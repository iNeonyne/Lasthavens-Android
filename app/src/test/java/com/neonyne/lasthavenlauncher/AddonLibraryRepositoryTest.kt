package com.neonyne.lasthavenlauncher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonLibraryRepositoryTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun importDefaultsDisabledAndSupportsEnableExportAndRemove() {
    val repository = AddonLibraryRepository(temporaryFolder.newFolder("addons"))
    val archive =
      zipOf(
        "Sample/Sample.toc" to
          "## Title: Sample Addon\n## Interface: 30300\n## OptionalDeps: OptionalOne, OptionalTwo\n",
        "Sample/main.lua" to "print('sample')",
      )

    val imported = repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())

    assertEquals(1, imported.addons.size)
    assertFalse(imported.addons.single().enabled)
    assertEquals(2, imported.addons.single().filesCount)
    assertEquals(
      listOf("OptionalOne", "OptionalTwo"),
      imported.addons.single().optionalDependencies,
    )

    val enabled = repository.setEnabled("Sample", true)
    assertTrue(enabled.addons.single().enabled)
    assertTrue(repository.readLibrary().addons.single().enabled)
    assertEquals(
      listOf("OptionalOne", "OptionalTwo"),
      repository.readLibrary().addons.single().optionalDependencies,
    )

    val exported = ByteArrayOutputStream()
    repository.exportZip("Sample", exported)
    val exportedAddons =
      AddonArchive.importZip(
        ByteArrayInputStream(exported.toByteArray()),
        exported.size().toLong(),
        temporaryFolder.newFolder("export-check"),
      )
    assertEquals("Sample", exportedAddons.single().folder)

    val removed = repository.removeAddon("Sample")
    assertTrue(removed.addons.isEmpty())
    assertFalse(repository.addonDirectory("Sample").exists())
  }

  @Test
  fun replacingAddonPreservesItsEnabledState() {
    val repository = AddonLibraryRepository(temporaryFolder.newFolder("addons"))
    val first = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(first), first.size.toLong())
    repository.setEnabled("Sample", true)

    val replacement = zipOf("Sample/Sample.toc" to "## Version: 2\n")
    val result = repository.importZip(ByteArrayInputStream(replacement), replacement.size.toLong())

    assertTrue(result.addons.single().enabled)
    assertEquals("2", result.addons.single().version)
  }

  @Test
  fun replacementCancellationPreservesExistingAddonAndReportsExactFolder() {
    val repository = AddonLibraryRepository(temporaryFolder.newFolder("cancel-replacement"))
    val original = zipOf("Sample/Sample.toc" to "## Version: 1\n", "Sample/main.lua" to "old")
    repository.importZip(ByteArrayInputStream(original), original.size.toLong())
    repository.setEnabled("Sample", true)

    val replacement = zipOf("Sample/Sample.toc" to "## Version: 2\n", "Sample/main.lua" to "new")
    var requestedFolders = emptyList<String>()

    assertThrows(CancellationException::class.java) {
      repository.importZip(
        ByteArrayInputStream(replacement),
        replacement.size.toLong(),
        confirmReplacement = { folders ->
          requestedFolders = folders
          false
        },
      )
    }

    assertEquals(listOf("Sample"), requestedFolders)
    val unchanged = repository.readLibrary().addons.single()
    assertEquals("1", unchanged.version)
    assertTrue(unchanged.enabled)
    assertEquals("old", File(repository.addonDirectory("Sample"), "main.lua").readText())
  }

  @Test
  fun libraryRejectsIndexEntriesWithoutBackingFiles() {
    val repository = AddonLibraryRepository(temporaryFolder.newFolder("missing-files"))
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    assertTrue(repository.addonDirectory("Sample").deleteRecursively())

    assertThrows(IllegalStateException::class.java) { repository.readLibrary() }
  }

  @Test
  fun removeRejectsUnknownAddonWithoutChangingLibrary() {
    val repository = AddonLibraryRepository(temporaryFolder.newFolder("unknown-remove"))
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())

    assertThrows(IllegalArgumentException::class.java) { repository.removeAddon("Missing") }
    assertEquals(listOf("Sample"), repository.readLibrary().addons.map { it.folder })
  }

  @Test
  fun rejectsImportWhenThereIsNotEnoughSpaceForCommitStaging() {
    val root = temporaryFolder.newFolder("low-storage")
    val repository = AddonLibraryRepository(root, availableStorageBytes = { 0L })
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")

    assertThrows(IllegalArgumentException::class.java) {
      repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    }

    assertTrue(root.listFiles().orEmpty().isEmpty())
    assertTrue(repository.readLibrary().addons.isEmpty())
  }

  @Test
  fun rejectsFalselySmallReportedSizeWhenStorageCannotCoverWorstCase() {
    val root = temporaryFolder.newFolder("untrusted-size-low-storage")
    val repository = AddonLibraryRepository(root, availableStorageBytes = { 128L })
    val unreadable =
      object : InputStream() {
        override fun read(): Int = error("Input must not be read before the staging preflight")
      }

    assertThrows(IllegalArgumentException::class.java) { repository.importZip(unreadable, 64L) }

    assertTrue(root.listFiles().orEmpty().isEmpty())
    assertTrue(repository.readLibrary().addons.isEmpty())
  }

  @Test
  fun rejectsImportThatWouldExceedTheLibraryEntryLimit() {
    val root = temporaryFolder.newFolder("entry-limit")
    writeLibraryIndex(root, 10_000)
    val repository = AddonLibraryRepository(root)
    assertEquals(10_000, repository.readLibrary().addons.size)
    val revisionBefore = repository.readLibrary().revision
    val archive = zipOf("NewAddon/NewAddon.toc" to "## Interface: 30300\n")

    assertThrows(IllegalArgumentException::class.java) {
      repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    }

    assertEquals(revisionBefore, repository.readLibrary().revision)
    assertEquals(10_000, repository.readLibrary().addons.size)
    assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".stage-") })
    assertTrue(root.listFiles().orEmpty().none { it.name == "NewAddon" })
  }

  @Test
  fun caseOnlyReplacementReusesTheExistingFolderWithoutOrphans() {
    val root = temporaryFolder.newFolder("case-replace")
    val repository = AddonLibraryRepository(root)
    val original =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    repository.importZip(ByteArrayInputStream(original), original.size.toLong())

    val replacement =
      zipOf(
        "sample/sample.toc" to "## Version: 2\n",
        "sample/main.lua" to "print('replacement')",
      )
    val result = repository.importZip(ByteArrayInputStream(replacement), replacement.size.toLong())

    assertEquals(listOf("Sample"), result.addons.map { it.folder })
    assertEquals("2", result.addons.single().version)
    assertFalse(File(root, "sample").exists())
    assertEquals("print('replacement')", File(root, "Sample/main.lua").readText())
    assertEquals(1, repository.readLibrary().addons.size)
  }

  @Test
  fun abortsForgedDeclaredSizeArchiveBeforeExtractingWhenStorageCannotCoverWorstCase() {
    val root = temporaryFolder.newFolder("forged-declared-size")
    val repository = AddonLibraryRepository(root, availableStorageBytes = { 200L * 1024 * 1024 })
    val archive =
      forgeDeclaredEntrySizes(
        zipOf(
          "Sample/Sample.toc" to "## Interface: 30300\n",
          "Sample/main.lua" to "x".repeat(5 * 1024 * 1024),
        ),
        declaredSizeBytes = 4L,
      )

    val error =
      assertThrows(IllegalArgumentException::class.java) {
        repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
      }

    assertEquals(
      "Not enough free storage to safely extract and stage imported addons.",
      error.message,
    )
    assertTrue(root.listFiles().orEmpty().isEmpty())
    assertTrue(repository.readLibrary().addons.isEmpty())
  }

  @Test
  fun recoveryRestoresPreviousStateWhenReplacementCrashedAfterBackupRename() {
    val root = temporaryFolder.newFolder("crash-replace")
    val repository = AddonLibraryRepository(root)
    val original =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    repository.importZip(ByteArrayInputStream(original), original.size.toLong())
    val indexRevision = repository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = indexRevision,
      newRevision = "uncommitted-revision",
      backups = listOf("Sample" to backup.name),
    )

    val recovered = repository.readLibrary()

    assertEquals(indexRevision, recovered.revision)
    assertEquals("1", recovered.addons.single().version)
    assertEquals("print('original')", File(root, "Sample/main.lua").readText())
    assertFalse(backup.exists())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryRemovesFreshTargetWhenImportCrashedBeforeIndexCommit() {
    val root = temporaryFolder.newFolder("crash-fresh")
    val repository = AddonLibraryRepository(root)
    val archive = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val indexRevision = repository.readLibrary().revision
    val fresh = File(root, "NewAddon")
    check(fresh.mkdirs())
    File(fresh, "NewAddon.toc").writeText("## Version: 2\n")
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = indexRevision,
      newRevision = "uncommitted-revision",
      fresh = listOf("NewAddon"),
    )

    val recovered = repository.readLibrary()

    assertEquals(indexRevision, recovered.revision)
    assertEquals(listOf("Sample"), recovered.addons.map { it.folder })
    assertFalse(fresh.exists())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryFinishesCleanupWhenCrashHappenedAfterIndexCommit() {
    val root = temporaryFolder.newFolder("crash-committed")
    val repository = AddonLibraryRepository(root)
    val first = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(first), first.size.toLong())
    File(root, "Sample/main.lua").writeText("print('replacement')")
    val committedRevision = repository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(backup.mkdirs())
    File(backup, "stale.txt").writeText("old")
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = "previous-revision",
      newRevision = committedRevision,
      backups = listOf("Sample" to backup.name),
    )

    val recovered = repository.readLibrary()

    assertEquals(committedRevision, recovered.revision)
    assertEquals("1", recovered.addons.single().version)
    assertEquals("print('replacement')", File(root, "Sample/main.lua").readText())
    assertFalse(backup.exists())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryRestoresAddonWhenRemovalCrashedAfterBackupRename() {
    val root = temporaryFolder.newFolder("crash-remove")
    val repository = AddonLibraryRepository(root)
    val archive =
      zipOf("Sample/Sample.toc" to "## Version: 1\n", "Sample/main.lua" to "print('kept')")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val oldRevision = repository.readLibrary().revision
    val backup = File(root, ".remove-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = oldRevision,
      newRevision = "uncommitted-revision",
      backups = listOf("Sample" to backup.name),
    )

    val recovered = repository.readLibrary()

    assertEquals(listOf("Sample"), recovered.addons.map { it.folder })
    assertEquals("print('kept')", File(root, "Sample/main.lua").readText())
    assertFalse(backup.exists())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryCanBeRepeatedAfterCrashImmediatelyFollowingBackupRestore() {
    val root = temporaryFolder.newFolder("recovery-repeat")
    val original = zipOf("Sample/Sample.toc" to "## Version: 1\n", "Sample/main.lua" to "old")
    val initialRepository = AddonLibraryRepository(root)
    initialRepository.importZip(ByteArrayInputStream(original), original.size.toLong())
    val oldRevision = initialRepository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = oldRevision,
      newRevision = "uncommitted-revision",
      backups = listOf("Sample" to backup.name),
    )

    val crashingRepository =
      AddonLibraryRepository(root, afterBackupRestore = { throw IOException("simulated crash") })
    assertThrows(IOException::class.java) { crashingRepository.readLibrary() }
    assertFalse(backup.exists())
    assertTrue(File(root, "Sample/main.lua").exists())
    assertTrue(File(root, ".txn-crashed").exists())

    val recovered = AddonLibraryRepository(root).readLibrary()

    assertEquals(oldRevision, recovered.revision)
    assertEquals("1", recovered.addons.single().version)
    assertEquals("old", File(root, "Sample/main.lua").readText())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryFailsClosedWhenIndexRevisionMatchesNeitherJournalState() {
    val root = temporaryFolder.newFolder("recovery-mismatched-index")
    val repository = AddonLibraryRepository(root)
    val archive = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val oldRevision = repository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = oldRevision,
      newRevision = "uncommitted-revision",
      backups = listOf("Sample" to backup.name),
    )
    writeLibraryIndex(root, 0)

    assertThrows(IllegalStateException::class.java) { repository.readLibrary() }

    assertTrue(backup.exists())
    assertFalse(File(root, "Sample").exists())
    assertTrue(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryFailsClosedAndPreservesDataWhenIndexIsMalformed() {
    val root = temporaryFolder.newFolder("recovery-malformed-index")
    val repository = AddonLibraryRepository(root)
    val archive = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val oldRevision = repository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      oldRevision = oldRevision,
      newRevision = "uncommitted-revision",
      backups = listOf("Sample" to backup.name),
    )
    File(root, "library.properties").writeText("revision=\ncount=not-a-number\n")

    assertThrows(IllegalStateException::class.java) { repository.readLibrary() }

    assertTrue(backup.exists())
    assertFalse(File(root, "Sample").exists())
    assertTrue(File(root, ".txn-crashed").exists())
  }

  @Test
  fun recoveryRejectsMalformedJournalPathsBeforeResolvingAnyPath() {
    val root = temporaryFolder.newFolder("recovery-malformed-journal")
    val repository = AddonLibraryRepository(root)
    val archive = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val oldRevision = repository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    val transaction =
      Properties().apply {
        this["formatVersion"] = "2"
        this["oldIndexMissing"] = "false"
        this["oldRevision"] = oldRevision
        this["newRevision"] = "uncommitted-revision"
        this["backup.0"] = backup.name
        this["target.0"] = "../outside"
      }
    FileOutputStream(File(root, ".txn-malformed")).use {
      transaction.store(it, "malformed test transaction")
    }
    val outside = File(root.parentFile, "outside")
    outside.writeText("keep")

    assertThrows(IllegalStateException::class.java) { repository.readLibrary() }

    assertTrue(backup.exists())
    assertTrue(File(root, ".txn-malformed").exists())
    assertEquals("keep", outside.readText())
  }

  @Test
  fun recoveryRollsBackFreshAddonWhenOriginalIndexWasAbsent() {
    val root = temporaryFolder.newFolder("recovery-empty-index")
    val fresh = File(root, "NewAddon")
    assertTrue(fresh.mkdirs())
    File(fresh, "NewAddon.toc").writeText("## Version: 1\n")
    writeTransaction(
      root,
      ".txn-empty",
      oldIndexMissing = true,
      oldRevision = null,
      newRevision = "uncommitted-revision",
      fresh = listOf("NewAddon"),
    )

    val recovered = AddonLibraryRepository(root).readLibrary()

    assertEquals(emptyList<AddonLibraryItem>(), recovered.addons)
    assertFalse(fresh.exists())
    assertFalse(File(root, ".txn-empty").exists())
  }

  @Test
  fun recoverySweepsStaleTemporaryArtifacts() {
    val root = temporaryFolder.newFolder("crash-sweep")
    val repository = AddonLibraryRepository(root)
    val archive = zipOf("Sample/Sample.toc" to "## Version: 1\n")
    repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val extraction = File(root, ".extract-stale")
    val stage = File(root, ".stage-stale")
    val orphanBackup = File(root, ".backup-orphan")
    val sourceArchive = File(root, "addon-source-stale.zip")
    val tempIndex = File(root, ".library-stale.tmp")
    extraction.mkdirs()
    stage.mkdirs()
    orphanBackup.mkdirs()
    sourceArchive.writeText("zip")
    tempIndex.writeText("index")

    val recovered = repository.readLibrary()

    assertEquals(listOf("Sample"), recovered.addons.map { it.folder })
    assertFalse(extraction.exists())
    assertFalse(stage.exists())
    assertFalse(orphanBackup.exists())
    assertFalse(sourceArchive.exists())
    assertFalse(tempIndex.exists())
  }

  @Test
  fun rejectsUnknownSizeArchiveWhenStorageCannotCoverWorstCase() {
    val root = temporaryFolder.newFolder("unknown-size-low-storage")
    val repository = AddonLibraryRepository(root, availableStorageBytes = { 0L })
    val unreadable =
      object : InputStream() {
        override fun read(): Int = error("Input must not be read before the staging preflight")
      }

    assertThrows(IllegalArgumentException::class.java) { repository.importZip(unreadable, null) }

    assertTrue(root.listFiles().orEmpty().isEmpty())
    assertTrue(repository.readLibrary().addons.isEmpty())
  }

  @Test
  fun restoresExistingAddonWhenIndexCommitFails() {
    val root = temporaryFolder.newFolder("rollback")
    val originalRepository = AddonLibraryRepository(root)
    val original =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    originalRepository.importZip(ByteArrayInputStream(original), original.size.toLong())

    val replacement =
      zipOf(
        "Sample/Sample.toc" to "## Version: 2\n",
        "Sample/main.lua" to "print('replacement')",
      )
    val failingRepository =
      AddonLibraryRepository(
        root,
        beforeIndexWrite = { throw IOException("forced index failure") },
      )

    assertThrows(IOException::class.java) {
      failingRepository.importZip(ByteArrayInputStream(replacement), replacement.size.toLong())
    }

    assertEquals("1", originalRepository.readLibrary().addons.single().version)
    assertEquals("print('original')", File(root, "Sample/main.lua").readText())
    assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".backup-") })
  }

  private fun forgeDeclaredEntrySizes(archive: ByteArray, declaredSizeBytes: Long): ByteArray {
    val centralSignature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
    val result = archive.copyOf()
    var offset = 0
    while (offset + 4 <= result.size) {
      if (centralSignature.indices.all { result[offset + it] == centralSignature[it] }) {
        for (byteIndex in 0 until 4) {
          result[offset + 24 + byteIndex] = (declaredSizeBytes ushr (8 * byteIndex)).toByte()
        }
        offset += 46
      } else {
        offset += 1
      }
    }
    return result
  }

  private fun writeTransaction(
    root: File,
    name: String,
    newRevision: String,
    oldRevision: String? = "old-revision",
    oldIndexMissing: Boolean = false,
    backups: List<Pair<String, String>> = emptyList(),
    fresh: List<String> = emptyList(),
    stages: List<String> = emptyList(),
  ) {
    val properties = Properties()
    properties["formatVersion"] = "2"
    properties["oldIndexMissing"] = oldIndexMissing.toString()
    oldRevision?.let { properties["oldRevision"] = it }
    properties["newRevision"] = newRevision
    backups.forEachIndexed { index, (folder, backupName) ->
      properties["backup.$index"] = backupName
      properties["target.$index"] = folder
    }
    fresh.forEachIndexed { index, folder -> properties["fresh.$index"] = folder }
    stages.forEachIndexed { index, folder -> properties["stage.$index"] = folder }
    FileOutputStream(File(root, name)).use {
      properties.store(it, "LastHavenPlus addon transaction")
    }
  }

  private fun writeLibraryIndex(root: File, count: Int) {
    val properties = Properties()
    properties["revision"] = "limit-revision"
    properties["count"] = count.toString()
    for (index in 0 until count) {
      val folder = "Addon" + index.toString().padStart(4, '0')
      val addonFolder = File(root, folder)
      check(addonFolder.mkdirs())
      File(addonFolder, "$folder.toc").writeText("## Interface: 30300\n")
      properties["addon.$index.title"] = encode("Addon $index")
      properties["addon.$index.folder"] = encode(folder)
      properties["addon.$index.version"] = encode("1")
      properties["addon.$index.interface"] = encode("30300")
      properties["addon.$index.notes"] = encode("")
      properties["addon.$index.dependencies"] = ""
      properties["addon.$index.optionalDependencies"] = ""
      properties["addon.$index.size"] = "10"
      properties["addon.$index.files"] = "1"
      properties["addon.$index.enabled"] = "false"
      properties["addon.$index.importedAt"] = "0"
      properties["addon.$index.warnings"] = ""
    }
    FileOutputStream(File(root, "library.properties")).use { properties.store(it, null) }
  }

  private fun encode(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

  private fun zipOf(vararg files: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, content) in files) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }

  private fun zipOfBytes(vararg files: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, content) in files) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }

  @Test
  fun importFlagsFilesAndBundlesExceedingTheRuntimeLimitsWithoutRejectingTheArchive() {
    val root = temporaryFolder.newFolder("runtime-limit-warnings")
    val repository = AddonLibraryRepository(root)
    val oversizedFile =
      ByteArray(AddonLimits.MAX_RUNTIME_BUNDLE_BYTES.toInt() + 1) { (it % 251).toByte() }
    val archive =
      zipOfBytes(
        "Sample/Sample.toc" to "## Version: 1\n".toByteArray(Charsets.UTF_8),
        "Sample/big.bin" to oversizedFile,
      )

    val imported = repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())

    val warnings = imported.addons.single().warnings
    assertTrue(
      warnings.any { it.contains("big.bin") && it.contains("8 MiB") },
    )
    assertTrue(
      warnings.any { it.contains("16 MiB") },
    )
    assertEquals(1, repository.readLibrary().addons.size)
  }

  @Test
  fun importDoesNotFlagFilesWithinTheRuntimeLimits() {
    val root = temporaryFolder.newFolder("runtime-limit-clean")
    val repository = AddonLibraryRepository(root)
    val boundaryFile = ByteArray(AddonLimits.MAX_RUNTIME_FILE_BYTES.toInt()) { (it % 251).toByte() }
    val archive =
      zipOfBytes(
        "Sample/Sample.toc" to "## Version: 1\n".toByteArray(Charsets.UTF_8),
        "Sample/boundary.bin" to boundaryFile,
      )

    val imported = repository.importZip(ByteArrayInputStream(archive), archive.size.toLong())

    assertTrue(imported.addons.single().warnings.isEmpty())
  }
}
