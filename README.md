# Hey Ron

Always-on voice hotword for Android. Say the wake word and your default assistant
(Muse) opens already listening — fully hands-free, no tap needed.

- Wake-word engine: **openWakeWord** (on-device ONNX, no account, no API key, no network)
- Foreground mic service with persistent notification (Android requires it for background mic)
- On wake: high-priority notification with full-screen intent firing `ACTION_VOICE_COMMAND`
- Auto-pauses during phone calls, restarts on boot
- `targetSdk 33` is deliberate: Android 14+ restricts full-screen intents for apps
  targeting API 34+; 33 keeps it working with just the manifest permission

## Current build

Listens for **"Hey Jarvis"** (bundled `hey_jarvis_v0.1.onnx`). No "hey ron" model exists in
the wild yet — train one via openWakeWord's free Colab notebook and drop the resulting
`.onnx` into `app/src/main/assets/` (replacing the jarvis file + updating the filename
reference in `OpenWakeWordEngine.kt`).

## Releases / updates

Push a tag `vX.Y` → GitHub Actions builds, signs, and publishes the APK as a Release.
On the phone, **Obtainium** watches this repo's releases and installs updates
(signature stays consistent — same keystore key for every release).

Required repo secrets (Settings → Secrets → Actions):
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Build locally

Needs JDK 17, Android SDK (compileSdk 34), Gradle 8.10.2:

```sh
gradle :app:assembleRelease
```

Release signing reads `keystore.properties` (see `.gitignore` — never commit it).
The keystore key must **never** change or Android will refuse updates.

## Project notes

`BUILD_STATE.md` has the full build history: Porcupine → openWakeWord engine swap,
exact audio-frontend parameters, and environment lessons.
