import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured =
  !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStoreFile).isFile

// Compose instrumentation does not initialise GeckoView. On CI, omit Gecko's ARM64 native
// libraries from the smoke-only APK so the same UI tests can run on a bootable x86_64 emulator.
// Release/debug artifacts remain ARM64-only and keep all GeckoView binaries.
val uiSmokeNoNativeLibs = providers.gradleProperty("uiSmokeNoNativeLibs").orNull == "true"

android {
  namespace = "com.neonyne.lasthavenlauncher"
  compileSdk = 37
  compileSdkMinor = 2

  defaultConfig {
    applicationId = "com.neonyne.lasthavenlauncher"
    minSdk = 29
    targetSdk = 36
    versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
    versionName = providers.gradleProperty("appVersionName").orElse("0.1.0").get()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters += "arm64-v8a"
    }
  }

  signingConfigs {
    if (releaseSigningConfigured) {
      create("release") {
        storeFile = file(releaseStoreFile!!)
        storePassword = releaseStorePassword
        keyAlias = "lasthavenplus"
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      if (releaseSigningConfigured) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  buildFeatures {
    compose = true
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
      if (uiSmokeNoNativeLibs) {
        excludes += "**/*.so"
      }
    }
  }
}

dependencies {
  // GeckoView's WebAuthn integration uses Google's proprietary FIDO2 client.
  // This app deliberately ships without Google Play services; passkey sign-in
  // is therefore unavailable, while other GeckoView/browser functionality remains.
  implementation(libs.geckoview.arm64) {
    exclude(group = "com.google.android.gms")
  }

  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.mediarouter)
  implementation(libs.androidx.core)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.activity.compose)
  implementation(libs.commons.compress)

  testImplementation(libs.junit)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
}

val verifyNoGooglePlayServices =
  tasks.register("verifyNoGooglePlayServices") {
    doLast {
      val googleArtifacts =
        configurations
          .getByName("releaseRuntimeClasspath")
          .resolvedConfiguration
          .resolvedArtifacts
          .filter { it.moduleVersion.id.group == "com.google.android.gms" }
      check(googleArtifacts.isEmpty()) {
        "Google Play services must not be present in releaseRuntimeClasspath: " +
          googleArtifacts.joinToString { it.moduleVersion.id.toString() }
      }
    }
  }

val verifyNoGooglePlayServicesInApk =
  tasks.register("verifyNoGooglePlayServicesInApk") {
    doLast {
      val apk =
        listOf(
            layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile,
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile,
          )
          .firstOrNull { it.isFile }
      check(apk != null) {
        "Release APK was not found for Google Play services verification."
      }

      fun hasGooglePlayServicesClass(dex: ByteArray): Boolean {
        fun readUInt(offset: Int): Int {
          check(offset >= 0 && offset + 4 <= dex.size) { "Invalid DEX table offset." }
          return (dex[offset].toInt() and 0xff) or
            ((dex[offset + 1].toInt() and 0xff) shl 8) or
            ((dex[offset + 2].toInt() and 0xff) shl 16) or
            ((dex[offset + 3].toInt() and 0xff) shl 24)
        }

        val stringIdsOffset = readUInt(60)
        val typeIdsOffset = readUInt(68)
        val classDefsSize = readUInt(96)
        val classDefsOffset = readUInt(100)

        for (index in 0 until classDefsSize) {
          val classIndex = readUInt(classDefsOffset + index * 32)
          val descriptorIndex = readUInt(typeIdsOffset + classIndex * 4)
          var cursor = readUInt(stringIdsOffset + descriptorIndex * 4)
          while ((dex[cursor].toInt() and 0x80) != 0) cursor++
          cursor++
          val start = cursor
          while (dex[cursor].toInt() != 0) cursor++
          val descriptor = String(dex, start, cursor - start, Charsets.UTF_8)
          if (descriptor.startsWith("Lcom/google/android/gms/")) return true
        }
        return false
      }

      ZipFile(apk).use { archive ->
        val dexEntries =
          archive.entries().asSequence().filter {
            it.name.matches(Regex("classes(\\d*)?\\.dex"))
          }
        val containsGooglePlayServices = dexEntries.any { entry ->
          archive.getInputStream(entry).use { input ->
            hasGooglePlayServicesClass(input.readBytes())
          }
        }
        check(!containsGooglePlayServices) {
          "Google Play services class definitions were found in the release APK."
        }
      }
    }
  }

tasks.configureEach {
  if (name == "assembleRelease") {
    dependsOn(verifyNoGooglePlayServices)
    finalizedBy(verifyNoGooglePlayServicesInApk)
  }
}
