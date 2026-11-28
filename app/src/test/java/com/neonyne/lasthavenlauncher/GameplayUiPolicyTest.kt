package com.neonyne.lasthavenlauncher

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayUiPolicyTest {
  @Test
  fun everySurfaceRequestsLandscapeOrientation() {
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
      GameplayUiPolicy.orientation(GameplayUiState.Chrome),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
      GameplayUiPolicy.orientation(GameplayUiState.NonImmersiveGameplay),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
      GameplayUiPolicy.orientation(GameplayUiState.ImmersiveGameplay),
    )
  }

  @Test
  fun keepAwakeAppliesToGameplaySurfacesOnly() {
    assertTrue(GameplayUiPolicy.keepScreenOn(GameplayUiState.NonImmersiveGameplay))
    assertTrue(GameplayUiPolicy.keepScreenOn(GameplayUiState.ImmersiveGameplay))
    assertFalse(GameplayUiPolicy.keepScreenOn(GameplayUiState.Chrome))
  }

  @Test
  fun chromeSurfacesFitSystemWindowsWhileGameplayDoesNot() {
    assertTrue(GameplayUiPolicy.decorFitsSystemWindows(GameplayUiState.Chrome))
    assertFalse(GameplayUiPolicy.decorFitsSystemWindows(GameplayUiState.NonImmersiveGameplay))
    assertFalse(GameplayUiPolicy.decorFitsSystemWindows(GameplayUiState.ImmersiveGameplay))
  }

  @Test
  fun immersiveGameplayHidesSystemBarsAndChromeShowsThem() {
    assertFalse(GameplayUiPolicy.systemBarsVisible(GameplayUiState.ImmersiveGameplay))
    assertTrue(GameplayUiPolicy.systemBarsVisible(GameplayUiState.NonImmersiveGameplay))
    assertTrue(GameplayUiPolicy.systemBarsVisible(GameplayUiState.Chrome))
    assertFalse(GameplayUiPolicy.lightSystemBars(GameplayUiState.Chrome))
    assertFalse(GameplayUiPolicy.lightSystemBars(GameplayUiState.NonImmersiveGameplay))
  }

  @Test
  fun navigationMappingCoversEverySurfaceCombination() {
    assertEquals(GameplayUiState.Chrome, GameplayUiPolicy.fromNavigation(false, false))
    assertEquals(GameplayUiState.Chrome, GameplayUiPolicy.fromNavigation(false, true))
    assertEquals(
      GameplayUiState.NonImmersiveGameplay,
      GameplayUiPolicy.fromNavigation(true, false),
    )
    assertEquals(
      GameplayUiState.ImmersiveGameplay,
      GameplayUiPolicy.fromNavigation(true, true),
    )
  }

  @Test
  fun focusChangesReapplyImmersiveStateOnlyWhenFocused() {
    assertEquals(
      GameplayUiState.ImmersiveGameplay,
      GameplayUiPolicy.onWindowFocus(GameplayUiState.ImmersiveGameplay, true),
    )
    assertNull(GameplayUiPolicy.onWindowFocus(GameplayUiState.ImmersiveGameplay, false))
    assertNull(GameplayUiPolicy.onWindowFocus(null, true))
  }

  @Test
  fun earlyExitDisposalRestoresChromeState() {
    val restored = GameplayUiPolicy.fromNavigation(false, false)
    assertEquals(GameplayUiState.Chrome, restored)
    assertFalse(GameplayUiPolicy.keepScreenOn(restored))
    assertTrue(GameplayUiPolicy.systemBarsVisible(restored))
    assertTrue(GameplayUiPolicy.decorFitsSystemWindows(restored))
  }

  @Test
  fun chromeBarsUseTheSharedDarkColor() {
    assertEquals((0xFF080D18).toInt(), GameplayUiPolicy.systemBarColorArgb)
  }
}
