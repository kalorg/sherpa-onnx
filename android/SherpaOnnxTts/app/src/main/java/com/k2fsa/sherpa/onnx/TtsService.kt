package com.k2fsa.sherpa.onnx

import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.view.Window
import java.util.Locale
import kotlin.math.min

/**
 * Modern Android TextToSpeechService (Android 15 / 16 / 17 compatible):
 * - Implements the Lollipop+ Voice API (onGetVoices, onGetDefaultVoiceNameFor,
 *   onIsValidVoiceName, onLoadVoice) required by modern Android / Samsung One UI TTS settings.
 * - Accepts both ISO 639-2/T (fra, eng) and ISO 639-1 (fr, en) language codes.
 * - Supports instant interruption in onStop() while synthesizing on the Qualcomm NPU.
 * - Lazy/thread-safe engine initialization via TtsManager so onCreate() never triggers an ANR.
 */
private const val TAG = "TtsService"

class TtsService : TextToSpeechService() {
    @Volatile
    private var stopRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TtsService onCreate (lazy initialization enabled)")
    }

    private fun activeLocale(): Locale = TtsManager.getActiveLocale(applicationContext)

    private fun matchesLanguage(requestedLang: String?): Boolean {
        if (requestedLang.isNullOrBlank()) return true
        val norm = requestedLang.lowercase(Locale.ROOT)
        return norm.startsWith("fr") || norm == "fra" || norm == "fre" ||
            norm.startsWith("en") || norm == "eng"
    }

    private fun matchesCountry(requestedCountry: String?): Boolean {
        if (requestedCountry.isNullOrBlank()) return false
        val norm = requestedCountry.uppercase(Locale.ROOT)
        return norm == "FR" || norm == "FRA" || norm == "US" || norm == "USA"
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!matchesLanguage(lang)) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        return if (matchesCountry(country)) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        val locale = activeLocale()
        val iso3Lang = runCatching { locale.isO3Language }.getOrDefault(locale.language)
        val iso3Country = runCatching { locale.isO3Country }.getOrDefault(locale.country)
        return arrayOf(iso3Lang, iso3Country, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val avail = onIsLanguageAvailable(lang, country, variant)
        if (avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        val targetDir = if (lang?.lowercase(Locale.ROOT)?.startsWith("en") == true) {
            "pipertts-en-qnn-s25-ultra"
        } else {
            "pipertts-fr-qnn-s25-ultra"
        }
        Thread {
            runCatching { TtsManager.ensureInitialized(applicationContext, targetDir) }
        }.start()
        return avail
    }

    override fun onGetVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        if (TtsManager.hasVoiceInAssetsOrFiles(applicationContext, "pipertts-fr-narrateur-s25-ultra")) {
            voices.add(
                Voice(
                    "piper-fr-narrateur",
                    Locale.FRANCE,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_VERY_LOW,
                    false,
                    emptySet()
                )
            )
        }
        if (TtsManager.hasVoiceInAssetsOrFiles(applicationContext, "pipertts-fr-qnn-s25-ultra")) {
            voices.add(
                Voice(
                    "piper-fr-siwis",
                    Locale.FRANCE,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_VERY_LOW,
                    false,
                    emptySet()
                )
            )
        }
        if (TtsManager.hasVoiceInAssetsOrFiles(applicationContext, "pipertts-en-qnn-s25-ultra")) {
            voices.add(
                Voice(
                    "piper-en-amy-qnn",
                    Locale.US,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_VERY_LOW,
                    false,
                    emptySet()
                )
            )
        }
        return voices
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String? {
        val norm = lang?.lowercase(Locale.ROOT).orEmpty()
        return if (norm.startsWith("en")) {
            "piper-en-amy-qnn"
        } else if (TtsManager.hasVoiceInAssetsOrFiles(applicationContext, "pipertts-fr-narrateur-s25-ultra")) {
            "piper-fr-narrateur"
        } else {
            "piper-fr-siwis"
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        return if (voiceName == "piper-fr-narrateur" || voiceName == "piper-fr-siwis" || voiceName == "piper-en-amy-qnn" ||
            voiceName.startsWith("piper-")
        ) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (onIsValidVoiceName(voiceName) != TextToSpeech.SUCCESS) {
            return TextToSpeech.ERROR
        }
        val targetDir = when {
            voiceName?.contains("narrateur") == true -> "pipertts-fr-narrateur-s25-ultra"
            voiceName?.contains("siwis") == true -> "pipertts-fr-qnn-s25-ultra"
            voiceName?.contains("en") == true -> "pipertts-en-qnn-s25-ultra"
            else -> "pipertts-fr-narrateur-s25-ultra"
        }
        Thread {
            runCatching { TtsManager.ensureInitialized(applicationContext, targetDir) }
        }.start()
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (onIsLanguageAvailable(request.language, request.country, request.variant) ==
            TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            callback.error()
            return
        }

        val reqLang = request.language?.lowercase(Locale.ROOT).orEmpty()
        val targetDir = if (reqLang.startsWith("en")) {
            "pipertts-en-qnn-s25-ultra"
        } else {
            "pipertts-fr-qnn-s25-ultra"
        }
        val tts = try {
            TtsManager.ensureInitialized(applicationContext, targetDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init TTS in onSynthesizeText", e)
            callback.error()
            return
        }

        val rate = runCatching { request.speechRate }.getOrDefault(100)
        val speed = if (rate > 0) (rate / 100.0f).coerceIn(0.25f, 3.0f) else 1.0f

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)
        if (text.isEmpty()) {
            callback.done()
            return
        }

        try {
            val genConfig = GenerationConfig(sid = 0, speed = speed)
            genConfig.phonemeCodepoints = TtsManager.phonemize(tts, text)

            tts.generateWithConfigAndCallback(
                text = "",
                config = genConfig,
                callback = { floatSamples ->
                    if (stopRequested) {
                        0
                    } else {
                        val pcm16 = floatToPcm16(floatSamples)
                        val maxBuf = callback.maxBufferSize
                        var offset = 0
                        while (offset < pcm16.size && !stopRequested) {
                            val toWrite = min(maxBuf, pcm16.size - offset)
                            callback.audioAvailable(pcm16, offset, toWrite)
                            offset += toWrite
                        }
                        if (stopRequested) 0 else 1
                    }
                }
            )
            if (!stopRequested) {
                callback.done()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error during TTS synthesis", e)
            callback.error()
        }
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val s = (clamped * 32767.0f).toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locale = TtsManager.getActiveLocale(applicationContext)
        val iso3Lang = runCatching { locale.isO3Language }.getOrDefault(locale.language)
        val iso3Country = runCatching { locale.isO3Country }.getOrDefault(locale.country)
        val voiceTag = if (iso3Country.isNotEmpty()) "$iso3Lang-$iso3Country" else iso3Lang

        val intent = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                arrayListOf(voiceTag, iso3Lang)
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                arrayListOf()
            )
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, intent)
        finish()
    }
}

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locale = TtsManager.getActiveLocale(applicationContext)
        val text = if (locale.language == "fr") {
            "Bonjour, ceci est une voix synthétisée sur le NPU Qualcomm Hexagon du Samsung Galaxy S25 Ultra."
        } else {
            "Hello! This voice is synthesized on your Samsung Galaxy S25 Ultra Qualcomm Hexagon NPU."
        }
        val intent = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text)
        }
        setResult(TextToSpeech.LANG_AVAILABLE, intent)
        finish()
    }
}

class InstallVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        finish()
    }
}
