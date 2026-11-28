package com.neonyne.lasthavenlauncher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the composition-root wiring that makes the bundled bridge part of the live game session.
 */
class AddonBridgeWiringTest {
  private val sourceRoot =
    File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")

  @Test
  fun `extension installation and sender binding precede the first game navigation`() {
    val mainSource = File(sourceRoot, "com/neonyne/lasthavenlauncher/MainActivity.kt").readText()
    val sessionSource =
      File(sourceRoot, "com/neonyne/lasthavenlauncher/GameSessionController.kt").readText()

    val startBlock =
      mainSource.substringAfter("val startGame: () -> Unit = {").substringBefore("val restoreGame")

    assertTrue(startBlock.contains("addonBridgeController.ensureInstalled(context) {"))
    assertTrue(
      startBlock.contains("gameSessionController.startGame(context, gameUrl) { session ->")
    )
    assertTrue(
      startBlock.indexOf("ensureInstalled") < startBlock.indexOf("gameSessionController.startGame")
    )
    assertTrue(mainSource.contains("addonBridgeController.setGameSession(session)"))

    val bindPositions =
      Regex("onSessionReady\\(gameSession\\)")
        .findAll(sessionSource)
        .map { it.range.first }
        .toList()
    val loadPositions =
      Regex("gameSession\\.loadUri\\(gameUrl\\)")
        .findAll(sessionSource)
        .map { it.range.first }
        .toList()
    assertTrue("both create and reopen paths must bind the session", bindPositions.size >= 2)
    assertTrue("both create and reopen paths must load the URI", loadPositions.size >= 2)
    assertTrue(
      "new-session sender binding must precede its first navigation",
      bindPositions[0] < loadPositions[0],
    )
    assertTrue(
      "reopened-session sender binding must precede its navigation",
      bindPositions[1] < loadPositions[1],
    )
  }

  @Test
  fun `bridge refreshes payload before explicit reload and tears down with composition`() {
    val source = File(sourceRoot, "com/neonyne/lasthavenlauncher/MainActivity.kt").readText()
    val reloadBlock =
      source.substringAfter("val reloadGame: () -> Unit = {").substringBefore("val startGame")

    assertTrue(reloadBlock.contains("addonBridgeController.ensureInstalled(context)"))
    assertTrue(reloadBlock.contains("addonBridgeController.refreshSnapshot {"))
    assertTrue(reloadBlock.indexOf("refreshSnapshot") < reloadBlock.indexOf("reloadGame"))
    assertTrue(source.contains("addonBridgeController.teardown()"))
    assertTrue(source.contains("addonBridgeExecutor.shutdown()"))
  }

  @Test
  fun `bridge reuses the app private library and existing session for restore`() {
    val source = File(sourceRoot, "com/neonyne/lasthavenlauncher/MainActivity.kt").readText()

    assertTrue(source.contains("File(context.filesDir, \"addons\")"))
    assertTrue(source.contains("snapshotProvider = { addonLibraryRepository.readLibrary() }"))
    assertTrue(source.contains("gameSessionController.restoreGame(context, gameUrl) { session ->"))
    assertTrue(source.contains("addonBridgeController.refreshSnapshot {"))
    assertTrue(source.contains("gameSessionController.startGame(context, gameUrl) { session ->"))
    assertTrue(source.contains("addonBridgeController.setGameSession(session)"))
    assertTrue(source.contains("gameSessionController.reloadGame(context, gameUrl)"))
  }
}
