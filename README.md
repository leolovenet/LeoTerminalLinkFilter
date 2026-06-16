# LeoTerminalLinkFilter

LeoTerminalLinkFilter is an Android Studio plugin that turns selected terminal or console stack trace output into clickable source links. It is mainly built for Android reverse engineering and debugging workflows that use Frida-style StackTrace logs, especially when the emitted path and method names come from decompiled or obfuscated Java sources.

## What It Does

- Registers a custom `ConsoleFilterProvider` for Android Studio and IntelliJ Platform consoles.
- Detects stack trace lines that contain a source path, class/method name, source file name, and optional overload markers.
- Resolves the target Java source file from the current project content roots.
- Falls back to fuzzy matching when the exact emitted path does not exist in the project.
- Supports decompiled comments such as `renamed from: ...` to locate obfuscated classes and methods.
- Adds clickable links for overload markers when multiple method definitions are found.

## Supported Log Shape

The filter is designed for lines shaped like:

```text
L23    at sources/com/abc/def/ghi/j.java  com.example.Clazz#a  (SourceFile)  [overloads: 1|2|3]
```

The important fields are:

- `sources/com/abc/def/ghi/j.java`: source path emitted by the log.
- `com.example.Clazz#a`: class name and method name separated by `#`.
- `(SourceFile)`: source file information.
- `[overloads: 1|2|3]`: optional overload labels.

When a matching file is found, the path part becomes clickable in the console. If the method has multiple candidate line numbers, overload labels can also become clickable.

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

## Development

Requirements:

- JDK 21
- Gradle Wrapper included in this repository
- Android Studio or IntelliJ Platform compatible SDK, downloaded by the IntelliJ Platform Gradle Plugin

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
