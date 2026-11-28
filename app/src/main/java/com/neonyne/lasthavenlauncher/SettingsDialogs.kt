package com.neonyne.lasthavenlauncher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class SettingsDestination(
  val titleRes: Int,
  val iconRes: Int,
) {
  GAME(R.string.settings_nav_game, R.drawable.ic_settings_game),
  NETWORK_STORAGE(R.string.settings_nav_network_storage, R.drawable.ic_settings_network),
  APPLICATION(R.string.settings_nav_application, R.drawable.ic_settings_info),
}

@Composable
fun SettingsScreen(
  context: Context,
  usageMeter: AppUsageMeter,
  isCheckingUpdate: Boolean,
  gameUrl: String,
  onGameUrlChange: (String) -> Unit,
  onBack: () -> Unit,
  onOpenDiagnostics: () -> Unit,
  onOpenUpdates: () -> Unit,
  onCheckUpdates: () -> Unit,
) {
  val activityStarted = rememberLifecycleStarted(context as? LifecycleOwner)
  val networkTimeLocale = LocalConfiguration.current.locales[0]
  var selectedDestination by remember { mutableStateOf(SettingsDestination.GAME) }
  var networkSnapshot by remember { mutableStateOf<NetworkUsageSnapshot?>(null) }
  var gameDataSizeBytes by remember { mutableStateOf<Long?>(null) }
  var lastNetworkUpdate by remember { mutableStateOf<Long?>(null) }
  var refreshingNetwork by remember { mutableStateOf(false) }

  LaunchedEffect(selectedDestination, usageMeter, activityStarted) {
    if (selectedDestination == SettingsDestination.NETWORK_STORAGE && activityStarted) {
      gameDataSizeBytes = withContext(Dispatchers.IO) { usageMeter.gameDataSizeBytes() }
      while (true) {
        refreshingNetwork = true
        val nextSnapshot =
          withContext(Dispatchers.IO) {
            runCatching { usageMeter.networkSnapshot() }.getOrNull()
          }
        if (nextSnapshot != null) {
          networkSnapshot = nextSnapshot
          lastNetworkUpdate = System.currentTimeMillis()
        }
        refreshingNetwork = false
        delay(5_000L)
      }
    }
  }

  SettingsNavigationFrame(
    selected = selectedDestination,
    onSelect = { selectedDestination = it },
    onBack = onBack,
  ) {
    when (selectedDestination) {
      SettingsDestination.GAME -> {
        PageSection(title = stringResource(R.string.settings_game_server_section)) {
          Text(
            stringResource(R.string.settings_game_server_hint),
            color = DesignTokens.textSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
              onClick = { onGameUrlChange(DEFAULT_GAME_URL) },
              enabled = gameUrl != DEFAULT_GAME_URL,
              modifier = Modifier.weight(1f),
            ) {
              Text(stringResource(R.string.settings_game_server_default))
            }
            OutlinedButton(
              onClick = { onGameUrlChange(ALTERNATE_GAME_URL) },
              enabled = gameUrl != ALTERNATE_GAME_URL,
              modifier = Modifier.weight(1f),
            ) {
              Text(stringResource(R.string.settings_game_server_alternate))
            }
          }
        }
      }

      SettingsDestination.NETWORK_STORAGE -> {
        PageSection(title = stringResource(R.string.settings_storage_section)) {
          InfoLine(
            stringResource(R.string.settings_game_data_size),
            gameDataSizeBytes?.let(::formatBytes)
              ?: stringResource(R.string.settings_value_not_available),
          )
        }
        PageSection(title = stringResource(R.string.settings_network_this_session)) {
          InfoLine(
            stringResource(R.string.settings_received_this_session),
            networkSnapshot?.receivedThisSessionBytes?.let(::formatBytes)
              ?: stringResource(R.string.settings_value_not_available),
          )
          InfoLine(
            stringResource(R.string.settings_sent_this_session),
            networkSnapshot?.sentThisSessionBytes?.let(::formatBytes)
              ?: stringResource(R.string.settings_value_not_available),
          )
        }
        PageSection(title = stringResource(R.string.settings_network_tracked_period)) {
          InfoLine(
            stringResource(R.string.settings_network_tracked_received),
            networkSnapshot?.receivedTrackedTotalBytes?.let(::formatBytes)
              ?: stringResource(R.string.settings_value_not_available),
          )
          InfoLine(
            stringResource(R.string.settings_network_tracked_sent),
            networkSnapshot?.sentTrackedTotalBytes?.let(::formatBytes)
              ?: stringResource(R.string.settings_value_not_available),
          )
          Text(
            stringResource(R.string.settings_network_auto_refresh),
            color = DesignTokens.textSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            stringResource(R.string.settings_network_tracked_hint),
            color = DesignTokens.textSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
          val updatedAt = lastNetworkUpdate
          if (updatedAt != null) {
            Text(
              stringResource(
                R.string.settings_network_last_updated,
                java.text
                  .SimpleDateFormat("HH:mm:ss", networkTimeLocale)
                  .format(java.util.Date(updatedAt)),
              ),
              color = DesignTokens.textSecondary,
              style = MaterialTheme.typography.labelSmall,
            )
          } else if (refreshingNetwork) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
        }
      }

      SettingsDestination.APPLICATION -> {
        PageSection(title = stringResource(R.string.settings_application_section)) {
          Text(
            stringResource(R.string.app_name),
            color = DesignTokens.textPrimary,
            fontWeight = FontWeight.SemiBold,
          )
          Text(stringResource(R.string.settings_game_engine), color = DesignTokens.textSecondary)
          Text(
            stringResource(R.string.settings_passkeys_unavailable),
            color = DesignTokens.textSecondary,
            style = MaterialTheme.typography.bodySmall,
          )
          OutlinedButton(
            onClick = onCheckUpdates,
            enabled = !isCheckingUpdate,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (isCheckingUpdate) stringResource(R.string.settings_checking_updates)
              else stringResource(R.string.settings_check_updates),
            )
          }
          TextButton(onClick = onOpenUpdates) {
            Text(stringResource(R.string.settings_update_details))
          }
          TextButton(onClick = onOpenDiagnostics) {
            Text(stringResource(R.string.settings_open_diagnostics))
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsNavigationFrame(
  selected: SettingsDestination,
  onSelect: (SettingsDestination) -> Unit,
  onBack: () -> Unit,
  content: @Composable () -> Unit,
) {
  BoxWithConstraints(
    modifier = Modifier.fillMaxSize().background(DesignTokens.ink).padding(12.dp),
  ) {
    val railWidth = if (maxWidth < 680.dp) 148.dp else 184.dp
    Row(
      modifier = Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.width(railWidth).fillMaxHeight(),
        color = DesignTokens.panel,
        shape = MaterialTheme.shapes.large,
      ) {
        Column(
          modifier = Modifier.fillMaxSize().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(
              stringResource(R.string.settings_title),
              color = DesignTokens.textPrimary,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              stringResource(R.string.settings_brand_mark),
              color = DesignTokens.frost,
              style = MaterialTheme.typography.labelSmall,
            )
          }
          SettingsDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(MaterialTheme.shapes.medium)
                  .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent,
                  )
                  .clickable { onSelect(destination) }
                  .padding(horizontal = 10.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                painter = painterResource(destination.iconRes),
                contentDescription = null,
                tint =
                  if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                  else DesignTokens.textSecondary,
                modifier = Modifier.width(20.dp),
              )
              Text(
                stringResource(destination.titleRes),
                color =
                  if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                  else DesignTokens.textSecondary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
              )
            }
          }
          Spacer(Modifier.weight(1f))
          TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
          }
        }
      }
      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.large)
            .background(DesignTokens.panel.copy(alpha = 0.55f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(
          stringResource(selected.titleRes),
          color = DesignTokens.textPrimary,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Column(
          modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          content()
        }
      }
    }
  }
}

