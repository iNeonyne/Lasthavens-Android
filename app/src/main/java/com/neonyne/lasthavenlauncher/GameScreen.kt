package com.neonyne.lasthavenlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun GameScreen(
  session: GeckoSession?,
  presentation: GameLoadPresentation,
  onRetry: () -> Unit,
  onRestore: () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    if (session != null) {
      key(session) {
        AndroidView(
          factory = { viewContext -> GeckoView(viewContext).apply { setSession(session) } },
          modifier = Modifier.fillMaxSize(),
          // Detach the view but keep the GeckoSession open when navigating to another screen.
          onRelease = { view -> view.releaseSession() },
        )
      }
    }

    when (presentation) {
      GameLoadPresentation.Game -> Unit

      GameLoadPresentation.Preparing ->
        GameStatusSurface(
          contentDescription = stringResource(R.string.game_preparing_content_description),
        ) {
          LinearProgressIndicator()
          Spacer(modifier = Modifier.height(16.dp))
          Text(stringResource(R.string.game_preparing), color = DesignTokens.textSecondary)
        }

      GameLoadPresentation.LoadingGame ->
        GameStatusSurface(
          contentDescription = stringResource(R.string.game_loading_content_description),
        ) {
          LinearProgressIndicator(color = DesignTokens.frost)
          Spacer(modifier = Modifier.height(16.dp))
          Text(stringResource(R.string.game_loading), color = DesignTokens.textSecondary)
        }

      GameLoadPresentation.RecoverableFailure ->
        GameStatusSurface(
          contentDescription = stringResource(R.string.game_load_failed_content_description),
        ) {
          Text(
            stringResource(R.string.game_load_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            color = DesignTokens.textPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            stringResource(R.string.game_load_failed_hint),
            color = DesignTokens.textSecondary,
            modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp),
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
            shape = RoundedCornerShape(DesignTokens.cornerButton),
            colors = DesignTokens.primaryButtonColors(),
          ) {
            Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
          }
        }

      GameLoadPresentation.RendererFailure ->
        GameStatusSurface(
          contentDescription = stringResource(R.string.game_crashed_content_description),
        ) {
          Text(
            stringResource(R.string.game_crashed_title),
            style = MaterialTheme.typography.headlineSmall,
            color = DesignTokens.textPrimary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            stringResource(R.string.game_crashed_hint),
            color = DesignTokens.textSecondary,
            modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp),
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = onRestore,
            modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
            shape = RoundedCornerShape(DesignTokens.cornerButton),
            colors = DesignTokens.primaryButtonColors(),
          ) {
            Text(stringResource(R.string.game_restore), fontWeight = FontWeight.Bold)
          }
        }
    }
  }
}

@Composable
private fun GameStatusSurface(
  contentDescription: String,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription },
    color = DesignTokens.logSurface,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      content()
    }
  }
}
