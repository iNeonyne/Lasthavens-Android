package com.neonyne.lasthavenlauncher

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke-tests the focused settings navigation and the requested metrics presentation. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationSmokeTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun settingsUsesSeparateGameNetworkAndApplicationDestinations() {
    val backPressed = AtomicBoolean(false)
    composeRule.setContent {
      val context = LocalContext.current
      val usageMeter = remember(context) { AppUsageMeter(context) }
      MaterialTheme(
        colorScheme = DesignTokens.colorScheme(),
        typography = DesignTokens.typography(),
        shapes = DesignTokens.shapes,
      ) {
        SettingsScreen(
          usageMeter = usageMeter,
          isCheckingUpdate = false,
          gameUrl = DEFAULT_GAME_URL,
          onGameUrlChange = {},
          onBack = { backPressed.set(true) },
          onOpenDiagnostics = {},
          onOpenUpdates = {},
          onCheckUpdates = {},
        )
      }
    }

    composeRule.onAllNodesWithText("Game").onFirst().assertIsDisplayed()
    composeRule.onNodeWithText("Game server").assertIsDisplayed()

    composeRule.onAllNodesWithText("Network & storage").onFirst().performClick()
    composeRule.onNodeWithText("Game data size").assertIsDisplayed()
    composeRule.onNodeWithText("This app session").assertIsDisplayed()
    composeRule.onNodeWithText("Lifetime total (tracked)").assertIsDisplayed()
    composeRule
      .onNodeWithText("Usage updates automatically while this page is open.")
      .assertIsDisplayed()

    // The network page deliberately does not expose APK/cache/since-boot categories.
    composeRule.onNodeWithText("Installed APK").assertDoesNotExist()
    composeRule.onNodeWithText("Received since device boot").assertDoesNotExist()
    composeRule.onAllNodesWithText("Application").onFirst().performClick()
    composeRule.onNodeWithText("Check for updates").assertIsDisplayed()
    composeRule.onNodeWithText("Back").performClick()
    composeRule.waitForIdle()
    assertTrue("Back should return through the Settings owner callback", backPressed.get())
  }
}
