// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

data class GenerationConfig(
    var silenceScale: Float = 0.2f,
    var speed: Float = 1.0f,
    var sid: Int = 0,
    var referenceAudio: FloatArray? = null,
    var referenceSampleRate: Int = 0,
    var referenceText: String? = null,
    var numSteps: Int = 5,
    var extra: Map<String, String>? = null,
    // Pre-phonemized input as Unicode codepoints (one inner array per sentence).
    var phonemeCodepoints: Array<IntArray>? = null,
    var tokens: Array<Array<String>>? = null
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private val ptrLock = ReentrantReadWriteLock()
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid OfflineTtsConfig: failed to create native OfflineTts"
        }
    }

    private inline fun <T> withValidPtr(block: (Long) -> T): T = ptrLock.read {
        val current = ptr
        check(current != 0L) { "OfflineTts instance has been released" }
        block(current)
    }

    fun sampleRate() = withValidPtr { getSampleRate(it) }

    fun numSpeakers() = withValidPtr { getNumSpeakers(it) }

    // Language/voice of the model, e.g., "fr", "en-us".
    fun lang() = withValidPtr { getLang(it) }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio = withValidPtr {
        generateImpl(it, text = text, sid = sid, speed = speed)
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio = withValidPtr {
        generateWithCallbackImpl(
            it,
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
    }

    fun generateWithConfig(
        text: String,
        config: GenerationConfig
    ): GeneratedAudio = withValidPtr {
        generateWithConfigImpl(it, text, config, null)
    }

    fun generateWithConfigAndCallback(
        text: String,
        config: GenerationConfig,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio = withValidPtr {
        generateWithConfigImpl(it, text, config, callback)
    }

    fun allocate(assetManager: AssetManager? = null) = ptrLock.write {
        if (ptr == 0L) {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
            require(ptr != 0L) {
                "Invalid OfflineTtsConfig: failed to create native OfflineTts"
            }
        }
    }

    fun free() = ptrLock.write {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        free()
    }

    fun release() = free()

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int
    private external fun getLang(ptr: Long): String

    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio

    private external fun generateWithConfigImpl(
        ptr: Long,
        text: String,
        config: GenerationConfig,
        callback: ((samples: FloatArray) -> Int)?
    ): GeneratedAudio

    companion object {
        @JvmStatic
        external fun prependAdspLibraryPath(newPath: String)

        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

/**
 * Build an OfflineTtsConfig for Piper / VITS TTS:
 * - Qualcomm Hexagon V79 NPU (S25 Ultra) when qnnContextBinary / qnnBackendLib is provided
 * - CPU ONNX Runtime fallback when modelName (.onnx) is provided with provider = "cpu"
 */
fun getOfflineTtsConfig(
    modelDir: String,
    modelName: String = "",
    lexicon: String = "",
    dataDir: String = "",
    dictDir: String = "",
    ruleFsts: String = "",
    ruleFars: String = "",
    numThreads: Int? = null,
    provider: String = "cpu",
    qnnBackendLib: String = "",
    qnnSystemLib: String = "",
    qnnContextBinary: String = "",
    qnnHtpPerfMode: Boolean = false,
): OfflineTtsConfig {
    val numberOfThreads = numThreads ?: 2

    if (modelName.isEmpty() && qnnContextBinary.isEmpty()) {
        throw IllegalArgumentException(
            "Please specify a Piper TTS model (qnnContextBinary for NPU or modelName for CPU ONNX)"
        )
    }

    val lexiconPath = if (lexicon.isEmpty()) {
        lexicon
    } else {
        lexicon.split(",").joinToString(",") { entry ->
            if ('/' in entry) entry else "$modelDir/$entry"
        }
    }

    val resolvedModelPath = if (modelName.isEmpty()) {
        ""
    } else {
        modelName.split(",").joinToString(",") { entry ->
            val trimmed = entry.trim()
            if ('/' in trimmed) trimmed else "$modelDir/$trimmed"
        }
    }

    val resolvedQnnContextBinary = if (qnnContextBinary.isEmpty()) {
        ""
    } else {
        qnnContextBinary.split(",").joinToString(",") { entry ->
            val trimmed = entry.trim()
            if ('/' in trimmed) trimmed else "$modelDir/$trimmed"
        }
    }

    val isNarrateur = modelDir.contains("narrateur")
    val defaultNoiseScale = if (isNarrateur) 0.35f else 0.667f
    val defaultNoiseScaleW = if (isNarrateur) 0.333f else 0.8f

    val vits = OfflineTtsVitsModelConfig(
        model = resolvedModelPath,
        lexicon = lexiconPath,
        tokens = "$modelDir/tokens.txt",
        dataDir = dataDir,
        dictDir = dictDir,
        noiseScale = defaultNoiseScale,
        noiseScaleW = defaultNoiseScaleW,
        qnnConfig = QnnConfig(
            backendLib = qnnBackendLib,
            systemLib = qnnSystemLib,
            contextBinary = resolvedQnnContextBinary,
            htpPerfMode = qnnHtpPerfMode,
        ),
    )

    val actualProvider = if (qnnContextBinary.isNotEmpty() || qnnBackendLib.isNotEmpty()) {
        "qnn"
    } else {
        provider
    }

    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = numberOfThreads,
            debug = actualProvider == "qnn",
            provider = actualProvider,
        ),
        ruleFsts = ruleFsts,
        ruleFars = ruleFars,
    )
}
