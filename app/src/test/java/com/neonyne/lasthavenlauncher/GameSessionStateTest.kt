package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun `decideReload reloads an open session in place`() {
    assertEquals(
      GameSessionReloadDecision.ReloadInPlace,
      GameSessionActions.decideReload(state(hasSession = true, sessionOpen = true)),
    )
  }

  @Test
  fun `decideReload reopens and loads a closed session`() {
    assertEquals(
      GameSessionReloadDecision.ReopenAndLoad,
      GameSessionActions.decideReload(state(hasSession = true, sessionOpen = false)),
    )
  }

  @Test
  fun `decideReload does nothing without a session`() {
    assertEquals(GameSessionReloadDecision.Noop, GameSessionActions.decideReload(state()))
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
  fun `shouldRestore recreates only an existing session`() {
    assertTrue(GameSessionActions.shouldRestore(state(hasSession = true, sessionCrashed = true)))
    assertFalse(GameSessionActions.shouldRestore(state()))
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
  fun `onSessionCreated clears the navigation failure flag only`() {
    val next =
      GameSessionActions.onSessionCreated(state(pageLoadFailed = true, hasLoadedGame = true))
    assertFalse(next.pageLoadFailed)
    assertTrue(next.hasLoadedGame)
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
