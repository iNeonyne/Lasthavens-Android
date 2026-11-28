package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonBridgeProtocolTest {
  @Test
  fun `parses known message types`() {
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest("ping", "r-1"),
      AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to "r-1")),
    )
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest("listAddons", "r-2"),
      AddonBridgeProtocol.parse(mapOf("type" to "listAddons", "requestId" to "r-2")),
    )
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest("getLoadOrder", "r-3"),
      AddonBridgeProtocol.parse(mapOf("type" to "getLoadOrder", "requestId" to "r-3")),
    )
  }

  @Test
  fun `parses the bootstrap plan request with an exact key set`() {
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest("getBootstrapPlan", "r-7"),
      AddonBridgeProtocol.parse(mapOf("type" to "getBootstrapPlan", "requestId" to "r-7")),
    )
    assertNull(
      AddonBridgeProtocol.parse(
        mapOf("type" to "getBootstrapPlan", "requestId" to "r-7", "extra" to "x"),
      ),
    )
  }

  @Test
  fun `bootstrap plan builder validates revisions, folders, paths and bounds`() {
    val valid =
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(
          AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("core.js", "ui/init.js")),
          AddonBridgeProtocol.BootstrapAddonEntry("UnitFrame", listOf("init.js")),
        ),
        listOf(AddonBridgeProtocol.BlockedAddon("Broken", "missingDependencies")),
      )
    assertNotNull(valid)
    val fields = valid?.toFields()
    assertEquals("bootstrapPlan", fields?.get("type"))
    assertEquals(
      listOf("core.js", "ui/init.js"),
      (fields?.get("addons") as List<*>).first().let { (it as Map<*, *>)["loadFiles"] },
    )

    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "short",
        "source-1",
        listOf(AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("core.js"))),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(AddonBridgeProtocol.BootstrapAddonEntry("blizzard_secret", emptyList())),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(
          AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("../escape.js")),
        ),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(
          AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("core.js")),
          AddonBridgeProtocol.BootstrapAddonEntry("depcore", listOf("other.js")),
        ),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("core.js", "core.js"))),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(
          AddonBridgeProtocol.BootstrapAddonEntry(
            "DepCore",
            List(AddonBridgeProtocol.MAX_RESPONSE_ITEMS + 1) { "f$it.js" },
          ),
        ),
        emptyList(),
      ),
    )
    assertNull(
      AddonBridgeProtocol.bootstrapPlan(
        "r-1",
        "a".repeat(64),
        "source-1",
        listOf(AddonBridgeProtocol.BootstrapAddonEntry("DepCore", listOf("core.js"))),
        listOf(AddonBridgeProtocol.BlockedAddon("Broken", "mystery")),
      ),
    )
  }

  @Test
  fun `rejects unknown or malformed fields`() {
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to "r", "extra" to 1)))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping")))
    assertNull(AddonBridgeProtocol.parse(mapOf("requestId" to "r")))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "evalJs", "requestId" to "r")))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to 5, "requestId" to "r")))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to 42)))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to "bad id!")))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to "x".repeat(65))))
    assertNull(AddonBridgeProtocol.parse(mapOf("type" to "ping", "requestId" to "")))
  }

  @Test
  fun `oversized message field count is rejected`() {
    val fields =
      (1..AddonBridgeProtocol.MAX_MESSAGE_FIELDS).associate { "k$it" to 1 } + ("type" to "ping")
    assertNull(AddonBridgeProtocol.parse(fields))
  }

  @Test
  fun `parses chunked payload requests with exact fields`() {
    val revision = "a".repeat(64)
    val infoFields = mapOf("type" to "getBundleInfo", "requestId" to "bundle-1")
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest("getBundleInfo", "bundle-1"),
      AddonBridgeProtocol.parse(infoFields),
    )

    val pageFields =
      mapOf(
        "type" to "getBundleManifestPage",
        "requestId" to "page-1",
        "revision" to revision,
        "page" to "31",
      )
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest(
        "getBundleManifestPage",
        "page-1",
        revision = revision,
        page = 31,
      ),
      AddonBridgeProtocol.parse(pageFields),
    )
    assertNull(AddonBridgeProtocol.parse(pageFields + ("page" to "32")))
    assertNull(AddonBridgeProtocol.parse(pageFields + ("page" to "00")))

    val chunkFields =
      mapOf(
        "type" to "getFileChunk",
        "requestId" to "chunk-1",
        "revision" to revision,
        "folder" to "unit-frame",
        "path" to "Textures/portrait.png",
        "offset" to "32768",
      )
    assertEquals(
      AddonBridgeProtocol.AddonBridgeRequest(
        "getFileChunk",
        "chunk-1",
        revision = revision,
        folder = "unit-frame",
        path = "Textures/portrait.png",
        offset = 32768L,
      ),
      AddonBridgeProtocol.parse(chunkFields),
    )
    assertNull(AddonBridgeProtocol.parse(chunkFields + ("path" to "../escape.lua")))
    assertNull(AddonBridgeProtocol.parse(chunkFields + ("folder" to "unit.frame")))
    assertNull(AddonBridgeProtocol.parse(chunkFields + ("offset" to "-1")))
  }

  @Test
  fun `builds bounded payload metadata page and file chunk responses`() {
    val file = java.io.File.createTempFile("addon-bridge", ".lua")
    try {
      file.writeBytes(byteArrayOf(1, 2, 3))
      val descriptor =
        AddonRuntimePayloadProvider.FileDescriptor(
          folder = "unit-frame",
          path = "init.lua",
          sizeBytes = 3,
          file = file,
          canonicalPath = file.canonicalPath,
          lastModified = file.lastModified(),
        )
      val manifest =
        AddonRuntimePayloadProvider.Manifest(
          revision = "a".repeat(64),
          sourceRevision = "rev1",
          addonFolders = listOf("unit-frame"),
          files = listOf(descriptor),
          filesByKey = mapOf("unit-frame/init.lua" to descriptor),
          totalBytes = 3,
        )
      val info = AddonBridgeProtocol.bundleInfo("info-1", manifest)!!
      assertEquals(1, info.toFields()["fileCount"])
      assertEquals(1, info.toFields()["pageCount"])
      assertEquals(3L, info.toFields()["totalBytes"])

      val page = AddonBridgeProtocol.manifestPage("page-1", manifest, 0, listOf(descriptor))!!
      assertEquals("bundleManifestPage", page.toFields()["type"])
      assertEquals(1, (page.toFields()["files"] as List<*>).size)

      val chunk =
        AddonBridgeProtocol.fileChunk(
          "chunk-1",
          manifest,
          "unit-frame",
          "init.lua",
          0,
          byteArrayOf(1, 2, 3),
        )!!
      assertEquals("fileChunk", chunk.toFields()["type"])
      assertEquals(true, chunk.toFields()["done"])
      assertEquals(3L, chunk.toFields()["totalBytes"])
      assertEquals("AQID", chunk.toFields()["data"])
    } finally {
      file.delete()
    }
  }

  @Test
  fun `ack and error builders validate input`() {
    assertEquals("ack", AddonBridgeProtocol.ack("r-1")!!.toFields()["type"])
    assertNull(AddonBridgeProtocol.ack("bad id!"))
    assertNull(AddonBridgeProtocol.error("r-1", "notACode"))
    assertEquals("invalidRequest", AddonBridgeProtocol.error("r-1", "invalidRequest")!!.code)
  }

  @Test
  fun `addon list enforces bounds and item text`() {
    val summary = AddonBridgeProtocol.AddonSummary("unit-frame", "0.5.1", true)
    assertNotNull(AddonBridgeProtocol.addonList("r-1", "rev1", listOf(summary)))
    assertNotNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("unit-frame", "", true)),
      ),
    )
    assertNotNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("unit-frame", "Release candidate 1", true)),
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("unit-frame", "bad\nversion", true)),
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        List(AddonBridgeProtocol.MAX_RESPONSE_ITEMS + 1) { summary },
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("../escape", "0.5.1", true)),
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("unit.frame", "0.5.1", true)),
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("blizzard_ui", "0.5.1", true)),
      ),
    )
    assertNull(
      AddonBridgeProtocol.addonList(
        "r-1",
        "rev1",
        listOf(
          AddonBridgeProtocol.AddonSummary("ok", "0.5.1", true),
          AddonBridgeProtocol.AddonSummary("a".repeat(129), "0.5.1", false),
        ),
      ),
    )
  }

  @Test
  fun `load order enforces bounds and item text`() {
    assertNotNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf("a"), listOf("b")))
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", List(257) { "addon$it" }, emptyList()))
    assertNull(
      AddonBridgeProtocol.loadOrder(
        "r-1",
        "rev1",
        List(129) { "addon$it" },
        List(128) { "blocked$it" },
      ),
    )
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf(".."), emptyList()))
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf("."), emptyList()))
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf("../escape"), emptyList()))
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf("unit.frame"), emptyList()))
    assertNull(AddonBridgeProtocol.loadOrder("r-1", "rev1", listOf("blizzard_ui"), emptyList()))
    assertNull(
      AddonBridgeProtocol.loadOrder(
        "r-1",
        "rev1",
        listOf("a".repeat(AddonBridgeProtocol.MAX_ITEM_CHARS + 1)),
        emptyList(),
      ),
    )
  }

  @Test
  fun `response byte count is bounded`() {
    assertTrue(AddonBridgeProtocol.isResponseWithinByteLimit(0))
    assertTrue(
      AddonBridgeProtocol.isResponseWithinByteLimit(AddonBridgeProtocol.MAX_RESPONSE_BYTES),
    )
    assertFalse(
      AddonBridgeProtocol.isResponseWithinByteLimit(AddonBridgeProtocol.MAX_RESPONSE_BYTES + 1),
    )
    assertFalse(AddonBridgeProtocol.isResponseWithinByteLimit(-1))
  }

  @Test
  fun `responses serialize to flat bounded field maps`() {
    val ack = AddonBridgeProtocol.ack("r-1")!!
    assertFalse(ack.toFields().values.any { it is Map<*, *> || it is List<*> })

    val list =
      AddonBridgeProtocol.addonList(
        "r-2",
        "rev1",
        listOf(AddonBridgeProtocol.AddonSummary("a", "1", false)),
      )!!
    assertEquals("listAddons", list.toFields()["type"])
    assertEquals("rev1", list.toFields()["revision"])

    val order = AddonBridgeProtocol.loadOrder("r-3", "rev1", listOf("a", "b"), listOf("c"))!!
    assertEquals(listOf("a", "b"), order.toFields()["loadOrder"])
    assertEquals(listOf("c"), order.toFields()["blocked"])
  }
}
