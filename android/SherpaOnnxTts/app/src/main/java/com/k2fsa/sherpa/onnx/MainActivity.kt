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
import android.widget.SeekBar
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

    private lateinit var tvSpeedVal: TextView
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvNoiseScaleVal: TextView
    private lateinit var sbNoiseScale: SeekBar
    private lateinit var tvNoiseWVal: TextView
    private lateinit var sbNoiseW: SeekBar
    private lateinit var tvSilenceVal: TextView
    private lateinit var sbSilence: SeekBar
    private lateinit var btnPresetSmooth: Button
    private lateinit var btnPresetNarrateur: Button
    private lateinit var btnPresetStandard: Button

    @Volatile
    private var currentSpeed: Float = 1.0f
    @Volatile
    private var currentNoiseScale: Float = 0.35f
    @Volatile
    private var currentNoiseW: Float = 0.33f
    @Volatile
    private var currentSilenceScale: Float = 0.20f

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

        tvSpeedVal = findViewById(R.id.tv_speed_val)
        sbSpeed = findViewById(R.id.sb_speed)
        tvNoiseScaleVal = findViewById(R.id.tv_noise_scale_val)
        sbNoiseScale = findViewById(R.id.sb_noise_scale)
        tvNoiseWVal = findViewById(R.id.tv_noise_w_val)
        sbNoiseW = findViewById(R.id.sb_noise_w)
        tvSilenceVal = findViewById(R.id.tv_silence_val)
        sbSilence = findViewById(R.id.sb_silence)
        btnPresetSmooth = findViewById(R.id.btn_preset_smooth)
        btnPresetNarrateur = findViewById(R.id.btn_preset_narrateur)
        btnPresetStandard = findViewById(R.id.btn_preset_standard)

        setupSliders()

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
                    applyAcousticPreset(noiseScale = 0.35f, noiseW = 0.33f)
                    text.setText("Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
                    startTtsInit(preferredModel = TtsManager.NARRATEUR_MODEL_DIR)
                }
                R.id.rb_voice_en -> {
                    TtsManager.setActiveModelDir(TtsManager.EN_MODEL_DIR)
                    TtsManager.reset()
                    applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
                    text.setText("Hello! This voice is accelerated by Qualcomm Hexagon NPU on your Samsung Galaxy S25 Ultra.")
                    startTtsInit(preferredModel = TtsManager.EN_MODEL_DIR)
                }
                R.id.rb_voice_fr -> {
                    TtsManager.setActiveModelDir(TtsManager.FR_MODEL_DIR)
                    TtsManager.reset()
                    applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
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
        applyAcousticPreset(noiseScale = 0.35f, noiseW = 0.33f)

        text.setText("Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
        TtsManager.setActiveModelDir(TtsManager.NARRATEUR_MODEL_DIR)

        startTtsInit(preferredModel = TtsManager.NARRATEUR_MODEL_DIR)
    }

    private fun setupSliders() {
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeed = 0.50f + (progress / 100.0f)
                speed.setText(String.format(Locale.ROOT, "%.2f", currentSpeed))
                tvSpeedVal.text = String.format(Locale.ROOT, "Vitesse : %.2fx", currentSpeed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbNoiseScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentNoiseScale = progress / 100.0f
                tvNoiseScaleVal.text = String.format(Locale.ROOT, "Bruit Timbre (noise) : %.2f", currentNoiseScale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbNoiseW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentNoiseW = progress / 100.0f
                tvNoiseWVal.text = String.format(Locale.ROOT, "Bruit Rythme (noise_w) : %.2f", currentNoiseW)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSilenceScale = progress / 100.0f
                tvSilenceVal.text = String.format(Locale.ROOT, "Pauses (silence) : %.2f", currentSilenceScale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPresetSmooth.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.33f, noiseW = 0.33f)
            Toast.makeText(applicationContext, "Profil Doux anti-métallique appliqué (0.33 / 0.33)", Toast.LENGTH_SHORT).show()
        }

        btnPresetNarrateur.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.50f, noiseW = 0.33f)
            Toast.makeText(applicationContext, "Profil Config Narrateur appliqué (0.50 / 0.33)", Toast.LENGTH_SHORT).show()
        }

        btnPresetStandard.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
            Toast.makeText(applicationContext, "Profil Piper Standard appliqué (0.67 / 0.80)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAcousticPreset(noiseScale: Float, noiseW: Float) {
        currentNoiseScale = noiseScale
        currentNoiseW = noiseW
        sbNoiseScale.progress = (noiseScale * 100).toInt().coerceIn(0, 100)
        sbNoiseW.progress = (noiseW * 100).toInt().coerceIn(0, 100)
        tvNoiseScaleVal.text = String.format(Locale.ROOT, "Bruit Timbre (noise) : %.2f", currentNoiseScale)
        tvNoiseWVal.text = String.format(Locale.ROOT, "Bruit Rythme (noise_w) : %.2f", currentNoiseW)
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

        val sidInt = sid.text.toString().toIntOrNull() ?: 0
        val speedFloat = currentSpeed
        val noiseScale = currentNoiseScale
        val noiseW = currentNoiseW
        val silenceScale = currentSilenceScale

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
                AppLog.i(String.format(
                    Locale.ROOT,
                    "Synthesis requested: \"%s\" (speed=%.2f, noise=%.2f, noise_w=%.2f, silence=%.2f)",
                    textStr.take(40), speedFloat, noiseScale, noiseW, silenceScale
                ))

                val genConfig = GenerationConfig(
                    sid = sidInt,
                    speed = speedFloat,
                    silenceScale = silenceScale,
                    extra = mapOf(
                        "noise_scale" to String.format(Locale.ROOT, "%.3f", noiseScale),
                        "noise_scale_w" to String.format(Locale.ROOT, "%.3f", noiseW)
                    )
                )
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
