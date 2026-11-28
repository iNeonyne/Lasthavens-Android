package com.neonyne.lasthavenlauncher

import java.util.Base64
import java.util.Locale

/**
 * Pure wire contract for the addon-bridge native messages (issue #167).
 *
 * Requests are flat JSON objects containing only bounded scalar strings. Responses may contain
 * bounded addon summaries, manifest pages and base64 file chunks. The Android adapter converts to
 * and from `org.json`; this layer owns the schema and rejects unknown fields/types.
 */
internal object AddonBridgeProtocol {
  const val KEY_TYPE = "type"
  const val KEY_REQUEST_ID = "requestId"
  const val KEY_ERROR = "error"
  const val KEY_REVISION = "revision"
  const val KEY_SOURCE_REVISION = "sourceRevision"
  const val KEY_ADDONS = "addons"
  const val KEY_LOAD_ORDER = "loadOrder"
  const val KEY_BLOCKED = "blocked"
  const val KEY_FOLDER = "folder"
  const val KEY_VERSION = "version"
  const val KEY_ENABLED = "enabled"
  const val KEY_PAGE = "page"
  const val KEY_PAGE_COUNT = "pageCount"
  const val KEY_PAGE_SIZE = "pageSize"
  const val KEY_FILE_COUNT = "fileCount"
  const val KEY_FILES = "files"
  const val KEY_PATH = "path"
  const val KEY_SIZE_BYTES = "sizeBytes"
  const val KEY_TOTAL_BYTES = "totalBytes"
  const val KEY_CHUNK_BYTES = "chunkBytes"
  const val KEY_OFFSET = "offset"
  const val KEY_DATA = "data"
  const val KEY_DONE = "done"
  const val KEY_LOAD_FILES = "loadFiles"
  const val KEY_REASON = "reason"

  const val MAX_REQUEST_ID_CHARS = 64
  const val MAX_MESSAGE_FIELDS = 6
  const val MAX_RESPONSE_ITEMS = 256
  const val MAX_RESPONSE_BYTES = 64 * 1024
  const val MAX_ITEM_CHARS = 128
  const val MAX_ERROR_CODE_CHARS = 32
  const val MAX_MANIFEST_PAGE_ITEMS = AddonRuntimePayloadProvider.MANIFEST_PAGE_SIZE
  const val MAX_MANIFEST_PAGES =
    AddonRuntimePayloadProvider.MAX_RUNTIME_FILES / AddonRuntimePayloadProvider.MANIFEST_PAGE_SIZE
  const val MAX_FILE_CHUNK_BYTES = AddonRuntimePayloadProvider.MAX_FILE_CHUNK_BYTES
  const val MAX_RUNTIME_PATH_CHARS = AddonRuntimePayloadProvider.MAX_RUNTIME_PATH_CHARS
  const val MAX_RUNTIME_FILE_BYTES = AddonRuntimePayloadProvider.MAX_RUNTIME_FILE_BYTES
  const val MAX_RUNTIME_BUNDLE_BYTES = AddonRuntimePayloadProvider.MAX_RUNTIME_BUNDLE_BYTES
  const val MAX_RUNTIME_FILES = AddonRuntimePayloadProvider.MAX_RUNTIME_FILES
  const val MAX_RUNTIME_ADDONS = AddonRuntimePayloadProvider.MAX_RUNTIME_ADDONS

  val MESSAGE_TYPES =
    setOf(
      "ping",
      "listAddons",
      "getLoadOrder",
      "getBootstrapPlan",
      "getBundleInfo",
      "getBundleManifestPage",
      "getFileChunk",
    )

  /** Blocked reasons surfaced with the bootstrap plan; mirrors [AddonBlockedReason]. */
  val BOOTSTRAP_BLOCKED_REASONS = setOf("missingDependencies", "dependencyCycle", "payloadTooLarge")
  val ERROR_CODES =
    setOf(
      "invalidRequest",
      "unsupported",
      "tooManyAddons",
      "unavailable",
      "payloadTooLarge",
      "invalidPath",
      "invalidOffset",
      "staleRevision",
      "notFound",
      "readFailed",
      "timeout",
    )

  private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
  private val ITEM_PATTERN = Regex("[A-Za-z0-9._-]+")
  private val BUNDLE_REVISION_PATTERN = Regex("[a-f0-9]{64}")
  private val DECIMAL_PATTERN = Regex("0|[1-9][0-9]*")
  private val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")

  data class AddonBridgeRequest(
    val type: String,
    val requestId: String,
    val revision: String? = null,
    val page: Int? = null,
    val folder: String? = null,
    val path: String? = null,
    val offset: Long? = null,
  )

