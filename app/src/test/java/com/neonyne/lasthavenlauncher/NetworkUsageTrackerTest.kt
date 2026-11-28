package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUsageTrackerTest {
  private class MemoryStore(
    var totals: PersistentNetworkTotals = PersistentNetworkTotals(),
    var failWrites: Boolean = false,
  ) : NetworkUsageTotalsStore {
    override fun read(): PersistentNetworkTotals = totals

    override fun write(totals: PersistentNetworkTotals): Boolean {
      if (failWrites) return false
      this.totals = totals
      return true
    }
  }

  @Test
  fun firstSampleEstablishesBaselineWithoutInventingEarlierTraffic() {
    val store = MemoryStore()
    val tracker = NetworkUsageTracker(store)

    val totals = tracker.sample(receivedCounterBytes = 1_000L, sentCounterBytes = 400L)

    assertEquals(0L, totals.receivedBytes)
    assertEquals(0L, totals.sentBytes)
    assertEquals(1_000L, totals.lastReceivedCounterBytes)
    assertEquals(400L, totals.lastSentCounterBytes)
  }

  @Test
  fun accumulatesIndependentReceivedAndSentDeltas() {
    val tracker = NetworkUsageTracker(MemoryStore())
    tracker.sample(receivedCounterBytes = 100L, sentCounterBytes = 200L)

    val totals = tracker.sample(receivedCounterBytes = 175L, sentCounterBytes = 235L)

    assertEquals(75L, totals.receivedBytes)
    assertEquals(35L, totals.sentBytes)
  }

  @Test
  fun unavailableCounterPreservesItsBaselineAndTheOtherDirectionStillAccumulates() {
    val tracker = NetworkUsageTracker(MemoryStore())
    tracker.sample(receivedCounterBytes = 100L, sentCounterBytes = 300L)

    val partial = tracker.sample(receivedCounterBytes = null, sentCounterBytes = 350L)
    assertEquals(0L, partial.receivedBytes)
    assertEquals(50L, partial.sentBytes)
    assertEquals(100L, partial.lastReceivedCounterBytes)

    val recovered = tracker.sample(receivedCounterBytes = 160L, sentCounterBytes = -1L)
    assertEquals(60L, recovered.receivedBytes)
    assertEquals(50L, recovered.sentBytes)
    assertEquals(350L, recovered.lastSentCounterBytes)
  }

  @Test
  fun lowerCounterIsTreatedAsResetAndCountsBytesSinceTheReset() {
    val tracker = NetworkUsageTracker(MemoryStore())
    tracker.sample(receivedCounterBytes = 900L, sentCounterBytes = 700L)
    tracker.sample(receivedCounterBytes = 1_000L, sentCounterBytes = 750L)

    val afterReset = tracker.sample(receivedCounterBytes = 25L, sentCounterBytes = 10L)

    assertEquals(125L, afterReset.receivedBytes)
    assertEquals(60L, afterReset.sentBytes)
    assertEquals(25L, afterReset.lastReceivedCounterBytes)
    assertEquals(10L, afterReset.lastSentCounterBytes)
  }

  @Test
  fun persistedTotalsAndBaselinesContinueAcrossTrackerRecreation() {
    val store = MemoryStore()
    val firstProcess = NetworkUsageTracker(store)
    firstProcess.sample(receivedCounterBytes = 10_000L, sentCounterBytes = 2_000L)
    val committed = firstProcess.sample(receivedCounterBytes = 10_250L, sentCounterBytes = 2_080L)
    assertEquals(250L, committed.receivedBytes)
    assertEquals(80L, committed.sentBytes)

    val nextProcess = NetworkUsageTracker(store)
    val continued = nextProcess.sample(receivedCounterBytes = 10_500L, sentCounterBytes = 2_100L)

    assertEquals(500L, continued.receivedBytes)
    assertEquals(100L, continued.sentBytes)
  }

  @Test
  fun failedPersistenceDoesNotAdvanceTheCommittedBaseline() {
    val store = MemoryStore()
    val tracker = NetworkUsageTracker(store)
    tracker.sample(receivedCounterBytes = 100L, sentCounterBytes = 50L)
    store.failWrites = true

    val uncommitted = tracker.sample(receivedCounterBytes = 200L, sentCounterBytes = 90L)
    assertEquals(0L, uncommitted.receivedBytes)
    assertEquals(100L, uncommitted.lastReceivedCounterBytes)

    store.failWrites = false
    val retried = tracker.sample(receivedCounterBytes = 250L, sentCounterBytes = 100L)
    assertEquals(150L, retried.receivedBytes)
    assertEquals(50L, retried.sentBytes)
  }

  @Test
  fun totalSaturatesInsteadOfOverflowing() {
    val store =
      MemoryStore(
        PersistentNetworkTotals(
          receivedBytes = Long.MAX_VALUE - 5L,
          sentBytes = Long.MAX_VALUE - 2L,
          lastReceivedCounterBytes = 100L,
          lastSentCounterBytes = 100L,
        ),
      )
    val totals = NetworkUsageTracker(store).sample(110L, 110L)

    assertEquals(Long.MAX_VALUE, totals.receivedBytes)
    assertEquals(Long.MAX_VALUE, totals.sentBytes)
    assertTrue(totals.receivedBytes >= 0L)
    assertTrue(totals.sentBytes >= 0L)
  }

  @Test
  fun unknownCounterRemainsUnknownUntilFirstValidSample() {
    val tracker = NetworkUsageTracker(MemoryStore())

    val unavailable = tracker.sample(receivedCounterBytes = -1L, sentCounterBytes = null)
    assertNull(unavailable.lastReceivedCounterBytes)
    assertNull(unavailable.lastSentCounterBytes)

    val baseline = tracker.sample(receivedCounterBytes = 12L, sentCounterBytes = 8L)
    assertEquals(0L, baseline.receivedBytes)
    assertEquals(0L, baseline.sentBytes)
  }
}
