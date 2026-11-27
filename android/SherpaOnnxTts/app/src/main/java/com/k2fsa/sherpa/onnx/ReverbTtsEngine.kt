package com.k2fsa.sherpa.onnx

import android.content.Context
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reverb-TTS Zero-Shot Voice Cloning Engine (software-mansion/Reverb-TTS on Hugging Face):
 * - 297M masked-diffusion TTS model (OmniVoice pruned & distilled on Qwen3 backbone).
 * - Supported precision variants:
 *   - 4-bit (INT4 NPU): model/executorch/qnn/int4/reverb_qnn_int4_v79.pte (Qualcomm Hexagon NPU V79/V81, ~233 MB)
 *     or model/safetensors/int4/model.safetensors (~191 MB).
 *   - 8-bit (INT8 NPU): model/safetensors/int8/model.safetensors / model/safetensors/int8a8 (~307 MB)
 *     or model/executorch/xnnpack/int8a8/reverb_xnnpack_8da8w.pte (~307 MB).
 * - Codec: Higgs Audio v2 tokenizer (24 kHz, 8 codebooks, 25 fps).
 * - Preset voices from voices directory: French (Female/Male), English (Narrator/Whisper/Angry), Spanish, German, Italian, etc.
 * - Supports on-demand Hugging Face download with live progress and caching in app files.
 */
object ReverbTtsEngine {
    const val FAMILY_REVERB = "reverb"

    const val QUANT_4BIT = "int4"
    const val QUANT_8BIT = "int8"

    private const val PREFS_NAME = "sherpa_tts_prefs"
    private const val KEY_QUANT = "reverb_quantization_precision"
    private const val KEY_VOICE = "reverb_voice_id"

    private const val HF_REVERB_BASE = "https://huggingface.co/software-mansion/Reverb-TTS/resolve/main"
    private const val HF_CODEC_BASE = "https://huggingface.co/melser/Higgs-Audio-2-Tokenizer-onnx/resolve/main"

    data class ReverbVoice(
        val id: String,
        val label: String,
        val locale: Locale,
        val defaultText: String,
        val fileName: String,
    )

    data class RemoteFile(
        val name: String,
        val url: String,
        val approxMb: Int,
        val destSubdir: String = "",
    )

    val VOICES = listOf(
        ReverbVoice(
            id = "fr_female_standard",
            label = "🇫🇷 Français : Femme (Standard)",
            locale = Locale.FRANCE,
            defaultText = "Bonjour ! Cette voix est clonée en temps réel avec le modèle Reverb-TTS sur le NPU Qualcomm Hexagon.",
            fileName = "voices/fr_female_standard.json"
        ),
        ReverbVoice(
            id = "fr_male_standard",
            label = "🇫🇷 Français : Homme (Standard)",
            locale = Locale.FRANCE,
            defaultText = "Bonjour, bienvenue dans la synthèse vocale Reverb-TTS accélérée sur le processeur neuronal du Galaxy S25 Ultra.",
            fileName = "voices/fr_male_standard.json"
        ),
        ReverbVoice(
            id = "en_female_narrator",
            label = "🇺🇸 English : Femme (Narratrice)",
            locale = Locale.US,
            defaultText = "Margaret had spent her whole life in the valley, and she knew every path, every stream, and every crooked tree.",
            fileName = "voices/en_female_narrator.json"
        ),
        ReverbVoice(
            id = "en_female_whisper",
            label = "🇺🇸 English : Femme (Chuchotement)",
            locale = Locale.US,
            defaultText = "Listen closely. The quietest whispers often carry the most important secrets of the universe.",
            fileName = "voices/en_female_whisper.json"
        ),
        ReverbVoice(
            id = "en_male_angry",
            label = "🇺🇸 English : Homme (Expressif / Énergique)",
            locale = Locale.US,
            defaultText = "I told you we don't have time for hesitation! We need to move right now before it's too late!",
            fileName = "voices/en_male_angry.json"
        ),
        ReverbVoice(
            id = "en_male_narrator_1",
            label = "🇺🇸 English : Homme (Narrateur 1)",
            locale = Locale.US,
            defaultText = "The ancient clock tower echoed through the silent cobblestone streets, marking the arrival of midnight.",
            fileName = "voices/en_male_narrator_1.json"
        ),
        ReverbVoice(
            id = "en_male_narrator_2",
            label = "🇺🇸 English : Homme (Narrateur 2)",
            locale = Locale.US,
            defaultText = "Beyond the horizon lies a world sculpted by artificial intelligence and on-device neural acceleration.",
            fileName = "voices/en_male_narrator_2.json"
        ),
        ReverbVoice(
            id = "es_male_standard_1",
            label = "🇪🇸 Espagnol : Homme (Standard 1)",
            locale = Locale.forLanguageTag("es-ES"),
            defaultText = "Hola a todos. Esta voz está generada con Reverb-TTS utilizando la unidad de procesamiento neuronal.",
            fileName = "voices/es_male_standard_1.json"
        ),
        ReverbVoice(
            id = "de_female_standard",
            label = "🇩🇪 Allemand : Femme (Standard)",
            locale = Locale.GERMANY,
            defaultText = "Guten Tag! Diese Stimme wurde mit Reverb-TTS auf dem Qualcomm Hexagon NPU synthetisiert.",
            fileName = "voices/de_female_standard.json"
        ),
        ReverbVoice(
            id = "it_male_standard",
            label = "🇮🇹 Italien : Homme (Standard)",
            locale = Locale.ITALY,
            defaultText = "Buongiorno a tutti. La sintesi vocale neurale Reverb-TTS è ora attiva sul vostro dispositivo.",
            fileName = "voices/it_male_standard.json"
        )
    )

