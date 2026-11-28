package com.neonyne.lasthavenlauncher

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

internal const val MAX_ADDON_LIBRARY_ITEMS = 10_000

data class AddonLibraryItem(
  val title: String,
  val folder: String,
  val version: String,
  val interfaceVersion: String,
  val notes: String,
  val dependencies: List<String>,
  val sizeBytes: Long,
  val filesCount: Int,
  val enabled: Boolean,
  val importedAt: Long,
  val warnings: List<String>,
  val optionalDependencies: List<String> = emptyList(),
)

data class AddonLibrarySnapshot(
  val revision: String,
  val addons: List<AddonLibraryItem>,
) {
  val totalBytes: Long
    get() = addons.sumOf { it.sizeBytes }
}

/**
 * App-private, file-backed addon library. Imported packages are never enabled automatically.
 *
 * All repository operations (reads, imports, removals, exports and transaction recovery) are
 * serialized behind one lock per canonical library root (issue #227). The launcher creates separate
 * repository instances over the same directory — the bridge snapshot path and the Addon Manager
 * executor — so the lock is shared by canonical root path, not per instance. This guarantees that
 * destructive stale recovery can never observe an in-flight transaction: while a mutation holds the
 * lock, no other operation can run recovery, and any transaction record a recovery pass sees is
 * genuinely abandoned (its owning process is gone). Recovery also runs once under the lock before
 * an instance's first operation, so a mutation cannot commit on top of an ambiguous index left by a
 * previous process; the pass is idempotent, and recovery failures are retried by the next operation
 * instead of being silently skipped.
 */
