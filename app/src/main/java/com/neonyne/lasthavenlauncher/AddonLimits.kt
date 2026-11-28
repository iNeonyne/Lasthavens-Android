package com.neonyne.lasthavenlauncher

/**
 * Authoritative addon size and count limits (issue #228).
 *
 * Every import, library, runtime-payload and bridge limit must be defined here exactly once and
 * aliased by the consuming objects, so the values cannot drift between the Kotlin validators and
 * the bundled JavaScript validators. [AddonArchive] enforces the import-side bounds,
 * [AddonRuntimePayloadProvider] the runtime payload bounds, and `payload-validation.js` re-states
 * the runtime bounds for the page realm; `AddonLimitsConsistencyTest` fails if any copy drifts.
 *
 * Import limits are intentionally wider than runtime limits: an addon may be storable and
 * exportable yet still too large for the game runtime. Such addons are isolated by the runtime
 * planner ([AddonBlockedReason.TooLarge]) instead of breaking the whole runtime manifest, and the
 * manager surfaces the exact offending file and limit through the import warnings.
 */
object AddonLimits {
  const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
  const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
  const val MAX_FILE_BYTES = 64L * 1024 * 1024
  const val MAX_TOC_BYTES = 1024L * 1024
  const val MAX_LIBRARY_BYTES = 384L * 1024 * 1024

  const val MAX_RUNTIME_BUNDLE_BYTES = 16L * 1024 * 1024
  const val MAX_RUNTIME_FILE_BYTES = 8L * 1024 * 1024
  const val MAX_RUNTIME_FILES = 1024
  const val MAX_RUNTIME_ADDONS = 128
  const val MAX_RUNTIME_PATH_CHARS = 512
  const val MAX_RUNTIME_ENTRIES_PER_ADDON = 4096
  const val MAX_FILE_CHUNK_BYTES = 32 * 1024
  const val MANIFEST_PAGE_SIZE = 32
}
