package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLayoutPolicyTest {
  @Test
  fun homeSwitchesToTwoColumnLayoutAtWideWindowBreakpoint() {
    assertFalse(LauncherLayoutPolicy.usesWideHome(699))
    assertTrue(LauncherLayoutPolicy.usesWideHome(700))
    assertTrue(LauncherLayoutPolicy.usesWideHome(1024))
  }

  @Test
  fun settingsContentWidthIsCappedOnLargeWindows() {
    assertEquals(680, LauncherLayoutPolicy.settingsContentMaxWidthDp(699))
    assertEquals(760, LauncherLayoutPolicy.settingsContentMaxWidthDp(900))
    assertEquals(760, LauncherLayoutPolicy.settingsContentMaxWidthDp(1200))
  }

  @Test
  fun pagePaddingTightensForNarrowWindows() {
    assertEquals(16, LauncherLayoutPolicy.pageHorizontalPaddingDp(360))
    assertEquals(24, LauncherLayoutPolicy.pageHorizontalPaddingDp(420))
  }

  @Test
  fun narrowLandscapeWindowsUseTheCompactLayout() {
    // Split-screen and resized landscape windows: narrow width, short height.
    assertFalse(LauncherLayoutPolicy.usesWideHome(640))
    assertEquals(24, LauncherLayoutPolicy.pageHorizontalPaddingDp(640))
    assertEquals(680, LauncherLayoutPolicy.settingsContentMaxWidthDp(640))
  }

  @Test
  fun wideLandscapeWindowsUseTheRoomyLayout() {
    assertTrue(LauncherLayoutPolicy.usesWideHome(1080))
    assertTrue(LauncherLayoutPolicy.usesWideHome(2400))
    assertEquals(24, LauncherLayoutPolicy.pageHorizontalPaddingDp(1080))
    assertEquals(760, LauncherLayoutPolicy.settingsContentMaxWidthDp(2400))
  }

  @Test
  fun dynamicResizingMovesAcrossBreakpointsWithoutHysteresis() {
    var widthDp = 640
    assertFalse(LauncherLayoutPolicy.usesWideHome(widthDp))
    widthDp = 900
    assertTrue(LauncherLayoutPolicy.usesWideHome(widthDp))
    widthDp = 410
    assertFalse(LauncherLayoutPolicy.usesWideHome(widthDp))
    assertEquals(16, LauncherLayoutPolicy.pageHorizontalPaddingDp(widthDp))
    widthDp = 700
    assertTrue(LauncherLayoutPolicy.usesWideHome(widthDp))
    assertEquals(24, LauncherLayoutPolicy.pageHorizontalPaddingDp(widthDp))
  }
}
