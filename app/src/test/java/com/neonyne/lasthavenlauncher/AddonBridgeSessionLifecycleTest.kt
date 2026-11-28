package com.neonyne.lasthavenlauncher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the addon bridge's session-identity lifecycle (issue #183): replacing the game session
 * must disconnect ports held for the previous session, reject late callbacks through the request
 * generation, stay idempotent for the same session instance, and free the connection limit so the
 * new session can connect.
 *
 * These are source-contract tests, not GeckoView runtime tests. Physical ARM64 verification remains
 * a separate acceptance requirement.
 */
class AddonBridgeSessionLifecycleTest {
  private val sourceRoot =
    File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")

  private fun controllerSource(): String =
    File(sourceRoot, "com/neonyne/lasthavenlauncher/AddonBridgeController.kt").readText()

  private fun setGameSessionSource(): String =
    controllerSource()
      .substringAfter("fun setGameSession(")
      .substringBefore("private fun disconnectAllPorts")

  @Test
  fun `session switch disconnects the previous session ports and frees the connection limit`() {
    val source = controllerSource()
    val setGameSession = setGameSessionSource()

    assertTrue(
      "session replacement must disconnect the ports held for the old session",
      setGameSession.contains("disconnectAllPorts()"),
    )
    assertTrue(
      "the port teardown helper must drop every tracked connection before disconnecting",
      source.contains(
        "private fun disconnectAllPorts() {\n" +
          "    val portsToDisconnect = connectedPorts.toList()\n" +
          "    connectedPorts.clear()\n" +
          "    portsToDisconnect.forEach { it.disconnect() }\n" +
          "  }",
      ),
    )
    assertTrue(
      "new connections must still pass the one-connection limit check against the freed list",
      source.contains("AddonBridgeTimeoutPolicy.canAcceptConnection(connectedPorts.size)"),
    )
  }

  @Test
  fun `late messages and callbacks from the previous session are rejected after the switch`() {
    val source = controllerSource()
    val setGameSession = setGameSessionSource()

    assertTrue(
      "the request generation must advance on session replacement",
      setGameSession.contains("requestGeneration += 1"),
    )
    assertTrue(
      "pending chunk requests must be cleared on session replacement",
      setGameSession.contains("clearPendingRequests()"),
    )
    val fileChunkCompletion =
      source
        .substringAfter("private fun handleFileChunk(")
        .substringBefore("private fun senderAllowed(")
    assertTrue(
      "worker completion must reject results whose request generation is stale",
      fileChunkCompletion.contains("pending.generation != requestGeneration"),
    )
    val timeoutRunnable =
      source
        .substringAfter("val timeout = Runnable {")
        .substringBefore("pending = PendingPortRequest")
    assertTrue(
      "port timeouts must not answer a request whose generation is stale",
      timeoutRunnable.contains("generation == requestGeneration"),
    )
  }

  @Test
  fun `repeated setGameSession with the same session instance keeps the active connection`() {
    val setGameSession = setGameSessionSource()

    assertTrue(
      "an unchanged session identity must be an early-return no-op",
      setGameSession
        .trimStart()
        .startsWith("session: GeckoSession?) {\n    if (session === gameSession) return"),
    )
  }

  @Test
  fun `switching to null and teardown disconnect resources and clear pending requests`() {
    val source = controllerSource()
    val setGameSession = setGameSessionSource()
    val teardown =
      source.substringAfter("fun teardown() {").substringBefore("private fun disconnectAllPorts")

    assertTrue(
      "clearing the session must still advance the generation and drop pending requests",
      setGameSession.contains("requestGeneration += 1") &&
        setGameSession.contains("clearPendingRequests()"),
    )
    assertTrue(
      "teardown must disconnect every tracked port",
      teardown.contains("disconnectAllPorts()"),
    )
    assertTrue(
      "teardown must clear pending requests and forget the session",
      teardown.contains("clearPendingRequests()") && teardown.contains("gameSession = null"),
    )
    assertTrue(
      "teardown must detach the native message delegate",
      teardown.contains("setMessageDelegate(null, AddonBridgePolicy.NATIVE_MESSAGING_NAME)"),
    )
  }

  @Test
  fun `bootstrap plan requests run through the bounded async pending-request path`() {
    val source = controllerSource()

    assertTrue(
      "the port delegate must route getBootstrapPlan to the async handler",
      source.contains("request.type == \"getBootstrapPlan\""),
    )
    val handler =
      source
        .substringAfter("private fun handleBootstrapPlan(")
        .substringBefore("private fun blockedReasonCode")
    assertTrue(
      "the bootstrap handler must register the request so timeouts and generations apply",
      handler.contains("registerPendingRequest(request.requestId, port, generation)"),
    )
    assertTrue(
      "the bootstrap handler must compute on the worker executor, not the message thread",
      handler.contains("worker.execute {"),
    )
    assertTrue(
      "late bootstrap responses must be dropped when the generation moved on",
      handler.contains("pending.generation != requestGeneration"),
    )
    assertTrue(
      "the bootstrap handler must surface blocked addons with protocol reasons",
      source.contains("private fun blockedReasonCode(reason: AddonBlockedReason): String"),
    )
  }
}
