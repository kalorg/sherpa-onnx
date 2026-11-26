package com.k2fsa.sherpa.onnx

import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    // Tabs
    private lateinit var btnTabModels: Button
    private lateinit var btnTabVoice: Button
    private lateinit var layoutTabModels: LinearLayout
    private lateinit var layoutTabVoice: LinearLayout
    private lateinit var rgModelFamily: RadioGroup
    private lateinit var tvModelArchDetails: TextView
    private lateinit var btnGoToVoiceTab: Button
    private lateinit var tvActiveFamilyBanner: TextView
    private lateinit var btnChangeModel: Button

    // Voices & Emotions
    private lateinit var rgVoices: RadioGroup
    private lateinit var rbVoiceNarrateur: RadioButton
    private lateinit var rbVoiceEn: RadioButton
    private lateinit var rbVoiceFr: RadioButton
    private lateinit var rbMeloUs: RadioButton
    private lateinit var rbMeloBr: RadioButton
    private lateinit var rbMeloIn: RadioButton
    private lateinit var rbMeloAu: RadioButton
    private lateinit var rbMeloDef: RadioButton
    private lateinit var rbNeuEmily: RadioButton
    private lateinit var rbNeuPaul: RadioButton
    private lateinit var rbNeuSophie: RadioButton
    private lateinit var rbNeuSteven: RadioButton
    private lateinit var layoutEmotions: LinearLayout
    private lateinit var tvEmotionStatus: TextView

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
    private var suppressVoiceListener: Boolean = false

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

        btnTabModels = findViewById(R.id.btn_tab_models)
        btnTabVoice = findViewById(R.id.btn_tab_voice)
        layoutTabModels = findViewById(R.id.layout_tab_models)
        layoutTabVoice = findViewById(R.id.layout_tab_voice)
        rgModelFamily = findViewById(R.id.rg_model_family)
        tvModelArchDetails = findViewById(R.id.tv_model_arch_details)
        btnGoToVoiceTab = findViewById(R.id.btn_go_to_voice_tab)
        tvActiveFamilyBanner = findViewById(R.id.tv_active_family_banner)
        btnChangeModel = findViewById(R.id.btn_change_model)

        rgVoices = findViewById(R.id.rg_voices)
        rbVoiceNarrateur = findViewById(R.id.rb_voice_narrateur)
        rbVoiceEn = findViewById(R.id.rb_voice_en)
        rbVoiceFr = findViewById(R.id.rb_voice_fr)
        rbMeloUs = findViewById(R.id.rb_melo_us)
        rbMeloBr = findViewById(R.id.rb_melo_br)
        rbMeloIn = findViewById(R.id.rb_melo_in)
        rbMeloAu = findViewById(R.id.rb_melo_au)
        rbMeloDef = findViewById(R.id.rb_melo_def)
        rbNeuEmily = findViewById(R.id.rb_neu_emily)
        rbNeuPaul = findViewById(R.id.rb_neu_paul)
        rbNeuSophie = findViewById(R.id.rb_neu_sophie)
        rbNeuSteven = findViewById(R.id.rb_neu_steven)
        layoutEmotions = findViewById(R.id.layout_emotions)
        tvEmotionStatus = findViewById(R.id.tv_emotion_status)

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

        setupTabs()
        setupSliders()
        setupEmotionButtons()

        // Restore persisted model family, voice, speaker ID, emotion, and acoustic sliders
        val savedFamily = TtsManager.getActiveModelFamily(applicationContext)
        val savedVoiceDir = TtsManager.getActiveModelDir(applicationContext)
        val savedSid = TtsManager.getSavedSpeakerId(applicationContext)
        val savedEmotion = TtsManager.getSavedEmotion(applicationContext)

        sid.setText(savedSid.toString())
        tvEmotionStatus.text = "🎭 Émotion NeuTTS-2E active : ${savedEmotion.replaceFirstChar { it.uppercase() }}"

        val defaultNoise = if (savedVoiceDir.contains("narrateur")) 0.35f else 0.67f
        val defaultNoiseW = if (savedVoiceDir.contains("narrateur")) 0.33f else 0.80f
        currentSpeed = TtsManager.getSavedSpeed(applicationContext)
        currentNoiseScale = TtsManager.getSavedNoiseScale(applicationContext, defaultNoise)
        currentNoiseW = TtsManager.getSavedNoiseW(applicationContext, defaultNoiseW)
        currentSilenceScale = TtsManager.getSavedSilenceScale(applicationContext, 0.20f)
        syncSlidersFromState()

        when (savedFamily) {
            TtsManager.MODEL_FAMILY_MELOTTS -> rgModelFamily.check(R.id.rb_family_melotts)
            TtsManager.MODEL_FAMILY_NEUTTS -> rgModelFamily.check(R.id.rb_family_neutts)
            else -> rgModelFamily.check(R.id.rb_family_piper)
        }
        updateUiForModelFamily(savedFamily, savedVoiceDir, savedSid, triggerInit = false)

        rgModelFamily.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_family_piper -> {
                    AppLog.i("Tab 1: User selected Model Family -> PIPER TTS (VITS 22.05kHz)")
                    TtsManager.setActiveModelFamily(TtsManager.MODEL_FAMILY_PIPER, applicationContext)
                    TtsManager.setActiveModelDir(TtsManager.NARRATEUR_MODEL_DIR, applicationContext)
                    TtsManager.setSavedSpeakerId(0, applicationContext)
                    sid.setText("0")
                    TtsManager.reset()
                    applyAcousticPreset(noiseScale = 0.35f, noiseW = 0.33f)
                    updateUiForModelFamily(TtsManager.MODEL_FAMILY_PIPER, TtsManager.NARRATEUR_MODEL_DIR, 0, triggerInit = true)
                }
                R.id.rb_family_melotts -> {
                    AppLog.i("Tab 1: User selected Model Family -> MELOTTS (VITS2 44.1kHz Multi-Accent)")
                    TtsManager.setActiveModelFamily(TtsManager.MODEL_FAMILY_MELOTTS, applicationContext)
                    TtsManager.setActiveModelDir(TtsManager.MELO_EN_MODEL_DIR, applicationContext)
                    TtsManager.setSavedSpeakerId(0, applicationContext)
                    sid.setText("0")
                    TtsManager.reset()
                    applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
                    updateUiForModelFamily(TtsManager.MODEL_FAMILY_MELOTTS, TtsManager.MELO_EN_MODEL_DIR, 0, triggerInit = true)
                }
                R.id.rb_family_neutts -> {
                    AppLog.i("Tab 1: User selected Model Family -> NEUTTS-2E (Emotional Multi-Speaker)")
                    TtsManager.setActiveModelFamily(TtsManager.MODEL_FAMILY_NEUTTS, applicationContext)
                    TtsManager.setActiveModelDir(TtsManager.MELO_EN_MODEL_DIR, applicationContext)
                    TtsManager.setSavedSpeakerId(0, applicationContext)
                    sid.setText("0")
                    TtsManager.reset()
                    applyEmotionPreset(TtsManager.getSavedEmotion(applicationContext))
                    updateUiForModelFamily(TtsManager.MODEL_FAMILY_NEUTTS, TtsManager.MELO_EN_MODEL_DIR, 0, triggerInit = true)
                }
            }
        }

        generate.setOnClickListener { onClickGenerate() }
        play.setOnClickListener { onClickPlay() }
        stop.setOnClickListener { onClickStop() }
        save.setOnClickListener { onClickSave() }
        share.setOnClickListener { onClickShare() }

        rgVoices.setOnCheckedChangeListener { _, checkedId ->
            if (suppressVoiceListener) return@setOnCheckedChangeListener
            when (checkedId) {
                // PIPER TTS VOICES
                R.id.rb_voice_narrateur -> {
                    selectVoice(TtsManager.NARRATEUR_MODEL_DIR, 0, "Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
                    applyAcousticPreset(noiseScale = 0.35f, noiseW = 0.33f)
                }
                R.id.rb_voice_en -> {
                    selectVoice(TtsManager.EN_MODEL_DIR, 0, "Hello! This voice is accelerated by Qualcomm Hexagon NPU on your Samsung Galaxy S25 Ultra.")
                    applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
                }
                R.id.rb_voice_fr -> {
                    selectVoice(TtsManager.FR_MODEL_DIR, 0, "Bonjour ! Cette voix française est synthétisée avec le modèle Siwis.")
                    applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
                }
                // MELOTTS VOICES (5 Accents, sid=0..4)
                R.id.rb_melo_us -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 0, "Hello! This is MeloTTS with an American English accent running on Qualcomm Hexagon V79 NPU.")
                R.id.rb_melo_br -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 1, "Hello! This is MeloTTS with a British English accent running at forty four kilohertz.")
                R.id.rb_melo_in -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 2, "Hello! This is MeloTTS with an Indian English accent accelerated on Snapdragon 8 Elite.")
                R.id.rb_melo_au -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 3, "Hello! This is MeloTTS with an Australian English accent running on your Galaxy S25 Ultra.")
                R.id.rb_melo_def -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 4, "Hello! This is MeloTTS default English voice synthesized on Qualcomm AI Engine Direct.")
                // NEUTTS-2E VOICES (4 Speakers, sid=0..3)
                R.id.rb_neu_emily -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 0, "I can't believe it's finally here! This is Emily speaking with emotional prosody.")
                R.id.rb_neu_paul -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 1, "My name is Paul, and I am synthesizing speech on the Qualcomm Hexagon processor.")
                R.id.rb_neu_sophie -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 2, "Hello, I am Sophie. Notice how the emotional parameters shape the rhythm and tone.")
                R.id.rb_neu_steven -> selectVoice(TtsManager.MELO_EN_MODEL_DIR, 3, "Hi there, Steven here! Ready to test real-time neural speech synthesis.")
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

        tvDebugLog.text = AppLog.getRecentLogs(80)

        startTtsInit(
            preferredModel = TtsManager.getActiveModelDir(applicationContext),
            forceMode = TtsManager.getForcedBackendMode(applicationContext)
        )
    }

    private fun setupTabs() {
        btnTabModels.setOnClickListener { switchTab(showModelsTab = true) }
        btnTabVoice.setOnClickListener { switchTab(showModelsTab = false) }
        btnGoToVoiceTab.setOnClickListener { switchTab(showModelsTab = false) }
        btnChangeModel.setOnClickListener { switchTab(showModelsTab = true) }
        // Start on Tab 2 (Voice & Settings) by default, with Tab 1 one tap away
        switchTab(showModelsTab = false)
    }

    private fun switchTab(showModelsTab: Boolean) {
        if (showModelsTab) {
            layoutTabModels.visibility = View.VISIBLE
            layoutTabVoice.visibility = View.GONE
            btnTabModels.setBackgroundColor(Color.parseColor("#0D47A1"))
            btnTabVoice.setBackgroundColor(Color.parseColor("#52606D"))
        } else {
            layoutTabModels.visibility = View.GONE
            layoutTabVoice.visibility = View.VISIBLE
            btnTabModels.setBackgroundColor(Color.parseColor("#52606D"))
            btnTabVoice.setBackgroundColor(Color.parseColor("#0D47A1"))
        }
    }

    private fun updateUiForModelFamily(
        family: String,
        voiceDir: String,
        speakerId: Int,
        triggerInit: Boolean
    ) {
        suppressVoiceListener = true
        val isPiper = family == TtsManager.MODEL_FAMILY_PIPER
        val isMelo = family == TtsManager.MODEL_FAMILY_MELOTTS
        val isNeu = family == TtsManager.MODEL_FAMILY_NEUTTS

        rbVoiceNarrateur.visibility = if (isPiper) View.VISIBLE else View.GONE
        rbVoiceEn.visibility = if (isPiper) View.VISIBLE else View.GONE
        rbVoiceFr.visibility = if (isPiper) View.VISIBLE else View.GONE

        rbMeloUs.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloBr.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloIn.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloAu.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloDef.visibility = if (isMelo) View.VISIBLE else View.GONE

        rbNeuEmily.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuPaul.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuSophie.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuSteven.visibility = if (isNeu) View.VISIBLE else View.GONE

        layoutEmotions.visibility = if (isNeu) View.VISIBLE else View.GONE

        when (family) {
            TtsManager.MODEL_FAMILY_MELOTTS -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🎵 MeloTTS (44.1 kHz)"
                tvModelArchDetails.text = "Architecture : MeloTTS VITS2 (Qualcomm AI Hub v0.67.0)\n• NPU : encoder.bin, sdp.bin, flow.bin, decoder.bin (HTP V79)\n• CPU Fallback : model.int8.onnx + lexicon.txt (44100 Hz, 5 Accents)"
                when (speakerId) {
                    1 -> rgVoices.check(R.id.rb_melo_br)
                    2 -> rgVoices.check(R.id.rb_melo_in)
                    3 -> rgVoices.check(R.id.rb_melo_au)
                    4 -> rgVoices.check(R.id.rb_melo_def)
                    else -> rgVoices.check(R.id.rb_melo_us)
                }
                if (text.text.isNullOrBlank() || text.text.toString().startsWith("Bonjour")) {
                    text.setText("Hello! This is MeloTTS running on your Samsung Galaxy S25 Ultra Qualcomm Hexagon V79 NPU.")
                }
            }
            TtsManager.MODEL_FAMILY_NEUTTS -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🎭 NeuTTS-2E (Émotionnel)"
                tvModelArchDetails.text = "Architecture : NeuTTS-2E (4 Locuteurs × 7 Émotions)\n• Voix : Emily, Paul, Sophie, Steven\n• Exécution NPU QNN HTP V79 / CPU avec modulation émotionnelle temps réel"
                when (speakerId) {
                    1 -> rgVoices.check(R.id.rb_neu_paul)
                    2 -> rgVoices.check(R.id.rb_neu_sophie)
                    3 -> rgVoices.check(R.id.rb_neu_steven)
                    else -> rgVoices.check(R.id.rb_neu_emily)
                }
                if (text.text.isNullOrBlank() || text.text.toString().startsWith("Bonjour")) {
                    text.setText("I can't believe it's finally here! Testing emotional speech synthesis on the Galaxy S25 Ultra.")
                }
            }
            else -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🎙️ Piper TTS (VITS)"
                tvModelArchDetails.text = "Architecture : Piper TTS (VITS 4-graphes QNN HTP V79 + eSpeak-NG)\n• NPU : encoder.bin, sdp.bin, flow.bin, decoder.bin\n• CPU Fallback : narrateur.onnx / fr_FR-siwis-low.onnx (22050 Hz)"
                when (voiceDir) {
                    TtsManager.EN_MODEL_DIR -> {
                        rgVoices.check(R.id.rb_voice_en)
                        text.setText("Hello! This voice is accelerated by Qualcomm Hexagon NPU on your Samsung Galaxy S25 Ultra.")
                    }
                    TtsManager.FR_MODEL_DIR -> {
                        rgVoices.check(R.id.rb_voice_fr)
                        text.setText("Bonjour ! Cette voix française est synthétisée avec le modèle Siwis.")
                    }
                    else -> {
                        rgVoices.check(R.id.rb_voice_narrateur)
                        text.setText("Bonjour ! Cette voix est celle de votre modèle narrateur personnalisé.")
                    }
                }
            }
        }
        suppressVoiceListener = false

        if (triggerInit) {
            startTtsInit(
                preferredModel = TtsManager.getActiveModelDir(applicationContext),
                forceMode = TtsManager.getForcedBackendMode(applicationContext)
            )
        }
    }

    private fun selectVoice(targetDir: String, speakerId: Int, sampleText: String) {
        val currentDir = TtsManager.getActiveModelDir(applicationContext)
        TtsManager.setSavedSpeakerId(speakerId, applicationContext)
        sid.setText(speakerId.toString())
        text.setText(sampleText)
        AppLog.i("Voice selected: dir=$targetDir, sid=$speakerId")
        if (currentDir != targetDir || tts == null) {
            TtsManager.setActiveModelDir(targetDir, applicationContext)
            TtsManager.reset()
            startTtsInit(preferredModel = targetDir)
        } else {
            Toast.makeText(applicationContext, "Locuteur sid=$speakerId sélectionné", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupEmotionButtons() {
        findViewById<Button>(R.id.btn_emo_neutral).setOnClickListener { applyEmotionPreset("neutral") }
        findViewById<Button>(R.id.btn_emo_happy).setOnClickListener { applyEmotionPreset("happy") }
        findViewById<Button>(R.id.btn_emo_sad).setOnClickListener { applyEmotionPreset("sad") }
        findViewById<Button>(R.id.btn_emo_angry).setOnClickListener { applyEmotionPreset("angry") }
        findViewById<Button>(R.id.btn_emo_surprised).setOnClickListener { applyEmotionPreset("surprised") }
        findViewById<Button>(R.id.btn_emo_fearful).setOnClickListener { applyEmotionPreset("fearful") }
        findViewById<Button>(R.id.btn_emo_disgusted).setOnClickListener { applyEmotionPreset("disgusted") }
    }

    private fun applyEmotionPreset(emotion: String) {
        TtsManager.setSavedEmotion(emotion, applicationContext)
        tvEmotionStatus.text = "🎭 Émotion NeuTTS-2E active : ${emotion.replaceFirstChar { it.uppercase() }}"
        when (emotion) {
            "happy" -> {
                currentSpeed = 1.12f
                currentNoiseScale = 0.72f
                currentNoiseW = 0.85f
                currentSilenceScale = 0.15f
            }
            "sad" -> {
                currentSpeed = 0.84f
                currentNoiseScale = 0.35f
                currentNoiseW = 0.40f
                currentSilenceScale = 0.40f
            }
            "angry" -> {
                currentSpeed = 1.18f
                currentNoiseScale = 0.80f
                currentNoiseW = 0.90f
                currentSilenceScale = 0.10f
            }
            "surprised" -> {
                currentSpeed = 1.15f
                currentNoiseScale = 0.85f
                currentNoiseW = 0.95f
                currentSilenceScale = 0.15f
            }
            "fearful" -> {
                currentSpeed = 1.08f
                currentNoiseScale = 0.75f
                currentNoiseW = 0.92f
                currentSilenceScale = 0.28f
            }
            "disgusted" -> {
                currentSpeed = 0.88f
                currentNoiseScale = 0.55f
                currentNoiseW = 0.65f
                currentSilenceScale = 0.32f
            }
            else -> { // neutral
                currentSpeed = 1.00f
                currentNoiseScale = 0.55f
                currentNoiseW = 0.60f
                currentSilenceScale = 0.20f
            }
        }
        syncSlidersFromState()
        persistCurrentAcousticParams()
        AppLog.i("NeuTTS-2E Emotion applied: '$emotion' (speed=$currentSpeed, noise=$currentNoiseScale, noise_w=$currentNoiseW, silence=$currentSilenceScale)")
    }

    private fun persistCurrentAcousticParams() {
        TtsManager.saveAcousticParams(
            context = applicationContext,
            speed = currentSpeed,
            noiseScale = currentNoiseScale,
            noiseW = currentNoiseW,
            silenceScale = currentSilenceScale
        )
    }

    private fun syncSlidersFromState() {
        sbSpeed.progress = ((currentSpeed - 0.50f) * 100.0f).toInt().coerceIn(0, 150)
        speed.setText(String.format(Locale.ROOT, "%.2f", currentSpeed))
        tvSpeedVal.text = String.format(Locale.ROOT, "Vitesse : %.2fx", currentSpeed)

        sbNoiseScale.progress = (currentNoiseScale * 100).toInt().coerceIn(0, 100)
        tvNoiseScaleVal.text = String.format(Locale.ROOT, "Bruit Timbre (noise) : %.2f", currentNoiseScale)

        sbNoiseW.progress = (currentNoiseW * 100).toInt().coerceIn(0, 100)
        tvNoiseWVal.text = String.format(Locale.ROOT, "Bruit Rythme (noise_w) : %.2f", currentNoiseW)

        sbSilence.progress = (currentSilenceScale * 100).toInt().coerceIn(0, 100)
        tvSilenceVal.text = String.format(Locale.ROOT, "Pauses (silence) : %.2f", currentSilenceScale)
    }

    private fun setupSliders() {
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeed = 0.50f + (progress / 100.0f)
                speed.setText(String.format(Locale.ROOT, "%.2f", currentSpeed))
                tvSpeedVal.text = String.format(Locale.ROOT, "Vitesse : %.2fx", currentSpeed)
                if (fromUser) persistCurrentAcousticParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbNoiseScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentNoiseScale = progress / 100.0f
                tvNoiseScaleVal.text = String.format(Locale.ROOT, "Bruit Timbre (noise) : %.2f", currentNoiseScale)
                if (fromUser) persistCurrentAcousticParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbNoiseW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentNoiseW = progress / 100.0f
                tvNoiseWVal.text = String.format(Locale.ROOT, "Bruit Rythme (noise_w) : %.2f", currentNoiseW)
                if (fromUser) persistCurrentAcousticParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSilenceScale = progress / 100.0f
                tvSilenceVal.text = String.format(Locale.ROOT, "Pauses (silence) : %.2f", currentSilenceScale)
                if (fromUser) persistCurrentAcousticParams()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPresetSmooth.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.33f, noiseW = 0.33f)
            Toast.makeText(applicationContext, "Profil Doux anti-métallique appliqué et sauvegardé (0.33 / 0.33)", Toast.LENGTH_SHORT).show()
        }

        btnPresetNarrateur.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.50f, noiseW = 0.33f)
            Toast.makeText(applicationContext, "Profil Config Narrateur appliqué et sauvegardé (0.50 / 0.33)", Toast.LENGTH_SHORT).show()
        }

        btnPresetStandard.setOnClickListener {
            applyAcousticPreset(noiseScale = 0.67f, noiseW = 0.80f)
            Toast.makeText(applicationContext, "Profil Piper Standard appliqué et sauvegardé (0.67 / 0.80)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAcousticPreset(noiseScale: Float, noiseW: Float) {
        currentNoiseScale = noiseScale
        currentNoiseW = noiseW
        syncSlidersFromState()
        persistCurrentAcousticParams()
    }

    private fun onSetMode(mode: String) {
        TtsManager.reset()
        TtsManager.setForcedBackendMode(mode, applicationContext)
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

        val sidInt = TtsManager.getSavedSpeakerId(applicationContext)
        val speedFloat = currentSpeed
        val noiseScale = currentNoiseScale
        val noiseW = currentNoiseW
        val silenceScale = currentSilenceScale
        val activeDir = TtsManager.getActiveModelDir(applicationContext)
        val activeFamily = TtsManager.getActiveModelFamily(applicationContext)
        val useMelo = TtsManager.usesMeloLexicon(activeDir)

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
                val synthSummary = "family=$activeFamily, dir=$activeDir, sid=$sidInt, speed=$speedFloat, noise=$noiseScale, noise_w=$noiseW"
                AppLog.markSynthesisStart(applicationContext, synthSummary)
                AppLog.i(String.format(
                    Locale.ROOT,
                    "Synthesis requested [%s]: \"%s\" (sid=%d, speed=%.2f, noise=%.2f, noise_w=%.2f, silence=%.2f)",
                    activeFamily.uppercase(Locale.ROOT), textStr.take(40), sidInt, speedFloat, noiseScale, noiseW, silenceScale
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
                val inputText = if (useMelo) {
                    AppLog.i("Using native MeloTTS Lexicon & Tone frontend (sid=$sidInt)...")
                    textStr
                } else {
                    genConfig.phonemeCodepoints = TtsManager.phonemize(activeTts, textStr)
                    ""
                }

                val audio = activeTts.generateWithConfigAndCallback(
                    text = inputText,
                    config = genConfig,
                    callback = this::callback
                )
                AppLog.markSynthesisDone(applicationContext)

                val elapsedMs = System.currentTimeMillis() - genStart
                val numSamples = audio.samples.size
                val sampleRate = activeTts.sampleRate()
                val audioDurationSec = if (sampleRate > 0) numSamples.toDouble() / sampleRate else 0.0
                val rtf = if (audioDurationSec > 0) (elapsedMs / 1000.0) / audioDurationSec else 0.0

                AppLog.i(String.format(
                    Locale.ROOT,
                    "Generated %d samples (%.2fs @ %dHz) in %d ms | RTF: %.3fx",
                    numSamples, audioDurationSec, sampleRate, elapsedMs, rtf
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
                AppLog.markSynthesisDone(applicationContext)
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
