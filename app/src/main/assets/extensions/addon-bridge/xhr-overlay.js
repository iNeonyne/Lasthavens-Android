"use strict";

/**
 * MAIN-world XHR overlay for the ZIP 0.5.1 addon payload.
 *
 * Wraps the page XMLHttpRequest so explicitly supported game resource routes are served from the
 * already-validated, read-only addon payload published by the isolated bridge through
 * window.__LASTHAVEN_ADDON_BRIDGE__. Everything that does not exactly match a supported addon
 * route keeps the native network path. Addon-route candidates that decode unsafely fail closed
 * with 404 and never reach the network. No page-forgeable message channel, no storage, no
 * persistent profile writes.
 */
(function installAddonXhrOverlay(global) {
  const CORE_KEY = "__LHAddonXhrOverlayCore";
  const BRIDGE_KEY = "__LASTHAVEN_ADDON_BRIDGE__";
  const GAME_HOST = "lasthavens.com";
  const GAME_HOSTS = new Set([GAME_HOST, "alt.lasthavens.com"]);
  const ADDON_PREFIX = "Interface/AddOns/";
  const BUNDLE_SEGMENT_PREFIX = "bundle-";
  const BLOCK_SEGMENT_PATTERN = /^(.+)\/(\d+)-(\d+)-(\d+)k\.bin$/;
  const BLOCK_APPEND_PATTERN = /^(.+)-(\d+)-(\d+)-(\d+)k\.bin$/;
  const TOKEN_PATTERN = /^[!#$%&'*+\-.^`|~0-9A-Za-z]+$/;
  const DOUBLE_ESCAPE_PATTERN = /%[0-9a-fA-F]{2}/;
  const CONTROL_CHARS_PATTERN = /[\u0000-\u001f\u007f-\u009f]/;
  const MAX_PATH_CHARS = 512;
  const MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
  const MAX_FILE_BYTES = 8 * 1024 * 1024;
  const MAX_RANGES = 16;
  const BLOCK_UNIT_BYTES = 1024;
  const MULTIPART_BOUNDARY = "LastHavenAddonRangeBoundary";
  const READY_WAIT_MS = 20000;
  const READY_POLL_MS = 50;
  const MAX_CONCURRENT_REQUESTS = 8;
  const STATUS_TEXTS = {
    200: "OK",
    206: "Partial Content",
    404: "Not Found",
    405: "Method Not Allowed",
    416: "Range Not Satisfiable"
  };
  const CONTENT_TYPES = {
    xml: "text/xml",
    json: "application/json",
    txt: "text/plain",
    toc: "text/plain",
    lua: "text/plain"
  };
  const HANDLER_TYPES = [
    "readystatechange",
    "loadstart",
    "progress",
    "abort",
    "error",
    "load",
    "timeout",
    "loadend"
  ];

  function contentTypeFor(payloadPath, isBlock) {
    if (isBlock) {
      return "application/octet-stream";
    }
    const dot = payloadPath.lastIndexOf(".");
    if (dot === -1) {
      return "application/octet-stream";
    }
    const extension = payloadPath.slice(dot + 1).toLowerCase();
    return CONTENT_TYPES[extension] || "application/octet-stream";
  }

  function isUnsafeSegment(segment) {
    return segment.length === 0 ||
      segment === "." ||
      segment === ".." ||
      segment.length > MAX_PATH_CHARS ||
      CONTROL_CHARS_PATTERN.test(segment) ||
      segment.includes("\\") ||
      segment.includes(":") ||
      segment.includes("/") ||
      DOUBLE_ESCAPE_PATTERN.test(segment);
  }

  function decodePath(rawPath) {
    const rawSegments = rawPath.split("/");
    const decoded = [];
    for (const raw of rawSegments) {
      let segment;
      try {
        segment = decodeURIComponent(raw);
      } catch (_error) {
        return null;
      }
      if (isUnsafeSegment(segment)) {
        return null;
      }
      decoded.push(segment);
    }
    return decoded.join("/");
  }

  function parseBlock(a, b, c) {
    const startBlock = Number(a);
    const startBlockIndex = Number(b);
    const blockCount = Number(c);
    if (blockCount < 1) {
      return null;
    }
    const offset = startBlockIndex * blockCount * BLOCK_UNIT_BYTES;
    const length = blockCount * BLOCK_UNIT_BYTES;
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) ||
        offset < 0 || offset + length > Number.MAX_SAFE_INTEGER) {
      return null;
    }
    return { offset: offset, length: length };
  }

  function resolveRoute(method, href, baseHref) {
    let url;
    let base;
    try {
      base = new URL(baseHref);
      url = new URL(href, base.href);
    } catch (_error) {
      return { kind: "passthrough" };
    }
    if (url.protocol !== "https:" ||
        !GAME_HOSTS.has(url.hostname) ||
        url.origin !== base.origin ||
        url.username !== "" ||
        url.password !== "" ||
        url.port !== "" ||
        url.search !== "" ||
        url.hash !== "") {
      return { kind: "passthrough" };
    }
    const upper = String(method).toUpperCase();
    let raw;
    let isBlocks = false;
    if (url.pathname.startsWith("/game/")) {
      raw = url.pathname.slice("/game/".length);
    } else if (url.pathname.startsWith("/blocks/")) {
      raw = url.pathname.slice("/blocks/".length);
      isBlocks = true;
    } else {
      return { kind: "passthrough" };
    }
    let addonCandidate = raw.startsWith(ADDON_PREFIX);
    if (!addonCandidate) {
      // Decode only the prefix segments first so unsafe suffixes cannot hide an encoded
      // addon route and fall through to the network.
      let decodedPrefix = "";
      for (const segment of raw.split("/").slice(0, 3)) {
        try {
          decodedPrefix += decodeURIComponent(segment) + "/";
        } catch (_error) {
          break;
        }
        if (decodedPrefix.startsWith(ADDON_PREFIX)) {
          addonCandidate = true;
          break;
        }
      }
    }
    if (!addonCandidate) {
      return { kind: "passthrough" };
    }
    if (!raw.startsWith(ADDON_PREFIX)) {
      return { kind: "notFound" };
    }
    if (upper !== "GET" && upper !== "HEAD") {
      return { kind: "methodNotAllowed", isHead: false };
    }
    const decoded = decodePath(raw);
    if (decoded === null || !decoded.startsWith(ADDON_PREFIX)) {
      return { kind: "notFound" };
    }
    if (isBlocks && decoded.startsWith(ADDON_PREFIX)) {
      const rest = decoded.slice(ADDON_PREFIX.length);
      const separator = rest.indexOf("/");
      const folder = separator === -1 ? rest : rest.slice(0, separator);
      const tail = separator === -1 ? "" : rest.slice(separator + 1);
      if (folder.length > 0 && tail.startsWith(BUNDLE_SEGMENT_PREFIX)) {
        return { kind: "bundleMask", folder: folder, isHead: upper === "HEAD" };
      }
    }
    let payloadPath = decoded;
    let block = null;
    if (isBlocks) {
      const match = BLOCK_SEGMENT_PATTERN.exec(decoded) ||
        BLOCK_APPEND_PATTERN.exec(decoded);
      if (match) {
        payloadPath = match[1];
        block = parseBlock(match[2], match[3], match[4]);
        if (block === null) {
          return { kind: "notFound" };
        }
      }
    }
    if (payloadPath.length > MAX_PATH_CHARS) {
      return { kind: "notFound" };
    }
    return {
      kind: "file",
      payloadPath: payloadPath,
      block: block,
      isHead: upper === "HEAD"
    };
  }

  function parseRange(header, size) {
    if (typeof header !== "string" || !header.startsWith("bytes=")) {
      return { kind: "ignore" };
    }
    const specs = header.slice("bytes=".length).split(",");
    if (specs.length > MAX_RANGES) {
      return { kind: "ignore" };
    }
    const ranges = [];
    let sawValid = false;
    let sawSatisfiable = false;
    for (const raw of specs) {
      const spec = raw.trim();
      let match = /^(\d+)-(\d+)$/.exec(spec);
      if (match) {
        sawValid = true;
        const start = Number(match[1]);
        const endRequested = Number(match[2]);
        if (start > endRequested) {
          return { kind: "ignore" };
        }
        if (start >= size) {
          continue;
        }
        sawSatisfiable = true;
        ranges.push({ start: start, end: Math.min(endRequested, size - 1) });
        continue;
      }
      match = /^(\d+)-$/.exec(spec);
      if (match) {
        sawValid = true;
        const start = Number(match[1]);
        if (start >= size) {
          continue;
        }
        sawSatisfiable = true;
        ranges.push({ start: start, end: size - 1 });
        continue;
      }
      match = /^-(\d+)$/.exec(spec);
      if (match) {
        sawValid = true;
        const suffix = Number(match[1]);
        if (suffix === 0 || size === 0) {
          continue;
        }
        sawSatisfiable = true;
        ranges.push({ start: Math.max(0, size - suffix), end: size - 1 });
        continue;
      }
      return { kind: "ignore" };
    }
    if (!sawValid) {
      return { kind: "ignore" };
    }
    if (!sawSatisfiable) {
      return { kind: "unsatisfiable" };
    }
    return { kind: "ranges", ranges: ranges };
  }

  function utf8Decode(bytes) {
    if (typeof TextDecoder === "function") {
      return new TextDecoder("utf-8").decode(bytes);
    }
    let text = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      text += String.fromCharCode.apply(
        null,
        bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)),
      );
    }
    return decodeURIComponent(escape(text));
  }

  function byteString(bytes) {
    let text = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      text += String.fromCharCode.apply(
        null,
        bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length)),
      );
    }
    return text;
  }

  const core = Object.freeze({
    GAME_HOST: GAME_HOST,
    GAME_HOSTS: Object.freeze(Array.from(GAME_HOSTS)),
    ADDON_PREFIX: ADDON_PREFIX,
    MAX_PAYLOAD_BYTES: MAX_PAYLOAD_BYTES,
    MAX_FILE_BYTES: MAX_FILE_BYTES,
    MAX_RANGES: MAX_RANGES,
    MULTIPART_BOUNDARY: MULTIPART_BOUNDARY,
    READY_WAIT_MS: READY_WAIT_MS,
    READY_POLL_MS: READY_POLL_MS,
    MAX_CONCURRENT_REQUESTS: MAX_CONCURRENT_REQUESTS,
    contentTypeFor: contentTypeFor,
    decodePath: decodePath,
    parseBlock: parseBlock,
    resolveRoute: resolveRoute,
    parseRange: parseRange,
    utf8Decode: utf8Decode
  });
  Object.defineProperty(global, CORE_KEY, {
    value: core,
    configurable: false,
    enumerable: false,
    writable: false
  });

  if (typeof global.XMLHttpRequest !== "function") {
    return;
  }

  const NativeXMLHttpRequest = global.XMLHttpRequest;
  const UNSENT = 0;
  const OPENED = 1;
  const HEADERS_RECEIVED = 2;
  const LOADING = 3;
  const DONE = 4;
  const states = new WeakMap();
  const bundleCache = { revision: null, index: null };
  let outstandingRequests = 0;

  function releaseOutstanding(state) {
    if (state.counted) {
      state.counted = false;
      outstandingRequests -= 1;
    }
  }

  function getIndexForPayload(payload) {
    if (!payload || typeof payload !== "object" || typeof payload.revision !== "string") {
      return null;
    }
    if (bundleCache.index !== null && payload.revision === bundleCache.revision) {
      return bundleCache.index;
    }
    const index = buildIndex(payload);
    if (index !== null) {
      bundleCache.revision = payload.revision;
      bundleCache.index = index;
    }
    return index;
  }

  function baseHref() {
    return global.location && typeof global.location.href === "string"
      ? global.location.href
      : "https://" + GAME_HOST + "/play";
  }

  function invalidStateError(message) {
    const error = new Error(message);
    error.name = "InvalidStateError";
    return error;
  }

  function buildIndex(payload) {
    if (!payload ||
        typeof payload !== "object" ||
        typeof payload.revision !== "string" ||
        !Array.isArray(payload.addons)) {
      return null;
    }
    const files = new Map();
    const lowerSet = new Set();
    const folders = new Set();
    for (const addon of payload.addons) {
      if (!addon ||
          typeof addon !== "object" ||
          typeof addon.folder !== "string" ||
          !addon.files ||
          typeof addon.files !== "object") {
        return null;
      }
      folders.add(addon.folder);
      for (const relativePath of Object.keys(addon.files)) {
        const encoded = addon.files[relativePath];
        if (typeof encoded !== "string" ||
            encoded.length > Math.ceil((MAX_FILE_BYTES * 4) / 3) + 4) {
          return null;
        }
        const payloadPath = ADDON_PREFIX + addon.folder + "/" + relativePath;
        const key = payloadPath.toLowerCase();
        if (lowerSet.has(key)) {
          return null;
        }
        lowerSet.add(key);
        files.set(payloadPath, {
          folder: addon.folder,
          relativePath: relativePath,
          encoded: encoded
        });
      }
    }
    return {
      revision: payload.revision,
      files: files,
      lowerSet: lowerSet,
      folders: folders,
      decoded: new Map(),
      decodedTotal: 0
    };
  }

  function decodeEntry(index, payloadPath, entry) {
    const cached = index.decoded.get(payloadPath);
    if (cached !== undefined) {
      return cached;
    }
    const encoded = entry.encoded;
    if (encoded.length % 4 !== 0) {
      return null;
    }
    const padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
    const contentLength = encoded.length - padding;
    for (let offset = 0; offset < contentLength; offset += 1) {
      const code = encoded.charCodeAt(offset);
      const valid =
        (code >= 65 && code <= 90) ||
        (code >= 97 && code <= 122) ||
        (code >= 48 && code <= 57) ||
        code === 43 ||
        code === 47;
      if (!valid) {
        return null;
      }
    }
    const expectedBytes = (encoded.length / 4) * 3 - padding;
    if (
      expectedBytes < 0 ||
      expectedBytes > MAX_FILE_BYTES ||
      index.decodedTotal + expectedBytes > MAX_PAYLOAD_BYTES
    ) {
      return null;
    }
    let binary;
    try {
      binary = atob(encoded);
    } catch (_error) {
      return null;
    }
    if (binary.length !== expectedBytes) {
      return null;
    }
    const bytes = new Uint8Array(binary.length);
    for (let offset = 0; offset < binary.length; offset += 1) {
      bytes[offset] = binary.charCodeAt(offset);
    }
    index.decoded.set(payloadPath, bytes);
    index.decodedTotal += bytes.length;
    return bytes;
  }

  function findHeader(requestHeaders, name) {
    for (const entry of requestHeaders) {
      if (entry[0] === name) {
        return entry[1];
      }
    }
    return null;
  }

  function createResponse(status, headers, body, contentType) {
    return {
      status: status,
      statusText: STATUS_TEXTS[status],
      headers: headers,
      body: body,
      contentType: contentType
    };
  }

  function notFoundResponse(route) {
    return createResponse(404, [], new Uint8Array(0), "application/octet-stream");
  }

  function withHeadHandling(route, response) {
    if (!route || !route.isHead) {
      return response;
    }
    return {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers,
      body: new Uint8Array(0),
      contentType: response.contentType,
      headLength: response.body.length
    };
  }

  function serveRoute(index, route, requestHeaders, overrideMimeType) {
    if (route.kind === "methodNotAllowed") {
      return {
        kind: "serve",
        response: createResponse(405, [["allow", "GET, HEAD"]], new Uint8Array(0), "text/plain")
      };
    }
    if (route.kind === "bundleMask") {
      if (index !== null && index.folders.has(route.folder)) {
        return { kind: "serve", response: notFoundResponse(route) };
      }
      return { kind: "passthrough" };
    }
    if (route.kind !== "file" || index === null) {
      return { kind: "notFound" };
    }
    const entry = index.files.get(route.payloadPath);
    if (entry === undefined) {
      return { kind: "notFound" };
    }
    const bytes = decodeEntry(index, route.payloadPath, entry);
    if (bytes === null) {
      return { kind: "notFound" };
    }
    const rangeHeader = findHeader(requestHeaders, "range");
    const override = overrideMimeType || null;
    if (route.block !== null && (rangeHeader === null || rangeHeader === undefined)) {
      const size = bytes.length;
      const blockOffset = route.block.offset;
      const blockLength = route.block.length;
      if (size === 0 || blockOffset >= size) {
        return {
          kind: "serve",
          response: createResponse(
            416,
            [["content-range", "bytes */" + size]],
            new Uint8Array(0),
            "application/octet-stream"
          )
        };
      }
      const end = Math.min(blockOffset + blockLength, size) - 1;
      return {
        kind: "serve",
        response: withHeadHandling(route, createResponse(
          206,
          [["content-range", "bytes " + blockOffset + "-" + end + "/" + size]],
          bytes.slice(blockOffset, end + 1),
          override || "application/octet-stream"
        ))
      };
    }
    const parsed = rangeHeader === null || rangeHeader === undefined
      ? { kind: "ignore" }
      : parseRange(rangeHeader, bytes.length);
    if (parsed.kind === "unsatisfiable") {
      return {
        kind: "serve",
        response: createResponse(
          416,
          [["content-range", "bytes */" + bytes.length]],
          new Uint8Array(0),
          override || contentTypeFor(route.payloadPath, route.block !== null)
        )
      };
    }
    if (parsed.kind === "ranges" && parsed.ranges.length === 1) {
      const range = parsed.ranges[0];
      return {
        kind: "serve",
        response: withHeadHandling(route, createResponse(
          206,
          [["content-range", "bytes " + range.start + "-" + range.end + "/" + bytes.length]],
          bytes.slice(range.start, range.end + 1),
          override || contentTypeFor(route.payloadPath, route.block !== null)
        ))
      };
    }
    if (parsed.kind === "ranges" && parsed.ranges.length > 1) {
      const contentType = override || contentTypeFor(route.payloadPath, route.block !== null);
      const parts = [];
      let bodyLength = 0;
      for (const range of parsed.ranges) {
        const head =
          "--" + MULTIPART_BOUNDARY + "\r\n" +
          "Content-Type: " + contentType + "\r\n" +
          "Content-Range: bytes " + range.start + "-" + range.end + "/" + bytes.length +
          "\r\n\r\n";
        const slice = bytes.slice(range.start, range.end + 1);
        parts.push({ head: head, body: slice });
        bodyLength += head.length + slice.length + 2;
      }
      const closing = "--" + MULTIPART_BOUNDARY + "--\r\n";
      bodyLength += closing.length;
      const body = new Uint8Array(bodyLength);
      let cursor = 0;
      for (const part of parts) {
        for (let offset = 0; offset < part.head.length; offset += 1) {
          body[cursor] = part.head.charCodeAt(offset);
          cursor += 1;
        }
        body.set(part.body, cursor);
        cursor += part.body.length;
        body[cursor] = 13;
        body[cursor + 1] = 10;
        cursor += 2;
      }
      for (let offset = 0; offset < closing.length; offset += 1) {
        body[cursor] = closing.charCodeAt(offset);
        cursor += 1;
      }
      return {
        kind: "serve",
        response: withHeadHandling(route, createResponse(
          206,
          [],
          body,
          "multipart/byteranges; boundary=" + MULTIPART_BOUNDARY
        ))
      };
    }
    return {
      kind: "serve",
      response: withHeadHandling(route, createResponse(
        200,
        [],
        bytes.slice(0),
        override || contentTypeFor(route.payloadPath, route.block !== null)
      ))
    };
  }

  function createEvent(target, type, sourceEvent) {
    return {
      type: type,
      target: target,
      currentTarget: target,
      eventPhase: 2,
      bubbles: false,
      cancelable: false,
      defaultPrevented: false,
      isTrusted: false,
      timeStamp: Date.now(),
      lengthComputable: Boolean(sourceEvent && sourceEvent.lengthComputable),
      loaded: sourceEvent && Number.isFinite(sourceEvent.loaded) ? sourceEvent.loaded : 0,
      total: sourceEvent && Number.isFinite(sourceEvent.total) ? sourceEvent.total : 0,
      preventDefault: function () {},
      stopPropagation: function () {},
      stopImmediatePropagation: function () {
        this._stopped = true;
      }
    };
  }

  function dispatchEventOn(target, listeners, handlers, type, sourceEvent) {
    const event = createEvent(target, type, sourceEvent);
    const entries = [];
    for (const entry of listeners) {
      if (entry.type === type) {
        entries.push(entry);
      }
    }
    const handler = handlers["on" + type];
    if (typeof handler === "function" && !entries.some((entry) => entry.isHandler)) {
      entries.push({ type: type, callback: handler, isHandler: true });
    }
    for (const entry of entries) {
      if (event._stopped) {
        return;
      }
      if (entry.options && entry.options.once) {
        const position = listeners.indexOf(entry);
        if (position !== -1) {
          listeners.splice(position, 1);
        }
      }
      try {
        entry.callback.call(target, event);
      } catch (_error) {
        // listener errors must not break response delivery
      }
    }
  }

  function registerHandler(listeners, type, callback) {
    const existing = listeners.find((entry) => entry.type === type && entry.isHandler);
    if (existing) {
      if (callback === null) {
        listeners.splice(listeners.indexOf(existing), 1);
      } else {
        existing.callback = callback;
      }
    } else if (callback !== null) {
      listeners.push({ type: type, callback: callback, isHandler: true });
    }
  }

  function OverlayXMLHttpRequestConstructor() {
    if (!(this instanceof OverlayXMLHttpRequestConstructor)) {
      throw new TypeError("XMLHttpRequest must be constructed with 'new'");
    }
    const native = new NativeXMLHttpRequest();
    const state = {
      method: "",
      url: "",
      async: true,
      user: null,
      password: null,
      opened: false,
      sent: false,
      decided: false,
      intercepted: false,
      nativeActive: false,
      readyState: UNSENT,
      status: 0,
      statusText: "",
      responseHeaders: [],
      responseBytes: null,
      responseURL: "",
      responseType: "",
      timeout: 0,
      withCredentials: false,
      requestHeaders: [],
      mimeType: null,
      listeners: [],
      uploadListeners: [],
      uploadFlag: false,
      handlers: {},
      uploadHandlers: {},
      aborted: false,
      done: false,
      waitTimer: null,
      timeoutTimer: null,
      native: native,
      requestId: 0,
      counted: false,
      pendingBody: null,
      suppressNativeOpenEvent: false
    };
    states.set(this, state);
    for (const type of HANDLER_TYPES) {
      native.addEventListener(type, (event) => {
        if (!state.nativeActive ||
            (type === "readystatechange" && state.suppressNativeOpenEvent)) {
          return;
        }
        dispatchEventOn(this, state.listeners, state.handlers, type, event);
      });
    }
    for (const type of ["loadstart", "progress", "abort", "error", "load", "timeout", "loadend"]) {
      native.upload.addEventListener(type, (event) => {
        if (!state.nativeActive || !state.uploadFlag) {
          return;
        }
        dispatchEventOn(this.upload, state.uploadListeners, state.uploadHandlers, type, event);
      });
    }
  }

  const prototype = OverlayXMLHttpRequestConstructor.prototype;
  prototype.UNSENT = UNSENT;
  prototype.OPENED = OPENED;
  prototype.HEADERS_RECEIVED = HEADERS_RECEIVED;
  prototype.LOADING = LOADING;
  prototype.DONE = DONE;

  function clearTimers(state) {
    if (state.waitTimer !== null) {
      clearTimeout(state.waitTimer);
      state.waitTimer = null;
    }
    if (state.timeoutTimer !== null) {
      clearTimeout(state.timeoutTimer);
      state.timeoutTimer = null;
    }
  }

  function startPassthrough(target, state, body) {
    releaseOutstanding(state);
    state.intercepted = false;
    state.nativeActive = true;
    const native = state.native;
    state.suppressNativeOpenEvent = true;
    try {
      native.open(state.method, state.url, state.async, state.user, state.password);
    } finally {
      state.suppressNativeOpenEvent = false;
    }
    for (const header of state.requestHeaders) {
      native.setRequestHeader(header[0], header[1]);
    }
    if (state.mimeType !== null) {
      native.overrideMimeType(state.mimeType);
    }
    native.responseType = state.responseType;
    native.timeout = state.timeout;
    native.withCredentials = state.withCredentials;
    native.send(body === undefined ? null : body);
  }

  function applyResponse(target, state, response, fireEvents) {
    releaseOutstanding(state);
    state.status = response.status;
    state.statusText = response.statusText;
    const headers = [];
    for (const header of response.headers) {
      headers.push([header[0], header[1]]);
    }
    headers.push(["content-type", response.contentType]);
    headers.push(["content-length", String(response.headLength ?? response.body.length)]);
    state.responseHeaders = headers;
    state.responseBytes = response.body;
    state.responseURL = new URL(state.url, baseHref()).href;
    const deliver = () => {
      if (state.uploadFlag) {
        dispatchEventOn(target, state.uploadListeners, state.uploadHandlers, "load");
        dispatchEventOn(target, state.uploadListeners, state.uploadHandlers, "loadend");
      }
      dispatchEventOn(target, state.listeners, state.handlers, "load");
      dispatchEventOn(target, state.listeners, state.handlers, "loadend");
    };
    if (!fireEvents) {
      state.readyState = DONE;
      state.done = true;
      return;
    }
    state.readyState = HEADERS_RECEIVED;
    dispatchEventOn(target, state.listeners, state.handlers, "readystatechange");
    state.readyState = LOADING;
    dispatchEventOn(target, state.listeners, state.handlers, "readystatechange");
    dispatchEventOn(target, state.listeners, state.handlers, "progress");
    state.readyState = DONE;
    state.done = true;
    dispatchEventOn(target, state.listeners, state.handlers, "readystatechange");
    deliver();
  }

  function applyNotFound(target, state, fireEvents, route) {
    applyResponse(
      target,
      state,
      route ? withHeadHandling(route, createResponse(404, [], new Uint8Array(0), "application/octet-stream")) : createResponse(404, [], new Uint8Array(0), "application/octet-stream"),
      fireEvents
    );
  }

  function runErrorSteps(target, state, type) {
    if (state.done || state.nativeActive) {
      return;
    }
    clearTimers(state);
    releaseOutstanding(state);
    state.readyState = DONE;
    state.done = true;
    dispatchEventOn(target, state.listeners, state.handlers, "readystatechange");
    dispatchEventOn(target, state.listeners, state.handlers, type);
    dispatchEventOn(target, state.listeners, state.handlers, "loadend");
  }

  function scheduleLocalResponse(target, state, requestId, complete) {
    if (state.uploadFlag) {
      dispatchEventOn(target, state.uploadListeners, state.uploadHandlers, "loadstart");
    }
    dispatchEventOn(target, state.listeners, state.handlers, "loadstart");
    if (state.timeout > 0) {
      state.timeoutTimer = setTimeout(() => {
        if (state.requestId !== requestId || state.aborted || state.done) {
          return;
        }
        runErrorSteps(target, state, "timeout");
      }, state.timeout);
    }
    setTimeout(() => {
      if (state.requestId !== requestId || state.aborted || state.done) {
        return;
      }
      clearTimers(state);
      complete();
    }, 0);
  }

  function cachedIndexForBridgeRevision(bridge) {
    if (typeof bridge.getRevision !== "function") {
      return null;
    }
    let revision = null;
    try {
      revision = bridge.getRevision();
    } catch (_error) {
      return null;
    }
    if (revision === null) {
      // The bridge publishes no bundle revision yet: the payload must not be cloned.
      return false;
    }
    if (bundleCache.index !== null && revision === bundleCache.revision) {
      return bundleCache.index;
    }
    return null;
  }

  function dropCachedBundle() {
    bundleCache.revision = null;
    bundleCache.index = null;
  }

  function resolveBundleNow() {
    const bridge = global[BRIDGE_KEY];
    if (!bridge || typeof bridge.getBundle !== "function") {
      return bundleCache.index;
    }
    const cached = cachedIndexForBridgeRevision(bridge);
    if (cached === false) {
      let bridgeState = null;
      try {
        bridgeState = typeof bridge.getState === "function" ? bridge.getState() : null;
      } catch (_error) {
        bridgeState = null;
      }
      if (bridgeState && !bridgeState.ready) {
        // A live state that no longer reports a bundle means the payload was torn down
        // (session replacement): drop the cached page-side data instead of serving it.
        dropCachedBundle();
      }
      return null;
    }
    if (cached !== null) {
      return cached;
    }
    let payload = null;
    let bridgeState = null;
    try {
      payload = bridge.getBundle();
      if (!payload && typeof bridge.getState === "function") {
        bridgeState = bridge.getState();
      }
    } catch (_error) {
      payload = null;
    }
    if (payload) {
      return getIndexForPayload(payload);
    }
    if (bridgeState && bridgeState.ready) {
      return null;
    }
    if (bridgeState) {
      dropCachedBundle();
    }
    return null;
  }

  function waitForBundle(target, state, requestId, onResolved) {
    const startedAt = Date.now();
    const poll = () => {
      state.waitTimer = null;
      if (state.requestId !== requestId || state.aborted || state.done) {
        return;
      }
      const bridge = global[BRIDGE_KEY];
      let payload = null;
      let bridgeState = null;
      if (bridge && typeof bridge.getBundle === "function") {
        const cached = cachedIndexForBridgeRevision(bridge);
        if (cached === false) {
          try {
            bridgeState = typeof bridge.getState === "function" ? bridge.getState() : null;
          } catch (_error) {
            bridgeState = null;
          }
        } else if (cached !== null) {
          onResolved(cached);
          return;
        } else {
          try {
            payload = bridge.getBundle();
            if (!payload && typeof bridge.getState === "function") {
              bridgeState = bridge.getState();
            }
          } catch (_error) {
            payload = null;
          }
        }
      }
      if (payload) {
        onResolved(getIndexForPayload(payload));
        return;
      }
      if (bridgeState && bridgeState.ready) {
        onResolved(null);
        return;
      }
      if (Date.now() - startedAt > READY_WAIT_MS) {
        onResolved(null);
        return;
      }
      state.waitTimer = setTimeout(poll, READY_POLL_MS);
    };
    state.waitTimer = setTimeout(poll, 0);
  }

  prototype.open = function (method, url, async, user, password) {
    const state = states.get(this);
    if (typeof method !== "string" || !TOKEN_PATTERN.test(method)) {
      throw new SyntaxError("invalid method");
    }
    const upper = method.toUpperCase();
    if (upper === "CONNECT" || upper === "TRACE" || upper === "TRACK") {
      throw new SyntaxError("unsupported method");
    }
    clearTimers(state);
    if (state.nativeActive && state.native.readyState !== DONE) {
      try {
        state.native.abort();
      } catch (_error) {
        // Continue opening the new request even if the previous native request cannot abort.
      }
    }
    releaseOutstanding(state);
    state.nativeActive = false;
    state.requestId += 1;
    state.method = upper;
    state.url = String(url);
    state.async = async === undefined ? true : Boolean(async);
    state.user = user === undefined ? null : String(user);
    state.password = password === undefined ? null : String(password);
    state.opened = true;
    state.sent = false;
    state.decided = false;
    state.intercepted = false;
    state.nativeActive = false;
    state.counted = false;
    state.requestHeaders = [];
    state.readyState = OPENED;
    state.status = 0;
    state.statusText = "";
    state.responseHeaders = [];
    state.responseBytes = null;
    state.responseURL = "";
    state.aborted = false;
    state.done = false;
    dispatchEventOn(this, state.listeners, state.handlers, "readystatechange");
  };

  prototype.send = function (body) {
    const state = states.get(this);
    if (!state.opened || state.sent) {
      throw invalidStateError("send");
    }
    state.sent = true;
    state.pendingBody = body === undefined ? null : body;
    const requestId = state.requestId;
    const route = resolveRoute(state.method, state.url, baseHref());
    state.decided = true;
    if (route.kind === "passthrough") {
      startPassthrough(this, state, body);
      return;
    }
    state.intercepted = true;
    if (route.kind === "methodNotAllowed") {
      if (!state.async) {
        const outcome = serveRoute(null, route, state.requestHeaders, state.mimeType);
        applyResponse(this, state, outcome.response, false);
        return;
      }
      scheduleLocalResponse(this, state, requestId, () => {
        const outcome = serveRoute(null, route, state.requestHeaders, state.mimeType);
        applyResponse(this, state, outcome.response, true);
      });
      return;
    }
    if (!state.async) {
      const index = resolveBundleNow();
      const outcome = serveRoute(index, route, state.requestHeaders, state.mimeType);
      if (outcome.kind === "passthrough") {
        startPassthrough(this, state, body);
        return;
      }
      if (outcome.kind === "notFound") {
        applyNotFound(this, state, false, route);
        return;
      }
      applyResponse(this, state, outcome.response, false);
      return;
    }
    if (outstandingRequests >= MAX_CONCURRENT_REQUESTS) {
      scheduleLocalResponse(this, state, requestId, () => {
        applyNotFound(this, state, true, route);
      });
      return;
    }
    outstandingRequests += 1;
    state.counted = true;
    if (state.uploadFlag) {
      dispatchEventOn(this, state.uploadListeners, state.uploadHandlers, "loadstart");
    }
    dispatchEventOn(this, state.listeners, state.handlers, "loadstart");
    if (state.timeout > 0) {
      state.timeoutTimer = setTimeout(() => {
        if (state.requestId !== requestId || state.aborted || state.done) {
          return;
        }
        runErrorSteps(this, state, "timeout");
      }, state.timeout);
    }
    waitForBundle(this, state, requestId, (index) => {
      if (state.requestId !== requestId || state.aborted || state.done) {
        return;
      }
      clearTimers(state);
      const outcome = serveRoute(index, route, state.requestHeaders, state.mimeType);
      if (outcome.kind === "passthrough") {
        startPassthrough(this, state, state.pendingBody);
        return;
      }
      if (outcome.kind === "notFound") {
        applyNotFound(this, state, true, route);
        return;
      }
      applyResponse(this, state, outcome.response, true);
    });
  };

  prototype.abort = function () {
    const state = states.get(this);
    clearTimers(state);
    releaseOutstanding(state);
    state.aborted = true;
    if (state.nativeActive) {
      state.native.abort();
      state.readyState = state.native.readyState;
      if (state.readyState === UNSENT) {
        state.status = 0;
        state.statusText = "";
        state.responseHeaders = [];
        state.responseBytes = null;
        state.responseURL = "";
        state.nativeActive = false;
      }
      return;
    }
    if (!state.intercepted || !state.sent) {
      if (state.readyState === DONE) {
        state.readyState = UNSENT;
      }
      return;
    }
    if (state.readyState === OPENED || state.readyState === HEADERS_RECEIVED ||
        state.readyState === LOADING) {
      state.readyState = DONE;
      state.done = true;
      dispatchEventOn(this, state.listeners, state.handlers, "readystatechange");
      dispatchEventOn(this, state.listeners, state.handlers, "abort");
      dispatchEventOn(this, state.listeners, state.handlers, "loadend");
    }
    if (state.readyState === DONE) {
      state.readyState = UNSENT;
    }
  };

  prototype.setRequestHeader = function (name, value) {
    const state = states.get(this);
    if (!state.opened || state.sent) {
      throw invalidStateError("setRequestHeader");
    }
    if (typeof name !== "string" || !TOKEN_PATTERN.test(name)) {
      throw new SyntaxError("invalid header name");
    }
    state.requestHeaders.push([name.toLowerCase(), String(value)]);
  };

  prototype.getAllResponseHeaders = function () {
    const state = states.get(this);
    if (state.nativeActive) {
      return state.native.getAllResponseHeaders();
    }
    if (state.readyState < HEADERS_RECEIVED) {
      return "";
    }
    return state.responseHeaders
      .map((header) => header[0] + ": " + header[1])
      .join("\r\n") + (state.responseHeaders.length > 0 ? "\r\n" : "");
  };

  prototype.getResponseHeader = function (name) {
    const state = states.get(this);
    if (state.nativeActive) {
      return state.native.getResponseHeader(name);
    }
    if (typeof name !== "string" || state.readyState < HEADERS_RECEIVED) {
      return null;
    }
    const lower = name.toLowerCase();
    const values = [];
    for (const header of state.responseHeaders) {
      if (header[0] === lower) {
        values.push(header[1]);
      }
    }
    return values.length > 0 ? values.join(", ") : null;
  };

  prototype.overrideMimeType = function (mime) {
    const state = states.get(this);
    if (state.readyState === LOADING || state.readyState === DONE) {
      throw invalidStateError("overrideMimeType");
    }
    state.mimeType = String(mime);
  };

  prototype.addEventListener = function (type, callback, options) {
    const state = states.get(this);
    state.listeners.push({
      type: String(type),
      callback: callback,
      options: options || undefined
    });
  };

  prototype.removeEventListener = function (type, callback, options) {
    const state = states.get(this);
    const position = state.listeners.findIndex(
      (entry) => entry.type === String(type) && entry.callback === callback
    );
    if (position !== -1) {
      state.listeners.splice(position, 1);
    }
  };

  prototype.dispatchEvent = function (event) {
    const state = states.get(this);
    dispatchEventOn(this, state.listeners, state.handlers, String(event.type), event);
    return true;
  };

  Object.defineProperty(prototype, "readyState", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      return state.nativeActive ? state.native.readyState : state.readyState;
    }
  });

  Object.defineProperty(prototype, "status", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      return state.nativeActive ? state.native.status : state.status;
    }
  });

  Object.defineProperty(prototype, "statusText", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      return state.nativeActive ? state.native.statusText : state.statusText;
    }
  });

  Object.defineProperty(prototype, "responseURL", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      if (state.nativeActive) {
        return state.native.responseURL;
      }
      return state.readyState >= HEADERS_RECEIVED ? state.responseURL : "";
    }
  });

  Object.defineProperty(prototype, "responseText", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      if (state.nativeActive) {
        return state.native.responseText;
      }
      if (state.responseType !== "" && state.responseType !== "text") {
        throw invalidStateError("responseText");
      }
      if (state.readyState !== LOADING && state.readyState !== DONE) {
        return "";
      }
      return utf8Decode(state.responseBytes);
    }
  });

  Object.defineProperty(prototype, "responseXML", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      if (state.nativeActive) {
        return state.native.responseXML;
      }
      return null;
    }
  });

  Object.defineProperty(prototype, "response", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      if (state.nativeActive) {
        return state.native.response;
      }
      const type = state.responseType;
      if (type === "" || type === "text") {
        if (state.readyState !== LOADING && state.readyState !== DONE) {
          return "";
        }
        return utf8Decode(state.responseBytes);
      }
      if (state.readyState !== DONE || state.responseBytes === null) {
        return null;
      }
      if (type === "arraybuffer") {
        return state.responseBytes.slice().buffer;
      }
      if (type === "blob") {
        if (typeof global.Blob === "function") {
          return new global.Blob([byteString(state.responseBytes)], {
            type: state.responseHeaders
              .filter((header) => header[0] === "content-type")
              .map((header) => header[1])
              .join(", ") || ""
          });
        }
        return null;
      }
      if (type === "json") {
        try {
          return JSON.parse(utf8Decode(state.responseBytes));
        } catch (_error) {
          return null;
        }
      }
      return null;
    }
  });

  Object.defineProperty(prototype, "responseType", {
    enumerable: true,
    get: function () {
      return states.get(this).responseType;
    },
    set: function (value) {
      const state = states.get(this);
      const type = String(value);
      if (state.readyState === LOADING || state.readyState === DONE) {
        throw invalidStateError("responseType");
      }
      if (!state.async && type !== "" && type !== "text") {
        throw invalidStateError("responseType");
      }
      state.responseType = type;
      if (state.nativeActive) {
        state.native.responseType = type;
      }
    }
  });

  Object.defineProperty(prototype, "timeout", {
    enumerable: true,
    get: function () {
      return states.get(this).timeout;
    },
    set: function (value) {
      const state = states.get(this);
      state.timeout = Number(value) || 0;
      if (state.nativeActive) {
        state.native.timeout = state.timeout;
      }
    }
  });

  Object.defineProperty(prototype, "withCredentials", {
    enumerable: true,
    get: function () {
      return states.get(this).withCredentials;
    },
    set: function (value) {
      const state = states.get(this);
      if (state.readyState === DONE) {
        throw invalidStateError("withCredentials");
      }
      state.withCredentials = Boolean(value);
      if (state.nativeActive) {
        state.native.withCredentials = state.withCredentials;
      }
    }
  });

  Object.defineProperty(prototype, "upload", {
    enumerable: true,
    get: function () {
      const state = states.get(this);
      if (!state.uploadProxy) {
        const target = this;
        state.uploadProxy = {
          addEventListener: (type, callback, options) => {
            state.uploadFlag = true;
            state.uploadListeners.push({
              type: String(type),
              callback: callback,
              options: options || undefined
            });
          },
          removeEventListener: (type, callback) => {
            const position = state.uploadListeners.findIndex(
              (entry) => entry.type === String(type) && entry.callback === callback
            );
            if (position !== -1) {
              state.uploadListeners.splice(position, 1);
            }
          },
          dispatchEvent: (event) => {
            dispatchEventOn(
              state.uploadProxy,
              state.uploadListeners,
              state.uploadHandlers,
              String(event.type),
              event
            );
            return true;
          }
        };
        for (const type of ["loadstart", "progress", "abort", "error", "load", "timeout", "loadend"]) {
          Object.defineProperty(state.uploadProxy, "on" + type, {
            enumerable: true,
            get: () => state.uploadHandlers["on" + type] || null,
            set: (callback) => {
              state.uploadFlag = true;
              registerHandler(state.uploadListeners, type, callback);
              state.uploadHandlers["on" + type] = callback;
            }
          });
        }
      }
      return state.uploadProxy;
    }
  });

  for (const type of HANDLER_TYPES) {
    Object.defineProperty(prototype, "on" + type, {
      enumerable: true,
      get: function () {
        const state = states.get(this);
        return state.handlers["on" + type] || null;
      },
      set: function (callback) {
        const state = states.get(this);
        registerHandler(state.listeners, type, callback);
        state.handlers["on" + type] = callback;
      }
    });
  }

  Object.defineProperty(global, "XMLHttpRequest", {
    value: OverlayXMLHttpRequestConstructor,
    writable: true,
    configurable: true
  });
})(typeof window !== "undefined" ? window : globalThis);
