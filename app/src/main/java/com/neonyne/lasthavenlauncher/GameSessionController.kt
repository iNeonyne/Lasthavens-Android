package com.neonyne.lasthavenlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

private const val TAG = "LastHavenLauncher"
private const val RELOAD_TIMEOUT_MS = 30_000L

/**
 * Owns the GeckoView game session lifecycle: lazy runtime acquisition, session creation/open,
 * reload and crash restore, plus the load/crash state exposed to Compose as [uiState].
 *
 * Session ownership and close conditions: the controller never closes a session itself. The
 * composition root's keep-alive effect closes a session only when the controller replaces it (crash
 * restore) or when the owning composition leaves. Navigating to launcher destinations detaches the
 * view but must never close the live session.
 *
 * The runtime stays lazy, main-thread and process-scoped via [GeckoRuntimeProvider]; it is not
 * created at Application startup.
 */
@MainThread
class GameSessionController {
  var session: GeckoSession? by mutableStateOf(null)
    private set

  private var runtime: GeckoRuntime? = null
  private val callbackGate = GameSessionCallbackGate()
  private val handler = Handler(Looper.getMainLooper())
  private var reloadAttempt by mutableStateOf<GameReloadAttempt?>(null)
  private var reloadTimeout: Runnable? = null
  private var onReloadSuccess: (() -> Unit)? = null
  private var isLoading by mutableStateOf(false)
  private var pageLoadFailed by mutableStateOf(false)
  private var sessionCrashed by mutableStateOf(false)
  private var hasLoadedGame by mutableStateOf(false)
  private var hasCompletedLoad by mutableStateOf(false)

  val uiState: GameSessionUiState
    get() = currentState()

  fun startGame(
    context: Context,
    gameUrl: String,
    onSessionReady: (GeckoSession) -> Unit = {},
  ) {
    if (reloadAttempt != null) return
    when (val decision = GameSessionActions.decideStart(uiState)) {
      GameSessionStartDecision.CreateNewSession -> {
        val gameSession = createSession(context)
        onSessionReady(gameSession)
        gameSession.loadUri(gameUrl)
        transition(GameSessionActions::onLoadRequested)
      }
      is GameSessionStartDecision.ReopenExisting -> {
        val gameSession = session ?: return
        gameSession.open(obtainRuntime(context))
        onSessionReady(gameSession)
        if (decision.loadUri) {
          gameSession.loadUri(gameUrl)
          transition(GameSessionActions::onLoadRequested)
        }
      }
      GameSessionStartDecision.KeepExisting -> Unit
    }
  }

  /** Coalesce taps from the start of bridge preparation through navigation completion. */
  internal fun reloadGame(
    context: Context,
    gameUrl: String,
    prepareBridge: ((Boolean) -> Unit) -> Unit,
    onSessionReady: (GeckoSession) -> Unit,
    onSuccess: (Boolean) -> Unit,
  ) {
    if (reloadAttempt != null) return
    val attempt = GameReloadAttempt(gameUrl)
    reloadAttempt = attempt

    scheduleReloadTimeout {
      Diagnostics.warn(context, TAG, "GeckoView reload bridge preparation timed out")
      navigateReload(context, attempt, onSessionReady) { onSuccess(false) }
    }
    prepareBridge { refreshed ->
      navigateReload(context, attempt, onSessionReady) { onSuccess(refreshed) }
    }
  }

  private fun scheduleReloadTimeout(action: () -> Unit) {
    reloadTimeout?.let(handler::removeCallbacks)
    val timeout = Runnable { action() }
    reloadTimeout = timeout
    handler.postDelayed(timeout, RELOAD_TIMEOUT_MS)
  }

