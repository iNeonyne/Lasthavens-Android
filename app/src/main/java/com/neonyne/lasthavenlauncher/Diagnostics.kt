package com.neonyne.lasthavenlauncher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, local-only diagnostic log with basic credential/session redaction. */
object Diagnostics {
  private const val MAX_LOG_BYTES = 512L * 1024L
  private const val MAX_READ_CHARS = 80 * 1024
  private const val LOG_FILE = "lasthavenplus.log"

  private val urlUserInfoPattern = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^\s/?#@<>"\\]+@""")
  private val secretHeaderPattern =
    Regex("""(?im)(\b(?:authorization|cookie|set-cookie)\s*:\s*)[^\r\n]+""")
  private val bearerPattern = Regex("""(?i)(\bbearer[ \t]+)[a-z0-9._~+/-]+=*""")
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
    val previous = File(directory, "$LOG_FILE.1").takeIf { it.isFile }?.readText().orEmpty()
    val current = File(directory, LOG_FILE).takeIf { it.isFile }?.readText().orEmpty()
    return (previous + current).takeLast(MAX_READ_CHARS).ifBlank { "No diagnostic entries yet." }
  }

  @Synchronized
  internal fun clearEntries(directory: File) {
    File(directory, LOG_FILE).delete()
    File(directory, "$LOG_FILE.1").delete()
  }

  internal fun sanitize(value: String): String =
    value
      .replace(urlUserInfoPattern) { match -> "${match.groupValues[1]}[REDACTED]@" }
      .replace(secretHeaderPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(bearerPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(secretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(jsonSecretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
      .replace(querySecretPattern) { match -> "${match.groupValues[1]}[REDACTED]" }
}
