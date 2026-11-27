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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    @Volatile
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

    // Reverb-TTS Quantization Selector (4-bit vs 8-bit NPU)
    private lateinit var layoutReverbQuantization: LinearLayout
    private lateinit var rgReverbQuantization: RadioGroup
    private lateinit var rbReverb4bit: RadioButton
    private lateinit var rbReverb8bit: RadioButton

    // Model Download Card (Reverb-TTS & LiteRT Hugging Face models)
    private lateinit var layoutModelDownload: LinearLayout
    private lateinit var tvDownloadStatus: TextView
    private lateinit var btnDownloadModel: Button
    private val activeDownloadCancel = AtomicBoolean(false)

    // Voices & Emotions
    private lateinit var rgVoices: RadioGroup
    // Piper TTS Voices
    private lateinit var rbVoiceNarrateur: RadioButton
    private lateinit var rbVoiceEn: RadioButton
    private lateinit var rbVoiceFr: RadioButton
    // MeloTTS Voices
    private lateinit var rbMeloUs: RadioButton
    private lateinit var rbMeloBr: RadioButton
    private lateinit var rbMeloIn: RadioButton
    private lateinit var rbMeloAu: RadioButton
    private lateinit var rbMeloDef: RadioButton
    // NeuTTS-2E Voices
    private lateinit var rbNeuEmily: RadioButton
    private lateinit var rbNeuPaul: RadioButton
    private lateinit var rbNeuSophie: RadioButton
    private lateinit var rbNeuSteven: RadioButton
    // Reverb-TTS Voices
    private lateinit var rbReverbFrFemale: RadioButton
    private lateinit var rbReverbFrMale: RadioButton
    private lateinit var rbReverbEnNarrator: RadioButton
    private lateinit var rbReverbEnWhisper: RadioButton
    private lateinit var rbReverbEnAngry: RadioButton
    private lateinit var rbReverbEnMale1: RadioButton
    private lateinit var rbReverbEnMale2: RadioButton
    private lateinit var rbReverbEsMale: RadioButton
    private lateinit var rbReverbDeFemale: RadioButton
    private lateinit var rbReverbItMale: RadioButton
    // LiteRT Voices
    private lateinit var rbLitertPocketEstelle: RadioButton
    private lateinit var rbLitertPocketAlba: RadioButton
    private lateinit var rbLitertPocketMarius: RadioButton
    private lateinit var rbLitertKokoroSiwis: RadioButton
    private lateinit var rbLitertKokoroHeart: RadioButton
    private lateinit var rbLitertKokoroBella: RadioButton
    private lateinit var rbLitertKokoroEmma: RadioButton
    private lateinit var rbLitertMatchaLj: RadioButton
    private lateinit var rbLitertPhononFreya: RadioButton
    private lateinit var rbLitertPhononClaire: RadioButton

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
    private val audioTrackLock = Any()
    private val initGeneration = AtomicInteger(0)
    private var mediaPlayer: MediaPlayer? = null
    private var track: AudioTrack? = null

    private val logListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                tvDebugLog.text = AppLog.getRecentLogs(80)
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

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

        // Reverb Quantization & Download views
        layoutReverbQuantization = findViewById(R.id.layout_reverb_quantization)
        rgReverbQuantization = findViewById(R.id.rg_reverb_quantization)
        rbReverb4bit = findViewById(R.id.rb_reverb_4bit)
        rbReverb8bit = findViewById(R.id.rb_reverb_8bit)

        layoutModelDownload = findViewById(R.id.layout_model_download)
        tvDownloadStatus = findViewById(R.id.tv_download_status)
        btnDownloadModel = findViewById(R.id.btn_download_model)

        // Voices
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

        rbReverbFrFemale = findViewById(R.id.rb_reverb_fr_female)
        rbReverbFrMale = findViewById(R.id.rb_reverb_fr_male)
        rbReverbEnNarrator = findViewById(R.id.rb_reverb_en_narrator)
        rbReverbEnWhisper = findViewById(R.id.rb_reverb_en_whisper)
        rbReverbEnAngry = findViewById(R.id.rb_reverb_en_angry)
        rbReverbEnMale1 = findViewById(R.id.rb_reverb_en_male1)
        rbReverbEnMale2 = findViewById(R.id.rb_reverb_en_male2)
        rbReverbEsMale = findViewById(R.id.rb_reverb_es_male)
        rbReverbDeFemale = findViewById(R.id.rb_reverb_de_female)
        rbReverbItMale = findViewById(R.id.rb_reverb_it_male)

        rbLitertPocketEstelle = findViewById(R.id.rb_litert_pocket_estelle)
        rbLitertPocketAlba = findViewById(R.id.rb_litert_pocket_alba)
        rbLitertPocketMarius = findViewById(R.id.rb_litert_pocket_marius)
        rbLitertKokoroSiwis = findViewById(R.id.rb_litert_kokoro_siwis)
        rbLitertKokoroHeart = findViewById(R.id.rb_litert_kokoro_heart)
        rbLitertKokoroBella = findViewById(R.id.rb_litert_kokoro_bella)
        rbLitertKokoroEmma = findViewById(R.id.rb_litert_kokoro_emma)
        rbLitertMatchaLj = findViewById(R.id.rb_litert_matcha_lj)
        rbLitertPhononFreya = findViewById(R.id.rb_litert_phonon_freya)
        rbLitertPhononClaire = findViewById(R.id.rb_litert_phonon_claire)

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

        btnDownloadModel.setOnClickListener { onClickDownloadModel() }

        rgReverbQuantization.setOnCheckedChangeListener { _, checkedId ->
            val newQuant = if (checkedId == R.id.rb_reverb_8bit) ReverbTtsEngine.QUANT_8BIT else ReverbTtsEngine.QUANT_4BIT
            AppLog.i("Tab 2: User changed Reverb precision -> $newQuant")
            ReverbTtsEngine.setSavedPrecision(applicationContext, newQuant)
            refreshModelDownloadCard()
            startTtsInit()
        }

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
            TtsManager.MODEL_FAMILY_REVERB -> rgModelFamily.check(R.id.rb_family_reverb)
            LiteRtTtsEngine.FAMILY_POCKET -> rgModelFamily.check(R.id.rb_family_litert_pocket)
            LiteRtTtsEngine.FAMILY_KOKORO -> rgModelFamily.check(R.id.rb_family_litert_kokoro)
            LiteRtTtsEngine.FAMILY_MATCHA -> rgModelFamily.check(R.id.rb_family_litert_matcha)
            LiteRtTtsEngine.FAMILY_PHONON -> rgModelFamily.check(R.id.rb_family_litert_phonon)
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
                R.id.rb_family_reverb -> {
                    AppLog.i("Tab 1: User selected Model Family -> REVERB-TTS (Zero-Shot 24kHz NPU)")
                    TtsManager.setActiveModelFamily(TtsManager.MODEL_FAMILY_REVERB, applicationContext)
                    TtsManager.reset()
                    updateUiForModelFamily(TtsManager.MODEL_FAMILY_REVERB, "", 0, triggerInit = true)
                }
                R.id.rb_family_litert_pocket -> {
                    AppLog.i("Tab 1: User selected Model Family -> LiteRT PocketTTS 100M")
                    TtsManager.setActiveModelFamily(LiteRtTtsEngine.FAMILY_POCKET, applicationContext)
                    TtsManager.reset()
                    updateUiForModelFamily(LiteRtTtsEngine.FAMILY_POCKET, "", 0, triggerInit = true)
                }
                R.id.rb_family_litert_kokoro -> {
                    AppLog.i("Tab 1: User selected Model Family -> LiteRT Kokoro-82M")
                    TtsManager.setActiveModelFamily(LiteRtTtsEngine.FAMILY_KOKORO, applicationContext)
                    TtsManager.reset()
                    updateUiForModelFamily(LiteRtTtsEngine.FAMILY_KOKORO, "", 0, triggerInit = true)
                }
                R.id.rb_family_litert_matcha -> {
                    AppLog.i("Tab 1: User selected Model Family -> LiteRT Matcha-TTS")
                    TtsManager.setActiveModelFamily(LiteRtTtsEngine.FAMILY_MATCHA, applicationContext)
                    TtsManager.reset()
                    updateUiForModelFamily(LiteRtTtsEngine.FAMILY_MATCHA, "", 0, triggerInit = true)
                }
                R.id.rb_family_litert_phonon -> {
                    AppLog.i("Tab 1: User selected Model Family -> LiteRT Phonon-31M")
                    TtsManager.setActiveModelFamily(LiteRtTtsEngine.FAMILY_PHONON, applicationContext)
                    TtsManager.reset()
                    updateUiForModelFamily(LiteRtTtsEngine.FAMILY_PHONON, "", 0, triggerInit = true)
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
                // REVERB-TTS VOICES
                R.id.rb_reverb_fr_female -> selectReverbVoice("fr_female_standard")
                R.id.rb_reverb_fr_male -> selectReverbVoice("fr_male_standard")
                R.id.rb_reverb_en_narrator -> selectReverbVoice("en_female_narrator")
                R.id.rb_reverb_en_whisper -> selectReverbVoice("en_female_whisper")
                R.id.rb_reverb_en_angry -> selectReverbVoice("en_male_angry")
                R.id.rb_reverb_en_male1 -> selectReverbVoice("en_male_narrator_1")
                R.id.rb_reverb_en_male2 -> selectReverbVoice("en_male_narrator_2")
                R.id.rb_reverb_es_male -> selectReverbVoice("es_male_standard_1")
                R.id.rb_reverb_de_female -> selectReverbVoice("de_female_standard")
                R.id.rb_reverb_it_male -> selectReverbVoice("it_male_standard")
                // LITERT VOICES
                R.id.rb_litert_pocket_estelle -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_POCKET, "estelle", "Bonjour ! Cette voix française est synthétisée en local avec Google LiteRT sur votre Galaxy S25 Ultra.")
                R.id.rb_litert_pocket_alba -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_POCKET, "alba", "Hello! This voice is synthesized on-device with Google LiteRT on your Galaxy S25 Ultra.")
                R.id.rb_litert_pocket_marius -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_POCKET, "marius", "Hello! This is Marius speaking with PocketTTS on Qualcomm Snapdragon.")
                R.id.rb_litert_kokoro_siwis -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_KOKORO, "ff_siwis", "Bonjour ! Cette voix française est synthétisée par Kokoro avec le timbre Siwis.")
                R.id.rb_litert_kokoro_heart -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_KOKORO, "af_heart", "Hello! This is Heart speaking with Kokoro on Google AI Edge LiteRT.")
                R.id.rb_litert_kokoro_bella -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_KOKORO, "af_bella", "Hello! This is Bella speaking with Kokoro text to speech engine.")
                R.id.rb_litert_kokoro_emma -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_KOKORO, "bf_emma", "Good afternoon! This is Emma speaking with a British English accent.")
                R.id.rb_litert_matcha_lj -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_MATCHA, "ljspeech", "Matcha-TTS is a fast, highly expressive neural text-to-speech model.")
                R.id.rb_litert_phonon_freya -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_PHONON, "freya", "Hello! This is Freya speaking with Phonon thirty-one M.")
                R.id.rb_litert_phonon_claire -> selectLiteRtVoice(LiteRtTtsEngine.FAMILY_PHONON, "claire", "Hello! This is Claire speaking with Phonon thirty-one M.")
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

        AppLog.addListener(logListener)
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

    private fun refreshModelDownloadCard() {
        val family = TtsManager.getActiveModelFamily(applicationContext)
        if (family == TtsManager.MODEL_FAMILY_REVERB) {
            val quant = ReverbTtsEngine.getSavedPrecision(applicationContext)
            val voiceId = ReverbTtsEngine.getSavedVoice(applicationContext)
            val missing = ReverbTtsEngine.missingFiles(applicationContext, quant, voiceId)
            layoutModelDownload.visibility = View.VISIBLE
            if (missing.isEmpty()) {
                tvDownloadStatus.text = "Statut : ✅ Modèle Reverb-TTS (${quant.uppercase(Locale.ROOT)}) prêt en local"
                tvDownloadStatus.setTextColor(Color.parseColor("#1B5E20"))
                btnDownloadModel.visibility = View.GONE
            } else {
                val totalMb = missing.sumOf { it.approxMb }
                tvDownloadStatus.text = "Fichiers manquants (~$totalMb Mo) : ${missing.joinToString { it.name }}"
                tvDownloadStatus.setTextColor(Color.parseColor("#B71C1C"))
                btnDownloadModel.visibility = View.VISIBLE
                btnDownloadModel.isEnabled = true
                btnDownloadModel.text = "⬇️ Télécharger Reverb-TTS ${quant.uppercase(Locale.ROOT)} (~$totalMb Mo)"
            }
        } else if (LiteRtTtsEngine.isLiteRtFamily(family)) {
            val voiceId = LiteRtTtsEngine.getSavedVoice(applicationContext, family)
            val mode = TtsManager.getForcedBackendMode(applicationContext)
            val missing = LiteRtTtsEngine.missingFiles(applicationContext, family, voiceId, mode)
            layoutModelDownload.visibility = View.VISIBLE
            if (missing.isEmpty()) {
                tvDownloadStatus.text = "Statut : ✅ Modèle ${LiteRtTtsEngine.familyLabel(family)} prêt en local"
                tvDownloadStatus.setTextColor(Color.parseColor("#1B5E20"))
                btnDownloadModel.visibility = View.GONE
            } else {
                val totalMb = missing.sumOf { it.approxMb }
                tvDownloadStatus.text = "Fichiers manquants (~$totalMb Mo) : ${missing.joinToString { it.name }}"
                tvDownloadStatus.setTextColor(Color.parseColor("#B71C1C"))
                btnDownloadModel.visibility = View.VISIBLE
                btnDownloadModel.isEnabled = true
                btnDownloadModel.text = "⬇️ Télécharger ${LiteRtTtsEngine.familyLabel(family)} (~$totalMb Mo)"
            }
        } else {
            layoutModelDownload.visibility = View.GONE
        }
    }

    private fun onClickDownloadModel() {
        val activeFamily = TtsManager.getActiveModelFamily(applicationContext)
        activeDownloadCancel.set(false)
        btnDownloadModel.isEnabled = false
        generate.isEnabled = false
        tvDownloadStatus.text = "Démarrage du téléchargement depuis Hugging Face..."

        Thread {
            try {
                if (activeFamily == TtsManager.MODEL_FAMILY_REVERB) {
                    val quant = ReverbTtsEngine.getSavedPrecision(applicationContext)
                    val voiceId = ReverbTtsEngine.getSavedVoice(applicationContext)
                    ReverbTtsEngine.download(
                        context = applicationContext,
                        quant = quant,
                        voiceId = voiceId,
                        cancel = activeDownloadCancel
                    ) { fileName, downloaded, total ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                            val dMb = downloaded / (1024 * 1024)
                            val tMb = total / (1024 * 1024)
                            tvDownloadStatus.text = "⬇️ $fileName : $pct% ($dMb Mo / $tMb Mo)"
                        }
                    }
                } else if (LiteRtTtsEngine.isLiteRtFamily(activeFamily)) {
                    val voiceId = LiteRtTtsEngine.getSavedVoice(applicationContext, activeFamily)
                    val mode = TtsManager.getForcedBackendMode(applicationContext)
                    LiteRtTtsEngine.download(
                        context = applicationContext,
                        family = activeFamily,
                        voiceId = voiceId,
                        mode = mode,
                        cancel = activeDownloadCancel
                    ) { fileName, downloaded, total ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                            val dMb = downloaded / (1024 * 1024)
                            val tMb = total / (1024 * 1024)
                            tvDownloadStatus.text = "⬇️ $fileName : $pct% ($dMb Mo / $tMb Mo)"
                        }
                    }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(applicationContext, "Téléchargement terminé avec succès !", Toast.LENGTH_SHORT).show()
                    refreshModelDownloadCard()
                    startTtsInit()
                }
            } catch (e: Throwable) {
                AppLog.e("Model download failed: ${e.message}", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tvDownloadStatus.text = "Erreur téléchargement : ${e.message}"
                    tvDownloadStatus.setTextColor(Color.parseColor("#B71C1C"))
                    btnDownloadModel.isEnabled = true
                    Toast.makeText(applicationContext, "Échec téléchargement : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
        val isReverb = family == TtsManager.MODEL_FAMILY_REVERB
        val isPocket = family == LiteRtTtsEngine.FAMILY_POCKET
        val isKokoro = family == LiteRtTtsEngine.FAMILY_KOKORO
        val isMatcha = family == LiteRtTtsEngine.FAMILY_MATCHA
        val isPhonon = family == LiteRtTtsEngine.FAMILY_PHONON

        // Piper
        rbVoiceNarrateur.visibility = if (isPiper) View.VISIBLE else View.GONE
        rbVoiceEn.visibility = if (isPiper) View.VISIBLE else View.GONE
        rbVoiceFr.visibility = if (isPiper) View.VISIBLE else View.GONE

        // MeloTTS
        rbMeloUs.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloBr.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloIn.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloAu.visibility = if (isMelo) View.VISIBLE else View.GONE
        rbMeloDef.visibility = if (isMelo) View.VISIBLE else View.GONE

        // NeuTTS-2E
        rbNeuEmily.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuPaul.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuSophie.visibility = if (isNeu) View.VISIBLE else View.GONE
        rbNeuSteven.visibility = if (isNeu) View.VISIBLE else View.GONE
        layoutEmotions.visibility = if (isNeu) View.VISIBLE else View.GONE

        // Reverb-TTS
        layoutReverbQuantization.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbFrFemale.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbFrMale.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEnNarrator.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEnWhisper.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEnAngry.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEnMale1.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEnMale2.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbEsMale.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbDeFemale.visibility = if (isReverb) View.VISIBLE else View.GONE
        rbReverbItMale.visibility = if (isReverb) View.VISIBLE else View.GONE

        // LiteRT
        rbLitertPocketEstelle.visibility = if (isPocket) View.VISIBLE else View.GONE
        rbLitertPocketAlba.visibility = if (isPocket) View.VISIBLE else View.GONE
        rbLitertPocketMarius.visibility = if (isPocket) View.VISIBLE else View.GONE

        rbLitertKokoroSiwis.visibility = if (isKokoro) View.VISIBLE else View.GONE
        rbLitertKokoroHeart.visibility = if (isKokoro) View.VISIBLE else View.GONE
        rbLitertKokoroBella.visibility = if (isKokoro) View.VISIBLE else View.GONE
        rbLitertKokoroEmma.visibility = if (isKokoro) View.VISIBLE else View.GONE

        rbLitertMatchaLj.visibility = if (isMatcha) View.VISIBLE else View.GONE

        rbLitertPhononFreya.visibility = if (isPhonon) View.VISIBLE else View.GONE
        rbLitertPhononClaire.visibility = if (isPhonon) View.VISIBLE else View.GONE

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
            TtsManager.MODEL_FAMILY_REVERB -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🔊 Reverb-TTS (Zero-Shot 24 kHz)"
                tvModelArchDetails.text = "Architecture : Reverb-TTS 297M (Software Mansion / OmniVoice distillé Qwen3)\n• NPU : Qualcomm Hexagon HTP V79/V81 INT4 QNN PTE (Snapdragon 8 Elite SM8750)\n• Précision NPU au choix : INT4 (191 Mo) ou INT8 W8A8 Studio (307 Mo)\n• Codec : Higgs Audio v2 tokenizer 24 kHz (8 codebooks, 25 fps)"

                val quant = ReverbTtsEngine.getSavedPrecision(applicationContext)
                if (quant == ReverbTtsEngine.QUANT_8BIT) {
                    rbReverb8bit.isChecked = true
                } else {
                    rbReverb4bit.isChecked = true
                }

                val savedVoice = ReverbTtsEngine.getSavedVoice(applicationContext)
                when (savedVoice) {
                    "fr_male_standard" -> rgVoices.check(R.id.rb_reverb_fr_male)
                    "en_female_narrator" -> rgVoices.check(R.id.rb_reverb_en_narrator)
                    "en_female_whisper" -> rgVoices.check(R.id.rb_reverb_en_whisper)
                    "en_male_angry" -> rgVoices.check(R.id.rb_reverb_en_angry)
                    "en_male_narrator_1" -> rgVoices.check(R.id.rb_reverb_en_male1)
                    "en_male_narrator_2" -> rgVoices.check(R.id.rb_reverb_en_male2)
                    "es_male_standard_1" -> rgVoices.check(R.id.rb_reverb_es_male)
                    "de_female_standard" -> rgVoices.check(R.id.rb_reverb_de_female)
                    "it_male_standard" -> rgVoices.check(R.id.rb_reverb_it_male)
                    else -> rgVoices.check(R.id.rb_reverb_fr_female)
                }
                text.setText(ReverbTtsEngine.getVoice(savedVoice).defaultText)
            }
            LiteRtTtsEngine.FAMILY_POCKET -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🇫🇷/🇺🇸 LiteRT PocketTTS 100M"
                tvModelArchDetails.text = "Architecture : PocketTTS 100M FlowLM (Kyutai / Google AI Edge LiteRT)\n• Voix Estelle (Français 122 Mo) & 8 Voix Anglaises (156 Mo)\n• Exécution MediaPipe Tasks Audio sur CPU XNNPACK (24 kHz)"
                val savedVoice = LiteRtTtsEngine.getSavedVoice(applicationContext, family)
                when (savedVoice) {
                    "alba" -> rgVoices.check(R.id.rb_litert_pocket_alba)
                    "marius" -> rgVoices.check(R.id.rb_litert_pocket_marius)
                    else -> rgVoices.check(R.id.rb_litert_pocket_estelle)
                }
                text.setText(if (savedVoice == "estelle") "Bonjour ! Cette voix française est synthétisée en local avec Google LiteRT sur votre Galaxy S25 Ultra." else "Hello! This voice is synthesized on-device with Google LiteRT on your Galaxy S25 Ultra.")
            }
            LiteRtTtsEngine.FAMILY_KOKORO -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🌐 LiteRT Kokoro-82M"
                tvModelArchDetails.text = "Architecture : Kokoro-82M StyleTTS2 (Google AI Edge LiteRT)\n• Vocodeur accéléré sur GPU Adreno ML Drift (OpenCL) + CPU XNNPACK (295 Mo)\n• 54 voix dont Siwis (FR) et Heart, Bella, Emma (EN) — 24 kHz"
                val savedVoice = LiteRtTtsEngine.getSavedVoice(applicationContext, family)
                when (savedVoice) {
                    "af_heart" -> rgVoices.check(R.id.rb_litert_kokoro_heart)
                    "af_bella" -> rgVoices.check(R.id.rb_litert_kokoro_bella)
                    "bf_emma" -> rgVoices.check(R.id.rb_litert_kokoro_emma)
                    else -> rgVoices.check(R.id.rb_litert_kokoro_siwis)
                }
                text.setText(if (savedVoice == "ff_siwis") "Bonjour ! Cette voix française est synthétisée par Kokoro avec le timbre Siwis." else "Hello! This is Kokoro speaking with Google AI Edge LiteRT on your Galaxy S25 Ultra.")
            }
            LiteRtTtsEngine.FAMILY_MATCHA -> {
                tvActiveFamilyBanner.text = "Modèle actif : ⚡ LiteRT Matcha-TTS"
                tvModelArchDetails.text = "Architecture : Matcha-TTS CFM + HiFi-GAN (LiteRT CompiledModel API)\n• GPU Adreno ML Drift + CPU XNNPACK\n• Phonémisation eSpeak-NG via piper-phonemize (22.05 kHz)"
                rgVoices.check(R.id.rb_litert_matcha_lj)
                text.setText("Matcha-TTS is a fast, highly expressive neural text-to-speech model.")
            }
            LiteRtTtsEngine.FAMILY_PHONON -> {
                tvActiveFamilyBanner.text = "Modèle actif : 🚀 LiteRT Phonon-31M"
                tvModelArchDetails.text = "Architecture : Phonon-31M Gradium FlowLM (Bundle léger 84 Mo)\n• Décodage neural rapide sur CPU XNNPACK (24 kHz)"
                val savedVoice = LiteRtTtsEngine.getSavedVoice(applicationContext, family)
                when (savedVoice) {
                    "claire" -> rgVoices.check(R.id.rb_litert_phonon_claire)
                    else -> rgVoices.check(R.id.rb_litert_phonon_freya)
                }
                text.setText("Hello! This is Phonon thirty-one M running on Google AI Edge LiteRT.")
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
        refreshModelDownloadCard()

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

    private fun selectReverbVoice(voiceId: String) {
        ReverbTtsEngine.setSavedVoice(applicationContext, voiceId)
        val voice = ReverbTtsEngine.getVoice(voiceId)
        text.setText(voice.defaultText)
        AppLog.i("Reverb-TTS voice selected: $voiceId")
        refreshModelDownloadCard()
        Toast.makeText(applicationContext, "Voix Reverb '${voice.label}' sélectionnée", Toast.LENGTH_SHORT).show()
    }

    private fun selectLiteRtVoice(family: String, voiceId: String, sampleText: String) {
        LiteRtTtsEngine.setSavedVoice(applicationContext, family, voiceId)
        text.setText(sampleText)
        AppLog.i("LiteRT voice selected: family=$family, voice=$voiceId")
        refreshModelDownloadCard()
        Toast.makeText(applicationContext, "Voix LiteRT '$voiceId' sélectionnée", Toast.LENGTH_SHORT).show()
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
        val gen = initGeneration.incrementAndGet()
        stopped = true
        generate.isEnabled = false
        generate.text = "Initialisation..."
        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false
        tvBackendStatus.text = "Backend: Initialisation en cours..."
        tvPerfMetrics.text = "Status: Initialisation du moteur..."

        val activeFamily = TtsManager.getActiveModelFamily(applicationContext)
        val mode = forceMode ?: TtsManager.getForcedBackendMode(applicationContext)

        refreshModelDownloadCard()

        Thread {
            try {
                AppLog.i("Starting TTS initialization for family=$activeFamily (mode=$mode)...")

                if (activeFamily == TtsManager.MODEL_FAMILY_REVERB) {
                    val quant = ReverbTtsEngine.getSavedPrecision(applicationContext)
                    val voiceId = ReverbTtsEngine.getSavedVoice(applicationContext)
                    val missing = ReverbTtsEngine.missingFiles(applicationContext, quant, voiceId)
                    if (missing.isNotEmpty()) {
                        val totalMb = missing.sumOf { it.approxMb }
                        runOnUiThread {
                            if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                            generate.text = "Modèle Reverb non téléchargé"
                            tvBackendStatus.text = "Reverb-TTS ${quant.uppercase(Locale.ROOT)} : Fichiers manquants (~$totalMb Mo)"
                            tvPerfMetrics.text = "Cliquez sur 'Télécharger' pour installer les fichiers NPU."
                            refreshModelDownloadCard()
                        }
                        return@Thread
                    }
                    val sampleRate = ReverbTtsEngine.prepare(applicationContext, quant, voiceId, mode)
                    if (gen != initGeneration.get() || isDestroyed) return@Thread
                    initAudioTrack(sampleRate)

                    runOnUiThread {
                        if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                        generate.text = getString(R.string.generate)
                        generate.isEnabled = true
                        tvBackendStatus.text = "Backend: ${ReverbTtsEngine.getActiveBackend()}"
                        tvPerfMetrics.text = "Prêt: ${ReverbTtsEngine.getActiveBackend()} | Voice: $voiceId | $sampleRate Hz"
                        refreshModelDownloadCard()
                        Toast.makeText(applicationContext, "Prêt : ${ReverbTtsEngine.getActiveBackend()}", Toast.LENGTH_SHORT).show()
                    }
                } else if (LiteRtTtsEngine.isLiteRtFamily(activeFamily)) {
                    val voiceId = LiteRtTtsEngine.getSavedVoice(applicationContext, activeFamily)
                    val missing = LiteRtTtsEngine.missingFiles(applicationContext, activeFamily, voiceId, mode)
                    if (missing.isNotEmpty()) {
                        val totalMb = missing.sumOf { it.approxMb }
                        runOnUiThread {
                            if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                            generate.text = "Modèle LiteRT non téléchargé"
                            tvBackendStatus.text = "${LiteRtTtsEngine.familyLabel(activeFamily)} : Fichiers manquants (~$totalMb Mo)"
                            tvPerfMetrics.text = "Cliquez sur 'Télécharger' pour installer les fichiers LiteRT."
                            refreshModelDownloadCard()
                        }
                        return@Thread
                    }
                    val sampleRate = LiteRtTtsEngine.prepare(applicationContext, activeFamily, voiceId, mode)
                    if (gen != initGeneration.get() || isDestroyed) return@Thread
                    initAudioTrack(sampleRate)

                    runOnUiThread {
                        if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                        generate.text = getString(R.string.generate)
                        generate.isEnabled = true
                        tvBackendStatus.text = "Backend: ${LiteRtTtsEngine.getActiveBackend()}"
                        tvPerfMetrics.text = "Prêt: ${LiteRtTtsEngine.getActiveBackend()} | Voice: $voiceId | $sampleRate Hz"
                        refreshModelDownloadCard()
                        Toast.makeText(applicationContext, "Prêt : ${LiteRtTtsEngine.getActiveBackend()}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val instance = TtsManager.ensureInitialized(
                        context = applicationContext,
                        preferredModelDir = preferredModel,
                        forceMode = forceMode
                    )
                    if (gen != initGeneration.get() || isDestroyed) {
                        return@Thread
                    }
                    tts = instance
                    initAudioTrack(instance.sampleRate())

                    runOnUiThread {
                        if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                        generate.text = getString(R.string.generate)
                        generate.isEnabled = true
                        tvBackendStatus.text = "Backend: ${TtsManager.getActiveBackend()}"
                        tvPerfMetrics.text = "Prêt: ${TtsManager.getActiveBackend()} | Lang: ${instance.lang()} | ${instance.sampleRate()} Hz"
                        refreshModelDownloadCard()
                        Toast.makeText(
                            applicationContext,
                            "Prêt : ${TtsManager.getActiveBackend()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("Failed to initialize TTS", e)
                runOnUiThread {
                    if (gen != initGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                    generate.text = "Échec initialisation"
                    tvBackendStatus.text = "Erreur: ${e.message}"
                    tvPerfMetrics.text = "Erreur d'init: ${e.message}"
                    refreshModelDownloadCard()
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
        if (sampleRate <= 0) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufLength = if (minBuf > 0) minBuf else sampleRate * 4 / 10

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        synchronized(audioTrackLock) {
            runCatching { track?.release() }
            track = null
            if (isDestroyed) return
            val created = AudioTrack(
                attr,
                format,
                bufLength,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (created.state == AudioTrack.STATE_INITIALIZED) {
                created.play()
                track = created
            } else {
                created.release()
            }
        }
    }

    private fun callback(samples: FloatArray): Int {
        synchronized(audioTrackLock) {
            val t = track ?: return 0
            if (t.state != AudioTrack.STATE_INITIALIZED) return 0
            return if (!stopped) {
                runCatching { t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) }
                1
            } else {
                runCatching { t.stop() }
                0
            }
        }
    }

    private fun prepareAudioTrackForSynthesis() {
        synchronized(audioTrackLock) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let { t ->
                runCatching { t.pause() }
                runCatching { t.flush() }
                runCatching { t.play() }
            }
        }
    }

    private fun setButtonsGenerating() {
        play.isEnabled = false
        save.isEnabled = false
        share.isEnabled = false
        generate.isEnabled = false
        stopped = false
        tvPerfMetrics.text = "Synthèse en cours..."
    }

    private fun onSynthesisSuccess(
        ok: Boolean,
        elapsedMs: Long,
        audioDurationSec: Double,
        rtf: Double,
        backendName: String
    ) {
        if (ok) {
            play.isEnabled = true
            save.isEnabled = true
            share.isEnabled = true
        }
        generate.isEnabled = true
        synchronized(audioTrackLock) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let { t ->
                runCatching { t.stop() }
            }
        }
        tvPerfMetrics.text = String.format(
            Locale.ROOT,
            "Temps: %d ms | Audio: %.2f s | RTF: %.3fx | %s",
            elapsedMs, audioDurationSec, rtf, backendName
        )
    }

    private fun onSynthesisError(e: Throwable) {
        generate.isEnabled = true
        tvPerfMetrics.text = "Erreur de synthèse: ${e.message}"
        Toast.makeText(
            applicationContext,
            "Erreur synthèse: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onClickGenerate() {
        val textStr = text.text.toString().trim()
        if (textStr.isEmpty()) {
            Toast.makeText(applicationContext, "Veuillez saisir un texte non vide !", Toast.LENGTH_SHORT).show()
            return
        }

        val activeFamily = TtsManager.getActiveModelFamily(applicationContext)
        val mode = TtsManager.getForcedBackendMode(applicationContext)

        if (activeFamily == TtsManager.MODEL_FAMILY_REVERB) {
            val quant = ReverbTtsEngine.getSavedPrecision(applicationContext)
            val voiceId = ReverbTtsEngine.getSavedVoice(applicationContext)
            val missing = ReverbTtsEngine.missingFiles(applicationContext, quant, voiceId)
            if (missing.isNotEmpty()) {
                Toast.makeText(applicationContext, "Veuillez d'abord télécharger le modèle Reverb-TTS !", Toast.LENGTH_SHORT).show()
                refreshModelDownloadCard()
                return
            }

            prepareAudioTrackForSynthesis()
            setButtonsGenerating()

            Thread {
                val genStart = System.currentTimeMillis()
                try {
                    AppLog.markSynthesisStart(applicationContext, "family=reverb, quant=$quant, voice=$voiceId, speed=$currentSpeed")
                    val audio = ReverbTtsEngine.synthesize(
                        context = applicationContext,
                        quant = quant,
                        voiceId = voiceId,
                        mode = mode,
                        text = textStr,
                        speed = currentSpeed,
                        callback = this::callback
                    )
                    AppLog.markSynthesisDone(applicationContext)

                    val elapsedMs = System.currentTimeMillis() - genStart
                    val numSamples = audio.samples.size
                    val sampleRate = audio.sampleRate
                    val audioDurationSec = if (sampleRate > 0) numSamples.toDouble() / sampleRate else 0.0
                    val rtf = if (audioDurationSec > 0) (elapsedMs / 1000.0) / audioDurationSec else 0.0

                    val filename = application.filesDir.absolutePath + "/generated.wav"
                    val ok = audio.samples.isNotEmpty() && (audio.save(filename) || File(filename).exists())

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        onSynthesisSuccess(ok, elapsedMs, audioDurationSec, rtf, ReverbTtsEngine.getActiveBackend())
                    }
                } catch (e: Throwable) {
                    AppLog.markSynthesisDone(applicationContext)
                    AppLog.e("Failed to generate audio (Reverb-TTS)", e)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        onSynthesisError(e)
                    }
                }
            }.start()
            return
        }

        if (LiteRtTtsEngine.isLiteRtFamily(activeFamily)) {
            val voiceId = LiteRtTtsEngine.getSavedVoice(applicationContext, activeFamily)
            val missing = LiteRtTtsEngine.missingFiles(applicationContext, activeFamily, voiceId, mode)
            if (missing.isNotEmpty()) {
                Toast.makeText(applicationContext, "Veuillez d'abord télécharger le modèle LiteRT !", Toast.LENGTH_SHORT).show()
                refreshModelDownloadCard()
                return
            }

            prepareAudioTrackForSynthesis()
            setButtonsGenerating()

            Thread {
                val genStart = System.currentTimeMillis()
                try {
                    AppLog.markSynthesisStart(applicationContext, "family=$activeFamily, voice=$voiceId, speed=$currentSpeed")
                    val audio = LiteRtTtsEngine.synthesize(
                        context = applicationContext,
                        family = activeFamily,
                        voiceId = voiceId,
                        mode = mode,
                        text = textStr,
                        speed = currentSpeed,
                        noiseScale = currentNoiseScale,
                        silenceScale = currentSilenceScale,
                        callback = this::callback
                    )
                    AppLog.markSynthesisDone(applicationContext)

                    val elapsedMs = System.currentTimeMillis() - genStart
                    val numSamples = audio.samples.size
                    val sampleRate = audio.sampleRate
                    val audioDurationSec = if (sampleRate > 0) numSamples.toDouble() / sampleRate else 0.0
                    val rtf = if (audioDurationSec > 0) (elapsedMs / 1000.0) / audioDurationSec else 0.0

                    val filename = application.filesDir.absolutePath + "/generated.wav"
                    LiteRtTtsEngine.writeWav(File(filename), audio.samples, sampleRate)
                    val ok = audio.samples.isNotEmpty()

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        onSynthesisSuccess(ok, elapsedMs, audioDurationSec, rtf, LiteRtTtsEngine.getActiveBackend())
                    }
                } catch (e: Throwable) {
                    AppLog.markSynthesisDone(applicationContext)
                    AppLog.e("Failed to generate audio (LiteRT)", e)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        onSynthesisError(e)
                    }
                }
            }.start()
            return
        }

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
        val useMelo = TtsManager.usesMeloLexicon(activeDir)

        prepareAudioTrackForSynthesis()
        setButtonsGenerating()

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
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onSynthesisSuccess(ok, elapsedMs, audioDurationSec, rtf, TtsManager.getActiveBackend())
                }
            } catch (e: Throwable) {
                AppLog.markSynthesisDone(applicationContext)
                AppLog.e("Failed to generate audio", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onSynthesisError(e)
                }
            }
        }.start()
    }

    private fun releaseMediaPlayer() {
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    private fun onClickPlay() {
        val file = File(application.filesDir, "generated.wav")
        if (!file.exists()) {
            Toast.makeText(applicationContext, "Aucun fichier audio généré", Toast.LENGTH_SHORT).show()
            return
        }
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(
            applicationContext,
            Uri.fromFile(file)
        )
        mediaPlayer?.start()
    }

    private fun onClickStop() {
        stopped = true
        val activeFamily = TtsManager.getActiveModelFamily(applicationContext)
        if (activeFamily == TtsManager.MODEL_FAMILY_REVERB) {
            ReverbTtsEngine.stop()
        } else if (LiteRtTtsEngine.isLiteRtFamily(activeFamily)) {
            LiteRtTtsEngine.stop()
        }
        val hasAudio = File(application.filesDir, "generated.wav").exists()
        play.isEnabled = hasAudio
        save.isEnabled = hasAudio
        share.isEnabled = hasAudio
        generate.isEnabled = (tts != null || activeFamily == TtsManager.MODEL_FAMILY_REVERB || LiteRtTtsEngine.isLiteRtFamily(activeFamily))
        synchronized(audioTrackLock) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let { t ->
                runCatching { t.pause() }
                runCatching { t.flush() }
            }
        }
        releaseMediaPlayer()
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
        stopped = true
        initGeneration.incrementAndGet()
        activeDownloadCancel.set(true)
        AppLog.removeListener(logListener)
        ReverbTtsEngine.release()
        LiteRtTtsEngine.release()
        synchronized(audioTrackLock) {
            runCatching { track?.release() }
            track = null
        }
        releaseMediaPlayer()
        super.onDestroy()
    }
}
