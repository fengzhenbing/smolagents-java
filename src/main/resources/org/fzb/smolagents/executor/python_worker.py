"""为 Java 执行器提供持久化、逐行 JSON 协议的本地 Python 工作进程。"""

import ast
import builtins
import contextlib
import io
import json
import keyword
import math
import sys
import traceback
import uuid


_REAL_EXEC = builtins.exec
_REAL_IMPORT = builtins.__import__
_NAMESPACE = {"__name__": "__main__"}
_TOOL_NAMES = set()


class _FinalAnswer(BaseException):
    """以不可被普通 Exception 捕获的信号中断当前动作。"""

    def __init__(self, value):
        self.value = value


def _emit(message):
    sys.__stdout__.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.__stdout__.flush()


def _json_value(value, depth=0):
    if depth > 64:
        raise ValueError("Python value is nested too deeply to serialize")
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite floating-point values are not valid JSON")
        return value
    if isinstance(value, (list, tuple)):
        return [_json_value(item, depth + 1) for item in value]
    if isinstance(value, dict):
        if not all(isinstance(key, str) for key in value):
            raise TypeError("JSON object keys must be strings")
        return {key: _json_value(item, depth + 1) for key, item in value.items()}
    raise TypeError("value is not JSON serializable")


def _code_builtins(authorized_imports):
    allowed = set(authorized_imports)

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        root = name.split(".", 1)[0]
        if "*" not in allowed and root not in allowed:
            raise ImportError(f"Import of {root} is not allowed")
        return _REAL_IMPORT(name, globals, locals, fromlist, level)

    namespace = dict(vars(builtins))
    namespace["__import__"] = guarded_import
    for name in ("breakpoint", "compile", "eval", "exec", "input", "open"):
        namespace.pop(name, None)
    return namespace


def _tool_function(name, schema):
    def invoke(*args, **kwargs):
        properties = schema.get("properties", {})
        property_names = list(properties.keys())
        if len(args) == 1 and isinstance(args[0], dict) and not kwargs:
            arguments = args[0]
        else:
            if len(args) > len(property_names):
                raise TypeError(f"{name} received too many positional arguments")
            arguments = dict(zip(property_names, args))
            for key, value in kwargs.items():
                if key in arguments:
                    raise TypeError(f"{name} received multiple values for {key}")
                arguments[key] = value
        call_id = "python-" + uuid.uuid4().hex
        _emit({
            "type": "tool_call",
            "callId": call_id,
            "name": name,
            "arguments": _json_value(arguments),
        })
        line = sys.stdin.readline()
        if not line:
            raise RuntimeError("Java tool bridge closed before returning a result")
        response = json.loads(line)
        if response.get("callId") != call_id:
            raise RuntimeError("Java tool bridge returned a mismatched call ID")
        if not response.get("ok"):
            raise RuntimeError(response.get("error", "Java tool execution failed"))
        return response.get("value")

    return invoke


def _source_tree(code):
    tree = ast.parse(code, filename="<smolagents-code>", mode="exec")
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        expression = tree.body[-1]
        tree.body[-1] = ast.Assign(
            targets=[ast.Name(id="__smolagents_result__", ctx=ast.Store())],
            value=expression.value,
        )
        ast.copy_location(tree.body[-1], expression)
        ast.fix_missing_locations(tree)
    return tree


def _line_number(error):
    if isinstance(error, SyntaxError):
        return error.lineno
    frames = [
        frame.lineno
        for frame in traceback.extract_tb(error.__traceback__)
        if frame.filename == "<smolagents-code>"
    ]
    return frames[-1] if frames else None


def _truncate(text, maximum):
    if maximum is None or len(text) <= maximum:
        return text
    marker = "\n[输出已截断]"
    return text[: max(0, maximum - len(marker))] + marker


def _variables_snapshot():
    result = {}
    for name, value in _NAMESPACE.items():
        if name.startswith("_") or name in ("final_answer", "__builtins__"):
            continue
        try:
            result[name] = _json_value(value)
        except (TypeError, ValueError, RecursionError):
            continue
    return result


def _execute(command):
    global _NAMESPACE, _TOOL_NAMES
    for name, value in command.get("variables", {}).items():
        if not name.isidentifier() or keyword.iskeyword(name) or name.startswith("__smolagents_"):
            raise ValueError(f"Invalid Python variable name: {name}")
        _NAMESPACE[name] = value

    authorized_imports = command.get("authorizedImports", [])
    _NAMESPACE["__builtins__"] = _code_builtins(authorized_imports)
    schemas = command.get("toolSchemas", {})
    for old_name in _TOOL_NAMES:
        _NAMESPACE.pop(old_name, None)
    _TOOL_NAMES = set(schemas)
    for name, schema in schemas.items():
        if name.isidentifier():
            _NAMESPACE[name] = _tool_function(name, schema)

    def final_answer(*args, **kwargs):
        if len(args) > 1:
            raise TypeError("final_answer accepts at most one positional argument")
        if args and "answer" in kwargs:
            raise TypeError("final_answer received answer more than once")
        value = args[0] if args else kwargs.get("answer")
        raise _FinalAnswer(value)

    _NAMESPACE["final_answer"] = final_answer
    output = io.StringIO()
    value = None
    is_final_answer = False
    error = None
    error_type = None
    line_number = None
    try:
        tree = _source_tree(command["code"])
        with contextlib.redirect_stdout(output):
            _REAL_EXEC(compile(tree, "<smolagents-code>", "exec"), _NAMESPACE, _NAMESPACE)
        value = _NAMESPACE.pop("__smolagents_result__", None)
    except _FinalAnswer as signal:
        is_final_answer = True
        value = signal.value
    except BaseException as failure:
        error_type = type(failure).__name__
        error = f"{type(failure).__name__}: {failure}"
        line_number = _line_number(failure)
    finally:
        _NAMESPACE.pop("__smolagents_result__", None)
        _NAMESPACE["final_answer"] = final_answer

    stdout = _truncate(output.getvalue(), command.get("maxOutputChars"))
    if error is not None:
        _emit({
            "type": "result",
            "ok": False,
            "stdout": stdout,
            "errorType": error_type or "PythonError",
            "error": error,
            "lineNumber": line_number,
            "variables": _variables_snapshot(),
        })
        return

    try:
        json_result = _json_value(value)
        has_json_result = True
    except (TypeError, ValueError, RecursionError):
        json_result = repr(value)
        has_json_result = False
    _emit({
        "type": "result",
        "ok": True,
        "stdout": stdout,
        "value": json_result,
        "valueIsJson": has_json_result,
        "isFinalAnswer": is_final_answer,
        "variables": _variables_snapshot(),
    })


def main():
    """逐行处理执行、重置和关闭命令，保留当前解释器命名空间。"""
    _emit({"type": "ready"})
    for line in sys.stdin:
        try:
            command = json.loads(line)
            kind = command.get("type")
            if kind == "execute":
                _execute(command)
            elif kind == "reset":
                global _NAMESPACE, _TOOL_NAMES
                _NAMESPACE = {"__name__": "__main__"}
                _TOOL_NAMES = set()
                _emit({"type": "reset"})
            elif kind == "shutdown":
                _emit({"type": "closed"})
                return
            else:
                _emit({"type": "protocol_error", "error": "unknown command"})
        except BaseException as error:
            _emit({"type": "protocol_error", "error": f"{type(error).__name__}: {error}"})


main()
