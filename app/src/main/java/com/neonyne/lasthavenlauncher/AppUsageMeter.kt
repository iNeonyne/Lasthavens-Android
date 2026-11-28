package com.neonyne.lasthavenlauncher

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Process
import java.io.File

data class NetworkUsageSnapshot(
  val receivedSinceBootBytes: Long?,
  val sentSinceBootBytes: Long?,
  val receivedThisSessionBytes: Long?,
  val sentThisSessionBytes: Long?,
  val receivedTrackedTotalBytes: Long,
  val sentTrackedTotalBytes: Long,
)

data class UsageSnapshot(
  val installedApkBytes: Long,
  val persistentDataBytes: Long,
  val cacheBytes: Long,
  val receivedSinceBootBytes: Long?,
  val sentSinceBootBytes: Long?,
  val receivedThisSessionBytes: Long?,
  val sentThisSessionBytes: Long?,
  val receivedTrackedTotalBytes: Long,
  val sentTrackedTotalBytes: Long,
)

/**
 * Local-only measurements. Android's UID counters include all app traffic (game, updater and other
 * app networking), not game-only traffic.
 */
class AppUsageMeter(context: Context) {
  private val appContext = context.applicationContext
  private val uid = Process.myUid()
  private val rxAtStart = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L }
  private val txAtStart = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0L }
  private val networkTracker =
    NetworkUsageTracker(
      SharedPreferencesNetworkTotalsStore(
        appContext.getSharedPreferences(NETWORK_TOTALS_PREFERENCES, Context.MODE_PRIVATE),
      ),
    )

  /** Samples only network counters; safe to poll while the Network & Storage page is visible. */
  fun networkSnapshot(): NetworkUsageSnapshot = readNetworkSnapshot()

  /**
   * Measures GeckoView's local profile (site storage and cache plus engine metadata).
   * This is the locally stored game-data footprint, not the installed APK size.
   */
  fun gameDataSizeBytes(): Long? {
    val profileDirectory = File(appContext.filesDir, "mozilla")
    return if (profileDirectory.isDirectory) directorySize(profileDirectory) else null
  }

  fun snapshot(): UsageSnapshot {
    val network = readNetworkSnapshot()
    val persistentData = appContext.filesDir
    val apkFiles = buildList {
      add(File(appContext.applicationInfo.sourceDir))
      appContext.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
    }
    return UsageSnapshot(
      installedApkBytes = apkFiles.sumOf { if (it.isFile) it.length() else 0L },
      persistentDataBytes = directorySize(persistentData),
      cacheBytes = directorySize(appContext.cacheDir) + directorySize(appContext.codeCacheDir),
      receivedSinceBootBytes = network.receivedSinceBootBytes,
      sentSinceBootBytes = network.sentSinceBootBytes,
      receivedThisSessionBytes = network.receivedThisSessionBytes,
      sentThisSessionBytes = network.sentThisSessionBytes,
      receivedTrackedTotalBytes = network.receivedTrackedTotalBytes,
      sentTrackedTotalBytes = network.sentTrackedTotalBytes,
    )
  }

  private fun readNetworkSnapshot(): NetworkUsageSnapshot {
    val rx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L }
    val tx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0L }
    val tracked = networkTracker.sample(rx, tx)
    return NetworkUsageSnapshot(
      receivedSinceBootBytes = rx,
      sentSinceBootBytes = tx,
      receivedThisSessionBytes = difference(rx, rxAtStart),
      sentThisSessionBytes = difference(tx, txAtStart),
      receivedTrackedTotalBytes = tracked.receivedBytes,
      sentTrackedTotalBytes = tracked.sentBytes,
    )
  }

  private fun difference(current: Long?, initial: Long?): Long? =
    if (current == null || initial == null) null
    else if (current >= initial) current - initial
    else current // The OS counter reset during this app process.

  private fun directorySize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf(::directorySize) ?: 0L
  }

  private companion object {
    const val NETWORK_TOTALS_PREFERENCES = "network_usage_totals"
  }
}

/** Stores both directions and their baselines in one atomic private-preferences commit. */
private class SharedPreferencesNetworkTotalsStore(
  private val preferences: SharedPreferences,
) : NetworkUsageTotalsStore {
  override fun read(): PersistentNetworkTotals =
    PersistentNetworkTotals(
      receivedBytes = preferences.getLong(KEY_RECEIVED_TOTAL, 0L).coerceAtLeast(0L),
      sentBytes = preferences.getLong(KEY_SENT_TOTAL, 0L).coerceAtLeast(0L),
      lastReceivedCounterBytes =
        preferences.getLong(KEY_RECEIVED_BASELINE, -1L).takeIf { it >= 0L },
      lastSentCounterBytes = preferences.getLong(KEY_SENT_BASELINE, -1L).takeIf { it >= 0L },
    )

  override fun write(totals: PersistentNetworkTotals): Boolean {
    val editor =
      preferences
        .edit()
        .putLong(KEY_RECEIVED_TOTAL, totals.receivedBytes.coerceAtLeast(0L))
        .putLong(KEY_SENT_TOTAL, totals.sentBytes.coerceAtLeast(0L))
        .putLong(KEY_RECEIVED_BASELINE, totals.lastReceivedCounterBytes ?: -1L)
        .putLong(KEY_SENT_BASELINE, totals.lastSentCounterBytes ?: -1L)
    return editor.commit()
  }

  private companion object {
    const val KEY_RECEIVED_TOTAL = "received_total_bytes"
    const val KEY_SENT_TOTAL = "sent_total_bytes"
    const val KEY_RECEIVED_BASELINE = "received_counter_baseline"
    const val KEY_SENT_BASELINE = "sent_counter_baseline"
  }
}
