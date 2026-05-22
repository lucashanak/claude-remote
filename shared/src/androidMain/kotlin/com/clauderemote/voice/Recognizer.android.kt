package com.clauderemote.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Build a [SpeechRecognizer] that's actually likely to work on this
 * device.
 *
 * The previous implementation blindly forced Google's recognition
 * component ("com.google.android.googlequicksearchbox/...") because on
 * Samsung the system default backend is broken for Czech. The problem is
 * that on Xiaomi/HyperOS and other non-GMS devices that component doesn't
 * exist; calling `createSpeechRecognizer(context, googleComponent)` then
 * returns a `SpeechRecognizer` instance that fires `ERROR_CLIENT` /
 * `ERROR_SERVER` on the very first `startListening` call, leaving the UI
 * in a stuck "listening" state with no feedback.
 *
 * Fix: probe for the Google service via [Intent.ACTION_RECOGNITION_SERVICE]
 * before binding. If absent, fall back to the system default — which is
 * the right choice on non-Samsung non-GMS devices.
 */
internal fun createCzechRecognizerSmart(context: Context): SpeechRecognizer? {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return null

    val pm = context.packageManager
    val googlePackage = "com.google.android.googlequicksearchbox"
    val intent = Intent(RecognitionService.SERVICE_INTERFACE).setPackage(googlePackage)
    val hasGoogle = pm.queryIntentServices(intent, 0).isNotEmpty()

    return runCatching {
        if (hasGoogle) {
            SpeechRecognizer.createSpeechRecognizer(
                context,
                ComponentName(
                    googlePackage,
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
                ),
            )
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }.getOrNull()
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
