# Hey Ron — build state (updated 2026-10-07, v2.0)

## What it is
Native Android hotword app for Adwaith, "Hey Ron", package `com.adwaithvarma.heyron`.
Say "Hey Ron" anywhere → the phone fires `ACTION_VOICE_COMMAND`, which opens the
default assistant app (Muse on his phone) with voice input already toggled.
Fully hands-free loop, no tap needed. Plain Views, no Compose.

## Status: v2.0 BUILD COMPLETE (verified)
- APK: `~/workspace/your_files/HeyRon.apk` (36 MB — onnxruntime native lib is the bulk), built 2026-10-07 ~16:14 UTC
- `apksigner verify` → VERIFIES (same key as v1: CN=Adwaith Varma — updates install cleanly over v1.0)
- `aapt dump badging` → package `com.adwaithvarma.heyron`, label "Hey Ron",
  versionCode 2 / versionName 2.0, minSdk 26, **targetSdk 33**, compileSdk 34
- Permissions: RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
  POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT, READ_PHONE_STATE, RECEIVE_BOOT_COMPLETED
- Native libs: `libonnxruntime.so` arm64-v8a only (M12 is arm64; 33 MB .so is why the APK is 36 MB)
- Pipeline smoke-tested in Python against the real .onnx files (see below)
- **NOT tested on a real device.** Nothing is claimed working until it installs
  and runs on Adwaith's Galaxy M12.

## v2.0: engine swap — Porcupine is DEAD, openWakeWord in
**Why:** Picovoice Console requires a company email to sign up — it blocks Gmail
and even academic domains (confirmed via their GitHub issues). Adwaith has neither,
so Porcupine is unusable for him. openWakeWord is fully on-device, MIT-licensed,
no key, no account, no network.
**What changed:**
- Removed: `ai.picovoice:porcupine-android` dep + all Porcupine code + the
  AccessKey placeholder flow (`local.properties` no longer carries secrets).
- Added: `com.microsoft.onnxruntime:onnxruntime-android:1.30.0` (fetched into
  `~/workspace/local-maven-repo` 2026-10-07 via the egress proxy; self-contained,
  no transitives).
- New files: `WakeWordEngine.kt` (interface — engines stay swappable),
  `OpenWakeWordEngine.kt` (the pipeline). `WakeService` now drives the engine
  interface; `MainActivity` checklist dropped the key row and gained a
  detection-threshold slider (0.10–0.90, default 0.50, persisted).
- APK grew 2.3 → 36 MB (onnxruntime native lib). arm64-v8a only.

## openWakeWord pipeline params (from source, NOT guessed)
Source: `openwakeword/utils.py` (AudioFeatures) + `openwakeword/model.py` (Model),
cloned 2026-10-07 from github.com/dscripka/openWakeWord.
- Audio: 16 kHz mono int16 PCM, processed in 1280-sample (80 ms) chunks.
- `melspectrogram.onnx`: input name `input`, shape [1, N]; fed the last
  1280 + 480 samples (the +480 = 160*3 STFT overlap, exactly as
  `_streaming_melspectrogram` does); output [time, 1, 32] squeezed → 32 mel
  bins, ~97 frames/sec (10 ms hop). Fixed transform afterwards: `spec/10 + 2`
  (the default `melspec_transform` in `_get_melspectrogram`).
- `embedding_model.onnx`: input name `input_1`, shape [1, 76, 32, 1] (last 76
  mel frames) → 96-dim embedding, one per 80 ms chunk. Feature buffer keeps
  the last 120 (~9.6 s).