@Composable
fun DiagnosticsScreen(
  context: Context,
  onBack: () -> Unit,
) {
  var content by remember { mutableStateOf(Diagnostics.read(context)) }
  SettingsPageFrame(title = stringResource(R.string.diagnostics_title), onBack = onBack) {
    PageSection(title = stringResource(R.string.diagnostics_log_section)) {
      Text(
        stringResource(R.string.diagnostics_privacy_hint),
        color = DesignTokens.textSecondary,
        style = MaterialTheme.typography.bodySmall,
      )
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DesignTokens.logSurface,
        shape = MaterialTheme.shapes.medium,
      ) {
        Text(
          content,
          modifier = Modifier.padding(DesignTokens.spaceSection),
          color = DesignTokens.textBody,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      val clearedMessage = stringResource(R.string.diagnostics_cleared)
      Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceSmall)) {
        OutlinedButton(onClick = { content = Diagnostics.read(context) }) {
          Text(stringResource(R.string.action_refresh))
        }
        OutlinedButton(
          onClick = {
            Diagnostics.clear(context)
            content = clearedMessage
          },
        ) {
          Text(stringResource(R.string.diagnostics_clear))
        }
      }
      Button(
        onClick = {
          val share =
            Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_share_subject))
              putExtra(Intent.EXTRA_TEXT, content)
            }
          context.startActivity(
            Intent.createChooser(share, context.getString(R.string.diagnostics_share_chooser)),
          )
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.diagnostics_share))
      }
    }
  }
}

