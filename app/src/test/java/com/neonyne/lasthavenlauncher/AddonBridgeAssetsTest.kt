package com.neonyne.lasthavenlauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the deterministic APK packaging of the bundled addon-bridge extension assets. */
class AddonBridgeAssetsTest {
  private val assetsDir =
    File("src/main/assets").takeIf { it.isDirectory } ?: File("app/src/main/assets")

  @Test
  fun `bridge assets exist at the documented location`() {
    val dir = File(assetsDir, AddonBridgePolicy.BUILTIN_LOCATION.removePrefix("/"))
    assertTrue(dir.isDirectory)
    assertTrue(File(dir, "manifest.json").isFile)
    assertTrue(File(dir, "background.js").isFile)
    assertTrue(File(dir, "payload-validation.js").isFile)
    assertTrue(File(dir, "bridge.js").isFile)
    assertTrue(File(dir, "xhr-overlay.js").isFile)
    assertTrue(File(dir, "worker-overlay.js").isFile)
    assertTrue(File(dir, "addon-bootstrap.js").isFile)
  }

  @Test
  fun `manifest declares exactly the launcher-owned extension identity`() {
    val manifest = File(assetsDir, "extensions/addon-bridge/manifest.json").readText()
    assertTrue(manifest.contains("\"id\": \"${AddonBridgePolicy.BRIDGE_EXTENSION_ID}\""))
    assertTrue(manifest.contains("\"manifest_version\": 2"))
    assertTrue(manifest.contains("\"version\": \"0.5.1\""))
    assertTrue(manifest.contains("\"run_at\": \"document_start\""))
    assertTrue(manifest.contains("\"https://lasthavens.com/play\""))
    assertTrue(manifest.contains("\"https://lasthavens.com/play/\""))
    assertTrue(manifest.contains("\"payload-validation.js\""))
    assertTrue(manifest.contains("\"bridge.js\""))
    assertTrue(manifest.contains("\"xhr-overlay.js\""))
    assertTrue(manifest.contains("\"worker-overlay.js\""))
    assertTrue(manifest.contains("\"addon-bootstrap.js\""))
    assertTrue(manifest.contains("\"world\": \"MAIN\""))
    assertFalse(manifest.contains("\"<all_urls>\""))
    assertFalse(manifest.contains("\"all_frames\": true"))
    assertEquals(1, Regex("\"content_scripts\"").findAll(manifest).count())
    assertEquals(4, Regex("\"matches\"").findAll(manifest).count())
    assertEquals(4, Regex("\"run_at\"").findAll(manifest).count())
    assertEquals(4, Regex("\"all_frames\"").findAll(manifest).count())
    assertEquals(3, Regex("\"world\": \"MAIN\"").findAll(manifest).count())
    assertTrue(manifest.contains("\"all_frames\": false"))
  }

  @Test
  fun `manifest requests required GeckoView messaging permissions only`() {
    val manifest = File(assetsDir, "extensions/addon-bridge/manifest.json").readText()
    assertTrue(manifest.contains("\"geckoViewAddons\""))
    assertTrue(manifest.contains("\"nativeMessaging\""))
    assertFalse(
      "content-script native messaging is not needed",
      manifest.contains("nativeMessagingFromContent"),
    )
    listOf("tabs", "cookies", "webRequest", "downloads", "history", "bookmarks", "<all_urls>")
      .forEach {
        assertFalse("manifest must not request $it", manifest.contains("\"$it\""))
      }
    assertTrue(
      "manifest must declare the exact-route content script",
      manifest.contains("content_scripts"),
    )
  }

  @Test
  fun `content bridge uses bounded messages and no page-forgeable channel`() {
    val bridge = File(assetsDir, "extensions/addon-bridge/bridge.js").readText()
    assertTrue(bridge.contains("browser.runtime.sendMessage"))
    assertTrue(bridge.contains("MAX_FILE_CHUNK_BYTES = 32 * 1024"))
    assertTrue(bridge.contains("MAX_STARTUP_WAIT_MS = 20000"))
    assertTrue(bridge.contains("window.wrappedJSObject"))
    assertTrue(bridge.contains("exportFunction"))
    assertTrue(bridge.contains("cloneInto"))
    assertTrue(bridge.contains("core.validatePayload(payload)"))
    assertFalse(bridge.contains("window.postMessage"))
    assertFalse(bridge.contains("eval("))
  }

