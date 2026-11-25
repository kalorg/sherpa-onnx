package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity

class CheckVoiceData : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locale = TtsEngine.getActiveLocale(applicationContext)
        val iso3Lang = runCatching { locale.isO3Language }.getOrDefault(TtsEngine.lang)
        val iso3Country = runCatching { locale.isO3Country }.getOrDefault("")
        val voiceTag = if (iso3Country.isNotEmpty()) "$iso3Lang-$iso3Country" else iso3Lang

        val intent = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                arrayListOf(voiceTag, iso3Lang)
            )
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, intent)
        finish()
    }
}