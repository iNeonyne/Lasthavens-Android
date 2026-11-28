package com.neonyne.lasthavenlauncher

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class AppUpdatePatch(
  val downloadUrl: String,
  val sha256: String,
  val size: Long,
  val baseVersion: String,
  val baseVersionCode: Long,
  val baseSha256: String,
)

data class AppUpdateInfo(
  val version: String,
  val versionCode: Long,
  val releaseNotes: String,
  val downloadUrl: String,
  val sha256: String,
  val size: Long,
  val patch: AppUpdatePatch?,
)

sealed class UpdateCheckResult {
  data object NoUpdate : UpdateCheckResult()

  data class Available(val update: AppUpdateInfo) : UpdateCheckResult()

  data class Failed(val message: String) : UpdateCheckResult()
}

/**
 * Opt-in GitHub Releases updater.
 *
 * A signed full APK is always published as a fallback. If a matching patch is available, the user
 * may choose it; BSDIFF reconstructs the exact signed APK locally, then package metadata and the
 * pinned signing certificate are checked before Android's package installer is opened.
 */
object AppUpdater {
  private const val RELEASE_API =
    "https://api.github.com/repos/iNeonyne/LastHavenPlus/releases/latest"
  private const val MANIFEST_ASSET_NAME = "lasthavenplus-update.json"
  private const val MAX_APK_BYTES = 500L * 1024L * 1024L
  private const val MAX_PATCH_BYTES = 500L * 1024L * 1024L
  private const val RELEASE_CERT_SHA256 =
    "B3BBF7520E77DFC384A60B1EEE6C5F205B978F9B453849E2BE523F642018D263"

  private const val MAX_RELEASE_JSON_BYTES = 2L * 1024L * 1024L
  private const val MAX_MANIFEST_JSON_BYTES = 128L * 1024L
  private const val MAX_REDIRECTS = 5

