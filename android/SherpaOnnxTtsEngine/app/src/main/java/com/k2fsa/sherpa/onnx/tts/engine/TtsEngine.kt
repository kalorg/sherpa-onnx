package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import android.util.Log
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.util.Locale

const val TAG = "sherpa-onnx-tts-engine"
const val MIN_TTS_SPEED = 0.25f
const val MAX_TTS_SPEED = 3.0f

/**
 * Piper TTS Engine for Samsung Galaxy S25 Ultra (Qualcomm Hexagon V79 NPU + CPU ONNX fallback).
 */
object TtsEngine {
    private const val FR_MODEL_DIR = "pipertts-fr-qnn-s25-ultra"
    private const val EN_MODEL_DIR = "pipertts-en-qnn-s25-ultra"
    private const val QNN_BINS = "encoder.bin,sdp.bin,flow.bin,decoder.bin"

    private val lock = Any()

    @Volatile
    var tts: OfflineTts? = null

    var speed: Float = 1.0F
    var speakerId: Int = 0
    var modelDir: String = EN_MODEL_DIR
    var modelName: String? = null
    var qnnBackendLib: String = "libQnnHtp.so"
    var qnnSystemLib: String = "libQnnSystem.so"
    var qnnContextBinary: String = QNN_BINS
    var usePhonemize: Boolean = true
    var lang: String = "eng"
    var lang2: String? = null
    var activeBackend: String = "Qualcomm Hexagon NPU (QNN HTP V79)"

    @Volatile
    private var phonemizerReady: Boolean = false

    fun getActiveLocale(context: Context? = null): Locale {
        val modelLang = tts?.lang()?.lowercase(Locale.ROOT).orEmpty()
        if (modelLang.startsWith("fr") || lang == "fra" || lang == "fr" || modelDir.contains("-fr-")) {
            return Locale.FRANCE
        }
        if (context != null && hasVoiceInAssetsOrFiles(context, FR_MODEL_DIR)) {
            return Locale.FRANCE
        }
        return Locale.US
    }

    fun getVoiceId(context: Context? = null): String {
        return "piper-qnn-${getActiveLocale(context).toLanguageTag()}"
    }

    fun createTts(context: Context) {
        if (tts != null) return
        synchronized(lock) {
            if (tts == null) {
                initTts(context.applicationContext)
            }
        }
    }

    private fun initTts(context: Context) {
        if (hasVoiceInAssetsOrFiles(context, FR_MODEL_DIR)) {
            modelDir = FR_MODEL_DIR
            lang = "fra"
        } else {
            modelDir = EN_MODEL_DIR
            lang = "eng"
        }

        if (usePhonemize && !phonemizerReady) {
            piperPhonemizeInitialize(context)
            phonemizerReady = true
        }

        val cfgAsset = "$modelDir/config.json"
        if (assetExists(context, cfgAsset)) {
            copyAssetToInternalStorage(context, cfgAsset)
        }
        val tokensPath = copyAssetToInternalStorage(context, "$modelDir/tokens.txt")

        speed = PreferenceHelper(context).getSpeed()
        speakerId = PreferenceHelper(context).getSid()

        var qnnError: Throwable? = null
        if (hasQnnBinaries(context, modelDir)) {
            try {
                Log.i(TAG, "nativelibdir: ${context.applicationInfo.nativeLibraryDir}")
                OfflineTts.prependAdspLibraryPath(context.applicationInfo.nativeLibraryDir)

                val config = getOfflineTtsConfig(
                    modelDir = modelDir,
                    qnnBackendLib = qnnBackendLib,
                    qnnSystemLib = qnnSystemLib,
                    qnnContextBinary = qnnContextBinary,
                )
                config.model.vits.tokens = tokensPath
                config.model.vits.qnnConfig.contextBinary =
                    copyAssetListToInternalStorage(context, config.model.vits.qnnConfig.contextBinary)

                tts = OfflineTts(assetManager = null, config = config)
                activeBackend = "Qualcomm Hexagon NPU (QNN HTP V79)"
                if (tts?.lang()?.startsWith("fr") == true) {
                    lang = "fra"
                }
                return
            } catch (e: Throwable) {
                Log.e(TAG, "QNN NPU init failed, checking for CPU ONNX fallback", e)
                qnnError = e
            }
        }

        val onnxFallback = modelName ?: findOnnxFallback(context, modelDir)
        if (onnxFallback != null) {
            val cpuConfig = getOfflineTtsConfig(
                modelDir = modelDir,
                modelName = onnxFallback,
                provider = "cpu",
            )
            cpuConfig.model.vits.tokens = tokensPath
            cpuConfig.model.vits.model = copyAssetToInternalStorage(context, cpuConfig.model.vits.model)
            tts = OfflineTts(assetManager = null, config = cpuConfig)
            activeBackend = "CPU Fallback (ONNX Runtime)"
            return
        }

        throw qnnError ?: IllegalStateException(
            "No QNN context binaries or CPU fallback (.onnx) found in $modelDir"
        )
    }

    private fun hasVoiceInAssetsOrFiles(context: Context, dir: String): Boolean {
        val inAssets = runCatching { context.assets.list(dir)?.isNotEmpty() == true }.getOrDefault(false)
        if (inAssets) return true
        return File(context.filesDir, "$dir/tokens.txt").exists()
    }

    private fun hasQnnBinaries(context: Context, dir: String): Boolean {
        return assetExists(context, "$dir/encoder.bin") ||
            File(context.filesDir, "$dir/encoder.bin").exists()
    }

    private fun findOnnxFallback(context: Context, dir: String): String? {
        val assetFiles = runCatching { context.assets.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
        val assetOnnx = assetFiles.firstOrNull { it.endsWith(".onnx") }
        if (assetOnnx != null) return "$dir/$assetOnnx"

        val dirFile = File(context.filesDir, dir)
        val fileOnnx = dirFile.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }
        return fileOnnx?.let { "$dir/${it.name}" }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/')
        val files = context.assets.list(dir) ?: return false
        return files.contains(fileName)
    }

    private fun copyAssetToInternalStorage(context: Context, path: String): String {
        val outFile = File(context.filesDir, path)
        if (!assetExists(context, path)) {
            outFile.parentFile?.mkdirs()
            return outFile.absolutePath
        }
        if (outFile.exists()) {
            val assetSize = context.assets.open(path).use { it.available() }
            if (outFile.length() == assetSize.toLong()) {
                return outFile.absolutePath
            }
        }
        outFile.parentFile?.mkdirs()
        context.assets.open(path).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }

    private fun copyAssetListToInternalStorage(context: Context, paths: String): String {
        if (paths.isBlank()) return paths
        return paths.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",") { copyAssetToInternalStorage(context, it) }
    }

    fun phonemize(text: String): Array<IntArray> {
        var voice = tts?.lang() ?: ""
        if (voice.isEmpty()) {
            voice = if (lang == "fra") "fr" else "en-us"
        }
        val result = piperPhonemizeText(text, voice)
            ?: throw RuntimeException("Failed to phonemize the text")
        try {
            return Array(result.numSentences) { i -> result.getPhonemes(i) }
        } finally {
            result.close()
        }
    }
}
