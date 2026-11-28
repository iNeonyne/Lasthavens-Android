package com.neonyne.lasthavenlauncher

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRuntimePayloadProviderTest {
  private fun item(folder: String, directory: File, enabled: Boolean = true): AddonLibraryItem {
    val files = directory.walkTopDown().filter(File::isFile).toList()
    return AddonLibraryItem(
      title = folder,
      folder = folder,
      version = "",
      interfaceVersion = "30300",
      notes = "",
      dependencies = emptyList(),
      sizeBytes = files.sumOf { it.length() },
      filesCount = files.size,
      enabled = enabled,
      importedAt = 1L,
      warnings = emptyList(),
    )
  }

  private fun withRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("addon-runtime-payload").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun writeAddon(root: File, folder: String, files: Map<String, ByteArray>): File {
    val directory = File(root, folder)
    check(directory.mkdirs())
    for ((path, bytes) in files) {
      val file = File(directory, path)
      val parent = file.parentFile
      check(parent == null || parent.mkdirs() || parent.isDirectory)
      file.writeBytes(bytes)
    }
    return directory
  }

  @Test
  fun manifestIncludesEveryResourceAndReadsBoundedChunks() = withRoot { root ->
    val imageBytes =
      ByteArray(AddonRuntimePayloadProvider.MAX_FILE_CHUNK_BYTES + 17) { (it % 251).toByte() }
    val directory =
      writeAddon(
        root,
        "UnitFrame",
        mapOf(
          "UnitFrame.toc" to "## Interface: 30300\ninit.lua\n".toByteArray(),
          "init.lua" to "print('ready')".toByteArray(),
          "Textures/portrait.png" to imageBytes,
        ),
      )
    val snapshot = AddonLibrarySnapshot("library-revision", listOf(item("UnitFrame", directory)))
    val provider = AddonRuntimePayloadProvider(AddonLibraryRepository(root))
    val manifest = provider.buildManifest(snapshot)

    assertEquals(listOf("UnitFrame"), manifest.addonFolders)
    assertEquals(
      listOf("Textures/portrait.png", "UnitFrame.toc", "init.lua"),
      manifest.files.map { it.path },
    )
    val expectedImageBytes =
      manifest.totalBytes - "## Interface: 30300\ninit.lua\n".length - "print('ready')".length
    assertEquals(imageBytes.size.toLong(), expectedImageBytes)
    assertEquals(1, manifest.pageCount)
    assertEquals(3, provider.page(manifest, 0).size)

    val first = provider.readChunk(manifest, "UnitFrame", "Textures/portrait.png", 0)
    val second =
      provider.readChunk(
        manifest,
        "UnitFrame",
        "Textures/portrait.png",
        AddonRuntimePayloadProvider.MAX_FILE_CHUNK_BYTES.toLong(),
      )
    assertEquals(AddonRuntimePayloadProvider.MAX_FILE_CHUNK_BYTES, first.size)
    assertArrayEquals(imageBytes.copyOfRange(0, first.size), first)
    assertArrayEquals(imageBytes.copyOfRange(first.size, imageBytes.size), second)
  }

  @Test
  fun disabledAndDependencyBlockedAddonsAreNotIncluded() = withRoot { root ->
    val disabledDirectory = writeAddon(root, "Disabled", mapOf("Disabled.toc" to byteArrayOf(1)))
    val missingDirectory = writeAddon(root, "MissingDep", mapOf("MissingDep.toc" to byteArrayOf(2)))
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(
          item("Disabled", disabledDirectory, enabled = false),
          item("MissingDep", missingDirectory).copy(dependencies = listOf("NotInstalled")),
        ),
      )
    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)
    assertTrue(manifest.addonFolders.isEmpty())
    assertTrue(manifest.files.isEmpty())
    assertEquals(0L, manifest.totalBytes)
    assertEquals(0, manifest.pageCount)
  }

  @Test
  fun isolatesFilesLargerThanRuntimeFileLimitAndKeepsValidAddons() = withRoot { root ->
    val largeDirectory = writeAddon(root, "LargeFile", mapOf("LargeFile.toc" to byteArrayOf(1)))
    val large = File(largeDirectory, "large.bin")
    RandomAccessFile(large, "rw").use {
      it.setLength(AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES + 1)
    }
    val exactDirectory =
      writeAddon(
        root,
        "ExactLimit",
        mapOf(
          "ExactLimit.toc" to ByteArray(AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES.toInt()),
        ),
      )
    val boundaryDirectory = writeAddon(root, "OverLimit", mapOf("OverLimit.toc" to byteArrayOf(1)))
    RandomAccessFile(File(boundaryDirectory, "over.bin"), "rw").use {
      it.setLength(AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES + 1)
    }
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(
          item("ExactLimit", exactDirectory),
          item("OverLimit", boundaryDirectory),
          item("LargeFile", largeDirectory),
        ),
      )
    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)

    assertEquals(listOf("ExactLimit"), manifest.addonFolders)
    assertTrue(manifest.files.all { it.folder == "ExactLimit" })
    assertTrue(
      manifest.files.any {
        it.path == "ExactLimit.toc" &&
          it.sizeBytes == AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES
      },
    )
  }

  @Test
  fun rejectsLibraryIndexMismatchAndUnsafePaths() = withRoot { root ->
    val directory = writeAddon(root, "UnitFrame", mapOf("UnitFrame.toc" to byteArrayOf(1)))
    val wrongCount = item("UnitFrame", directory).copy(filesCount = 2)
    assertThrows(AddonRuntimePayloadException::class.java) {
      AddonRuntimePayloadProvider(AddonLibraryRepository(root))
        .buildManifest(AddonLibrarySnapshot("revision", listOf(wrongCount)))
    }

    val unsafeName = File(directory, "bad\\\\escape.lua")
    unsafeName.writeBytes(byteArrayOf(2))
    val withUnsafePath = item("UnitFrame", directory)
    val unsafeError =
      assertThrows(AddonRuntimePayloadException::class.java) {
        AddonRuntimePayloadProvider(AddonLibraryRepository(root))
          .buildManifest(AddonLibrarySnapshot("revision", listOf(withUnsafePath)))
      }
    assertEquals("invalidPath", unsafeError.code)
    unsafeName.delete()

    val outside = File(root, "outside.lua")
    outside.writeBytes(byteArrayOf(3))
    val symlink = File(directory, "escape.lua")
    Files.createSymbolicLink(symlink.toPath(), outside.toPath())
    val withSymlink = item("UnitFrame", directory)
    val symlinkError =
      assertThrows(AddonRuntimePayloadException::class.java) {
        AddonRuntimePayloadProvider(AddonLibraryRepository(root))
          .buildManifest(AddonLibrarySnapshot("revision", listOf(withSymlink)))
      }
    assertEquals("invalidPath", symlinkError.code)
  }

  @Test
  fun omittingAnOversizedRequiredAddonAlsoOmitsItsDependents() = withRoot { root ->
    val oversizedDirectory =
      writeAddon(
        root,
        "LargeDependency",
        mapOf("LargeDependency.toc" to byteArrayOf(1)),
      )
    RandomAccessFile(File(oversizedDirectory, "oversized.lua"), "rw").use {
      it.setLength(AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES + 1)
    }
    val dependentDirectory =
      writeAddon(
        root,
        "Dependent",
        mapOf("Dependent.toc" to byteArrayOf(2), "dependent.lua" to byteArrayOf(3)),
      )
    val healthyDirectory =
      writeAddon(root, "Independent", mapOf("Independent.toc" to byteArrayOf(4)))
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(
          item("LargeDependency", oversizedDirectory),
          item("Dependent", dependentDirectory).copy(dependencies = listOf("LargeDependency")),
          item("Independent", healthyDirectory),
        ),
      )

    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)

    assertEquals(listOf("Independent"), manifest.addonFolders)
    assertTrue(manifest.files.all { it.folder == "Independent" })
  }

  @Test
  fun isolatesFileCountOverflowAndKeepsValidAddons() = withRoot { root ->
    val overflowDirectory = File(root, "ManyFiles")
    assertTrue(overflowDirectory.mkdirs())
    for (index in 0..AddonRuntimePayloadProvider.MAX_RUNTIME_FILES) {
      File(overflowDirectory, "file-$index.bin").writeBytes(byteArrayOf())
    }
    val healthyDirectory = writeAddon(root, "Healthy", mapOf("Healthy.toc" to byteArrayOf(1)))
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(
          AddonLibraryItem(
            title = "ManyFiles",
            folder = "ManyFiles",
            version = "",
            interfaceVersion = "",
            notes = "",
            dependencies = emptyList(),
            sizeBytes = 0,
            filesCount = AddonRuntimePayloadProvider.MAX_RUNTIME_FILES + 1,
            enabled = true,
            importedAt = 0,
            warnings = emptyList(),
          ),
          item("Healthy", healthyDirectory),
        ),
      )
    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)

    assertEquals(listOf("Healthy"), manifest.addonFolders)
  }

  @Test
  fun rejectsCaseInsensitiveResourcePathCollisions() = withRoot { root ->
    val directory =
      writeAddon(
        root,
        "UnitFrame",
        mapOf("Init.lua" to byteArrayOf(1), "init.lua" to byteArrayOf(2)),
      )
    val snapshot = AddonLibrarySnapshot("revision", listOf(item("UnitFrame", directory)))
    val error =
      assertThrows(AddonRuntimePayloadException::class.java) {
        AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)
      }
    assertEquals("invalidPath", error.code)
  }

  @Test
  fun isolatesExcessiveEmptyDirectoryEntriesAndKeepsValidAddons() = withRoot { root ->
    val directory = File(root, "EmptyDirectories")
    assertTrue(directory.mkdirs())
    for (index in 0..AddonRuntimePayloadProvider.MAX_RUNTIME_ENTRIES_PER_ADDON) {
      assertTrue(File(directory, "empty-$index").mkdir())
    }
    val healthyDirectory = writeAddon(root, "Healthy", mapOf("Healthy.toc" to byteArrayOf(1)))
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(
          AddonLibraryItem(
            title = "EmptyDirectories",
            folder = "EmptyDirectories",
            version = "",
            interfaceVersion = "",
            notes = "",
            dependencies = emptyList(),
            sizeBytes = 0,
            filesCount = 0,
            enabled = true,
            importedAt = 0,
            warnings = emptyList(),
          ),
          item("Healthy", healthyDirectory),
        ),
      )
    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)

    assertEquals(listOf("Healthy"), manifest.addonFolders)
  }

  @Test
  fun isolatesTheAddonThatExhaustsTheRuntimeBundleBudget() = withRoot { root ->
    val nearHalf = AddonRuntimePayloadProvider.MAX_RUNTIME_BUNDLE_BYTES / 2 + 1
    val exactFileLimit = AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES.toInt()
    val firstDirectory =
      writeAddon(
        root,
        "First",
        mapOf(
          "First.toc" to byteArrayOf(1),
          "first.bin" to ByteArray(exactFileLimit),
        ),
      )
    val secondDirectory =
      writeAddon(
        root,
        "Second",
        mapOf(
          "Second.toc" to byteArrayOf(1),
          "second.bin" to ByteArray(exactFileLimit),
        ),
      )
    val snapshot =
      AddonLibrarySnapshot(
        "revision",
        listOf(item("First", firstDirectory), item("Second", secondDirectory)),
      )
    val manifest = AddonRuntimePayloadProvider(AddonLibraryRepository(root)).buildManifest(snapshot)

    assertEquals(listOf("First"), manifest.addonFolders)
    assertEquals(nearHalf, manifest.totalBytes)
  }

  @Test
  fun entryPointsListsValidatedTocLoadFilesPerAddon() = withRoot { root ->
    val coreDirectory =
        "DepCore",
          "DepCore.toc" to "## Interface: 30300\ncore.js\nmissing.lua\n".toByteArray(),
          "core.js" to byteArrayOf(1),
    val uiDirectory =
        "UnitFrame",
          "UnitFrame.toc" to "## Interface: 30300\ninit.js\nskin.css\n".toByteArray(),
          "init.js" to byteArrayOf(1),
          "skin.css" to byteArrayOf(2),
        listOf(item("DepCore", coreDirectory), item("UnitFrame", uiDirectory)),
    val provider = AddonRuntimePayloadProvider(AddonLibraryRepository(root))
    val manifest = provider.buildManifest(snapshot)
    val entryPoints = provider.entryPoints(manifest)
    assertEquals(listOf("core.js"), entryPoints["DepCore"])
    assertEquals(listOf("init.js", "skin.css"), entryPoints["UnitFrame"])
  fun entryPointsTreatsMissingOrMalformedTocAsEmptyWithoutFailing() = withRoot { root ->
    val bareDirectory = writeAddon(root, "BareAddon", mapOf("core.js" to byteArrayOf(1)))
    val garbageDirectory =
        "GarbageAddon",
          "GarbageAddon.toc" to "# only comments\n\n../escape.js\n".toByteArray(),
        listOf(item("BareAddon", bareDirectory), item("GarbageAddon", garbageDirectory)),
    assertEquals(emptyList<String>(), entryPoints["BareAddon"])
    assertEquals(emptyList<String>(), entryPoints["GarbageAddon"])
  fun revisionChangesWhenTheResourceInventoryChanges() = withRoot { root ->
    val directory = writeAddon(root, "UnitFrame", mapOf("init.lua" to byteArrayOf(1)))
    val provider = AddonRuntimePayloadProvider(AddonLibraryRepository(root))
    val first =
      provider.buildManifest(
        AddonLibrarySnapshot("same-index-revision", listOf(item("UnitFrame", directory))),
      )

    File(directory, "Textures/icon.blp").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(2))
    }
    val second =
      provider.buildManifest(
        AddonLibrarySnapshot("same-index-revision", listOf(item("UnitFrame", directory))),
      )

    assertEquals(first.sourceRevision, second.sourceRevision)
    assertNotEquals(first.revision, second.revision)
  }

  @Test
  fun rejectsUnsafeFolders() = withRoot { root ->
    val unsafeFolder = File(root, "unit.frame")
    assertTrue(unsafeFolder.mkdirs())
    assertThrows(IllegalArgumentException::class.java) {
      AddonRuntimePayloadProvider(AddonLibraryRepository(root))
        .buildManifest(
          AddonLibrarySnapshot(
            "revision",
            listOf(
              AddonLibraryItem(
                title = "unsafe",
                folder = "unit.frame",
                version = "",
                interfaceVersion = "",
                notes = "",
                dependencies = emptyList(),
                sizeBytes = 0,
                filesCount = 0,
                enabled = true,
                importedAt = 0,
                warnings = emptyList(),
              ),
            ),
          ),
        )
    }
  }
}
