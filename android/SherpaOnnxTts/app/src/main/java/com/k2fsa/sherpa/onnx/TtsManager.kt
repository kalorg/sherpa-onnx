package com.k2fsa.sherpa.onnx

import android.content.Context
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
 * - Full diagnostics logging into AppLog.
 */
object TtsManager {
    const val FR_MODEL_DIR = "pipertts-fr-qnn-s25-ultra"
    const val EN_MODEL_DIR = "pipertts-en-qnn-s25-ultra"
    private const val QNN_BINS = "encoder.bin,sdp.bin,flow.bin,decoder.bin"

    private val lock = Any()

    @Volatile
    private var ttsInstance: OfflineTts? = null

    @Volatile
    private var activeBackend: String = "Not Initialized"

    @Volatile
    private var activeModelDir: String = FR_MODEL_DIR

    @Volatile
    private var forcedBackendMode: String = "auto" // "auto", "npu", "cpu"

    @Volatile
    private var phonemizerReady: Boolean = false

    fun getTts(): OfflineTts? = ttsInstance

    fun getActiveBackend(): String = activeBackend

    fun getForcedBackendMode(): String = forcedBackendMode

    fun setForcedBackendMode(mode: String) {
        forcedBackendMode = mode
    }

    fun getActiveModelDir(context: Context): String {
        if (hasVoiceInAssetsOrFiles(context, FR_MODEL_DIR)) {
            return FR_MODEL_DIR
        }
        return EN_MODEL_DIR
    }

    fun getActiveLocale(context: Context): Locale {
        val t = ttsInstance
        val lang = t?.lang()?.lowercase(Locale.ROOT).orEmpty()
        if (lang.startsWith("fr") || activeModelDir.contains("-fr-")) {
            return Locale.FRANCE
        }
        return Locale.US
    }

    fun getVoiceId(context: Context): String {
        val locale = getActiveLocale(context)
        val name = if (activeModelDir.contains("-fr-")) "siwis" else "amy"
        return "piper-${locale.language}-$name"
    }

    fun reset() {
        synchronized(lock) {
            ttsInstance = null
            activeBackend = "Not Initialized"
        }
    }

