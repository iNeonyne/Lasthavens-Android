package com.neonyne.lasthavenlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesPreviewTest {
  @Test
  fun leavesEmptyNotesEmpty() {
    assertEquals("", releaseNotesPreview(""))
  }

  @Test
  fun preservesNormalNotesAsPlainText() {
    val notes = "# Changes\n<b>Bug fixes</b> and better performance 🚀"
    assertEquals(notes, releaseNotesPreview(notes))
  }

  @Test
  fun preservesNotesAtTheLimit() {
    val notes = "a".repeat(16_384)
    assertEquals(notes, releaseNotesPreview(notes))
  }

  @Test
  fun truncatesNotesAboveTheLimit() {
    val prefix = "a".repeat(16_384)
    assertEquals(prefix, releaseNotesPreview(prefix + "More notes"))
  }

  @Test
  fun preservesASurrogatePairThatFitsAtTheLimit() {
    val prefix = "a".repeat(16_382) + "🚀"
    assertEquals(prefix, releaseNotesPreview(prefix + "More notes"))
  }

  @Test
  fun dropsTheWholeSurrogatePairWhenItCrossesTheLimit() {
    val prefix = "a".repeat(16_383)
    assertEquals(prefix, releaseNotesPreview(prefix + "🚀"))
  }
}
