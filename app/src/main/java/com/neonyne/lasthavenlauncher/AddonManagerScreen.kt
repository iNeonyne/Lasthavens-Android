package com.neonyne.lasthavenlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AddonManagerScreen(
  snapshot: AddonLibrarySnapshot,
  busy: Boolean,
  message: String?,
  onImport: () -> Unit,
  onToggle: (String, Boolean) -> Unit,
  onExport: (String) -> Unit,
  onRemove: (String) -> Unit,
  onBack: () -> Unit,
  reloadRequired: Boolean = false,
  operationLabel: String? = null,
  replacementFolders: List<String>? = null,
  onConfirmReplacement: () -> Unit = {},
  onCancelReplacement: () -> Unit = {},
) {
  val dependencyReport = remember(snapshot) { AddonDependencyAnalyzer.analyze(snapshot.addons) }
  var pendingRemoval by remember { mutableStateOf<String?>(null) }
  var selectedDetails by remember { mutableStateOf<AddonLibraryItem?>(null) }
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.addon_manager_title),
          color = DesignTokens.frost,
          style = MaterialTheme.typography.labelSmall,
        )
        Text(
          stringResource(R.string.addon_manager_local_addons),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      OutlinedButton(
        onClick = onBack,
        enabled = !busy && (addon.enabled || !isBlocked),
        modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
      ) {
        Text(stringResource(R.string.addon_manager_back))
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Button(
        onClick = onImport,
        enabled = !busy,
        modifier = Modifier.weight(1f).heightIn(min = DesignTokens.minTouchTarget),
        shape = RoundedCornerShape(DesignTokens.cornerButton),
        colors = DesignTokens.primaryButtonColors(),
      ) {
        Text(stringResource(R.string.addon_manager_import_zip))
      }
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(DesignTokens.cornerButton),
      ) {
        Text(
          "${snapshot.addons.size} addons\n${formatAddonSize(snapshot.totalBytes)}",
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }

    Text(
      stringResource(R.string.addon_manager_import_trust_warning),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )

    if (busy) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          operationLabel.orEmpty(),
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    if (reloadRequired) {
      Text(
        stringResource(R.string.addon_manager_reload_required),
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (!message.isNullOrBlank()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(DesignTokens.cornerButton),
      ) {
        Text(
          message,
          modifier =
            Modifier.fillMaxWidth().padding(12.dp).semantics {
              liveRegion = LiveRegionMode.Polite
            },
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    if (snapshot.addons.isEmpty()) {
      Spacer(Modifier.height(20.dp))
      Text(
        stringResource(R.string.addon_manager_no_addons),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(snapshot.addons, key = { it.folder }) { addon ->
          AddonCard(
            addon = addon,
            dependencyReport = dependencyReport,
            busy = busy,
            onToggle = { enabled -> onToggle(addon.folder, enabled) },
            onDetails = { selectedDetails = addon },
            onExport = { onExport(addon.folder) },
            onRemove = { pendingRemoval = addon.folder },
          )
        }
      }
    }
  }
  replacementFolders?.let { folders ->
    AlertDialog(
      onDismissRequest = onCancelReplacement,
      title = { Text(stringResource(R.string.addon_manager_replace_confirm_title)) },
      text = {
        Column(
          modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(stringResource(R.string.addon_manager_replace_confirm_message))
          folders.forEach { folder -> Text("• $folder") }
        }
      },
      confirmButton = {
        TextButton(onClick = onConfirmReplacement) {
          Text(stringResource(R.string.addon_manager_replace))
        }
      },
      dismissButton = {
        TextButton(onClick = onCancelReplacement) {
          Text(stringResource(R.string.addon_manager_cancel))
        }
      },
    )
  }
  selectedDetails?.let { addon ->
    AddonDetailsDialog(
      addon = addon,
      dependencyReport = dependencyReport,
      onDismiss = { selectedDetails = null },
    )
  }
  pendingRemoval?.let { folder ->
    AlertDialog(
      onDismissRequest = { pendingRemoval = null },
      title = { Text(stringResource(R.string.addon_manager_remove_confirm_title)) },
      text = { Text(stringResource(R.string.addon_manager_remove_confirm_message, folder)) },
      confirmButton = {
        TextButton(
          onClick = {
            pendingRemoval = null
            onRemove(folder)
          },
        ) {
          Text(stringResource(R.string.addon_manager_remove))
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingRemoval = null }) {
          Text(stringResource(R.string.addon_manager_cancel))
        }
      },
    )
  }
}

@Composable
private fun AddonCard(
  addon: AddonLibraryItem,
  dependencyReport: AddonDependencyReport,
  busy: Boolean,
  onToggle: (Boolean) -> Unit,
  onDetails: () -> Unit,
  onExport: () -> Unit,
  onRemove: () -> Unit,
) {
  val missing = dependencyReport.missingByFolder[addon.folder].orEmpty()
  val isBlocked = missing.isNotEmpty() || addon.folder in dependencyReport.cyclicFolders
  val warningCount =
    addon.warnings.size + if (addon.sizeBytes > AddonLimits.MAX_RUNTIME_BUNDLE_BYTES) 1 else 0
  val hasWarnings = warningCount > 0
  val status =
    when {
      isBlocked -> stringResource(R.string.addon_manager_status_blocked)
      hasWarnings -> stringResource(R.string.addon_manager_warnings_count, warningCount)
      addon.enabled -> stringResource(R.string.addon_manager_status_enabled)
      else -> stringResource(R.string.addon_manager_status_disabled)
    }
  val statusColor =
    when {
      isBlocked -> MaterialTheme.colorScheme.error
      hasWarnings -> MaterialTheme.colorScheme.tertiary
      addon.enabled -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  var menuExpanded by remember(addon.folder) { mutableStateOf(false) }
  val toggleDescription = stringResource(R.string.addon_manager_toggle, addon.title)
  val actionsDescription = stringResource(R.string.addon_manager_more_actions, addon.title)

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(DesignTokens.cornerCard),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          addon.title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            "${addon.folder} · ${addon.version.ifBlank { stringResource(R.string.addon_manager_no_version) }}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            stringResource(
              R.string.addon_manager_files_summary,
              addon.filesCount,
              formatAddonSize(addon.sizeBytes),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
          )
          Surface(
            color = statusColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(50),
          ) {
            Text(
              status,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
              color = statusColor,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )
          }
        }
      }
      Switch(
        checked = addon.enabled,
        onCheckedChange = onToggle,
        enabled = !busy,
        modifier = Modifier.semantics { contentDescription = toggleDescription },
      )
      Box {
        IconButton(
          onClick = { menuExpanded = true },
          enabled = !busy,
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_more_vert),
            contentDescription = actionsDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DropdownMenu(
          expanded = menuExpanded,
          onDismissRequest = { menuExpanded = false },
        ) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.addon_manager_details)) },
            onClick = {
              menuExpanded = false
              onDetails()
            },
            leadingIcon = {
              Icon(
                painter = painterResource(R.drawable.ic_settings_info),
                contentDescription = null,
              )
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.addon_manager_export_zip)) },
            onClick = {
              menuExpanded = false
              onExport()
            },
            enabled = !busy,
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.addon_manager_remove)) },
            onClick = {
              menuExpanded = false
              onRemove()
            },
            enabled = !busy,
          )
        }
      }
    }
  }
}

