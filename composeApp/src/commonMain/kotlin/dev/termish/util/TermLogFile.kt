package dev.termish.util

/**
 * Cross-platform log file handle (for diagnostics logs with 1MB rotation):
 * commonMain cannot directly reference java.io.File (Kotlin/Native does not have a JVM),
 * Platform implementation: Android = java.io.File; iOS = NSFileManager/NSFileHandle.
 */
internal expect class TermLogFile(
    path: String,
) {
    fun exists(): Boolean

    fun length(): Long

    fun delete()

    /** Rename (assuming the target does not exist). */
    fun renameTo(target: String): Boolean

    /** Append (create the file if it does not exist). */
    fun appendText(text: String)

    fun absolutePath(): String
}
