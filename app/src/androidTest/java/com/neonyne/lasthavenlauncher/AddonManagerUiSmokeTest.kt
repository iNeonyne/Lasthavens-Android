package com.neonyne.lasthavenlauncher

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Addon Manager Compose surface without starting a remote game session. */
@RunWith(AndroidJUnit4::class)
class AddonManagerUiSmokeTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun addonManagerRendersAddonAndItsPrimaryActions() {
    val imported = AtomicBoolean(false)
    val enabled = AtomicBoolean(false)
    val returned = AtomicBoolean(false)
    val exported = AtomicBoolean(false)
    val removed = AtomicBoolean(false)
    val addon =
      AddonLibraryItem(
        title = "Landscape Smoke Addon",
        folder = "LandscapeSmoke",
        version = "1.0",
        interfaceVersion = "30300",
        notes = "",
        dependencies = emptyList(),
        sizeBytes = 2_048L,
        filesCount = 3,
        enabled = false,
        importedAt = 1L,
        warnings = emptyList(),
      )

    composeRule.setContent {
      MaterialTheme(
        colorScheme = DesignTokens.colorScheme(),
        typography = DesignTokens.typography(),
        shapes = DesignTokens.shapes,
      ) {
        AddonManagerScreen(
          snapshot = AddonLibrarySnapshot(revision = "ui-smoke", addons = listOf(addon)),
          busy = false,
          message = null,
          onImport = { imported.set(true) },
          onToggle = { folder, value ->
            assertTrue(folder == addon.folder)
            enabled.set(value)
          },
          onExport = { folder -> exported.set(folder == addon.folder) },
          onRemove = { folder -> removed.set(folder == addon.folder) },
          onBack = { returned.set(true) },
        )
      }
    }

    composeRule.onNodeWithText("Local addons").assertIsDisplayed()
    composeRule.onNodeWithText("Landscape Smoke Addon").assertIsDisplayed()
    composeRule.onNodeWithText("Import ZIP").assertIsDisplayed()
    composeRule.onNodeWithText("Import ZIP").performClick()
    composeRule.onNode(isToggleable()).performClick()

    composeRule
      .onNodeWithContentDescription("More actions for Landscape Smoke Addon")
      .performClick()
    composeRule.onNodeWithText("Details").performClick()
    composeRule.onNodeWithText("LandscapeSmoke · 1.0").assertIsDisplayed()
    composeRule.onNodeWithText("Close").performClick()

    composeRule
      .onNodeWithContentDescription("More actions for Landscape Smoke Addon")
      .performClick()
    composeRule.onNodeWithText("Export ZIP").performClick()

    composeRule
      .onNodeWithContentDescription("More actions for Landscape Smoke Addon")
      .performClick()
    composeRule.onNodeWithText("Remove").performClick()
    composeRule.onNodeWithText("Remove").performClick()

    composeRule.onNodeWithText("Back").performClick()
    composeRule.waitForIdle()

    assertTrue("Import action should be wired", imported.get())
    assertTrue("Enable switch should update the selected addon", enabled.get())
    assertTrue("Export action should be wired", exported.get())
    assertTrue("Remove confirmation should be wired", removed.get())
    assertTrue("Back action should be wired", returned.get())
  }

  @Test
  fun addonWithMissingDependencyIsBlockedAndCannotBeEnabled() {
    val addon =
      AddonLibraryItem(
        title = "Blocked Smoke Addon",
        folder = "BlockedSmoke",
        version = "1.0",
        interfaceVersion = "30300",
        notes = "",
        dependencies = listOf("MissingDependency"),
        sizeBytes = 2_048L,
        filesCount = 3,
        enabled = false,
        importedAt = 1L,
        warnings = emptyList(),
      )

    composeRule.setContent {
      MaterialTheme(
        colorScheme = DesignTokens.colorScheme(),
        typography = DesignTokens.typography(),
        shapes = DesignTokens.shapes,
      ) {
        AddonManagerScreen(
          snapshot = AddonLibrarySnapshot(revision = "blocked-ui-smoke", addons = listOf(addon)),
          busy = false,
          message = null,
          onImport = {},
          onToggle = { _, _ -> error("A blocked addon must not be enabled") },
          onExport = {},
          onRemove = {},
          onBack = {},
        )
      }
    }

    composeRule.onNodeWithText("Blocked").assertIsDisplayed()
    composeRule.onNode(isToggleable()).assertIsNotEnabled()
  }
}