class AddonLibraryRepository(
  private val root: File,
  private val availableStorageBytes: () -> Long = { root.usableSpace },
  private val beforeIndexWrite: (() -> Unit)? = null,
  private val afterBackupRestore: (() -> Unit)? = null,
) {
  private companion object {
    const val MIN_FREE_SPACE_RESERVE_BYTES = 1024L * 1024L
    const val TXN_PREFIX = ".txn-"
    const val BACKUP_PREFIX = ".backup-"
    const val REMOVE_PREFIX = ".remove-"
    const val STAGE_PREFIX = ".stage-"
    const val EXTRACT_PREFIX = ".extract-"
    const val TEMP_INDEX_PREFIX = ".library-"
    const val TEMP_INDEX_SUFFIX = ".tmp"
    const val SOURCE_ARCHIVE_PREFIX = "addon-source-"

    /**
     * One lock per canonical library root, shared by every [AddonLibraryRepository] instance that
     * points at that root.
     */
    val rootLocks = ConcurrentHashMap<String, ReentrantLock>()
  }

  private val indexFile = File(root, "library.properties")

  private val rootKey: String by lazy { root.canonicalFile.path }

  private val startupRecoveryCompleted = java.util.concurrent.atomic.AtomicBoolean(false)

  private data class TransactionPlan(
    val oldRevision: String?,
    val oldIndexMissing: Boolean,
    val newRevision: String,
    val backups: List<Pair<File, File>>,
    val freshTargets: List<File>,
    val stages: List<File>,
  )

  private data class CommitPlan(
    val stage: File,
    val target: File,
    val backup: File?,
  )

  fun readLibrary(performStaleSweep: Boolean = true): AddonLibrarySnapshot = withLibraryLock {
    recoverBeforeOperation(performStaleSweep)
    readLibraryIndex(requireAddonDirectories = true) ?: AddonLibrarySnapshot("empty", emptyList())
  }

  /**
   * Runs [block] while holding the repository-wide lock for this library root. Runtime payload
   * manifest and chunk reads use it so addon files cannot be renamed or deleted by a concurrent
   * import, removal or recovery pass while they are being read.
   */
  fun <T> withLibraryAccess(block: () -> T): T = withLibraryLock {
    recoverBeforeOperation(performStaleSweep = false)
    block()
  }

  private fun <T> withLibraryLock(block: () -> T): T =
    rootLocks.computeIfAbsent(rootKey) { ReentrantLock() }.withLock(block)

  /**
   * Performs the controlled startup recovery before the first operation on this root, and keeps the
   * historical recovery-on-read behavior for sweep-enabled reads. Runs under the repository lock,
   * so it can never race a mutation; the completion marker is only recorded after recovery
   * succeeds, so a failed recovery is retried by the next operation instead of being skipped.
   */
  private fun recoverBeforeOperation(performStaleSweep: Boolean) {
    val startupRecoveryPending = !startupRecoveryCompleted.get()
    // Always revisit transaction journals. A later operation may be retrying recovery after a
    // previous transaction cleanup failed; the per-instance marker only suppresses repeat stale
    // sweeps, not the inexpensive journal check.
    recoverPendingTransactions(performStaleSweep = performStaleSweep || startupRecoveryPending)
    startupRecoveryCompleted.set(true)
  }

  /**
   * Parses and validates the complete index. Recovery uses the same validation without requiring
   * addon directories to be in place, because a crash may have moved them into transaction backups.
   */
  private fun readLibraryIndex(requireAddonDirectories: Boolean): AddonLibrarySnapshot? {
    if (!indexFile.exists()) return null
    val properties = Properties().apply { FileInputStream(indexFile).use(::load) }
    val revision =
      properties.getProperty("revision")?.takeIf(::isSafeRevision)
        ?: throw IllegalStateException("Addon library index revision is invalid.")
    val count =
      properties.getProperty("count")?.toIntOrNull()
        ?: throw IllegalStateException("Addon library index is invalid.")
    require(count in 0..MAX_ADDON_LIBRARY_ITEMS) {
      "Addon library index contains too many entries."
    }
    val addons =
      (0 until count).map { index ->
        fun value(key: String): String =
          decode(
            properties.getProperty("addon.$index.$key")
              ?: throw IllegalStateException("Addon library index is missing $key."),
          )
        val folder = value("folder")
        require(isSafeFolder(folder)) { "Addon library contains an invalid folder name." }
        AddonLibraryItem(
          title = value("title"),
          folder = folder,
          version = value("version"),
          interfaceVersion = value("interface"),
          notes = value("notes"),
          dependencies =
            decodeList(
              properties.getProperty("addon.$index.dependencies")
                ?: throw IllegalStateException("Addon library dependencies are missing."),
            ),
          optionalDependencies =
            decodeList(
              properties.getProperty("addon.$index.optionalDependencies")
                ?: throw IllegalStateException("Addon library optional dependencies are missing."),
            ),
          sizeBytes =
            properties.getProperty("addon.$index.size")?.toLongOrNull()?.also {
              require(it in 0..AddonArchive.MAX_LIBRARY_BYTES) {
                "Addon library size is invalid."
              }
            } ?: throw IllegalStateException("Addon library size is invalid."),
          filesCount =
            properties.getProperty("addon.$index.files")?.toIntOrNull()?.also {
              require(it in 1..AddonArchive.MAX_ENTRIES) {
                "Addon library file count is invalid."
              }
            } ?: throw IllegalStateException("Addon library file count is invalid."),
          enabled =
            properties.getProperty("addon.$index.enabled")?.toBooleanStrictOrNull()
              ?: throw IllegalStateException("Addon library enabled state is invalid."),
          importedAt =
            properties.getProperty("addon.$index.importedAt")?.toLongOrNull()
              ?: throw IllegalStateException("Addon library import time is invalid."),
          warnings =
            decodeList(
              properties.getProperty("addon.$index.warnings")
                ?: throw IllegalStateException("Addon library warnings are missing."),
            ),
        )
      }
    require(addons.map { it.folder.lowercase(Locale.ROOT) }.distinct().size == addons.size) {
      "Addon library contains duplicate folder names."
    }
    require(addons.sumOf { it.sizeBytes } <= AddonArchive.MAX_LIBRARY_BYTES) {
      "Addon library exceeds 384 MiB."
    }
    if (requireAddonDirectories) {
      check(addons.all { addonDirectory(it.folder).isDirectory }) {
        "Addon library references missing addon files."
      }
    }
    return AddonLibrarySnapshot(revision, addons)
  }

  fun importZip(
    input: InputStream,
    compressedSize: Long?,
    onStage: (AddonImportStage) -> Unit = {},
    confirmReplacement: (List<String>) -> Boolean = { true },
  ): AddonLibrarySnapshot = input.use { source ->
    withLibraryLock {
      importZipLocked(source, compressedSize, onStage, confirmReplacement)
    }
  }

  private fun importZipLocked(
    input: InputStream,
    compressedSize: Long?,
    onStage: (AddonImportStage) -> Unit,
    confirmReplacement: (List<String>) -> Boolean,
  ): AddonLibrarySnapshot {
    recoverBeforeOperation(performStaleSweep = false)
    if (!root.exists() && !root.mkdirs()) {
      throw IllegalStateException("Could not create addon storage.")
    }
    val requiredStagingBytes = AddonArchive.MAX_ARCHIVE_BYTES + MIN_FREE_SPACE_RESERVE_BYTES
    require(availableStorageBytes() >= requiredStagingBytes) {
      "Not enough free storage to stage the selected archive."
    }
    val extraction = File(root, EXTRACT_PREFIX + UUID.randomUUID())
    return try {
      val imported =
        AddonArchive.importZip(
          input,
          compressedSize,
          extraction,
          beforeExtraction = { _ ->
            val requiredBytes = AddonArchive.MAX_EXPANDED_BYTES + MIN_FREE_SPACE_RESERVE_BYTES
            require(availableStorageBytes() >= requiredBytes) {
              "Not enough free storage to safely extract and stage imported addons."
            }
          },
          onStage = onStage,
        )
      require(imported.isNotEmpty()) { "The archive contains no addons." }

      // Mid-import index reads never run recovery: the in-flight extraction and staging belong to
      // this active transaction, and the repository lock already excludes every other operation.
      val current =
        readLibraryIndex(requireAddonDirectories = true)
          ?: AddonLibrarySnapshot("empty", emptyList())
      val replacing = imported.map { it.folder.lowercase(Locale.ROOT) }.toSet()
      val replacedFolders =
        current.addons.filter { it.folder.lowercase(Locale.ROOT) in replacing }.map { it.folder }
      if (replacedFolders.isNotEmpty() && !confirmReplacement(replacedFolders)) {
        throw CancellationException("Addon replacement was cancelled.")
      }
      val total =
        current.addons
          .filterNot { it.folder.lowercase(Locale.ROOT) in replacing }
          .sumOf { it.sizeBytes } + imported.sumOf { it.sizeBytes }
      require(total <= AddonArchive.MAX_LIBRARY_BYTES) {
        "Addon library exceeds 384 MiB."
      }
      val stagedBytes = imported.sumOf { it.sizeBytes }
      require(availableStorageBytes() >= stagedBytes + MIN_FREE_SPACE_RESERVE_BYTES) {
        "Not enough free storage to safely commit imported addons."
      }
      val projectedCount =
        current.addons.count { it.folder.lowercase(Locale.ROOT) !in replacing } + imported.size
      require(projectedCount <= MAX_ADDON_LIBRARY_ITEMS) {
        "Addon library contains too many entries."
      }
      onStage(AddonImportStage.COMMITTING)

      val existing = current.addons.associateBy { it.folder.lowercase(Locale.ROOT) }
      val nextItems =
        current.addons.filterNot { it.folder.lowercase(Locale.ROOT) in replacing }.toMutableList()

      val plans = mutableListOf<CommitPlan>()
      for (addon in imported) {
        require(isSafeFolder(addon.folder)) { "Invalid addon folder: ${addon.folder}" }
        val stage = File(root, STAGE_PREFIX + UUID.randomUUID())
        require(stage.mkdirs()) { "Could not prepare addon storage." }
        val previous = existing[addon.folder.lowercase(Locale.ROOT)]
        val targetFolderName = previous?.folder ?: addon.folder
        val target = File(root, targetFolderName)
        val backup =
          if (previous != null) {
            File(root, BACKUP_PREFIX + UUID.randomUUID())
          } else {
            null
          }
        plans += CommitPlan(stage, target, backup)
        for ((relativePath, sourceFile) in addon.files) {
          val destination = File(stage, relativePath)
          val canonicalRoot = stage.canonicalFile
          val canonicalDestination = destination.canonicalFile
          require(canonicalDestination.path.startsWith(canonicalRoot.path + File.separator)) {
            "Unsafe addon file path: $relativePath"
          }
          destination.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) {
              throw IllegalStateException("Could not create addon file directory.")
            }
          }
          FileInputStream(sourceFile).use { source ->
            FileOutputStream(destination).use { target -> source.copyTo(target) }
          }
        }
        nextItems +=
          AddonLibraryItem(
            title = addon.title,
            folder = targetFolderName,
            version = addon.version,
            interfaceVersion = addon.interfaceVersion,
            notes = addon.notes,
            dependencies = addon.dependencies,
            optionalDependencies = addon.optionalDependencies,
            sizeBytes = addon.sizeBytes,
            filesCount = addon.files.size,
            enabled = previous?.enabled ?: false,
            importedAt = System.currentTimeMillis(),
            warnings = addon.warnings,
          )
      }

      require(nextItems.size <= MAX_ADDON_LIBRARY_ITEMS) {
        "Addon library contains too many entries."
      }

      val newRevision = UUID.randomUUID().toString()
      val oldIndex = readLibraryIndex(requireAddonDirectories = false)
      val transaction = File(root, TXN_PREFIX + UUID.randomUUID())
      writeTransactionPlan(
        transaction = transaction,
        oldRevision = oldIndex?.revision,
        oldIndexMissing = oldIndex == null,
        newRevision = newRevision,
        backups = plans.mapNotNull { plan -> plan.backup?.let { plan.target.name to it.name } },
        freshTargets = plans.filter { it.backup == null }.map { it.target.name },
        stages = plans.map { it.stage.name },
      )
      try {
        for (plan in plans) {
          plan.backup?.let { backup ->
            check(plan.target.renameTo(backup)) {
              "Could not stage the existing addon for replacement."
            }
          }
          check(plan.stage.renameTo(plan.target)) { "Could not commit imported addon files." }
        }

        val snapshot = AddonLibrarySnapshot(newRevision, nextItems)
        writeIndex(snapshot)
        plans.forEach { plan ->
          plan.backup?.let { backup ->
            check(backup.deleteRecursively()) { "Could not finalize replaced addon files." }
          }
        }
        check(transaction.delete()) { "Could not finalize the addon transaction record." }
        onStage(AddonImportStage.COMPLETE)
        snapshot
      } catch (error: Exception) {
        try {
          recoverPendingTransactions(performStaleSweep = false)
        } catch (recoveryError: Exception) {
          throw IllegalStateException(
              "Addon transaction recovery is incomplete; transaction data was preserved.",
              error,
            )
            .also { it.addSuppressed(recoveryError) }
        }
        throw error
      } finally {
        // A still-present journal means recovery failed. Preserve every stage named by it.
        if (!transaction.exists()) plans.forEach { it.stage.deleteRecursively() }
      }
    } finally {
      extraction.deleteRecursively()
    }
  }

  fun setEnabled(folder: String, enabled: Boolean): AddonLibrarySnapshot = withLibraryLock {
    recoverBeforeOperation(performStaleSweep = false)
    require(isSafeFolder(folder)) { "Invalid addon folder." }
    val current =
      readLibraryIndex(requireAddonDirectories = true) ?: AddonLibrarySnapshot("empty", emptyList())
    require(current.addons.any { it.folder == folder }) { "Addon not found." }
    val next =
      current.copy(
        revision = UUID.randomUUID().toString(),
        addons = current.addons.map { if (it.folder == folder) it.copy(enabled = enabled) else it },
      )
    writeIndex(next)
    next
  }

  fun removeAddon(folder: String): AddonLibrarySnapshot = withLibraryLock {
    removeAddonLocked(folder)
  }

  private fun removeAddonLocked(folder: String): AddonLibrarySnapshot {
    recoverBeforeOperation(performStaleSweep = false)
    require(isSafeFolder(folder)) { "Invalid addon folder." }
    val current =
      readLibraryIndex(requireAddonDirectories = true) ?: AddonLibrarySnapshot("empty", emptyList())
    require(current.addons.any { it.folder == folder }) { "Addon not found." }
    val newRevision = UUID.randomUUID().toString()
    val next =
      current.copy(
        revision = newRevision,
        addons = current.addons.filterNot { it.folder == folder },
      )
    val target = addonDirectory(folder)
    val backup = File(root, REMOVE_PREFIX + UUID.randomUUID())
    val transaction = File(root, TXN_PREFIX + UUID.randomUUID())
    val oldIndex = readLibraryIndex(requireAddonDirectories = false)
    writeTransactionPlan(
      transaction = transaction,
      oldRevision = oldIndex?.revision,
      oldIndexMissing = oldIndex == null,
      newRevision = newRevision,
      backups = listOf(folder to backup.name),
      freshTargets = emptyList(),
      stages = emptyList(),
    )
    try {
      if (target.exists()) {
        check(target.renameTo(backup)) { "Could not stage addon files for removal." }
      }
      writeIndex(next)
    } catch (error: Exception) {
      try {
        recoverPendingTransactions(performStaleSweep = false)
      } catch (recoveryError: Exception) {
        throw IllegalStateException(
            "Addon removal recovery is incomplete; transaction data was preserved.",
            error,
          )
          .also { it.addSuppressed(recoveryError) }
      }
      throw error
    }
    // If cleanup fails, the journal remains and the next read safely finishes it.
    check(backup.deleteRecursively()) { "Could not finalize removed addon files." }
    check(transaction.delete()) { "Could not finalize the addon transaction record." }
    return next
  }

  fun exportZip(folder: String, output: OutputStream) = withLibraryLock {
    exportZipLocked(folder, output)
  }

  private fun exportZipLocked(folder: String, output: OutputStream) {
    recoverBeforeOperation(performStaleSweep = false)
    require(isSafeFolder(folder)) { "Invalid addon folder." }
    val addonDirectory = File(root, folder)
    require(addonDirectory.isDirectory) { "Addon not found." }
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
      val files =
        addonDirectory.walkTopDown().filter(File::isFile).sortedBy {
          it.relativeTo(addonDirectory).invariantSeparatorsPath
        }
      for (file in files) {
        val relative = file.relativeTo(addonDirectory).invariantSeparatorsPath
        AddonArchive.validateEntryPath(relative)
        zip.putNextEntry(ZipEntry("$folder/$relative"))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  fun addonDirectory(folder: String): File {
    require(isSafeFolder(folder)) { "Invalid addon folder." }
    return File(root, folder)
  }

  /**
   * Resolves crash-interrupted import/removal transactions before the index is read. Each
   * transaction record names its pre-assigned backup directories, affected folders and the index
   * revision it would commit; a committed transaction only finishes backup cleanup, an interrupted
   * one rolls back to the recorded previous state. Staging, extraction, source-archive, backup and
   * temporary-index leftovers are deleted only after every recorded transaction has been resolved;
   * an unresolvable record stops cleanup so no referenced data is discarded. The sweep is skipped
   * while an import is in flight so the active extraction directory is never treated as stale.
   */
  private fun recoverPendingTransactions(performStaleSweep: Boolean) {
    if (!root.isDirectory) return
    val pending =
      root.listFiles { file -> file.name.startsWith(TXN_PREFIX) }?.sortedBy { it.name } ?: return
    for (transaction in pending) {
      val plan = loadTransactionPlan(transaction)
      val index = readLibraryIndex(requireAddonDirectories = false)
      val committed = index?.revision == plan.newRevision
      val matchesOldState =
        if (plan.oldIndexMissing) index == null else index?.revision == plan.oldRevision
      if (!committed && !matchesOldState) {
        throw IllegalStateException(
          "Addon transaction index state is ambiguous; transaction data was preserved.",
        )
      }

      if (committed) {
        // A committed index is authoritative. Repeating backup/stage cleanup is safe.
        plan.backups.forEach { (_, backup) ->
          check(!backup.exists() || backup.deleteRecursively()) {
            "Could not finalize addon transaction backup."
          }
        }
      } else {
        // Rollback is replay-safe: once a backup has been renamed back, a later recovery pass
        // sees backup absent + target present and leaves the restored target untouched.
        for ((target, backup) in plan.backups.asReversed()) {
          if (backup.exists()) {
            if (target.exists()) {
              check(target.deleteRecursively()) { "Could not clear interrupted addon replacement." }
            }
            check(backup.renameTo(target)) { "Could not restore previous addon files." }
            afterBackupRestore?.invoke()
          } else {
            check(target.exists()) {
              "Addon transaction backup and restored target are both missing; data was preserved."
            }
          }
        }
        for (fresh in plan.freshTargets) {
          check(!fresh.exists() || fresh.deleteRecursively()) {
            "Could not remove an uncommitted addon folder."
          }
        }
      }
      plan.stages.forEach { stage ->
        check(!stage.exists() || stage.deleteRecursively()) {
          "Could not clean an addon transaction staging folder."
        }
      }
      check(transaction.delete()) { "Could not finalize the addon transaction record." }
    }
    if (!performStaleSweep) return
    root
      .listFiles { file ->
        file.name.startsWith(EXTRACT_PREFIX) ||
          file.name.startsWith(STAGE_PREFIX) ||
          file.name.startsWith(BACKUP_PREFIX) ||
          file.name.startsWith(REMOVE_PREFIX) ||
          file.name.startsWith(SOURCE_ARCHIVE_PREFIX) ||
          (file.name.startsWith(TEMP_INDEX_PREFIX) && file.name.endsWith(TEMP_INDEX_SUFFIX))
      }
      ?.forEach { it.deleteRecursively() }
  }

  private fun isSafeRevision(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))

  private fun resolveTransactionChild(name: String): File {
    require(name.isNotBlank() && File(name).name == name && '/' !in name && '\\' !in name) {
      "Addon transaction contains an unsafe path."
    }
    val rootPath = root.canonicalFile
    val child = File(rootPath, name)
    require(child.canonicalFile.parentFile == rootPath) {
      "Addon transaction path escapes addon storage."
    }
    return child
  }

  private fun writeTransactionPlan(
    transaction: File,
    oldRevision: String?,
    oldIndexMissing: Boolean,
    newRevision: String,
    backups: List<Pair<String, String>>,
    freshTargets: List<String>,
    stages: List<String>,
  ) {
    require(isSafeRevision(newRevision)) { "Invalid addon transaction revision." }
    require(oldIndexMissing == (oldRevision == null)) {
      "Addon transaction previous index state is invalid."
    }
    if (oldRevision != null)
      require(isSafeRevision(oldRevision)) {
        "Invalid previous addon transaction revision."
      }
    val properties = Properties()
    properties["formatVersion"] = "2"
    properties["oldIndexMissing"] = oldIndexMissing.toString()
    oldRevision?.let { properties["oldRevision"] = it }
    properties["newRevision"] = newRevision
    backups.forEachIndexed { index, (folder, backupName) ->
      require(isSafeFolder(folder)) { "Invalid addon transaction target." }
      require(backupName.startsWith(BACKUP_PREFIX) || backupName.startsWith(REMOVE_PREFIX)) {
        "Invalid addon transaction backup."
      }
      properties["backup.$index"] = backupName
      properties["target.$index"] = folder
    }
    freshTargets.forEachIndexed { index, folder ->
      require(isSafeFolder(folder)) { "Invalid fresh addon transaction target." }
      properties["fresh.$index"] = folder
    }
    stages.forEachIndexed { index, stage ->
      require(stage.startsWith(STAGE_PREFIX)) { "Invalid addon transaction stage." }
      properties["stage.$index"] = stage
    }
    FileOutputStream(transaction).use { output ->
      properties.store(output, "LastHavenPlus addon transaction")
      output.fd.sync()
    }
  }

  private fun loadTransactionPlan(transaction: File): TransactionPlan {
    require(transaction.name.startsWith(TXN_PREFIX)) { "Invalid addon transaction filename." }
    require(transaction.isFile && transaction.canonicalFile.parentFile == root.canonicalFile) {
      "Addon transaction record is not a regular file inside addon storage."
    }
    val properties = Properties()
    FileInputStream(transaction).use { properties.load(it) }
    require(properties.getProperty("formatVersion") == "2") {
      "Unsupported or malformed addon transaction record; data was preserved."
    }
    val newRevision =
      properties.getProperty("newRevision")?.takeIf(::isSafeRevision)
        ?: throw IllegalStateException("Addon transaction revision is invalid.")
    val oldIndexMissing =
      properties.getProperty("oldIndexMissing")?.toBooleanStrictOrNull()
        ?: throw IllegalStateException("Addon transaction previous index marker is invalid.")
    val oldRevision = properties.getProperty("oldRevision")
    require(oldIndexMissing == (oldRevision == null)) {
      "Addon transaction previous index record is inconsistent."
    }
    if (oldRevision != null)
      require(isSafeRevision(oldRevision)) {
        "Addon transaction previous revision is invalid."
      }

    val backupIndices = mutableSetOf<Int>()
    val targetIndices = mutableSetOf<Int>()
    val freshIndices = mutableSetOf<Int>()
    val stageIndices = mutableSetOf<Int>()
    for (key in properties.stringPropertyNames()) {
      when {
        key == "formatVersion" ||
          key == "oldIndexMissing" ||
          key == "oldRevision" ||
          key == "newRevision" -> Unit
        key.startsWith("backup.") -> {
          val index = key.removePrefix("backup.").toIntOrNull()
          require(index != null && index >= 0) { "Malformed addon transaction backup index." }
          backupIndices += index
        }
        key.startsWith("target.") -> {
          val index = key.removePrefix("target.").toIntOrNull()
          require(index != null && index >= 0) { "Malformed addon transaction target index." }
          targetIndices += index
        }
        key.startsWith("fresh.") -> {
          val index = key.removePrefix("fresh.").toIntOrNull()
          require(index != null && index >= 0) { "Malformed addon transaction fresh index." }
          freshIndices += index
        }
        key.startsWith("stage.") -> {
          val index = key.removePrefix("stage.").toIntOrNull()
          require(index != null && index >= 0) { "Malformed addon transaction stage index." }
          stageIndices += index
        }
        else -> throw IllegalStateException("Unknown addon transaction field; data was preserved.")
      }
    }
    fun requireContiguous(indices: Set<Int>, field: String) {
      require(indices == (0 until indices.size).toSet()) {
        "Malformed addon transaction $field list."
      }
    }
    require(backupIndices == targetIndices) {
      "Addon transaction backup/target pairs are incomplete."
    }
    requireContiguous(backupIndices, "backup")
    requireContiguous(freshIndices, "fresh")
    requireContiguous(stageIndices, "stage")

    val backups =
      backupIndices.sorted().map { index ->
        val folder =
          properties.getProperty("target.$index")?.takeIf(::isSafeFolder)
            ?: throw IllegalStateException("Addon transaction target path is invalid.")
        val backupName =
          properties.getProperty("backup.$index")
            ?: throw IllegalStateException("Addon transaction backup path is missing.")
        require(backupName.startsWith(BACKUP_PREFIX) || backupName.startsWith(REMOVE_PREFIX)) {
          "Addon transaction backup path is invalid."
        }
        resolveTransactionChild(folder) to resolveTransactionChild(backupName)
      }
    val freshTargets =
      freshIndices.sorted().map { index ->
        val name =
          properties.getProperty("fresh.$index")?.takeIf(::isSafeFolder)
            ?: throw IllegalStateException("Addon transaction fresh path is invalid.")
        resolveTransactionChild(name)
      }
    val stages =
      stageIndices.sorted().map { index ->
        val name =
          properties.getProperty("stage.$index")
            ?: throw IllegalStateException("Addon transaction stage path is missing.")
        require(name.startsWith(STAGE_PREFIX)) { "Addon transaction stage path is invalid." }
        resolveTransactionChild(name)
      }
    val targets =
      backups.map { it.first.name.lowercase(Locale.ROOT) } +
        freshTargets.map { it.name.lowercase(Locale.ROOT) }
    require(targets.distinct().size == targets.size) {
      "Addon transaction contains duplicate target folders."
    }
    val backupNames = backups.map { it.second.name }
    require(backupNames.distinct().size == backupNames.size) {
      "Addon transaction contains duplicate backup folders."
    }
    require(stages.map { it.name }.distinct().size == stages.size) {
      "Addon transaction contains duplicate staging folders."
    }
    return TransactionPlan(oldRevision, oldIndexMissing, newRevision, backups, freshTargets, stages)
  }

  private fun writeIndex(snapshot: AddonLibrarySnapshot) {
    val properties = Properties()
    properties["revision"] = snapshot.revision
    properties["count"] = snapshot.addons.size.toString()
    snapshot.addons.forEachIndexed { index, addon ->
      fun put(key: String, value: String) {
        properties["addon.$index.$key"] = encode(value)
      }
      put("title", addon.title)
      put("folder", addon.folder)
      put("version", addon.version)
      put("interface", addon.interfaceVersion)
      put("notes", addon.notes)
      properties["addon.$index.dependencies"] = encodeList(addon.dependencies)
      properties["addon.$index.optionalDependencies"] = encodeList(addon.optionalDependencies)
      properties["addon.$index.size"] = addon.sizeBytes.toString()
      properties["addon.$index.files"] = addon.filesCount.toString()
      properties["addon.$index.enabled"] = addon.enabled.toString()
      properties["addon.$index.importedAt"] = addon.importedAt.toString()
      properties["addon.$index.warnings"] = encodeList(addon.warnings)
    }

    beforeIndexWrite?.invoke()
    val temporary = File(root, TEMP_INDEX_PREFIX + UUID.randomUUID() + TEMP_INDEX_SUFFIX)
    try {
      FileOutputStream(temporary).use { output ->
        properties.store(output, "LastHavenPlus addon library")
        output.fd.sync()
      }
      try {
        Files.move(
          temporary.toPath(),
          indexFile.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      temporary.delete()
    }
  }

  private fun isSafeFolder(folder: String): Boolean =
    folder.matches(Regex("[A-Za-z0-9_-]+")) && !folder.startsWith("blizzard_", ignoreCase = true)

  private fun encode(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

  private fun decode(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

  private fun encodeList(values: List<String>): String = values.joinToString(".") { encode(it) }

  private fun decodeList(value: String): List<String> =
    if (value.isBlank()) emptyList() else value.split('.').map(::decode)
}
