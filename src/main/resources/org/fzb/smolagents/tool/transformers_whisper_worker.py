"""Optional persistent Transformers Whisper process for smolagents-java."""

import base64
import contextlib
import io
import json
import os
import sys
import traceback


def emit(message):
    sys.__stdout__.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.__stdout__.flush()


def main():
    model_id = sys.argv[1]
    device_name = sys.argv[2]
    local_files_only = sys.argv[3].lower() == "true"
    try:
        with contextlib.redirect_stdout(sys.stderr):
            import soundfile as sf
            import torch
            from transformers import AutoProcessor, WhisperForConditionalGeneration

            if device_name == "auto":
                device = "cuda" if torch.cuda.is_available() else "cpu"
            elif device_name == "cuda" and not torch.cuda.is_available():
                raise RuntimeError("CUDA was requested but is not available")
            else:
                device = device_name
            auth_token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
            load_options = {"local_files_only": local_files_only}
            if auth_token:
                load_options["token"] = auth_token
            processor = AutoProcessor.from_pretrained(model_id, **load_options)
            model = WhisperForConditionalGeneration.from_pretrained(model_id, **load_options)
            model.to(device)
            model.eval()
        emit({"type": "ready", "device": device})
    except Exception as error:
        emit({"type": "startup_error", "error": f"{type(error).__name__}: {error}"})
        return 2

    for line in sys.stdin:
        try:
            request = json.loads(line)
            request_id = request.get("id")
            if request.get("type") == "shutdown":
                emit({"type": "shutdown"})
                return 0
            if request.get("type") == "reset":
                emit({"type": "reset"})
                continue
            if request.get("type") != "transcribe":
                emit({"type": "error", "id": request_id, "error": "Unsupported worker request"})
                continue

            raw = base64.b64decode(request["audio"], validate=True)
            with contextlib.redirect_stdout(sys.stderr):
                waveform, sampling_rate = sf.read(io.BytesIO(raw), dtype="float32", always_2d=False)
                if getattr(waveform, "ndim", 1) > 1:
                    waveform = waveform.mean(axis=1)
                inputs = processor(
                    waveform,
                    sampling_rate=sampling_rate,
                    return_tensors="pt",
                )
                inputs = {key: value.to(device) for key, value in inputs.items()}
                generation = {}
                language = request.get("language")
                if language:
                    generation["forced_decoder_ids"] = processor.get_decoder_prompt_ids(
                        language=language,
                        task="transcribe",
                    )
                with torch.inference_mode():
                    predicted = model.generate(inputs["input_features"], **generation)
                transcript = processor.batch_decode(predicted, skip_special_tokens=True)[0]
            emit({"type": "result", "id": request_id, "text": transcript})
        except Exception as error:
            emit({
                "type": "error",
                "id": request.get("id") if isinstance(request, dict) else None,
                "error": f"{type(error).__name__}: {error}",
            })
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BaseException:
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)
