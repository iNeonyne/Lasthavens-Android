package com.neonyne.lasthavenlauncher

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, local-only diagnostic log with basic credential/session redaction. */
object Diagnostics {
  private const val MAX_LOG_BYTES = 512L * 1024L
  private const val MAX_READ_CHARS = 80 * 1024
  // UTF-8 uses at most three bytes per UTF-16 code unit. Allow for a split code point.
  private const val MAX_READ_BYTES = MAX_READ_CHARS * 3 + 4
  private const val LOG_FILE = "lasthavenplus.log"

  private val secretHeaderPattern =
    Regex("""(?im)(\b(?:authorization|cookie|set-cookie)\s*:\s*)[^\r\n]+""")
  private val secretPattern =
    Regex(
      """(?i)((?:access[_-]?token|refresh[_-]?token|password|passwd|authorization|set-cookie|cookie|session(?:[_-]?id)?|token|secret|api[_-]?key)(\s*[=:]\s*))(?:bearer\s+)?([^&\s,;]+)""",
    )
  private val jsonSecretPattern =
    Regex(
      """(?i)(["']?(?:access[_-]?token|refresh[_-]?token|password|passwd|authorization|set-cookie|cookie|session(?:[_-]?id)?|token|secret|api[_-]?key)["']\s*:\s*["']?)([^"'&\s,;}]+)""",
    )
  private val querySecretPattern =
    Regex(
      """(?i)([?&](?:access[_-]?token|refresh[_-]?token|token|session(?:[_-]?id)?|auth|api[_-]?key|key|code|password|secret)=)[^&#\s]+""",
    )

  @Synchronized
  fun log(context: Context, level: String, tag: String, message: String) {
    runCatching {
      writeEntry(File(context.applicationContext.filesDir, "diagnostics"), level, tag, message)
    }
  }

  fun info(context: Context, tag: String, message: String) = log(context, "I", tag, message)

  fun warn(context: Context, tag: String, message: String) = log(context, "W", tag, message)

  fun error(context: Context, tag: String, message: String) = log(context, "E", tag, message)

  @Synchronized
  fun read(context: Context): String {
    return readEntries(File(context.applicationContext.filesDir, "diagnostics"))
  }

  @Synchronized
  fun clear(context: Context) {
    clearEntries(File(context.applicationContext.filesDir, "diagnostics"))
  }

  @Synchronized
  internal fun writeEntry(directory: File, level: String, tag: String, message: String) {
    if (!directory.exists() && !directory.mkdirs()) return
    val file = File(directory, LOG_FILE)
    if (file.length() >= MAX_LOG_BYTES) {
      val previous = File(directory, "$LOG_FILE.1")
      previous.delete()
      if (!file.renameTo(previous)) file.writeText("")
    }
    val safeMessage = sanitize(message).take(4_000)
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    file.appendText("$timestamp $level/$tag: $safeMessage\n")
  }

  @Synchronized
  internal fun readEntries(directory: File): String {
    val previous = readTail(File(directory, "$LOG_FILE.1"))
    val current = readTail(File(directory, LOG_FILE))
    return (previous + current).takeLast(MAX_READ_CHARS).ifBlank { "No diagnostic entries yet." }
  }

  private fun readTail(file: File): String {
    val path = file.toPath()
    try {
      if (!Files.readAttributes(path, BasicFileAttributes::class.java).isRegularFile) {
        throw IOException("Invalid diagnostics log file.")
      }
      return Files.newByteChannel(path).use { channel ->
        val size = channel.size()
        val bytes = ByteBuffer.allocate(minOf(size, MAX_READ_BYTES.toLong()).toInt())
        channel.position(size - bytes.capacity())
        while (bytes.hasRemaining() && channel.read(bytes) > 0) {
          // Fill the buffer, including when a read returns fewer bytes than requested.
        }
        bytes.array().decodeToString(endIndex = bytes.position())
      }
    } catch (_: NoSuchFileException) {
      return ""
    }
  }

  @Synchronized
  internal fun clearEntries(directory: File) {
    try {
      Files.deleteIfExists(File(directory, LOG_FILE).toPath())
    } finally {
      Files.deleteIfExists(File(directory, "$LOG_FILE.1").toPath())
    }
  }

  internal fun sanitize(value: String): String =
    value
      .replace(secretHeaderPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(secretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(jsonSecretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(querySecretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
}
