package com.neonyne.lasthavenlauncher

/**
 * Rejects stale GeckoView callbacks from a replaced game session (issue #218). The controller
 * advances the generation every time a new session is created; delegates capture the generation
 * active at attach time and each callback is accepted only while that generation is still current.
 */
class GameSessionCallbackGate(initialGeneration: Int = 0) {
  private var currentGeneration: Int = initialGeneration

  val generation: Int
    get() = currentGeneration

  /** Registers a replacement session and returns its callback generation. */
  fun advance(): Int = ++currentGeneration

  /** True only for callbacks that belong to the current session generation. */
  fun accepts(callbackGeneration: Int): Boolean = callbackGeneration == currentGeneration
}

/**
 * Immutable snapshot of the game-session state rendered by the composition root.
 * [hasSession]/[sessionOpen] mirror the live GeckoSession; the remaining fields mirror the
 * GeckoView load/process signals observed so far.
 */
data class GameSessionUiState(
  val hasSession: Boolean,
  val sessionOpen: Boolean,
  val isLoading: Boolean,
  val pageLoadFailed: Boolean,
  val sessionCrashed: Boolean,
  val hasLoadedGame: Boolean,
  val hasCompletedLoad: Boolean,
) {
  /** The single launcher-owned presentation shown on the game screen. */
  val presentation: GameLoadPresentation
    get() =
      GameLoadPolicy.resolve(
        hasSession = hasSession,
        isLoading = isLoading,
        hasCompletedLoad = hasCompletedLoad,
        failure = GameLoadPolicy.failure(sessionCrashed, pageLoadFailed),
      )
}

/** What startGame must do with the current session. */
sealed interface GameSessionStartDecision {
  /** No session exists yet; create it, open it, attach delegates and load the game URL. */
  data object CreateNewSession : GameSessionStartDecision

  /** A session exists but is closed; re-open it, loading the game URL only if never loaded. */
  data class ReopenExisting(val loadUri: Boolean) : GameSessionStartDecision

  /** A live open session already exists; only navigation must change. */
  data object KeepExisting : GameSessionStartDecision
}

/** What reload must do with the current session. */
sealed interface GameSessionReloadDecision {
  /** The session is open; reload it in place. */
  data object ReloadInPlace : GameSessionReloadDecision

  /** The session exists but is closed; re-open it and load the game URL again. */
  data object ReopenAndLoad : GameSessionReloadDecision

  /** No session exists; reload must do nothing. */
  data object Noop : GameSessionReloadDecision
}

/** What changing the configured game URL must do with the current session. */
sealed interface GameSessionUrlChangeDecision {
  /** Navigate the existing open session to the selected URL. */
  data object LoadInPlace : GameSessionUrlChangeDecision

  /** Re-open the existing closed session and navigate to the selected URL. */
  data object ReopenAndLoad : GameSessionUrlChangeDecision

  /** No session exists; the saved preference will be used when Play creates one. */
  data object Noop : GameSessionUrlChangeDecision
}

/**
 * Pure decision and transition rules for the game session lifecycle. The controller applies these
 * rules; they carry no Android or GeckoView references so they can be tested on the JVM.
 */
object GameSessionActions {
  fun decideStart(state: GameSessionUiState): GameSessionStartDecision =
    when {
      !state.hasSession -> GameSessionStartDecision.CreateNewSession
      !state.sessionOpen -> GameSessionStartDecision.ReopenExisting(loadUri = !state.hasLoadedGame)
      else -> GameSessionStartDecision.KeepExisting
    }

  fun decideReload(state: GameSessionUiState): GameSessionReloadDecision =
    when {
      !state.hasSession -> GameSessionReloadDecision.Noop
      state.sessionOpen -> GameSessionReloadDecision.ReloadInPlace
      else -> GameSessionReloadDecision.ReopenAndLoad
    }

  fun decideGameUrlChange(state: GameSessionUiState): GameSessionUrlChangeDecision =
    when {
      !state.hasSession -> GameSessionUrlChangeDecision.Noop
      state.sessionOpen -> GameSessionUrlChangeDecision.LoadInPlace
      else -> GameSessionUrlChangeDecision.ReopenAndLoad
    }

  /** Only an existing (possibly crashed) session is restored by recreating it. */
  fun shouldRestore(state: GameSessionUiState): Boolean = state.hasSession

  /** A page load started; any prior failure signal is superseded. */
  fun onPageStart(state: GameSessionUiState): GameSessionUiState =
    state.copy(isLoading = true, pageLoadFailed = false, sessionCrashed = false)

  fun onPageStop(state: GameSessionUiState, success: Boolean): GameSessionUiState =
    if (success) {
      state.copy(
        isLoading = false,
        pageLoadFailed = false,
        sessionCrashed = false,
        hasCompletedLoad = true,
      )
    } else {
      state.copy(isLoading = false, pageLoadFailed = true)
    }

  /**
   * The GeckoView content process crashed or was killed. A process failure supersedes any in-flight
   * page load so the renderer-failure presentation wins over loading.
   */
  fun onProcessFailure(state: GameSessionUiState): GameSessionUiState =
    state.copy(isLoading = false, pageLoadFailed = false, sessionCrashed = true)

  fun onSessionCreated(state: GameSessionUiState): GameSessionUiState =
    state.copy(pageLoadFailed = false)

  fun onLoadRequested(state: GameSessionUiState): GameSessionUiState =
    state.copy(hasLoadedGame = true)

  fun onReloadCleared(state: GameSessionUiState): GameSessionUiState =
    state.copy(pageLoadFailed = false, sessionCrashed = false)

  /** Drops all load/crash progress before a crash-restore recreate. */
  fun onSessionReset(state: GameSessionUiState): GameSessionUiState =
    state.copy(
      isLoading = false,
      pageLoadFailed = false,
      sessionCrashed = false,
      hasLoadedGame = false,
      hasCompletedLoad = false,
    )
}
