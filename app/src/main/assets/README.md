# Wake-word models (openWakeWord — no key, no account, no network)

This folder holds the ONNX models the app runs fully on-device:

- `melspectrogram.onnx` + `embedding_model.onnx` — openWakeWord's shared audio
  frontend (from the v0.5.1 release assets). Do not remove.
- `hey_jarvis_v0.1.onnx` — official openWakeWord model, bundled as the fallback
  so the whole pipeline (listen → wake → open assistant) is testable.
- `hey_ron.onnx` — YOUR trained "Hey Ron" model goes here (drop it in, rebuild).
  When present, the app uses it instead of the Jarvis fallback.

## How to train "Hey Ron" (free, ~30–60 min, one time)

1. Open the official openWakeWord training notebook in Google Colab (free GPU):
   https://github.com/dscripka/openWakeWord — follow the training docs/notebooks.
   Community shortcut: the `openwakeword-trainer` repo has a streamlined notebook.
2. Generate positive samples for the phrase "hey ron" with the notebook's TTS
   step (it synthesizes many voices automatically — you don't record anything).
3. Train, download the resulting `.onnx` file, rename it to `hey_ron.onnx`,
   drop it in this folder, rebuild the APK.
4. In the app, lower/raise the detection-threshold slider if you get false
   accepts (too sensitive) or missed wakes (not sensitive enough).

Tip: "hey ron" is short (2 syllables). If it false-triggers a lot, retrain with
a longer phrase like "hey ron listen" — more syllables = fewer false accepts.
