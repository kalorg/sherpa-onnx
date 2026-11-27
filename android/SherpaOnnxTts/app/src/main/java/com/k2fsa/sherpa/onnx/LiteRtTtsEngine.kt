package com.k2fsa.sherpa.onnx

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.TtsStream
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Google AI Edge LiteRT text-to-speech engines (Galaxy S25 Ultra / Snapdragon 8 Elite):
 *
 * - Matcha-TTS (litert-community/Matcha-TTS): 3 `.tflite` graphs executed with the LiteRT
 *   `CompiledModel` API (text encoder + HiFi-GAN vocoder on GPU ML Drift, CFM decoder on CPU
 *   XNNPACK), eSpeak-NG `en-us` IPA from piper-phonemize, 22.05 kHz.
 * - PocketTTS 100M, Kokoro-82M, Phonon-31M (litert-community `.litertlm` bundles) executed with
 *   the MediaPipe `TextToSpeech` task (built-in G2P, 24 kHz, streaming chunks).
 *
 * Models are downloaded on demand from Hugging Face into app-specific storage. All native
 * handles are guarded by [engineLock] so the activity and the system TTS service never use or
 * free an engine concurrently.
 */
object LiteRtTtsEngine {
    const val FAMILY_MATCHA = "litert_matcha"
    const val FAMILY_POCKET = "litert_pocket"
    const val FAMILY_KOKORO = "litert_kokoro"
    const val FAMILY_PHONON = "litert_phonon"

    private const val HF_BASE = "https://huggingface.co/litert-community"
    private const val PREFS_NAME = "sherpa_tts_prefs"
    private const val KEY_VOICE_PREFIX = "litert_voice_"
    private const val CRASH_TAG_PREFIX = "litert:"

    // Matcha-TTS file names
    private const val MATCHA_TEXTENC = "matcha_textenc_fp16.tflite"
    private const val MATCHA_DECODER = "matcha_decoder_fp16.tflite"
    private const val MATCHA_VOCODER = "matcha_vocoder_fp16.tflite"
    private const val MATCHA_EMB = "emb.bin"
    private const val MATCHA_CONFIG = "config.json"

    // .litertlm bundles
    private const val POCKET_EN = "pocket_tts_en_int8.litertlm"
    private const val POCKET_FR = "pocket_tts_fr_int8.litertlm"
    private const val KOKORO_GPU = "kokoro_int8_gpu_fp16.litertlm"
    private const val KOKORO_CPU = "kokoro_int8_fp16.litertlm"
    private const val PHONON = "phonon_int8.litertlm"

    data class LiteRtVoice(
        val id: String,
        val label: String,
        val locale: Locale,
        val sampleText: String,
    )

    data class RemoteFile(val name: String, val url: String, val approxMb: Int)

    class ModelNotDownloadedException(message: String) : IllegalStateException(message)

    private val FR_SAMPLE = "Bonjour ! Cette voix est synthétisée en local avec Google LiteRT sur votre Galaxy S25 Ultra."
    private val EN_SAMPLE = "Hello! This voice is synthesized on-device with Google LiteRT on your Galaxy S25 Ultra."