  // A process-scoped worker with an idle timeout and a bounded queue. Duplicate
  // taps cannot accumulate an unbounded number of network jobs.
  private val executor =
    ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "LastHavenUpdater").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
      )
      .apply { allowCoreThreadTimeOut(true) }
  private val mainHandler = Handler(Looper.getMainLooper())

  private fun enqueue(appContext: Context, onRejected: () -> Unit, task: () -> Unit) {
    try {
      executor.execute { task() }
    } catch (error: RejectedExecutionException) {
      Diagnostics.warn(appContext, "Updater", "Updater is busy; request was not queued")
      mainHandler.post { onRejected() }
    }
  }

  fun checkForUpdate(context: Context, callback: (UpdateCheckResult) -> Unit) {
    val appContext = context.applicationContext
    enqueue(
      appContext,
      {
        callback(UpdateCheckResult.Failed("Updater is busy. Try again shortly."))
      },
    ) {
      val result =
        try {
          val release =
            withNetworkRetry(appContext) {
              val releaseConnection = openConnection(RELEASE_API)
              try {
                val responseCode = releaseConnection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                  null
                } else {
                  UpdateNetworkPolicy.requireHttpSuccess(
                    responseCode,
                    HttpURLConnection.HTTP_OK,
                    "GitHub release lookup",
                  )
                  JSONObject(
                    releaseConnection.inputStream.use {
                      UpdateNetworkPolicy.readLimitedText(it, MAX_RELEASE_JSON_BYTES)
                    },
                  )
                }
              } finally {
                releaseConnection.disconnect()
              }
            }

          if (release == null) {
            UpdateCheckResult.NoUpdate
          } else {
            val tag = release.getString("tag_name")
            val latestVersion = UpdateVersionPolicy.releaseVersionFromTag(tag)

            val packageInfo = installedPackageInfo(appContext)
            val currentVersion = packageInfo.versionName ?: "0.0.0"
            val versionComparison = compareVersions(latestVersion, currentVersion)
            val latestVersionCode = versionCodeForVersion(latestVersion)
            if (
              versionComparison < 0 ||
                (versionComparison == 0 && latestVersionCode <= packageInfo.longVersionCode)
            ) {
              UpdateCheckResult.NoUpdate
            } else {
              val assets = release.getJSONArray("assets")
              val assetByName =
                (0 until assets.length())
                  .map { assets.getJSONObject(it) }
                  .associateBy { it.optString("name") }

              val manifestAsset =
                assetByName[MANIFEST_ASSET_NAME] ?: error("Release is missing $MANIFEST_ASSET_NAME")
              val manifestUrl = requireGithubUrl(manifestAsset.getString("browser_download_url"))
              val manifest =
                withNetworkRetry(appContext) {
                  val manifestConnection = openConnection(manifestUrl)
                  try {
                    val responseCode = manifestConnection.responseCode
                    UpdateNetworkPolicy.requireHttpSuccess(
                      responseCode,
                      HttpURLConnection.HTTP_OK,
                      "Update manifest download",
                    )
                    JSONObject(
                      manifestConnection.inputStream.use {
                        UpdateNetworkPolicy.readLimitedText(it, MAX_MANIFEST_JSON_BYTES)
                      },
                    )
                  } finally {
                    manifestConnection.disconnect()
                  }
                }

              val target = manifest.getJSONObject("apk")
              val apkName = target.getString("name")
              val expectedApkName = UpdateManifestPolicy.apkAssetName(latestVersion)
              val fullAsset = assetByName[apkName] ?: error("Release is missing $apkName")
              val fullUrl = requireGithubUrl(fullAsset.getString("browser_download_url"))
              val fullSha = requireSha256(target.getString("sha256"))
              val apiDigest = fullAsset.optString("digest")
              if (apiDigest.startsWith("sha256:")) {
                check(apiDigest.removePrefix("sha256:").equals(fullSha, true)) {
                  "GitHub APK digest does not match the update manifest"
                }
              }

              val versionCode = manifest.getLong("versionCode")
              val targetSize = target.getLong("size")
              UpdateManifestPolicy.validateApk(
                schemaVersion = manifest.optInt("schemaVersion"),
                manifestVersion = manifest.getString("version"),
                expectedReleaseVersion = latestVersion,
                versionCode = versionCode,
                expectedVersionCode = latestVersionCode,
                installedVersionCode = packageInfo.longVersionCode,
                apkName = apkName,
                expectedApkName = expectedApkName,
                apkSize = targetSize,
                releaseAssetSize = fullAsset.getLong("size"),
                maximumApkBytes = MAX_APK_BYTES,
              )

              val patch = runCatching {
                manifest.optJSONObject("patch")?.let { patchJson ->
                  val patchName = patchJson.getString("name")
                  val patchAsset =
                    assetByName[patchName]
                      ?: error("Release is missing differential patch $patchName")
                  val patchUrl = requireGithubUrl(patchAsset.getString("browser_download_url"))
                  val patchSha = requireSha256(patchJson.getString("sha256"))
                  val patchApiDigest = patchAsset.optString("digest")
                  if (patchApiDigest.startsWith("sha256:")) {
                    check(patchApiDigest.removePrefix("sha256:").equals(patchSha, true)) {
                      "GitHub patch digest does not match the update manifest"
                    }
                  }
                  val patchSize = patchJson.getLong("size")
                  check(patchSize in 1..MAX_PATCH_BYTES) {
                    "Differential patch size is invalid"
                  }
                  check(patchAsset.getLong("size") == patchSize) {
                    "GitHub patch asset size does not match the update manifest"
                  }
                  AppUpdatePatch(
                      downloadUrl = patchUrl,
                      sha256 = patchSha,
                      size = patchSize,
                      baseVersion = patchJson.getString("fromVersion"),
                      baseVersionCode = patchJson.getLong("fromVersionCode"),
                      baseSha256 = requireSha256(patchJson.getString("fromSha256")),
                    )
                    .takeIf {
                      UpdateManifestPolicy.isPatchCompatible(
                        currentVersion,
                        packageInfo.longVersionCode,
                        it.baseVersion,
                        it.baseVersionCode,
                      )
                    }
                }
              }
                .getOrNull()

              UpdateCheckResult.Available(
                AppUpdateInfo(
                  version = latestVersion,
                  versionCode = versionCode,
                  releaseNotes =
                    release.optString("body").ifBlank {
                      "A newer version of LastHavenPlus is available."
                    },
                  downloadUrl = fullUrl,
                  sha256 = fullSha,
                  size = targetSize,
                  patch = patch,
                ),
              )
            }
          }
        } catch (error: Exception) {
          UpdateCheckResult.Failed(error.message ?: "Unable to check for updates")
        }

      when (result) {
        is UpdateCheckResult.Available ->
          Diagnostics.info(appContext, "Updater", "Update available: ${result.update.version}")
        is UpdateCheckResult.Failed ->
          Diagnostics.warn(appContext, "Updater", "Update check failed: ${result.message}")
        UpdateCheckResult.NoUpdate -> Diagnostics.info(appContext, "Updater", "No update available")
      }
      mainHandler.post { callback(result) }
    }
  }

  internal fun downloadUpdate(
    context: Context,
    update: AppUpdateInfo,
    preferDelta: Boolean,
    callback: (Result<Uri>) -> Unit,
    onProgress: (UpdateProgressState) -> Unit = {},
  ) {
    val appContext = context.applicationContext
    enqueue(
      appContext,
      {
        val error = IllegalStateException("Updater is busy. Try again shortly.")
        onProgress(UpdateProgressState(stage = UpdateStage.FAILED, failureMessage = error.message))
        callback(Result.failure(error))
      },
    ) {
      val progressTracker = UpdateProgressTracker()
      var lastProgressPublishedAtNanos: Long? = null
      fun publishProgress(force: Boolean = false) {
        val now = System.nanoTime()
        val last = lastProgressPublishedAtNanos
        if (!force && last != null && now - last < 100_000_000L) return
        lastProgressPublishedAtNanos = now
        val snapshot = progressTracker.snapshot()
        mainHandler.post { onProgress(snapshot) }
      }
      fun publishDownloadProgress() {
        publishProgress()
      }
      fun setProgressStage(stage: UpdateStage, failureMessage: String? = null) {
        progressTracker.onStageChanged(stage, failureMessage)
        publishProgress(force = true)
      }

      val result = runCatching {
        val updateDirectory =
          File(appContext.filesDir, "updates").apply {
            check(isDirectory || mkdirs()) { "Could not create the update download directory" }
          }
        UpdateFileCleanup.removeObsoleteFiles(
          updateDirectory,
          UpdateFileCleanup.protectedApkNames(appContext),
        )
        val temporaryApk =
          File.createTempFile(
            "update-${update.versionCode}-",
            ".apk.part",
            updateDirectory,
          )
        try {
          val finalApk =
            File(
              updateDirectory,
              "LastHavenPlus-update-${update.versionCode}-${UUID.randomUUID()}.apk",
            )
          val patch = update.patch
          val installedInfo = installedPackageInfo(appContext)
          val canUsePatch =
            preferDelta &&
              patch != null &&
              installedInfo.versionName == patch.baseVersion &&
              installedInfo.longVersionCode == patch.baseVersionCode

          val verifyCandidate: () -> Unit = {
            check(temporaryApk.length() == update.size) {
              "Reconstructed APK size does not match the release manifest"
            }
            check(sha256(temporaryApk).equals(update.sha256, true)) {
              "APK checksum verification failed"
            }
            verifySignedUpdate(appContext, temporaryApk, update)
          }

          UpdateFallback.withFullFallback(
            preferDelta = canUsePatch,
            deltaAttempt = {
              val delta = patch ?: error("Differential patch metadata is missing")
              setProgressStage(UpdateStage.DIFFERENTIAL_DOWNLOAD)
              progressTracker.onAttemptStarted(1)
              publishProgress()
              val baseApk = File(appContext.applicationInfo.sourceDir)
              check(baseApk.isFile && baseApk.canRead()) {
                "Installed APK is not accessible"
              }
              UpdateNetworkPolicy.verifyBaseApkDigest(
                sha256(baseApk),
                delta.baseSha256,
              )

              val patchFile =
                File.createTempFile(
                  "update-${update.versionCode}-",
                  ".bsdiff.part",
                  updateDirectory,
                )
              try {
                downloadToFileWithRetry(
                  delta.downloadUrl,
                  delta.sha256,
                  delta.size,
                  MAX_PATCH_BYTES,
                  patchFile,
                  appContext,
                  progressTracker,
                  ::publishDownloadProgress,
                )
                BsdiffPatch.apply(baseApk, patchFile, temporaryApk, MAX_APK_BYTES)
                verifyCandidate()
                temporaryApk
              } finally {
                patchFile.delete()
              }
            },
            fullAttempt = {
              // Never let a failed or partially reconstructed delta reach the installer.
              temporaryApk.delete()
              progressTracker.onAttemptStarted(1)
              setProgressStage(UpdateStage.FULL_DOWNLOAD)
              downloadToFileWithRetry(
                update.downloadUrl,
                update.sha256,
                update.size,
                MAX_APK_BYTES,
                temporaryApk,
                appContext,
                progressTracker,
                ::publishDownloadProgress,
              )
              verifyCandidate()
              temporaryApk
            },
            onDeltaFailure = { error ->
              progressTracker.onFallbackToFull()
              Diagnostics.warn(
                appContext,
                "Updater",
                "Differential update failed; falling back to full APK: ${error.message}",
              )
            },
          )

          check(temporaryApk.renameTo(finalApk)) { "Could not finalize downloaded APK" }
          val updateUri =
            FileProvider.getUriForFile(
              appContext,
              "${appContext.packageName}.updates",
              finalApk,
            )
          setProgressStage(UpdateStage.READY)
          updateUri
        } finally {
          temporaryApk.delete()
        }
      }

      result
        .onSuccess {
          Diagnostics.info(appContext, "Updater", "Update download and validation succeeded")
        }
        .onFailure {
          setProgressStage(UpdateStage.FAILED, it.message ?: "Update download failed")
          Diagnostics.error(appContext, "Updater", "Update download failed: ${it.message}")
        }
      mainHandler.post { callback(result) }
    }
  }

  private fun verifySignedUpdate(context: Context, apkFile: File, update: AppUpdateInfo) {
    val packageManager = context.packageManager
    @Suppress("DEPRECATION")
    val archiveInfo =
      packageManager.getPackageArchiveInfo(
        apkFile.absolutePath,
        PackageManager.GET_SIGNING_CERTIFICATES,
      ) ?: error("Downloaded file is not a valid Android APK")

    check(archiveInfo.packageName == context.packageName) {
      "Update package ID does not match this launcher"
    }
    check(archiveInfo.longVersionCode == update.versionCode) {
      "Update APK versionCode does not match the release manifest"
    }
    check(archiveInfo.versionName == update.version) {
      "Update APK version does not match the release tag"
    }

    val installed = installedPackageInfo(context)
    val installedSigners =
      installed.signingInfo?.apkContentsSigners
        ?: error("Installed app signing certificate could not be read")
    val archiveSigners =
      archiveInfo.signingInfo?.apkContentsSigners
        ?: error("Update APK signing certificate could not be read")
    check(installedSigners.isNotEmpty() && archiveSigners.isNotEmpty()) {
      "Update APK has no signing certificate"
    }

    val installedFingerprints = installedSigners.map { certificateSha256(it.toByteArray()) }.toSet()
    val updateFingerprints = archiveSigners.map { certificateSha256(it.toByteArray()) }.toSet()
    check(RELEASE_CERT_SHA256 in installedFingerprints) {
      "Installed app does not use the configured LastHaven release key"
    }
    check(
      updateFingerprints == installedFingerprints && RELEASE_CERT_SHA256 in updateFingerprints,
    ) {
      "Update APK was not signed with the installed LastHaven release key"
    }
  }

  @Suppress("DEPRECATION")
  private fun installedPackageInfo(context: Context): PackageInfo =
    context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_SIGNING_CERTIFICATES,
    )

  private fun <T> withNetworkRetry(context: Context, action: () -> T): T =
    UpdateNetworkPolicy.withIoRetry(
      maxAttempts = 3,
      initialDelayMillis = 750L,
      onRetry = { _, nextAttempt ->
        Diagnostics.warn(
          context,
          "Updater",
          "Temporary network failure; retrying request (attempt $nextAttempt of 3)",
        )
      },
      action = action,
    )

  private fun downloadToFileWithRetry(
    url: String,
    expectedSha256: String,
    expectedSize: Long,
    maximumBytes: Long,
    destination: File,
    context: Context,
    progressTracker: UpdateProgressTracker,
    publishProgress: () -> Unit,
  ) {
    UpdateNetworkPolicy.withDownloadRetry(
      destination = destination,
      maxAttempts = 3,
      initialDelayMillis = 750L,
      onRetry = { _, nextAttempt ->
        progressTracker.onAttemptStarted(nextAttempt)
        publishProgress()
        Diagnostics.warn(
          context,
          "Updater",
          "Temporary download failure; retrying (attempt $nextAttempt of 3)",
        )
      },
    ) {
      downloadToFile(
        url,
        expectedSha256,
        expectedSize,
        maximumBytes,
        destination,
        progressTracker,
        publishProgress,
      )
    }
  }

  private fun downloadToFile(
    url: String,
    expectedSha256: String,
    expectedSize: Long,
    maximumBytes: Long,
    destination: File,
    progressTracker: UpdateProgressTracker,
    publishProgress: () -> Unit,
  ) {
    val connection = openConnection(url)
    try {
      val responseCode = connection.responseCode
      UpdateNetworkPolicy.requireHttpSuccess(
        responseCode,
        HttpURLConnection.HTTP_OK,
        "Update download",
      )
      val contentLength = connection.contentLengthLong
      check(contentLength <= maximumBytes) { "Update file is unexpectedly large" }
      if (expectedSize > 0 && contentLength >= 0) {
        check(contentLength == expectedSize) { "Update file size does not match its manifest" }
      }
      progressTracker.onTotalBytes(contentLength, expectedSize)
      publishProgress()

      UpdateDownloadPolicy.copyAndVerify(
        input = connection.inputStream,
        destination = destination,
        expectedSize = expectedSize,
        maximumBytes = maximumBytes,
        expectedSha256 = expectedSha256,
        onBytesCopied = { delta ->
          progressTracker.onBytesWritten(delta)
          publishProgress()
        },
        onVerificationStarted = {
          progressTracker.onStageChanged(UpdateStage.VERIFYING)
          publishProgress()
        },
      )
    } catch (error: Exception) {
      destination.delete()
      throw error
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: String): HttpURLConnection {
    var currentUrl = UpdateNetworkPolicy.requireTrustedConnectionUrl(url)
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
      val connection =
        (URL(currentUrl).openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 15_000
          readTimeout = 60_000
          instanceFollowRedirects = false
          setRequestProperty("Accept", "application/vnd.github+json")
          setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
          setRequestProperty("User-Agent", "LastHavenPlus-Android")
        }
      val responseCode =
        try {
          connection.responseCode
        } catch (error: Exception) {
          connection.disconnect()
          throw error
        }

      if (UpdateNetworkPolicy.isRedirect(responseCode)) {
        val location = connection.getHeaderField("Location")
        connection.disconnect()
        check(redirectCount < MAX_REDIRECTS) {
          "Update server exceeded the redirect limit"
        }
        currentUrl =
          UpdateNetworkPolicy.resolveTrustedRedirect(
            currentUrl,
            location ?: error("Update server returned a redirect without a location"),
          )
      } else {
        return connection
      }
    }
    error("Update server exceeded the redirect limit")
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun certificateSha256(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02X".format(it) }

  private fun requireSha256(value: String): String = UpdateVersionPolicy.requireSha256(value)

  private fun requireGithubUrl(value: String): String =
    UpdateVersionPolicy.requireGithubAssetUrl(value)

  private fun versionCodeForVersion(version: String): Long =
    UpdateVersionPolicy.versionCode(version)

  private fun compareVersions(first: String, second: String): Int =
    UpdateVersionPolicy.compare(first, second)
}
