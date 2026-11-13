package dev.termish.util

import kotlin.concurrent.Volatile

/**
 * Unified log layer. End-to-end tracing (connection/reconnect/network/notification/lifecycle),
 * You no longer need to use temporary `println` statements to debug and pinpoint issues.
 *
 * - [termLogEnabled] Platform-specific control based on build type: Android debuggable,
 *   iOS DEBUG Symbols
 * - [diagnosticsEnabled] Diagnostics mode: Can also be enabled at runtime in release mode (settings page),
 *   Also write to the log file ([logFileDirectory]) for exporting to troubleshoot production issues.
 * - Messages are lazy parameters: the lambda does not execute when the switch is off, and string concatenation incurs zero overhead
 * - Usage: TermLog.i("ssh") { "Connection starting $host:$port" }
 */
object TermLog {
    /** Diagnostic mode (switch on the settings page): "release" also logs and writes to files. */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    /** Whether to output (build, debug, or diagnostics mode). */
    val enabled: Boolean get() = termLogEnabled || diagnosticsEnabled

    /** Current log file path (not empty when diagnostics mode is enabled and file output is available). */
    val logFilePath: String? get() = if (diagnosticsEnabled) currentLogFile else null

    fun d(
        tag: String,
        msg: () -> String,
    ) = log('D', tag, msg)

    fun i(
        tag: String,
        msg: () -> String,
    ) = log('I', tag, msg)

    fun w(
        tag: String,
        msg: () -> String,
    ) = log('W', tag, msg)

    fun e(
        tag: String,
        msg: () -> String,
    ) = log('E', tag, msg)

    @Volatile
    private var currentLogFile: String? = null

    private fun log(
        level: Char,
        tag: String,
        msg: () -> String,
    ) {
        if (!enabled) return
        val text = msg()
        platformLog(level, tag, text)
        if (diagnosticsEnabled) writeFile(level, tag, text)
    }

    /** Add logging to a log file (1MB rotation: termish.log → termish.log.old). Failure is silent. */
    private fun writeFile(
        level: Char,
        tag: String,
        msg: String,
    ) {
        try {
            val dir = logFileDirectory() ?: return
            val file = TermLogFile("$dir/termish.log")
            if (file.length() > 1024 * 1024) {
                TermLogFile("$dir/termish.log.old").let {
                    if (it.exists()) it.delete()
                }
                file.renameTo("$dir/termish.log.old")
            }
            file.appendText("[$level/$tag] $msg\n")
            currentLogFile = file.absolutePath()
        } catch (_: Throwable) {
        }
    }
}

/** Is the platform build in debug mode (disable logging for release builds)? */
expect val termLogEnabled: Boolean

/** Platform log output: Android logcat / iOS NSLog. */
expect fun platformLog(
    level: Char,
    tag: String,
    msg: String,
)

/** Log file directory (used for writing files in diagnostics mode); returns null if unavailable. */
expect fun logFileDirectory(): String?

/** Share/export diagnostics logs (Android system sharing / iOS UIActivityViewController). */
expect fun shareDiagnosticLogs()