@Composable
// All launcher-owned update-page copy is sourced from default Android string resources.
internal fun UpdateScreen(
  update: AppUpdateInfo?,
  message: String?,
  isChecking: Boolean,
  isDownloading: Boolean,
  downloadingDelta: Boolean,
  progress: UpdateProgressState,
  onBack: () -> Unit,
  onDownload: (preferDelta: Boolean) -> Unit,
) {
  SettingsPageFrame(title = stringResource(R.string.updates_title), onBack = onBack) {
    PageSection(title = stringResource(R.string.updates_status_section)) {
      when {
        isChecking -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text(stringResource(R.string.updates_checking), color = DesignTokens.textSecondary)
        }
        update != null -> {
          Text(
            stringResource(R.string.updates_available, update.version),
            color = DesignTokens.textPrimary,
          )
          if (update.patch != null) {
            val patchMib =
              String.format(java.util.Locale.US, "%.1f", update.patch.size / 1024.0 / 1024.0)
            Text(
              stringResource(R.string.updates_delta_available, patchMib),
              color = DesignTokens.textSecondary,
            )
          }
          if (update.releaseNotes.isNotBlank()) {
            Text(update.releaseNotes, color = DesignTokens.textSecondary)
          }
          if (isDownloading) {
            val downloadLabel =
              when (progress.stage) {
                UpdateStage.DIFFERENTIAL_DOWNLOAD ->
                  stringResource(R.string.updates_downloading_delta)
                UpdateStage.FULL_DOWNLOAD -> stringResource(R.string.updates_downloading_full)
                UpdateStage.VERIFYING -> stringResource(R.string.updates_verifying)
                UpdateStage.READY -> stringResource(R.string.updates_ready)
                else ->
                  if (downloadingDelta) stringResource(R.string.updates_downloading_delta)
                  else stringResource(R.string.updates_downloading_full)
              }
            val progressPercent = progress.percent
            if (progressPercent != null) {
              LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
              )
            } else {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(downloadLabel, color = DesignTokens.textSecondary)
            if (progress.downloadAttempt > 1) {
              Text(
                stringResource(R.string.updates_retrying_attempt, progress.downloadAttempt),
                color = DesignTokens.textSecondary,
              )
            }
            val totalLabel =
              if (progress.totalKnown) formatBytes(progress.totalBytes)
              else stringResource(R.string.updates_unknown_size)
            Text(
              if (progress.totalKnown) {
                stringResource(
                  R.string.updates_progress_bytes,
                  formatBytes(progress.receivedBytes),
                  totalLabel,
                )
              } else {
                stringResource(
                  R.string.updates_progress_received,
                  formatBytes(progress.receivedBytes),
                )
              },
              color = DesignTokens.textSecondary,
            )
            progress.estimatedBytesPerSecond?.let { rate ->
              Text(
                stringResource(R.string.updates_progress_speed, formatBytes(rate)),
                color = DesignTokens.textSecondary,
              )
            }
            progress.estimatedSecondsRemaining?.let { seconds ->
              Text(
                stringResource(R.string.updates_progress_eta, seconds),
                color = DesignTokens.textSecondary,
              )
            }
            if (progress.fallbackToFull) {
              Text(
                stringResource(R.string.updates_fallback_full),
                color = DesignTokens.textSecondary,
              )
            }
          } else if (progress.stage == UpdateStage.FAILED) {
            val retryHapticView = LocalView.current
            Text(
              progress.failureMessage
                ?: message
                ?: stringResource(R.string.updates_download_failed),
              color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
              onClick = {
                restrainedActionFeedback(retryHapticView)
                onDownload(downloadingDelta)
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.updates_retry_download))
            }
            if (update.patch != null && downloadingDelta) {
              TextButton(onClick = { onDownload(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                  stringResource(
                    if (progress.fallbackToFull) R.string.updates_retry_full
                    else R.string.updates_download_full_instead,
                  ),
                )
              }
            }
          } else {
            val hapticView = LocalView.current
            Button(
              onClick = {
                // Haptic confirmation for explicit download actions only.
                restrainedActionFeedback(hapticView)
                onDownload(update.patch != null)
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                if (update.patch != null) stringResource(R.string.updates_download_smaller)
                else stringResource(R.string.updates_download_full),
              )
            }
            if (update.patch != null) {
              OutlinedButton(
                onClick = {
                  restrainedActionFeedback(hapticView)
                  onDownload(false)
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(stringResource(R.string.updates_download_full_instead))
              }
            }
          }
        }
        else ->
          Text(
            message ?: stringResource(R.string.updates_no_info),
            color = DesignTokens.textSecondary,
          )
      }
    }
    if (message != null && update != null && progress.stage != UpdateStage.FAILED) {
      PageSection(title = stringResource(R.string.updates_download_status)) {
        Text(message, color = DesignTokens.textSecondary)
      }
    }
  }
}

