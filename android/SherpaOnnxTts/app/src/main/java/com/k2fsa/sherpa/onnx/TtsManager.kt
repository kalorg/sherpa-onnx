package com.k2fsa.sherpa.onnx

import android.content.Context
import android.util.Log
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import java.io.File
import java.util.Locale

/**
 * Thread-safe manager for Piper TTS on Samsung Galaxy S25 Ultra:
 * - Primary backend: Qualcomm Hexagon V79 NPU via QNN (encoder.bin, sdp.bin, flow.bin, decoder.bin)
 * - Automatic CPU fallback: Standard Piper ONNX model (.onnx) if present and NPU initialization fails
 * - Auto-detects a French voice directory ("pipertts-fr-qnn-s25-ultra") in assets or internal storage
 *   before falling back to the bundled English default ("pipertts-en-qnn-s25-ultra").
 */
object TtsManager {
    private const val FR_MODEL_DIR = "pipertts-fr-qnn-s25-ultra"
    private const val EN_MODEL_DIR = "pipertts-en-qnn-s25-ultra"
    private const val QNN_BINS = "encoder.bin,sdp.bin,flow.bin,decoder.bin"

    private val lock = Any()

    @Volatile
    private var ttsInstance: OfflineTts? = null

    @Volatile
    private var activeBackend: String = "NPU (QNN HTP V79)"

    @Volatile
    private var activeModelDir: String = EN_MODEL_DIR

    @Volatile
    private var phonemizerReady: Boolean = false

    fun getTts(): OfflineTts? = ttsInstance

    fun getActiveBackend(): String = activeBackend

    fun getActiveModelDir(context: Context): String {
        if (hasVoiceInAssetsOrFiles(context, FR_MODEL_DIR)) {
            return FR_MODEL_DIR
        }
        return EN_MODEL_DIR
    }

    fun getActiveLocale(context: Context): Locale {
        val t = ttsInstance
        val lang = t?.lang()?.lowercase(Locale.ROOT).orEmpty()
        if (lang.startsWith("fr") || getActiveModelDir(context).contains("-fr-")) {
            return Locale.FRANCE
        }
        return Locale.US
    }

    fun getVoiceId(context: Context): String {
        val locale = getActiveLocale(context)
        return "piper-qnn-${locale.toLanguageTag()}"
    }

    fun ensureInitialized(context: Context): OfflineTts {
        ttsInstance?.let { return it }
        synchronized(lock) {
            ttsInstance?.let { return it }

            val appContext = context.applicationContext
            if (!phonemizerReady) {
                piperPhonemizeInitialize(appContext)
                phonemizerReady = true
            }

            val modelDir = getActiveModelDir(appContext)
            activeModelDir = modelDir

            val cfgAsset = "$modelDir/config.json"
            if (assetExists(appContext, cfgAsset)) {
                copyAssetToInternalStorage(appContext, cfgAsset)
            }
            val tokensPath = copyAssetToInternalStorage(appContext, "$modelDir/tokens.txt")

            // 1. Try Qualcomm Hexagon V79 NPU (QNN) first
            var qnnError: Throwable? = null
            if (hasQnnBinaries(appContext, modelDir)) {
                try {
                    Log.i(TAG, "Initializing Qualcomm QNN HTP V79 NPU from $modelDir (nativeLibraryDir=${appContext.applicationInfo.nativeLibraryDir})")
                    OfflineTts.prependAdspLibraryPath(appContext.applicationInfo.nativeLibraryDir)

                    val qnnConfig = getOfflineTtsConfig(
                        modelDir = modelDir,
                        qnnBackendLib = "libQnnHtp.so",
                        qnnSystemLib = "libQnnSystem.so",
                        qnnContextBinary = QNN_BINS,
                    )
                    qnnConfig.model.vits.tokens = tokensPath
                    qnnConfig.model.vits.qnnConfig.contextBinary =
                        copyAssetListToInternalStorage(appContext, qnnConfig.model.vits.qnnConfig.contextBinary)

                    val created = OfflineTts(assetManager = null, config = qnnConfig)
                    activeBackend = "Qualcomm Hexagon NPU (QNN HTP V79)"
                    ttsInstance = created
                    return created
                } catch (e: Throwable) {
                    Log.e(TAG, "QNN NPU initialization failed, checking for CPU ONNX fallback", e)
                    qnnError = e
                }
            }

            // 2. Automatic CPU fallback if a standard Piper .onnx model exists in modelDir
            val onnxFallback = findOnnxFallback(appContext, modelDir)
            if (onnxFallback != null) {
                Log.w(TAG, "Falling back to CPU ONNX model: $onnxFallback")
                val cpuConfig = getOfflineTtsConfig(
                    modelDir = modelDir,
                    modelName = onnxFallback,
                    provider = "cpu",
                )
                cpuConfig.model.vits.tokens = tokensPath
                cpuConfig.model.vits.model = copyAssetToInternalStorage(appContext, cpuConfig.model.vits.model)
                val created = OfflineTts(assetManager = null, config = cpuConfig)
                activeBackend = "CPU Fallback (ONNX Runtime)"
                ttsInstance = created
                return created
            }

            throw qnnError ?: IllegalStateException(
                "No QNN context binaries ($QNN_BINS) or CPU fallback (.onnx) found in $modelDir"
            )
        }
    }

    fun phonemize(tts: OfflineTts, text: String): Array<IntArray> {
        var voice = tts.lang()
        if (voice.isEmpty()) {
            voice = "en-us"
        }
        Log.i(TAG, "Phonemizer voice: $voice")
        val result = piperPhonemizeText(text, voice)
            ?: throw RuntimeException("Failed to phonemize text for voice '$voice'")
        try {
            return Array(result.numSentences) { i -> result.getPhonemes(i) }
        } finally {
            result.close()
        }
    }

    private fun hasVoiceInAssetsOrFiles(context: Context, dir: String): Boolean {
        val inAssets = runCatching { context.assets.list(dir)?.isNotEmpty() == true }.getOrDefault(false)
        if (inAssets) return true
        val inFiles = File(context.filesDir, "$dir/tokens.txt").exists()
        return inFiles
    }

    private fun hasQnnBinaries(context: Context, modelDir: String): Boolean {
        return assetExists(context, "$modelDir/encoder.bin") ||
            File(context.filesDir, "$modelDir/encoder.bin").exists()
    }

    private fun findOnnxFallback(context: Context, modelDir: String): String? {
        val assetFiles = runCatching { context.assets.list(modelDir)?.toList().orEmpty() }.getOrDefault(emptyList())
        val assetOnnx = assetFiles.firstOrNull { it.endsWith(".onnx") }
        if (assetOnnx != null) return "$modelDir/$assetOnnx"

        val dirFile = File(context.filesDir, modelDir)
        val fileOnnx = dirFile.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".onnx") }
        return fileOnnx?.let { "$modelDir/${it.name}" }
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
        Log.i(TAG, "Copied $path -> ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    private fun copyAssetListToInternalStorage(context: Context, paths: String): String {
        if (paths.isBlank()) return paths
        return paths.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",") { copyAssetToInternalStorage(context, it) }
    }
}
