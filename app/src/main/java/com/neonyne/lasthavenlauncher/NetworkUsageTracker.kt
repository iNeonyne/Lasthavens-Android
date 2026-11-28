package com.neonyne.lasthavenlauncher

/**
 * Durable per-UID byte totals plus the last successfully sampled Android counter values.
 *
 * The stored totals are tracked totals, not a claim that Android exposes complete device-lifetime
 * traffic. Only deltas observed between successful samples can be accumulated.
 */
internal data class PersistentNetworkTotals(
  val receivedBytes: Long = 0L,
  val sentBytes: Long = 0L,
  val lastReceivedCounterBytes: Long? = null,
  val lastSentCounterBytes: Long? = null,
)

internal interface NetworkUsageTotalsStore {
  fun read(): PersistentNetworkTotals

  /** Returns false when the complete snapshot could not be durably committed. */
  fun write(totals: PersistentNetworkTotals): Boolean
}

/**
 * Converts Android per-UID counters into restart-persistent totals without double counting.
 *
 * A missing/negative counter does not change its saved baseline. A lower counter is treated as an
 * OS counter reset (commonly a reboot); the new non-negative counter value is counted as the first
 * observed delta after that reset. Totals saturate instead of overflowing.
 */
internal class NetworkUsageTracker(private val store: NetworkUsageTotalsStore) {
  fun sample(receivedCounterBytes: Long?, sentCounterBytes: Long?): PersistentNetworkTotals =
    synchronized(PROCESS_LOCK) {
      val previous = store.read()
      val received =
        advance(previous.receivedBytes, previous.lastReceivedCounterBytes, receivedCounterBytes)
      val sent = advance(previous.sentBytes, previous.lastSentCounterBytes, sentCounterBytes)
      val updated =
        PersistentNetworkTotals(
          receivedBytes = received.first,
          sentBytes = sent.first,
          lastReceivedCounterBytes = received.second,
          lastSentCounterBytes = sent.second,
        )

      // Keep the old persisted baseline if storage refuses the commit. The next sample can then
      // account for the full delta from that baseline rather than reporting an unpersisted total.
      if (updated == previous || store.write(updated)) updated else previous
    }

  private fun advance(
    total: Long,
    previousCounter: Long?,
    currentCounter: Long?,
  ): Pair<Long, Long?> {
    if (currentCounter == null || currentCounter < 0L) return total to previousCounter
    if (previousCounter == null) return total to currentCounter

    val delta =
      if (currentCounter >= previousCounter) currentCounter - previousCounter
      else currentCounter // Counter reset: count bytes observed since the reset.
    return saturatingAdd(total, delta) to currentCounter
  }

  private fun saturatingAdd(total: Long, delta: Long): Long =
    if (delta > Long.MAX_VALUE - total) Long.MAX_VALUE else total + delta

  private companion object {
    // All AppUsageMeter instances share one process-wide sampling lock because they share the
    // same app-wide TrafficStats counters and persisted totals.
    val PROCESS_LOCK = Any()
  }
}
