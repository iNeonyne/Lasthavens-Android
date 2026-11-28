"use strict";

/**
 * MAIN-world Worker bootstrap overlay for the ZIP 0.5.1 addon runtime (issue #169).
 *
 * Wraps the page Worker constructor so only the exact expected game worker (same-origin https,
 * classic, options.name === "em-pthread", listed in the exact-URL allowlist) is bootstrapped
 * through a Blob shim. The shim waits for the page boot signal, installs a bounded addon XHR
 * forwarder in the worker scope, then importScripts() the original worker URL. Everything else
 * passes through to the native constructor untouched.
 *
 * The overlay also exposes the read-only blocks.idx masking core: a bounded index parser and a
 * guarded WebVfsStoreIo replacement that fills only intersecting read spans in worker memory with
 * 48 ('0') / 35 ('#') without ever writing persistent storage. The masking hook stays disabled
 * until verified index records are supplied; fail closed means "no masking", never "guessed mask".
 *
 * No page-forgeable globals beyond the frozen core export, no storage, no telemetry, no writes.
 */
(function installAddonWorkerOverlay(global) {
  const CORE_KEY = "__LHAddonWorkerOverlayCore";
  const XHR_CORE_KEY = "__LHAddonXhrOverlayCore";
  const BRIDGE_KEY = "__LASTHAVEN_ADDON_BRIDGE__";
  const GAME_HOST = "lasthavens.com";
  const GAME_HOSTS = new Set([GAME_HOST, "alt.lasthavens.com"]);
  const WORKER_NAME = "em-pthread";
  const BOOT_KEY = "__lhBoot";
  const READY_KEY = "__lhReady";
  const REQUEST_SIGNAL = "lhAddonRequest";
  const RESPONSE_SIGNAL = "lhAddonResponse";
  const FAILURE_SIGNAL = "lhWorkerFailed";
  const MASK_SIGNAL = "lhMaskRecords";
  const MASK_DIAGNOSTIC_SIGNAL = "lhMaskDiagnostic";
  const ADDON_PREFIX = "Interface/AddOns/";
  const FOLDER_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;
  const UNSIGNED_PATTERN = /^[0-9]+$/;
  const CONTROL_CHARS_PATTERN = /[\u0000-\u001f\u007f-\u009f]/;
  const MAX_URL_CHARS = 2048;
  const MAX_PATH_CHARS = 512;
  const MAX_QUEUED_MESSAGES = 64;
  const MAX_INTERCEPTED_WORKERS = 8;
  const BOOT_TIMEOUT_MS = 20000;
  const MAX_WORKER_FILE_BYTES = 8 * 1024 * 1024;
  const MAX_MANIFEST_ENTRIES = 8192;
  const MAX_MANIFEST_TEXT_BYTES = 2 * 1024 * 1024;
  const MAX_RUNTIME_BUNDLE_BYTES = 16 * 1024 * 1024;
  const MAX_INFLIGHT_WORKER_REQUESTS = 4;
  const MAX_INDEX_FILE_BYTES = 2 * 1024 * 1024;
  const MAX_INDEX_LINES = 65536;
  const MAX_INDEX_LINE_CHARS = 4096;
  const MAX_INDEX_RECORDS = 32768;
  const MAX_INDEX_FIELD_CHARS = 512;
  const FILL_ZERO = 48;
  const FILL_HASH = 35;
  const WORKER_URL_ALLOWLIST = Object.freeze([]);
  const WORKER_CONTENT_TYPES = {
    xml: "text/xml",
    json: "application/json",
    txt: "text/plain",
    toc: "text/plain",
    lua: "text/plain"
  };

  function contentTypeForWorkerPath(path) {
    const dot = path.lastIndexOf(".");
    if (dot === -1) {
      return "application/octet-stream";
    }
    const extension = path.slice(dot + 1).toLowerCase();
    return WORKER_CONTENT_TYPES[extension] || "application/octet-stream";
  }

  function decodeWorkerPath(rawPath) {
    if (typeof rawPath !== "string" || rawPath.length === 0 || rawPath.length > MAX_PATH_CHARS) {
      return null;
    }
    if (CONTROL_CHARS_PATTERN.test(rawPath) || rawPath.includes("\\") || rawPath.includes(":")) {
      return null;
    }
    if (/%[0-9a-fA-F]{2}/.test(rawPath.replace(/%[0-9a-fA-F]{2}/g, ""))) {
      return null;
    }
    let decoded = rawPath;
    try {
      decoded = decodeURIComponent(rawPath);
    } catch (error) {
      return null;
    }
    if (typeof decoded !== "string" || decoded.length === 0 || decoded.length > MAX_PATH_CHARS) {
      return null;
    }
    if (CONTROL_CHARS_PATTERN.test(decoded) || decoded.includes("\\") || decoded.includes(":")) {
      return null;
    }
    return decoded;
  }

  function isSafeAddonPath(path) {
    if (typeof path !== "string" || !path.startsWith(ADDON_PREFIX)) {
      return false;
    }
    const rest = path.slice(ADDON_PREFIX.length);
    const slash = rest.indexOf("/");
    if (slash === -1) {
      return false;
    }
    const folder = rest.slice(0, slash);
    const filePath = rest.slice(slash + 1);
    if (!FOLDER_PATTERN.test(folder) || folder.toLowerCase().startsWith("blizzard_")) {
      return false;
    }
    if (typeof filePath !== "string" || filePath.length === 0 || filePath.length > MAX_PATH_CHARS) {
      return false;
    }
    if (CONTROL_CHARS_PATTERN.test(filePath) || filePath.includes("\\") || filePath.includes(":")) {
      return false;
    }
    return filePath.split("/").every((part) => part.length > 0 && part !== "." && part !== "..");
  }

  function base64ByteLength(encoded) {
    if (typeof encoded !== "string" || encoded.length % 4 !== 0 ||
        !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded)) {
      return null;
    }
    const padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
    const byteLength = (encoded.length / 4) * 3 - padding;
    return Number.isSafeInteger(byteLength) && byteLength >= 0 &&
      byteLength <= MAX_WORKER_FILE_BYTES ? byteLength : null;
  }

  function buildManifestEntries(payload, manifestText) {
    const reject = (stage) => ({ ok: false, stage: stage, enabledFolders: [], entries: [] });
    if (!payload || !Array.isArray(payload.addons) || payload.addons.length > 128) {
      return reject("payload");
    }
    const manifestLower = typeof manifestText === "string" ? manifestText.toLowerCase() : "";
    const enabledFolders = [];
    const entries = [];
    const seenFolders = new Set();
    const seenPaths = new Set();
    let totalFileBytes = 0;
    let manifestBytes = 0;
    for (let i = 0; i < payload.addons.length; i += 1) {
      const addon = payload.addons[i];
      const folder = addon && addon.folder;
      if (typeof folder !== "string" || !FOLDER_PATTERN.test(folder) ||
          folder.toLowerCase().startsWith("blizzard_") || seenFolders.has(folder.toLowerCase())) {
        return reject("folder");
      }
      seenFolders.add(folder.toLowerCase());
      if (manifestLower.includes((ADDON_PREFIX + folder + "/").toLowerCase())) {
        continue;
      }
      if (!addon.files || typeof addon.files !== "object" || Array.isArray(addon.files)) {
        return reject("files");
      }
      const paths = Object.keys(addon.files);
      if (entries.length + paths.length > MAX_MANIFEST_ENTRIES) {
        return reject("entry-limit");
      }
      const addonEntries = [];
      for (let p = 0; p < paths.length; p += 1) {
        const path = ADDON_PREFIX + folder + "/" + paths[p];
        if (!isSafeAddonPath(path) || seenPaths.has(path.toLowerCase())) {
          return reject("path");
        }
        const sizeBytes = base64ByteLength(addon.files[paths[p]]);
        if (sizeBytes === null) {
          return reject("file-data");
        }
        totalFileBytes += sizeBytes;
        if (!Number.isSafeInteger(totalFileBytes) || totalFileBytes > MAX_RUNTIME_BUNDLE_BYTES) {
          return reject("bundle-size");
        }
        manifestBytes += String(sizeBytes).length + 1 + path.length + 1;
        if (manifestBytes > MAX_MANIFEST_TEXT_BYTES) {
          return reject("manifest-size");
        }
        seenPaths.add(path.toLowerCase());
        addonEntries.push({ path: path, sizeBytes: sizeBytes });
      }
      if (addonEntries.length > 0) {
        enabledFolders.push(folder);
        entries.push(...addonEntries);
      }
    }
    return { ok: true, stage: null, enabledFolders: enabledFolders, entries: entries };
  }

  function validateManifestEntries(entries, enabledFolders) {
    if (!Array.isArray(entries) || entries.length > MAX_MANIFEST_ENTRIES ||
        !Array.isArray(enabledFolders) || enabledFolders.length > 128) {
      return { ok: false, entries: [] };
    }
    const enabled = new Set(enabledFolders.map((folder) => String(folder).toLowerCase()));
    const seen = new Set();
    const validated = [];
    let totalBytes = 0;
    let textBytes = 0;
    for (let i = 0; i < entries.length; i += 1) {
      const entry = entries[i];
      if (!entry || typeof entry !== "object" || !isSafeAddonPath(entry.path) ||
          !Number.isSafeInteger(entry.sizeBytes) || entry.sizeBytes < 0 ||
          entry.sizeBytes > MAX_WORKER_FILE_BYTES) {
        return { ok: false, entries: [] };
      }
      const folder = entry.path.slice(ADDON_PREFIX.length).split("/")[0];
      if (!enabled.has(folder.toLowerCase()) || seen.has(entry.path.toLowerCase())) {
        return { ok: false, entries: [] };
      }
      totalBytes += entry.sizeBytes;
      textBytes += String(entry.sizeBytes).length + 1 + entry.path.length + 1;
      if (totalBytes > MAX_RUNTIME_BUNDLE_BYTES || textBytes > MAX_MANIFEST_TEXT_BYTES) {
        return { ok: false, entries: [] };
      }
      seen.add(entry.path.toLowerCase());
      validated.push(Object.freeze({ path: entry.path, sizeBytes: entry.sizeBytes }));
    }
    return { ok: true, entries: Object.freeze(validated) };
  }

  function appendAddonManifest(text, entries) {
    if (typeof text !== "string" || !Array.isArray(entries) || entries.length === 0) {
      return text;
    }
    const lower = text.toLowerCase();
    const conflictingFolders = new Set();
    for (let i = 0; i < entries.length; i += 1) {
      const folder = entries[i].path.slice(ADDON_PREFIX.length).split("/")[0];
      if (lower.includes((ADDON_PREFIX + folder + "/").toLowerCase())) {
        conflictingFolders.add(folder.toLowerCase());
      }
    }
    const additions = [];
    for (let i = 0; i < entries.length; i += 1) {
      const entry = entries[i];
      const folder = entry.path.slice(ADDON_PREFIX.length).split("/")[0];
      if (!conflictingFolders.has(folder.toLowerCase())) {
        additions.push(String(entry.sizeBytes) + "\t" + entry.path);
      }
    }
    if (additions.length === 0) {
      return text;
    }
    return String(text).replace(/\s*$/, "") + "\n" + additions.join("\n") + "\n";
  }

  function installAddonManifestFetch(entries) {
    const nativeFetch = self.fetch;
    if (typeof nativeFetch !== "function" || typeof self.Response !== "function" ||
        typeof self.Headers !== "function" || typeof self.URL !== "function") {
      return false;
    }
    self.fetch = function (input, init) {
      let url;
      let method = "GET";
      try {
        const isRequest = typeof self.Request === "function" && input instanceof self.Request;
        url = new self.URL(isRequest ? input.url : String(input), originalUrl);
        method = String((init && init.method) || (isRequest && input.method) || "GET").toUpperCase();
      } catch (error) {
        return nativeFetch.call(self, input, init);
      }
      if (method !== "GET" || url.origin !== self.location.origin ||
          url.pathname !== "/game-manifest.txt" || url.search !== "" || url.hash !== "") {
        return nativeFetch.call(self, input, init);
      }
      return nativeFetch.call(self, input, init).then(function (response) {
        if (!response || !response.ok) {
          return response;
        }
        return response.clone().text().then(function (text) {
          const patched = appendAddonManifest(text, entries);
          if (patched === text) {
            return response;
          }
          const headers = new self.Headers(response.headers);
          headers.delete("content-length");
          headers.delete("content-encoding");
          return new self.Response(patched, {
            status: response.status,
            statusText: response.statusText,
            headers: headers
          });
        });
      });
    };
    return true;
  }

  function canonicalUnsigned(text) {
    if (typeof text !== "string" || text.length === 0 || text.length > 15 || !UNSIGNED_PATTERN.test(text)) {
      return null;
    }
    if (text.length > 1 && text.charCodeAt(0) === 48) {
      return null;
    }
    const value = Number(text);
    if (!Number.isSafeInteger(value) || value < 0) {
      return null;
    }
    return value;
  }

  let activeWorkerUrlAllowlist = WORKER_URL_ALLOWLIST;

  function setWorkerUrlAllowlist(entries) {
    if (!Array.isArray(entries) || entries.length > MAX_INTERCEPTED_WORKERS) {
      return false;
    }
    for (const entry of entries) {
      if (typeof entry !== "string" || entry.length === 0 || entry.length > MAX_URL_CHARS) {
        return false;
      }
      let parsed;
      try {
        parsed = new URL(entry);
      } catch (error) {
        return false;
      }
      if (parsed.protocol !== "https:" || !GAME_HOSTS.has(parsed.hostname) ||
          parsed.username !== "" || parsed.password !== "" || parsed.port !== "" ||
          parsed.search !== "" || parsed.hash !== "") {
        return false;
      }
    }
    activeWorkerUrlAllowlist = Object.freeze(entries.slice());
    return true;
  }

  function shouldInterceptWorker(rawUrl, options, config) {
    if (!config || typeof config !== "object" || typeof config.baseHref !== "string") {
      return { ok: false, reason: "config" };
    }
    const useRuntimeAllowlist = config.allowlist === undefined;
    if (useRuntimeAllowlist) {
      config = { baseHref: config.baseHref, allowlist: activeWorkerUrlAllowlist };
    }
    if (options !== undefined && options !== null) {
      if (typeof options !== "object") {
        return { ok: false, reason: "options-type" };
      }
      if (options.type !== undefined && options.type !== "classic") {
        return { ok: false, reason: "worker-type" };
      }
      if (options.name !== WORKER_NAME) {
        return { ok: false, reason: "worker-name" };
      }
    } else {
      return { ok: false, reason: "worker-name" };
    }
    if (typeof rawUrl !== "string" || rawUrl.length === 0 || rawUrl.length > MAX_URL_CHARS) {
      return { ok: false, reason: "url-type" };
    }
    if (CONTROL_CHARS_PATTERN.test(rawUrl)) {
      return { ok: false, reason: "url-chars" };
    }
    let parsed;
    let base;
    try {
      base = new URL(config.baseHref);
      parsed = new URL(rawUrl, base.href);
    } catch (error) {
      return { ok: false, reason: "url-parse" };
    }
    if (parsed.protocol !== "https:") {
      return { ok: false, reason: "url-scheme" };
    }
    if (!GAME_HOSTS.has(parsed.hostname) || parsed.origin !== base.origin ||
        parsed.username !== "" || parsed.password !== "" || parsed.port !== "") {
      return { ok: false, reason: "url-origin" };
    }
    if (parsed.hash !== "" || parsed.search !== "") {
      return { ok: false, reason: "url-query" };
    }
    const pathname = parsed.pathname;
    if (pathname.length === 0 || pathname.length > MAX_PATH_CHARS) {
      return { ok: false, reason: "url-path" };
    }
    if (pathname.includes("..") || pathname.includes("\\") || pathname.includes(":")) {
      return { ok: false, reason: "url-path" };
    }
    const allowlist = Array.isArray(config.allowlist) ? config.allowlist : [];
    const href = parsed.href;
    if (allowlist.length === 0) {
      if (!useRuntimeAllowlist) {
        return { ok: false, reason: "allowlist-empty" };
      }
      // GeckoView knows the game worker URL only when the game constructs its Worker. Pin the
      // first validated, same-origin em-pthread URL for this document; all later candidates must
      // match it exactly. This is narrower than a broad pthread-name interception rule.
      activeWorkerUrlAllowlist = Object.freeze([href]);
      return { ok: true, reason: "learned", href: href, pathname: pathname };
    }
    for (const entry of allowlist) {
      if (typeof entry === "string" && entry === href) {
        return { ok: true, reason: "matched", href: href, pathname: pathname };
      }
    }
    return { ok: false, reason: "allowlist-miss" };
  }

  function parseBlocksIndex(bytes, enabledFolders, limits) {
      function reject(stage) { return { ok: false, stage: stage, records: null }; }
      const resolved = limits && typeof limits === "object" ? limits : {};
      const maxFileBytes = canonicalUnsigned(String(resolved.maxFileBytes || MAX_INDEX_FILE_BYTES));
      const maxLines = canonicalUnsigned(String(resolved.maxLines || MAX_INDEX_LINES));
      const maxLineChars = canonicalUnsigned(String(resolved.maxLineChars || MAX_INDEX_LINE_CHARS));
      const maxRecords = canonicalUnsigned(String(resolved.maxRecords || MAX_INDEX_RECORDS));
      if (maxFileBytes === null || maxLines === null || maxLineChars === null || maxRecords === null) return reject("limits");
      if (!(bytes instanceof Uint8Array)) return reject("input-type");
      if (bytes.length > maxFileBytes) return reject("file-size");
      if (!Array.isArray(enabledFolders) || enabledFolders.length > 128) return reject("folders");
      const enabled = new Set();
      for (let i = 0; i < enabledFolders.length; i += 1) {
        const folder = enabledFolders[i];
        if (typeof folder !== "string" || !FOLDER_PATTERN.test(folder) ||
            folder.toLowerCase().startsWith("blizzard_") || enabled.has(folder.toLowerCase())) return reject("folders");
        enabled.add(folder.toLowerCase());
      }
      if (typeof TextDecoder !== "function" || typeof TextEncoder !== "function") return reject("decoder");
      let text;
      try { text = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
      catch (error) { return reject("utf-8"); }
      const encoder = new TextEncoder();
      const hasTrailingNewline = text.endsWith("\n");
      const lines = text.split("\n");
      if (hasTrailingNewline) lines.pop();
      if (lines.length > maxLines) return reject("line-count");
      const records = [];
      let byteOffset = 0;
      let rowCount = 0;
      for (let i = 0; i < lines.length; i += 1) {
        const rawLine = lines[i];
        const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
        const lineBytes = encoder.encode(rawLine).length;
        if (line.length > maxLineChars) return reject("line-length");
        if (line.length > 0) {
          rowCount += 1;
          if (rowCount > maxRecords) return reject("record-count");
          const fields = line.split("\t");
          if (fields.length !== 5) return reject("record-columns");
          for (let f = 0; f < fields.length; f += 1) {
            if (fields[f].length === 0 || fields[f].length > MAX_INDEX_FIELD_CHARS ||
                CONTROL_CHARS_PATTERN.test(fields[f])) return reject("record-field");
          }
          const digits = fields[1];
          if (!UNSIGNED_PATTERN.test(digits)) return reject("record-numbers");
          const path = fields[4];
          if (path.includes("\\") || path.includes(":") || path.startsWith("/") ||
              path.split("/").some((part) => part === "." || part === ".." || part.length === 0)) return reject("record-path");
          if (path.startsWith(ADDON_PREFIX)) {
            if (!isSafeAddonPath(path)) return reject("record-path");
            const folder = path.slice(ADDON_PREFIX.length).split("/")[0];
            if (enabled.has(folder.toLowerCase())) {
              const column2Start = byteOffset + encoder.encode(fields[0]).length + 1;
              const column2End = column2Start + encoder.encode(digits).length;
              const pathStart = byteOffset + encoder.encode(fields[0]).length + 1 +
                encoder.encode(fields[1]).length + 1 + encoder.encode(fields[2]).length + 1 +
                encoder.encode(fields[3]).length + 1;
              const pathEnd = pathStart + 1;
              if (!Number.isSafeInteger(column2Start) || !Number.isSafeInteger(column2End) ||
                  !Number.isSafeInteger(pathStart) || column2Start < 0 ||
                  column2End <= column2Start || pathEnd > bytes.length) return reject("record-span");
              records.push(Object.freeze({ path, start: column2Start, end: column2End, value: FILL_ZERO }));
              records.push(Object.freeze({ path, start: pathStart, end: pathEnd, value: FILL_HASH }));
            }
          }
        }
        byteOffset += lineBytes + (i < lines.length - 1 || hasTrailingNewline ? 1 : 0);
      }
      if (byteOffset !== bytes.length || records.length > maxRecords * 2) return reject("record-span");
      return { ok: true, stage: null, records: Object.freeze(records), indexSize: bytes.length };
    }
  
    function validateMaskRecords(maskBuffer, indexSize, enabledFolders) {
    const maxMaskBytes = MAX_INDEX_RECORDS * 2 * 16;
    if (!(maskBuffer instanceof ArrayBuffer) || maskBuffer.byteLength > maxMaskBytes ||
        maskBuffer.byteLength % 16 !== 0 || !Number.isSafeInteger(indexSize) ||
        indexSize < 0 || indexSize > MAX_INDEX_FILE_BYTES ||
        !Array.isArray(enabledFolders) || enabledFolders.length > 128) {
      return { ok: false, records: null };
    }
    const enabled = new Set();
    for (let i = 0; i < enabledFolders.length; i += 1) {
      const folder = enabledFolders[i];
      if (typeof folder !== "string" || !FOLDER_PATTERN.test(folder) ||
          folder.toLowerCase().startsWith("blizzard_") || enabled.has(folder.toLowerCase())) {
        return { ok: false, records: null };
      }
      enabled.add(folder.toLowerCase());
    }
    const records = new Uint32Array(maskBuffer);
    let previousEnd = 0;
    for (let i = 0; i < records.length; i += 4) {
      const start = records[i];
      const end = records[i + 1];
      const value = records[i + 2];
      const folderIndex = records[i + 3];
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
          end <= start || end > indexSize ||
          (value !== FILL_ZERO && value !== FILL_HASH) ||
          !Number.isSafeInteger(folderIndex) || folderIndex >= enabledFolders.length ||
          (i > 0 && start < previousEnd)) {
        return { ok: false, records: null };
      }
      const folder = enabledFolders[folderIndex];
      if (typeof folder !== "string" || !FOLDER_PATTERN.test(folder) ||
          folder.toLowerCase().startsWith("blizzard_")) {
        return { ok: false, records: null };
      }
      previousEnd = end;
    }
    return { ok: true, records: records };
  }
  function intersectingSpans(records, readStart, readEnd) {
    if (!(records instanceof Uint32Array) || records.length % 4 !== 0 ||
        !Number.isSafeInteger(readStart) || !Number.isSafeInteger(readEnd)) {
      return [];
    }
    const spans = [];
    for (let i = 0; i < records.length; i += 4) {
      const start = Math.max(records[i], readStart);
      const end = Math.min(records[i + 1], readEnd);
      if (end > start) {
        spans.push(Object.freeze({ start: start, end: end, value: records[i + 2] }));
      }
    }
    return spans;
  }

  function maskReadSpan(heap, offset, count, records) {
    if (!(heap instanceof Uint8Array)) {
      return -1;
    }
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(count) || offset < 0 || count < 0) {
      return -1;
    }
    if (offset + count > heap.length) {
      return -1;
    }
    const readEnd = offset + count;
    const spans = intersectingSpans(records, offset, readEnd);
    let written = 0;
    for (let i = 0; i < spans.length; i += 1) {
      const span = spans[i];
      for (let position = span.start; position < span.end; position += 1) {
        heap[position] = span.value;
        written += 1;
      }
    }
    return written;
  }

  function maskWebVfsStoreIo(wasmImports, records) {
    if (!wasmImports || typeof wasmImports !== "object") {
      return false;
    }
    const original = wasmImports.WebVfsStoreIo;
    if (typeof original !== "function" || original.__lhMasked === true) {
      return false;
    }
    const masked = function (file, write, offset, count) {
      const result = original.apply(this, arguments);
      try {
        if (file === 1 && !write && records instanceof Uint32Array && records.length > 0) {
          const heap = wasmImports.HEAPU8;
          if (heap instanceof Uint8Array) {
            maskReadSpan(heap, offset, count, records);
          }
        }
      } catch (error) {
        /* masking must never break the underlying read */
      }
      return result;
    };
    masked.__lhMasked = true;
    wasmImports.WebVfsStoreIo = masked;
    return true;
  }

  function wrapAssignWasmImports(scope, getRecords) {
    if (!scope || (typeof scope !== "object" && typeof scope !== "function")) {
      return false;
    }
    const original = scope.assignWasmImports;
    if (typeof original !== "function" || original.__lhWrapped === true) {
      return false;
    }
    const wrapped = function () {
      const result = original.apply(this, arguments);
      try {
        const candidates = [result, arguments[0], scope.wasmImports];
        for (let i = 0; i < candidates.length; i += 1) {
          const candidate = candidates[i];
          if (candidate && typeof candidate === "object" && typeof candidate.WebVfsStoreIo === "function") {
            maskWebVfsStoreIo(candidate, typeof getRecords === "function" ? getRecords() : null);
          }
        }
      } catch (error) {
        /* wrapping must never break the original assignment */
      }
      return result;
    };
    wrapped.__lhWrapped = true;
    scope.assignWasmImports = wrapped;
    return true;
  }

  function base64ToBytes(text) {
    if (typeof text !== "string" || text.length === 0 || text.length % 4 !== 0) {
      return null;
    }
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(text)) {
      return null;
    }
    const byteLength = Math.floor(text.length / 4) * 3 - (text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0);
    if (byteLength > MAX_WORKER_FILE_BYTES) {
      return null;
    }
    if (typeof atob !== "function") {
      return null;
    }
    const binary = atob(text);
    if (typeof binary !== "string" || binary.length !== byteLength) {
      return null;
    }
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i) & 0xff;
    }
    return bytes;
  }

  const SHARED_WORKER_FUNCTIONS = [
    canonicalUnsigned,
    isSafeAddonPath,
    parseBlocksIndex,
    validateMaskRecords,
    intersectingSpans,
    maskReadSpan,
    maskWebVfsStoreIo,
    wrapAssignWasmImports,
    base64ToBytes,
    validateManifestEntries,
    appendAddonManifest,
    installAddonManifestFetch
  ];

  function buildBootstrapSource(href) {
    let parsed;
    try {
      parsed = new URL(href);
    } catch (error) {
      throw new Error("lh-worker-bootstrap-url");
    }
    if (parsed.protocol !== "https:" || !GAME_HOSTS.has(parsed.hostname) ||
        parsed.username !== "" || parsed.password !== "" || parsed.port !== "" ||
        parsed.search !== "" || parsed.hash !== "") {
      throw new Error("lh-worker-bootstrap-url");
    }
    const shared = SHARED_WORKER_FUNCTIONS
      .map((fn) => String(fn))
      .join("\n");
    return "(function () {\n" +
      "\"use strict\";\n" +
      "var originalUrl = " + JSON.stringify(href) + ";\n" +
      "var BOOT_KEY = " + JSON.stringify(BOOT_KEY) + ";\n" +
      "var READY_KEY = " + JSON.stringify(READY_KEY) + ";\n" +
      "var REQUEST_SIGNAL = " + JSON.stringify(REQUEST_SIGNAL) + ";\n" +
      "var RESPONSE_SIGNAL = " + JSON.stringify(RESPONSE_SIGNAL) + ";\n" +
      "var FAILURE_SIGNAL = " + JSON.stringify(FAILURE_SIGNAL) + ";\n" +
      "var MASK_SIGNAL = " + JSON.stringify(MASK_SIGNAL) + ";\n" +
      "var MASK_DIAGNOSTIC_SIGNAL = " + JSON.stringify(MASK_DIAGNOSTIC_SIGNAL) + ";\n" +
      "var ADDON_PREFIX = " + JSON.stringify(ADDON_PREFIX) + ";\n" +
      "var GAME_HOSTS = new Set(" + JSON.stringify(Array.from(GAME_HOSTS)) + ");\n" +
      "var FOLDER_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;\n" +
      "var UNSIGNED_PATTERN = /^[0-9]+$/;\n" +
      "var MAX_INDEX_FIELD_CHARS = " + MAX_INDEX_FIELD_CHARS + ";\n" +
      "var CONTROL_CHARS_PATTERN = /[\\u0000-\\u001f\\u007f-\\u009f]/;\n" +
      "var FILL_ZERO = " + FILL_ZERO + ";\n" +
      "var FILL_HASH = " + FILL_HASH + ";\n" +
      "var MAX_QUEUED = " + MAX_QUEUED_MESSAGES + ";\n" +
      "var MAX_INFLIGHT = " + MAX_INFLIGHT_WORKER_REQUESTS + ";\n" +
      "var MAX_FILE_BYTES = " + MAX_WORKER_FILE_BYTES + ";\n" +
      "var MAX_WORKER_FILE_BYTES = " + MAX_WORKER_FILE_BYTES + ";\n" +
      "var MAX_MANIFEST_ENTRIES = " + MAX_MANIFEST_ENTRIES + ";\n" +
      "var MAX_MANIFEST_TEXT_BYTES = " + MAX_MANIFEST_TEXT_BYTES + ";\n" +
      "var MAX_RUNTIME_BUNDLE_BYTES = " + MAX_RUNTIME_BUNDLE_BYTES + ";\n" +
      "var MAX_PATH_CHARS = " + MAX_PATH_CHARS + ";\n" +
      "var MAX_INDEX_FILE_BYTES = " + MAX_INDEX_FILE_BYTES + ";\n" +
      "var MAX_INDEX_LINES = " + MAX_INDEX_LINES + ";\n" +
      "var MAX_INDEX_LINE_CHARS = " + MAX_INDEX_LINE_CHARS + ";\n" +
      "var MAX_INDEX_RECORDS = " + MAX_INDEX_RECORDS + ";\n" +
      "var port = null;\n" +
      "var booted = false;\n" +
      "var failed = false;\n" +
      "var maskRecords = null;\n" +
      "var manifestEntries = [];\n" +
      "var maskDiagnosticReported = false;\n" +
      "function reportMaskDiagnostic(stage) {\n" +
      "  if (maskDiagnosticReported) { return; }\n" +
      "  maskDiagnosticReported = true;\n" +
      "  try { if (port) { port.postMessage({ type: MASK_DIAGNOSTIC_SIGNAL, stage: String(stage).slice(0, 32) }); } } catch (error) {}\n" +
      "}\n" +
      "var xhrResponseHandler = null;\n" +
      "var queue = [];\n" +
      "function reportFailure(stage) {\n" +
      "  if (failed) { return; }\n" +
      "  failed = true;\n" +
      "  try { if (port) { port.postMessage({ type: FAILURE_SIGNAL, stage: String(stage) }); } } catch (error) {}\n" +
      "  try { self.postMessage({ type: FAILURE_SIGNAL, stage: String(stage) }); } catch (error) {}\n" +
      "  try { self.close(); } catch (error) {}\n" +
      "}\n" +
      "function deliver(event) {\n" +
      "  if (typeof self.onmessage === 'function') { self.onmessage(event); return; }\n" +
      "  self.dispatchEvent(event);\n" +
      "}\n" +
      "function installAddonXhrForwarder(workerPort) {\n" +
      "  if (typeof self.XMLHttpRequest !== 'function') { return; }\n" +
      "  var NativeXHR = self.XMLHttpRequest;\n" +
      "  var requestIdCounter = 0;\n" +
      "  var inflightCount = 0;\n" +
      "  var pending = Object.create(null);\n" +
      "  xhrResponseHandler = function (data) {\n" +
      "    var entry = pending[data.requestId];\n" +
      "    if (!entry) { return; }\n" +
      "    delete pending[data.requestId];\n" +
      "    inflightCount -= 1;\n" +
      "    respond(entry, data);\n" +
      "  };\n" +
      "  function baseHref() { return originalUrl; }\n" +
      "  function respond(entry, response) {\n" +
      "    entry.state.status = response.status;\n" +
      "    entry.state.statusText = response.statusText;\n" +
      "    entry.state.responseHeaders = response.headers || '';\n" +
      "    entry.state.readyState = 4;\n" +
      "    var bytes = typeof response.base64 === 'string' ? base64ToBytes(response.base64) : null;\n" +
      "    if (typeof response.base64 === 'string' && bytes === null) {\n" +
      "      entry.state.status = 404;\n" +
      "      entry.state.statusText = 'Not Found';\n" +
      "      bytes = new Uint8Array(0);\n" +
      "    }\n" +
      "    entry.state.bodyBytes = bytes === null ? new Uint8Array(0) : bytes;\n" +
      "    var target = entry.target;\n" +
      "    fire(target, 'readystatechange');\n" +
      "    fire(target, 'load');\n" +
      "    fire(target, 'loadend');\n" +
      "  }\n" +
      "  function fire(target, type) {\n" +
      "    var state = target.__lhState;\n" +
      "    if (typeof state.handlers['on' + type] === 'function') {\n" +
      "      try { state.handlers['on' + type].call(target, { type: type, target: target }); } catch (error) {}\n" +
      "    }\n" +
      "    var listeners = state.listeners[type] || [];\n" +
      "    for (var i = 0; i < listeners.length; i += 1) {\n" +
      "      try { listeners[i].call(target, { type: type, target: target }); } catch (error) {}\n" +
      "    }\n" +
      "  }\n" +
      "  if (typeof workerPort.start === 'function') { workerPort.start(); }\n" +
      "  function OverlayXMLHttpRequest() {\n" +
      "    this.__lhState = {\n" +
      "      method: 'GET',\n" +
      "      url: '',\n" +
      "      intercepted: false,\n" +
      "      manifestRequest: false,\n" +
      "      native: null,\n" +
      "      readyState: 0,\n" +
      "      status: 0,\n" +
      "      statusText: '',\n" +
      "      responseHeaders: '',\n" +
      "      bodyBytes: new Uint8Array(0),\n" +
      "      responseType: '',\n" +
      "      requestHeaders: [],\n" +
      "      listeners: {},\n" +
      "      handlers: {}\n" +
      "    };\n" +
      "  }\n" +
      "  var prototype = OverlayXMLHttpRequest.prototype;\n" +
      "  prototype.open = function (method, url, async) {\n" +
      "    var state = this.__lhState;\n" +
      "    state.method = String(method).toUpperCase();\n" +
      "    state.url = String(url);\n" +
      "    state.async = async !== false;\n" +
      "    state.intercepted = false;\n" +
      "    state.manifestRequest = false;\n" +
      "    var parsed = null;\n" +
      "    try { parsed = new URL(state.url, baseHref()); } catch (error) { parsed = null; }\n" +
      "    var baseOrigin = '';\n" +
      "    try { baseOrigin = new URL(baseHref()).origin; } catch (error) {}\n" +
      "    var sameOrigin = parsed && parsed.protocol === 'https:' && GAME_HOSTS.has(parsed.hostname) && parsed.origin === baseOrigin;\n" +
      "    state.manifestRequest = Boolean(sameOrigin && state.method === 'GET' && parsed.pathname === '/game-manifest.txt' && parsed.search === '' && parsed.hash === '');\n" +
      "    if (sameOrigin && state.method !== 'GET' && state.method !== 'HEAD') {\n" +
      "      var isAddonRoute = false;\n" +
      "      try { isAddonRoute = decodeURIComponent(parsed.pathname).indexOf(ADDON_PREFIX) !== -1; } catch (error) { isAddonRoute = false; }\n" +
      "      if (isAddonRoute) { state.url = ''; state.intercepted = true; state.failClosed = 405; return; }\n" +
      "    }\n" +
      "    if (sameOrigin && parsed.pathname.indexOf(ADDON_PREFIX) !== -1) {\n" +
      "      if (!state.async) { state.failClosed = 404; state.intercepted = true; return; }\n" +
      "      state.intercepted = true;\n" +
      "      return;\n" +
      "    }\n" +
      "    state.native = new NativeXHR();\n" +
      "    state.native.open(method, url, async !== false);\n" +
      "  };\n" +
      "  prototype.send = function (body) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.failClosed) {\n" +
      "      var self2 = this;\n" +
      "      state.status = state.failClosed;\n" +
      "      state.statusText = state.failClosed === 405 ? 'Method Not Allowed' : 'Not Found';\n" +
      "      state.readyState = 4;\n" +
      "      setTimeout(function () { fire(self2, 'readystatechange'); fire(self2, 'load'); fire(self2, 'loadend'); }, 0);\n" +
      "      return;\n" +
      "    }\n" +
      "    if (!state.intercepted) {\n" +
      "      if (state.native) { state.native.send(body); }\n" +
      "      return;\n" +
      "    }\n" +
      "    if (inflightCount >= MAX_INFLIGHT) {\n" +
      "      state.status = 503;\n" +
      "      state.statusText = 'Service Unavailable';\n" +
      "      state.readyState = 4;\n" +
      "      var self3 = this;\n" +
      "      setTimeout(function () { fire(self3, 'readystatechange'); fire(self3, 'load'); fire(self3, 'loadend'); }, 0);\n" +
      "      return;\n" +
      "    }\n" +
      "    var requestId = 'r' + (requestIdCounter += 1);\n" +
      "    inflightCount += 1;\n" +
      "    pending[requestId] = { state: state, target: this };\n" +
      "    var pathOnly = '';\n" +
      "    try { pathOnly = decodeURIComponent(new URL(state.url, baseHref()).pathname.replace(/^\\//, '')); } catch (error) { pathOnly = ''; }\n" +
      "    workerPort.postMessage({\n" +
      "      type: REQUEST_SIGNAL,\n" +
      "      requestId: requestId,\n" +
      "      method: state.method,\n" +
      "      path: pathOnly,\n" +
      "      origin: (function () { try { return new URL(baseHref()).origin; } catch (error) { return ''; } })()\n" +
      "    });\n" +
      "  };\n" +
      "  prototype.abort = function () {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { state.native.abort(); }\n" +
      "  };\n" +
      "  prototype.setRequestHeader = function (name, value) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { state.native.setRequestHeader(name, value); return; }\n" +
      "    state.requestHeaders.push([String(name), String(value)]);\n" +
      "  };\n" +
      "  prototype.getAllResponseHeaders = function () {\n" +
      "    var state = this.__lhState;\n" +
      "    return state.native ? state.native.getAllResponseHeaders() : state.responseHeaders;\n" +
      "  };\n" +
      "  prototype.getResponseHeader = function (name) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { return state.native.getResponseHeader(name); }\n" +
      "    var needle = String(name).toLowerCase() + ':';\n" +
      "    var lines = state.responseHeaders.split('\\r\\n');\n" +
      "    for (var i = 0; i < lines.length; i += 1) {\n" +
      "      if (lines[i].toLowerCase().indexOf(needle) === 0) { return lines[i].slice(needle.length).trim(); }\n" +
      "    }\n" +
      "    return null;\n" +
      "  };\n" +
      "  prototype.overrideMimeType = function (mime) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { state.native.overrideMimeType(mime); }\n" +
      "  };\n" +
      "  prototype.addEventListener = function (type, callback) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { state.native.addEventListener(type, callback); return; }\n" +
      "    var key = String(type);\n" +
      "    if (!state.listeners[key]) { state.listeners[key] = []; }\n" +
      "    state.listeners[key].push(callback);\n" +
      "  };\n" +
      "  prototype.removeEventListener = function (type, callback) {\n" +
      "    var state = this.__lhState;\n" +
      "    if (state.native) { state.native.removeEventListener(type, callback); return; }\n" +
      "    var key = String(type);\n" +
      "    var list = state.listeners[key] || [];\n" +
      "    var index = list.indexOf(callback);\n" +
      "    if (index !== -1) { list.splice(index, 1); }\n" +
      "  };\n" +
      "  Object.defineProperty(prototype, 'readyState', {\n" +
      "    get: function () { return this.__lhState.native ? this.__lhState.native.readyState : this.__lhState.readyState; }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'status', {\n" +
      "    get: function () { return this.__lhState.native ? this.__lhState.native.status : this.__lhState.status; }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'statusText', {\n" +
      "    get: function () { return this.__lhState.native ? this.__lhState.native.statusText : this.__lhState.statusText; }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'responseText', {\n" +
      "    get: function () {\n" +
      "      var state = this.__lhState;\n" +
      "      if (state.native) {\n" +
      "        var nativeText = state.native.responseText;\n" +
      "        if (state.manifestRequest && state.native.readyState === 4 && state.native.status === 200) { return appendAddonManifest(nativeText, manifestEntries); }\n" +
      "        return nativeText;\n" +
      "      }\n" +
      "      var text = '';\n" +
      "      for (var i = 0; i < state.bodyBytes.length; i += 1) { text += String.fromCharCode(state.bodyBytes[i]); }\n" +
      "      return text;\n" +
      "    }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'response', {\n" +
      "    get: function () {\n" +
      "      var state = this.__lhState;\n" +
      "      if (state.native) {\n" +
      "        if (state.manifestRequest && state.native.readyState === 4 && state.native.status === 200) {\n" +
      "          if (state.responseType === '' || state.responseType === 'text') { return appendAddonManifest(state.native.responseText, manifestEntries); }\n" +
      "          if (state.responseType === 'arraybuffer' && state.native.response) {\n" +
      "            var sourceText = new TextDecoder().decode(state.native.response);\n" +
      "            return new TextEncoder().encode(appendAddonManifest(sourceText, manifestEntries)).buffer;\n" +
      "          }\n" +
      "        }\n" +
      "        return state.native.response;\n" +
      "      }\n" +
      "      if (state.responseType === 'arraybuffer') { return state.bodyBytes.buffer; }\n" +
      "      return this.responseText;\n" +
      "    }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'responseType', {\n" +
      "    get: function () { return this.__lhState.responseType; },\n" +
      "    set: function (value) { this.__lhState.responseType = String(value); }\n" +
      "  });\n" +
      "  Object.defineProperty(prototype, 'onreadystatechange', {\n" +
      "    get: function () { return this.__lhState.handlers.onreadystatechange || null; },\n" +
      "    set: function (value) { this.__lhState.handlers.onreadystatechange = typeof value === 'function' ? value : null; }\n" +
      "  });\n" +
      "  ['onload', 'onerror', 'onabort', 'ontimeout', 'onloadstart', 'onprogress', 'onloadend'].forEach(function (key) {\n" +
      "    Object.defineProperty(prototype, key, {\n" +
      "      get: function () { return this.__lhState.handlers[key] || null; },\n" +
      "      set: function (value) { this.__lhState.handlers[key] = typeof value === 'function' ? value : null; }\n" +
      "    });\n" +
      "  });\n" +
      "  self.XMLHttpRequest = OverlayXMLHttpRequest;\n" +
      "}\n" +
      "self.addEventListener('message', function (event) {\n" +
      "  if (booted) { return; }\n" +
      "  var data = event.data;\n" +
      "  if (data && data[BOOT_KEY] === true) {\n" +
      "    var ports = event.ports || [];\n" +
      "    if (ports.length !== 1) { reportFailure('boot-port'); return; }\n" +
      "    port = ports[0];\n" +
      "    booted = true;\n" +
      "    var validatedMasks = validateMaskRecords(data.maskBuffer, data.indexSize, data.enabledFolders);\n" +
      "    if (validatedMasks.ok) { maskRecords = validatedMasks.records; }\n" +
      "    else { reportMaskDiagnostic('mask-records-invalid'); }\n" +
      "    var validatedManifest = validateManifestEntries(data.manifestEntries || [], data.enabledFolders || []);\n" +
      "    if (validatedManifest.ok) { manifestEntries = validatedManifest.entries; }\n" +
      "    else { reportMaskDiagnostic('manifest-entries-invalid'); }\n" +
      "    if (typeof data.maskError === 'string' && data.maskError.length <= 32) { reportMaskDiagnostic(data.maskError); }\n" +
      "    port.addEventListener('message', function (event) {\n" +
      "      var data = event.data;\n" +
      "      if (!data || typeof data !== 'object') { return; }\n" +
      "      if (data.type === MASK_SIGNAL) {\n" +
      "        var update = validateMaskRecords(data.maskBuffer, data.indexSize, data.enabledFolders);\n" +
      "        if (update.ok) { maskRecords = update.records; }\n" +
      "        else { reportMaskDiagnostic('mask-records-invalid'); }\n" +
      "        return;\n" +
      "      }\n" +
      "      if (data.type === RESPONSE_SIGNAL && typeof xhrResponseHandler === 'function') {\n" +
      "        xhrResponseHandler(data);\n" +
      "      }\n" +
      "    });\n" +
      "    try { if (typeof port.start === 'function') { port.start(); } } catch (error) {}\n" +
      "    installAddonXhrForwarder(port);\n" +
      "    try { installAddonManifestFetch(manifestEntries); } catch (error) { reportMaskDiagnostic('manifest-fetch-install'); }\n" +
      "    try { self.importScripts(originalUrl); } catch (error) { reportFailure('import-scripts'); return; }\n" +
      "    try { port.postMessage({ __lhReady: true }); } catch (error) { reportFailure('ready-signal'); return; }\n" +
      "    try { wrapAssignWasmImports(self, function () { return maskRecords; }); } catch (error) {}\n" +
      "    var pendingMessages = queue.splice(0, queue.length);\n" +
      "    for (var i = 0; i < pendingMessages.length; i += 1) { deliver(pendingMessages[i]); }\n" +
      "    return;\n" +
      "  }\n" +
      "  queue.push(event);\n" +
      "  if (queue.length > MAX_QUEUED) { reportFailure('queue-overflow'); }\n" +
      "});\n" +
      shared + "\n" +
      "})();\n";
  }

  function resolveBundleEntry(payload, addonPath) {
    if (!payload || typeof payload !== "object" || !Array.isArray(payload.addons)) {
      return null;
    }
    const rest = addonPath.slice(ADDON_PREFIX.length);
    const slash = rest.indexOf("/");
    if (slash === -1) {
      return null;
    }
    const folder = rest.slice(0, slash);
    const filePath = rest.slice(slash + 1);
    for (let i = 0; i < payload.addons.length; i += 1) {
      const addon = payload.addons[i];
      if (addon && addon.folder === folder && addon.files && typeof addon.files === "object") {
        const value = Object.prototype.hasOwnProperty.call(addon.files, filePath)
          ? addon.files[filePath]
          : null;
        return typeof value === "string" ? { folder: folder, filePath: filePath, base64: value } : null;
      }
    }
    return null;
  }

  function localResolveRoute(method, href) {
    let url;
    try {
      url = new URL(href);
    } catch (error) {
      return { kind: "notFound" };
    }
    if (url.protocol !== "https:" || !GAME_HOSTS.has(url.hostname) || url.search !== "" || url.hash !== "") {
      return { kind: "notFound" };
    }
    let raw = null;
    if (url.pathname.startsWith("/game/") || url.pathname.startsWith("/blocks/")) {
      raw = url.pathname.slice(url.pathname.indexOf("/") + 1 + url.pathname.split("/")[1].length + 1);
    }
    if (raw === null || !raw.startsWith(ADDON_PREFIX)) {
      return { kind: "notFound" };
    }
    const upper = String(method).toUpperCase();
    if (upper !== "GET" && upper !== "HEAD") {
      return { kind: "methodNotAllowed" };
    }
    const decoded = decodeWorkerPath(raw);
    if (decoded === null || !decoded.startsWith(ADDON_PREFIX) || decoded.length > MAX_PATH_CHARS) {
      return { kind: "notFound" };
    }
    return { kind: "file", payloadPath: decoded, block: null, isHead: upper === "HEAD" };
  }

  function bytesToBase64(bytes) {
    let text = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      text += String.fromCharCode.apply(
        null,
        bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)),
      );
    }
    return btoa(text);
  }

  function workerFileResponse(status, statusText, headers, base64) {
    return { status: status, statusText: statusText, headers: headers, base64: base64 };
  }

  function notFoundWorkerResponse() {
    return workerFileResponse(404, "Not Found", "", null);
  }

  function serveWorkerFileRequest(bridgePayload, request, xhrCore) {
    if (!request || typeof request !== "object") {
      return notFoundWorkerResponse();
    }
    const method = typeof request.method === "string" ? request.method.toUpperCase() : "";
    const rawPath = typeof request.path === "string" ? request.path : "";
    if (method !== "GET" && method !== "HEAD") {
      return workerFileResponse(405, "Method Not Allowed", "", null);
    }
    if (rawPath.length === 0 || rawPath.length > MAX_URL_CHARS) {
      return notFoundWorkerResponse();
    }
    let origin;
    try {
      const expectedOrigin = typeof request.origin === "string" ? request.origin : "https://" + GAME_HOST;
      const parsedOrigin = new URL(expectedOrigin);
      if (parsedOrigin.protocol !== "https:" || !GAME_HOSTS.has(parsedOrigin.hostname) ||
          parsedOrigin.port !== "" || parsedOrigin.username !== "" || parsedOrigin.password !== "" ||
          parsedOrigin.origin !== expectedOrigin) {
        return notFoundWorkerResponse();
      }
      origin = parsedOrigin.origin;
    } catch (error) {
      return notFoundWorkerResponse();
    }
    const href = origin + (rawPath.startsWith("/") ? rawPath : "/" + rawPath);
    let route;
    if (xhrCore && typeof xhrCore.resolveRoute === "function") {
      route = xhrCore.resolveRoute(method, href, origin + "/play");
    } else {
      route = localResolveRoute(method, href);
    }
    if (!route || route.kind === "passthrough" || route.kind === "bundleMask") {
      return notFoundWorkerResponse();
    }
    if (route.kind === "methodNotAllowed") {
      return workerFileResponse(405, "Method Not Allowed", "", null);
    }
    if (route.kind !== "file" || typeof route.payloadPath !== "string") {
      return notFoundWorkerResponse();
    }
    const entry = resolveBundleEntry(bridgePayload, route.payloadPath);
    if (entry === null) {
      return notFoundWorkerResponse();
    }
    const base64 = entry.base64;
    if (typeof base64 !== "string" || base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) {
      return notFoundWorkerResponse();
    }
    const byteLength = Math.floor(base64.length / 4) * 3 -
      (base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0);
    if (byteLength > MAX_WORKER_FILE_BYTES) {
      return notFoundWorkerResponse();
    }
    const contentType = xhrCore && typeof xhrCore.contentTypeFor === "function"
      ? xhrCore.contentTypeFor(route.payloadPath, false)
      : contentTypeForWorkerPath(route.payloadPath);
    if (route.block !== null) {
      const blockOffset = route.block.offset;
      const blockLength = route.block.length;
      if (byteLength === 0 || blockOffset >= byteLength) {
        return workerFileResponse(416, "Range Not Satisfiable", "content-range: bytes */" + byteLength, null);
      }
      const end = Math.min(blockOffset + blockLength, byteLength) - 1;
      const decoded = base64ToBytes(base64);
      if (decoded === null) {
        return notFoundWorkerResponse();
      }
      const slice = decoded.subarray(blockOffset, end + 1);
      return workerFileResponse(
        206,
        "Partial Content",
        "content-type: application/octet-stream\r\ncontent-range: bytes " +
          blockOffset + "-" + end + "/" + byteLength,
        bytesToBase64(slice)
      );
    }
    return workerFileResponse(
      200,
      "OK",
      "Content-Type: " + contentType + "\r\nContent-Length: " + byteLength,
      route.isHead ? null : base64
    );
  }

  const core = Object.freeze({
    WORKER_NAME: WORKER_NAME,
    ADDON_PREFIX: ADDON_PREFIX,
    MAX_QUEUED_MESSAGES: MAX_QUEUED_MESSAGES,
    MAX_INTERCEPTED_WORKERS: MAX_INTERCEPTED_WORKERS,
    BOOT_TIMEOUT_MS: BOOT_TIMEOUT_MS,
    MAX_WORKER_FILE_BYTES: MAX_WORKER_FILE_BYTES,
    MAX_INFLIGHT_WORKER_REQUESTS: MAX_INFLIGHT_WORKER_REQUESTS,
    MAX_INDEX_FILE_BYTES: MAX_INDEX_FILE_BYTES,
    MAX_INDEX_LINES: MAX_INDEX_LINES,
    MAX_INDEX_LINE_CHARS: MAX_INDEX_LINE_CHARS,
    MAX_INDEX_RECORDS: MAX_INDEX_RECORDS,
    FILL_ZERO: FILL_ZERO,
    FILL_HASH: FILL_HASH,
    canonicalUnsigned: canonicalUnsigned,
    decodeWorkerPath: decodeWorkerPath,
    isSafeAddonPath: isSafeAddonPath,
    shouldInterceptWorker: shouldInterceptWorker,
    setWorkerUrlAllowlist: setWorkerUrlAllowlist,
    buildBootstrapSource: buildBootstrapSource,
    parseBlocksIndex: parseBlocksIndex,
    intersectingSpans: intersectingSpans,
    maskReadSpan: maskReadSpan,
    maskWebVfsStoreIo: maskWebVfsStoreIo,
    wrapAssignWasmImports: wrapAssignWasmImports,
    validateMaskRecords: validateMaskRecords,
    base64ToBytes: base64ToBytes,
    resolveBundleEntry: resolveBundleEntry,
    serveWorkerFileRequest: serveWorkerFileRequest,
    buildManifestEntries: buildManifestEntries,
    validateManifestEntries: validateManifestEntries,
    appendAddonManifest: appendAddonManifest,
    localResolveRoute: localResolveRoute,
    contentTypeForWorkerPath: contentTypeForWorkerPath
  });
  Object.defineProperty(global, CORE_KEY, {
    value: core,
    configurable: false,
    enumerable: false,
    writable: false
  });

  if (typeof global.Worker !== "function" || typeof global.URL !== "function") {
    return;
  }
  const location = global.location;
  if (!location || typeof location.href !== "string" || !GAME_HOSTS.has(location.hostname)) {
    return;
  }

  const NativeWorker = global.Worker;
  const registry = new Set();

  function teardownEntry(entry, stage) {
    if (entry.tornDown) {
      return;
    }
    entry.tornDown = true;
    if (entry.timer !== null) {
      global.clearTimeout(entry.timer);
      entry.timer = null;
    }
    try {
      if (entry.port) {
        entry.port.close();
      }
    } catch (error) {
      /* port already closed */
    }
    try {
      entry.instance.removeEventListener("message", entry.onMessage);
      entry.instance.removeEventListener("error", entry.onError);
      entry.instance.removeEventListener("messageerror", entry.onError);
    } catch (error) {
      /* listeners already removed */
    }
    try {
      if (entry.blobUrl) {
        global.URL.revokeObjectURL(entry.blobUrl);
      }
    } catch (error) {
      /* blob URL already revoked */
    }
    entry.blobUrl = null;
    entry.port = null;
    registry.delete(entry.instance);
    if (stage === "boot-timeout" || stage === "worker-failed" || stage === "worker-error") {
      try {
        entry.instance.terminate();
      } catch (error) {
        /* worker already terminated */
      }
    }
  }

  let maskBootstrapCache = null;

  function readMaskBootstrapData(global, core) {
    const failed = (stage, enabledFolders, manifestEntries) => ({
      maskBuffer: new ArrayBuffer(0),
      indexSize: 0,
      enabledFolders: enabledFolders || [],
      manifestEntries: manifestEntries || [],
      error: stage
    });
    let payload = null;
    try {
      const bridge = global[BRIDGE_KEY];
      payload = bridge && typeof bridge.getBundle === "function" ? bridge.getBundle() : null;
    } catch (error) {
      return failed("bundle-unavailable");
    }
    if (!payload || !Array.isArray(payload.addons) || payload.addons.length > 128) {
      return failed("bundle-unavailable");
    }
    const manifestUrl = new URL("/game-manifest.txt", global.location.href).href;
    const manifestPromise = typeof global.fetch === "function"
      ? Promise.resolve(global.fetch(manifestUrl))
          .then((response) => response && response.ok && typeof response.text === "function"
            ? response.text()
            : "")
          .catch(() => "")
      : Promise.resolve("");
    return manifestPromise.then((manifestText) => {
      const manifestPlan = core.buildManifestEntries(payload, manifestText);
      if (!manifestPlan.ok) {
        return failed("manifest-" + manifestPlan.stage, [], []);
      }
      const enabledFolders = manifestPlan.enabledFolders;
      const manifestEntries = manifestPlan.entries;
      if (enabledFolders.length === 0) {
        return {
          maskBuffer: new ArrayBuffer(0),
          indexSize: 0,
          enabledFolders: [],
          manifestEntries: manifestEntries,
          error: null
        };
      }
      if (!global.navigator || !global.navigator.storage ||
          typeof global.navigator.storage.getDirectory !== "function") {
        return failed("opfs-unavailable", enabledFolders, manifestEntries);
      }
      return Promise.resolve()
        .then(() => global.navigator.storage.getDirectory())
        .then((root) => root.getFileHandle("blocks.idx"))
        .then((handle) => handle.getFile())
        .then((file) => {
          if (!file || !Number.isSafeInteger(file.size) || file.size < 0 ||
              file.size > core.MAX_INDEX_FILE_BYTES || typeof file.arrayBuffer !== "function") {
            return failed("index-size", enabledFolders, manifestEntries);
          }
          return file.arrayBuffer().then((buffer) => {
            const parsed = core.parseBlocksIndex(new Uint8Array(buffer), enabledFolders);
            if (!parsed.ok) {
              return failed(parsed.stage || "index-parse", enabledFolders, manifestEntries);
            }
            const folderIndexes = new Map();
            for (let i = 0; i < enabledFolders.length; i += 1) {
              folderIndexes.set(enabledFolders[i].toLowerCase(), i);
            }
            const compact = new Uint32Array(parsed.records.length * 4);
            for (let i = 0; i < parsed.records.length; i += 1) {
              const record = parsed.records[i];
              const folder = record.path.slice(ADDON_PREFIX.length).split("/")[0].toLowerCase();
              const folderIndex = folderIndexes.get(folder);
              if (!Number.isSafeInteger(folderIndex)) {
                return failed("folder-index", enabledFolders, manifestEntries);
              }
              compact.set([record.start, record.end, record.value, folderIndex], i * 4);
            }
            return {
              maskBuffer: compact.buffer,
              indexSize: parsed.indexSize,
              enabledFolders,
              manifestEntries,
              error: null
            };
          });
        })
        .catch(() => failed("opfs-read", enabledFolders, manifestEntries));
    });
  }

  function interceptWorker(rawUrl, options, decision) {
    const bootstrapSource = core.buildBootstrapSource(decision.href);
    const blob = new global.Blob([bootstrapSource], { type: "text/javascript" });
    const blobUrl = global.URL.createObjectURL(blob);
    const channel = new global.MessageChannel();
    const entry = {
      instance: null,
      blobUrl: blobUrl,
      port: channel.port1,
      timer: null,
      onMessage: null,
      onError: null,
      tornDown: false,
      bootSent: false
    };
    entry.onMessage = function (event) {
      const data = event.data;
      if (!data || typeof data !== "object") {
        return;
      }
      if (data[READY_KEY] === true) {
        if (entry.timer !== null) {
          global.clearTimeout(entry.timer);
          entry.timer = null;
        }
        try {
          global.URL.revokeObjectURL(entry.blobUrl);
        } catch (error) {
          /* already revoked */
        }
        entry.blobUrl = null;
        return;
      }
      if (data.type === MASK_DIAGNOSTIC_SIGNAL) {
        try { if (global.console && typeof global.console.warn === "function") { global.console.warn("Addon worker masking disabled: " + String(data.stage).slice(0, 32)); } } catch (error) {}
        return;
      }
      if (data.type === FAILURE_SIGNAL) {
        teardownEntry(entry, "worker-failed");
        return;
      }
      if (data.type === REQUEST_SIGNAL) {
        if (entry.tornDown || typeof data.requestId !== "string" || data.requestId.length > 64) {
          return;
        }
        let payload = null;
        try {
          const bridge = global[BRIDGE_KEY];
          payload = bridge && typeof bridge.getBundle === "function" ? bridge.getBundle() : null;
        } catch (error) {
          payload = null;
        }
        const response = core.serveWorkerFileRequest(payload, data, global[XHR_CORE_KEY] || null);
        try {
          entry.port.postMessage({
            type: RESPONSE_SIGNAL,
            requestId: data.requestId,
            status: response.status,
            statusText: response.statusText,
            headers: response.headers,
            base64: response.base64
          });
        } catch (error) {
          teardownEntry(entry, "worker-failed");
        }
      }
    };
    entry.onError = function () {
      teardownEntry(entry, "worker-error");
    };
    entry.port.onmessage = entry.onMessage;
    const instance = new NativeWorker(blobUrl, { name: options.name });
    entry.instance = instance;
    try {
      instance.addEventListener("message", entry.onMessage);
      instance.addEventListener("error", entry.onError);
      instance.addEventListener("messageerror", entry.onError);
    } catch (error) {
      teardownEntry(entry, "worker-error");
      throw error;
    }
    entry.timer = global.setTimeout(function () {
      teardownEntry(entry, "boot-timeout");
    }, BOOT_TIMEOUT_MS);
    const sendBoot = function (maskData) {
      if (entry.tornDown || entry.bootSent) return;
      entry.bootSent = true;
      const bootData = Object.assign({}, {
        maskBuffer: maskData.maskBuffer.slice(0),
        indexSize: maskData.indexSize,
        enabledFolders: maskData.enabledFolders,
        manifestEntries: maskData.manifestEntries || [],
        maskError: maskData.error
      });
      Object.defineProperty(bootData, BOOT_KEY, { value: true, enumerable: true });
      try { instance.postMessage(bootData, [channel.port2, bootData.maskBuffer]); }
      catch (error) { teardownEntry(entry, "worker-error"); }
    };
    try {
      let maskData = maskBootstrapCache;
      if (maskData === null) {
        maskData = readMaskBootstrapData(global, core);
        if (maskData && typeof maskData.then === "function") {
          maskBootstrapCache = maskData.then((result) => {
            if (result.error === "bundle-unavailable") {
              maskBootstrapCache = null;
            }
            return result;
          });
          maskData = maskBootstrapCache;
        } else if (maskData.error !== "bundle-unavailable") {
          maskBootstrapCache = maskData;
        }
      }
      if (maskData && typeof maskData.then === "function") {
        maskData.then(sendBoot).catch(() => sendBoot({ maskBuffer: new ArrayBuffer(0), indexSize: 0, enabledFolders: [], error: "opfs-read" }));
      } else {
        sendBoot(maskData || { maskBuffer: new ArrayBuffer(0), indexSize: 0, enabledFolders: [], error: "mask-unavailable" });
      }
    } catch (error) {
      sendBoot({ maskBuffer: new ArrayBuffer(0), indexSize: 0, enabledFolders: [], error: "mask-unavailable" });
    }
    const nativeTerminate = instance.terminate.bind(instance);
    instance.terminate = function () {
      teardownEntry(entry, "terminated");
      nativeTerminate();
    };
    return instance;
  }

  function PatchedWorker(rawUrl, options) {
    const decision = core.shouldInterceptWorker(rawUrl, options, {
      baseHref: location.href
    });
    if (!decision.ok) {
      return new NativeWorker(rawUrl, options);
    }
    if (registry.size >= MAX_INTERCEPTED_WORKERS) {
      throw new Error("lh-worker-cap-reached");
    }
    const instance = interceptWorker(rawUrl, options, decision);
    registry.add(instance);
    return instance;
  }
  PatchedWorker.prototype = NativeWorker.prototype;
  Object.defineProperty(global, "Worker", {
    value: PatchedWorker,
    writable: true,
    configurable: true
  });
})(typeof window !== "undefined" ? window : globalThis);
