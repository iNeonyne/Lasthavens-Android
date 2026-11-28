package com.neonyne.lasthavenlauncher

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipFile

enum class AddonImportStage {
  COPYING_ARCHIVE,
  VALIDATING_ARCHIVE,
  EXTRACTING_FILES,
  INDEXING_ADDONS,
  COMMITTING,
  COMPLETE,
}

data class ImportedAddon(
  val title: String,
  val folder: String,
  val version: String,
  val interfaceVersion: String,
  val notes: String,
  val dependencies: List<String>,
  val files: Map<String, File>,
  val sizeBytes: Long,
  val warnings: List<String>,
  val optionalDependencies: List<String> = emptyList(),
)

object AddonArchive {
  const val MAX_ARCHIVE_BYTES = AddonLimits.MAX_ARCHIVE_BYTES
  const val MAX_EXPANDED_BYTES = AddonLimits.MAX_EXPANDED_BYTES
  const val MAX_FILE_BYTES = AddonLimits.MAX_FILE_BYTES
  const val MAX_TOC_BYTES = AddonLimits.MAX_TOC_BYTES
  const val MAX_TITLE_CHARS = 256
  const val MAX_VERSION_CHARS = 128
  const val MAX_INTERFACE_CHARS = 256
  const val MAX_NOTES_CHARS = 4096
  const val MAX_DEPENDENCIES = 256
  const val MAX_DEPENDENCY_NAME_CHARS = 128
  const val MAX_ENTRIES = 32_000
  const val MAX_ADDONS_PER_ARCHIVE = 256
  const val MAX_LIBRARY_BYTES = AddonLimits.MAX_LIBRARY_BYTES

  private data class TocMetadata(
    val title: String,
    val version: String,
    val interfaceVersion: String,
    val notes: String,
    val dependencies: List<String>,
    val optionalDependencies: List<String>,
  )

  private data class AddonRoot(val path: String, val folder: String)

