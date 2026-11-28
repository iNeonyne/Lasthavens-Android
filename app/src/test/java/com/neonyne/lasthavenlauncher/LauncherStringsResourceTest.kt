package com.neonyne.lasthavenlauncher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New launcher copy intentionally lives in the default resource set; untranslated locales use
 * Android's normal fallback rather than duplicated English values. Positional format arguments are
 * checked explicitly so the extraction preserves runtime formatting.
 *
 * Lightweight resource-resolution verification for the launcher-owned string extraction
 * (issue #149): every `R.string` reference in launcher UI code must resolve to a declared resource,
 * and the declared resources must be well-formed with unique names and valid positional format
 * args.
 */
class LauncherStringsResourceTest {
  private fun moduleRoot(): File =
    sequenceOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
      ?: error("Could not locate src/main/res from the unit-test working directory")

  private fun loadDeclaredStrings(): Map<String, String> {
    val file = File(moduleRoot(), "values/strings.xml")
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodes = document.getElementsByTagName("string")
    val declared = mutableMapOf<String, String>()
    for (index in 0 until nodes.length) {
      val element = nodes.item(index) as org.w3c.dom.Element
      val name =
        element.getAttribute("name")
          ?: error("strings.xml contains a string without a name attribute")
      assertTrue("Duplicate string name: $name", name !in declared)
      declared[name] = element.textContent
    }
    return declared
  }

  @Test
  fun `launcher string resources are declared exactly once with valid positional arguments`() {
    val declared = loadDeclaredStrings()
    assertTrue("No string resources found", declared.isNotEmpty())
    val validSpecifier = Regex("%(?:\\d+\\$[sdf]|%)")
    declared.forEach { (name, value) ->
      assertTrue("Resource $name has a blank value", value.isNotBlank())
      val leftover = validSpecifier.replace(value, "")
      assertTrue(
        "Resource $name contains a non-positional or invalid % specifier: $leftover",
        !leftover.contains('%'),
      )
    }
  }

  @Test
  fun `launcher UI code only references declared string resources`() {
    val declared = loadDeclaredStrings()
    val sourceRoots = sequenceOf(File("src/main/java"), File("app/src/main/java"))
    val sources =
      sourceRoots
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" } }
        .toList()
    assertTrue("No Kotlin sources found under src/main/java", sources.isNotEmpty())
    val reference = Regex("""R\.string\.([A-Za-z0-9_]+)""")
    val referenced = mutableSetOf<String>()
    sources.forEach { file ->
      reference.findAll(file.readText()).forEach { referenced += it.groupValues[1] }
    }
    assertTrue("No R.string references found in launcher sources", referenced.isNotEmpty())
    val undeclared = referenced - declared.keys
    assertTrue("Undeclared string resources referenced: $undeclared", undeclared.isEmpty())
  }

  @Test
  fun `default locale fallback strings are not duplicated in translated resources`() {
    val resourcesRoot = moduleRoot()
    val defaultDocument =
      DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(resourcesRoot, "values/strings.xml"))
    val defaultNodes = defaultDocument.getElementsByTagName("string")
    val fallbackNames = mutableSetOf<String>()
    for (index in 0 until defaultNodes.length) {
      val element = defaultNodes.item(index) as org.w3c.dom.Element
      if ("MissingTranslation" in element.getAttribute("tools:ignore").split(",")) {
        fallbackNames += element.getAttribute("name")
      }
    }
    assertTrue("No default-locale fallback strings were marked", fallbackNames.isNotEmpty())

    val translatedResources =
      resourcesRoot.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") }
    translatedResources.forEach { directory ->
      val stringsFile = File(directory, "strings.xml")
      if (!stringsFile.isFile) return@forEach
      val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
      val nodes = document.getElementsByTagName("string")
      for (index in 0 until nodes.length) {
        val element = nodes.item(index) as org.w3c.dom.Element
        val name = element.getAttribute("name")
        assertFalse(
          "Default-locale fallback string $name is duplicated in ${directory.name}",
          name in fallbackNames,
        )
      }
    }
  }

  @Test
  fun `extracted launcher strings preserve their exact original wording`() {
    val declared = loadDeclaredStrings()
    val expected =
      mapOf(
        "home_tagline" to "Return to Azeroth.",
        "home_game_title" to "Wrath of the Lich King",
        "home_game_subtitle" to "Your adventure continues where you left it.",
        "home_exit_title" to "Exit LastHavenPlus?",
        "home_exit_message" to "Your game data remains stored on this device.",
        "game_load_failed_title" to "Unable to load LastHaven",
        "game_load_failed_hint" to "Check your connection and try again.",
        "game_crashed_title" to "LastHaven stopped unexpectedly",
        "game_crashed_hint" to "The game session ended. Restore the game to continue playing.",
        "settings_storage_hint" to
          "Includes app-private files and GeckoView data; it is not a profile-only measurement.",
        "settings_network_hint" to
          "Per-app totals include game and updater traffic. " +
            "They are stored locally and reset on device reboot.",
        "diagnostics_privacy_hint" to "Logs are stored on this device. Review them before sharing.",
        "action_back" to "Back",
        "settings_brand_mark" to "LASTHAVENPLUS",
        "updates_title" to "App updates",
        "updates_status_section" to "Update status",
        "updates_checking" to "Checking the stable release channel…",
        "updates_available" to "LastHavenPlus %1\$s is available.",
        "updates_delta_available" to "A smaller differential package is available (%1\$s MiB).",
        "updates_downloading_delta" to "Downloading differential update…",
        "updates_downloading_full" to "Downloading full APK…",
        "updates_download_smaller" to "Download smaller update",
        "updates_download_full" to "Download update",
        "updates_download_full_instead" to "Download full APK instead",
        "updates_no_info" to "No update information yet. Check for updates from Settings.",
        "updates_download_status" to "Download status",
      )
    expected.forEach { (name, wording) ->
      assertTrue("Missing string resource: $name", name in declared)
      assertTrue(
        "Resource $name changed wording: was \"$wording\", now \"${declared[name]}\"",
        declared[name] == wording,
      )
    }
  }
}
