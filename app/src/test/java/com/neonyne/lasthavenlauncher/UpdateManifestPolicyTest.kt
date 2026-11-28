package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestPolicyTest {
  @Test
  fun acceptsValidNewerManifest() {
    UpdateManifestPolicy.validateApk(
      schemaVersion = 1,
      manifestVersion = "1.1.0",
      expectedReleaseVersion = "1.1.0",
      versionCode = 1_001_000,
      expectedVersionCode = 1_001_000,
      installedVersionCode = 1_000_000,
      apkName = "LastHavenPlus-1.1.0-stable-arm64-v8a.apk",
      expectedApkName = "LastHavenPlus-1.1.0-stable-arm64-v8a.apk",
      apkSize = 100,
      releaseAssetSize = 100,
      maximumApkBytes = 500,
    )
  }

  @Test
  fun rejectsUnsupportedOrInconsistentManifestFields() {
    val invalidCases: List<() -> Unit> =
      listOf(
        { validate(schemaVersion = 2) },
        { validate(manifestVersion = "1.2.0") },
        { validate(versionCode = 1_000_000) },
        { validate(installedVersionCode = 1_001_000) },
        { validate(apkName = "other.apk") },
        { validate(apkSize = 0) },
        { validate(apkSize = 501) },
        { validate(releaseAssetSize = 99) },
      )
    invalidCases.forEach { action ->
      try {
        action()
        throw AssertionError("Expected invalid manifest to be rejected")
      } catch (_: IllegalStateException) {
        // Expected.
      }
    }
  }

  @Test
  fun derivesVersionQualifiedStableApkName() {
    assertEquals(
      "LastHavenPlus-2.5.1-stable-arm64-v8a.apk",
      UpdateManifestPolicy.apkAssetName("2.5.1"),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsInvalidApkAssetVersion() {
    UpdateManifestPolicy.apkAssetName("../latest")
  }

  @Test
  fun onlyUsesPatchForExactInstalledBaseVersionAndCode() {
    assertTrue(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "1.0.0", 1_000_000))
    assertFalse(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "0.9.0", 900_000))
    assertFalse(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "1.0.0", 999_999))
  }

  @Test
  fun selectsFullApkWhenPatchIsAtLeastAsLarge() {
    fun selectedDownload(patchSize: Long) =
      UpdateFallback.withFullFallback(
        preferDelta = UpdateManifestPolicy.isPatchSizeEligible(patchSize, 100, 500),
        deltaAttempt = { "patch" },
        fullAttempt = { "full" },
      )

    assertEquals("patch", selectedDownload(99))
    assertEquals("full", selectedDownload(100))
    assertEquals("full", selectedDownload(101))
    assertFalse(UpdateManifestPolicy.isPatchSizeEligible(501, 600, 500))
    assertFalse(UpdateManifestPolicy.isPatchSizeEligible(0, 100, 500))
  }

  private fun validate(
    schemaVersion: Int = 1,
    manifestVersion: String = "1.1.0",
    versionCode: Long = 1_001_000,
    installedVersionCode: Long = 1_000_000,
    apkName: String = "LastHavenPlus-1.1.0-stable-arm64-v8a.apk",
    apkSize: Long = 100,
    releaseAssetSize: Long = 100,
  ) {
    UpdateManifestPolicy.validateApk(
      schemaVersion = schemaVersion,
      manifestVersion = manifestVersion,
      expectedReleaseVersion = "1.1.0",
      versionCode = versionCode,
      expectedVersionCode = 1_001_000,
      installedVersionCode = installedVersionCode,
      apkName = apkName,
      expectedApkName = "LastHavenPlus-1.1.0-stable-arm64-v8a.apk",
      apkSize = apkSize,
      releaseAssetSize = releaseAssetSize,
      maximumApkBytes = 500,
    )
  }
}
