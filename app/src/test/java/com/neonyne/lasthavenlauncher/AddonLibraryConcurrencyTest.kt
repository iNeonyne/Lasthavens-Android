package com.neonyne.lasthavenlauncher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies that addon library access is serialized behind one repository-wide lock (issue #227).
 *
 * The launcher uses separate [AddonLibraryRepository] instances for the bridge snapshot path and
 * the Addon Manager executor, so every test here exercises two instances over the same root. The
 * repository-wide lock must be shared by canonical root path for these guarantees to hold.
 *
 * These tests use real threads and real files on the JVM; physical ARM64 verification remains a
 * separate acceptance requirement.
 */
class AddonLibraryConcurrencyTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `concurrent import and bridge snapshot read never roll back the active transaction`() {
    val root = temporaryFolder.newFolder("concurrent-import-read")
    val managerRepository = AddonLibraryRepository(root)
    val bridgeRepository = AddonLibraryRepository(root)
    val firstArchive =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    managerRepository.importZip(ByteArrayInputStream(firstArchive), firstArchive.size.toLong())
    val archive =
      zipOf(
        "Sample/Sample.toc" to "## Version: 2\n",
        "Sample/main.lua" to "print('replacement')",
      )
    val confirmStarted = CountDownLatch(1)
    val releaseConfirm = CountDownLatch(1)
    val importResult = AtomicReference<AddonLibrarySnapshot?>()
    val importError = AtomicReference<Throwable?>()
    val importer = Thread {
      try {
        importResult.set(
          managerRepository.importZip(
            ByteArrayInputStream(archive),
            archive.size.toLong(),
            confirmReplacement = {
              confirmStarted.countDown()
              releaseConfirm.await(15, TimeUnit.SECONDS)
              true
            },
          ),
        )
      } catch (error: Throwable) {
        importError.set(error)
      }
    }

    importer.start()
    assertTrue(confirmStarted.await(15, TimeUnit.SECONDS))

    val readResult = AtomicReference<AddonLibrarySnapshot?>()
    val readError = AtomicReference<Throwable?>()
    val reader = Thread {
      try {
        readResult.set(bridgeRepository.readLibrary())
      } catch (error: Throwable) {
        readError.set(error)
      }
    }
    reader.start()

    releaseConfirm.countDown()
    importer.join(TimeUnit.SECONDS.toMillis(20))
    reader.join(TimeUnit.SECONDS.toMillis(20))

    assertFalse("import thread must finish", importer.isAlive)
    assertFalse("reader thread must finish", reader.isAlive)
    assertNull("import must not fail", importError.get())
    assertNull("snapshot read must not fail", readError.get())

    val imported = checkNotNull(importResult.get())
    val read = checkNotNull(readResult.get())
    assertEquals(listOf("Sample"), imported.addons.map { it.folder })
    assertEquals("2", imported.addons.single().version)
    assertEquals(
      "the bridge read must observe the committed import, not a rolled-back state",
      imported.revision,
      read.revision,
    )
    assertEquals("print('replacement')", File(root, "Sample/main.lua").readText())
    assertTrue(
      "no transaction, staging, extraction or backup leftovers may remain",
      root.listFiles().orEmpty().none { it.name.startsWith(".") },
    )
  }

  @Test
  fun `concurrent removal and bridge snapshot read are deterministic and safe`() {
    val root = temporaryFolder.newFolder("concurrent-remove-read")
    val managerRepository = AddonLibraryRepository(root)
    val bridgeRepository = AddonLibraryRepository(root)
    val archive =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('sample')",
      )
    managerRepository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
    val removeStarted = CountDownLatch(1)
    val removalResult = AtomicReference<AddonLibrarySnapshot?>()
    val removalError = AtomicReference<Throwable?>()
    val remover = Thread {
      try {
        removalResult.set(managerRepository.removeAddon("Sample"))
      } catch (error: Throwable) {
        removalError.set(error)
      } finally {
        removeStarted.countDown()
      }
    }
    val readResult = AtomicReference<AddonLibrarySnapshot?>()
    val readError = AtomicReference<Throwable?>()
    val reader = Thread {
      try {
        readResult.set(bridgeRepository.readLibrary())
      } catch (error: Throwable) {
        readError.set(error)
      }
    }

    remover.start()
    removeStarted.await()
    reader.start()
    remover.join(TimeUnit.SECONDS.toMillis(20))
    reader.join(TimeUnit.SECONDS.toMillis(20))

    assertFalse(remover.isAlive)
    assertFalse(reader.isAlive)
    assertNull(removalError.get())
    assertNull(readError.get())

    val removed = checkNotNull(removalResult.get())
    val read = checkNotNull(readResult.get())
    assertTrue(removed.addons.isEmpty())
    assertEquals(
      "the concurrent read must observe the committed removal",
      removed.revision,
      read.revision,
    )
    assertFalse(File(root, "Sample").exists())
    assertTrue(
      root.listFiles().orEmpty().none {
        it.name.startsWith(".remove-") || it.name.startsWith(".txn-")
      },
    )
  }

  @Test
  fun `concurrent export and bridge snapshot read complete without corrupting either`() {
    val root = temporaryFolder.newFolder("concurrent-export-read")
    val managerRepository = AddonLibraryRepository(root)
    val bridgeRepository = AddonLibraryRepository(root)
    val archive =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('sample')",
      )
    managerRepository.importZip(ByteArrayInputStream(archive), archive.size.toLong())

    val exportBytes = ByteArrayOutputStream()
    val exportError = AtomicReference<Throwable?>()
    val readResult = AtomicReference<AddonLibrarySnapshot?>()
    val readError = AtomicReference<Throwable?>()
    val exporter = Thread {
      try {
        managerRepository.exportZip("Sample", exportBytes)
      } catch (error: Throwable) {
        exportError.set(error)
      }
    }
    val reader = Thread {
      try {
        readResult.set(bridgeRepository.readLibrary())
      } catch (error: Throwable) {
        readError.set(error)
      }
    }

    exporter.start()
    reader.start()
    exporter.join(TimeUnit.SECONDS.toMillis(20))
    reader.join(TimeUnit.SECONDS.toMillis(20))

    assertFalse(exporter.isAlive)
    assertFalse(reader.isAlive)
    assertNull(exportError.get())
    assertNull(readError.get())

    val exportedEntries = mutableListOf<String>()
    ZipInputStream(exportBytes.toByteArray().inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        exportedEntries += entry.name
      }
    }
    assertEquals(listOf("Sample/Sample.toc", "Sample/main.lua"), exportedEntries)
    assertEquals(listOf("Sample"), checkNotNull(readResult.get()).addons.map { it.folder })
  }

  @Test
  fun `startup recovery rolls back an abandoned transaction before the first mutation`() {
    val root = temporaryFolder.newFolder("startup-recovery-first-mutation")
    val setupRepository = AddonLibraryRepository(root)
    val original =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    setupRepository.importZip(ByteArrayInputStream(original), original.size.toLong())
    val indexRevision = setupRepository.readLibrary().revision
    val backup = File(root, ".backup-crashed")
    check(File(root, "Sample").renameTo(backup))
    writeTransaction(
      root,
      ".txn-crashed",
      newRevision = "uncommitted-revision",
      oldRevision = indexRevision,
      backups = listOf("Sample" to backup.name),
    )

    // A new process would construct a fresh instance; the import is its first operation.
    val freshRepository = AddonLibraryRepository(root)
    val replacement =
      zipOf(
        "Sample/Sample.toc" to "## Version: 2\n",
        "Sample/main.lua" to "print('replacement')",
      )
    val imported =
      freshRepository.importZip(ByteArrayInputStream(replacement), replacement.size.toLong())

    assertEquals("2", imported.addons.single().version)
    assertEquals("print('replacement')", File(root, "Sample/main.lua").readText())
    assertFalse(backup.exists())
    assertFalse(File(root, ".txn-crashed").exists())
  }

  @Test
  fun `cancelled import while a reader waits keeps the last committed library`() {
    val root = temporaryFolder.newFolder("cancelled-import-reader")
    val managerRepository = AddonLibraryRepository(root)
    val bridgeRepository = AddonLibraryRepository(root)
    val original =
      zipOf(
        "Sample/Sample.toc" to "## Version: 1\n",
        "Sample/main.lua" to "print('original')",
      )
    managerRepository.importZip(ByteArrayInputStream(original), original.size.toLong())
    val committedRevision = managerRepository.readLibrary().revision

    val replacement =
      zipOf(
        "Sample/Sample.toc" to "## Version: 2\n",
        "Sample/main.lua" to "print('replacement')",
      )
    val confirmStarted = CountDownLatch(1)
    val releaseConfirm = CountDownLatch(1)
    val importError = AtomicReference<Throwable?>()
    val importer = Thread {
      try {
        managerRepository.importZip(
          ByteArrayInputStream(replacement),
          replacement.size.toLong(),
          confirmReplacement = {
            confirmStarted.countDown()
            releaseConfirm.await(15, TimeUnit.SECONDS)
            false
          },
        )
      } catch (error: Throwable) {
        importError.set(error)
      }
    }

    importer.start()
    assertTrue(confirmStarted.await(15, TimeUnit.SECONDS))
    val readResult = AtomicReference<AddonLibrarySnapshot?>()
    val readError = AtomicReference<Throwable?>()
    val reader = Thread {
      try {
        readResult.set(bridgeRepository.readLibrary())
      } catch (error: Throwable) {
        readError.set(error)
      }
    }
    reader.start()

    releaseConfirm.countDown()
    importer.join(TimeUnit.SECONDS.toMillis(20))
    reader.join(TimeUnit.SECONDS.toMillis(20))

    assertFalse(importer.isAlive)
    assertFalse(reader.isAlive)
    assertTrue(
      "the cancelled import must surface as a cancellation",
      importError.get() is java.util.concurrent.CancellationException,
    )
    assertNull(readError.get())
    val read = checkNotNull(readResult.get())
    assertEquals(
      "the reader must still see the last committed library",
      committedRevision,
      read.revision,
    )
    assertEquals("print('original')", File(root, "Sample/main.lua").readText())
    assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".") })
  }

  @Test
  fun `first enable remove and export operations recover abandoned transactions`() {
    fun interruptedRoot(name: String): File {
      val root = temporaryFolder.newFolder(name)
      val setupRepository = AddonLibraryRepository(root)
      val archive =
        zipOf(
          "Sample/Sample.toc" to "## Version: 1\n",
          "Sample/main.lua" to "print('sample')",
        )
      setupRepository.importZip(ByteArrayInputStream(archive), archive.size.toLong())
      val previousRevision = setupRepository.readLibrary().revision
      val backup = File(root, ".backup-crashed")
      check(File(root, "Sample").renameTo(backup))
      writeTransaction(
        root,
        ".txn-crashed",
        newRevision = "crashed-revision",
        oldRevision = previousRevision,
        backups = listOf("Sample" to backup.name),
      )
      return root
    }

    val enableRoot = interruptedRoot("first-enable-recovery")
    val enabled = AddonLibraryRepository(enableRoot).setEnabled("Sample", true)
    assertTrue(enabled.addons.single().enabled)
    assertEquals("print('sample')", File(enableRoot, "Sample/main.lua").readText())
    assertFalse(File(enableRoot, ".txn-crashed").exists())

    val removeRoot = interruptedRoot("first-remove-recovery")
    val removed = AddonLibraryRepository(removeRoot).removeAddon("Sample")
    assertTrue(removed.addons.isEmpty())
    assertFalse(File(removeRoot, "Sample").exists())
    assertFalse(File(removeRoot, ".txn-crashed").exists())

    val exportRoot = interruptedRoot("first-export-recovery")
    val output = ByteArrayOutputStream()
    AddonLibraryRepository(exportRoot).exportZip("Sample", output)
    val entries = mutableListOf<String>()
    ZipInputStream(output.toByteArray().inputStream()).use { zip ->
      while (true) entries += (zip.nextEntry?.name ?: break)
    }
    assertEquals(listOf("Sample/Sample.toc", "Sample/main.lua"), entries)
    assertTrue(File(exportRoot, "Sample/main.lua").isFile)
    assertFalse(File(exportRoot, ".txn-crashed").exists())
  }

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

  private fun writeTransaction(
    root: File,
    name: String,
    newRevision: String,
    oldRevision: String? = "old-revision",
    oldIndexMissing: Boolean = false,
    backups: List<Pair<String, String>> = emptyList(),
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
    FileOutputStream(File(root, name)).use {
      properties.store(it, "LastHavenPlus addon transaction")
    }
  }
}