  private fun navigateReload(
    context: Context,
    attempt: GameReloadAttempt,
    onSessionReady: (GeckoSession) -> Unit,
    onSuccess: () -> Unit,
  ) {
    // Bridge installation and snapshot work can outlive a timeout, URL change or disposal.
    if (reloadAttempt !== attempt || attempt.navigationRequested) return
    try {
      val decision = GameSessionActions.decideReload(uiState)
      val gameSession =
        when (decision) {
          GameSessionReloadDecision.ReloadInPlace -> checkNotNull(session)
          GameSessionReloadDecision.ReopenAndLoad ->
            checkNotNull(session).also { it.open(obtainRuntime(context)) }
          GameSessionReloadDecision.CreateNewSession -> createSession(context)
        }
      onSessionReady(gameSession)
      onReloadSuccess = onSuccess
      attempt.requestNavigation(callbackGate.generation)
      scheduleReloadTimeout { failReload(context, "navigation timed out") }
      // Stop any earlier document load before queuing an explicit navigation in the same window.
      if (decision == GameSessionReloadDecision.ReloadInPlace) gameSession.stop()
      gameSession.load(
        GeckoSession.Loader()
          .uri(attempt.url)
          .flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE or GeckoSession.LOAD_FLAGS_REPLACE_HISTORY),
      )
      transition(GameSessionActions::onLoadRequested)
      Diagnostics.info(context, TAG, "GeckoView reload requested")
    } catch (error: RuntimeException) {
      failReload(context, "could not request navigation (${error.javaClass.simpleName})")
    }
  }

  internal fun cancelReload() {
    reloadTimeout?.let(handler::removeCallbacks)
    reloadTimeout = null
    onReloadSuccess = null
    reloadAttempt = null
  }

  private fun failReload(context: Context, reason: String) {
    val navigationRequested = reloadAttempt?.navigationRequested == true
    cancelReload()
    if (navigationRequested) session?.takeIf { it.isOpen() }?.stop()
    transition { GameSessionActions.onPageStop(it, success = false) }
    Diagnostics.warn(context, TAG, "GeckoView reload failed: $reason")
  }

  /** Navigate the existing GeckoSession to a newly selected game server without replacing it. */
  fun loadGameUrl(context: Context, gameUrl: String) {
    // Restore will use the new selection when it replaces the crashed session.
    if (sessionCrashed) return
    val gameSession = session ?: return
    when (GameSessionActions.decideGameUrlChange(uiState)) {
      GameSessionUrlChangeDecision.LoadInPlace -> {
        transition(GameSessionActions::onReloadCleared)
        gameSession.loadUri(gameUrl)
        transition(GameSessionActions::onLoadRequested)
      }
      GameSessionUrlChangeDecision.ReopenAndLoad -> {
        gameSession.open(obtainRuntime(context))
        transition(GameSessionActions::onReloadCleared)
        gameSession.loadUri(gameUrl)
        transition(GameSessionActions::onLoadRequested)
      }
      GameSessionUrlChangeDecision.Noop -> Unit
    }
  }

  private fun createSession(context: Context): GeckoSession {
    val gameRuntime = obtainRuntime(context)
    val gameSession = GeckoSession()
    val callbackGeneration = callbackGate.advance()
    setupGeckoDelegates(gameSession, context, callbackGeneration)
    gameSession.open(gameRuntime)
    session = gameSession
    transition(GameSessionActions::onSessionReset)
    return gameSession
  }

  private fun obtainRuntime(context: Context): GeckoRuntime =
    runtime ?: GeckoRuntimeProvider.get(context).also { runtime = it }

  private fun currentState(): GameSessionUiState =
    GameSessionUiState(
      hasSession = session != null,
      sessionOpen = session?.isOpen() == true,
      isLoading = isLoading,
      pageLoadFailed = pageLoadFailed,
      sessionCrashed = sessionCrashed,
      hasLoadedGame = hasLoadedGame,
      hasCompletedLoad = hasCompletedLoad,
      reloadInProgress = reloadAttempt != null,
    )

  private fun transition(transform: (GameSessionUiState) -> GameSessionUiState) {
    val next = transform(currentState())
    isLoading = next.isLoading
    pageLoadFailed = next.pageLoadFailed
    sessionCrashed = next.sessionCrashed
    hasLoadedGame = next.hasLoadedGame
    hasCompletedLoad = next.hasCompletedLoad
  }

  private fun handleProcessFailure(context: Context, message: String) {
    if (reloadAttempt?.navigationRequested == true) {
      cancelReload()
      Diagnostics.warn(context, TAG, "GeckoView reload interrupted by content process failure")
    }
    transition(GameSessionActions::onProcessFailure)
    Diagnostics.error(context, TAG, message)
  }

  private fun setupGeckoDelegates(
    geckoSession: GeckoSession,
    context: Context,
    callbackGeneration: Int,
  ) {
    fun isStaleSessionCallback(): Boolean = !callbackGate.accepts(callbackGeneration)

    geckoSession.setProgressDelegate(
      object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
          if (isStaleSessionCallback() || sessionCrashed || url == "about:blank") return
          reloadAttempt?.onPageStart(callbackGeneration, url)
          transition(GameSessionActions::onPageStart)
          Diagnostics.info(context, TAG, "GeckoView page started")
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
          if (isStaleSessionCallback() || sessionCrashed) return
          val attempt = reloadAttempt
          if (attempt?.navigationRequested == true) {
            val reloadSucceeded = attempt.onPageStop(callbackGeneration, success) ?: return
            val completed = onReloadSuccess
            cancelReload()
            transition { GameSessionActions.onPageStop(it, reloadSucceeded) }
            if (reloadSucceeded) {
              Diagnostics.info(context, TAG, "GeckoView reload completed")
              completed?.invoke()
            } else {
              Diagnostics.warn(context, TAG, "GeckoView reload failed: page load unsuccessful")
            }
            return
          }
          if (!isLoading) return
          transition { GameSessionActions.onPageStop(it, success) }
          if (!success) {
            Diagnostics.warn(context, TAG, "GeckoView page load failed")
          }
        }
      },
    )
    geckoSession.setContentDelegate(
      object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) {
          if (isStaleSessionCallback()) return
          handleProcessFailure(context, "GeckoView content process crashed")
        }

        override fun onKill(session: GeckoSession) {
          if (isStaleSessionCallback()) return
          handleProcessFailure(context, "GeckoView content process was killed")
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
          Diagnostics.info(context, TAG, "GeckoView fullscreen changed: $fullScreen")
        }

        override fun onExternalResponse(
          session: GeckoSession,
          response: org.mozilla.geckoview.WebResponse,
        ) {
          Diagnostics.warn(context, TAG, "GeckoView external response not handled")
        }
      },
    )
    geckoSession.setNavigationDelegate(
      object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
          session: GeckoSession,
          request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<org.mozilla.geckoview.AllowOrDeny> =
          if (GameNavigationPolicy.allows(request.uri)) {
            GeckoResult.allow()
          } else {
            GeckoResult.deny()
          }
      },
    )
    geckoSession.setPermissionDelegate(
      object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
          session: GeckoSession,
          permission: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
          val host = android.net.Uri.parse(permission.uri).host.orEmpty().lowercase(Locale.ROOT)
          val isGameOrigin = host == "lasthavens.com" || host.endsWith(".lasthavens.com")
          val decision =
            when (permission.permission) {
              GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
              GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
              GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                if (isGameOrigin) {
                  GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                  GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
              else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            }
          return GeckoResult.fromValue(decision)
        }

        override fun onAndroidPermissionsRequest(
          session: GeckoSession,
          permissions: Array<out String>?,
          callback: GeckoSession.PermissionDelegate.Callback,
        ) {
          callback.reject()
        }

        override fun onMediaPermissionRequest(
          session: GeckoSession,
          uri: String,
          video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
          audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
          callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
          callback.reject()
        }
      },
    )
  }
}
