package com.neonyne.lasthavenlauncher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BsdiffPatchTest {
  @Test
  fun reconstructsExactOutputFromBsdiff40Patch() {
    val directory = Files.createTempDirectory("bsdiff-test").toFile()
    try {
      val oldBytes = "abcXYZ".toByteArray(Charsets.UTF_8)
      val expected = "abc123XYZ!".toByteArray(Charsets.UTF_8)
      val oldFile = File(directory, "old.apk").apply { writeBytes(oldBytes) }
      val patchFile = File(directory, "update.bsdiff")
      val outputFile = File(directory, "new.apk")

      val control = compressed {
        writeOffset(3)
        writeOffset(3)
        writeOffset(0)
        writeOffset(3)
        writeOffset(1)
        writeOffset(0)
      }
      val differences = compressed {
        write(ByteArray(6))
      }
      val extra = compressed {
        write("123!".toByteArray(Charsets.UTF_8))
      }

      val header =
        ByteArrayOutputStream()
          .apply {
            write("BSDIFF40".toByteArray(Charsets.US_ASCII))
            writeOffset(control.size.toLong())
            writeOffset(differences.size.toLong())
            writeOffset(expected.size.toLong())
          }
          .toByteArray()

      patchFile.writeBytes(header + control + differences + extra)
      val outputSize = BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)

      assertEquals(expected.size.toLong(), outputSize)
      assertArrayEquals(expected, outputFile.readBytes())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun acceptsZeroOutputControlTupleThatSeeksWithinBaseApk() {
    val directory = Files.createTempDirectory("bsdiff-zero-output-seek").toFile()
    try {
      val oldBytes = "skipXYZ".toByteArray(Charsets.UTF_8)
      val expected = "XYZ".toByteArray(Charsets.UTF_8)
      val oldFile = File(directory, "old.apk").apply { writeBytes(oldBytes) }
      val patchFile = File(directory, "update.bsdiff")
      val outputFile = File(directory, "new.apk")

      // BSDIFF40 permits a tuple with no output and a non-zero old-file seek.
      val control = compressed {
        // Several seek-only tuples must not be rejected by an output-size-only bound.
        repeat(4) {
          writeOffset(0)
          writeOffset(0)
          writeOffset(1)
        }
        writeOffset(3)
        writeOffset(0)
        writeOffset(0)
      }
      val differences = compressed { write(ByteArray(3)) }
      val extra = compressed {}
      val header =
        ByteArrayOutputStream()
          .apply {
            write("BSDIFF40".toByteArray(Charsets.US_ASCII))
            writeOffset(control.size.toLong())
            writeOffset(differences.size.toLong())
            writeOffset(expected.size.toLong())
          }
          .toByteArray()

      patchFile.writeBytes(header + control + differences + extra)
      val outputSize = BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)

      assertEquals(expected.size.toLong(), outputSize)
      assertArrayEquals(expected, outputFile.readBytes())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun rejectsTruncatedHeaderAndRemovesPartialOutput() {
    val directory = Files.createTempDirectory("bsdiff-truncated-header").toFile()
    try {
      val oldFile = File(directory, "old.apk").apply { writeBytes("old".toByteArray()) }
      val patchFile =
        File(directory, "broken.bsdiff").apply {
          writeBytes("BSDIFF40".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        }
      val outputFile = File(directory, "new.apk").apply { writeText("stale output") }

      try {
        BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)
        throw AssertionError("Expected truncated patch to be rejected")
      } catch (expected: IllegalArgumentException) {
        assertEquals(false, outputFile.exists())
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun rejectsUnsupportedPatchMagicAndRemovesPartialOutput() {
    val directory = Files.createTempDirectory("bsdiff-invalid-magic").toFile()
    try {
      val oldFile = File(directory, "old.apk").apply { writeBytes("old".toByteArray()) }
      val patchFile = File(directory, "broken.bsdiff").apply { writeBytes(ByteArray(32)) }
      val outputFile = File(directory, "new.apk").apply { writeText("stale output") }

      try {
        BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)
        throw AssertionError("Expected unsupported patch format to be rejected")
      } catch (expected: IllegalArgumentException) {
        assertEquals(false, outputFile.exists())
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun rejectsPatchWhoseDeclaredOutputExceedsLimitBeforeDecompression() {
    val directory = Files.createTempDirectory("bsdiff-output-limit").toFile()
    try {
      val oldFile = File(directory, "old.apk").apply { writeBytes("old".toByteArray()) }
      val header =
        ByteArrayOutputStream()
          .apply {
            write("BSDIFF40".toByteArray(Charsets.US_ASCII))
            writeOffset(1)
            writeOffset(1)
            writeOffset(2048)
          }
          .toByteArray()
      val patchFile =
        File(directory, "oversized.bsdiff").apply {
          writeBytes(header + byteArrayOf(1, 2))
        }
      val outputFile = File(directory, "new.apk").apply { writeText("stale output") }

      try {
        BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)
        throw AssertionError("Expected output size limit to be enforced")
      } catch (expected: IllegalArgumentException) {
        assertEquals(false, outputFile.exists())
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun rejectsDecompressedExtraSectionLargerThanOutputBound() {
    val directory = Files.createTempDirectory("bsdiff-decompression-limit").toFile()
    try {
      val oldFile = File(directory, "old.apk").apply { writeBytes(byteArrayOf()) }
      val control = compressed {
        writeOffset(0)
        writeOffset(1)
        writeOffset(0)
      }
      val differences = compressed {}
      val extra = compressed { write(byteArrayOf(0x41, 0x42)) }
      val header =
        ByteArrayOutputStream()
          .apply {
            write("BSDIFF40".toByteArray(Charsets.US_ASCII))
            writeOffset(control.size.toLong())
            writeOffset(differences.size.toLong())
            writeOffset(1)
          }
          .toByteArray()
      val patchFile =
        File(directory, "bomb.bsdiff").apply { writeBytes(header + control + differences + extra) }
      val outputFile = File(directory, "new.apk").apply { writeText("stale output") }
      try {
        BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024)
        throw AssertionError("Expected decompressed section overflow to be rejected")
      } catch (expected: IllegalArgumentException) {
        assertEquals(false, outputFile.exists())
      }
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test
  fun closesControlWhenDiffInitializationFails() {
    assertSectionStreamsClosed(failingSection = 1)
  }

  @Test
  fun closesControlAndDiffWhenExtraInitializationFails() {
    assertSectionStreamsClosed(failingSection = 2)
  }

  private fun assertSectionStreamsClosed(failingSection: Int) {
    val directory = Files.createTempDirectory("bsdiff-section-init").toFile()
    try {
      val oldFile = File(directory, "old.apk").apply { writeText("A") }
      val outputFile = File(directory, "new.apk").apply { writeText("stale output") }
      val control = compressed {
        writeOffset(1)
        writeOffset(0)
        writeOffset(0)
      }
      val diff = compressed { write(0) }
      val extra = compressed {}
      val sections =
        listOf(control, diff, extra).mapIndexed { index, section ->
          if (index == failingSection) section.copyOf(2) else section
        }
      val patchFile =
        File(directory, "truncated-section.bsdiff").apply {
          outputStream().use { output ->
            val header =
              ByteArrayOutputStream().apply {
                write("BSDIFF40".toByteArray(Charsets.US_ASCII))
                writeOffset(sections[0].size.toLong())
                writeOffset(sections[1].size.toLong())
                writeOffset(1)
              }
            header.writeTo(output)
            sections.forEach { output.write(it) }
          }
        }

      var sectionsOpened = 0
      val closedSections = mutableListOf<Int>()
      assertThrows(IOException::class.java) {
        BsdiffPatch.apply(oldFile, patchFile, outputFile, 1024) { file ->
          val index = sectionsOpened++
          object : FilterInputStream(file.inputStream()) {
            override fun close() {
              closedSections.add(index)
              super.close()
            }
          }
        }
      }

      assertEquals(failingSection + 1, sectionsOpened)
      assertEquals((failingSection downTo 0).toList(), closedSections)
      assertEquals(false, outputFile.exists())
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun compressed(writeContent: ByteArrayOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    BZip2CompressorOutputStream(bytes).use { compressed ->
      val payload = ByteArrayOutputStream().apply(writeContent).toByteArray()
      compressed.write(payload)
    }
    return bytes.toByteArray()
  }

  private fun ByteArrayOutputStream.writeOffset(value: Long) {
    var magnitude = kotlin.math.abs(value)
    val bytes = ByteArray(8)
    for (index in 0..7) {
      bytes[index] = (magnitude and 0xff).toByte()
      magnitude = magnitude ushr 8
    }
    if (value < 0) bytes[7] = (bytes[7].toInt() or 0x80).toByte()
    write(bytes)
  }
}
