"""Deterministic JSONL stand-in for the optional Transformers Whisper worker."""

import base64
import json
import sys
import time


model_id = sys.argv[1]
device = sys.argv[2]
local_only = sys.argv[3]
inference_error_sent = False
if model_id == "startup-error":
    sys.stdout.write('{"type":"startup_error","error":"missing optional dependency"}\n')
    sys.stdout.flush()
    sys.exit(2)
if model_id == "startup-eof":
    sys.exit(2)
sys.stdout.write(json.dumps({
    "type": "ready",
    "device": device,
    "modelId": model_id,
    "localFilesOnly": local_only,
}) + "\n")
sys.stdout.flush()

for line in sys.stdin:
    request = json.loads(line)
    if request.get("type") == "shutdown":
        sys.stdout.write('{"type":"shutdown"}\n')
        sys.stdout.flush()
        break
    if request.get("type") == "reset":
        sys.stdout.write('{"type":"reset"}\n')
        sys.stdout.flush()
        continue
    if model_id == "hang":
        time.sleep(10)
    audio = base64.b64decode(request["audio"])
    if model_id == "mismatch":
        sys.stdout.write(json.dumps({
            "type": "result",
            "id": "different-request",
            "text": "wrong request",
        }) + "\n")
        sys.stdout.flush()
        continue
    if model_id == "invalid-response":
        sys.stdout.write("{invalid json}\n")
        sys.stdout.flush()
        continue
    if model_id == "empty-transcript":
        text = " "
    elif model_id == "error-response" and not inference_error_sent:
        sys.stdout.write(json.dumps({
            "type": "error",
            "id": request["id"],
            "error": "fixture inference error",
        }) + "\n")
        sys.stdout.flush()
        inference_error_sent = True
        continue
    else:
        text = f"{model_id}:{device}:{local_only}:{request.get('language')}:{len(audio)}"
    sys.stdout.write(json.dumps({
        "type": "result",
        "id": request["id"],
        "text": text,
    }) + "\n")
    sys.stdout.flush()
