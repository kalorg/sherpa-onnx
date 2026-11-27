package com.k2fsa.sherpa.onnx.tts.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import java.util.Locale
import kotlin.math.min

class TtsService : TextToSpeechService() {
    @Volatile
    private var stopRequested: Boolean = false

    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()
        Thread {
            runCatching { TtsEngine.createTts(applicationContext) }
                .onFailure { Log.e(TAG, "Background TTS init failed", it) }
        }.start()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    private fun activeLocale(): Locale = TtsEngine.getActiveLocale(applicationContext)

    private fun supports(lang: String?, country: String? = null): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE
        val norm = lang.lowercase(Locale.ROOT)
        val locale = activeLocale()
        val iso2 = locale.language.lowercase(Locale.ROOT)
        val iso3 = runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrDefault("")
        val langMatch = norm == iso2 || norm == iso3 ||
            norm == TtsEngine.lang || norm == TtsEngine.lang2 ||
            (iso2 == "fr" && (norm == "fra" || norm == "fre" || norm.startsWith("fr"))) ||
            (iso2 == "en" && (norm == "eng" || norm.startsWith("en")))

        if (!langMatch) return TextToSpeech.LANG_NOT_SUPPORTED

        if (!country.isNullOrBlank()) {
            val cNorm = country.uppercase(Locale.ROOT)
            val c2 = locale.country.uppercase(Locale.ROOT)
            val c3 = runCatching { locale.isO3Country.uppercase(Locale.ROOT) }.getOrDefault("")
            if (cNorm == c2 || cNorm == c3) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
        }
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        return supports(_lang, _country)
    }

    override fun onGetVoices(): List<Voice> {
        val locale = activeLocale()
        val voiceId = TtsEngine.getVoiceId(applicationContext)
        return listOf(
            Voice(
                voiceId,
                locale,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_VERY_LOW,
                false,
                emptySet()
            )
        )
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (supports(lang, country) == TextToSpeech.LANG_NOT_SUPPORTED) {
            return null
        }
        return TtsEngine.getVoiceId(applicationContext)
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        return if (voiceName == TtsEngine.getVoiceId(applicationContext) ||
            voiceName.startsWith("piper-qnn-")
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
        Thread {
            runCatching { TtsEngine.createTts(applicationContext) }
        }.start()
        return TextToSpeech.SUCCESS
    }

    override fun onGetLanguage(): Array<String> {
        val locale = activeLocale()
        val iso3Lang = runCatching { locale.isO3Language }.getOrDefault(TtsEngine.lang)
        val iso3Country = runCatching { locale.isO3Country }.getOrDefault("")
        return arrayOf(iso3Lang, iso3Country, "")
    }

    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        val avail = supports(_lang, _country)
        if (avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        Thread {
            runCatching { TtsEngine.createTts(applicationContext) }
        }.start()
        return avail
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val language = request.language
        val country = request.country
        val variant = request.variant
        val text = request.charSequenceText?.toString().orEmpty()

        val rate = runCatching { request.speechRate }.getOrDefault(-1)
        val engineSpeed = if (rate > 0) {
            (rate / 100.0f).coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
        } else {
            TtsEngine.speed
        }

        if (onIsLanguageAvailable(language, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }

        try {
            TtsEngine.createTts(applicationContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to init TTS engine", e)
            callback.error()
            return
        }

        val tts = TtsEngine.tts
        if (tts == null) {
            callback.error()
            return
        }

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank()) {
            callback.done()
            return
        }

        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            if (stopRequested) return 0
            val samples = floatArrayToByteArray(floatSamples)
            val maxBufferSize: Int = callback.maxBufferSize
            if (maxBufferSize <= 0) return 0
            var offset = 0
            while (offset < samples.size && !stopRequested) {
                val bytesToWrite = min(maxBufferSize, samples.size - offset)
                if (callback.audioAvailable(samples, offset, bytesToWrite) != TextToSpeech.SUCCESS) {
                    stopRequested = true
                    break
                }
                offset += bytesToWrite
            }
            return if (stopRequested) 0 else 1
        }

        try {
            val genConfig = GenerationConfig(sid = TtsEngine.speakerId, speed = engineSpeed)
            var genText = text
            if (TtsEngine.usePhonemize) {
                genConfig.phonemeCodepoints = TtsEngine.phonemize(text)
                genText = ""
            }
            tts.generateWithConfigAndCallback(
                text = genText,
                config = genConfig,
                callback = ttsCallback,
            )
            if (!stopRequested) {
                callback.done()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val v = audio[i]
            val clamped = if (v.isFinite()) v.coerceIn(-1.0f, 1.0f) else 0.0f
            val sample = (clamped * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}