@Composable
private fun AddonDetailsDialog(
  addon: AddonLibraryItem,
  dependencyReport: AddonDependencyReport,
  onDismiss: () -> Unit,
) {
  val missing = dependencyReport.missingByFolder[addon.folder].orEmpty()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(addon.title) },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "${addon.folder} · ${addon.version.ifBlank { stringResource(R.string.addon_manager_no_version) }}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          stringResource(
            R.string.addon_manager_files_summary,
            addon.filesCount,
            formatAddonSize(addon.sizeBytes),
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        if (missing.isNotEmpty()) {
          Text(
            stringResource(R.string.addon_manager_missing_dependencies, missing.joinToString()),
            color = MaterialTheme.colorScheme.error,
          )
        }
        if (addon.optionalDependencies.isNotEmpty()) {
          Text(
            stringResource(
              R.string.addon_manager_optional_dependencies,
              addon.optionalDependencies.joinToString(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (addon.folder in dependencyReport.cyclicFolders) {
          Text(
            stringResource(R.string.addon_manager_dependency_cycle),
            color = MaterialTheme.colorScheme.error,
          )
        }
        if (addon.sizeBytes > AddonLimits.MAX_RUNTIME_BUNDLE_BYTES) {
          Text(
            stringResource(R.string.addon_manager_runtime_too_large),
            color = MaterialTheme.colorScheme.error,
          )
        }
        addon.warnings.forEach { warning ->
          Text(warning, color = MaterialTheme.colorScheme.tertiary)
        }
        if (addon.notes.isNotBlank()) {
          Text(addon.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.addon_manager_details_close))
      }
    },
  )
}

private fun formatAddonSize(bytes: Long): String =
  if (bytes < 1024L) {
    "$bytes B"
  } else {
    String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
  }
