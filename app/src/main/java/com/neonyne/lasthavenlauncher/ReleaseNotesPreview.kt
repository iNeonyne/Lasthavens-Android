package com.neonyne.lasthavenlauncher

// Bound the notes passed to Compose Text to 16,384 UTF-16 code units.
internal const val MAX_RELEASE_NOTES_DISPLAY_LENGTH = 16 * 1024

internal fun releaseNotesPreview(notes: String): String {
  if (notes.length <= MAX_RELEASE_NOTES_DISPLAY_LENGTH) return notes

  val limit = MAX_RELEASE_NOTES_DISPLAY_LENGTH
  val end =
    if (notes[limit - 1].isHighSurrogate() && notes[limit].isLowSurrogate()) limit - 1 else limit
  return notes.substring(0, end)
}
