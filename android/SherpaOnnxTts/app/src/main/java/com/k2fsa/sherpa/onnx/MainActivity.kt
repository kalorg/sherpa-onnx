package com.k2fsa.sherpa.onnx

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

const val TAG = "sherpa-onnx"

class MainActivity : AppCompatActivity() {
    private lateinit var tts: OfflineTts
    private lateinit var text: EditText
    private lateinit var sid: EditText
    private lateinit var speed: EditText
    private lateinit var generate: Button
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var save: Button
    private lateinit var share: Button

    @Volatile
    private var stopped: Boolean = false
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var track: AudioTrack

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            if (uri != null) {
                copyGeneratedWavToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        text = findViewById(R.id.text)
        sid = findViewById(R.id.sid)
        speed = findViewById(R.id.speed)

        generate = findViewById(R.id.generate)
        play = findViewById(R.id.play)
        stop = findViewById(R.id.stop)
        save = findViewById(R.id.save)
        share = findViewById(R.id.share)

        generate.setOnClickListener { onClickGenerate() }
        play.setOnClickListener { onClickPlay() }
        stop.setOnClickListener { onClickStop() }
        save.setOnClickListener { onClickSave() }
        share.setOnClickListener { onClickShare() }

        sid.setText("0")
        speed.setText("1.0")

        val isFrench = TtsManager.getActiveModelDir(applicationContext).contains("-fr-")
        val sampleText = if (isFrench) {
            "Bonjour ! Cette voix française est synthétisée avec succès par Sherpa-ONNX."
        } else {
            "Hello! This voice is synthesized on your Samsung Galaxy S25 Ultra Qualcomm Hexagon NPU using Sherpa ONNX and QNN."
        }
        text.setText(sampleText)

        generate.isEnabled = false
        generate.text = "Initializing TTS..."
        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false

        Thread {
            try {
                Log.i(TAG, "Start to initialize TTS")
                tts = TtsManager.ensureInitialized(applicationContext)
                Log.i(TAG, "Finish initializing TTS (${TtsManager.getActiveBackend()})")

                initAudioTrack()

                runOnUiThread {
                    generate.text = getString(R.string.generate)
                    generate.isEnabled = true
                    Toast.makeText(
                        applicationContext,
                        "Ready: ${TtsManager.getActiveBackend()} [${tts.lang()}]",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize TTS", e)
                runOnUiThread {
                    generate.text = "Init failed"
                    text.setText(
                        "Error initializing NPU TTS:\n${e.message}\n\n${Log.getStackTraceString(e)}"
                    )
                    Toast.makeText(
                        applicationContext,
                        "TTS Init Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun initAudioTrack() {
        val sampleRate = tts.sampleRate()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr,
            format,
            bufLength,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
    }

    private fun callback(samples: FloatArray): Int {
        return if (!stopped) {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } else {
            track.stop()
            0
        }
    }

    private fun onClickGenerate() {
        val sidInt = sid.text.toString().toIntOrNull()
        if (sidInt == null || sidInt < 0) {
            Toast.makeText(
                applicationContext,
                "Please input a non-negative integer for speaker ID!",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val speedFloat = speed.text.toString().toFloatOrNull()
        if (speedFloat == null || speedFloat <= 0) {
            Toast.makeText(
                applicationContext,
                "Please input a positive number for speech speed!",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val textStr = text.text.toString().trim()
        if (textStr.isEmpty()) {
            Toast.makeText(applicationContext, "Please input a non-empty text!", Toast.LENGTH_SHORT)
                .show()
            return
        }

        track.pause()
        track.flush()
        track.play()

        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false
        generate.isEnabled = false
        stopped = false

        Thread {
            try {
                val genConfig = GenerationConfig(sid = sidInt, speed = speedFloat)
                genConfig.phonemeCodepoints = TtsManager.phonemize(tts, textStr)

                val audio = tts.generateWithConfigAndCallback(
                    text = "",
                    config = genConfig,
                    callback = this::callback
                )

                val filename = application.filesDir.absolutePath + "/generated.wav"
                val ok = audio.samples.isNotEmpty() && audio.save(filename)
                runOnUiThread {
                    if (ok) {
                        play.isEnabled = true
                        save.isEnabled = true
                        share.isEnabled = true
                    }
                    generate.isEnabled = true
                    track.stop()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to generate audio", e)
                runOnUiThread {
                    generate.isEnabled = true
                    Toast.makeText(
                        applicationContext,
                        "Synthesis error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun onClickPlay() {
        val filename = application.filesDir.absolutePath + "/generated.wav"
        mediaPlayer?.stop()
        mediaPlayer = MediaPlayer.create(
            applicationContext,
            Uri.fromFile(File(filename))
        )
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        play.isEnabled = true
        save.isEnabled = true
        share.isEnabled = true
        generate.isEnabled = true
        track.pause()
        track.flush()
        mediaPlayer?.stop()
        mediaPlayer = null
    }

    private fun onClickSave() {
        saveLauncher.launch("generated.wav")
    }

    private fun onClickShare() {
        val file = File(application.filesDir.absolutePath + "/generated.wav")
        if (!file.exists()) {
            Toast.makeText(applicationContext, "No audio to share", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "com.k2fsa.sherpa.onnx.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share audio"))
    }

    private fun copyGeneratedWavToUri(destUri: Uri) {
        try {
            val srcFile = File(application.filesDir.absolutePath + "/generated.wav")
            contentResolver.openOutputStream(destUri)?.use { output ->
                srcFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(applicationContext, "Audio saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio: $e")
            Toast.makeText(applicationContext, "Failed to save audio", Toast.LENGTH_SHORT).show()
        }
    }
}
