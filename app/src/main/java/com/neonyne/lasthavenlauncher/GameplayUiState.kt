package com.neonyne.lasthavenlauncher

import android.content.pm.ActivityInfo

/**
 * Immutable snapshot of the app-window presentation (issue #148). The entire app is landscape;
 * immersive system-bar hiding, transient-bar behavior and keep-awake apply to gameplay surfaces
 * only. One Activity/window boundary derives every window effect from this state via
 * [GameplayUiPolicy], replacing the previous scattered orientation and inset side effects.
 */
data class GameplayUiState(
  val gameplay: Boolean,
  val immersive: Boolean,
) {
  companion object {
    /** Non-gameplay surfaces: Home, Settings, Diagnostics, Update and the game menu overlay. */
    val Chrome = GameplayUiState(gameplay = false, immersive = false)

    /** Gameplay surfaces with visible system bars (for example the Addon Manager). */
    val NonImmersiveGameplay = GameplayUiState(gameplay = true, immersive = false)

    /** Fullscreen gameplay without the menu overlay. */
    val ImmersiveGameplay = GameplayUiState(gameplay = true, immersive = true)
  }
}

/**
 * Pure presentation policy for [GameplayUiState]. Landscape orientation is constant across every
 * app surface; keep-awake and immersive behavior are gameplay-only. All values are derived without
 * touching a Window so the policy stays unit-testable.
 */
object GameplayUiPolicy {
  /** Chrome bar color shared by the status and navigation bars when they are visible. */
  val systemBarColorArgb: Int = 0xFF080D18.toInt()

  fun orientation(state: GameplayUiState): Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

  fun keepScreenOn(state: GameplayUiState): Boolean = state.gameplay

  fun decorFitsSystemWindows(state: GameplayUiState): Boolean = !state.gameplay

  fun systemBarsVisible(state: GameplayUiState): Boolean = !state.immersive

  fun lightSystemBars(state: GameplayUiState): Boolean = false

  /**
   * Returns the state to re-apply when window focus changes: immersive gameplay must re-hide the
   * system bars after focus returns (transient bars, dialogs, or other apps), and nothing is
   * reapplied while the window is unfocused.
   */
  fun onWindowFocus(state: GameplayUiState?, hasFocus: Boolean): GameplayUiState? =
    if (hasFocus && state != null) state else null

  /** Maps the navigation-derived gameplay and immersive flags onto an immutable state. */
  fun fromNavigation(gameplay: Boolean, immersive: Boolean): GameplayUiState =
    when {
      !gameplay -> GameplayUiState.Chrome
      immersive -> GameplayUiState.ImmersiveGameplay
      else -> GameplayUiState.NonImmersiveGameplay
    }
}
