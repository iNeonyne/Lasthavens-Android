package com.neonyne.lasthavenlauncher

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun redactsAuthorizationCookiesTokensAndSensitiveQueryParameters() {
    val sanitized =
      Diagnostics.sanitize(
        "Authorization: Bearer abc123\n" +
          "cookie=crumbs token=xyz access_token=hidden\n" +
          "https://example.invalid/play?session_id=private&view=game",
      )

    assertTrue(sanitized.contains("Authorization: [REDACTED]"))
    assertTrue(sanitized.contains("cookie=[REDACTED]"))
    assertTrue(sanitized.contains("token=[REDACTED]"))
    assertTrue(sanitized.contains("access_token=[REDACTED]"))
    assertTrue(sanitized.contains("session_id=[REDACTED]"))
    assertFalse(sanitized.contains("abc123"))
    assertFalse(sanitized.contains("crumbs"))
    assertFalse(sanitized.contains("hidden"))
    assertFalse(sanitized.contains("private"))
  }

  @Test
  fun redactsCompleteCookieHeaders() {
    val sanitized =
      Diagnostics.sanitize(
        "Cookie: session=first-secret; auth=second-secret\n" +
          "Set-Cookie: refresh=third-secret; HttpOnly",
      )

    assertTrue(sanitized.contains("Cookie: [REDACTED]"))
    assertTrue(sanitized.contains("Set-Cookie: [REDACTED]"))
    assertFalse(sanitized.contains("first-secret"))
    assertFalse(sanitized.contains("second-secret"))
    assertFalse(sanitized.contains("third-secret"))
  }

  @Test
  fun redactsJsonCredentialFields() {
    val sanitized =
      Diagnostics.sanitize("""{"access_token":"json-secret","session_id":"session-secret"}""")

    assertTrue(sanitized.contains("access_token"))
    assertTrue(sanitized.contains("session_id"))
    assertFalse(sanitized.contains("json-secret"))
    assertFalse(sanitized.contains("session-secret"))
  }

  @Test
  fun redactsKeySpellingVariantsAndBearerPrefixes() {
    val sanitized =
      Diagnostics.sanitize(
        "api_key=variant-one api-key=variant-two apikey=variant-three " +
          "session=plain-session session-id=dashed-session sessionid=joined-session " +
          "session_id=underscored-session refresh_token=rt-secret " +
          "token: Bearer prefixed-secret",
      )

    assertFalse(sanitized.contains("variant-"))
    assertFalse(sanitized.contains("plain-session"))
    assertFalse(sanitized.contains("dashed-session"))
    assertFalse(sanitized.contains("joined-session"))
    assertFalse(sanitized.contains("underscored-session"))
    assertFalse(sanitized.contains("rt-secret"))
    assertFalse(sanitized.contains("prefixed-secret"))
    assertTrue(sanitized.contains("token: [REDACTED]"))
  }

  @Test
  fun redactsSensitiveQueryParametersWhileKeepingOrdinaryOnes() {
    val sanitized =
      Diagnostics.sanitize(
        "https://example.invalid/play?auth=query-secret&key=key-secret&code=code-secret" +
          "&refresh_token=refresh-secret&password=password-secret&token=token-secret&view=game",
      )

    assertFalse(sanitized.contains("query-secret"))
    assertFalse(sanitized.contains("key-secret"))
    assertFalse(sanitized.contains("code-secret"))
    assertFalse(sanitized.contains("refresh-secret"))
    assertFalse(sanitized.contains("password-secret"))
    assertFalse(sanitized.contains("token-secret"))
    assertTrue(sanitized.contains("view=game"))
  }

  @Test
  fun redactsSingleQuotedAndUnquotedJsonSecrets() {
    val singleQuoted = Diagnostics.sanitize("'access_token':'single-secret'")
    val unquoted = Diagnostics.sanitize("{access_token: \"unquoted-secret\"}")

    assertFalse(singleQuoted.contains("single-secret"))
    assertFalse(unquoted.contains("unquoted-secret"))
    assertTrue(singleQuoted.contains("access_token"))
    assertTrue(unquoted.contains("access_token"))
  }

  @Test
  fun keepsNonSensitiveContentIntact() {
    val content = "Interface: 30300 view=game status=ok count=42"
    assertEquals(content, Diagnostics.sanitize(content))
  }

  @Test
  fun redactsUrlUserInfoWhileKeepingHostPathAndOrdinaryQueryParameters() {
    val content =
      "Opening https://guest:password@example.invalid/play and " +
        "wss://guest%40mail:p%40ss%2Fword@[::1]:8443/socket?view=game#ready"

    assertEquals(
      "Opening https://[REDACTED]@example.invalid/play and " +
        "wss://[REDACTED]@[::1]:8443/socket?view=game#ready",
      Diagnostics.sanitize(content),
    )
  }

  @Test
  fun redactsStandaloneBearerCredentialsWithoutConsumingFollowingText() {
    val content = "Request rejected (bEaReR\tabc.DEF+/_~-==), retrying in 5 seconds."

    assertEquals(
      "Request rejected (bEaReR\t[REDACTED]), retrying in 5 seconds.",
      Diagnostics.sanitize(content),
    )
  }

  @Test
  fun redactsCredentialsFromExceptionMessagesBeforeWriting() {
    val directory = temporaryFolder.newFolder("exception-redaction")
    val error = IOException("https://guest:password@example.invalid/play rejected Bearer abc123")

    Diagnostics.writeEntry(directory, "E", "Test", "Request failed: ${error.message}")

    val line = File(directory, "lasthavenplus.log").readText()
    assertTrue(
      line.endsWith(
        "E/Test: Request failed: https://[REDACTED]@example.invalid/play rejected Bearer [REDACTED]\n",
      ),
    )
  }

  @Test
  fun redactsCompleteBasicAndBearerAuthorizationHeaders() {
    val content = "Authorization: Basic dXNlcjpwYXNz\nauthorization: Bearer abc123\nstatus=failed"

    assertEquals(
      "Authorization: [REDACTED]\nauthorization: [REDACTED]\nstatus=failed",
      Diagnostics.sanitize(content),
    )
  }

  @Test
  fun keepsSimilarWordsAndAtSignsOutsideUrlAuthoritiesIntact() {
    val content =
      "BearerToken enabled, pallbearer ready, bearer_mode=on. " +
        "https://example.invalid/users/guest@example.invalid?view=guest@example.invalid"

    assertEquals(content, Diagnostics.sanitize(content))
  }

  @Test
  fun truncatesOversizedMessagesToTheEntryCap() {
    val directory = temporaryFolder.newFolder("entry-cap")
    Diagnostics.writeEntry(directory, "I", "Test", "a".repeat(6_000))

    val line = File(directory, "lasthavenplus.log").readText().trim()

    assertTrue(line.contains("a".repeat(4_000)))
    assertFalse(line.contains("a".repeat(4_001)))
  }

  @Test
  fun rotatesLogAtSizeBoundaryAndKeepsASinglePreviousFile() {
    val directory = temporaryFolder.newFolder("rotation")
    Diagnostics.writeEntry(directory, "I", "Test", "first-entry")
    val current = File(directory, "lasthavenplus.log")
    current.appendText("x".repeat(512 * 1024))

    Diagnostics.writeEntry(directory, "I", "Test", "second-entry")

    val previous = File(directory, "lasthavenplus.log.1")
    assertTrue(previous.isFile)
    assertTrue(previous.readText().contains("first-entry"))
    assertFalse(current.readText().contains("first-entry"))
    assertTrue(current.readText().contains("second-entry"))

    current.appendText("x".repeat(512 * 1024))
    Diagnostics.writeEntry(directory, "I", "Test", "third-entry")

    assertFalse(File(directory, "lasthavenplus.log.2").exists())
    assertTrue(File(directory, "lasthavenplus.log.1").readText().contains("second-entry"))
    assertTrue(current.readText().contains("third-entry"))
    assertFalse(current.readText().contains("second-entry"))
  }

  @Test
  fun rotationFallsBackToTruncationWhenRenameFails() {
    val directory = temporaryFolder.newFolder("rotation-failure")
    val stuckPrevious = File(directory, "lasthavenplus.log.1")
    assertTrue(stuckPrevious.mkdirs())
    File(stuckPrevious, "occupied").writeText("blocked")
    val current = File(directory, "lasthavenplus.log")
    current.writeText("stale-content")
    current.appendText("x".repeat(512 * 1024))

    Diagnostics.writeEntry(directory, "I", "Test", "after-failure")

    assertTrue(stuckPrevious.isDirectory)
    assertTrue(current.readText().contains("after-failure"))
    assertFalse(current.readText().contains("stale-content"))
  }

  @Test
  fun writeEntryFailsWhenDirectoryPathIsOccupiedByAFile() {
    val blocked = temporaryFolder.newFile("blocked")

    assertThrows(IOException::class.java) {
      Diagnostics.writeEntry(blocked, "I", "Test", "unwritable")
    }
  }

  @Test
  fun readEntriesReturnsPlaceholderWhenNoLogsExist() {
    val directory = temporaryFolder.newFolder("empty-read")

    assertEquals("No diagnostic entries yet.", Diagnostics.readEntries(directory))
  }

  @Test
  fun readEntriesBoundsOutputToNewestEightyThousandChars() {
    val directory = temporaryFolder.newFolder("read-bound")
    File(directory, "lasthavenplus.log").writeText("y".repeat(90_000))

    val content = Diagnostics.readEntries(directory)

    assertEquals(80 * 1024, content.length)
    assertTrue(content.endsWith("y"))
  }

  @Test
  fun readEntriesReturnsRotatedLogBeforeCurrentLog() {
    val directory = temporaryFolder.newFolder("read-order")
    File(directory, "lasthavenplus.log.1").writeText("older\n")
    File(directory, "lasthavenplus.log").writeText("newer\n")

    assertEquals("older\nnewer\n", Diagnostics.readEntries(directory))
  }

  @Test
  fun clearEntriesRemovesCurrentAndRotatedLogs() {
    val directory = temporaryFolder.newFolder("clear")
    File(directory, "lasthavenplus.log").writeText("current")
    File(directory, "lasthavenplus.log.1").writeText("previous")

    Diagnostics.clearEntries(directory)

    assertFalse(File(directory, "lasthavenplus.log").exists())
    assertFalse(File(directory, "lasthavenplus.log.1").exists())
    assertEquals("No diagnostic entries yet.", Diagnostics.readEntries(directory))
  }
}