    private val VOICES: Map<String, List<LiteRtVoice>> = mapOf(
        FAMILY_MATCHA to listOf(
            LiteRtVoice("ljspeech", "🇺🇸 Matcha LJSpeech (EN — 22.05 kHz)", Locale.US, EN_SAMPLE),
        ),
        FAMILY_POCKET to listOf(
            LiteRtVoice("estelle", "🇫🇷 Estelle (Français — pocket_tts_fr 122 Mo)", Locale.FRANCE, FR_SAMPLE),
            LiteRtVoice("alba", "🇺🇸 Alba (English — pocket_tts_en 156 Mo)", Locale.US, EN_SAMPLE),
            LiteRtVoice("marius", "🇺🇸 Marius (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("javert", "🇺🇸 Javert (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("jean", "🇺🇸 Jean (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("fantine", "🇺🇸 Fantine (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("cosette", "🇺🇸 Cosette (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("eponine", "🇺🇸 Eponine (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("azelma", "🇺🇸 Azelma (English)", Locale.US, EN_SAMPLE),
        ),
        FAMILY_KOKORO to listOf(
            LiteRtVoice("ff_siwis", "🇫🇷 Siwis (Français — Kokoro)", Locale.FRANCE, FR_SAMPLE),
            LiteRtVoice("af_heart", "🇺🇸 Heart (English US)", Locale.US, EN_SAMPLE),
            LiteRtVoice("af_bella", "🇺🇸 Bella (English US)", Locale.US, EN_SAMPLE),
            LiteRtVoice("am_adam", "🇺🇸 Adam (English US)", Locale.US, EN_SAMPLE),
            LiteRtVoice("bf_emma", "🇬🇧 Emma (English UK)", Locale.UK, EN_SAMPLE),
        ),
        FAMILY_PHONON to listOf(
            LiteRtVoice("freya", "🇺🇸 Freya (English — défaut)", Locale.US, EN_SAMPLE),
            LiteRtVoice("claire", "🇺🇸 Claire (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("lukas", "🇺🇸 Lukas (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("sofia", "🇺🇸 Sofia (English)", Locale.US, EN_SAMPLE),
            LiteRtVoice("lucas", "🇺🇸 Lucas (English)", Locale.US, EN_SAMPLE),
        ),
    )

    // ---------------------------------------------------------------------------------------
    // Native engine state (guarded by engineLock)
    // ---------------------------------------------------------------------------------------
    private val engineLock = Any()
    private val downloadLock = Any()

    private var mpTts: TextToSpeech? = null
    private var mpKey: String = ""
    private var matcha: MatchaEngine? = null
    private var matchaKey: String = ""

    @Volatile
    private var activeBackend: String = "LiteRT non initialisé"

    @Volatile
    private var activeSampleRate: Int = 24000

    @Volatile
    private var currentStream: TtsStream? = null

    private val stopFlag = AtomicBoolean(false)

    // ---------------------------------------------------------------------------------------
    // Families, voices & preferences
    // ---------------------------------------------------------------------------------------
    fun isLiteRtFamily(family: String?): Boolean =
        family == FAMILY_MATCHA || family == FAMILY_POCKET || family == FAMILY_KOKORO || family == FAMILY_PHONON

    fun voicesFor(family: String): List<LiteRtVoice> = VOICES[family].orEmpty()

    fun getSavedVoice(context: Context, family: String): String {
        val voices = voicesFor(family)
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE_PREFIX + family, null)
        return voices.firstOrNull { it.id == saved }?.id ?: voices.firstOrNull()?.id.orEmpty()
    }

    fun setSavedVoice(context: Context, family: String, voiceId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE_PREFIX + family, voiceId)
            .apply()
    }

    fun localeFor(family: String, voiceId: String): Locale =
        voicesFor(family).firstOrNull { it.id == voiceId }?.locale ?: Locale.US

    fun familyLabel(family: String): String = when (family) {
        FAMILY_MATCHA -> "Matcha-TTS LiteRT"
        FAMILY_POCKET -> "PocketTTS 100M LiteRT"
        FAMILY_KOKORO -> "Kokoro-82M LiteRT"
        FAMILY_PHONON -> "Phonon-31M LiteRT"
        else -> family
    }

    fun getActiveBackend(): String = activeBackend

    fun getSampleRate(): Int = activeSampleRate

    // ---------------------------------------------------------------------------------------
    // Files & downloads
    // ---------------------------------------------------------------------------------------
    private fun storageDirs(context: Context, family: String): List<File> {
        val app = context.applicationContext
        val dirs = mutableListOf<File>()
        app.getExternalFilesDir(null)?.let { dirs.add(File(it, "litert/$family")) }
        dirs.add(File(app.filesDir, "litert/$family"))
        return dirs
    }

    private fun downloadDir(context: Context, family: String): File = storageDirs(context, family).first()

    private fun findFile(context: Context, family: String, name: String): File? =
        storageDirs(context, family).map { File(it, name) }.firstOrNull { it.isFile && it.length() > 0L }

    private fun repoFor(family: String): String = when (family) {
        FAMILY_MATCHA -> "Matcha-TTS"
        FAMILY_POCKET -> "pocket-tts"
        FAMILY_KOKORO -> "Kokoro-82M"
        FAMILY_PHONON -> "phonon-31m-en"
        else -> throw IllegalArgumentException("Unknown LiteRT family: $family")
    }

    private fun remote(family: String, name: String, mb: Int) =
        RemoteFile(name, "$HF_BASE/${repoFor(family)}/resolve/main/$name", mb)

    /** Files required to run [family]/[voiceId] in backend [mode] ("auto", "npu", "cpu"). */
    fun requiredFiles(family: String, voiceId: String, mode: String): List<RemoteFile> = when (family) {
        FAMILY_MATCHA -> listOf(
            remote(family, MATCHA_CONFIG, 1),
            remote(family, MATCHA_EMB, 1),
            remote(family, MATCHA_TEXTENC, 15),
            remote(family, MATCHA_DECODER, 23),
            remote(family, MATCHA_VOCODER, 29),
        )
        FAMILY_POCKET -> listOf(
            if (voiceId == "estelle") remote(family, POCKET_FR, 122) else remote(family, POCKET_EN, 156)
        )
        FAMILY_KOKORO -> listOf(
            if (mode == "cpu") remote(family, KOKORO_CPU, 295) else remote(family, KOKORO_GPU, 295)
        )
        FAMILY_PHONON -> listOf(remote(family, PHONON, 84))
        else -> emptyList()
    }

    /** Resolves which local bundle to load; Kokoro accepts the CPU/GPU-universal file in GPU mode. */
    private fun resolveBundle(context: Context, family: String, voiceId: String, mode: String): File? {
        if (family == FAMILY_KOKORO) {
            return if (mode == "cpu") {
                findFile(context, family, KOKORO_CPU)
            } else {
                findFile(context, family, KOKORO_GPU) ?: findFile(context, family, KOKORO_CPU)
            }
        }
        val name = requiredFiles(family, voiceId, mode).firstOrNull()?.name ?: return null
        return findFile(context, family, name)
    }

    fun missingFiles(context: Context, family: String, voiceId: String, mode: String): List<RemoteFile> {
        if (family == FAMILY_KOKORO && resolveBundle(context, family, voiceId, mode) != null) return emptyList()
        return requiredFiles(family, voiceId, mode).filter { findFile(context, family, it.name) == null }
    }

    fun isReady(context: Context, family: String, voiceId: String, mode: String): Boolean =
        missingFiles(context, family, voiceId, mode).isEmpty()

    /**
     * Downloads the missing files for [family]/[voiceId] from Hugging Face. Writes to `*.part`
     * and renames atomically, so a partially downloaded bundle is never loaded by LiteRT.
     */
    fun download(
        context: Context,
        family: String,
        voiceId: String,
        mode: String,
        cancel: AtomicBoolean,
        progress: (fileName: String, downloaded: Long, total: Long) -> Unit,
    ) {
        synchronized(downloadLock) {
            val missing = missingFiles(context, family, voiceId, mode)
            if (missing.isEmpty()) {
                AppLog.i("LiteRT download: all files for ${familyLabel(family)} already present")
                return
            }
            val dir = downloadDir(context, family)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Cannot create directory ${dir.absolutePath}")
            }
            for (rf in missing) {
                if (cancel.get()) throw IOException("Téléchargement annulé")
                downloadOne(rf, File(dir, rf.name), cancel, progress)
            }
        }
    }

    private fun downloadOne(
        rf: RemoteFile,
        target: File,
        cancel: AtomicBoolean,
        progress: (String, Long, Long) -> Unit,
    ) {
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        AppLog.i("LiteRT download START: ${rf.url} (~${rf.approxMb} MB) -> ${target.absolutePath}")
        val start = System.currentTimeMillis()
        var url = URL(rf.url)
        var conn: HttpURLConnection? = null
        try {
            // Follow redirects manually (huggingface.co -> CDN), restricted to HTTPS.
            var redirects = 0
            while (true) {
                if (url.protocol != "https") throw IOException("Refusing non-HTTPS URL: $url")
                val c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "SherpaOnnxTts-LiteRT/2.1 (Android)")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrEmpty() || ++redirects > 8) throw IOException("Bad redirect ($code) for ${rf.name}")
                    url = URL(url, loc)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    c.disconnect()
                    throw IOException("HTTP $code while downloading ${rf.name}")
                }
                conn = c
                break
            }
            val c = conn ?: throw IOException("HTTP connection failed for ${rf.name}")
            val total = c.contentLengthLong
            var downloaded = 0L
            var lastReportPct = -1
            c.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancel.get()) throw IOException("Téléchargement annulé")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        if (pct != lastReportPct) {
                            lastReportPct = pct
                            progress(rf.name, downloaded, total)
                            if (pct % 10 == 0) AppLog.i("LiteRT download ${rf.name}: $pct% (${downloaded / (1024 * 1024)} MB)")
                        }
                    }
                    output.fd.sync()
                }
            }
            if (total > 0 && downloaded != total) {
                throw IOException("Incomplete download for ${rf.name}: $downloaded / $total bytes")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Cannot rename ${part.name} -> ${target.name}")
            val sec = (System.currentTimeMillis() - start) / 1000.0
            AppLog.i(String.format(Locale.ROOT, "LiteRT download DONE: %s (%.1f MB in %.1f s)", rf.name, downloaded / 1048576.0, sec))
        } catch (t: Throwable) {
            part.delete()
            AppLog.e("LiteRT download FAILED for ${rf.name}: ${t.message}", t)
            throw t
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Engine lifecycle
    // ---------------------------------------------------------------------------------------

    /** Frees every native LiteRT / MediaPipe handle. */
    fun release() {
        stop()
        synchronized(engineLock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        mpTts?.let { t -> runCatching { t.close() }.onFailure { AppLog.w("MediaPipe TTS close failed", it) } }
        mpTts = null
        mpKey = ""
        matcha?.let { m -> runCatching { m.close() }.onFailure { AppLog.w("Matcha close failed", it) } }
        matcha = null
        matchaKey = ""
        activeBackend = "LiteRT non initialisé"
    }

    /** Requests the in-flight synthesis to stop as soon as possible (lock-free). */
    fun stop() {
        stopFlag.set(true)
        currentStream?.let { s -> runCatching { s.cancel() } }
    }

    private fun effectiveMode(context: Context, mode: String): String {
        val prevCrash = AppLog.didPreviousNpuInitCrash(context)
        if (mode == "auto" && prevCrash != null && prevCrash.startsWith(CRASH_TAG_PREFIX)) {
            AppLog.w("ALERT: previous native crash while initializing LiteRT accelerator ($prevCrash). Using CPU (XNNPACK) for this session.")
            return "cpu"
        }
        return mode
    }

    /**
     * Loads (or reuses) the native engine for [family]/[voiceId]. Returns the output sample rate.
     * Throws [ModelNotDownloadedException] when the model files are not on the device yet.
     */
    fun prepare(context: Context, family: String, voiceId: String, requestedMode: String, speed: Float = 1.0f): Int {
        val app = context.applicationContext
        AppLog.init(app)
        val mode = effectiveMode(app, requestedMode)
        val missing = missingFiles(app, family, voiceId, mode)
        if (missing.isNotEmpty()) {
            val totalMb = missing.sumOf { it.approxMb }
            throw ModelNotDownloadedException(
                "${familyLabel(family)} : ${missing.joinToString { it.name }} non téléchargé(s) (~$totalMb Mo)"
            )
        }
        synchronized(engineLock) {
            return if (family == FAMILY_MATCHA) {
                prepareMatchaLocked(app, mode)
            } else {
                prepareMediaPipeLocked(app, family, voiceId, mode, speed)
            }
        }
    }

    private fun prepareMediaPipeLocked(app: Context, family: String, voiceId: String, mode: String, speed: Float): Int {
        val bundle = resolveBundle(app, family, voiceId, mode)
            ?: throw ModelNotDownloadedException("${familyLabel(family)} : bundle .litertlm introuvable")
        val rate = (Math.round(speed.coerceIn(0.25f, 3.0f) * 20f) / 20f).coerceIn(0.25f, 3.0f)
        // PocketTTS / Phonon are published for the CPU delegate; Kokoro's GPU bundle runs its vocoder on GPU.
        val wantGpu = family == FAMILY_KOKORO && mode != "cpu" && bundle.name == KOKORO_GPU
        val key = "$family|${bundle.absolutePath}|${bundle.length()}|$wantGpu|$rate|$voiceId"
        mpTts?.let { if (mpKey == key) return activeSampleRate }

        // Release the previous engine (MediaPipe or Matcha) before mapping a new bundle: ~300 MB each.
        releaseLocked()

        AppLog.i("--- LiteRT INITIALIZATION START (${familyLabel(family)}) ---")
        AppLog.i("Bundle: ${bundle.absolutePath} (${bundle.length() / (1024 * 1024)} MB) | voice=$voiceId | mode=$mode | speakingRate=$rate")

        fun create(delegate: Delegate): TextToSpeech {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(bundle.absolutePath)
                .setDelegate(delegate)
                .build()
            val options = TextToSpeechOptions(
                baseOptions = baseOptions,
                defaultVoiceId = voiceId,
                speakingRate = rate,
                cacheDir = File(app.cacheDir, "litert-tts").apply { mkdirs() }.absolutePath,
            )
            return TextToSpeech.create(options)
        }

        val t0 = System.currentTimeMillis()
        var delegateLabel: String
        val created: TextToSpeech = if (wantGpu) {
            try {
                AppLog.markNpuInitStart(app, "$CRASH_TAG_PREFIX$family:gpu")
                val t = create(Delegate.GPU)
                delegateLabel = "GPU ML Drift (OpenCL Adreno) + CPU XNNPACK"
                t
            } catch (e: Throwable) {
                AppLog.w("MediaPipe TextToSpeech GPU delegate failed (${e.message}); retrying on CPU XNNPACK", e)
                if (mode == "npu") {
                    AppLog.markNpuInitDone(app)
                    throw e
                }
                delegateLabel = "CPU XNNPACK (repli GPU)"
                create(Delegate.CPU)
            } finally {
                AppLog.markNpuInitDone(app)
            }
        } else {
            delegateLabel = "CPU XNNPACK"
            create(Delegate.CPU)
        }
        val elapsed = System.currentTimeMillis() - t0

        val voices = runCatching { created.listVoices() }.getOrElse {
            AppLog.w("listVoices() failed: ${it.message}", it)
            emptyList()
        }
        AppLog.i("Bundled voices (${voices.size}): " + voices.joinToString { "${it.voiceId}[${it.locale}, ${it.sampleRateHz} Hz]" })
        if (voices.isNotEmpty() && voices.none { it.voiceId == voiceId }) {
            AppLog.w("Voice '$voiceId' not listed in bundle ${bundle.name}; MediaPipe will use its default voice")
        }
        val sr = voices.firstOrNull { it.voiceId == voiceId }?.sampleRateHz
            ?: voices.firstOrNull()?.sampleRateHz
            ?: 24000
        mpTts = created
        mpKey = key
        activeSampleRate = if (sr > 0) sr else 24000
        activeBackend = "${familyLabel(family)} — $delegateLabel"
        AppLog.i("SUCCESS: $activeBackend ready in $elapsed ms (sampleRate=$activeSampleRate)")
        return activeSampleRate
    }

    private fun prepareMatchaLocked(app: Context, mode: String): Int {
        val key = "matcha|$mode"
        matcha?.let { if (matchaKey == key) return activeSampleRate }
        releaseLocked()

        AppLog.i("--- LiteRT INITIALIZATION START (Matcha-TTS CompiledModel) ---")
        runCatching {
            Environment.create().use { env ->
                AppLog.i("LiteRT available accelerators: ${env.getAvailableAccelerators().joinToString()}")
            }
        }.onFailure { AppLog.w("LiteRT Environment probe failed: ${it.message}") }

        TtsManager.ensurePhonemizer(app)
        val t0 = System.currentTimeMillis()
        val useGpu = mode != "cpu"
        val engine = try {
            if (useGpu) AppLog.markNpuInitStart(app, "${CRASH_TAG_PREFIX}matcha:gpu")
            MatchaEngine.load(
                textenc = findFile(app, FAMILY_MATCHA, MATCHA_TEXTENC)!!,
                decoder = findFile(app, FAMILY_MATCHA, MATCHA_DECODER)!!,
                vocoder = findFile(app, FAMILY_MATCHA, MATCHA_VOCODER)!!,
                emb = findFile(app, FAMILY_MATCHA, MATCHA_EMB)!!,
                config = findFile(app, FAMILY_MATCHA, MATCHA_CONFIG)!!,
                useGpu = useGpu,
            )
        } finally {
            if (useGpu) AppLog.markNpuInitDone(app)
        }
        matcha = engine
        matchaKey = key
        activeSampleRate = engine.sampleRate
        activeBackend = if (useGpu) {
            "Matcha-TTS LiteRT — TextEnc/Vocoder GPU ML Drift + Décodeur CFM CPU XNNPACK"
        } else {
            "Matcha-TTS LiteRT — CPU XNNPACK"
        }
        AppLog.i("SUCCESS: $activeBackend ready in ${System.currentTimeMillis() - t0} ms (sampleRate=${engine.sampleRate})")
        return engine.sampleRate
    }

    // ---------------------------------------------------------------------------------------
    // Synthesis
    // ---------------------------------------------------------------------------------------

    /**
     * Synthesizes [text] with the currently prepared engine. [callback] receives each audio chunk
     * (float PCM in [-1, 1]) and returns 0 to stop. Returns the full waveform.
     */
    fun synthesize(
        context: Context,
        family: String,
        voiceId: String,
        mode: String,
        text: String,
        speed: Float,
        noiseScale: Float,
        silenceScale: Float,
        callback: (FloatArray) -> Int,
    ): GeneratedAudio {
        val sr = prepare(context, family, voiceId, mode, speed)
        stopFlag.set(false)
        val collected = FloatArrayBuilder()
        val t0 = System.currentTimeMillis()
        synchronized(engineLock) {
            if (family == FAMILY_MATCHA) {
                val engine = matcha ?: throw IllegalStateException("Matcha engine not initialized")
                engine.synthesize(
                    text = text,
                    speed = speed,
                    temperature = noiseScale,
                    silenceScale = silenceScale,
                    stop = stopFlag,
                ) { chunk ->
                    collected.add(chunk)
                    if (callback(chunk) == 0) stopFlag.set(true)
                    !stopFlag.get()
                }
            } else {
                val tts = mpTts ?: throw IllegalStateException("MediaPipe TextToSpeech not initialized")
                val stream = tts.openStream(text, SynthesisOptions(voiceId = voiceId), true)
                currentStream = stream
                try {
                    var chunks = 0
                    while (!stopFlag.get()) {
                        val chunk = stream.readNext() ?: break
                        val samples = chunk.samples
                        if (chunks == 0) {
                            AppLog.i("LiteRT first audio chunk after ${System.currentTimeMillis() - t0} ms (TTFA)")
                        }
                        chunks++
                        if (samples.isNotEmpty()) {
                            collected.add(samples)
                            if (callback(samples) == 0) {
                                stopFlag.set(true)
                                runCatching { stream.cancel() }
                                break
                            }
                        }
                        if (chunk.isFinal) break
                    }
                    AppLog.i("LiteRT stream finished: $chunks chunk(s)${if (stopFlag.get()) " (stopped)" else ""}")
                } finally {
                    currentStream = null
                    runCatching { stream.close() }
                }
            }
        }
        return GeneratedAudio(collected.toArray(), sr)
    }

    /** Writes a 16-bit PCM mono WAV without depending on the sherpa-onnx JNI library. */
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        return try {
            val dataBytes = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
                putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(header.array())
                val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) {
                    val c = if (s.isFinite()) s.coerceIn(-1f, 1f) else 0f
                    pcm.putShort((c * 32767f).toInt().toShort())
                }
                raf.write(pcm.array())
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Throwable) {
            AppLog.e("Failed to write WAV ${file.absolutePath}", e)
            false
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(24000 * 8)
        private var size = 0
        fun add(chunk: FloatArray) {
            if (size + chunk.size > data.size) {
                var cap = data.size
                while (cap < size + chunk.size) cap *= 2
                data = data.copyOf(cap)
            }
            System.arraycopy(chunk, 0, data, size, chunk.size)
            size += chunk.size
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    // ---------------------------------------------------------------------------------------
    // Matcha-TTS (LiteRT CompiledModel): text encoder -> length regulator -> CFM Euler ODE -> HiFi-GAN
    // ---------------------------------------------------------------------------------------
    private class MatchaEngine(
        private val textenc: CompiledModel,
        private val decoder: CompiledModel,
        private val vocoder: CompiledModel,
        private val emb: FloatArray,
        private val symbolIds: Map<Int, Int>,
        private val spaceId: Int,
        private val nChannels: Int,
        private val nFeats: Int,
        private val maxText: Int,
        private val maxMel: Int,
        private val melMean: Float,
        private val melStd: Float,
        private val hop: Int,
        private val lengthScale: Float,
        private val nSteps: Int,
        private val timeHalf: Int,
        val sampleRate: Int,
    ) : AutoCloseable {
        private val teIn: List<TensorBuffer> = textenc.createInputBuffers()
        private val teOut: List<TensorBuffer> = textenc.createOutputBuffers()
        private val decIn: List<TensorBuffer> = decoder.createInputBuffers()
        private val decOut: List<TensorBuffer> = decoder.createOutputBuffers()
        private val vocIn: List<TensorBuffer> = vocoder.createInputBuffers()
        private val vocOut: List<TensorBuffer> = vocoder.createOutputBuffers()
        private val random = Random()

        // Matcha-TTS limits: blanks are interspersed, and 512 mel frames ≈ 5.9 s per chunk.
        private val maxPhonemesPerChunk = min((maxText - 1) / 2, 72)

        init {
            require(teIn.size == 2 && teOut.size == 2) { "Unexpected text encoder signature: ${teIn.size} in / ${teOut.size} out" }
            require(decIn.size == 4 && decOut.size == 1) { "Unexpected decoder signature: ${decIn.size} in / ${decOut.size} out" }
            require(vocIn.size == 1 && vocOut.size == 1) { "Unexpected vocoder signature: ${vocIn.size} in / ${vocOut.size} out" }
        }

        fun synthesize(
            text: String,
            speed: Float,
            temperature: Float,
            silenceScale: Float,
            stop: AtomicBoolean,
            onChunk: (FloatArray) -> Boolean,
        ) {
            val chunks = phonemize(text)
            AppLog.i("Matcha: ${chunks.size} chunk(s), ${chunks.sumOf { it.size }} phoneme id(s)")
            val pause = FloatArray((silenceScale.coerceIn(0f, 1f) * 0.5f * sampleRate).toInt())
            for ((i, ids) in chunks.withIndex()) {
                if (stop.get()) return
                val t0 = System.currentTimeMillis()
                val wav = synthesizeChunk(ids, speed, temperature, stop) ?: return
                AppLog.i(String.format(Locale.ROOT, "Matcha chunk %d/%d: %d phonemes -> %.2f s audio in %d ms",
                    i + 1, chunks.size, ids.size, wav.size.toFloat() / sampleRate, System.currentTimeMillis() - t0))
                if (wav.isNotEmpty() && !onChunk(wav)) return
                if (i < chunks.size - 1 && pause.isNotEmpty() && !onChunk(pause)) return
            }
        }

        private fun phonemize(text: String): List<IntArray> {
            val result = piperPhonemizeText(text, "en-us")
                ?: throw RuntimeException("Failed to phonemize text for Matcha (en-us)")
            val out = mutableListOf<IntArray>()
            try {
                for (s in 0 until result.numSentences) {
                    val raw = result.getPhonemes(s)
                    val ids = ArrayList<Int>(raw.size)
                    for (cp in raw) {
                        symbolIds[cp]?.let { ids.add(it) }
                    }
                    var start = 0
                    while (start < ids.size) {
                        var end = min(start + maxPhonemesPerChunk, ids.size)
                        if (end < ids.size) {
                            // Prefer cutting at a word boundary.
                            var lastSpace: Int? = null
                            val minBound = start + maxPhonemesPerChunk / 2
                            var search = end - 1
                            while (search >= minBound) {
                                if (ids[search] == spaceId) {
                                    lastSpace = search
                                    break
                                }
                                search--
                            }
                            if (lastSpace != null) end = lastSpace + 1
                        }
                        val piece = ids.subList(start, end)
                        var hasNonSpace = false
                        for (sym in piece) {
                            if (sym != spaceId) {
                                hasNonSpace = true
                                break
                            }
                        }
                        if (hasNonSpace) out.add(piece.toIntArray())
                        start = end
                    }
                }
            } finally {
                result.close()
            }
            return out
        }

        private fun synthesizeChunk(ids: IntArray, speed: Float, temperature: Float, stop: AtomicBoolean): FloatArray? {
            val n = ids.size
            val tLen = 2 * n + 1
            // 1. Host embedding lookup with interspersed blanks (id 0) -> emb[1, maxText, nChannels]
            val embIn = FloatArray(maxText * nChannels)
            val tmask = FloatArray(maxText)
            for (t in 0 until maxText) {
                val id = if (t % 2 == 1 && t / 2 < n) ids[t / 2] else 0
                System.arraycopy(emb, id * nChannels, embIn, t * nChannels, nChannels)
                if (t < tLen) tmask[t] = 1f
            }
            teIn[0].writeFloat(embIn)
            teIn[1].writeFloat(tmask)
            textenc.run(teIn, teOut)
            val o0 = teOut[0].readFloat()
            val o1 = teOut[1].readFloat()
            val mu = if (o0.size >= o1.size) o0 else o1 // [1, nFeats, maxText]
            val logw = if (o0.size >= o1.size) o1 else o0 // [1, 1, maxText]
            if (mu.size < nFeats * maxText || logw.size < maxText) {
                throw IllegalStateException("Unexpected text encoder outputs: ${o0.size}, ${o1.size}")
            }

            // 2. Durations + length regulator -> mu_y[1, nFeats, maxMel]
            val ls = lengthScale / speed.coerceIn(0.25f, 3.0f)
            val cum = FloatArray(maxText)
            var acc = 0f
            for (t in 0 until maxText) {
                val w = if (tmask[t] > 0f) ceil(exp(logw[t].coerceIn(-10f, 10f))) * ls else 0f
                acc += w
                cum[t] = acc
            }
            val yLen = acc.coerceIn(1f, maxMel.toFloat()).toInt()
            if (acc > maxMel) AppLog.w("Matcha: chunk truncated to $maxMel mel frames (needed ${acc.toInt()})")
            val muY = FloatArray(nFeats * maxMel)
            var idx = 0
            for (f in 0 until yLen) {
                while (idx < maxText - 1 && cum[idx] <= f) idx++
                for (c in 0 until nFeats) muY[c * maxMel + f] = mu[c * maxText + idx]
            }
            val ymask = FloatArray(maxMel) { if (it < yLen) 1f else 0f }

            // 3. CFM Euler ODE (nSteps), x0 ~ N(0, temperature^2)
            val temp = temperature.coerceIn(0f, 1.5f)
            val x = FloatArray(nFeats * maxMel)
            for (c in 0 until nFeats) for (f in 0 until yLen) x[c * maxMel + f] = (random.nextGaussian() * temp).toFloat()
            decIn[1].writeFloat(muY)
            decIn[3].writeFloat(ymask)
            val dt = 1f / nSteps
            for (k in 0 until nSteps) {
                if (stop.get()) return null
                decIn[0].writeFloat(x)
                decIn[2].writeFloat(timeEmbedding(k.toFloat() / nSteps))
                decoder.run(decIn, decOut)
                val v = decOut[0].readFloat()
                for (i in x.indices) x[i] += v[i] * dt
            }

            // 4. Denormalize mel and run HiFi-GAN vocoder
            val mel = FloatArray(nFeats * maxMel)
            for (c in 0 until nFeats) for (f in 0 until yLen) {
                mel[c * maxMel + f] = x[c * maxMel + f] * melStd + melMean
            }
            if (stop.get()) return null
            vocIn[0].writeFloat(mel)
            vocoder.run(vocIn, vocOut)
            val wav = vocOut[0].readFloat()
            val len = min(yLen * hop, wav.size)
            return FloatArray(len) { i -> val s = wav[i]; if (s.isFinite()) s.coerceIn(-1f, 1f) else 0f }
        }

        private fun timeEmbedding(t: Float): FloatArray {
            val out = FloatArray(2 * timeHalf)
            val scale = -ln(10000.0) / (timeHalf - 1)
            for (i in 0 until timeHalf) {
                val e = 1000.0 * t * exp(i * scale)
                out[i] = sin(e).toFloat()
                out[timeHalf + i] = cos(e).toFloat()
            }
            return out
        }

        override fun close() {
            (teIn + teOut + decIn + decOut + vocIn + vocOut).forEach { runCatching { it.close() } }
            runCatching { textenc.close() }
            runCatching { decoder.close() }
            runCatching { vocoder.close() }
        }

        companion object {
            fun load(textenc: File, decoder: File, vocoder: File, emb: File, config: File, useGpu: Boolean): MatchaEngine {
                val cfg = JSONObject(config.readText())
                val symbols = cfg.getJSONArray("symbols")
                val symbolIds = HashMap<Int, Int>(symbols.length() * 2)
                var spaceId = -1
                for (i in 0 until symbols.length()) {
                    val s = symbols.getString(i)
                    if (s.codePointCount(0, s.length) == 1) {
                        val cp = s.codePointAt(0)
                        if (!symbolIds.containsKey(cp)) symbolIds[cp] = i
                        if (s == " ") spaceId = i
                    }
                }
                val nVocab = cfg.optInt("n_vocab", symbols.length())
                val nChannels = cfg.optInt("n_channels", 192)
                val embBytes = emb.readBytes()
                val expected = nVocab * nChannels * 4
                require(embBytes.size >= expected) { "emb.bin too small: ${embBytes.size} < $expected bytes" }
                val embArr = FloatArray(nVocab * nChannels)
                ByteBuffer.wrap(embBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(embArr)

                val gpu = if (useGpu) Accelerator.GPU else Accelerator.CPU
                fun compile(f: File, acc: Accelerator, label: String): CompiledModel {
                    val t0 = System.currentTimeMillis()
                    val m = CompiledModel.create(f.absolutePath, CompiledModel.Options(acc))
                    AppLog.i("LiteRT CompiledModel '$label' (${f.length() / (1024 * 1024)} MB) compiled for $acc in ${System.currentTimeMillis() - t0} ms")
                    return m
                }
                val models = mutableListOf<CompiledModel>()
                try {
                    val te = compile(textenc, gpu, "textenc").also { models.add(it) }
                    // The CFM decoder is mis-fused by GPU delegates: always CPU XNNPACK.
                    val de = compile(decoder, Accelerator.CPU, "decoder").also { models.add(it) }
                    val vo = compile(vocoder, gpu, "vocoder").also { models.add(it) }
                    return MatchaEngine(
                        textenc = te,
                        decoder = de,
                        vocoder = vo,
                        emb = embArr,
                        symbolIds = symbolIds,
                        spaceId = spaceId,
                        nChannels = nChannels,
                        nFeats = cfg.optInt("n_feats", 80),
                        maxText = cfg.optInt("MAX_TEXT", 256),
                        maxMel = cfg.optInt("MAX_MEL", 512),
                        melMean = cfg.optDouble("mel_mean", -5.536622).toFloat(),
                        melStd = cfg.optDouble("mel_std", 2.116101).toFloat(),
                        hop = cfg.optInt("hop", 256),
                        lengthScale = cfg.optDouble("length_scale", 0.95).toFloat(),
                        nSteps = cfg.optInt("n_timesteps_default", 10).coerceIn(1, 64),
                        timeHalf = cfg.optInt("in_channels", 160) / 2,
                        sampleRate = cfg.optInt("sample_rate", 22050),
                    )
                } catch (t: Throwable) {
                    models.forEach { runCatching { it.close() } }
                    throw t
                }
            }
        }
    }
}