  /**
   * Copies [input] to [output] while guaranteeing forward progress even when the underlying stream
   * returns zero-byte reads. [sink] receives each accepted chunk before it is written so callers
   * can enforce byte caps and update digests; throwing from [sink] aborts the copy without writing.
   */
  internal fun copyWithProgressGuarantee(
    input: InputStream,
    output: OutputStream,
    buffer: ByteArray,
    sink: (ByteArray, Int) -> Unit,
  ) {
    val singleByte = ByteArray(1)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      if (count == 0) {
        val single = input.read()
        if (single < 0) break
        singleByte[0] = single.toByte()
        sink(singleByte, 1)
        output.write(single)
        continue
      }
      sink(buffer, count)
      output.write(buffer, 0, count)
    }
  }

  /**
   * Copies the selected archive to private staging storage, validates its central directory, then
   * streams each accepted file to disk. Expanded addon payloads are never retained in RAM.
   *
   * The caller may supply a verified document size when the provider exposes one. Unknown sizes are
   * accepted, but the actual streamed byte count is always capped at the compressed archive limit.
   */
  fun importZip(
    input: InputStream,
    compressedSize: Long?,
    extractionDirectory: File,
    onStage: (AddonImportStage) -> Unit = {},
    beforeExtraction: (Long) -> Unit = {},
  ): List<ImportedAddon> = input.use { archiveInput ->
    onStage(AddonImportStage.COPYING_ARCHIVE)
    require(compressedSize == null || compressedSize in 1..MAX_ARCHIVE_BYTES) {
      "Archive must be between 1 byte and 128 MiB."
    }
    require(extractionDirectory.mkdirs() || extractionDirectory.isDirectory) {
      "Could not prepare temporary addon storage."
    }

    var archiveFile: File? = null
    try {
      val archiveParent =
        extractionDirectory.parentFile
          ?: throw IllegalArgumentException("Temporary addon storage has no parent directory.")
      val sourceArchive = File.createTempFile("addon-source-", ".zip", archiveParent)
      archiveFile = sourceArchive
      var copiedBytes = 0L
      FileOutputStream(sourceArchive).use { output ->
        copyWithProgressGuarantee(archiveInput, output, ByteArray(DEFAULT_BUFFER_SIZE)) {
          chunk,
          length ->
          copiedBytes += length
          require(copiedBytes <= MAX_ARCHIVE_BYTES) {
            "Archive exceeds the 128 MiB compressed-size limit."
          }
        }
        output.fd.sync()
      }
      require(compressedSize == null || copiedBytes == compressedSize) {
        "Selected archive size changed or could not be verified."
      }
      require(copiedBytes > 0) { "Selected archive is empty." }
      onStage(AddonImportStage.VALIDATING_ARCHIVE)

      val entries = linkedMapOf<String, File>()
      val seen = mutableSetOf<String>()
      val acceptedEntries =
        mutableListOf<org.apache.commons.compress.archivers.zip.ZipArchiveEntry>()
      var declaredExpandedBytes = 0L
      var entryCount = 0
      val canonicalRoot = extractionDirectory.canonicalFile

      ZipFile(sourceArchive).use { zip ->
        val archiveEntries = zip.entries
        while (archiveEntries.hasMoreElements()) {
          val entry = archiveEntries.nextElement()
          entryCount += 1
          require(entryCount <= MAX_ENTRIES) { "Archive contains too many entries." }

          val path = validateEntryPath(entry.name)
          require(seen.add(path.lowercase(Locale.ROOT))) { "Duplicate archive path: $path" }
          require(!entry.isUnixSymlink) { "Symbolic links are not supported: $path" }
          require(!entry.generalPurposeBit.usesEncryption()) {
            "Encrypted ZIP entries are not supported: $path"
          }
          require(entry.method == 0 || entry.method == 8) {
            "Unsupported ZIP compression method: $path"
          }
          require(zip.canReadEntryData(entry)) { "Unsupported ZIP entry: $path" }

          if (entry.isDirectory) continue
          require(entry.size in 0..MAX_FILE_BYTES) {
            "File exceeds the 64 MiB limit or has an invalid size: $path"
          }
          require(entry.compressedSize >= 0 && entry.crc >= 0) {
            "ZIP entry metadata is incomplete: $path"
          }
          declaredExpandedBytes += entry.size
          require(declaredExpandedBytes <= MAX_EXPANDED_BYTES) {
            "Expanded archive exceeds the 256 MiB limit."
          }
          acceptedEntries += entry
        }

        // Check free space before writing any expanded archive payload to disk.
        beforeExtraction(declaredExpandedBytes)
        onStage(AddonImportStage.EXTRACTING_FILES)

        var expandedBytes = 0L
        for (entry in acceptedEntries) {
          val path = validateEntryPath(entry.name)
          val destination = File(extractionDirectory, path)
          require(destination.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
            "Unsafe archive path: $path"
          }
          destination.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) {
              "Could not create temporary addon directory."
            }
          }

          val crc = CRC32()
          var fileBytes = 0L
          zip.getInputStream(entry).use { source ->
            FileOutputStream(destination).use { output ->
              copyWithProgressGuarantee(source, output, ByteArray(DEFAULT_BUFFER_SIZE)) {
                chunk,
                length ->
                fileBytes += length
                expandedBytes += length
                require(fileBytes <= MAX_FILE_BYTES) {
                  "File exceeds the 64 MiB limit: $path"
                }
                require(expandedBytes <= MAX_EXPANDED_BYTES) {
                  "Expanded archive exceeds the 256 MiB limit."
                }
                crc.update(chunk, 0, length)
              }
            }
          }
          require(fileBytes == entry.size && crc.value == entry.crc) {
            "File integrity check failed: $path"
          }
          entries[path] = destination
        }
      }

      check(sourceArchive.delete()) { "Could not remove temporary addon archive." }
      archiveFile = null
      onStage(AddonImportStage.INDEXING_ADDONS)
      parseAddons(entries)
    } catch (error: Exception) {
      archiveFile?.delete()
      extractionDirectory.deleteRecursively()
      throw error
    }
  }

  internal fun validateEntryPath(name: String): String {
    val path = name.replace('\\', '/')
    val segments = path.split('/')
    require(
      path.isNotEmpty() &&
        !path.startsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(path) &&
        !path.contains(':') &&
        !path.any { it.code in 0..31 } &&
        segments.dropLast(if (path.endsWith('/')) 1 else 0).none {
          it.isEmpty() || it == "." || it == ".."
        },
    ) {
      "Unsafe archive path: $name"
    }
    return path
  }

  private fun parseAddons(entries: Map<String, File>): List<ImportedAddon> {
    val roots = mutableListOf<AddonRoot>()
    for (path in entries.keys) {
      val parts = path.split('/')
      val fileName = parts.last()
      if (parts.size >= 2) {
        val folder = parts[parts.lastIndex - 1]
        if (fileName.equals("$folder.toc", ignoreCase = true)) {
          roots += AddonRoot(parts.dropLast(1).joinToString("/"), folder)
        }
      } else if (fileName.endsWith(".toc", ignoreCase = true)) {
        roots += AddonRoot("", fileName.dropLast(4))
      }
    }

    require(roots.isNotEmpty()) { "Main Folder/Folder.toc is missing." }
    require(roots.size <= MAX_ADDONS_PER_ARCHIVE) {
      "Archive contains too many addons (maximum $MAX_ADDONS_PER_ARCHIVE)."
    }
    require(roots.size == 1 || roots.none { it.path.isEmpty() }) {
      "Multiple addons must each be packaged in a separate folder."
    }
    val topRoots = roots.filter { root ->
      roots.none { parent ->
        parent != root && (parent.path.isEmpty() || root.path.startsWith(parent.path + "/"))
      }
    }

    val seenFolders = mutableSetOf<String>()
    return topRoots.map { root ->
      require(
        root.folder.matches(Regex("[A-Za-z0-9_-]+")) &&
          !root.folder.startsWith("blizzard_", ignoreCase = true),
      ) {
        "Invalid or reserved addon folder: ${root.folder}"
      }
      require(seenFolders.add(root.folder.lowercase(Locale.ROOT))) {
        "Duplicate addon folder: ${root.folder}"
      }

      val ownFiles =
        entries
          .filter { (path, _) ->
            val inRoot = if (root.path.isEmpty()) true else path.startsWith(root.path + "/")
            inRoot &&
              path.split('/').none { it.equals("__MACOSX", ignoreCase = true) } &&
              !Regex("(^|/)\\.(git|svn)(/|$)").containsMatchIn(path)
          }
          .mapKeys { (path, _) ->
            if (root.path.isEmpty()) path else path.removePrefix(root.path + "/")
          }

      val tocName = "${root.folder}.toc"
      val tocFile =
        ownFiles.entries.firstOrNull { it.key.equals(tocName, ignoreCase = true) }?.value
          ?: throw IllegalArgumentException("Main Folder/Folder.toc is missing for ${root.folder}.")
      require(tocFile.length() <= MAX_TOC_BYTES) { "Addon TOC exceeds the 1 MiB limit." }
      val metadata = parseToc(String(tocFile.readBytes(), StandardCharsets.UTF_8))
      val total = ownFiles.values.sumOf { it.length() }
      val warnings = buildList {
        if (
          metadata.interfaceVersion.isNotBlank() &&
            "30300" !in metadata.interfaceVersion.split(Regex("[,\\s]+"))
        ) {
          add("The TOC does not declare Interface 30300. Compatibility is unverified.")
        }
        for ((path, file) in ownFiles) {
          if (file.length() > AddonLimits.MAX_RUNTIME_FILE_BYTES) {
            add(
              "File '$path' exceeds the " +
                "${AddonLimits.MAX_RUNTIME_FILE_BYTES / (1024L * 1024L)} MiB runtime per-file " +
                "limit; the addon cannot load into the game runtime.",
            )
          }
        }
        if (total > AddonLimits.MAX_RUNTIME_BUNDLE_BYTES) {
          add(
            "The addon exceeds the " +
              "${AddonLimits.MAX_RUNTIME_BUNDLE_BYTES / (1024L * 1024L)} MiB runtime bundle " +
              "limit; it cannot load into the game runtime.",
          )
        }
        if (ownFiles.size > AddonLimits.MAX_RUNTIME_FILES) {
          add(
            "The addon contains more than ${AddonLimits.MAX_RUNTIME_FILES} files and cannot load " +
              "into the game runtime.",
          )
        }
        val runtimeEntries = mutableSetOf<String>()
        for (path in ownFiles.keys) {
          var prefix = ""
          for (segment in path.split('/')) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            runtimeEntries += prefix
          }
        }
        if (runtimeEntries.size > AddonLimits.MAX_RUNTIME_ENTRIES_PER_ADDON) {
          add(
            "The addon exceeds the " +
              "${AddonLimits.MAX_RUNTIME_ENTRIES_PER_ADDON} runtime file and directory entry " +
              "limit; it cannot load into the game runtime.",
          )
        }
      }

      ImportedAddon(
        title = metadata.title.ifBlank { root.folder },
        folder = root.folder,
        version = metadata.version,
        interfaceVersion = metadata.interfaceVersion,
        notes = metadata.notes,
        dependencies = metadata.dependencies,
        files = ownFiles,
        sizeBytes = total,
        warnings = warnings,
        optionalDependencies = metadata.optionalDependencies,
      )
    }
  }

  private fun parseToc(text: String): TocMetadata {
    val values = mutableMapOf<String, String>()
    for (line in text.lineSequence()) {
      val match = Regex("^##\\s*([^:]+):\\s*(.*)$").find(line) ?: continue
      values[match.groupValues[1].trim().lowercase(Locale.ROOT)] = match.groupValues[2].trim()
    }
    fun clean(value: String?): String =
      value.orEmpty().replace(Regex("\\|c[0-9a-f]{8}|\\|r", RegexOption.IGNORE_CASE), "")

    fun boundedField(key: String, maximum: Int, cleanColors: Boolean = true): String {
      val value = if (cleanColors) clean(values[key]) else values[key].orEmpty()
      require(value.length <= maximum) { "TOC $key metadata exceeds the $maximum-character limit." }
      return value
    }

    fun dependencyList(raw: String): List<String> {
      val dependencies = raw.split(Regex("[,\\s]+")).filter(String::isNotBlank)
      require(dependencies.size <= MAX_DEPENDENCIES) {
        "TOC contains more than $MAX_DEPENDENCIES dependencies."
      }
      require(
        dependencies.all {
          it.length <= MAX_DEPENDENCY_NAME_CHARS && it.matches(Regex("[A-Za-z0-9_-]+"))
        },
      ) {
        "TOC contains an invalid dependency name."
      }
      return dependencies.distinctBy { it.lowercase(Locale.ROOT) }
    }

    val dependencies =
      dependencyList(values["dependencies"] ?: values["requireddeps"] ?: values["deps"].orEmpty())
    val optionalDependencies =
      dependencyList(values["optionaldeps"] ?: values["optional-dependencies"].orEmpty())

    return TocMetadata(
      title = boundedField("title", MAX_TITLE_CHARS),
      version = boundedField("version", MAX_VERSION_CHARS),
      interfaceVersion = boundedField("interface", MAX_INTERFACE_CHARS, cleanColors = false),
      notes = boundedField("notes", MAX_NOTES_CHARS),
      dependencies = dependencies,
      optionalDependencies = optionalDependencies,
    )
  }
}
