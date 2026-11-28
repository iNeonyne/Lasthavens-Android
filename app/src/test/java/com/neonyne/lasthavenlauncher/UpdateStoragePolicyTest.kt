package com.neonyne.lasthavenlauncher

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStoragePolicyTest {
  private val mib = 1024L * 1024
  private val apkSize = 100 * mib
  private val patchSize = 25 * mib

  @Test
  fun acceptsExactBudgetForFullAndDifferentialStaging() {
    UpdateStoragePolicy.requireSpace(116 * mib, apkSize)
    UpdateStoragePolicy.requireSpace(141 * mib, apkSize, patchSize)
  }

  @Test
  fun insufficientPatchSpaceFallsBackToFullWithItsOwnBudget() {
    val available = 116 * mib
    val selected =
      UpdateFallback.withFullFallback(
        preferDelta = true,
        deltaAttempt = {
          UpdateStoragePolicy.requireSpace(available, apkSize, patchSize)
          "patch"
        },
        fullAttempt = {
          UpdateStoragePolicy.requireSpace(available, apkSize)
          "full"
        },
      )
    assertEquals("full", selected)
  }

  @Test
  fun insufficientFullSpaceReportsHowMuchToFree() {
    val failure =
      assertThrows(IllegalStateException::class.java) {
        UpdateStoragePolicy.requireSpace(116 * mib - 1, apkSize)
      }
    assertTrue(failure.message.orEmpty().contains("Free at least 1 MiB"))
  }

  @Test
  fun unavailableStorageMetadataReportsHowToRetry() {
    val failure =
      assertThrows(IllegalStateException::class.java) {
        UpdateStoragePolicy.requireSpace(null, apkSize)
      }
    assertTrue(failure.message.orEmpty().contains("Check device storage in Settings"))
  }

  @Test
  fun storageExhaustionAfterPreflightCleansPartialAndPreservesVerifiedApk() {
    val directory = Files.createTempDirectory("lasthaven-update-storage").toFile()
    try {
      val verified =
        File(directory, "LastHavenPlus-update-1-12345678-1234-1234-1234-123456789abc.apk")
      verified.writeText("verified APK")
      val abandoned = File(directory, "old.apk.part").apply { writeText("partial") }
      UpdateFileCleanup.removeObsoleteFiles(directory, keepFinalizedApks = true)
      assertFalse(abandoned.exists())
      UpdateStoragePolicy.requireSpace(116 * mib, apkSize)

      val staged = File(directory, "new.apk.part")
      val noSpace = IOException("No space left on device")
      val failure =
        assertThrows(IOException::class.java) {
          UpdateDownloadPolicy.copyAndVerify(
            input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            destination = staged,
            expectedSize = 3,
            maximumBytes = 3,
            expectedSha256 = "unused",
            onBytesCopied = { throw noSpace },
          )
        }

      assertSame(noSpace, failure)
      assertFalse(staged.exists())
      assertEquals("verified APK", verified.readText())
    } finally {
      directory.deleteRecursively()
    }
  }
}
