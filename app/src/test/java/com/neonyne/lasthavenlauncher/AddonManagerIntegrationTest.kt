package com.neonyne.lasthavenlauncher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-contract regressions for the Addon Manager's same-session integration (issue #170). */
class AddonManagerIntegrationTest {
  private val sourceRoot =
    File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")

  private fun source(name: String): String =
    File(sourceRoot, "com/neonyne/lasthavenlauncher/$name").readText()

  @Test
  fun managerReturnRefreshesBridgeSnapshotWithoutStartingOrReplacingGameSession() {
    val main = source("MainActivity.kt")
    val managerBranch =
      main.substringAfter("Screen.AddonManager ->").substringBefore("Screen.Diagnostics ->")
    assertTrue(managerBranch.contains("addonBridgeController.refreshSnapshot()"))
    assertTrue(managerBranch.contains("LauncherNavigationPolicy.returnToGame(navigation)"))
    assertTrue(!managerBranch.contains("startGame("))
    assertTrue(!managerBranch.contains("restoreGame("))
  }

  @Test
  fun managerChangesAreMarkedPendingAndOnlyClearedAfterSuccessfulExplicitReload() {
    val main = source("MainActivity.kt")
    assertTrue(main.contains("var addonReloadRequired by remember { mutableStateOf(false) }"))
    assertTrue(main.contains("addonReloadRequired = true"))
    val reload =
      main.substringAfter("val reloadGame: () -> Unit =").substringBefore("val loadGameUrl:")
    assertTrue(reload.contains("if (refreshed)"))
    assertTrue(reload.contains("gameSessionController.reloadGame(context, gameUrl)"))
    assertTrue(reload.contains("addonReloadRequired = false"))
  }

  @Test
  fun managerHostReportsLibraryRevisionChangesAndKeepsOperationsSerialized() {
    val host = source("AddonManagerHost.kt")
    assertTrue(host.contains("initialRevision"))
    assertTrue(host.contains("libraryChanged = true"))
    assertTrue(host.contains("BackHandler {"))
    assertTrue(host.contains("if (!busy) onBack(libraryChanged)"))
    assertTrue(host.contains("executor.execute"))
  }

  @Test
  fun archiveImportExposesEveryRequiredOperationStage() {
    val archive = source("AddonArchive.kt")
    val repository = source("AddonLibraryRepository.kt")
    val host = source("AddonManagerHost.kt")
    listOf(
        "COPYING_ARCHIVE",
        "VALIDATING_ARCHIVE",
        "EXTRACTING_FILES",
        "INDEXING_ADDONS",
      )
      .forEach { stage ->
        assertTrue(archive.contains("onStage(AddonImportStage.$stage)"))
        assertTrue(host.contains("AddonImportStage.$stage ->"))
      }
    assertTrue(repository.contains("onStage(AddonImportStage.COMMITTING)"))
    assertTrue(repository.contains("onStage(AddonImportStage.COMPLETE)"))
  }

  @Test
  fun replacementRequiresNamedUserConfirmationBeforeLibraryTransaction() {
    val repository = source("AddonLibraryRepository.kt")
    val host = source("AddonManagerHost.kt")
    val screen = source("AddonManagerScreen.kt")
    val confirmation = repository.indexOf("confirmReplacement(replacedFolders)")
    val transactionPlanning = repository.indexOf("val plans =")
    assertTrue(confirmation >= 0)
    assertTrue(confirmation < transactionPlanning)
    assertTrue(host.contains("CompletableFuture<Boolean>()"))
    assertTrue(screen.contains("folders.forEach"))
    assertTrue(screen.contains("onCancelReplacement"))
  }

  @Test
  fun existingGameSessionControllerRemainsCompositionScopedAcrossManagerNavigation() {
    val main = source("MainActivity.kt")
    assertTrue(main.contains("val gameSessionController = remember { GameSessionController() }"))
    assertTrue(main.contains("session = gameSessionController.session"))
    assertTrue(main.contains("AddonManagerHost("))
  }
}
