package com.k2fsa.sherpa.onnx

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var tts: OfflineTts? = null
    private lateinit var text: EditText
    private lateinit var sid: EditText
    private lateinit var speed: EditText
    private lateinit var generate: Button
    private lateinit var play: Button
    private lateinit var stop: Button
    private lateinit var save: Button
    private lateinit var share: Button

    private lateinit var rgVoices: android.widget.RadioGroup
    private lateinit var rbVoiceEn: android.widget.RadioButton
    private lateinit var rbVoiceFr: android.widget.RadioButton
    private lateinit var tvBackendStatus: TextView
    private lateinit var btnModeAuto: Button
    private lateinit var btnModeNpu: Button
    private lateinit var btnModeCpu: Button
    private lateinit var tvPerfMetrics: TextView
    private lateinit var tvDebugLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button

    @Volatile
    private var stopped: Boolean = false
    private var mediaPlayer: MediaPlayer? = null
    private var track: AudioTrack? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            if (uri != null) {
                copyGeneratedWavToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        setContentView(R.layout.activity_main)

        text = findViewById(R.id.text)
        sid = findViewById(R.id.sid)
        speed = findViewById(R.id.speed)

        generate = findViewById(R.id.generate)
        play = findViewById(R.id.play)
        stop = findViewById(R.id.stop)
        save = findViewById(R.id.save)
        share = findViewById(R.id.share)

        rgVoices = findViewById(R.id.rg_voices)
        rbVoiceEn = findViewById(R.id.rb_voice_en)
        rbVoiceFr = findViewById(R.id.rb_voice_fr)
        tvBackendStatus = findViewById(R.id.tv_backend_status)
        btnModeAuto = findViewById(R.id.btn_mode_auto)
        btnModeNpu = findViewById(R.id.btn_mode_npu)
        btnModeCpu = findViewById(R.id.btn_mode_cpu)
        tvPerfMetrics = findViewById(R.id.tv_perf_metrics)
        tvDebugLog = findViewById(R.id.tv_debug_log)
        scrollLog = findViewById(R.id.scroll_log)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        generate.setOnClickListener { onClickGenerate() }
        play.setOnClickListener { onClickPlay() }
        stop.setOnClickListener { onClickStop() }
        save.setOnClickListener { onClickSave() }
        share.setOnClickListener { onClickShare() }

        rgVoices.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_voice_narrateur -> {
                    TtsManager.setActiveModelDir(TtsManager.NARRATEUR_MODEL_DIR)
                    TtsManager.reset()
                    text.setText("Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
                    startTtsInit(preferredModel = TtsManager.NARRATEUR_MODEL_DIR)
                }
                R.id.rb_voice_en -> {
                    TtsManager.setActiveModelDir(TtsManager.EN_MODEL_DIR)
                    TtsManager.reset()
                    text.setText("Hello! This voice is accelerated by Qualcomm Hexagon NPU on your Samsung Galaxy S25 Ultra.")
                    startTtsInit(preferredModel = TtsManager.EN_MODEL_DIR)
                }
                R.id.rb_voice_fr -> {
                    TtsManager.setActiveModelDir(TtsManager.FR_MODEL_DIR)
                    TtsManager.reset()
                    text.setText("Bonjour ! Cette voix française est synthétisée avec le modèle Siwis.")
                    startTtsInit(preferredModel = TtsManager.FR_MODEL_DIR)
                }
            }
        }

        btnModeAuto.setOnClickListener { onSetMode("auto") }
        btnModeNpu.setOnClickListener { onSetMode("npu") }
        btnModeCpu.setOnClickListener { onSetMode("cpu") }

        btnCopyLogs.setOnClickListener {
            AppLog.copyToClipboard(applicationContext)
            Toast.makeText(applicationContext, "Rapport complet copié dans le presse-papiers !", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            AppLog.clear()
            tvDebugLog.text = ""
        }

        AppLog.addListener {
            runOnUiThread {
                tvDebugLog.text = AppLog.getRecentLogs(80)
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        sid.setText("0")
        speed.setText("1.0")

        text.setText("Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
        TtsManager.setActiveModelDir(TtsManager.NARRATEUR_MODEL_DIR)

        startTtsInit(preferredModel = TtsManager.NARRATEUR_MODEL_DIR)
    }

    private fun onSetMode(mode: String) {
        TtsManager.reset()
        TtsManager.setForcedBackendMode(mode)
        Toast.makeText(applicationContext, "Mode sélectionné : $mode", Toast.LENGTH_SHORT).show()
        startTtsInit(forceMode = mode)
    }

    private fun startTtsInit(preferredModel: String? = null, forceMode: String? = null) {
        generate.isEnabled = false
        generate.text = "Initialisation..."
        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false
        tvBackendStatus.text = "Backend: Initialisation en cours..."
        tvPerfMetrics.text = "Status: Initialisation du moteur..."

        Thread {
            try {
                AppLog.i("Starting TTS initialization...")
                val instance = TtsManager.ensureInitialized(
                    context = applicationContext,
                    preferredModelDir = preferredModel,
                    forceMode = forceMode
                )
                tts = instance
                initAudioTrack(instance.sampleRate())

                runOnUiThread {
                    generate.text = getString(R.string.generate)
                    generate.isEnabled = true
                    tvBackendStatus.text = "Backend: ${TtsManager.getActiveBackend()}"
                    tvPerfMetrics.text = "Prêt: ${TtsManager.getActiveBackend()} | Lang: ${instance.lang()} | ${instance.sampleRate()} Hz"
                    Toast.makeText(
                        applicationContext,
                        "Prêt : ${TtsManager.getActiveBackend()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                AppLog.e("Failed to initialize TTS", e)
                runOnUiThread {
                    generate.text = "Échec initialisation"
                    tvBackendStatus.text = "Erreur: ${e.message}"
                    tvPerfMetrics.text = "Erreur d'init: ${e.message}"
                    Toast.makeText(
                        applicationContext,
                        "Erreur init: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun initAudioTrack(sampleRate: Int) {
        track?.release()
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
        track?.play()
    }

    private fun callback(samples: FloatArray): Int {
        val t = track ?: return 0
        return if (!stopped) {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } else {
            t.stop()
            0
        }
    }

    private fun onClickGenerate() {
        val activeTts = tts
        if (activeTts == null) {
            startTtsInit()
            return
        }

        val sidInt = sid.text.toString().toIntOrNull()
        if (sidInt == null || sidInt < 0) {
            Toast.makeText(applicationContext, "Entrez un ID de locuteur valide (>= 0) !", Toast.LENGTH_SHORT).show()
            return
        }

        val speedFloat = speed.text.toString().toFloatOrNull()
        if (speedFloat == null || speedFloat <= 0) {
            Toast.makeText(applicationContext, "Entrez une vitesse positive !", Toast.LENGTH_SHORT).show()
            return
        }

        val textStr = text.text.toString().trim()
        if (textStr.isEmpty()) {
            Toast.makeText(applicationContext, "Veuillez saisir un texte non vide !", Toast.LENGTH_SHORT).show()
            return
        }

        track?.pause()
        track?.flush()
        track?.play()

        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false
        generate.isEnabled = false
        stopped = false
        tvPerfMetrics.text = "Synthèse en cours..."

        Thread {
            val genStart = System.currentTimeMillis()
            try {
                AppLog.i("Synthesis requested: \"${textStr.take(50)}\" (sid=$sidInt, speed=$speedFloat)")

                val genConfig = GenerationConfig(sid = sidInt, speed = speedFloat)
                genConfig.phonemeCodepoints = TtsManager.phonemize(activeTts, textStr)

                val audio = activeTts.generateWithConfigAndCallback(
                    text = "",
                    config = genConfig,
                    callback = this::callback
                )

                val elapsedMs = System.currentTimeMillis() - genStart
                val numSamples = audio.samples.size
                val sampleRate = activeTts.sampleRate()
                val audioDurationSec = if (sampleRate > 0) numSamples.toDouble() / sampleRate else 0.0
                val rtf = if (audioDurationSec > 0) (elapsedMs / 1000.0) / audioDurationSec else 0.0

                AppLog.i(String.format(
                    Locale.ROOT,
                    "Generated %d samples (%.2fs) in %d ms | RTF: %.3fx",
                    numSamples, audioDurationSec, elapsedMs, rtf
                ))

                val filename = application.filesDir.absolutePath + "/generated.wav"
                val ok = audio.samples.isNotEmpty() && audio.save(filename)

                runOnUiThread {
                    if (ok) {
                        play.isEnabled = true
                        save.isEnabled = true
                        share.isEnabled = true
                    }
                    generate.isEnabled = true
                    track?.stop()
                    tvPerfMetrics.text = String.format(
                        Locale.ROOT,
                        "Temps: %d ms | Audio: %.2f s | RTF: %.3fx | %s",
                        elapsedMs, audioDurationSec, rtf, TtsManager.getActiveBackend()
                    )
                }
            } catch (e: Throwable) {
                AppLog.e("Failed to generate audio", e)
                runOnUiThread {
                    generate.isEnabled = true
                    tvPerfMetrics.text = "Erreur de synthèse: ${e.message}"
                    Toast.makeText(
                        applicationContext,
                        "Erreur synthèse: ${e.message}",
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
        track?.pause()
        track?.flush()
        mediaPlayer?.stop()
        mediaPlayer = null
    }

    private fun onClickSave() {
        saveLauncher.launch("generated.wav")
    }

    private fun onClickShare() {
        val file = File(application.filesDir.absolutePath + "/generated.wav")
        if (!file.exists()) {
            Toast.makeText(applicationContext, "Aucun fichier audio à partager", Toast.LENGTH_SHORT).show()
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
        startActivity(Intent.createChooser(intent, "Partager le fichier audio"))
    }

    private fun copyGeneratedWavToUri(destUri: Uri) {
        try {
            val srcFile = File(application.filesDir.absolutePath + "/generated.wav")
            contentResolver.openOutputStream(destUri)?.use { output ->
                srcFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(applicationContext, "Audio enregistré avec succès", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.e("Failed to save audio", e)
            Toast.makeText(applicationContext, "Échec de l'enregistrement: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        track?.release()
        track = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
