package com.neonyne.lasthavenlauncher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Streaming BSDIFF40 patch application. Reconstructs exact signed APK bytes; the large old/new APK
 * files are never loaded into a single byte array.
 */
internal object BsdiffPatch {
  fun apply(oldFile: File, patchFile: File, outputFile: File, maxOutputBytes: Long): Long {
    // Never leave an old partial APK at the output path when validation fails.
    outputFile.delete()
    try {
      require(oldFile.isFile && oldFile.canRead()) { "Installed APK is not readable" }
      require(patchFile.isFile && patchFile.length() >= HEADER_SIZE) { "Invalid patch file" }
      require(maxOutputBytes > 0) { "Invalid output size limit" }

      val header = ByteArray(HEADER_SIZE)
      FileInputStream(patchFile).use { readFully(it, header) }
      require(header.copyOfRange(0, 8).contentEquals(MAGIC)) {
        "Unsupported differential patch format"
      }

      val controlLength = readOffset(header, 8)
      val diffLength = readOffset(header, 16)
      val newSize = readOffset(header, 24)
      require(controlLength >= 0 && diffLength >= 0 && newSize > 0) {
        "Invalid differential patch header"
      }
      require(newSize <= maxOutputBytes) { "Updated APK exceeds the size limit" }
      require(controlLength <= patchFile.length() - HEADER_SIZE) {
        "Truncated differential patch"
      }
      require(diffLength <= patchFile.length() - HEADER_SIZE - controlLength) {
        "Truncated differential patch"
      }

      val extraLength = patchFile.length() - HEADER_SIZE - controlLength - diffLength
      // Output-producing tuples are bounded by output size, but BSDIFF40 also permits
      // zero-output seek tuples. Allow a fixed, bounded control-stream allowance for them
      // without letting a tiny output authorize unbounded decompression.
      val outputControlLimit = Math.multiplyExact(newSize, CONTROL_BYTES_PER_OUTPUT_BYTE)
      val maxControlBytes =
        minOf(
          MAX_CONTROL_SECTION_BYTES,
          Math.addExact(outputControlLimit, SEEK_ONLY_CONTROL_ALLOWANCE_BYTES),
        )
      val control = openBzipSection(patchFile, HEADER_SIZE.toLong(), controlLength, maxControlBytes)
      val diff = openBzipSection(patchFile, HEADER_SIZE + controlLength, diffLength, newSize)
      val extra =
        openBzipSection(
          patchFile,
          HEADER_SIZE + controlLength + diffLength,
          extraLength,
          newSize,
        )

      try {
        RandomAccessFile(oldFile, "r").use { oldApk ->
          FileOutputStream(outputFile).use { output ->
            val oldSize = oldApk.length()
            var oldPosition = 0L
            var newPosition = 0L
            val diffBuffer = ByteArray(BUFFER_SIZE)
            val oldBuffer = ByteArray(BUFFER_SIZE)
            val outputBuffer = ByteArray(BUFFER_SIZE)

            while (newPosition < newSize) {
              val addLength = readOffset(control.stream)
              val copyLength = readOffset(control.stream)
              val seekAdjustment = readOffset(control.stream)
              require(
                addLength >= 0 &&
                  copyLength >= 0 &&
                  (addLength > 0 || copyLength > 0 || seekAdjustment != 0L)
              ) {
                "Invalid differential patch control data"
              }
              require(addLength <= newSize - newPosition) {
                "Differential patch exceeds output size"
              }

              var remaining = addLength
              while (remaining > 0) {
                val count = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                readFully(diff.stream, diffBuffer, count)
                oldBuffer.fill(0, 0, count)

                val oldEnd = Math.addExact(oldPosition, count.toLong())
                val availableStart = maxOf(0L, oldPosition)
                val availableEnd = minOf(oldSize, oldEnd)
                if (availableEnd > availableStart) {
                  val sourceOffset = (availableStart - oldPosition).toInt()
                  val availableCount = (availableEnd - availableStart).toInt()
                  oldApk.seek(availableStart)
                  oldApk.readFully(oldBuffer, sourceOffset, availableCount)
                }

                for (index in 0 until count) {
                  outputBuffer[index] =
                    ((oldBuffer[index].toInt() and 0xff) + (diffBuffer[index].toInt() and 0xff))
                      .toByte()
                }
                output.write(outputBuffer, 0, count)
                oldPosition = oldEnd
                newPosition += count
                remaining -= count
              }

              require(copyLength <= newSize - newPosition) {
                "Differential patch exceeds output size"
              }
              remaining = copyLength
              while (remaining > 0) {
                val count = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                readFully(extra.stream, outputBuffer, count)
                output.write(outputBuffer, 0, count)
                newPosition += count
                remaining -= count
              }
              oldPosition = Math.addExact(oldPosition, seekAdjustment)
            }

            require(newPosition == newSize) { "Reconstructed APK has an invalid size" }
            require(control.stream.read() == -1) { "Unexpected trailing control data" }
            control.bounded.skipRemaining()
            require(diff.stream.read() == -1) { "Unexpected trailing difference data" }
            diff.bounded.skipRemaining()
            require(extra.stream.read() == -1) { "Unexpected trailing extra data" }
            extra.bounded.skipRemaining()
            output.fd.sync()
          }
        }
      } finally {
        control.stream.close()
        diff.stream.close()
        extra.stream.close()
      }

      require(outputFile.length() == newSize) { "Reconstructed APK has an invalid size" }
      return outputFile.length()
    } catch (error: Exception) {
      outputFile.delete()
      throw error
    }
  }

