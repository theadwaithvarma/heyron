package com.adwaithvarma.heyron

/**
 * Abstraction over the wake-word engine so engines stay swappable.
 * v1 shipped Porcupine; v2 is openWakeWord (no API key, no account wall).
 * Any future engine just implements this interface.
 */
interface WakeWordEngine {
    /** Human-readable keyword, e.g. "Hey Ron" — shown in the UI. */
    val keywordName: String

    /** Register the callback fired on the audio thread when the keyword is heard. */
    fun setOnWakeListener(listener: () -> Unit)

    /** Start capturing audio and scoring. Safe to call after stop(). */
    fun start()

    /** Stop capturing/scoring, keeping the models loaded. */
    fun stop()

    /** Release everything (audio recorder, native sessions). */
    fun release()
}
