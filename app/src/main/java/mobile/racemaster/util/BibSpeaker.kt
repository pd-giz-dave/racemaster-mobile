package mobile.racemaster.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mobile.racemaster.data.settings.BibVoice

// Surfaced by SpeakBibsAloudControl so an operator can tell why nothing's being spoken, rather
// than silently getting no audio feedback at all — see BibSpeaker's own class doc for why
// UNAVAILABLE can't just be fixed in-app with one tap the way the rest of this feature is.
enum class BibSpeakerStatus { UNKNOWN, READY, UNAVAILABLE }

/**
 * Speaks a just-recorded bib number aloud, digit by digit (e.g. "123" -> "one two three"),
 * loudly, in a requested gendered voice — see TODO.md and SettingsRepository.speakBibsAloud's
 * own doc for why: useful at a quiet on-course checkpoint, not practical at a noisy finish line,
 * hence this being gated behind a persistent operator setting rather than always-on.
 *
 * Gender is realized via setPitch() layered on whatever voice/engine the device already
 * defaults to, NOT by enumerating TextToSpeech.getVoices() and filtering by name/gender
 * metadata — that's unreliable across OEMs/engines, especially on the cheap, older devices this
 * app actually runs on (a Samsung XCover4 on API 28, a Cubot on API 27), which may expose only
 * one voice with no gender metadata at all.
 *
 * "Loud" mirrors Beeper/ClickSound's own established trick: force STREAM_ALARM to max volume
 * and route speech onto that stream via KEY_PARAM_STREAM, so it's heard outdoors regardless of
 * the device's media/ringer volume or silent/vibrate mode.
 *
 * TextToSpeech's own constructor is asynchronous — it binds to a system TTS service and only
 * becomes usable once the init callback fires, which can take over a second on an old device. A
 * speak() call arriving before that (e.g. an operator typing a bib within the first second
 * after the app/process starts) is queued (one pending call only — the latest wins) and flushed
 * as soon as init completes, rather than silently dropped. If the device has no usable TTS
 * engine at all, init reports failure and speak() just no-ops from then on — never crashes.
 *
 * Confirmed live on a Cubot (API 27): the engine can be present but have been left explicitly
 * *disabled* at the OS package level (PackageManager enabled-state DISABLED_USER) — e.g. by an
 * OEM's own bloatware-reduction tool — in which case init fails immediately (status=ERROR) with
 * nothing else logged anywhere, indistinguishable from "no engine at all" without checking
 * PackageManager directly. A normal app has no API to flip another app's package back to enabled
 * (PackageManager.setApplicationEnabledSetting on someone else's package needs a
 * signature/system permission this app will never hold) — the same "no in-app one-tap fix
 * exists" situation as KeepScreenOnStatus's own battery-optimization toggle already documents.
 * [status] and [enginePackageName] exist so SpeakBibsAloudControl can at least name the problem
 * and jump to that package's own App Info screen, where an operator can tap Enable themselves —
 * and [retryIfUnavailable] exists because TextToSpeech's own one-shot init never retries itself:
 * confirmed live, flipping the engine back on via that App Info screen and returning to this app
 * left [status] stuck UNAVAILABLE forever, looking like the fix had failed, until something
 * explicitly re-attempts construction from scratch.
 */
