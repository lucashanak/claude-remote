package com.clauderemote.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Build a [SpeechRecognizer] that's actually likely to work on this device.
 *
 * History of this bug: we used to force a *hardcoded* Google component name
 * (`com.google.android.voicesearch.serviceapi.GoogleRecognitionService`).
 * That class name is stale on newer Google app builds / non-Pixel devices,
 * so `createSpeechRecognizer(context, thatComponent)` returned an instance
 * that fired `ERROR_LANGUAGE_NOT_SUPPORTED` on the first `startListening` —
 * even on devices where Gboard voice typing recognises Czech perfectly,
 * because Gboard uses the *system-default* recognition service, not that
 * exact component.
 *
 * Fix:
 *  - Everywhere except Samsung, just use the system default
 *    (`createSpeechRecognizer(context)`). It's whatever the user has set
 *    and is exactly what Gboard uses, so it handles cs-CZ wherever Gboard
 *    does.
 *  - Only on Samsung (whose own default backend is genuinely unreliable for
 *    Czech) do we prefer Google — and we *resolve the real component name*
 *    via PackageManager instead of hardcoding it.
 */
internal fun createCzechRecognizerSmart(context: Context): SpeechRecognizer? {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return null

    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        val googleService = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE)
                .setPackage("com.google.android.googlequicksearchbox"),
            0,
        ).firstOrNull()?.serviceInfo
        if (googleService != null) {
            val rec = runCatching {
                SpeechRecognizer.createSpeechRecognizer(
                    context,
                    ComponentName(googleService.packageName, googleService.name),
                )
            }.getOrNull()
            if (rec != null) return rec
        }
    }

    return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
}

/**
 * Translate an Android `SpeechRecognizer.ERROR_*` code into a short
 * Czech-language message. Falls back to the numeric code when unknown.
 */
internal fun recognizerErrorLabel(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Chyba audio vstupu"
    SpeechRecognizer.ERROR_CLIENT -> "Chyba klienta rozpoznávání"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chybí oprávnění mikrofonu"
    SpeechRecognizer.ERROR_NETWORK -> "Chyba sítě"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Vypršel časový limit sítě"
    SpeechRecognizer.ERROR_NO_MATCH -> "Nerozpoznáno"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznávač je zaneprázdněn"
    SpeechRecognizer.ERROR_SERVER -> "Chyba serveru rozpoznávání"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Příliš dlouhé ticho"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Čeština není na tomto zařízení podporována"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Český jazykový balíček chybí"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server rozpoznávání odpojen"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Příliš mnoho dotazů, zkuste znovu"
    else -> "Chyba rozpoznávání ($code)"
}
