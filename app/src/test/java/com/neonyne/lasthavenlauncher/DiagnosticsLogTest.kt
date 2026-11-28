package com.neonyne.lasthavenlauncher

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsLogTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun readsAndClearsOnTheInjectedDispatcher() = runBlocking {
    val callerThread = Thread.currentThread()
    val directory = temporaryFolder.newFolder()
    Diagnostics.writeEntry(directory, "I", "Test", "token=private")
    val expected = Diagnostics.readEntries(directory)

    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
      val log =
        DiagnosticsLog(
          read = {
            assertNotSame(callerThread, Thread.currentThread())
            Diagnostics.readEntries(directory)
          },
          clear = {
            assertNotSame(callerThread, Thread.currentThread())
            Diagnostics.clearEntries(directory)
          },
          dispatcher = dispatcher,
        )

      assertEquals(DiagnosticsState.Loaded(expected), log.execute(DiagnosticsAction.READ))
      assertEquals(DiagnosticsState.Cleared, log.execute(DiagnosticsAction.CLEAR))
      assertEquals("No diagnostic entries yet.", Diagnostics.readEntries(directory))
    }
  }

  @Test
  fun reportsFailuresAndCanRetryAfterThePathIsFixed() = runBlocking {
    val directory = temporaryFolder.newFolder()
    val blockedLog = File(directory, "lasthavenplus.log").apply { mkdir() }
    val child = File(blockedLog, "occupied").apply { writeText("occupied") }
    val rotatedLog = File(directory, "lasthavenplus.log.1").apply { writeText("older entry") }
    val log =
      DiagnosticsLog(
        { Diagnostics.readEntries(directory) },
        { Diagnostics.clearEntries(directory) },
      )

    assertEquals(
      DiagnosticsState.Failed(DiagnosticsAction.READ),
      log.execute(DiagnosticsAction.READ),
    )
    assertEquals(
      DiagnosticsState.Failed(DiagnosticsAction.CLEAR),
      log.execute(DiagnosticsAction.CLEAR),
    )

    assertFalse(rotatedLog.exists())
    check(child.delete() && blockedLog.delete())
    assertEquals(
      DiagnosticsState.Loaded("No diagnostic entries yet."),
      log.execute(DiagnosticsAction.READ),
    )
    assertEquals(DiagnosticsState.Cleared, log.execute(DiagnosticsAction.CLEAR))
  }
}
