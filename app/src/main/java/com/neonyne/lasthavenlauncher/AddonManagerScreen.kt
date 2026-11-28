package com.neonyne.lasthavenlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
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
          style = MaterialTheme.typography.headlineSmall,
        )
      }
      OutlinedButton(
        onClick = onBack,
        enabled = !busy,
        modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
      ) {
        Text(stringResource(R.string.addon_manager_back))
      }
    }

    Text(
      stringResource(R.string.addon_manager_import_description),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )

    Text(
      stringResource(R.string.addon_manager_import_trust_warning),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )

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
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(snapshot.addons, key = { it.folder }) { addon ->
          AddonCard(
            addon = addon,
            dependencyReport = dependencyReport,
            busy = busy,
            onToggle = { enabled -> onToggle(addon.folder, enabled) },
            onExport = { onExport(addon.folder) },
            onRemove = { pendingRemoval = addon.folder },
          )
        }
      }
    }

    Text(
      stringResource(R.string.addon_manager_runtime_status),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
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
  onExport: () -> Unit,
  onRemove: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(DesignTokens.cornerCard),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            addon.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "${addon.folder} · ${addon.version.ifBlank { stringResource(R.string.addon_manager_no_version) }}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
          checked = addon.enabled,
          onCheckedChange = onToggle,
          enabled = !busy,
        )
      }

      Text(
        stringResource(
          R.string.addon_manager_files_summary,
          addon.filesCount,
          formatAddonSize(addon.sizeBytes),
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )

      val missing = dependencyReport.missingByFolder[addon.folder].orEmpty()
      if (missing.isNotEmpty()) {
        Text(
          stringResource(R.string.addon_manager_missing_dependencies, missing.joinToString()),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (addon.optionalDependencies.isNotEmpty()) {
        Text(
          stringResource(
            R.string.addon_manager_optional_dependencies,
            addon.optionalDependencies.joinToString(),
          ),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (addon.folder in dependencyReport.cyclicFolders) {
        Text(
          stringResource(R.string.addon_manager_dependency_cycle),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (addon.sizeBytes > AddonLimits.MAX_RUNTIME_BUNDLE_BYTES) {
        Text(
          stringResource(R.string.addon_manager_runtime_too_large),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      addon.warnings.forEach { warning ->
        Text(
          warning,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (addon.notes.isNotBlank()) {
        Text(addon.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onExport,
          enabled = !busy,
          modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
        ) {
          Text(stringResource(R.string.addon_manager_export_zip))
        }
        OutlinedButton(
          onClick = onRemove,
          enabled = !busy,
          modifier = Modifier.heightIn(min = DesignTokens.minTouchTarget),
        ) {
          Text(stringResource(R.string.addon_manager_remove))
        }
      }
    }
  }
}

private fun formatAddonSize(bytes: Long): String =
  if (bytes < 1024L) {
    "$bytes B"
  } else {
    String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
  }