  data class AddonSummary(val folder: String, val version: String, val enabled: Boolean)

  data class BundleManifestFile(val folder: String, val path: String, val sizeBytes: Long)

  sealed interface AddonBridgeResponse {
    val requestId: String

    fun toFields(): Map<String, Any?>
  }

  data class AckResponse(override val requestId: String) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(KEY_TYPE to "ack", KEY_REQUEST_ID to requestId)
  }

  data class ErrorResponse(override val requestId: String, val code: String) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(KEY_TYPE to "error", KEY_REQUEST_ID to requestId, KEY_ERROR to code)
  }

  data class AddonListResponse(
    override val requestId: String,
    val revision: String,
    val addons: List<AddonSummary>,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "listAddons",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_ADDONS to
          addons.map {
            mapOf(
              KEY_FOLDER to it.folder,
              KEY_VERSION to it.version,
              KEY_ENABLED to it.enabled,
            )
          },
      )
  }

  data class LoadOrderResponse(
    override val requestId: String,
    val revision: String,
    val loadOrder: List<String>,
    val blocked: List<String>,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "getLoadOrder",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_LOAD_ORDER to loadOrder,
        KEY_BLOCKED to blocked,
      )
  }

  data class BundleInfoResponse(
    override val requestId: String,
    val revision: String,
    val sourceRevision: String,
    val addonFolders: List<String>,
    val fileCount: Int,
    val totalBytes: Long,
    val pageCount: Int,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "bundleInfo",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_SOURCE_REVISION to sourceRevision,
        KEY_ADDONS to addonFolders,
        KEY_FILE_COUNT to fileCount,
        KEY_TOTAL_BYTES to totalBytes,
        KEY_PAGE_COUNT to pageCount,
        KEY_PAGE_SIZE to MAX_MANIFEST_PAGE_ITEMS,
        KEY_CHUNK_BYTES to MAX_FILE_CHUNK_BYTES,
      )
  }

  data class BundleManifestPageResponse(
    override val requestId: String,
    val revision: String,
    val page: Int,
    val files: List<BundleManifestFile>,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "bundleManifestPage",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_PAGE to page,
        KEY_FILES to
          files.map {
            mapOf(
              KEY_FOLDER to it.folder,
              KEY_PATH to it.path,
              KEY_SIZE_BYTES to it.sizeBytes,
            )
          },
      )
  }

  data class BootstrapAddonEntry(val folder: String, val loadFiles: List<String>)

  data class BlockedAddon(val folder: String, val reason: String)

  data class BootstrapPlanResponse(
    override val requestId: String,
    val revision: String,
    val sourceRevision: String,
    val addons: List<BootstrapAddonEntry>,
    val blocked: List<BlockedAddon>,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "bootstrapPlan",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_SOURCE_REVISION to sourceRevision,
        KEY_ADDONS to
          addons.map {
            mapOf(KEY_FOLDER to it.folder, KEY_LOAD_FILES to it.loadFiles)
          },
        KEY_BLOCKED to
          blocked.map {
            mapOf(KEY_FOLDER to it.folder, KEY_REASON to it.reason)
          },
      )
  }

  data class FileChunkResponse(
    override val requestId: String,
    val revision: String,
    val folder: String,
    val path: String,
    val offset: Long,
    val totalBytes: Long,
    val data: String,
    val done: Boolean,
  ) : AddonBridgeResponse {
    override fun toFields(): Map<String, Any?> =
      mapOf(
        KEY_TYPE to "fileChunk",
        KEY_REQUEST_ID to requestId,
        KEY_REVISION to revision,
        KEY_FOLDER to folder,
        KEY_PATH to path,
        KEY_OFFSET to offset,
        KEY_TOTAL_BYTES to totalBytes,
        KEY_DATA to data,
        KEY_DONE to done,
      )
  }

  /** Parses a request using an exact per-type key set; all wire values must already be strings. */
  fun parse(fields: Map<String, Any?>): AddonBridgeRequest? {
    if (fields.size !in 2..MAX_MESSAGE_FIELDS) return null
    val type = fields[KEY_TYPE] as? String ?: return null
    if (type !in MESSAGE_TYPES) return null
    val requestId = fields[KEY_REQUEST_ID] as? String ?: return null
    if (!isRequestIdValid(requestId)) return null

    return when (type) {
      "ping",
      "listAddons",
      "getLoadOrder",
      "getBundleInfo",
      "getBootstrapPlan" -> {
        if (fields.keys != setOf(KEY_TYPE, KEY_REQUEST_ID)) return null
        AddonBridgeRequest(type, requestId)
      }
      "getBundleManifestPage" -> {
        if (fields.keys != setOf(KEY_TYPE, KEY_REQUEST_ID, KEY_REVISION, KEY_PAGE)) return null
        val revision = fields[KEY_REVISION] as? String ?: return null
        if (!isBundleRevisionValid(revision)) return null
        val pageText = fields[KEY_PAGE] as? String ?: return null
        if (!DECIMAL_PATTERN.matches(pageText)) return null
        val page =
          pageText.toIntOrNull()?.takeIf { it in 0 until MAX_MANIFEST_PAGES } ?: return null
        AddonBridgeRequest(type, requestId, revision = revision, page = page)
      }
      "getFileChunk" -> {
        if (
          fields.keys !=
            setOf(KEY_TYPE, KEY_REQUEST_ID, KEY_REVISION, KEY_FOLDER, KEY_PATH, KEY_OFFSET)
        ) {
          return null
        }
        val revision = fields[KEY_REVISION] as? String ?: return null
        if (!isBundleRevisionValid(revision)) return null
        val folder = fields[KEY_FOLDER] as? String ?: return null
        if (!isFolderName(folder)) return null
        val path = fields[KEY_PATH] as? String ?: return null
        if (!isSafeRelativePath(path)) return null
        val offsetText = fields[KEY_OFFSET] as? String ?: return null
        if (!DECIMAL_PATTERN.matches(offsetText)) return null
        val offset =
          offsetText.toLongOrNull()?.takeIf { it in 0 until MAX_RUNTIME_BUNDLE_BYTES }
            ?: return null
        AddonBridgeRequest(
          type,
          requestId,
          revision = revision,
          folder = folder,
          path = path,
          offset = offset,
        )
      }
      else -> null
    }
  }

  fun ack(requestId: String): AckResponse? =
    if (isRequestIdValid(requestId)) AckResponse(requestId) else null

  fun error(requestId: String, code: String): ErrorResponse? {
    if (!isRequestIdValid(requestId)) return null
    if (code.length !in 1..MAX_ERROR_CODE_CHARS || code !in ERROR_CODES) return null
    return ErrorResponse(requestId, code)
  }

  fun addonList(
    requestId: String,
    revision: String,
    addons: List<AddonSummary>,
  ): AddonListResponse? {
    if (!isRequestIdValid(requestId) || !isRevisionValid(revision)) return null
    if (addons.size > MAX_RESPONSE_ITEMS) return null
    if (addons.any { !isFolderName(it.folder) || !isDisplayText(it.version) }) return null
    return AddonListResponse(requestId, revision, addons)
  }

  fun loadOrder(
    requestId: String,
    revision: String,
    loadOrder: List<String>,
    blocked: List<String>,
  ): LoadOrderResponse? {
    if (!isRequestIdValid(requestId) || !isRevisionValid(revision)) return null
    if (loadOrder.size + blocked.size > MAX_RESPONSE_ITEMS) return null
    if ((loadOrder + blocked).any { !isFolderName(it) }) return null
    return LoadOrderResponse(requestId, revision, loadOrder, blocked)
  }

  /**
   * Builds the page bootstrap plan (issue #245): validated TOC load-file entry points per ordered
   * addon plus the blocked addons with reasons. Every list is bounded and path-validated; the total
   * entry count is additionally capped by [MAX_RESPONSE_ITEMS] so the response always fits the
   * bounded response transport.
   */
  fun bootstrapPlan(
    requestId: String,
    revision: String,
    sourceRevision: String,
    addons: List<BootstrapAddonEntry>,
    blocked: List<BlockedAddon>,
  ): BootstrapPlanResponse? {
    if (!isRequestIdValid(requestId)) return null
    if (!BUNDLE_REVISION_PATTERN.matches(revision)) return null
    if (!isRevisionValid(sourceRevision)) return null
    if (addons.size > MAX_RUNTIME_ADDONS || blocked.size > MAX_RUNTIME_ADDONS) return null
    val folders = addons.map { it.folder }
    if (folders.any { !isFolderName(it) }) return null
    if (folders.size != folders.map { it.lowercase(Locale.ROOT) }.toSet().size) return null
    if (addons.sumOf { it.loadFiles.size } > MAX_RESPONSE_ITEMS) return null
    for (entry in addons) {
      if (entry.loadFiles.size != entry.loadFiles.toSet().size) return null
      if (entry.loadFiles.any { !isSafeRelativePath(it) }) return null
    }
    if (blocked.any { !isFolderName(it.folder) || it.reason !in BOOTSTRAP_BLOCKED_REASONS }) {
      return null
    }
    return BootstrapPlanResponse(requestId, revision, sourceRevision, addons, blocked)
  }

  fun bundleInfo(
    requestId: String,
    manifest: AddonRuntimePayloadProvider.Manifest,
  ): BundleInfoResponse? {
    if (
      !isRequestIdValid(requestId) ||
        !isBundleRevisionValid(manifest.revision) ||
        !isRevisionValid(manifest.sourceRevision) ||
        manifest.addonFolders.size > MAX_RUNTIME_ADDONS ||
        manifest.addonFolders.any { !isFolderName(it) } ||
        manifest.files.size > MAX_RUNTIME_FILES ||
        manifest.totalBytes !in 0..MAX_RUNTIME_BUNDLE_BYTES ||
        manifest.pageCount !in 0..MAX_MANIFEST_PAGES
    ) {
      return null
    }
    return BundleInfoResponse(
      requestId,
      manifest.revision,
      manifest.sourceRevision,
      manifest.addonFolders,
      manifest.files.size,
      manifest.totalBytes,
      manifest.pageCount,
    )
  }

  fun manifestPage(
    requestId: String,
    manifest: AddonRuntimePayloadProvider.Manifest,
    page: Int,
    files: List<AddonRuntimePayloadProvider.FileDescriptor>,
  ): BundleManifestPageResponse? {
    if (
      !isRequestIdValid(requestId) ||
        !isBundleRevisionValid(manifest.revision) ||
        page !in 0 until manifest.pageCount ||
        files.size > MAX_MANIFEST_PAGE_ITEMS ||
        files.any {
          !isFolderName(it.folder) ||
            !isSafeRelativePath(it.path) ||
            it.sizeBytes !in 0..MAX_RUNTIME_FILE_BYTES
        }
    ) {
      return null
    }
    return BundleManifestPageResponse(
      requestId,
      manifest.revision,
      page,
      files.map { BundleManifestFile(it.folder, it.path, it.sizeBytes) },
    )
  }

  fun fileChunk(
    requestId: String,
    manifest: AddonRuntimePayloadProvider.Manifest,
    folder: String,
    path: String,
    offset: Long,
    bytes: ByteArray,
  ): FileChunkResponse? {
    val descriptor = manifest.filesByKey["$folder/$path"] ?: return null
    if (
      !isRequestIdValid(requestId) ||
        !isBundleRevisionValid(manifest.revision) ||
        !isFolderName(folder) ||
        !isSafeRelativePath(path) ||
        offset !in 0 until descriptor.sizeBytes ||
        bytes.isEmpty() ||
        bytes.size > MAX_FILE_CHUNK_BYTES ||
        offset + bytes.size > descriptor.sizeBytes
    ) {
      return null
    }
    val done = offset + bytes.size == descriptor.sizeBytes
    val encoded = Base64.getEncoder().encodeToString(bytes)
    if (!BASE64_PATTERN.matches(encoded)) return null
    val expectedBytes = minOf(MAX_FILE_CHUNK_BYTES.toLong(), descriptor.sizeBytes - offset)
    if (bytes.size.toLong() != expectedBytes) return null
    return FileChunkResponse(
      requestId,
      manifest.revision,
      folder,
      path,
      offset,
      descriptor.sizeBytes,
      encoded,
      done,
    )
  }

  fun isResponseWithinByteLimit(encodedByteCount: Int): Boolean =
    encodedByteCount in 0..MAX_RESPONSE_BYTES

  fun isRequestIdValid(requestId: String): Boolean =
    requestId.length in 1..MAX_REQUEST_ID_CHARS && requestId.matches(REQUEST_ID_PATTERN)

  private fun isRevisionValid(revision: String): Boolean =
    revision.length in 1..MAX_ITEM_CHARS && revision.matches(ITEM_PATTERN)

  private fun isBundleRevisionValid(revision: String): Boolean =
    BUNDLE_REVISION_PATTERN.matches(revision)

  private fun isFolderName(value: String): Boolean =
    value.length in 1..MAX_ITEM_CHARS &&
      value.matches(Regex("[A-Za-z0-9_-]+")) &&
      !value.startsWith("blizzard_", ignoreCase = true)

  private fun isSafeRelativePath(value: String): Boolean =
    value.length in 1..MAX_RUNTIME_PATH_CHARS &&
      runCatching { AddonArchive.validateEntryPath(value) }.getOrNull() == value

  private fun isDisplayText(value: String): Boolean =
    value.length <= MAX_ITEM_CHARS && value.none { it.isISOControl() }
}