    fun ensureInitialized(
        context: Context,
        preferredModelDir: String? = null,
        forceMode: String? = null
    ): OfflineTts {
        val appContext = context.applicationContext
        val targetDir = preferredModelDir ?: if (activeModelDir.isNotEmpty()) activeModelDir else getActiveModelDir(appContext)
        val mode = forceMode ?: forcedBackendMode

        ttsInstance?.let {
            if (activeModelDir == targetDir && forcedBackendMode == mode) return it
        }

        synchronized(lock) {
            ttsInstance?.let {
                if (activeModelDir == targetDir && forcedBackendMode == mode) return it
            }

            val startTime = System.currentTimeMillis()
            val modelDir = targetDir
            activeModelDir = modelDir
            forcedBackendMode = mode

            AppLog.i("--- TTS INITIALIZATION START ---")
            AppLog.i("Target voice: $modelDir | Mode: $mode")

            if (!phonemizerReady) {
                AppLog.i("Initializing Piper phonemizer native libraries...")
                val pStart = System.currentTimeMillis()
                piperPhonemizeInitialize(appContext)
                phonemizerReady = true
                AppLog.i("Phonemizer initialized in ${System.currentTimeMillis() - pStart} ms")
            }

            val cfgAsset = "$modelDir/config.json"
            if (assetExists(appContext, cfgAsset)) {
                copyAssetToInternalStorage(appContext, cfgAsset)
            }
            val tokensPath = copyAssetToInternalStorage(appContext, "$modelDir/tokens.txt")
            AppLog.i("Tokens path: $tokensPath (exists: ${File(tokensPath).exists()}, size: ${File(tokensPath).length()} bytes)")

            // 1. Try Qualcomm Hexagon V79 NPU (QNN) if allowed
            var qnnError: Throwable? = null
            if (mode != "cpu" && hasQnnBinaries(appContext, modelDir)) {
                try {
                    AppLog.i("Attempting Qualcomm QNN HTP V79 NPU from $modelDir")
                    val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
                    AppLog.i("Native library dir: $nativeLibDir")
                    OfflineTts.prependAdspLibraryPath(nativeLibDir)

                    val qnnConfig = getOfflineTtsConfig(
                        modelDir = modelDir,
                        qnnBackendLib = "libQnnHtp.so",
                        qnnSystemLib = "libQnnSystem.so",
                        qnnContextBinary = QNN_BINS,
                    )
                    qnnConfig.model.vits.tokens = tokensPath
                    qnnConfig.model.vits.qnnConfig.contextBinary =
                        copyAssetListToInternalStorage(appContext, qnnConfig.model.vits.qnnConfig.contextBinary)

                    AppLog.i("Context binaries staged: ${qnnConfig.model.vits.qnnConfig.contextBinary}")
                    AppLog.i("Instantiating native OfflineTts (QNN HTP V79)...")
                    val qnnInitStart = System.currentTimeMillis()
                    val created = OfflineTts(assetManager = null, config = qnnConfig)
                    val qnnElapsed = System.currentTimeMillis() - qnnInitStart

                    activeBackend = "Qualcomm Hexagon NPU (QNN HTP V79)"
                    ttsInstance = created
                    AppLog.i("SUCCESS: Qualcomm NPU TTS ready in $qnnElapsed ms (sampleRate=${created.sampleRate()}, lang=${created.lang()})")
                    return created
                } catch (e: Throwable) {
                    AppLog.w("Qualcomm NPU init failed: ${e.message}", e)
                    qnnError = e
                    if (mode == "npu") {
                        AppLog.e("Forced NPU mode requested: aborting fallback")
                        throw e
                    }
                }
            } else if (mode == "cpu") {
                AppLog.i("Mode is forced CPU: skipping NPU check")
            } else {
                AppLog.i("No QNN context binaries in $modelDir, skipping NPU")
            }

            // 2. Automatic CPU fallback if a standard Piper .onnx model exists
            val onnxFallback = findOnnxFallback(appContext, modelDir)
            if (onnxFallback != null) {
                AppLog.i("Starting CPU ONNX Runtime fallback: $onnxFallback")
                val cpuConfig = getOfflineTtsConfig(
                    modelDir = modelDir,
                    modelName = onnxFallback,
                    provider = "cpu",
                )
                cpuConfig.model.vits.tokens = tokensPath
                val modelFile = copyAssetToInternalStorage(appContext, cpuConfig.model.vits.model)
                cpuConfig.model.vits.model = modelFile
                AppLog.i("ONNX model staged at: $modelFile (size: ${File(modelFile).length() / (1024 * 1024)} MB)")

                val cpuInitStart = System.currentTimeMillis()
                val created = OfflineTts(assetManager = null, config = cpuConfig)
                val cpuElapsed = System.currentTimeMillis() - cpuInitStart

                activeBackend = "CPU Fallback (ONNX Runtime)"
                ttsInstance = created
                AppLog.i("SUCCESS: CPU ONNX TTS ready in $cpuElapsed ms (sampleRate=${created.sampleRate()}, lang=${created.lang()})")
                return created
            }

            val finalError = qnnError ?: IllegalStateException(
                "No QNN context binaries ($QNN_BINS) or CPU fallback (.onnx) found in $modelDir"
            )
            AppLog.e("FAILED: No viable backend could be initialized: ${finalError.message}", finalError)
            throw finalError
        }
    }

    fun phonemize(tts: OfflineTts, text: String): Array<IntArray> {
        val start = System.currentTimeMillis()
        var voice = tts.lang()
        if (voice.isEmpty()) {
            voice = if (activeModelDir.contains("-fr-")) "fr" else "en-us"
        }
        AppLog.i("Phonemizing text (${text.length} chars) using voice '$voice'...")
        val result = piperPhonemizeText(text, voice)
            ?: throw RuntimeException("Failed to phonemize text for voice '$voice'")
        try {
            val phonemes = Array(result.numSentences) { i -> result.getPhonemes(i) }
            val totalTokens = phonemes.sumOf { it.size }
            val elapsed = System.currentTimeMillis() - start
            AppLog.i("Phonemization complete in $elapsed ms: ${phonemes.size} sentence(s), $totalTokens token(s)")
            return phonemes
        } finally {
            result.close()
        }
    }

    fun hasVoiceInAssetsOrFiles(context: Context, dir: String): Boolean {
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
        AppLog.i("Extracted asset: $path (${outFile.length() / 1024} KB)")
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
