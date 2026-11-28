package com.neonyne.lasthavenlauncher

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Removes updater-owned temporary files and obsolete finalized APKs without touching user files.
 */
internal object UpdateFileCleanup {
  internal const val PREFERENCES_NAME = "launcher_settings"
  internal const val KEY_PENDING_UPDATE_URI = "pending_update_uri"
  internal const val KEY_INSTALLER_HANDOFF_APK_NAME = "installer_handoff_apk_name"
  internal const val KEY_INSTALLER_HANDOFF_CANDIDATE_APK_NAME =
    "installer_handoff_candidate_apk_name"

  private const val FILE_PROVIDER_ROOT = "update_apks"
  private const val MAX_ENTRIES_PER_CLEANUP = 256
  private val managedApkName =
    Regex(
      """^LastHavenPlus-update-\d+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.apk$""",
    )

  /** Returns only a validated APK filename from this app's expected FileProvider URI. */
  fun managedApkName(uri: Uri, packageName: String): String? =
    managedApkName(uri.scheme, uri.authority, uri.pathSegments, packageName)

  internal fun managedApkName(
    scheme: String?,
    authority: String?,
    pathSegments: List<String>,
    packageName: String,
  ): String? {
    if (scheme != "content" || authority != "$packageName.updates") return null
    if (pathSegments.size != 2 || pathSegments[0] != FILE_PROVIDER_ROOT) return null
    return pathSegments[1].takeIf(::isManagedApkName)
  }

  fun isManagedApkName(name: String): Boolean = managedApkName.matches(name)

  /** Protects the APK awaiting permission/installer handoff and the latest launched installer. */
  fun protectedApkNames(context: Context): Set<String> {
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return protectedApkNames(
      pendingUriName =
        preferences.getString(KEY_PENDING_UPDATE_URI, null)?.let { value ->
          runCatching { Uri.parse(value) }
            .getOrNull()
            ?.let { managedApkName(it, context.packageName) }
        },
      handoffApkName = preferences.getString(KEY_INSTALLER_HANDOFF_APK_NAME, null),
      candidateApkName = preferences.getString(KEY_INSTALLER_HANDOFF_CANDIDATE_APK_NAME, null),
    )
  }

  internal fun protectedApkNames(
    pendingUriName: String?,
    handoffApkName: String?,
    candidateApkName: String?,
  ): Set<String> =
    listOfNotNull(pendingUriName, handoffApkName, candidateApkName)
      .filter(::isManagedApkName)
      .toSet()

  /**
   * Deletes abandoned partials and obsolete finalized updater APKs in one bounded directory scan.
   * Only direct regular files with updater-owned names are eligible; symlinks and subdirectories
   * are never followed or traversed.
   */
  fun removeObsoleteFiles(
    directory: File,
    protectedApkNames: Set<String> = emptySet(),
    keepFinalizedApks: Boolean = false,
  ) {
    if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return
    val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return
    runCatching {
      Files.newDirectoryStream(directory.toPath()).use { entries ->
        var scanned = 0
        for (path in entries) {
          if (scanned >= MAX_ENTRIES_PER_CLEANUP) break
          scanned += 1

          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
          val file = path.toFile()
          val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: continue
          if (canonicalFile.parentFile != canonicalDirectory) continue

          when {
            file.name.endsWith(".part") -> file.delete()
            !keepFinalizedApks && isManagedApkName(file.name) && file.name !in protectedApkNames ->
              file.delete()
          }
        }
      }
    }
  }
}
