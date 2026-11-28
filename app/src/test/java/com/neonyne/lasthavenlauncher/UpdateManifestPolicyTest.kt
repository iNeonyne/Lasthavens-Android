package com.neonyne.lasthavenlauncher

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
            apkName = "lasthavenplus-arm64.apk",
            expectedApkName = "lasthavenplus-arm64.apk",
            apkSize = 100,
            releaseAssetSize = 100,
            maximumApkBytes = 500,
        )
    }

    @Test
    fun rejectsUnsupportedOrInconsistentManifestFields() {
        val invalidCases: List<() -> Unit> = listOf(
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
    fun onlyUsesPatchForExactInstalledBaseVersionAndCode() {
        assertTrue(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "1.0.0", 1_000_000))
        assertFalse(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "0.9.0", 900_000))
        assertFalse(UpdateManifestPolicy.isPatchCompatible("1.0.0", 1_000_000, "1.0.0", 999_999))
    }

    private fun validate(
        schemaVersion: Int = 1,
        manifestVersion: String = "1.1.0",
        versionCode: Long = 1_001_000,
        installedVersionCode: Long = 1_000_000,
        apkName: String = "lasthavenplus-arm64.apk",
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
            expectedApkName = "lasthavenplus-arm64.apk",
            apkSize = apkSize,
            releaseAssetSize = releaseAssetSize,
            maximumApkBytes = 500,
        )
    }
}
