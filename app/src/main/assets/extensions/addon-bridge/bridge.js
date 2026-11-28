"use strict";

/**
 * Isolated-world relay for the ZIP 0.5.1 addon payload.
 *
 * The content script requests one bounded native chunk at a time through the extension background,
 * validates the complete payload, then exposes read-only getters into the exact game page realm.
 * No page-forgeable window message is accepted and page code cannot mutate the isolated source.
 */
(function installIsolatedAddonBridge() {
  const GAME_ORIGINS = new Set(["https://lasthavens.com", "https://alt.lasthavens.com"]);
  const BRIDGE_KEY = "__LASTHAVEN_ADDON_BRIDGE__";
  const core = globalThis.__LHAddonRuntimeCore;
  const MAX_STARTUP_WAIT_MS = 20000;
  const MAX_RUNTIME_BUNDLE_BYTES = 16 * 1024 * 1024;
  const MAX_RUNTIME_FILE_BYTES = 8 * 1024 * 1024;
  const MAX_RUNTIME_FILES = 1024;
  const MAX_RUNTIME_ADDONS = 128;
  const MAX_RUNTIME_PATH_CHARS = 512;
  const MAX_MANIFEST_PAGE_ITEMS = 32;
  const MAX_FILE_CHUNK_BYTES = 32 * 1024;

  if (!core || typeof core.validatePayload !== "function") {
    return;
  }
  let pageUrl;
  try {
    pageUrl = new URL(location.href);
  } catch (_error) {
    return;
  }
  if (
    !GAME_ORIGINS.has(pageUrl.origin) ||
      pageUrl.username !== "" ||
      pageUrl.password !== "" ||
      pageUrl.port !== "" ||
      (pageUrl.pathname !== "/play" && pageUrl.pathname !== "/play/")
  ) {
    return;
  }

  let currentBundle = null;
  let pageBundleClone = null;
  let ready = false;
  let errorCode = null;
  let currentPlan = null;
  let planError = null;

  function deepFreezePageClone(value, seen) {
    if (value === null || typeof value !== "object") {
      return value;
    }
    const visited = seen || new WeakSet();
    if (visited.has(value)) {
      return value;
    }
    visited.add(value);
    for (const key of Object.keys(value)) {
      deepFreezePageClone(value[key], visited);
    }
    return Object.freeze(value);
  }

  function isSafeFolder(folder) {
    return typeof folder === "string" &&
      /^[A-Za-z0-9_-]{1,128}$/.test(folder) &&
      !folder.toLowerCase().startsWith("blizzard_");
  }

  function isSafeRelativePath(path) {
    if (typeof path !== "string" || path.length === 0 || path.length > MAX_RUNTIME_PATH_CHARS) {
      return false;
    }
    if (path.startsWith("/") || path.includes("\\") || /[\u0000-\u001f\u007f-\u009f:]/.test(path)) {
      return false;
    }
    return path.split("/").every((part) => part.length > 0 && part !== "." && part !== "..");
  }

  function requireResponse(response, expectedType) {
    if (response && response.type === "error" && typeof response.error === "string") {
      throw new Error(response.error);
    }
    if (!response || response.type !== expectedType) {
      throw new Error("invalidResponse");
    }
    return response;
  }

  function decodeBase64Chunk(encoded, expectedLength) {
    if (typeof encoded !== "string" ||
        encoded.length > 4 * Math.ceil(MAX_FILE_CHUNK_BYTES / 3) + 4 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) {
      throw new Error("invalidResponse");
    }
    let binary;
    try {
      binary = atob(encoded);
    } catch (_error) {
      throw new Error("invalidResponse");
    }
    if (binary.length !== expectedLength) {
      throw new Error("invalidResponse");
    }
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  }

  function encodeBase64(bytes) {
    let encoded = "";
    const base64SegmentBytes = 0x3fff;
    for (let offset = 0; offset < bytes.length; offset += base64SegmentBytes) {
      const segment = bytes.subarray(offset, Math.min(offset + base64SegmentBytes, bytes.length));
      let binary = "";
      for (let index = 0; index < segment.length; index += 1) {
        binary += String.fromCharCode(segment[index]);
      }
      encoded += btoa(binary);
    }
    return encoded;
  }

  async function requestNative(type, fields, expectedType) {
    const response = await browser.runtime.sendMessage(Object.assign({}, fields, { type: type }));
    return requireResponse(response, expectedType);
  }

  async function readAddonFile(revision, descriptor) {
    const bytes = new Uint8Array(descriptor.sizeBytes);
    let offset = 0;
    while (offset < descriptor.sizeBytes) {
      const response = await requestNative(
        "getFileChunk",
        {
          revision: revision,
          folder: descriptor.folder,
          path: descriptor.path,
          offset: String(offset)
        },
        "fileChunk"
      );
      if (response.revision !== revision ||
          response.folder !== descriptor.folder ||
          response.path !== descriptor.path ||
          response.offset !== offset ||
          response.totalBytes !== descriptor.sizeBytes) {
        throw new Error("invalidResponse");
      }
      const expectedLength = Math.min(MAX_FILE_CHUNK_BYTES, descriptor.sizeBytes - offset);
      const chunk = decodeBase64Chunk(response.data, expectedLength);
      if (response.done !== (offset + chunk.length === descriptor.sizeBytes)) {
        throw new Error("invalidResponse");
      }
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    return encodeBase64(bytes);
  }

  async function getAddonBundle() {
    const info = await requestNative("getBundleInfo", {}, "bundleInfo");
    if (typeof info.revision !== "string" || !/^[a-f0-9]{64}$/.test(info.revision) ||
        typeof info.sourceRevision !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(info.sourceRevision) ||
        !Array.isArray(info.addons) || info.addons.length > MAX_RUNTIME_ADDONS ||
        !Number.isSafeInteger(info.fileCount) || info.fileCount < 0 || info.fileCount > MAX_RUNTIME_FILES ||
        !Number.isSafeInteger(info.totalBytes) || info.totalBytes < 0 || info.totalBytes > MAX_RUNTIME_BUNDLE_BYTES ||
        !Number.isSafeInteger(info.pageCount) ||
        info.pageCount !== Math.ceil(info.fileCount / MAX_MANIFEST_PAGE_ITEMS) ||
        info.pageSize !== MAX_MANIFEST_PAGE_ITEMS ||
        info.chunkBytes !== MAX_FILE_CHUNK_BYTES) {
      throw new Error("invalidResponse");
    }

    const addonMap = new Map();
    const seenFolders = new Set();
    for (const folder of info.addons) {
      const normalized = typeof folder === "string" ? folder.toLowerCase() : "";
      if (!isSafeFolder(folder) || seenFolders.has(normalized)) {
        throw new Error("invalidResponse");
      }
      seenFolders.add(normalized);
      addonMap.set(folder, { folder: folder, files: Object.create(null) });
    }

    const seenFiles = new Set();
    let listedFiles = 0;
    let listedBytes = 0;
    for (let page = 0; page < info.pageCount; page += 1) {
      const response = await requestNative(
        "getBundleManifestPage",
        { revision: info.revision, page: String(page) },
        "bundleManifestPage"
      );
      if (response.revision !== info.revision ||
          response.page !== page ||
          !Array.isArray(response.files) ||
          response.files.length > MAX_MANIFEST_PAGE_ITEMS) {
        throw new Error("invalidResponse");
      }
      for (const descriptor of response.files) {
        if (!descriptor || !isSafeFolder(descriptor.folder) ||
            !isSafeRelativePath(descriptor.path) ||
            !Number.isSafeInteger(descriptor.sizeBytes) ||
            descriptor.sizeBytes < 0 || descriptor.sizeBytes > MAX_RUNTIME_FILE_BYTES ||
            !addonMap.has(descriptor.folder)) {
          throw new Error("invalidResponse");
        }
        const key = descriptor.folder.toLowerCase() + "/" + descriptor.path.toLowerCase();
        if (seenFiles.has(key)) {
          throw new Error("invalidResponse");
        }
        seenFiles.add(key);
        listedFiles += 1;
        listedBytes += descriptor.sizeBytes;
        if (listedFiles > MAX_RUNTIME_FILES || listedBytes > MAX_RUNTIME_BUNDLE_BYTES) {
          throw new Error("payloadTooLarge");
        }
        const encoded = descriptor.sizeBytes === 0
          ? ""
          : await readAddonFile(info.revision, descriptor);
        addonMap.get(descriptor.folder).files[descriptor.path] = encoded;
      }
    }

    if (listedFiles !== info.fileCount || listedBytes !== info.totalBytes) {
      throw new Error("invalidResponse");
    }
    return {
      revision: info.revision,
      addons: Array.from(addonMap.values())
    };
  }

  const BOOTSTRAP_BLOCKED_REASONS = new Set([
    "missingDependencies",
    "dependencyCycle",
    "payloadTooLarge"
  ]);

  /**
   * Fetches and validates the native bootstrap plan (issue #245): per ordered addon the TOC
   * load-file entry points, plus blocked addons with reasons. Every folder must exist in the
   * validated bundle and every load path must be a safe path present in that addon's files.
   * A failure is recorded as a bounded plan error instead of blocking the bundle getters.
   */
  async function fetchBootstrapPlan() {
    try {
      const plan = await requestNative("getBootstrapPlan", {}, "bootstrapPlan");
      if (
        plan.revision !== currentBundle.revision ||
        typeof plan.sourceRevision !== "string" ||
        plan.sourceRevision.length < 1 ||
        plan.sourceRevision.length > 128 ||
        !Array.isArray(plan.addons) ||
        plan.addons.length > MAX_RUNTIME_ADDONS ||
        !Array.isArray(plan.blocked) ||
        plan.blocked.length > MAX_RUNTIME_ADDONS
      ) {
        throw new Error("invalidResponse");
      }
      const bundleAddons = new Map(currentBundle.addons.map((addon) => [addon.folder, addon]));
      const seenFolders = new Set();
      let totalLoadFiles = 0;
      const addons = [];
      for (const entry of plan.addons) {
        if (!entry || !isSafeFolder(entry.folder) || seenFolders.has(entry.folder.toLowerCase())) {
          throw new Error("invalidResponse");
        }
        seenFolders.add(entry.folder.toLowerCase());
        const bundleAddon = bundleAddons.get(entry.folder);
        if (!bundleAddon || !Array.isArray(entry.loadFiles)) {
          throw new Error("invalidResponse");
        }
        totalLoadFiles += entry.loadFiles.length;
        if (totalLoadFiles > 256) {
          throw new Error("invalidResponse");
        }
        const seenPaths = new Set();
        for (const path of entry.loadFiles) {
          if (
            typeof path !== "string" ||
            !isSafeRelativePath(path) ||
            seenPaths.has(path.toLowerCase()) ||
            !(path in bundleAddon.files)
          ) {
            throw new Error("invalidResponse");
          }
          seenPaths.add(path.toLowerCase());
        }
        addons.push({ folder: entry.folder, loadFiles: entry.loadFiles.slice() });
      }
      const blocked = [];
      for (const entry of plan.blocked) {
        if (
          !entry ||
          !isSafeFolder(entry.folder) ||
          !BOOTSTRAP_BLOCKED_REASONS.has(entry.reason)
        ) {
          throw new Error("invalidResponse");
        }
        blocked.push({ folder: entry.folder, reason: entry.reason });
      }
      currentPlan = core.deepFreeze({
        revision: plan.revision,
        addons: core.deepFreeze(addons),
        blocked: core.deepFreeze(blocked)
      });
    } catch (error) {
      const code = typeof error?.message === "string" ? error.message : "unavailable";
      planError = code.slice(0, 32);
    }
  }

  function publishToPage() {
    try {
      const targetWindow = window;
      const pageGlobal = window.wrappedJSObject || window;
      const pageBridge = cloneInto(
        {
          getBundle: exportFunction(
            () => {
              if (currentBundle === null) {
                return null;
              }
              // One page-realm clone per payload revision (issue #229): the bundle is deep
              // frozen and read-only, so the same clone is reused until the revision changes.
              if (pageBundleClone === null) {
                // cloneInto uses structured cloning, which does not retain property
                // descriptors. Re-freeze the page-realm copy (including nested file maps) so
                // caching cannot turn one caller's mutation into stale data for later requests.
                pageBundleClone = deepFreezePageClone(cloneInto(currentBundle, targetWindow));
              }
              return pageBundleClone;
            },
            targetWindow,
          ),
          getRevision: exportFunction(
            () => (currentBundle === null ? null : currentBundle.revision),
            targetWindow,
          ),
          getBootstrapPlan: exportFunction(
            () =>
              currentPlan === null
                ? planError === null
                  ? null
                  : cloneInto({ error: planError }, targetWindow)
                : cloneInto(currentPlan, targetWindow),
            targetWindow,
          ),
          getState: exportFunction(
            () => cloneInto({ ready: ready, error: errorCode }, targetWindow),
            targetWindow,
          ),
        },
        targetWindow,
        { cloneFunctions: true },
      );
      Object.defineProperty(pageGlobal, BRIDGE_KEY, {
        value: pageBridge,
        configurable: false,
        enumerable: false,
        writable: false
      });
      return true;
    } catch (_error) {
      return false;
    }
  }

  async function fetchBundle() {
    const startedAt = Date.now();
    let attempt = 0;
    while (Date.now() - startedAt <= MAX_STARTUP_WAIT_MS) {
      try {
        const payload = await getAddonBundle();
        if (!core.validatePayload(payload)) {
          throw new Error("invalidPayload");
        }
        currentBundle = core.deepFreeze(payload);
        pageBundleClone = null;
        ready = true;
        return;
      } catch (error) {
        const code = typeof error?.message === "string" ? error.message : "unavailable";
        if (code !== "unavailable" && code !== "native_unavailable" && code !== "native_disconnected") {
          errorCode = code.slice(0, 32);
          ready = true;
          return;
        }
        const delay = Math.min(250 * (attempt + 1), 2000);
        attempt += 1;
        await new Promise((resolve) => setTimeout(resolve, delay));
      }
    }
    errorCode = "unavailable";
    ready = true;
  }

  if (!publishToPage()) {
    return;
  }
  fetchBundle().then(() => {
    if (currentBundle !== null) {
      return fetchBootstrapPlan();
    }
    return undefined;
  });
})();
