package com.neonyne.lasthavenlauncher

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * Bounded, read-only view of enabled addon resources for the ZIP 0.5.1 runtime payload.
 *
 * The provider never copies files into extension storage. It snapshots validated file descriptors
 * from the app-private library and reads only the requested bounded chunk from the same file.
 */
internal class AddonRuntimePayloadProvider(private val repository: AddonLibraryRepository) {
  data class FileDescriptor
  internal constructor(
    val folder: String,
    val path: String,
    val sizeBytes: Long,
    internal val file: File,
    internal val canonicalPath: String,
    internal val lastModified: Long,
  )

  data class Manifest
  internal constructor(
    val revision: String,
    val sourceRevision: String,
    val addonFolders: List<String>,
    val files: List<FileDescriptor>,
    internal val filesByKey: Map<String, FileDescriptor>,
    val totalBytes: Long,
  ) {
    val pageCount: Int
      get() =
        (files.size + AddonRuntimePayloadProvider.MANIFEST_PAGE_SIZE - 1) /
          AddonRuntimePayloadProvider.MANIFEST_PAGE_SIZE
  }

  /**
   * Builds the runtime manifest. The directory walk runs under the repository-wide library lock so
   * a concurrent import, removal or recovery pass cannot rename or delete addon files mid-scan.
   */
  fun buildManifest(snapshot: AddonLibrarySnapshot): Manifest {
    val plan = AddonRuntimePlanner.plan(snapshot)
    if (plan.orderedAddons.size > MAX_RUNTIME_ADDONS) {
      throw AddonRuntimePayloadException("payloadTooLarge")
    }
    return repository.withLibraryAccess { buildManifestLocked(plan, snapshot) }
  }