- Wake-word `.onnx`: input name read dynamically from the session
  (hey_jarvis_v0.1's is literally `x.1` — never hardcode), shape [1, 16, 96]
  (last 16 embeddings); output [1, 1] → score in [0, 1].
- First 5 predictions forced to 0 while buffers warm up (mirrors model.py).
- Threshold default 0.5 = openWakeWord's own recommended value
  (their `_get_positive_prediction_frames` default and examples).
- **Smoke test 2026-10-07:** ran the exact pipeline in Python (onnxruntime CPU)
  over 4 s of random noise: 8 mel frames/chunk, 96-dim embeddings, scores
  0.0000–0.0003 — no false triggers, shapes all match the Kotlin port.

## Wake-word model provenance
- **Bundled:** `hey_jarvis_v0.1.onnx` (official openWakeWord v0.5.1 release
  asset) as the fallback so the full pipeline is testable out of the box.
  Checked the Home Assistant community collection
  (fwartner/home-assistant-wakewords-collection, cloned 2026-10-07): **no
  "hey ron" model exists** there (closest: "ronaldo", "jarvis").
- **"Hey Ron" training: NOT done locally** — no GPU here, and the pipeline
  needs multi-GB dataset downloads + hours of CPU training. Documented path
  for Adwaith instead (see assets/README.md): official openWakeWord training
  notebook on free Colab GPU, TTS-generated samples (~30–60 min), drop the
  result in as `app/src/main/assets/hey_ron.onnx`, rebuild. Tip: prefer a
  3+ syllable phrase ("hey ron listen") — fewer false accepts.

## File map (~/workspace/heyron/)
- `settings.gradle`, `build.gradle`, `gradle.properties` — offline local-Maven
  repo pattern; Gradle 8.10.2, AGP 8.5.2, Kotlin 2.0.21 (do NOT upgrade)
- `local.properties` — no secrets anymore (kept for AGP expectations)
- `keystore.properties` (chmod 600) + `heyron-release.keystore` — release signing
- `app/build.gradle` — targetSdk 33 (deliberate, see below), minifyEnabled +
  shrinkResources, `ndk { abiFilters 'arm64-v8a' }`, signingConfigs.release
- `app/proguard-rules.pro` — keeps for onnxruntime, engine, service, receiver, activity
- `app/src/main/AndroidManifest.xml` — permissions + MainActivity (launcher) +
  WakeService (`foregroundServiceType="microphone"`) + BootReceiver
- `app/src/main/assets/` — `melspectrogram.onnx`, `embedding_model.onnx`,
  `hey_jarvis_v0.1.onnx` (fallback), `README.md` (training instructions)
- `app/src/main/java/com/adwaithvarma/heyron/`
  - `WakeWordEngine.kt` — engine interface (start/stop/release/setOnWakeListener)
  - `OpenWakeWordEngine.kt` — openWakeWord pipeline (AudioRecord 16 kHz mono,
    80 ms chunks, the three ONNX stages, threshold, 5-frame warmup)
  - `MainActivity.kt` — status, Start/Stop, threshold slider (0.10–0.90),
    checklist (model file? notifications allowed?)
  - `WakeService.kt` — foreground mic service; on wake → HIGH-priority
    notification with full-screen intent → ACTION_VOICE_COMMAND; tap fallback;
    PhoneStateListener pauses on OFFHOOK, resumes on IDLE
  - `BootReceiver.kt` — restarts the service after reboot if it was enabled
- `app/src/main/res/layout/activity_main.xml`, `values/strings.xml`

## Adwaith's setup steps (v2.0 — no key, no account needed)
1. **Install** `HeyRon.apk` on the M12 (installs over v1.0 as an update — same key).
2. Open it, grant **microphone + notification + phone-state** permissions, tap
   **"Start listening"**. It listens for **"Hey Jarvis"** until step 3 is done
   (that's the bundled fallback proving the pipeline works end to end).
3. **(Optional, for the real phrase)** train "hey ron" via the free Colab
   notebook (instructions in `app/src/main/assets/README.md`), send the
   resulting file over, and I'll drop it in as `hey_ron.onnx` and rebuild.
4. Tune the **detection-threshold slider** in the app if needed: false alarms
   → slide down; missed wakes → slide up.
5. Allow **auto-start** in One UI device settings or the boot-restart may be suppressed.

## Keystore — NEVER CHANGE THIS KEY
- `~/workspace/heyron/heyron-release.keystore` (JKS, RSA 2048, 25y, alias `heyron`)
- Credentials in `~/workspace/heyron/keystore.properties` (chmod 600).
- `app/build.gradle` signs every release build with it automatically.
- Android refuses to install an update signed with a different key — this exact
  keystore must be used for all future HeyRon builds, forever.

## Why targetSdk 33 (not 34/35)
Android 14+ gates full-screen intents behind a user-granted Settings permission
for apps targeting API 34+. Targeting 33 keeps the wake notification's
full-screen intent working with just the manifest permission — essential for a
sideloaded personal app that must pop Muse open from the lock screen.

## Build environment lessons (2026-10-07 — future builds read this)
1. **Gradle daemon handshake dies in this container** with "Could not receive a
   message from the daemon". Root cause: the JVM binds the daemon socket to the
   IPv4-mapped IPv6 address `::ffff:127.0.0.1`, and the container's network
   policy intercepts/hijacks TCP to that address (verified with a raw-socket
   repro: the connection gets a policy message instead of reaching the listener).
   Plain `127.0.0.1` (AF_INET) works fine. Fix: `org.gradle.jvmargs=
   -Djava.net.preferIPv4Stack=true` in `gradle.properties` AND
   `GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"` on every gradle invocation.
   Without both, no Gradle build can run here at all.
2. **Keystore: use JKS with a simple alphanumeric password.** A PKCS12 keystore
   whose password contained base64 special chars failed AGP signing with
   "Given final block not properly padded" even though keytool accepted it.
   JKS + `[A-Za-z0-9]` password works through keytool, jarsigner, and AGP.
3. AGP 8.x needs `android.buildFeatures.buildConfig true` explicitly when using
   `buildConfigField`, despite defaults suggesting otherwise. (v2.0 removed
   BuildConfig usage entirely with the AccessKey.)
4. onnxruntime-android 1.30.0 is self-contained (no transitives); its AAR carries
   ~33 MB native libs per ABI — filter to `arm64-v8a` or the APK balloons past
   59 MB. ORT Java API notes: input names via `session.inputNames` (wake models
   use arbitrary names like `x.1` — never hardcode); `inputInfo[name].info as
   TensorInfo` for shapes; always `.use {}` OrtSession.Result tensors (native
   memory leaks otherwise).
5. Porcupine 4.0.2 was removed in v2.0 (company-email signup wall). Its old notes
   are superseded — do not re-add it.

## v2.1 (2026-10-07, 23:00 IST) — instant, DND-proof wake launch
- Problem: on wake, the full-screen intent did not auto-launch while the phone
  was in use (Android demotes FSI to heads-up when interactive; DND suppresses
  it too). Adwaith had to tap the notification.
- Fix: request SYSTEM_ALERT_WINDOW ("Display over other apps"). Holding it
  exempts the service from background-activity-start restrictions, so
  onWakeWord() now calls startActivity(ACTION_VOICE_COMMAND) directly when
  Settings.canDrawOverlays() is true (try/catch -> falls back to the FSI
  notification). No notification is posted on the direct path.
- Engine restart after wake is now delayed 1.5s via main-looper Handler (was
  immediate) so the assistant session isn't cut off.
- MainActivity: setup checklist gained a "Display over other apps" row + a
  grant button deep-linking to Settings.ACTION_MANAGE_OVERLAY_PERMISSION
  (SAW can't be requested via requestPermissions()).
- Samsung Routines considered and rejected: its "Notification received"
  trigger exists, but THEN can only open an app (Muse's main screen, not voice
  mode). The overlay path fires the real voice intent with zero manual setup.
- Version: 2.1, versionCode 201 (CI maps tag vX.Y -> X*100+Y).

## v2.2 (2026-10-07, 23:35 IST) — "make like" the reference project
- Adwaith rejected v2.1's permission-dance framing; asked to match similar
  projects. Studied kangrio/assistant (offline hotword + launch AI assistant):
  it uses NO full-screen intent in the wake path — just SYSTEM_ALERT_WINDOW +
  direct startActivity(), i.e. exactly v2.1's mechanism. So v2.1 stays; v2.2
  adds the rest of their pattern:
  - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + checklist row/button firing the
    system "always run in background" dialog (Samsung dozes mic services).
  - Wake "ding" via ToneGenerator on successful direct launch.
  - Considered their setPackage() explicit targeting, but RoleManager has no
    public getRoleHolders() (verified against android-34/35 jars — only
    isRoleHeld/isRoleAvailable/createRequestRoleIntent exist); they get the
    package from their own onboarding picker. Skipped — the implicit intent
    already resolves to the user-set default.
- Version: 2.2, versionCode 202.

## v2.3 (2026-10-07, 23:30 IST) — the actual bug: wrong intent channel
- Adwaith: wake word showed a picker with only Google and Perplexity, no Muse.
  Root cause: Muse does NOT handle ACTION_VOICE_COMMAND (that's the legacy
  headset voice channel; only Google/Perplexity listen on it). Muse implements
  the assist entry point (ACTION_ASSIST, long-press-home), which opens it
  already listening. We were knocking on the wrong door — not a weak app.
- onWakeWord() now tries ACTION_ASSIST first, then ACTION_VOICE_COMMAND as
  fallback, on both the direct-launch path and the notification tap
  PendingIntent. (Matches kangrio/assistant's per-assistant channel split.)
- Version: 2.3, versionCode 203.

## v2.4 (2026-10-07) — the designed version
- Built from ALGORITHM_VNEXT.md (deep research: Muse APK teardown
  com.facebook.aura/HatchVoiceInteractionService; API-33 mechanisms; Samsung
  One UI 5.1 battery behavior). No more speculative versions.
- onWakeWord() pipeline: engine.stop() sync → ding immediately → direct
  startActivity(ASSIST, then VOICE_COMMAND) if overlay granted → else FSI
  notification (tap now routes through new WakeTapReceiver so the wake log
  records TAP). 3 s debounce after any launch attempt.
- Mic re-arm at 4 s (was 1.5 s) with exponential backoff 1/2/4/8 s; persistent
  failure marks the engine DEGRADED (persistent notification + checklist) instead
  of dying silently. resumeAfterCall() reuses the same backoff.
- WakeService companion: logWake()/lastWakeSummary() (timestamp, path
  DIRECT/FSI_POSTED/TAP, defeat detail); defaultAssistantLabel() reads
  Settings.Secure "assistant" key; isWakeChannelHigh(); degraded/paused prefs.
- MainActivity: 7-row diagnostics checklist — default assistant (+ settings
  button), overlay, battery (+ Samsung never-sleeping path note, shown only on
  Samsung), notifications (+ wake-channel importance check), DND state, last
  wake log, engine state. Layout wrapped in ScrollView.
- New WakeTapReceiver (manifest-registered, exported=false).
- Version: 2.4, versionCode 204. Signed with existing release key (verified via
  apksigner, CN=Adwaith Varma). SHA-256:
  a55208e06b3490225febf664402c267968237df0cd67d7f97616cf18f347126e
- Local build notes: no gradlew wrapper in repo — use
  ~/workspace/gradle-8.10.2/bin/gradle with JAVA_HOME=~/workspace/jdk17/jdk-17.0.20.1+1
  and GRADLE_OPTS="-Djava.net.preferIPv4Stack=true".
