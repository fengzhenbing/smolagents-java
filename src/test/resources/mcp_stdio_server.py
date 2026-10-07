import json
import sys


def reply(message_id, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": message_id, "result": result}) + "\n")
    sys.stdout.flush()


for line in sys.stdin:
    request = json.loads(line)
    method = request.get("method")
    request_id = request.get("id")
    params = request.get("params", {})

    if method == "notifications/initialized":
        continue
    if method == "initialize":
        reply(request_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "test-mcp-server", "version": "1.0"},
        })
    elif method == "tools/list":
        reply(request_id, {
            "tools": [
                {
                    "name": "echo",
                    "description": "Echo text.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"text": {"type": "string"}},
                        "required": ["text"],
                        "additionalProperties": False,
                    },
                    "outputSchema": {
                        "type": "object",
                        "properties": {"echoed": {"type": "string"}},
                    },
                },
                {
                    "name": "json_text",
                    "description": "Return JSON text.",
                    "inputSchema": {"type": "object"},
                },
                {
                    "name": "fail",
                    "description": "Return an MCP execution error.",
                    "inputSchema": {"type": "object"},
                },
            ],
        })
    elif method == "tools/call":
        name = params["name"]
        if name == "echo":
            text = params["arguments"]["text"]
            reply(request_id, {
                "content": [{"type": "text", "text": "echo:" + text}],
                "structuredContent": {"echoed": text},
                "isError": False,
            })
        elif name == "json_text":
            reply(request_id, {"content": [{"type": "text", "text": "{\"answer\":42}"}]})
        elif name == "fail":
            reply(request_id, {
                "content": [{"type": "text", "text": "remote execution failed"}],
                "isError": True,
            })
        else:
            reply(request_id, {"content": [{"type": "text", "text": "unknown tool"}], "isError": True})
    elif method == "prompts/list":
        reply(request_id, {
            "prompts": [
                {
                    "name": "greet",
                    "description": "Create a greeting prompt.",
                    "arguments": [{"name": "name", "description": "Name to greet.", "required": True}],
                }
            ],
        })
    elif method == "prompts/get":
        name = params.get("arguments", {}).get("name", "world")
        reply(request_id, {
            "description": "Rendered greeting.",
            "messages": [{"role": "user", "content": {"type": "text", "text": "Hello " + name}}],
        })
    elif method == "resources/list":
        reply(request_id, {
            "resources": [
                {
                    "uri": "test://fixture/readme",
                    "name": "readme",
                    "description": "Fixture resource.",
                    "mimeType": "text/plain",
                }
            ],
        })
    elif method == "resources/read":
        reply(request_id, {
            "contents": [
                {
                    "uri": params["uri"],
                    "mimeType": "text/plain",
                    "text": "MCP resource body",
                }
            ],
        })
    else:
        sys.stdout.write(json.dumps({
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {"code": -32601, "message": "Method not found"},
        }) + "\n")
        sys.stdout.flush()