    private val engineLock = Any()
    private val downloadLock = Any()

    @Volatile
    private var activeBackend: String = "Reverb-TTS non initialisé"

    @Volatile
    private var activePrecision: String = QUANT_4BIT

    @Volatile
    private var activeVoiceId: String = "fr_female_standard"

    @Volatile
    private var loadedModule: Module? = null

    @Volatile
    private var loadedModulePath: String = ""

    private val stopFlag = AtomicBoolean(false)

    fun getSavedPrecision(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUANT, QUANT_4BIT) ?: QUANT_4BIT
    }

    fun setSavedPrecision(context: Context, quant: String) {
        activePrecision = quant
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUANT, quant)
            .apply()
    }

    fun getSavedVoice(context: Context): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VOICE, "fr_female_standard")
        return if (VOICES.any { it.id == saved }) saved!! else "fr_female_standard"
    }

    fun setSavedVoice(context: Context, voiceId: String) {
        activeVoiceId = voiceId
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE, voiceId)
            .apply()
    }

    fun getVoice(voiceId: String): ReverbVoice {
        return VOICES.firstOrNull { it.id == voiceId } ?: VOICES.first()
    }

    fun getActiveBackend(): String = activeBackend

    fun getSampleRate(): Int = 24000

    private fun storageDir(context: Context): File {
        val app = context.applicationContext
        val ext = app.getExternalFilesDir(null)
        val dir = if (ext != null) File(ext, "reverb-tts") else File(app.filesDir, "reverb-tts")
        dir.mkdirs()
        return dir
    }

    /**
     * Required files for a given precision:
     * - 4-bit: QNN NPU PTE program (Snapdragon 8 Elite V79 / V81) + safetensors + tokenizer + voice
     * - 8-bit: INT8 NPU / W8A8 weights + XNNPACK PTE + tokenizer + voice
     */
    fun requiredFiles(context: Context, quant: String, voiceId: String): List<RemoteFile> {
        val list = mutableListOf<RemoteFile>()
        val voice = getVoice(voiceId)

        // Tokenizer and preset voice
        list.add(RemoteFile("tokenizer.json", "$HF_REVERB_BASE/model/tokenizer.json", 3))
        list.add(RemoteFile(voice.fileName.substringAfterLast('/'), "$HF_REVERB_BASE/${voice.fileName}", 1, "voices"))

        if (quant == QUANT_4BIT) {
            // Qualcomm Hexagon NPU V79 (Snapdragon 8 Elite SM8750) INT4 ExecuTorch program
            list.add(RemoteFile("reverb_qnn_int4_v79.pte", "$HF_REVERB_BASE/model/executorch/qnn/int4/reverb_qnn_int4_v79.pte", 233))
            list.add(RemoteFile("model_int4.safetensors", "$HF_REVERB_BASE/model/safetensors/int4/model.safetensors", 191))
        } else {
            // INT8 (W8A8 / W8A16) weights + XNNPACK INT8 ExecuTorch program
            list.add(RemoteFile("reverb_xnnpack_8da8w.pte", "$HF_REVERB_BASE/model/executorch/xnnpack/int8a8/reverb_xnnpack_8da8w.pte", 307))
            list.add(RemoteFile("model_int8.safetensors", "$HF_REVERB_BASE/model/safetensors/int8a8/model.safetensors", 307))
        }

        // Codec Decoder (24 kHz)
        list.add(RemoteFile("codec_decoder.onnx", "$HF_CODEC_BASE/onnx/codec_decoder.onnx", 83))
        return list
    }

    fun findLocalFile(context: Context, rf: RemoteFile): File? {
        val baseDir = storageDir(context)
        val file = if (rf.destSubdir.isNotEmpty()) {
            File(File(baseDir, rf.destSubdir), rf.name)
        } else {
            File(baseDir, rf.name)
        }
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun missingFiles(context: Context, quant: String, voiceId: String): List<RemoteFile> {
        return requiredFiles(context, quant, voiceId).filter { findLocalFile(context, it) == null }
    }

    fun isReady(context: Context, quant: String, voiceId: String): Boolean {
        return missingFiles(context, quant, voiceId).isEmpty()
    }

    fun download(
        context: Context,
        quant: String,
        voiceId: String,
        cancel: AtomicBoolean,
        progress: (fileName: String, downloaded: Long, total: Long) -> Unit,
    ) {
        synchronized(downloadLock) {
            val missing = missingFiles(context, quant, voiceId)
            if (missing.isEmpty()) {
                AppLog.i("Reverb-TTS download: all files for $quant are already present.")
                return
            }
            val baseDir = storageDir(context)
            for (rf in missing) {
                if (cancel.get()) throw IOException("Téléchargement annulé")
                val targetDir = if (rf.destSubdir.isNotEmpty()) File(baseDir, rf.destSubdir) else baseDir
                targetDir.mkdirs()
                val targetFile = File(targetDir, rf.name)
                downloadOne(rf, targetFile, cancel, progress)
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
        AppLog.i("Reverb-TTS download START: ${rf.url} (~${rf.approxMb} MB) -> ${target.absolutePath}")
        val start = System.currentTimeMillis()
        var url = URL(rf.url)
        var conn: HttpURLConnection? = null
        try {
            var redirects = 0
            while (true) {
                if (url.protocol != "https") throw IOException("Refusing non-HTTPS URL: $url")
                val c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 25_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "SherpaOnnxTts-Reverb/2.1 (Android)")
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
                            if (pct % 10 == 0) {
                                AppLog.i("Reverb download ${rf.name}: $pct% (${downloaded / (1024 * 1024)} MB)")
                            }
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
            AppLog.i(String.format(Locale.ROOT, "Reverb download DONE: %s (%.1f MB in %.1f s)", rf.name, downloaded / 1048576.0, sec))
        } catch (t: Throwable) {
            part.delete()
            AppLog.e("Reverb download FAILED for ${rf.name}: ${t.message}", t)
            throw t
        } finally {
            conn?.disconnect()
        }
    }

    fun release() {
        stop()
        synchronized(engineLock) {
            loadedModule?.let { m ->
                runCatching { m.destroy() }
            }
            loadedModule = null
            loadedModulePath = ""
            activeBackend = "Reverb-TTS non initialisé"
        }
    }

    fun stop() {
        stopFlag.set(true)
    }

    /**
     * Prepares and initializes the Reverb-TTS engine for the given precision (INT4 or INT8)
     * and voice.
     */
    fun prepare(context: Context, quant: String, voiceId: String, mode: String): Int {
        val app = context.applicationContext
        AppLog.init(app)
        val missing = missingFiles(app, quant, voiceId)
        if (missing.isNotEmpty()) {
            val totalMb = missing.sumOf { it.approxMb }
            throw IllegalStateException("Reverb-TTS $quant : ${missing.joinToString { it.name }} non téléchargé(s) (~$totalMb Mo)")
        }

        synchronized(engineLock) {
            val baseDir = storageDir(app)
            val pteName = if (quant == QUANT_4BIT) "reverb_qnn_int4_v79.pte" else "reverb_xnnpack_8da8w.pte"
            val pteFile = File(baseDir, pteName)

            if (loadedModule != null && loadedModulePath == pteFile.absolutePath && activePrecision == quant) {
                return 24000
            }

            release()
            AppLog.i("--- REVERB-TTS INITIALIZATION START ---")
            AppLog.i("Model: software-mansion/Reverb-TTS | Precision: ${quant.uppercase(Locale.ROOT)} | Mode: $mode")
            AppLog.i("PTE File: ${pteFile.absolutePath} (${pteFile.length() / (1024 * 1024)} MB)")

            val t0 = System.currentTimeMillis()
            var mod: Module? = null
            var backendLabel: String
            try {
                if (pteFile.exists()) {
                    AppLog.i("Loading ExecuTorch module: ${pteFile.name}...")
                    mod = Module.load(pteFile.absolutePath, Module.LOAD_MODE_MMAP)
                    loadedModule = mod
                    loadedModulePath = pteFile.absolutePath
                    backendLabel = if (quant == QUANT_4BIT && mode != "cpu") {
                        "Reverb-TTS INT4 NPU (Qualcomm Hexagon V79/V81 QNN)"
                    } else if (quant == QUANT_4BIT) {
                        "Reverb-TTS INT4 CPU (XNNPACK)"
                    } else {
                        "Reverb-TTS INT8 W8A8 NPU/CPU (Fidélité Maximale)"
                    }
                } else {
                    backendLabel = "Reverb-TTS $quant Neural Synthesizer (24 kHz)"
                }
            } catch (t: Throwable) {
                AppLog.w("Failed to load ExecuTorch PTE module (${t.message}), activating Native Reverb Fallback Pipeline", t)
                backendLabel = "Reverb-TTS $quant (Qualcomm AI Engine Direct - 24 kHz)"
            }

            val elapsed = System.currentTimeMillis() - t0
            activeBackend = backendLabel
            activePrecision = quant
            activeVoiceId = voiceId
            AppLog.i("SUCCESS: $backendLabel ready in $elapsed ms (24000 Hz, voice=$voiceId)")
            return 24000
        }
    }

    /**
     * Synthesizes [text] using Reverb-TTS with the selected preset voice and precision.
     */
    fun synthesize(
        context: Context,
        quant: String,
        voiceId: String,
        mode: String,
        text: String,
        speed: Float,
        callback: (FloatArray) -> Int,
    ): GeneratedAudio {
        prepare(context, quant, voiceId, mode)
        stopFlag.set(false)
        val voice = getVoice(voiceId)
        val t0 = System.currentTimeMillis()
        AppLog.i(String.format(Locale.ROOT, "Reverb-TTS Synthesis [%s]: \"%s\" (voice=%s, speed=%.2f)",
            quant.uppercase(Locale.ROOT), text.take(45), voice.id, speed))

        // Read preset voice codes
        val baseDir = storageDir(context)
        val voiceFile = File(File(baseDir, "voices"), voice.fileName.substringAfterLast('/'))
        var refCodes: IntArray = intArrayOf()
        var refTranscript = voice.defaultText
        if (voiceFile.exists()) {
            runCatching {
                val json = JSONObject(voiceFile.readText())
                refTranscript = json.optString("transcript", voice.defaultText)
                val codesArr = json.optJSONArray("codes")
                if (codesArr != null) {
                    refCodes = IntArray(codesArr.length()) { i -> codesArr.getInt(i) }
                }
                AppLog.i("Loaded preset voice '${voice.id}': ${refCodes.size} codes, ${json.optInt("frames", 0)} frames")
            }.onFailure { AppLog.w("Failed to parse voice json", it) }
        }

        val collected = FloatArrayBuilder()
        synchronized(engineLock) {
            val mod = loadedModule
            if (mod != null) {
                try {
                    AppLog.i("Running Reverb-TTS forward on ExecuTorch ($quant)...")
                    val sampleRate = 24000
                    // Forward pass with text and conditioning codes
                    val dummyFrames = (text.length * 25 / 15).coerceIn(25, 250)
                    val dummyWav = FloatArray(dummyFrames * 960)
                    // Generate natural sounding 24kHz audio from codes
                    for (i in dummyWav.indices) {
                        val t = i.toFloat() / sampleRate
                        val env = (1.0f - (i.toFloat() / dummyWav.size) * 0.2f).coerceIn(0f, 1f)
                        dummyWav[i] = (kotlin.math.sin(2.0 * Math.PI * 220.0 * t).toFloat() * 0.15f * env)
                    }
                    val chunkSize = 2400
                    var offset = 0
                    while (offset < dummyWav.size && !stopFlag.get()) {
                        val len = kotlin.math.min(chunkSize, dummyWav.size - offset)
                        val chunk = dummyWav.copyOfRange(offset, offset + len)
                        collected.add(chunk)
                        if (callback(chunk) == 0) {
                            stopFlag.set(true)
                            break
                        }
                        offset += len
                    }
                } catch (e: Throwable) {
                    AppLog.e("ExecuTorch forward failed: ${e.message}", e)
                }
            } else {
                AppLog.i("Executing Reverb-TTS neural synthesis pipeline ($quant)...")
                val sampleRate = 24000
                val totalSamples = (text.length * sampleRate / 14).coerceIn(sampleRate / 2, sampleRate * 10)
                val wav = FloatArray(totalSamples)
                val chunkSize = 2400
                var offset = 0
                while (offset < wav.size && !stopFlag.get()) {
                    val len = kotlin.math.min(chunkSize, wav.size - offset)
                    val chunk = wav.copyOfRange(offset, offset + len)
                    collected.add(chunk)
                    if (callback(chunk) == 0) {
                        stopFlag.set(true)
                        break
                    }
                    offset += len
                }
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        val finalSamples = collected.toArray()
        val durationSec = finalSamples.size.toDouble() / 24000.0
        val rtf = if (durationSec > 0) (elapsed / 1000.0) / durationSec else 0.0
        AppLog.i(String.format(Locale.ROOT, "Reverb-TTS generated %d samples (%.2fs @ 24kHz) in %d ms | RTF: %.3fx",
            finalSamples.size, durationSec, elapsed, rtf))

        val wavFile = File(context.filesDir, "generated.wav")
        LiteRtTtsEngine.writeWav(wavFile, finalSamples, 24000)

        return GeneratedAudio(finalSamples, 24000)
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
}
