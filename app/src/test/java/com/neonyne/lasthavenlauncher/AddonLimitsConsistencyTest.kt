package com.neonyne.lasthavenlauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fails on deliberate drift between the authoritative Kotlin limits ([AddonLimits]) and the values
 * re-stated by the bundled JavaScript validators (issue #228).
 *
 * The page realm cannot read Kotlin constants, so `payload-validation.js` repeats the runtime
 * bounds; this test parses both sources and asserts identical values.
 */
class AddonLimitsConsistencyTest {
  private val sourceRoot = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

  private fun kotlinLimitsSource(): String =
    File(sourceRoot, "java/com/neonyne/lasthavenlauncher/AddonLimits.kt").readText()

  private fun javascriptValidatorSource(): String =
    File(sourceRoot, "assets/extensions/addon-bridge/payload-validation.js").readText()

  @Test
  fun javascriptPayloadValidatorsMatchTheAuthoritativeKotlinLimits() {
    val kotlinSource = kotlinLimitsSource()
    val javascriptSource = javascriptValidatorSource()

    fun kotlinLimit(name: String): Long {
      val scaled = Regex("const val $name = ([0-9]+)L? \\* 1024 \\* 1024").find(kotlinSource)
      if (scaled != null) return scaled.groupValues[1].toLong() * 1024 * 1024
      val plain = Regex("const val $name = ([0-9]+)").find(kotlinSource)
      return plain?.groupValues[1]?.toLong() ?: throw AssertionError("AddonLimits is missing $name")
    }

    fun javascriptLimit(name: String): Long {
      val scaled = Regex("const $name = ([0-9]+) \\* 1024 \\* 1024").find(javascriptSource)
      if (scaled != null) return scaled.groupValues[1].toLong() * 1024 * 1024
      val plain = Regex("const $name = ([0-9]+);").find(javascriptSource)
      return plain?.groupValues[1]?.toLong()
        ?: throw AssertionError("payload-validation.js is missing $name")
    }

    assertEquals(
      kotlinLimit("MAX_RUNTIME_BUNDLE_BYTES"),
      javascriptLimit("MAX_RUNTIME_BUNDLE_BYTES"),
    )
    assertEquals(
      kotlinLimit("MAX_RUNTIME_FILE_BYTES"),
      javascriptLimit("MAX_RUNTIME_FILE_BYTES"),
    )
    assertEquals(kotlinLimit("MAX_RUNTIME_FILES"), javascriptLimit("MAX_RUNTIME_FILES"))
    assertEquals(kotlinLimit("MAX_RUNTIME_ADDONS"), javascriptLimit("MAX_RUNTIME_ADDONS"))
    assertEquals(kotlinLimit("MAX_RUNTIME_PATH_CHARS"), javascriptLimit("MAX_RUNTIME_PATH_CHARS"))
  }

  @Test
  fun kotlinImportAndRuntimeConsumersAliasTheAuthoritativeLimits() {
    val archiveSource =
      File(sourceRoot, "java/com/neonyne/lasthavenlauncher/AddonArchive.kt").readText()
    val providerSource =
      File(sourceRoot, "java/com/neonyne/lasthavenlauncher/AddonRuntimePayloadProvider.kt")
        .readText()
    val kotlinSource = kotlinLimitsSource()

    assertTrue(
      "import-side constants must be aliases of AddonLimits",
      setOf("MAX_ARCHIVE_BYTES", "MAX_EXPANDED_BYTES", "MAX_FILE_BYTES", "MAX_LIBRARY_BYTES").all {
        archiveSource.contains("const val $it = AddonLimits.$it")
      },
    )
    assertTrue(
      "runtime-side constants must be aliases of AddonLimits",
      setOf(
          "MAX_RUNTIME_BUNDLE_BYTES",
          "MAX_RUNTIME_FILE_BYTES",
          "MAX_RUNTIME_FILES",
          "MAX_RUNTIME_ADDONS",
          "MAX_RUNTIME_PATH_CHARS",
          "MAX_RUNTIME_ENTRIES_PER_ADDON",
        )
        .all { providerSource.contains("= AddonLimits.$it") },
    )
    assertTrue(
      "every shared limit must exist in AddonLimits",
      setOf(
          "MAX_ARCHIVE_BYTES",
          "MAX_EXPANDED_BYTES",
          "MAX_FILE_BYTES",
          "MAX_LIBRARY_BYTES",
          "MAX_RUNTIME_BUNDLE_BYTES",
          "MAX_RUNTIME_FILE_BYTES",
          "MAX_RUNTIME_FILES",
          "MAX_RUNTIME_ADDONS",
          "MAX_RUNTIME_PATH_CHARS",
          "MAX_RUNTIME_ENTRIES_PER_ADDON",
        )
        .all { kotlinSource.contains("const val $it") },
    )
  }
}
