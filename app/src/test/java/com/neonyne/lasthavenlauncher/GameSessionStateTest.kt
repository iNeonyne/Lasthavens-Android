package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionStateTest {
  private fun state(
    hasSession: Boolean = false,
    sessionOpen: Boolean = false,
    isLoading: Boolean = false,
    pageLoadFailed: Boolean = false,
    sessionCrashed: Boolean = false,
    hasLoadedGame: Boolean = false,
    hasCompletedLoad: Boolean = false,
  ): GameSessionUiState =
    GameSessionUiState(
      hasSession = hasSession,
      sessionOpen = sessionOpen,
      isLoading = isLoading,
      pageLoadFailed = pageLoadFailed,
      sessionCrashed = sessionCrashed,
      hasLoadedGame = hasLoadedGame,
      hasCompletedLoad = hasCompletedLoad,
    )

  @Test
  fun `decideStart creates a session when none exists`() {
    assertEquals(
      GameSessionStartDecision.CreateNewSession,
      GameSessionActions.decideStart(state()),
    )
  }

  @Test
  fun `decideStart reopens a closed session and loads the URL only if never loaded`() {
    assertEquals(
      GameSessionStartDecision.ReopenExisting(loadUri = true),
      GameSessionActions.decideStart(state(hasSession = true, hasLoadedGame = false)),
    )
    assertEquals(
      GameSessionStartDecision.ReopenExisting(loadUri = false),
      GameSessionActions.decideStart(state(hasSession = true, hasLoadedGame = true)),
    )
  }

  @Test
  fun `decideStart keeps a live open session`() {
    assertEquals(
      GameSessionStartDecision.KeepExisting,
      GameSessionActions.decideStart(state(hasSession = true, sessionOpen = true)),
    )
  }

  @Test
  fun `reload navigates in the open session from idle loading and failed states`() {
    val idle = state(hasSession = true, sessionOpen = true, hasCompletedLoad = true)
    val firstLoad = idle.copy(isLoading = true, hasCompletedLoad = false)
    val failed = firstLoad.copy(isLoading = false, pageLoadFailed = true)
    for (state in listOf(idle, firstLoad, failed)) {
      assertEquals(GameSessionReloadDecision.ReloadInPlace, GameSessionActions.decideReload(state))
    }
  }

  @Test
  fun `decideReload reopens and loads a closed session`() {
    assertEquals(
      GameSessionReloadDecision.ReopenAndLoad,
      GameSessionActions.decideReload(state(hasSession = true, hasLoadedGame = true)),
    )
  }

  @Test
  fun `reload creates a session when missing and replaces a crashed session`() {
    for (state in listOf(state(), state(hasSession = true, sessionCrashed = true))) {
      assertEquals(
        GameSessionReloadDecision.CreateNewSession,
        GameSessionActions.decideReload(state),
      )
    }
  }

  @Test
  fun `reload must start the selected URL before successful completion`() {
    val attempt = GameReloadAttempt(ALTERNATE_GAME_URL)
    attempt.onPageStart(2, ALTERNATE_GAME_URL) // A previous load during bridge preparation.
    assertTrue(attempt.requestNavigation(2))
    assertNull(attempt.onPageStop(2, success = true))
    attempt.onPageStart(2, DEFAULT_GAME_URL)
    assertNull(attempt.onPageStop(2, success = true))
    attempt.onPageStart(2, ALTERNATE_GAME_URL)
    assertEquals(true, attempt.onPageStop(2, success = true))
  }

  @Test
  fun `duplicate preparation completion cannot submit a second reload`() {
    val attempt = GameReloadAttempt(DEFAULT_GAME_URL)
    assertTrue(attempt.requestNavigation(2))
    assertFalse(attempt.requestNavigation(2))
    attempt.onPageStart(2, DEFAULT_GAME_URL)
    assertEquals(false, attempt.onPageStop(2, success = false))
    assertNull(attempt.onPageStop(2, success = true))
  }

  @Test
  fun `replaced session callbacks cannot start finish or fail the current reload`() {
    val attempt = GameReloadAttempt(DEFAULT_GAME_URL)
    assertTrue(attempt.requestNavigation(2))
    attempt.onPageStart(1, DEFAULT_GAME_URL)
    assertNull(attempt.onPageStop(2, success = true))
    attempt.onPageStart(2, DEFAULT_GAME_URL)
    assertNull(attempt.onPageStop(1, success = false))
    assertNull(attempt.onPageStop(1, success = true))
    assertEquals(true, attempt.onPageStop(2, success = true))
  }

  @Test
  fun `explicit reload shows loading even after a previous failure or successful load`() {
    assertEquals(
      GameLoadPresentation.LoadingGame,
      state(hasSession = true, pageLoadFailed = true).copy(reloadInProgress = true).presentation,
    )
    assertEquals(
      GameLoadPresentation.LoadingGame,
      state(hasSession = true, hasCompletedLoad = true).copy(reloadInProgress = true).presentation,
    )
  }

  @Test
  fun `decideGameUrlChange navigates an open session in place`() {
    assertEquals(
      GameSessionUrlChangeDecision.LoadInPlace,
      GameSessionActions.decideGameUrlChange(state(hasSession = true, sessionOpen = true)),
    )
  }

  @Test
  fun `decideGameUrlChange reopens a closed session`() {
    assertEquals(
      GameSessionUrlChangeDecision.ReopenAndLoad,
      GameSessionActions.decideGameUrlChange(state(hasSession = true, sessionOpen = false)),
    )
  }

  @Test
  fun `decideGameUrlChange does nothing when no session exists`() {
    assertEquals(GameSessionUrlChangeDecision.Noop, GameSessionActions.decideGameUrlChange(state()))
  }

  @Test
  fun `onPageStart marks loading and supersedes prior failures`() {
    val next =
      GameSessionActions.onPageStart(
        state(pageLoadFailed = true, sessionCrashed = false, hasCompletedLoad = true),
      )
    assertTrue(next.isLoading)
    assertFalse(next.pageLoadFailed)
    assertFalse(next.sessionCrashed)
    assertTrue(next.hasCompletedLoad)
  }

  @Test
  fun `onPageStop success completes the load`() {
    val next =
      GameSessionActions.onPageStop(state(isLoading = true, pageLoadFailed = true), success = true)
    assertFalse(next.isLoading)
    assertFalse(next.pageLoadFailed)
    assertFalse(next.sessionCrashed)
    assertTrue(next.hasCompletedLoad)
  }

  @Test
  fun `onPageStop failure keeps a prior process failure and does not complete the load`() {
    val next =
      GameSessionActions.onPageStop(state(isLoading = true, sessionCrashed = true), success = false)
    assertFalse(next.isLoading)
    assertTrue(next.pageLoadFailed)
    assertTrue(next.sessionCrashed)
    assertFalse(next.hasCompletedLoad)
  }

  @Test
  fun `onProcessFailure supersedes loading and navigation failure`() {
    val next = GameSessionActions.onProcessFailure(state(isLoading = true, pageLoadFailed = true))
    assertFalse(next.isLoading)
    assertFalse(next.pageLoadFailed)
    assertTrue(next.sessionCrashed)
  }

  @Test
  fun `onLoadRequested records the first game load`() {
    assertTrue(GameSessionActions.onLoadRequested(state()).hasLoadedGame)
  }

  @Test
  fun `onReloadCleared clears load failure flags`() {
    val next =
      GameSessionActions.onReloadCleared(state(pageLoadFailed = true, sessionCrashed = true))
    assertFalse(next.pageLoadFailed)
    assertFalse(next.sessionCrashed)
  }

  @Test
  fun `onSessionReset drops all load and crash progress`() {
    val next =
      GameSessionActions.onSessionReset(
        state(
          isLoading = true,
          pageLoadFailed = true,
          sessionCrashed = true,
          hasLoadedGame = true,
          hasCompletedLoad = true,
        ),
      )
    assertFalse(next.isLoading)
    assertFalse(next.pageLoadFailed)
    assertFalse(next.sessionCrashed)
    assertFalse(next.hasLoadedGame)
    assertFalse(next.hasCompletedLoad)
  }

  @Test
  fun `presentation follows the GameLoadPolicy classification`() {
    assertEquals(GameLoadPresentation.Preparing, state().presentation)
    assertEquals(
      GameLoadPresentation.LoadingGame,
      state(hasSession = true, sessionOpen = true, isLoading = true).presentation,
    )
    assertEquals(
      GameLoadPresentation.RecoverableFailure,
      state(hasSession = true, sessionOpen = true, pageLoadFailed = true).presentation,
    )
    assertEquals(
      GameLoadPresentation.RendererFailure,
      state(hasSession = true, sessionOpen = true, sessionCrashed = true).presentation,
    )
    assertEquals(
      GameLoadPresentation.Game,
      state(hasSession = true, sessionOpen = true, hasCompletedLoad = true).presentation,
    )
  }

  @Test
  fun `process failure wins over navigation failure in presentation`() {
    assertEquals(
      GameLoadPresentation.RendererFailure,
      state(hasSession = true, sessionOpen = true, pageLoadFailed = true, sessionCrashed = true)
        .presentation,
    )
  }
}
