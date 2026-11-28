package com.neonyne.lasthavenlauncher

internal object UpdateStoragePolicy {
  // Leave 16 MiB of headroom for filesystem overhead and incidental writes while staging.
  // The space check is a snapshot, so download and reconstruction still handle I/O failures.
  private const val SAFETY_MARGIN_BYTES = 16L * 1024 * 1024

  fun requireSpace(availableBytes: Long?, apkSize: Long, patchSize: Long = 0) {
    check(availableBytes != null && availableBytes >= 0) {
      "Could not check storage for the update. Check device storage in Settings and try again."
    }
    // A differential update keeps the patch and the reconstructed APK on disk at the same time.
    val requiredBytes = apkSize + patchSize + SAFETY_MARGIN_BYTES
    check(availableBytes >= requiredBytes) {
      val bytesPerMiB = 1024L * 1024
      val missingMiB = (requiredBytes - availableBytes + bytesPerMiB - 1) / bytesPerMiB
      "Not enough storage for the update. Free at least $missingMiB MiB and try again."
    }
  }
}
