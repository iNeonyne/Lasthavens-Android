package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionCallbackGateTest {
  @Test
  fun `advance returns a new generation for each replacement session`() {
    val gate = GameSessionCallbackGate()

    val first = gate.advance()
    val second = gate.advance()

    assertEquals(1, first)
    assertEquals(2, second)
    assertEquals(2, gate.generation)
  }

  @Test
  fun `accepts only callbacks from the current generation`() {
    val gate = GameSessionCallbackGate()
    val sessionA = gate.advance()

    assertTrue(gate.accepts(sessionA))
    assertFalse(gate.accepts(sessionA + 1))
    assertFalse(gate.accepts(0))
  }

  @Test
  fun `callback from replaced session is ignored after replacement`() {
    val gate = GameSessionCallbackGate()
    val sessionACallbackGeneration = gate.advance()

    gate.advance()

    assertFalse(gate.accepts(sessionACallbackGeneration))
  }

  @Test
  fun `replacement session callbacks stay accepted after stale ones arrive`() {
    val gate = GameSessionCallbackGate()
    val sessionACallbackGeneration = gate.advance()
    val sessionBCallbackGeneration = gate.advance()

    assertFalse(gate.accepts(sessionACallbackGeneration))
    assertTrue(gate.accepts(sessionBCallbackGeneration))
  }

  @Test
  fun `callbacks from the initial generation are accepted before any replacement`() {
    val gate = GameSessionCallbackGate(initialGeneration = 0)

    assertTrue(gate.accepts(0))
  }
}
