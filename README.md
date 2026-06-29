# LeoTerminalLinkFilter

LeoTerminalLinkFilter is an Android Studio plugin that turns selected terminal or console stack trace output into clickable source links. It is mainly built for Android reverse engineering and debugging workflows that use Frida-style StackTrace logs, especially when the emitted path and method names come from decompiled or obfuscated Java sources.

## What It Does

- Registers a custom `ConsoleFilterProvider` for Android Studio and IntelliJ Platform consoles.
- Detects stack trace lines that contain a source path, class/method name, source file name, and optional overload markers.
- Resolves the target Java source file from the current project content roots.
- Falls back to fuzzy matching when the exact emitted path does not exist in the project.
- Supports decompiled comments such as `renamed from: ...` to locate obfuscated classes and methods.
- Adds clickable links for overload markers when multiple method definitions are found.
- Can consume an optional Frida-provided method signature for the currently hooked overload.

## Supported Log Shape

The filter is designed for lines shaped like:

```text
L23    at sources/com/abc/def/ghi/j.java  com.example.Clazz#a  (SourceFile#23)  [sig: (java.lang.String,int):boolean] [overloads: 1|2|3]
```

The important fields are:

- `sources/com/abc/def/ghi/j.java`: source path emitted by the log.
- `com.example.Clazz#a`: class name and method name separated by `#`.
- `(SourceFile#23)`: source file and optional line number. The helper uses `#` instead of `:` to avoid Android Studio's built-in `File.java:line` hyperlink detection.
- `[sig: (java.lang.String,int):boolean]`: optional signature emitted by the Frida helper when the current hook knows the exact overload.
- `[overloads: 1|2|3]`: optional overload labels.

When a matching file is found, the path part becomes clickable in the console. If the method has multiple candidate line numbers, overload labels can also become clickable.
When a signature is present and matches one source declaration, the plugin jumps directly to that overload.

## Matching Strategy

1. Try to find the source file by exact relative path under the project content roots.
2. If exact matching fails, walk directories with a fuzzy path match:
   - exact directory name matches first;
   - suffix directory matches next;
   - Java files whose names match the emitted segment or source name are preferred.
3. Inspect Java files for decompiler comments like:

```java
/* renamed from: com.example.Clazz */
```

4. Locate method definitions by method name or nearby `renamed from:` comments.
5. If a `[sig: ...]` marker is present, prefer declarations whose parameter types match that signature.

## Frida Helper

This repository includes a pure Frida-side formatter at:

```text
frida/leo-stacktrace.js
```

Load it before your own hook script. With the Frida CLI, pass this helper first and your script second:

```bash
frida -U -f com.example.app -l frida/leo-stacktrace.js -l your-hook.js
```

If you prefer to ship a single script, paste or bundle `frida/leo-stacktrace.js` before the code that calls `LeoTerminalLinkFilter`.

For a normal stack trace, call it inside `Java.perform(...)`:

```js
console.log(LeoTerminalLinkFilter.stackTraceAdv());
```

When you are inside a specific overload hook, use the explicit `stackTraceForOverload(className, methodName, overload)` form so the current frame can include an exact signature:

```js
Java.perform(function () {
  var className = "com.example.Clazz";
  var methodName = "a";
  var Clazz = Java.use(className);
  var overload = Clazz[methodName].overload("java.lang.String", "int");

  overload.implementation = function (text, count) {
    console.log(LeoTerminalLinkFilter.stackTraceForOverload(className, methodName, overload));

    return overload.call(this, text, count);
  };
});
```

There is also a shorter convenience form:

```js
LeoTerminalLinkFilter.stackTraceForOverload(overload);
```

The convenience form reads the signature from `overload.argumentTypes` and `overload.returnType`, then marks the first Java stack frame whose overload list contains that signature. It is shorter, but can be ambiguous if another active frame has the same signature. Use the explicit form when correctness matters.

The lower-level equivalent of the explicit form is:

```js
LeoTerminalLinkFilter.stackTraceAdv({
  currentFrame: LeoTerminalLinkFilter.frameFromOverload("com.example.Clazz", "a", overload),
});
```

The lower-level form is only useful if you want to build or cache the current-frame metadata yourself.

Only the current hooked frame can be made signature-accurate this way. Older frames still depend on `Throwable.getStackTrace()`, which does not expose parameter types, so they keep the existing overload-candidate behavior.

## Development

Requirements:

- JDK 21
- Gradle Wrapper included in this repository
- Android Studio or IntelliJ Platform compatible SDK, downloaded by the IntelliJ Platform Gradle Plugin

If your default `java` is not JDK 21, set `JAVA_HOME` before running Gradle:

```bash
export JAVA_HOME=/path/to/jdk-21
```

Useful commands:

```bash
./gradlew runIde
./gradlew buildPlugin
./gradlew verifyPlugin
```

The generated plugin archive is written to:

```text
build/distributions/
```

## Project Layout

```text
src/main/kotlin/com/leolovenet/leoterminallinkfilter/
  LeoCustomFilter.kt
  LeoCustomFilterProvider.kt

src/main/resources/META-INF/
  plugin.xml
  pluginIcon.svg
```

## Notes

This plugin is intentionally narrow in scope: it focuses on making Frida/decompiled stack trace output easier to navigate while analyzing Android Java sources inside Android Studio.
