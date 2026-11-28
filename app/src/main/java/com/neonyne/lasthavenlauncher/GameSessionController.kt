package com.neonyne.lasthavenlauncher

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

private const val TAG = "LastHavenLauncher"

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
  private var isLoading by mutableStateOf(false)
  private var pageLoadFailed by mutableStateOf(false)
  private var sessionCrashed by mutableStateOf(false)
  private var hasLoadedGame by mutableStateOf(false)
  private var hasCompletedLoad by mutableStateOf(false)

  val uiState: GameSessionUiState
    get() =
      GameSessionUiState(
        hasSession = session != null,
        sessionOpen = session?.isOpen() == true,
        isLoading = isLoading,
        pageLoadFailed = pageLoadFailed,
        sessionCrashed = sessionCrashed,
        hasLoadedGame = hasLoadedGame,
        hasCompletedLoad = hasCompletedLoad,
      )

  fun startGame(
    context: Context,
    gameUrl: String,
    onSessionReady: (GeckoSession) -> Unit = {},
  ) {
    when (val decision = GameSessionActions.decideStart(uiState)) {
      is GameSessionStartDecision.CreateNewSession -> {
        val gameRuntime = obtainRuntime(context)
        val gameSession = GeckoSession()
        val callbackGeneration = callbackGate.advance()
        setupGeckoDelegates(gameSession, context, callbackGeneration)
        gameSession.open(gameRuntime)
        session = gameSession
        onSessionReady(gameSession)
        transition(GameSessionActions::onSessionCreated)
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

  fun reloadGame(context: Context, gameUrl: String) {
    when (GameSessionActions.decideReload(uiState)) {
      GameSessionReloadDecision.ReloadInPlace -> {
        transition(GameSessionActions::onReloadCleared)
        session?.reload()
      }
      GameSessionReloadDecision.ReopenAndLoad -> {
        val gameSession = session ?: return
        gameSession.open(obtainRuntime(context))
        gameSession.loadUri(gameUrl)
        transition(GameSessionActions::onReloadCleared)
      }
      GameSessionReloadDecision.Noop -> Unit
    }
  }

  /** Navigate the existing GeckoSession to a newly selected game server without replacing it. */
  fun loadGameUrl(context: Context, gameUrl: String) {
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

  /**
   * Recovers from a content-process crash: a crashed session cannot be reloaded in place, so the
   * session is recreated while keeping the runtime (and therefore the game profile) intact. The
   * replaced session is closed by the composition root's keep-alive effect once it observes the new
   * session value.
   */
  fun restoreGame(
    context: Context,
    gameUrl: String,
    onSessionReady: (GeckoSession) -> Unit = {},
  ) {
    if (!GameSessionActions.shouldRestore(uiState)) return
    session = null
    transition(GameSessionActions::onSessionReset)
    startGame(context, gameUrl, onSessionReady)
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
    )

  private fun transition(transform: (GameSessionUiState) -> GameSessionUiState) {
    val next = transform(currentState())
    isLoading = next.isLoading
    pageLoadFailed = next.pageLoadFailed
    sessionCrashed = next.sessionCrashed
    hasLoadedGame = next.hasLoadedGame
    hasCompletedLoad = next.hasCompletedLoad
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
          if (isStaleSessionCallback()) return
          transition(GameSessionActions::onPageStart)
          Diagnostics.info(context, TAG, "GeckoView page started")
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
          if (isStaleSessionCallback()) return
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
          transition(GameSessionActions::onProcessFailure)
          Diagnostics.error(context, TAG, "GeckoView content process crashed")
        }

        override fun onKill(session: GeckoSession) {
          if (isStaleSessionCallback()) return
          transition(GameSessionActions::onProcessFailure)
          Diagnostics.error(context, TAG, "GeckoView content process was killed")
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