class BibSpeaker(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager = context.packageManager
    @Volatile private var ready = false
    @Volatile private var available = true
    @Volatile private var pending: Pair<String, BibVoice>? = null

    private val _status = MutableStateFlow(BibSpeakerStatus.UNKNOWN)
    val status: StateFlow<BibSpeakerStatus> = _status.asStateFlow()

    private var tts: TextToSpeech = createTts()

    private fun createTts(): TextToSpeech = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
        Log.i(TAG, "onInit status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
        available = status == TextToSpeech.SUCCESS
        if (available) {
            // The spoken text (toSpokenDigits()) is always hardcoded English words, regardless
            // of device locale — must explicitly request it rather than trusting whatever
            // language the engine happens to default to, which on some devices/engines has no
            // voice data installed for at all (speak() then just silently produces nothing, no
            // exception, nothing in logcat under this class's own tag). UK, not US, per TODO.md.
            val langResult = tts.setLanguage(Locale.UK)
            Log.i(TAG, "setLanguage(UK) result=$langResult")
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                available = false
            }
        }
        ready = true
        _status.value = if (available) BibSpeakerStatus.READY else BibSpeakerStatus.UNAVAILABLE
        if (available) pending?.let { (digits, voice) -> speakNow(digits, voice) }
        pending = null
    })

    /** Re-attempts initialization from scratch — a no-op unless [status] is currently
     *  UNAVAILABLE, so it's safe to call speculatively on every resume (see
     *  SpeakBibsAloudControl's own LifecycleResumeEffect — the exact same "re-check whatever
     *  might have changed while away in system Settings" pattern KeepScreenOnStatus already
     *  established for the battery-optimization exemption). */
    fun retryIfUnavailable() {
        if (_status.value != BibSpeakerStatus.UNAVAILABLE) return
        ready = false
        runCatching { tts.shutdown() }
        tts = createTts()
    }

    // The package actually backing (or expected to back) synthesis — which engine a device uses
    // varies by OEM (a Samsung phone's own SMT engine vs. Google's on the Cubot above), so
    // SpeakBibsAloudControl's own "fix it" action needs this rather than a hardcoded guess.
    // tts.defaultEngine alone isn't enough: confirmed live on the Cubot, once its engine package
    // is DISABLED, defaultEngine returns null — PackageManager excludes disabled components from
    // its normal intent resolution, which is exactly the one situation this needs to see through.
    // MATCH_DISABLED_COMPONENTS on a direct query for the TTS_SERVICE intent finds it regardless
    // of enabled state, which is also exactly why this can't just always be used instead of
    // defaultEngine: on a device with more than one engine installed, it doesn't single out
    // whichever one is actually the OPERATOR'S chosen default the way defaultEngine does — so
    // defaultEngine is tried first and this is only the fallback for when that itself goes quiet.
    fun enginePackageName(): String? {
        tts.defaultEngine?.let { return it }
        return runCatching {
            packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            ).firstOrNull()?.serviceInfo?.packageName
        }.getOrNull()
    }

    /** No-ops safely (never throws) if TTS isn't ready or isn't available on this device. */
    fun speak(digitsString: String, voice: BibVoice) {
        if (!ready) {
            pending = digitsString to voice
            return
        }
        if (!available) return
        speakNow(digitsString, voice)
    }

    private fun speakNow(digitsString: String, voice: BibVoice) {
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            tts.setPitch(if (voice == BibVoice.MALE) MALE_PITCH else FEMALE_PITCH)
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            val words = digitsString.toSpokenDigits()
            // speak() itself never throws on failure — it returns ERROR instead (e.g. engine
            // busy, or a rejected utterance), which runCatching alone can't see — log it
            // explicitly or a silent failure here is otherwise undiagnosable from outside.
            val result = tts.speak(words, TextToSpeech.QUEUE_FLUSH, params, "bib_$digitsString")
            Log.i(TAG, "speak(\"$words\") result=$result (SUCCESS=${TextToSpeech.SUCCESS})")
        }.onFailure { Log.w(TAG, "speakNow threw", it) }
    }

    // Kept for symmetry with Beeper.release() but currently has no call site: bibSpeaker is an
    // AppContainer-lifetime singleton, not owned per-ViewModel, and this app has no app-wide
    // teardown hook to call it from (see RacemasterApplication — no onTerminate override, which
    // real devices never call anyway). Every other AppContainer singleton already goes
    // un-torn-down the same way, released only when the process dies.
    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object {
        const val TAG = "BibSpeaker"
        const val MALE_PITCH = 0.7f
        const val FEMALE_PITCH = 1.3f
    }
}

private val DIGIT_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

/** "007" -> "zero zero seven" — one word per character, so the TTS engine reads each digit
 *  individually rather than parsing the whole string as one number ("one hundred twenty three"
 *  for "123"). Pure/testable independent of any TextToSpeech instance. Non-digit characters are
 *  dropped defensively rather than crashing — should never occur given callers only ever pass
 *  BibsModeViewModel's own digit-only entry buffer. */
fun String.toSpokenDigits(): String =
    filter(Char::isDigit).map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