  @Test
  fun `xhr overlay serves local payload without page-forgeable channels`() {
    val overlay = File(assetsDir, "extensions/addon-bridge/xhr-overlay.js").readText()
    assertTrue(overlay.contains("__LASTHAVEN_ADDON_BRIDGE__"))
    assertTrue(overlay.contains("getBundle"))
    assertTrue(overlay.contains("XMLHttpRequest"))
    assertTrue(overlay.contains("Interface/AddOns/"))
    assertFalse(overlay.contains("window.postMessage"))
    assertFalse(overlay.contains("eval("))
    assertFalse(overlay.contains("browser.storage"))
    assertFalse(overlay.contains("browser.runtime.sendMessage"))
    assertFalse(overlay.contains("indexedDB"))
  }

  @Test
  fun `worker overlay gates interception without page-forgeable channels`() {
    val overlay = File(assetsDir, "extensions/addon-bridge/worker-overlay.js").readText()
    assertTrue(overlay.contains("__LHAddonWorkerOverlayCore"))
    assertTrue(overlay.contains("setWorkerUrlAllowlist"))
    assertTrue(overlay.contains("em-pthread"))
    assertTrue(overlay.contains("parseBlocksIndex"))
    assertTrue(overlay.contains("maskWebVfsStoreIo"))
    assertTrue(overlay.contains("Interface/AddOns/"))
    assertFalse(overlay.contains("window.postMessage"))
    assertFalse(overlay.contains("eval("))
    assertFalse(overlay.contains("browser.storage"))
    assertFalse(overlay.contains("browser.runtime.sendMessage"))
    assertFalse(overlay.contains("indexedDB"))
  }

  @Test
  fun `payload validator checks the ZIP payload without page-controlled inputs`() {
    val core = File(assetsDir, "extensions/addon-bridge/payload-validation.js").readText()
    assertTrue(core.contains("function validatePayload(payload)"))
    assertTrue(core.contains("MAX_RUNTIME_BUNDLE_BYTES = 16 * 1024 * 1024"))
    assertTrue(core.contains("MAX_RUNTIME_FILES = 1024"))
    assertTrue(core.contains("MAX_RUNTIME_FILE_BYTES = 8 * 1024 * 1024"))
    assertTrue(core.contains("function deepFreeze(value)"))
  }

  @Test
  fun `assets stay within the deterministic size bounds`() {
    val dir = File(assetsDir, AddonBridgePolicy.BUILTIN_LOCATION.removePrefix("/"))
    val files = dir.walkTopDown().filter { it.isFile }.toList()
    assertTrue(files.size in 2..8)
    files.forEach { assertTrue("${it.name} too large", it.length() <= 64 * 1024) }
    assertEquals(7, files.size)
  }

  @Test
  fun `background script uses only the launcher native namespace`() {
    val background = File(assetsDir, "extensions/addon-bridge/background.js").readText()
    assertTrue(background.contains(AddonBridgePolicy.NATIVE_MESSAGING_NAME))
    assertTrue(background.contains("function scheduleReconnect()"))
    assertTrue(background.contains("MAX_RECONNECT_DELAY_MS = 30000"))
    assertTrue(background.contains("nextPort.onDisconnect"))
    assertTrue(background.contains("stalePort.disconnect()"))
    assertTrue(background.contains("requestNative(\"ping\")"))
    assertTrue(background.contains("MAX_PENDING_REQUESTS = 8"))
    assertTrue(background.contains("MAX_RUNTIME_BUNDLE_BYTES = 16 * 1024 * 1024"))
    assertTrue(background.contains("getFileChunk"))
    assertFalse(background.contains("browser.storage.local"))
    assertFalse(background.contains("window.postMessage"))
    assertFalse(background.contains("getAddonBundle()"))
    assertFalse(background.contains("eval("))
    assertFalse(background.contains("XMLHttpRequest"))
  }
}