@Composable
private fun SettingsPageFrame(
  title: String,
  onBack: () -> Unit,
  content: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize().background(DesignTokens.ink)) {
    val widthDp = maxWidth.value.toInt()
    val maxContentWidth = LauncherLayoutPolicy.settingsContentMaxWidthDp(widthDp).dp
    val horizontalPadding = LauncherLayoutPolicy.pageHorizontalPaddingDp(widthDp).dp
    Column(
      modifier =
        Modifier.widthIn(max = maxContentWidth)
          .fillMaxWidth()
          .fillMaxHeight()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = horizontalPadding, vertical = 18.dp)
          .align(Alignment.TopCenter),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            title,
            color = DesignTokens.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
          )
          Text(
            stringResource(R.string.settings_brand_mark),
            color = DesignTokens.frost,
            style = MaterialTheme.typography.labelSmall,
          )
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
      }
      content()
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun PageSection(
  title: String,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = DesignTokens.panel),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(DesignTokens.spaceSection),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, color = DesignTokens.frost, style = MaterialTheme.typography.titleSmall)
      content()
    }
  }
}

@Composable
private fun InfoLine(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = DesignTokens.textSecondary, modifier = Modifier.weight(1f))
    Text(value, color = DesignTokens.textPrimary, fontWeight = FontWeight.SemiBold)
  }
}

private fun formatBytes(value: Long): String {
  if (value < 1024L) return "$value B"
  val units = listOf("KiB", "MiB", "GiB")
  var amount = value.toDouble()
  var index = -1
  do {
    amount /= 1024.0
    index++
  } while (amount >= 1024.0 && index < units.lastIndex)
  return String.format(java.util.Locale.US, "%.1f %s", amount, units[index])
}
