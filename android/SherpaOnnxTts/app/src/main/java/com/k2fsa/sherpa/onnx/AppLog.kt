package com.k2fsa.sherpa.onnx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger that collects:
 * - Device and OS metadata (Android 17 / Snapdragon 8 Elite / Hexagon V79 / ABIs / JNI libs)
 * - In-memory events and lifecycle logs
 * - Process logcat (tag: sherpa-onnx, QNN, SherpaOnnxTts, exceptions)
 * - One-click clipboard copy for user support
 */
object AppLog {
    private const val TAG = "SherpaOnnxTts"
    private const val LOG_FILE_NAME = "sherpa_app_logs.txt"
    private const val NPU_INIT_MARKER = "npu_init_in_progress.marker"
    private val memoryLogs = StringBuilder()
    private val listeners = mutableListOf<() -> Unit>()
    private var logFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (logFile == null) {
            val f = File(context.filesDir, LOG_FILE_NAME)
            logFile = f
            if (f.exists() && f.length() > 0) {
                runCatching {
                    val prev = f.readText()
                    memoryLogs.append("--- PREVIOUS SESSION LOGS ---\n")
                    memoryLogs.append(prev.takeLast(12000))
                    memoryLogs.append("--- NEW SESSION START ---\n")
                }
            }
        }
    }

    fun markNpuInitStart(context: Context, modelDir: String) {
        runCatching {
            File(context.filesDir, NPU_INIT_MARKER).writeText("$modelDir|${System.currentTimeMillis()}")
            writePublicLog(context, "STATUS: NPU initialization in progress for $modelDir...")
        }
    }

    fun markNpuInitDone(context: Context) {
        runCatching {
            File(context.filesDir, NPU_INIT_MARKER).delete()
            writePublicLog(context, "STATUS: Initialization finished.")
        }
    }

    fun writePublicLog(context: Context, extraNote: String = "") {
        runCatching {
            val diag = getFullDiagnostics(context) + (if (extraNote.isNotEmpty()) "\n=== NOTE ===\n$extraNote\n" else "")
            // Save to external app files dir (/sdcard/Android/data/com.k2fsa.sherpa.onnx/files/)
            context.getExternalFilesDir(null)?.let { extDir ->
                File(extDir, "sherpa_onnx_diagnostics.txt").writeText(diag)
            }
            // Also try public Download folder (/sdcard/Download/sherpa_onnx_diagnostics.txt)
            val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (dlDir != null && dlDir.exists()) {
                File(dlDir, "sherpa_onnx_diagnostics.txt").writeText(diag)
            }
        }
    }

    fun didPreviousNpuInitCrash(context: Context): String? {
        return runCatching {
            val marker = File(context.filesDir, NPU_INIT_MARKER)
            if (marker.exists()) {
                val info = marker.readText()
                marker.delete()
                info
            } else {
                null
            }
        }.getOrNull()
    }

    @Synchronized
    fun i(msg: String) {
        append("INFO", msg)
        Log.i(TAG, msg)
    }

    @Synchronized
    fun w(msg: String, tr: Throwable? = null) {
        append("WARN", if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
        Log.w(TAG, msg, tr)
    }

    @Synchronized
    fun e(msg: String, tr: Throwable? = null) {
        append("ERROR", if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
        Log.e(TAG, msg, tr)
    }

    @Synchronized
    private fun append(level: String, text: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val line = "[$time][$level] $text\n"
        memoryLogs.append(line)
        runCatching {
            logFile?.appendText(line)
        }
        notifyListeners()
    }

    @Synchronized
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    @Synchronized
    fun getRecentLogs(maxLines: Int = 100): String {
        val lines = memoryLogs.lines()
        return if (lines.size > maxLines) {
            lines.takeLast(maxLines).joinToString("\n")
        } else {
            memoryLogs.toString()
        }
    }

    fun copyToClipboard(context: Context): String {
        val diag = getFullDiagnostics(context)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SherpaOnnx_Diagnostics", diag)
        cm.setPrimaryClip(clip)
        return diag
    }

    fun getFullDiagnostics(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== SYSTEM & HARDWARE DIAGNOSTICS ===\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Brand / Model: ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Hardware / Board: ${Build.HARDWARE} / ${Build.BOARD}\n")
        sb.append("SoC / Chipset: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "N/A"}\n")
        sb.append("Android Release: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        sb.append("Native Libs Dir: ${context.applicationInfo.nativeLibraryDir}\n")

        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val libs = nativeDir.listFiles()?.sortedBy { it.name }?.map {
            "${it.name} (${String.format(Locale.ROOT, "%.1f", it.length() / 1024.0)} KB)"
        } ?: emptyList()
        sb.append("Installed Libs (${libs.size}):\n  ${libs.joinToString("\n  ")}\n\n")

        sb.append("=== TTS ENGINE STATE ===\n")
        sb.append("Active Backend: ${TtsManager.getActiveBackend()}\n")
        sb.append("Active Model Dir: ${TtsManager.getActiveModelDir(context)}\n")
        sb.append("Active Locale: ${TtsManager.getActiveLocale(context)}\n")
        val instance = TtsManager.getTts()
        if (instance != null) {
            sb.append("TTS Instance: Initialized (sampleRate=${instance.sampleRate()}, speakers=${instance.numSpeakers()}, lang=${instance.lang()})\n")
        } else {
            sb.append("TTS Instance: Not initialized yet\n")
        }
        sb.append("\n")

        sb.append("=== APP EVENT LOGS ===\n")
        synchronized(this) {
            sb.append(memoryLogs.toString())
        }
        sb.append("\n")

        sb.append("=== LOGCAT (NATIVE SHERPA-ONNX & QNN) ===\n")
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "300", "sherpa-onnx:V", "SherpaOnnxTts:V", "*:E")
            )
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                var count = 0
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                    count++
                }
                if (count == 0) {
                    sb.append("(No logcat entries captured)\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Failed to dump logcat: ${e.message}\n")
        }

        return sb.toString()
    }

    @Synchronized
    fun clear() {
        memoryLogs.setLength(0)
        runCatching { logFile?.writeText("") }
        notifyListeners()
    }
}
