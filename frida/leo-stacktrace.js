/*
 * Frida-side stack trace formatter for LeoTerminalLinkFilter.
 *
 * Use inside Java.perform(...). The output is intentionally shaped for the
 * Android Studio plugin in this repository:
 *
 *   12 sources/com/example/Foo.java  com.example.Foo#a  (Foo.java#34) [sig: (java.lang.String,int):boolean] [overloads: 1 | 2]
 *
 * getStackTrace() does not expose method parameter types. The optional
 * currentFrame argument lets a hook pass the exact overload signature for the
 * currently hooked Java method, while older stack frames still fall back to
 * method-name matching and overload links.
 *
 * stackTraceForOverload(className, methodName, overload) is the strict form.
 * stackTraceForOverload(overload) is a convenience form that infers the frame
 * by scanning the current Java stack for the first method with the same
 * overload signature.
 */
(function (root) {
  "use strict";

  function typeName(type) {
    if (!type) return "";
    return type.className || type.name || type.toString();
  }

  function overloadSignature(overload) {
    if (!overload) return null;

    var args = [];
    var argumentTypes = overload.argumentTypes || [];
    for (var i = 0; i < argumentTypes.length; i++) {
      args.push(typeName(argumentTypes[i]));
    }

    var returnType = typeName(overload.returnType);
    return "(" + args.join(",") + ")" + (returnType ? ":" + returnType : "");
  }

  function frameFromOverload(className, methodName, overload) {
    var signature = overloadSignature(overload);
    if (!signature) return null;
    return {
      className: className,
      methodName: methodName,
      signature: signature,
    };
  }

  function sourceLocation(fileName, lineNumber) {
    if (!fileName) return "Unknown Source";
    if (lineNumber !== null && lineNumber !== undefined && lineNumber >= 0) {
      return fileName + "#" + lineNumber;
    }
    return fileName;
  }

  function overloadCount(className, methodName) {
    try {
      var clazz = Java.use(className);
      var method = clazz[methodName];
      return method && method.overloads ? method.overloads.length : 0;
    } catch (_) {
      return 0;
    }
  }

  function hasOverloadSignature(className, methodName, signature) {
    try {
      var clazz = Java.use(className);
      var method = clazz[methodName];
      var overloads = method && method.overloads ? method.overloads : [];
      for (var i = 0; i < overloads.length; i++) {
        if (overloadSignature(overloads[i]) === signature) {
          return true;
        }
      }
    } catch (_) {
      return false;
    }
    return false;
  }

  function overloadMarker(count) {
    if (count <= 1) return "";

    var labels = [];
    for (var i = 1; i <= count; i++) {
      labels.push(i);
    }
    return " [overloads: " + labels.join(" | ") + "]";
  }

  function signatureMarker(frame, className, methodName, alreadyUsed) {
    if (alreadyUsed || !frame || !frame.signature) return "";
    if (frame.className || frame.methodName) {
      if (frame.className !== className || frame.methodName !== methodName) return "";
    } else if (!hasOverloadSignature(className, methodName, frame.signature)) {
      return "";
    }
    return " [sig: " + frame.signature + "]";
  }

  function stackTraceAdv(options) {
    options = options || {};

    var currentFrame = options.currentFrame || null;
    var includeHeader = options.includeHeader !== false;
    var includeOverloads = options.includeOverloads !== false;
    var buf = includeHeader ? ["stackTrace:"] : [];
    var Throwable = Java.use("java.lang.Throwable");
    var stack = Throwable.$new().getStackTrace();
    var signatureUsed = false;

    for (var i = 0; i < stack.length; i++) {
      var item = stack[i];
      var className = item.getClassName();
      var methodName = item.getMethodName();
      var fileName = item.getFileName();
      var lineNumber = item.getLineNumber();
      var level = stack.length - i;
      var levelText = level < 10 ? " " + level : "" + level;
      var path = "sources/" + className.replace(/\./g, "/") + ".java";
      var sig = signatureMarker(currentFrame, className, methodName, signatureUsed);

      if (sig) {
        signatureUsed = true;
      }

      var overloads = includeOverloads ? overloadMarker(overloadCount(className, methodName)) : "";
      buf.push(
        "  " +
          levelText +
          " " +
          path +
          "  " +
          className +
          "#" +
          methodName +
          "  (" +
          sourceLocation(fileName, lineNumber) +
          ")" +
          sig +
          overloads
      );
    }

    return buf.join("\n");
  }

  function stackTraceForOverload(className, methodName, overload, options) {
    if (typeof className !== "string") {
      options = methodName || {};
      overload = className;
      options.currentFrame = {
        signature: overloadSignature(overload),
      };
    } else {
      options = options || {};
      options.currentFrame = frameFromOverload(className, methodName, overload);
    }
    return stackTraceAdv(options);
  }

  var api = {
    frameFromOverload: frameFromOverload,
    overloadSignature: overloadSignature,
    stackTraceAdv: stackTraceAdv,
    stackTraceForOverload: stackTraceForOverload,
  };

  var globalObject = root || (typeof globalThis !== "undefined" ? globalThis : null);
  if (globalObject) {
    globalObject.LeoTerminalLinkFilter = api;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
