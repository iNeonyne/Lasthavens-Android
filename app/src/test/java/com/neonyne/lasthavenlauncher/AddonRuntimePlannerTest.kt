package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers stable dependency ordering and the bounds expected at the future runtime boundary. */
class AddonRuntimePlannerTest {
  private fun item(
    folder: String,
    enabled: Boolean = true,
    dependencies: List<String> = emptyList(),
    optionalDependencies: List<String> = emptyList(),
  ) =
    AddonLibraryItem(
      title = folder,
      folder = folder,
      version = "1.0.0",
      interfaceVersion = "30300",
      notes = "",
      dependencies = dependencies,
      sizeBytes = 128L,
      filesCount = 2,
      enabled = enabled,
      importedAt = 1000L,
      warnings = emptyList(),
      optionalDependencies = optionalDependencies,
    )

  @Test
  fun dependenciesLoadBeforeDependentsInStableOrder() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "revision-one",
          listOf(
            item("BagSort", dependencies = listOf("AceCore")),
            item("AceCore"),
            item("QuestHelper", dependencies = listOf("BagSort")),
          ),
        ),
      )
    assertEquals(
      listOf("AceCore", "BagSort", "QuestHelper"),
      plan.orderedAddons.map { it.folder },
    )
    assertTrue(plan.blockedAddons.isEmpty())
  }

  @Test
  fun missingDependenciesPropagateAndDisabledAddonsAreExcluded() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "revision-two",
          listOf(
            item("DisabledLib", enabled = false),
            item("MidLib", dependencies = listOf("DisabledLib")),
            item("TopAddon", dependencies = listOf("MidLib")),
            item("HealthyAddon"),
          ),
        ),
      )
    assertEquals(listOf("HealthyAddon"), plan.orderedAddons.map { it.folder })
    assertTrue(plan.blockedAddons["MidLib"] is AddonBlockedReason.MissingDependencies)
    assertTrue(plan.blockedAddons["TopAddon"] is AddonBlockedReason.MissingDependencies)
  }

  @Test
  fun dependencyCyclesAreBlockedWithoutBlockingIndependentAddons() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "revision-three",
          listOf(
            item("Alpha", dependencies = listOf("Beta")),
            item("Beta", dependencies = listOf("Alpha")),
            item("Standalone"),
          ),
        ),
      )
    assertEquals(listOf("Standalone"), plan.orderedAddons.map { it.folder })
    assertEquals(AddonBlockedReason.DependencyCycle, plan.blockedAddons["Alpha"])
    assertEquals(AddonBlockedReason.DependencyCycle, plan.blockedAddons["Beta"])
  }

  @Test
  fun tocPathsIgnoreCommentsAndRejectUnsafeOrDuplicateEntries() {
    val files =
      AddonRuntimePlanner.parseTocLoadFiles(
        """
        ## Interface: 30300
        # Comment
        Core\Init.lua
        Core/Init.lua
        ../escape.lua
        /absolute.lua
        UI/Panel.xml
        """
          .trimIndent(),
      )
    assertEquals(listOf("Core/Init.lua", "UI/Panel.xml"), files)
  }

  @Test
  fun emptyPlanAndRevisionAreDeterministicForSameSnapshot() {
    val snapshot = AddonLibrarySnapshot("empty-revision", emptyList())
    val first = AddonRuntimePlanner.plan(snapshot)
    val second = AddonRuntimePlanner.plan(snapshot)
    assertTrue(first.isEmpty)
    assertEquals(first.bundleRevision, second.bundleRevision)

    val enabled = AddonRuntimePlanner.plan(AddonLibrarySnapshot("r1", listOf(item("Addon"))))
    val changed = AddonRuntimePlanner.plan(AddonLibrarySnapshot("r2", listOf(item("Addon"))))
    assertNotEquals(enabled.bundleRevision, changed.bundleRevision)
  }

  @Test
  fun requiredDependenciesMatchFolderNamesCaseInsensitively() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "case-fold",
          listOf(item("BagSort", dependencies = listOf("aceCORE")), item("AceCore")),
        ),
      )
    assertEquals(listOf("AceCore", "BagSort"), plan.orderedAddons.map { it.folder })
    assertTrue(plan.blockedAddons.isEmpty())
  }

  @Test
  fun optionalDependenciesPreferProviderBeforeConsumerWithoutBlocking() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "optional-order",
          listOf(
            item("AConsumer", optionalDependencies = listOf("ZProvider")),
            item("ZProvider", optionalDependencies = listOf("OptionalExtra")),
            item("Independent"),
            item("OptionalExtra"),
          ),
        ),
      )
    val ordered = plan.orderedAddons.map { it.folder }
    assertTrue(ordered.indexOf("ZProvider") < ordered.indexOf("AConsumer"))
    assertTrue(plan.blockedAddons.isEmpty())
  }

  @Test
  fun deepDependencyChainIsPlannedWithoutRecursiveTraversal() {
    val addons =
      (0 until 512).map { index ->
        item(
          "Addon$index",
          dependencies = if (index == 0) emptyList() else listOf("Addon${index - 1}"),
        )
      }
    val plan = AddonRuntimePlanner.plan(AddonLibrarySnapshot("deep-chain", addons))
    assertEquals(512, plan.orderedAddons.size)
    assertEquals("Addon0", plan.orderedAddons.first().folder)
    assertEquals(
      "Addon511",
      plan.orderedAddons.last().folder,
    )
  }

  @Test
  fun plannerAcceptsMoreAddonsThanOneImportArchiveCanContain() {
    val addons = (0..AddonArchive.MAX_ADDONS_PER_ARCHIVE).map { item("Addon$it") }
    val plan = AddonRuntimePlanner.plan(AddonLibrarySnapshot("multi-import-library", addons))
    assertEquals(AddonArchive.MAX_ADDONS_PER_ARCHIVE + 1, plan.orderedAddons.size)
  }

  @Test
  fun plannerRejectsSnapshotsBeyondValidatedLibraryBounds() {
    val oversized = (0..MAX_ADDON_LIBRARY_ITEMS).map { item("Addon$it") }
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      AddonRuntimePlanner.plan(AddonLibrarySnapshot("oversized", oversized))
    }
  }

  @Test
  fun tocLoadOrderRejectsOversizedInput() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      AddonRuntimePlanner.parseTocLoadFiles("x".repeat(AddonArchive.MAX_TOC_BYTES.toInt() + 1))
    }
  }

  @Test
  fun oversizedAddonsAreBlockedWithTooLargeAndOthersStayLoadable() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "size-limit-revision",
          listOf(
            item("Oversized").copy(sizeBytes = AddonLimits.MAX_RUNTIME_BUNDLE_BYTES + 1),
            item("Healthy"),
          ),
        ),
      )

    assertEquals(listOf("Healthy"), plan.orderedAddons.map { it.folder })
    val reason = plan.blockedAddons["Oversized"]
    assertTrue(reason is AddonBlockedReason.TooLarge)
    assertTrue(
      (reason as AddonBlockedReason.TooLarge)
        .detail
        .contains(
          "${AddonLimits.MAX_RUNTIME_BUNDLE_BYTES / (1024L * 1024L)} MiB",
        ),
    )
  }

  @Test
  fun addonExactlyAtTheRuntimeBundleLimitRemainsLoadable() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "boundary-revision",
          listOf(item("Boundary").copy(sizeBytes = AddonLimits.MAX_RUNTIME_BUNDLE_BYTES)),
        ),
      )

    assertTrue(plan.blockedAddons.isEmpty())
    assertEquals(listOf("Boundary"), plan.orderedAddons.map { it.folder })
  }

  @Test
  fun dependentsOfOversizedAddonsAreAlsoBlocked() {
    val plan =
      AddonRuntimePlanner.plan(
        AddonLibrarySnapshot(
          "propagation-revision",
          listOf(
            item("OversizedLib").copy(sizeBytes = AddonLimits.MAX_RUNTIME_BUNDLE_BYTES + 1),
            item("Dependent", dependencies = listOf("OversizedLib")),
            item("Healthy"),
          ),
        ),
      )

    assertEquals(listOf("Healthy"), plan.orderedAddons.map { it.folder })
    assertTrue(plan.blockedAddons.getValue("Dependent") is AddonBlockedReason.MissingDependencies)
  }
}