  private fun buildManifestLocked(
    plan: AddonLoadPlan,
    snapshot: AddonLibrarySnapshot,
  ): Manifest {
    val descriptors = mutableListOf<FileDescriptor>()
    val addonFolders = mutableListOf<String>()
    var totalBytes = 0L
    val skippedAddons = mutableSetOf<String>()

    for (addon in plan.orderedAddons) {
      // Never include a dependent when its required addon was omitted by a runtime size limit.
      // The planner has already excluded missing/cyclic dependencies; this covers dependencies
      // that become unavailable during the bounded filesystem walk below.
      val unavailableDependencies =
        addon.dependencies.filter { it.lowercase(Locale.ROOT) in skippedAddons }
      if (unavailableDependencies.isNotEmpty()) {
        skippedAddons += addon.folder.lowercase(Locale.ROOT)
        continue
      }

      // Per-addon size isolation (issue #228): an addon that violates a runtime payload limit is
      // skipped so every independent valid addon stays usable. Security violations (invalid paths,
      // unreadable files) still fail the whole manifest.
      try {
        val addonByteCount = appendAddonDescriptors(addon, totalBytes, descriptors)
        totalBytes += addonByteCount
        addonFolders += addon.folder
      } catch (error: AddonRuntimePayloadException) {
        if (error.code != "payloadTooLarge") throw error
        skippedAddons += addon.folder.lowercase(Locale.ROOT)
      }
    }
    val sortedFiles =
      descriptors.sortedWith(compareBy<FileDescriptor> { it.folder }.thenBy { it.path })
    val normalizedKeys = sortedFiles.map { key(it.folder, it.path).lowercase(Locale.ROOT) }
    if (normalizedKeys.distinct().size != normalizedKeys.size) {
      throw AddonRuntimePayloadException("invalidPath")
    }
    val filesByKey = sortedFiles.associateBy { key(it.folder, it.path) }
    val revisionSignature = buildString {
      append(plan.bundleRevision)
      sortedFiles.forEach {
        append('\n')
          .append(it.folder)
          .append('\t')
          .append(it.path)
          .append('\t')
          .append(it.sizeBytes)
          .append('\t')
          .append(it.lastModified)
      }
    }
    val revision =
      MessageDigest.getInstance("SHA-256")
        .digest(revisionSignature.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return Manifest(
      revision = revision,
      sourceRevision = snapshot.revision,
      addonFolders = addonFolders,
      files = sortedFiles,
      filesByKey = filesByKey,
      totalBytes = totalBytes,
    )
  }

  /**
   * Walks one addon directory and appends its validated descriptors to [descriptors] only when the
   * whole addon is within every runtime limit. Returns the committed file and byte counts. Size
   * violations throw `payloadTooLarge` (isolated by the caller); path and readability violations
   * still fail the manifest.
   */
  private fun appendAddonDescriptors(
    addon: AddonLibraryItem,
    committedBytes: Long,
    descriptors: MutableList<FileDescriptor>,
  ): Long {
    val directory = repository.addonDirectory(addon.folder)
    if (!directory.isDirectory) {
      throw AddonRuntimePayloadException("unavailable")
    }
    val canonicalDirectory = directory.canonicalFile
    val expectedParent = directory.absoluteFile.parentFile?.canonicalFile
    if (expectedParent == null || canonicalDirectory.parentFile != expectedParent) {
      throw AddonRuntimePayloadException("invalidPath")
    }
    val canonicalRootPath = canonicalDirectory.toPath()
    val pendingDirectories = ArrayDeque<File>()
    pendingDirectories.add(directory)
    var addonBytes = 0L
    var addonFiles = 0
    var addonEntries = 0
    val addonDescriptors = mutableListOf<FileDescriptor>()

    while (pendingDirectories.isNotEmpty()) {
      val current = pendingDirectories.removeLast()
      val children = mutableListOf<File>()
      try {
        Files.newDirectoryStream(current.toPath()).use { entries ->
          for (entry in entries) {
            addonEntries += 1
            if (addonEntries > MAX_RUNTIME_ENTRIES_PER_ADDON) {
              throw AddonRuntimePayloadException(
                "payloadTooLarge",
                "addon exceeds the " +
                  "${AddonLimits.MAX_RUNTIME_ENTRIES_PER_ADDON} entry runtime limit",
              )
            }
            children += entry.toFile()
          }
        }
      } catch (error: AddonRuntimePayloadException) {
        throw error
      } catch (_: Exception) {
        throw AddonRuntimePayloadException("readFailed")
      }
      children.sortBy { it.name }
      for (child in children) {
        if (Files.isSymbolicLink(child.toPath())) {
          throw AddonRuntimePayloadException("invalidPath")
        }
        val canonicalChild = child.canonicalFile
        if (
          !canonicalChild.toPath().startsWith(canonicalRootPath) ||
            canonicalChild == canonicalDirectory
        ) {
          throw AddonRuntimePayloadException("invalidPath")
        }
        if (child.isDirectory) {
          if (!Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw AddonRuntimePayloadException("invalidPath")
          }
          pendingDirectories.add(child)
          continue
        }
        if (!child.isFile || !Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
          throw AddonRuntimePayloadException("invalidPath")
        }

        val relativePath = child.relativeTo(directory).invariantSeparatorsPath
        val validatedPath = runCatching {
          AddonArchive.validateEntryPath(relativePath)
        }
          .getOrElse {
            throw AddonRuntimePayloadException("invalidPath")
          }
        if (validatedPath != relativePath || relativePath.length > MAX_RUNTIME_PATH_CHARS) {
          throw AddonRuntimePayloadException("invalidPath")
        }

        val size = child.length()
        if (size !in 0..MAX_RUNTIME_FILE_BYTES) {
          throw AddonRuntimePayloadException(
            "payloadTooLarge",
            "file '$relativePath' exceeds the " +
              "${AddonLimits.MAX_RUNTIME_FILE_BYTES / (1024L * 1024L)} MiB runtime per-file limit",
          )
        }
        addonBytes += size
        addonFiles += 1
        if (
          addonFiles > AddonArchive.MAX_ENTRIES ||
            descriptors.size + addonDescriptors.size + 1 > MAX_RUNTIME_FILES
        ) {
          throw AddonRuntimePayloadException(
            "payloadTooLarge",
            "addon exceeds the ${AddonLimits.MAX_RUNTIME_FILES} file runtime limit",
          )
        }

        addonDescriptors +=
          FileDescriptor(
            folder = addon.folder,
            path = relativePath,
            sizeBytes = size,
            file = child,
            canonicalPath = canonicalChild.path,
            lastModified = child.lastModified(),
          )
      }
    }

    if (addonFiles != addon.filesCount || addonBytes != addon.sizeBytes) {
      throw AddonRuntimePayloadException("unavailable")
    }
    if (addonBytes + committedBytes > AddonLimits.MAX_RUNTIME_BUNDLE_BYTES) {
      throw AddonRuntimePayloadException(
        "payloadTooLarge",
        "addon exceeds the ${AddonLimits.MAX_RUNTIME_BUNDLE_BYTES / (1024L * 1024L)} MiB runtime bundle budget",
      )
    }
    descriptors += addonDescriptors
    return addonBytes
  }

  /**
   * Extracts validated TOC load-file entry points for every addon folder in [manifest]
   * (issue #245). Each list keeps only paths that are present in the served manifest for the same
   * folder, deduplicated case-insensitively. A missing or unreadable TOC yields an empty list for
   * that addon so one malformed addon cannot fail the whole bootstrap plan.
   */
  fun entryPoints(manifest: Manifest): Map<String, List<String>> = repository.withLibraryAccess {
    entryPointsLocked(manifest)
  }

  private fun entryPointsLocked(manifest: Manifest): Map<String, List<String>> {
    val filesByFolder = manifest.files.groupBy { it.folder }
    val result = LinkedHashMap<String, List<String>>()
    for (folder in manifest.addonFolders) {
      val files = filesByFolder[folder].orEmpty()
      val toc = files.firstOrNull { it.path.equals("$folder.toc", ignoreCase = true) }
      result[folder] =
        if (toc == null) {
          emptyList()
        } else {
          val available = files.map { it.path }.toSet()
          val availableLower = available.map { it.lowercase(Locale.ROOT) }.toSet()
          runCatching {
              val content =
                if (toc.sizeBytes in 1..AddonArchive.MAX_TOC_BYTES) toc.file.readBytes()
                else ByteArray(0)
              AddonRuntimePlanner.parseTocLoadFiles(String(content, StandardCharsets.UTF_8))
            }
            .getOrDefault(emptyList())
            .filter { it in available || it.lowercase(Locale.ROOT) in availableLower }
            .distinctBy { it.lowercase(Locale.ROOT) }
        }
    }
    return result
  }

  fun page(manifest: Manifest, page: Int): List<FileDescriptor> {
    if (page !in 0 until manifest.pageCount) {
      throw AddonRuntimePayloadException("invalidRequest")
    }
    val start = page * MANIFEST_PAGE_SIZE
    return manifest.files.subList(start, minOf(start + MANIFEST_PAGE_SIZE, manifest.files.size))
  }

  fun readChunk(manifest: Manifest, folder: String, path: String, offset: Long): ByteArray {
    val descriptor =
      manifest.filesByKey[key(folder, path)] ?: throw AddonRuntimePayloadException("notFound")
    if (offset < 0 || offset >= descriptor.sizeBytes) {
      throw AddonRuntimePayloadException("invalidOffset")
    }
    return repository.withLibraryAccess { readChunkLocked(descriptor, offset) }
  }

  private fun readChunkLocked(descriptor: FileDescriptor, offset: Long): ByteArray {
    val currentCanonical = descriptor.file.canonicalFile
    if (
      Files.isSymbolicLink(descriptor.file.toPath()) ||
        currentCanonical.path != descriptor.canonicalPath ||
        !Files.isRegularFile(descriptor.file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        descriptor.file.length() != descriptor.sizeBytes ||
        descriptor.file.lastModified() != descriptor.lastModified
    ) {
      throw AddonRuntimePayloadException("unavailable")
    }

    val length = minOf(MAX_FILE_CHUNK_BYTES.toLong(), descriptor.sizeBytes - offset).toInt()
    val bytes = ByteArray(length)
    try {
      RandomAccessFile(descriptor.file, "r").use { input ->
        input.seek(offset)
        input.readFully(bytes)
      }
    } catch (_: Exception) {
      throw AddonRuntimePayloadException("readFailed")
    }
    return bytes
  }

  private fun key(folder: String, path: String): String = "$folder/$path"

  companion object {
    const val MAX_RUNTIME_BUNDLE_BYTES = AddonLimits.MAX_RUNTIME_BUNDLE_BYTES
    const val MAX_RUNTIME_FILE_BYTES = AddonLimits.MAX_RUNTIME_FILE_BYTES
    const val MAX_RUNTIME_FILES = AddonLimits.MAX_RUNTIME_FILES
    const val MAX_RUNTIME_ADDONS = AddonLimits.MAX_RUNTIME_ADDONS
    const val MAX_RUNTIME_PATH_CHARS = AddonLimits.MAX_RUNTIME_PATH_CHARS
    const val MAX_RUNTIME_ENTRIES_PER_ADDON = AddonLimits.MAX_RUNTIME_ENTRIES_PER_ADDON
    const val MAX_FILE_CHUNK_BYTES = AddonLimits.MAX_FILE_CHUNK_BYTES
    const val MANIFEST_PAGE_SIZE = AddonLimits.MANIFEST_PAGE_SIZE
  }
}

internal class AddonRuntimePayloadException(val code: String, val detail: String? = null) :
  IllegalStateException(detail ?: code)
