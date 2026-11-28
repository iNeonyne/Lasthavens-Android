package com.neonyne.lasthavenlauncher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonArchiveTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun importsAddonFolderAndReadsTocMetadata() {
    val archive =
      zipOf(
        "Example/Example.toc" to
          "## Title: Example Addon\n## Version: 1.2.3\n## Interface: 30300\n## Notes: Test notes\n## Dependencies: LibOne, LibTwo\n",
        "Example/main.lua" to "print('example')",
        "Example/Media/icon.blp" to "image",
      )

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder(),
      )

    assertEquals(1, imported.size)
    assertEquals("Example Addon", imported.single().title)
    assertEquals("Example", imported.single().folder)
    assertEquals("1.2.3", imported.single().version)
    assertEquals("30300", imported.single().interfaceVersion)
    assertEquals(listOf("LibOne", "LibTwo"), imported.single().dependencies)
    assertEquals(setOf("Example.toc", "main.lua", "Media/icon.blp"), imported.single().files.keys)
    assertTrue(imported.single().warnings.isEmpty())
  }

  @Test
  fun warnsWhenAddonFileCountExceedsRuntimeLimit() {
    val files = mutableListOf("Sample/Sample.toc" to "## Interface: 30300\n")
    for (index in 0..AddonLimits.MAX_RUNTIME_FILES) {
      files += "Sample/file-$index.lua" to "return $index"
    }
    val archive = zipOf(*files.toTypedArray())

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("runtime-file-count"),
      )

    assertTrue(imported.single().warnings.any { it.contains("more than 1024 files") })
  }

  @Test
  fun warnsWhenDirectoryAndFileEntryCountExceedsRuntimeLimit() {
    val files = mutableListOf("Sample/Sample.toc" to "## Interface: 30300\n")
    for (index in 0 until AddonLimits.MAX_RUNTIME_FILES - 1) {
      files += "Sample/group-$index/one/two/three/file-$index.lua" to ""
    }
    val archive = zipOf(*files.toTypedArray())

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("runtime-entry-count"),
      )

    assertTrue(imported.single().warnings.any { it.contains("runtime file and directory entry") })
  }

  @Test
  fun runsStoragePreflightBeforeWritingExpandedEntries() {
    val toc = "## Interface: 30300\n"
    val archive = zipOf("Sample/Sample.toc" to toc, "Sample/main.lua" to "print('sample')")
    val extraction = temporaryFolder.newFolder("preflight")

    assertThrows(IllegalStateException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        extraction,
      ) { expandedBytes ->
        assertEquals(
          toc.toByteArray(Charsets.UTF_8).size + "print('sample')".length.toLong(),
          expandedBytes,
        )
        assertTrue(extraction.listFiles().orEmpty().isEmpty())
        throw IllegalStateException("forced low-storage preflight")
      }
    }

    assertTrue(!extraction.exists())
  }

  @Test
  fun parsesOptionalDependenciesWithoutTreatingThemAsRequired() {
    val archive =
      zipOf(
        "Sample/Sample.toc" to
          "## Dependencies: RequiredOne, RequiredTwo\n## OptionalDeps: OptionalOne, OptionalTwo\n",
      )

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("optional-dependencies"),
      )

    assertEquals(listOf("RequiredOne", "RequiredTwo"), imported.single().dependencies)
    assertEquals(listOf("OptionalOne", "OptionalTwo"), imported.single().optionalDependencies)
  }

  @Test
  fun rejectsOversizedTOCMetadataAndDependencyLists() {
    val oversizedNotes =
      zipOf("Sample/Sample.toc" to "## Notes: ${"x".repeat(AddonArchive.MAX_NOTES_CHARS + 1)}\n")
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(oversizedNotes),
        oversizedNotes.size.toLong(),
        temporaryFolder.newFolder("oversized-notes"),
      )
    }

    val excessiveDependencies =
      (0..AddonArchive.MAX_DEPENDENCIES).joinToString(",") { "Dependency$it" }
    val excessiveDependencyArchive =
      zipOf("Sample/Sample.toc" to "## Dependencies: $excessiveDependencies\n")
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(excessiveDependencyArchive),
        excessiveDependencyArchive.size.toLong(),
        temporaryFolder.newFolder("excessive-dependencies"),
      )
    }
  }

  @Test
  fun importsFlatAddonContents() {
    val archive =
      zipOf(
        "Flat.toc" to "## Title: Flat addon\n## Interface: 30300\n",
        "Scripts/init.lua" to "print('flat')",
      )

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder(),
      )

    assertEquals("Flat", imported.single().folder)
    assertTrue(imported.single().files.containsKey("Scripts/init.lua"))
  }

  @Test
  fun reportsUnsupportedInterfaceAsWarning() {
    val archive = zipOf("Old/Old.toc" to "## Interface: 30400\n")

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder(),
      )

    assertEquals(1, imported.single().warnings.size)
  }

  @Test
  fun importsMultipleTopLevelAddonsFromOneArchive() {
    val archive =
      zipOf(
        "Alpha/Alpha.toc" to "## Interface: 30300\n",
        "Alpha/main.lua" to "print('alpha')",
        "Beta/Beta.toc" to "## Interface: 30300\n",
        "Beta/main.lua" to "print('beta')",
      )

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("multiple-addons"),
      )

    assertEquals(setOf("Alpha", "Beta"), imported.map { it.folder }.toSet())
  }

  @Test
  fun rejectsDuplicateCentralDirectoryRecords() {
    val archive =
      zipOf(
        "Sample/Sample.toc" to "## Interface: 30300\\n",
        "Sample/main.lua" to "print('sample')",
      )
    val duplicated = duplicateFirstCentralDirectoryRecord(archive)

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(duplicated),
        duplicated.size.toLong(),
        temporaryFolder.newFolder("duplicate-central-record"),
      )
    }
  }

  @Test
  fun importsAddonsNestedUnderCommonPackagingDirectory() {
    val archive =
      zipOf(
        "Release/Alpha/Alpha.toc" to "## Interface: 30300\\n",
        "Release/Alpha/main.lua" to "print('alpha')",
        "Release/Beta/Beta.toc" to "## Interface: 30300\\n",
        "Release/Beta/main.lua" to "print('beta')",
      )

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("nested-package"),
      )

    assertEquals(setOf("Alpha", "Beta"), imported.map { it.folder }.toSet())
    assertEquals(setOf("main.lua", "Alpha.toc"), imported.first { it.folder == "Alpha" }.files.keys)
    assertEquals(setOf("main.lua", "Beta.toc"), imported.first { it.folder == "Beta" }.files.keys)
  }

  @Test
  fun rejectsArchivesWithTooManyAddonRoots() {
    val files =
      Array(AddonArchive.MAX_ADDONS_PER_ARCHIVE + 1) { index ->
        "Addon$index/Addon$index.toc" to "## Interface: 30300\\n"
      }
    val archive = zipOf(*files)

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("too-many-addons"),
      )
    }
  }

  @Test
  fun rejectsMultipleFlatAddonsWithoutFolderBoundaries() {
    val archive =
      zipOf(
        "Alpha.toc" to "## Interface: 30300\\n",
        "Alpha.lua" to "print('alpha')",
        "Beta.toc" to "## Interface: 30300\\n",
        "Beta.lua" to "print('beta')",
      )

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("ambiguous-flat-addons"),
      )
    }
  }

  @Test
  fun rejectsCaseInsensitiveDuplicatePaths() {
    val archive =
      zipOf(
        "Sample/Sample.toc" to "## Interface: 30300\n",
        "Sample/main.lua" to "print('first')",
        "sample/MAIN.lua" to "print('second')",
      )

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("duplicate-paths"),
      )
    }
  }

  @Test
  fun rejectsPathTraversalAndWindowsAbsolutePaths() {
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.validateEntryPath("../outside.lua")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.validateEntryPath("C:\\outside.lua")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.validateEntryPath("folder/../../outside.lua")
    }
  }

  @Test
  fun rejectsReservedAndInvalidAddonFolderNames() {
    val archive = zipOf("Blizzard_Test/Blizzard_Test.toc" to "## Title: Blizzard\n")
    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder(),
      )
    }
  }

  @Test
  fun importsWhenDocumentProviderDoesNotReportSize() {
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\n")

    val imported =
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        null,
        temporaryFolder.newFolder("unknown-size"),
      )

    assertEquals("Sample", imported.single().folder)
  }

  @Test
  fun rejectsDocumentSizeMismatch() {
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")
    val extraction = temporaryFolder.newFolder("size-mismatch")

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong() - 1,
        extraction,
      )
    }
    assertTrue(!extraction.exists())
  }

  @Test
  fun rejectsCrcMismatchFromCentralDirectory() {
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")
    val corrupted = archive.copyOf()
    val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
    val centralDirectory =
      corrupted.indices.first { index ->
        index + signature.size <= corrupted.size &&
          signature.indices.all { offset -> corrupted[index + offset] == signature[offset] }
      }
    corrupted[centralDirectory + 16] = (corrupted[centralDirectory + 16].toInt() xor 1).toByte()

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(corrupted),
        corrupted.size.toLong(),
        temporaryFolder.newFolder("crc-mismatch"),
      )
    }
  }

  @Test
  fun rejectsTruncatedZipCentralDirectory() {
    val archive = zipOf("Sample/Sample.toc" to "## Interface: 30300\\n")
    val truncated = archive.copyOf(archive.size - 8)

    assertThrows(Exception::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(truncated),
        truncated.size.toLong(),
        temporaryFolder.newFolder("truncated"),
      )
    }
  }

  @Test
  fun rejectsArchivesWithTooManyEntries() {
    val entries =
      (0..AddonArchive.MAX_ENTRIES)
        .map { index -> "Pack/file" + index.toString().padStart(5, '0') to "" }
        .toTypedArray()
    val archive = zipOf(*entries)

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("too-many-entries"),
      )
    }
  }

  @Test
  fun rejectsExpandedSizeBombBeforeAnyExtraction() {
    val archive =
      inflateCentralDirectorySizes(
        zipOf(
          "Pack/Pack.toc" to "## Interface: 30300\n",
          "Pack/a.lua" to "",
          "Pack/b.lua" to "",
          "Pack/c.lua" to "",
          "Pack/d.lua" to "",
        ),
        declaredSizeBytes = 60L * 1024 * 1024,
      )

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(
        ByteArrayInputStream(archive),
        archive.size.toLong(),
        temporaryFolder.newFolder("size-bomb"),
      ) {
        error("Extraction preflight must not be reached for a size-bomb archive")
      }
    }
  }

  @Test
  fun rejectsEntriesWhoseActualSizeExceedsDeclaredCentralDirectorySize() {
    val archive =
      inflateCentralDirectorySizes(
        zipOf(
          "Sample/Sample.toc" to "## Interface: 30300\n",
          "Sample/main.lua" to "print('sample')",
        ),
        declaredSizeBytes = 4L,
      )

    val error =
      assertThrows(IllegalArgumentException::class.java) {
        AddonArchive.importZip(
          ByteArrayInputStream(archive),
          archive.size.toLong(),
          temporaryFolder.newFolder("size-mismatch"),
        )
      }
    assertTrue(error.message.orEmpty().startsWith("File integrity check failed"))
  }

  @Test
  fun copiesStreamsThatReturnZeroByteReadsWithoutSpinning() {
    val data = ByteArray(70_000) { (it % 251).toByte() }
    val input = ZeroThenDelegateInputStream(ByteArrayInputStream(data), zeroReadsBeforeData = 3)
    val output = ByteArrayOutputStream()

    AddonArchive.copyWithProgressGuarantee(input, output, ByteArray(DEFAULT_BUFFER_SIZE)) { _, _ ->
    }

    assertTrue(data.contentEquals(output.toByteArray()))
  }

  @Test
  fun importsArchivesFromStreamsThatReturnZeroByteReads() {
    val archive =
      zipOf("Sample/Sample.toc" to "## Interface: 30300\n", "Sample/main.lua" to "print('sample')")
    val input = ZeroThenDelegateInputStream(ByteArrayInputStream(archive), zeroReadsBeforeData = 2)

    val imported =
      AddonArchive.importZip(input, null, temporaryFolder.newFolder("zero-read-archive"))

    assertEquals(listOf("Sample"), imported.map { it.folder })
  }

  @Test
  fun rejectsArchiveAboveCompressedSizeLimitBeforeReading() {
    val input =
      object : InputStream() {
        override fun read(): Int = error("Input must not be read")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
          error("Input must not be read")
      }

    assertThrows(IllegalArgumentException::class.java) {
      AddonArchive.importZip(input, AddonArchive.MAX_ARCHIVE_BYTES + 1, temporaryFolder.newFolder())
    }
  }

  private class ZeroThenDelegateInputStream(
    private val delegate: ByteArrayInputStream,
    private val zeroReadsBeforeData: Int,
  ) : InputStream() {
    var zeroReads = 0

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (zeroReads++ < zeroReadsBeforeData) return 0
      return delegate.read(buffer, offset, length)
    }
  }

  private fun inflateCentralDirectorySizes(archive: ByteArray, declaredSizeBytes: Long): ByteArray {
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

  private fun duplicateFirstCentralDirectoryRecord(archive: ByteArray): ByteArray {
    fun intAt(offset: Int): Int =
      (archive[offset].toInt() and 0xff) or
        ((archive[offset + 1].toInt() and 0xff) shl 8) or
        ((archive[offset + 2].toInt() and 0xff) shl 16) or
        ((archive[offset + 3].toInt() and 0xff) shl 24)

    fun shortAt(offset: Int): Int =
      (archive[offset].toInt() and 0xff) or ((archive[offset + 1].toInt() and 0xff) shl 8)

    val eocdSignature = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    val eocd =
      archive.indices.last { index ->
        index + eocdSignature.size <= archive.size &&
          eocdSignature.indices.all { archive[index + it] == eocdSignature[it] }
      }
    val entryCount = shortAt(eocd + 10)
    val centralSize = intAt(eocd + 12)
    val centralOffset = intAt(eocd + 16)
    val firstRecordLength =
      46 + shortAt(centralOffset + 28) + shortAt(centralOffset + 30) + shortAt(centralOffset + 32)
    val result = ByteArray(archive.size + firstRecordLength)
    archive.copyInto(result, 0, 0, eocd)
    archive.copyInto(result, eocd, centralOffset, centralOffset + firstRecordLength)
    archive.copyInto(result, eocd + firstRecordLength, eocd, archive.size)
    val newEocd = eocd + firstRecordLength
    fun putShort(offset: Int, value: Int) {
      result[offset] = value.toByte()
      result[offset + 1] = (value ushr 8).toByte()
    }
    fun putInt(offset: Int, value: Int) {
      result[offset] = value.toByte()
      result[offset + 1] = (value ushr 8).toByte()
      result[offset + 2] = (value ushr 16).toByte()
      result[offset + 3] = (value ushr 24).toByte()
    }
    putShort(newEocd + 10, entryCount + 1)
    putShort(newEocd + 8, shortAt(eocd + 8) + 1)
    putInt(newEocd + 12, centralSize + firstRecordLength)
    return result
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
}
