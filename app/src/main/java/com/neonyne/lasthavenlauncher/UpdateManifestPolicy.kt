package com.neonyne.lasthavenlauncher

/** Validates the release manifest fields before any update is offered. */
internal object UpdateManifestPolicy {
  fun apkAssetName(version: String): String {
    require(version.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+"""))) {
      "APK asset version must use MAJOR.MINOR.PATCH"
    }
    return "LastHavenPlus-$version-stable-arm64-v8a.apk"
  }

  fun validateApk(
    schemaVersion: Int,
    manifestVersion: String,
    expectedReleaseVersion: String,
    versionCode: Long,
    expectedVersionCode: Long,
    installedVersionCode: Long,
    apkName: String,
    expectedApkName: String,
    apkSize: Long,
    releaseAssetSize: Long,
    maximumApkBytes: Long,
  ) {
    check(schemaVersion == 1) { "Unsupported update manifest version" }
    check(manifestVersion == expectedReleaseVersion) {
      "Update manifest version does not match the release tag"
    }
    check(versionCode == expectedVersionCode) {
      "Update manifest versionCode does not match the release tag"
    }
    check(versionCode > installedVersionCode) {
      "Update versionCode is not newer than the installed app"
    }
    check(apkName == expectedApkName) {
      "Update manifest references an unexpected APK"
    }
    check(apkSize in 1..maximumApkBytes) { "Update APK size is invalid" }
    check(releaseAssetSize == apkSize) {
      "GitHub APK asset size does not match the update manifest"
    }
  }

  fun isPatchSizeEligible(patchSize: Long, apkSize: Long, maximumPatchBytes: Long): Boolean =
    patchSize in 1..maximumPatchBytes && patchSize < apkSize

  fun isPatchCompatible(
    currentVersion: String,
    installedVersionCode: Long,
    baseVersion: String,
    baseVersionCode: Long,
  ): Boolean = currentVersion == baseVersion && installedVersionCode == baseVersionCode
}