  private fun openBzipSection(
    file: File,
    offset: Long,
    length: Long,
    maxDecompressedBytes: Long,
  ): BzipSection {
    require(length > 0) { "Differential patch contains an empty compressed section" }
    val input = FileInputStream(file)
    try {
      var remaining = offset
      while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
          remaining -= skipped
        } else {
          if (input.read() < 0) throw IllegalArgumentException("Truncated differential patch")
          remaining--
        }
      }
      val bounded = BoundedInputStream(input, length)
      val decompressed = BZip2CompressorInputStream(bounded, true)
      return BzipSection(DecompressedLimitInputStream(decompressed, maxDecompressedBytes), bounded)
    } catch (error: Exception) {
      input.close()
      throw error
    }
  }

  private fun readOffset(input: InputStream): Long {
    val bytes = ByteArray(8)
    readFully(input, bytes)
    return readOffset(bytes, 0)
  }

  private fun readOffset(bytes: ByteArray, offset: Int): Long {
    var value = bytes[offset + 7].toLong() and 0x7f
    for (index in 6 downTo 0) {
      value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
    }
    return if ((bytes[offset + 7].toInt() and 0x80) != 0) -value else value
  }

  private fun readFully(input: InputStream, buffer: ByteArray) =
    readFully(input, buffer, 0, buffer.size)

  private fun readFully(input: InputStream, buffer: ByteArray, length: Int) =
    readFully(input, buffer, 0, length)

  private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
    var position = offset
    val end = offset + length
    while (position < end) {
      val count = input.read(buffer, position, end - position)
      if (count < 0) throw IllegalArgumentException("Truncated differential patch")
      if (count > 0) position += count
    }
  }

  private data class BzipSection(
    val stream: InputStream,
    val bounded: BoundedInputStream,
  )

  private class DecompressedLimitInputStream(
    private val source: InputStream,
    private val maxBytes: Long,
  ) : InputStream() {
    private var emittedBytes = 0L

    init {
      require(maxBytes >= 0) { "Invalid decompressed section limit" }
    }

    override fun read(): Int {
      if (emittedBytes == maxBytes) {
        if (source.read() < 0) return -1
        throw IllegalArgumentException("Decompressed patch section exceeds its limit")
      }
      val value = source.read()
      if (value >= 0) emittedBytes++
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (emittedBytes == maxBytes)
        return read().let { value ->
          if (value < 0) -1
          else {
            buffer[offset] = value.toByte()
            1
          }
        }
      val allowed = minOf(length.toLong(), maxBytes - emittedBytes).toInt()
      val count = source.read(buffer, offset, allowed)
      if (count > 0) emittedBytes += count
      return count
    }

    override fun close() = source.close()
  }

  private class BoundedInputStream(
    private val source: InputStream,
    private var remaining: Long,
  ) : InputStream() {
    override fun read(): Int {
      if (remaining <= 0) return -1
      val value = source.read()
      if (value >= 0) remaining--
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (remaining <= 0) return -1
      val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
      if (count > 0) remaining -= count
      return count
    }

    fun skipRemaining() {
      while (remaining > 0) {
        val skipped = source.skip(remaining)
        if (skipped > 0) {
          remaining -= skipped
        } else {
          if (source.read() < 0) throw IllegalArgumentException("Truncated differential patch")
          remaining--
        }
      }
    }

    override fun close() = source.close()
  }

  private const val HEADER_SIZE = 32
  private const val BUFFER_SIZE = 64 * 1024
  private const val CONTROL_BYTES_PER_OUTPUT_BYTE = 24L
  private const val SEEK_ONLY_CONTROL_ALLOWANCE_BYTES = 1L * 1024L * 1024L
  private const val MAX_CONTROL_SECTION_BYTES = 64L * 1024L * 1024L
  private val MAGIC = "BSDIFF40".toByteArray(Charsets.US_ASCII)
}
