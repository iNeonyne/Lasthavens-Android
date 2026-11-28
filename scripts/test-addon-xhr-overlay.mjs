import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import vm from "node:vm";

const overlayPath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/xhr-overlay.js",
  import.meta.url,
);
const overlaySource = readFileSync(fileURLToPath(overlayPath), "utf8");
const overlayBytes = readFileSync(fileURLToPath(overlayPath));

const BASE_HREF = "https://lasthavens.com/play";
const ADDON_PREFIX_TEST = "Interface/AddOns/";
const REVISION = "a".repeat(64);

function createContext(overrides = {}) {
  const context = vm.createContext({
    URL,
    TextDecoder,
    Blob,
    atob,
    btoa,
    setTimeout,
    clearTimeout,
    console,
    location: { href: BASE_HREF },
    ...overrides,
  });
  return context;
}

function loadCore() {
  const context = createContext();
  vm.runInContext(overlaySource, context, { filename: "xhr-overlay.js" });
  return context.__LHAddonXhrOverlayCore;
}

function makeAddon(folder, files) {
  return { folder, files: Object.assign(Object.create(null), files) };
}

function makePayload(addons) {
  return { revision: REVISION, addons };
}

function makeBridge(payload, state = { ready: true, error: null }) {
  return {
    getBundle: () => payload,
    getState: () => state,
  };
}

function collectEvents(target, context) {
  const events = [];
  for (const type of [
    "readystatechange",
    "loadstart",
    "progress",
    "abort",
    "error",
    "load",
    "timeout",
    "loadend",
  ]) {
    target.addEventListener(type, () => {
      events.push(type + ":" + target.readyState);
    });
  }
  return events;
}

const NATIVE_HANDLED = new Set([
  "readystatechange",
  "loadstart",
  "progress",
  "abort",
  "error",
  "load",
  "timeout",
  "loadend",
]);

function makeMiniUpload() {
  const listeners = new Map();
  return {
    addEventListener: (type, callback) => {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(callback);
    },
    removeEventListener: (type, callback) => {
      const list = listeners.get(type) || [];
      const index = list.indexOf(callback);
      if (index !== -1) list.splice(index, 1);
    },
    dispatchEvent: (event) => {
      for (const callback of listeners.get(event.type) || []) callback(event);
    },
    _listeners: listeners,
  };
}

class MockNativeXHR {
  constructor() {
    this.readyState = 0;
    this.status = 0;
    this.statusText = "";
    this.response = null;
    this.responseText = "";
    this.responseXML = null;
    this.responseURL = "";
    this.responseType = "";
    this.timeout = 0;
    this.withCredentials = false;
    this.upload = makeMiniUpload();
    this._listeners = new Map();
    this._requestHeaders = [];
    this._opened = false;
    this._sent = false;
  }
  open(method, url, async, user, password) {
    this._opened = true;
    this._openArgs = { method, url, async, user, password };
    this.readyState = 1;
  }
  setRequestHeader(name, value) {
    this._requestHeaders.push([name.toLowerCase(), value]);
  }
  overrideMimeType(mime) {
    this._mime = mime;
  }
  send(body) {
    this._sent = true;
    this._body = body === undefined ? null : body;
    this._deliver();
  }
  abort() {
    this.readyState = 0;
    this._fire("abort");
    this._fire("loadend");
  }
  getAllResponseHeaders() {
    return "x-mock: yes\r\n";
  }
  getResponseHeader(name) {
    return name.toLowerCase() === "x-mock" ? "yes" : null;
  }
  addEventListener(type, callback) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(callback);
  }
  removeEventListener(type, callback) {
    const list = this._listeners.get(type) || [];
    const index = list.indexOf(callback);
    if (index !== -1) list.splice(index, 1);
  }
  dispatchEvent(event) {
    this._fire(event.type);
    return true;
  }
  _fire(type) {
    const handler = this["on" + type];
    if (typeof handler === "function") handler.call(this, { type });
    for (const callback of this._listeners.get(type) || []) {
      callback.call(this, { type });
    }
  }
  _deliver({
    status = 200,
    statusText = "OK",
    bodyText = "mock-body",
    url = "https://lasthavens.com/mock",
    headers = [["content-type", "text/plain"]],
  } = {}) {
    this.status = status;
    this.statusText = statusText;
    this.responseURL = url;
    this.responseText = bodyText;
    this.response = bodyText;
    this.readyState = 2;
    this._fire("readystatechange");
    this.readyState = 3;
    this._fire("readystatechange");
    this.readyState = 4;
    this._fire("readystatechange");
    this._fire("load");
    this._fire("loadend");
  }
}

function loadWrapper({ payload, bridgeState, dateNow, nativeXhr, atobImpl } = {}) {
  const overrides = {
    XMLHttpRequest: nativeXhr || MockNativeXHR,
    atob: atobImpl || atob,
  };
  if (dateNow) {
    overrides.Date = { now: dateNow, parse: Date.parse, UTC: Date.UTC };
  }
  const context = createContext(overrides);
  if (payload !== undefined || bridgeState !== undefined) {
    context.__LASTHAVEN_ADDON_BRIDGE__ = makeBridge(payload, bridgeState);
    vm.runInContext(
      "this.__LASTHAVEN_ADDON_BRIDGE__ = __LASTHAVEN_ADDON_BRIDGE__",
      context,
    );
  }
  vm.runInContext(overlaySource, context, { filename: "xhr-overlay.js" });
  const XHR = context.XMLHttpRequest;
  assert.notEqual(XHR, overrides.XMLHttpRequest, "overlay must replace XMLHttpRequest");
  const instances = [];
  const OriginalXHR = XHR;
  const tracking = class extends OriginalXHR {};
  return { context, XHR: OriginalXHR, instances };
}

function b64(text) {
  return Buffer.from(text, "utf8").toString("base64");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

test("manifest keeps the MAIN-world overlay as an exact-route content script", () => {
  const manifest = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL(
          "../app/src/main/assets/extensions/addon-bridge/manifest.json",
          import.meta.url,
        ),
      ),
      "utf8",
    ),
  );
  assert.equal(manifest.content_scripts.length, 4);
  const isolated = manifest.content_scripts[0];
  assert.deepEqual(isolated.js, ["payload-validation.js", "bridge.js"]);
  assert.equal(isolated.world, undefined);
  const overlay = manifest.content_scripts[1];
  assert.deepEqual(overlay.js, ["xhr-overlay.js"]);
  assert.equal(overlay.world, "MAIN");
  assert.equal(overlay.run_at, "document_start");
  assert.equal(overlay.all_frames, false);
  assert.deepEqual(overlay.matches, [
    "https://lasthavens.com/play",
    "https://lasthavens.com/play/",
    "https://alt.lasthavens.com/play",
    "https://alt.lasthavens.com/play/",
  ]);
});

test("overlay source stays free of page-forgeable and storage channels", () => {
  const source = overlaySource;
  assert.equal(source.includes("postMessage"), false);
  assert.equal(source.includes("eval("), false);
  assert.equal(source.includes("browser.storage"), false);
  assert.equal(source.includes("indexedDB"), false);
  assert.equal(source.includes("document.cookie"), false);
  assert.equal(source.includes("browser.runtime.sendMessage"), false);
  assert.equal(source.includes("__LASTHAVEN_ADDON_BRIDGE__"), true);
  assert.equal(source.includes("getBundle"), true);
});

test("core resolves exact game file routes and rejects everything else", () => {
  const core = loadCore();
  const file = core.resolveRoute(
    "GET",
    "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    BASE_HREF,
  );
  assert.equal(file.kind, "file");
  assert.equal(file.payloadPath, "Interface/AddOns/Foo/bar.lua");
  assert.equal(file.block, null);
  assert.equal(file.isHead, false);

  const alternateFile = core.resolveRoute(
    "GET",
    "https://alt.lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    "https://alt.lasthavens.com/play",
  );
  assert.equal(alternateFile.kind, "file");
  assert.equal(alternateFile.payloadPath, "Interface/AddOns/Foo/bar.lua");
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
      "https://alt.lasthavens.com/play",
    ).kind,
    "passthrough",
    "alternate-server pages must not intercept requests to the default origin",
  );

  const head = core.resolveRoute(
    "head",
    "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    BASE_HREF,
  );
  assert.equal(head.isHead, true);

  assert.equal(
    core.resolveRoute(
      "POST",
      "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
      BASE_HREF,
    ).kind,
    "methodNotAllowed",
  );
  assert.equal(
    core.resolveRoute("GET", "https://lasthavens.com/game/Assets/texture.png", BASE_HREF)
      .kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute("GET", "https://lasthavens.com/blocks/Other/data.bin", BASE_HREF)
      .kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute("POST", "https://lasthavens.com/game/Assets/texture.png", BASE_HREF)
      .kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute("GET", "https://lasthavens.com/play", BASE_HREF).kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute("GET", "https://other.com/game/Interface/AddOns/Foo/x", BASE_HREF)
      .kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com:8443/game/Interface/AddOns/Foo/x",
      BASE_HREF,
    ).kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://user:pass@lasthavens.com/game/Interface/AddOns/Foo/x",
      BASE_HREF,
    ).kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com/game/Interface/AddOns/Foo/x?query=1",
      BASE_HREF,
    ).kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com/game/Interface/AddOns/Foo/x#frag",
      BASE_HREF,
    ).kind,
    "passthrough",
  );
  assert.equal(
    core.resolveRoute("GET", "http://lasthavens.com/game/Interface/AddOns/Foo/x", BASE_HREF)
      .kind,
    "passthrough",
  );
});

test("core parses block slice URLs into bounded offsets", () => {
  const core = loadCore();
  const route = core.resolveRoute(
    "GET",
    "https://lasthavens.com/blocks/Interface/AddOns/Foo/data-7-2-4k.bin",
    BASE_HREF,
  );
  assert.equal(route.kind, "file");
  assert.equal(route.payloadPath, "Interface/AddOns/Foo/data");
  assert.equal(route.block.offset, 2 * 4 * 1024);
  assert.equal(route.block.length, 4 * 1024);

  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com/blocks/Interface/AddOns/Foo/x-0-0-0k.bin",
      BASE_HREF,
    ).kind,
    "notFound",
  );
  assert.equal(
    core.resolveRoute(
      "GET",
      "https://lasthavens.com/blocks/Interface/AddOns/Foo/x-0-99999999999999999999-4k.bin",
      BASE_HREF,
    ).kind,
    "notFound",
  );
});

test("core marks bundle block URLs for masking", () => {
  const core = loadCore();
  const route = core.resolveRoute(
    "GET",
    "https://lasthavens.com/blocks/Interface/AddOns/Foo/bundle-1234.bin",
    BASE_HREF,
  );
  assert.equal(route.kind, "bundleMask");
  assert.equal(route.folder, "Foo");
});

test("core fails closed on traversal, escapes and unsafe decoded paths", () => {
  const core = loadCore();
  const urls = [
    "https://lasthavens.com/game/Interface/AddOns/Foo/%252e%252e%252ftrap.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/back%5Cslash.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/col%3Aon.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/nul%00byte.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/%zzmalformed.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/" + "x".repeat(600) + ".lua",
  ];
  for (const url of urls) {
    assert.equal(core.resolveRoute("GET", url, BASE_HREF).kind, "notFound", url);
  }
  assert.equal(
    core.resolveRoute("GET", "https://lasthavens.com/game//double-slash.lua", BASE_HREF)
      .kind,
    "passthrough",
  );
});

test("encoded spellings of the addon prefix fail closed instead of reaching the network", () => {
  const core = loadCore();
  for (const url of [
    "https://lasthavens.com/game/%49nterface/AddOns/Foo/bar.lua",
    "https://lasthavens.com/game/Interface%2FAddOns%2FFoo%2Fbar.lua",
    "https://lasthavens.com/blocks/%49nterface/AddOns/Foo/data.bin",
    "https://lasthavens.com/game/%49nterface/AddOns/Foo/%2e%2e/escape.lua",
    "https://lasthavens.com/game/Interface%2FAddOns%2FFoo%2F%2e%2e%2Fescape.lua",
  ]) {
    assert.equal(core.resolveRoute("GET", url, BASE_HREF).kind, "notFound", url);
  }
});

test("URL-normalized dot segments that leave the addon prefix pass through untouched", () => {
  const core = loadCore();
  for (const url of [
    "https://lasthavens.com/game/Interface/AddOns/%2e%2e/escape.lua",
    "https://lasthavens.com/game/Interface/AddOns/Foo/../../../etc/passwd",
  ]) {
    assert.equal(core.resolveRoute("GET", url, BASE_HREF).kind, "passthrough", url);
  }
});

test("plain dot-segment traversal is URL-normalized outside the addon prefix", () => {
  const core = loadCore();
  const route = core.resolveRoute(
    "GET",
    "https://lasthavens.com/game/Interface/AddOns/Foo/../../../etc/passwd",
    BASE_HREF,
  );
  assert.equal(route.kind, "passthrough");
});

test("encoded slash cannot forge a longer payload path", () => {
  const core = loadCore();
  const route = core.resolveRoute(
    "GET",
    "https://lasthavens.com/game/Interface/AddOns/Foo/x%2F..%2F..%2Fsecret.lua",
    BASE_HREF,
  );
  assert.equal(route.kind, "notFound");
});

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

test("range parser handles closed, open-ended, suffix, multipart and malformed specs", () => {
  const core = loadCore();
  assert.deepEqual(plain(core.parseRange("bytes=0-4", 10)), {
    kind: "ranges",
    ranges: [{ start: 0, end: 4 }],
  });
  assert.deepEqual(plain(core.parseRange("bytes=5-", 10)), {
    kind: "ranges",
    ranges: [{ start: 5, end: 9 }],
  });
  assert.deepEqual(plain(core.parseRange("bytes=-3", 10)), {
    kind: "ranges",
    ranges: [{ start: 7, end: 9 }],
  });
  assert.deepEqual(plain(core.parseRange("bytes=-100", 10)), {
    kind: "ranges",
    ranges: [{ start: 0, end: 9 }],
  });
  assert.equal(core.parseRange("bytes=5-2", 10).kind, "ignore");
  assert.equal(core.parseRange("bytes=abc", 10).kind, "ignore");
  assert.equal(core.parseRange("chars=0-4", 10).kind, "ignore");
  assert.equal(core.parseRange("bytes=10-", 10).kind, "unsatisfiable");
  assert.equal(core.parseRange("bytes=10-20", 10).kind, "unsatisfiable");
  assert.equal(core.parseRange("bytes=-0", 10).kind, "unsatisfiable");
  assert.equal(core.parseRange("bytes=0-0", 0).kind, "unsatisfiable");
  assert.equal(core.parseRange("bytes=-1", 0).kind, "unsatisfiable");
  const multi = core.parseRange("bytes=0-1,4-5,8-", 10);
  assert.equal(multi.kind, "ranges");
  assert.equal(multi.ranges.length, 3);
  assert.equal(core.parseRange("bytes=" + Array(20).fill("0-1").join(","), 10).kind, "ignore");
  assert.deepEqual(plain(core.parseRange("bytes=0-99", 10)), {
    kind: "ranges",
    ranges: [{ start: 0, end: 9 }],
  });
});

test("content type mapping follows documented extensions and blocks stay binary", () => {
  const core = loadCore();
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/ui.xml", false), "text/xml");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/data.json", false), "application/json");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/readme.txt", false), "text/plain");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/Foo.toc", false), "text/plain");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/logic.lua", false), "text/plain");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/texture.bin", false), "application/octet-stream");
  assert.equal(core.contentTypeFor("Interface/AddOns/Foo/data", true), "application/octet-stream");
});

function makePayloadWithFiles(files, folder = "Foo") {
  const payloadFiles = { [folder + ".toc"]: "" };
  for (const [path, content] of Object.entries(files)) {
    payloadFiles[path] = content === "" ? "" : b64(content);
  }
  return makePayload([makeAddon(folder, payloadFiles)]);
}

async function deliveredXhr({
  url,
  method = "GET",
  payload,
  bridgeState,
  requestHeaders,
  responseType,
  timeout,
  async = true,
  dateNow,
} = {}) {
  const { context, XHR } = loadWrapper({ payload, bridgeState, dateNow });
  const xhr = new context.XMLHttpRequest();
  const events = collectEvents(xhr, context);
  xhr.open(method, url, async);
  if (responseType) xhr.responseType = responseType;
  if (timeout) xhr.timeout = timeout;
  if (requestHeaders) {
    for (const [name, value] of requestHeaders) xhr.setRequestHeader(name, value);
  }
  xhr.send();
  await sleep(25);
  return { xhr, events, context };
}

test("async intercepted request delivers the full payload with native ordering", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "hello addon" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  const events = collectEvents(xhr, context);
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  assert.equal(xhr.readyState, 1);
  assert.equal(xhr.status, 0);
  await sleep(25);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.statusText, "OK");
  assert.equal(xhr.responseText, "hello addon");
  assert.equal(xhr.response, "hello addon");
  assert.equal(xhr.responseURL, "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  assert.equal(xhr.getResponseHeader("Content-Type"), "text/plain");
  assert.equal(xhr.getResponseHeader("content-length"), "11");
  assert.equal(
    xhr.getAllResponseHeaders(),
    "content-type: text/plain\r\ncontent-length: 11\r\n",
  );
  assert.deepEqual(events, [
    "readystatechange:1",
    "loadstart:1",
    "readystatechange:2",
    "readystatechange:3",
    "progress:3",
    "readystatechange:4",
    "load:4",
    "loadend:4",
  ]);
});

test("synchronous intercepted request completes without firing events", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "sync body" });
  const { xhr, events } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    async: false,
  });
  assert.equal(xhr.readyState, 4);
  assert.equal(xhr.responseText, "sync body");
  assert.equal(xhr.status, 200);
  assert.deepEqual(events, ["readystatechange:1"]);
});

test("response types expose arraybuffer, json and blob only when done", async () => {
  const payload = makePayloadWithFiles({ "data.json": '{"value":42}' });
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/data.json",
    payload,
    responseType: "json",
  });
  assert.deepEqual(plain(xhr.response), { value: 42 });

  const binary = makePayloadWithFiles({ "data.bin": "\u0001\u0002\u0003" });
  const bufferResult = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/data.bin",
    payload: binary,
    responseType: "arraybuffer",
  });
  assert.deepEqual(Array.from(new Uint8Array(bufferResult.xhr.response)), [1, 2, 3]);

  const blobResult = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/data.bin",
    payload: binary,
    responseType: "blob",
  });
  assert.equal(blobResult.xhr.response.size, 3);

  assert.throws(() => bufferResult.xhr.responseText, (error) => error.name === "InvalidStateError");
});

test("HEAD serves real length headers without a body", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "12345" });
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    method: "HEAD",
  });
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "");
  assert.equal(xhr.getResponseHeader("content-length"), "5");
});

test("missing and case-conflicting addon paths fail closed with 404", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const missing = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/absent.lua",
    payload,
  });
  assert.equal(missing.xhr.status, 404);
  assert.equal(missing.xhr.responseText, "");

  const wrongCase = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/foo/bar.lua",
    payload,
  });
  assert.equal(wrongCase.xhr.status, 404);

  const unknownFolder = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Missing/x.lua",
    payload,
  });
  assert.equal(unknownFolder.xhr.status, 404);
});

test("closed, open-ended and suffix ranges return exact 206 slices", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "0123456789" });
  const closed = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=2-5"]],
  });
  assert.equal(closed.xhr.status, 206);
  assert.equal(closed.xhr.responseText, "2345");
  assert.equal(closed.xhr.getResponseHeader("content-range"), "bytes 2-5/10");
  assert.equal(closed.xhr.getResponseHeader("content-length"), "4");

  const openEnded = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=7-"]],
  });
  assert.equal(openEnded.xhr.status, 206);
  assert.equal(openEnded.xhr.responseText, "789");

  const suffix = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=-3"]],
  });
  assert.equal(suffix.xhr.status, 206);
  assert.equal(suffix.xhr.responseText, "789");

  const clamped = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=8-99"]],
  });
  assert.equal(clamped.xhr.status, 206);
  assert.equal(clamped.xhr.responseText, "89");
});

test("unsatisfiable ranges return 416 and malformed ranges serve the full body", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "0123456789" });
  const unsatisfiable = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=10-"]],
  });
  assert.equal(unsatisfiable.xhr.status, 416);
  assert.equal(unsatisfiable.xhr.getResponseHeader("content-range"), "bytes */10");

  const malformed = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=zz-yy"]],
  });
  assert.equal(malformed.xhr.status, 200);
  assert.equal(malformed.xhr.responseText, "0123456789");

  const emptyFile = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/empty.toc",
    payload: makePayloadWithFiles({ "empty.toc": "" }),
    requestHeaders: [["Range", "bytes=0-0"]],
  });
  assert.equal(emptyFile.xhr.status, 416);
});

test("multipart ranges return a bounded deterministic byteranges body", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "0123456789" });
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload,
    requestHeaders: [["Range", "bytes=0-1,4-5"]],
  });
  assert.equal(xhr.status, 206);
  const contentType = xhr.getResponseHeader("content-type");
  assert.ok(contentType.startsWith("multipart/byteranges; boundary="));
  const body = xhr.responseText;
  assert.ok(body.includes("Content-Range: bytes 0-1/10"));
  assert.ok(body.includes("Content-Range: bytes 4-5/10"));
  assert.ok(body.includes("01"));
  assert.ok(body.includes("45"));
  assert.equal(xhr.getResponseHeader("content-range"), null);
});

test("block URLs slice local bytes with exact bounds", async () => {
  const payload = makePayloadWithFiles({ "data.bin": "0123456789ABCDEF" });
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/blocks/Interface/AddOns/Foo/data.bin-0-0-4k.bin",
    payload,
  });
  assert.equal(xhr.status, 206);
  assert.equal(xhr.getResponseHeader("content-range"), "bytes 0-15/16");
  assert.equal(xhr.responseText, "0123456789ABCDEF");
});

test("block URLs beyond the file end and on empty files fail with 416", async () => {
  const payload = makePayloadWithFiles({ "data.bin": "abc" });
  const beyond = await deliveredXhr({
    url: "https://lasthavens.com/blocks/Interface/AddOns/Foo/data.bin-0-1-4k.bin",
    payload,
  });
  assert.equal(beyond.xhr.status, 416);
  assert.equal(beyond.xhr.getResponseHeader("content-range"), "bytes */3");

  const empty = await deliveredXhr({
    url: "https://lasthavens.com/blocks/Interface/AddOns/Foo/empty.bin-0-1-4k.bin",
    payload: makePayloadWithFiles({ "empty.bin": "" }),
  });
  assert.equal(empty.xhr.status, 416);
});

test("an explicit Range header takes precedence over the block slice", async () => {
  const payload = makePayloadWithFiles({ "data.bin": "0123456789ABCDEF" });
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/blocks/Interface/AddOns/Foo/data.bin-0-1-4k.bin",
    payload,
    requestHeaders: [["Range", "bytes=0-1"]],
  });
  assert.equal(xhr.status, 206);
  assert.equal(xhr.responseText, "01");
  assert.equal(xhr.getResponseHeader("content-range"), "bytes 0-1/16");
});

test("bundle block URLs are masked only when the addon folder exists locally", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" }, "Foo");
  const masked = await deliveredXhr({
    url: "https://lasthavens.com/blocks/Interface/AddOns/Foo/bundle-9.bin",
    payload,
  });
  assert.equal(masked.xhr.status, 404);
  assert.equal(masked.xhr.responseText, "");
});

test("bundle mask for an unknown folder passes through to the native XHR", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" }, "Foo");
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  const events = [];
  xhr.open("GET", "https://lasthavens.com/blocks/Interface/AddOns/Absent/bundle-9.bin");
  xhr.addEventListener("load", () => events.push("load"));
  xhr.send();
  await sleep(25);
  assert.equal(xhr.responseText, "mock-body");
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseURL, "https://lasthavens.com/mock");
  assert.deepEqual(events, ["load"]);
});

test("async requests wait for a bridge that publishes later", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "late" });
  const { context, XHR } = loadWrapper({ payload: null, bridgeState: { ready: false, error: null } });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  const events = collectEvents(xhr, context);
  xhr.send();
  assert.equal(xhr.readyState, 1);
  context.__LASTHAVEN_ADDON_BRIDGE__ = makeBridge(payload);
  await sleep(150);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "late");
  assert.ok(events.includes("load:4"));
});

test("a bridge in an error state fails closed with 404", async () => {
  const { xhr, events } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload: null,
    bridgeState: { ready: true, error: "native_disconnected" },
  });
  assert.equal(xhr.status, 404);
  assert.ok(events.includes("load:4"));
  assert.ok(!events.includes("error:4"));
});

test("a bridge that never becomes ready fails closed after the bounded wait", async () => {
  let fakeNow = 0;
  const { xhr } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload: null,
    bridgeState: { ready: false, error: null },
    dateNow: () => {
      fakeNow += 6000;
      return fakeNow;
    },
  });
  await sleep(400);
  assert.equal(xhr.status, 404);
});

test("timeout fires error steps without corrupting the request", async () => {
  let fakeNow = 0;
  const { xhr, events } = await deliveredXhr({
    url: "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua",
    payload: null,
    bridgeState: { ready: false, error: null },
    timeout: 20,
    dateNow: () => fakeNow,
  });
  await sleep(80);
  assert.equal(xhr.status, 0);
  assert.ok(events.includes("timeout:4"));
  assert.ok(events.includes("loadend:4"));
  assert.ok(!events.includes("load:4"));
});

test("abort during the readiness wait follows the request error steps", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "late" });
  const { context, XHR } = loadWrapper({ payload: null, bridgeState: { ready: false, error: null } });
  const xhr = new context.XMLHttpRequest();
  const events = collectEvents(xhr, context);
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  xhr.abort();
  assert.equal(xhr.readyState, 0);
  assert.equal(xhr.status, 0);
  context.__LASTHAVEN_ADDON_BRIDGE__ = makeBridge(payload);
  await sleep(150);
  assert.deepEqual(events, ["readystatechange:1", "loadstart:1", "readystatechange:4", "abort:4", "loadend:4"]);
});

test("abort before send and after completion follow the abort spec", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context, XHR } = loadWrapper({ payload });
  const beforeSend = new context.XMLHttpRequest();
  const beforeEvents = collectEvents(beforeSend, context);
  beforeSend.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  beforeSend.abort();
  assert.deepEqual(beforeEvents, ["readystatechange:1"]);
  assert.equal(beforeSend.readyState, 1);

  const afterDone = new context.XMLHttpRequest();
  afterDone.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  afterDone.send();
  await sleep(25);
  assert.equal(afterDone.readyState, 4);
  const afterEvents = collectEvents(afterDone, context);
  afterDone.abort();
  assert.deepEqual(afterEvents, []);
  assert.equal(afterDone.readyState, 0);
});

test("state-machine violations throw without corrupting instances", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context, XHR } = loadWrapper({ payload });
  assert.throws(
    () => context.XMLHttpRequest.call({}),
    (error) => error !== null && typeof error === "object" && error.name === "TypeError",
  );


  const xhr = new context.XMLHttpRequest();
  assert.throws(() => xhr.send(), (error) => error.name === "InvalidStateError");
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  assert.throws(() => xhr.send(), (error) => error.name === "InvalidStateError");
  await sleep(25);
  assert.equal(xhr.readyState, 4);
  assert.throws(() => {
    xhr.responseType = "text";
  }, (error) => error.name === "InvalidStateError");
  assert.throws(
    () => xhr.open("BAD METHOD", "https://lasthavens.com/x"),
    (error) => error !== null && typeof error === "object" && error.name === "SyntaxError",
  );

  assert.throws(
    () => xhr.open("TRACE", "https://lasthavens.com/x"),
    (error) => error !== null && typeof error === "object" && error.name === "SyntaxError",
  );


  const sync = new context.XMLHttpRequest();
  sync.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua", false);
  assert.throws(() => {
    sync.responseType = "json";
  }, (error) => error.name === "InvalidStateError");
});

test("pass-through requests delegate entirely to the native XHR", () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  const events = [];
  xhr.open("POST", "https://lasthavens.com/api/submit", true, "user", "pass");
  xhr.responseType = "text";
  xhr.timeout = 1234;
  xhr.withCredentials = true;
  xhr.setRequestHeader("X-Custom", "yes");
  xhr.overrideMimeType("text/plain");
  xhr.onreadystatechange = () => events.push("rsc:" + xhr.readyState);
  xhr.addEventListener("load", () => events.push("load"));
  xhr.send("body");
  assert.equal(xhr.responseText, "mock-body");
  assert.equal(xhr.status, 200);
  assert.equal(xhr.getResponseHeader("x-mock"), "yes");
  assert.equal(xhr.getAllResponseHeaders(), "x-mock: yes\r\n");

  const nativeInstance = (() => {
    const probe = new context.XMLHttpRequest();
    probe.open("GET", "https://lasthavens.com/other");
    probe.send();
    return probe;
  })();
  assert.equal(nativeInstance.responseText, "mock-body");
  assert.deepEqual(events, ["rsc:2", "rsc:3", "rsc:4", "load"]);
});

test("pass-through events target the wrapper and invoke listeners with the wrapper as this", () => {
  const { context } = loadWrapper();
  const xhr = new context.XMLHttpRequest();
  let observed = null;
  xhr.addEventListener("load", function (event) {
    observed = { thisValue: this, target: event.target, currentTarget: event.currentTarget };
  });
  xhr.open("GET", "https://lasthavens.com/api/status");
  xhr.send();
  assert.equal(observed.thisValue, xhr);
  assert.equal(observed.target, xhr);
  assert.equal(observed.currentTarget, xhr);
});

test("pass-through upload events target the upload facade and preserve progress fields", () => {
  let native;
  class UploadNativeXHR extends MockNativeXHR {
    constructor() {
      super();
      native = this;
    }
    send(body) {
      this.upload.dispatchEvent({
        type: "progress",
        loaded: 3,
        total: 7,
        lengthComputable: true,
      });
      super.send(body);
    }
  }
  const { context } = loadWrapper({ nativeXhr: UploadNativeXHR });
  const xhr = new context.XMLHttpRequest();
  const upload = xhr.upload;
  let observed = null;
  upload.addEventListener("progress", function (event) {
    observed = { thisValue: this, target: event.target, currentTarget: event.currentTarget,
      loaded: event.loaded, total: event.total, lengthComputable: event.lengthComputable };
  });
  xhr.open("POST", "https://lasthavens.com/api/upload");
  xhr.send("payload");
  assert.equal(native._sent, true);
  assert.equal(observed.thisValue, upload);
  assert.equal(observed.target, upload);
  assert.equal(observed.currentTarget, upload);
  assert.equal(observed.loaded, 3);
  assert.equal(observed.total, 7);
  assert.equal(observed.lengthComputable, true);
});

test("opening a new pass-through request aborts the previous native request", () => {
  let native;
  class DeferredNativeXHR extends MockNativeXHR {
    constructor() {
      super();
      this.abortCount = 0;
      native = this;
    }
    send(body) {
      this._sent = true;
      this._body = body === undefined ? null : body;
    }
    abort() {
      this.abortCount += 1;
      super.abort();
    }
  }
  const { context } = loadWrapper({ nativeXhr: DeferredNativeXHR });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/api/first");
  xhr.send();
  assert.equal(native.readyState, 1);
  xhr.open("GET", "https://lasthavens.com/api/second");
  assert.equal(native.abortCount, 1);
  assert.equal(xhr.readyState, 1);
});

test("aborting a pass-through request resets the facade before XHR reuse", () => {
  let native;
  class DeferredNativeXHR extends MockNativeXHR {
    constructor() {
      super();
      this.abortCount = 0;
      native = this;
    }
    send(body) {
      this._sent = true;
      this._body = body === undefined ? null : body;
    }
    abort() {
      this.abortCount += 1;
      super.abort();
    }
  }
  const { context } = loadWrapper({ nativeXhr: DeferredNativeXHR });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/api/first");
  xhr.send();
  xhr.abort();
  assert.equal(xhr.readyState, 0);
  assert.equal(xhr.status, 0);
  assert.equal(native.abortCount, 1);
  xhr.open("GET", "https://lasthavens.com/api/second");
  assert.equal(native.abortCount, 1);
  xhr.send();
  assert.equal(native._openArgs.url, "https://lasthavens.com/api/second");
});

test("pass-through never opens the native XHR for intercepted URLs", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "local" });
  const opened = [];
  class TrackingNativeXHR extends MockNativeXHR {
    open(...args) {
      opened.push(args[1]);
      super.open(...args);
    }
  }
  const { context } = loadWrapper({ payload, nativeXhr: TrackingNativeXHR });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  await sleep(25);
  assert.equal(xhr.responseText, "local");
  assert.deepEqual(opened, []);
});

test("upload listeners receive bounded events around delivery", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  const uploadEvents = [];
  xhr.upload.addEventListener("loadstart", () => uploadEvents.push("ul-loadstart"));
  xhr.upload.addEventListener("load", () => uploadEvents.push("ul-load"));
  xhr.upload.addEventListener("loadend", () => uploadEvents.push("ul-loadend"));
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  const xhrEvents = [];
  xhr.addEventListener("loadstart", () => xhrEvents.push("xhr-loadstart"));
  xhr.addEventListener("load", () => xhrEvents.push("xhr-load"));
  xhr.send();
  await sleep(25);
  assert.deepEqual(uploadEvents, ["ul-loadstart", "ul-load", "ul-loadend"]);
  assert.deepEqual(xhrEvents, ["xhr-loadstart", "xhr-load"]);
});

test("once listeners fire exactly once and synthetic dispatch reaches listeners", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  let onceCount = 0;
  xhr.addEventListener("load", () => {
    onceCount += 1;
  }, { once: true });
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  await sleep(25);
  assert.equal(onceCount, 1);
  xhr.dispatchEvent({ type: "load" });
  assert.equal(onceCount, 1);
});

test("overrideMimeType replaces the served content type", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.overrideMimeType("application/xml");
  xhr.send();
  await sleep(25);
  assert.equal(xhr.getResponseHeader("content-type"), "application/xml");
});


test("unsupported methods fail closed for addon URLs without native networking", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "local" });
  const opened = [];
  class TrackingNativeXHR extends MockNativeXHR {
    open(...args) {
      opened.push(args[1]);
      super.open(...args);
    }
  }
  const { context } = loadWrapper({ payload, nativeXhr: TrackingNativeXHR });
  const xhr = new context.XMLHttpRequest();
  const events = collectEvents(xhr, context);
  xhr.open("POST", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  assert.equal(xhr.readyState, 1, "async rejection must not complete inside send()");
  assert.equal(xhr.status, 0);
  assert.deepEqual(opened, []);
  await sleep(25);
  assert.equal(xhr.status, 405);
  assert.equal(xhr.statusText, "Method Not Allowed");
  assert.equal(xhr.responseText, "");
  assert.equal(xhr.getResponseHeader("allow"), "GET, HEAD");
  assert.ok(events.includes("load:4"));
  assert.ok(!events.includes("error:4"));
});

test("non-addon game and blocks paths delegate to the native XHR", () => {
  const payload = makePayloadWithFiles({ "bar.lua": "local" });
  const { context } = loadWrapper({ payload });
  const gameXhr = new context.XMLHttpRequest();
  gameXhr.open("GET", "https://lasthavens.com/game/Assets/texture.png");
  gameXhr.send();
  assert.equal(gameXhr.responseText, "mock-body");
  assert.equal(gameXhr.status, 200);
  const blocksXhr = new context.XMLHttpRequest();
  blocksXhr.open("GET", "https://lasthavens.com/blocks/World/chunk.bin");
  blocksXhr.send();
  assert.equal(blocksXhr.responseText, "mock-body");
});

test("a revision change invalidates the shared bundle index cache", async () => {
  const payloadA = makePayloadWithFiles({ "bar.lua": "content-a" });
  const { context } = loadWrapper({ payload: payloadA });
  const first = new context.XMLHttpRequest();
  first.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  first.send();
  await sleep(25);
  assert.equal(first.responseText, "content-a");

  const payloadB = makePayloadWithFiles({ "bar.lua": "content-b" });
  payloadB.revision = "b".repeat(64);
  context.__LASTHAVEN_ADDON_BRIDGE__ = {
    getBundle: () => payloadB,
    getState: () => ({ ready: true, error: null }),
  };
  const second = new context.XMLHttpRequest();
  second.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  second.send();
  await sleep(25);
  assert.equal(second.responseText, "content-b");
});

test("decode aggregate bound rejects files before large allocations", async () => {
  const big1 = Buffer.alloc(8 * 1024 * 1024, 1).toString("base64");
  const big2 = Buffer.alloc(8 * 1024 * 1024, 2).toString("base64");
  const big3 = Buffer.alloc(8 * 1024 * 1024, 3).toString("base64");
  let decodeCalls = 0;
  const addon = {
    folder: "Huge",
    files: Object.assign(Object.create(null), {
      "Huge.toc": "",
      "a.bin": big1,
      "b.bin": big2,
      "c.bin": big3,
    }),
  };
  const { context } = loadWrapper({
    payload: makePayload([addon]),
    atobImpl: (value) => {
      decodeCalls += 1;
      return atob(value);
    },
  });
  const a = new context.XMLHttpRequest();
  a.open("GET", "https://lasthavens.com/game/Interface/AddOns/Huge/a.bin");
  a.send();
  await sleep(60);
  assert.equal(a.status, 200);
  assert.equal(a.responseText.length, 8 * 1024 * 1024);

  const b = new context.XMLHttpRequest();
  b.open("GET", "https://lasthavens.com/game/Interface/AddOns/Huge/b.bin");
  b.send();
  await sleep(60);
  assert.equal(b.status, 200);

  const c = new context.XMLHttpRequest();
  c.open("GET", "https://lasthavens.com/game/Interface/AddOns/Huge/c.bin");
  c.send();
  await sleep(25);
  assert.equal(c.status, 404);
  assert.equal(decodeCalls, 2, "over-limit file must be rejected before atob allocates it");
});

test("concurrent intercepted requests are capped and slots are cleaned up", async () => {
  const { context } = loadWrapper({
    payload: null,
    bridgeState: { ready: false, error: null },
  });
  const pending = [];
  for (let i = 0; i < 8; i += 1) {
    const xhr = new context.XMLHttpRequest();
    xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
    xhr.send();
    pending.push(xhr);
  }
  const ninth = new context.XMLHttpRequest();
  ninth.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  ninth.send();
  assert.equal(ninth.status, 0);
  assert.equal(ninth.readyState, 1, "concurrency rejection must be asynchronous");
  await sleep(25);
  assert.equal(ninth.status, 404);
  assert.equal(ninth.readyState, 4);

  for (const xhr of pending) {
    xhr.abort();
  }
  const recovered = new context.XMLHttpRequest();
  recovered.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  recovered.send();
  assert.equal(recovered.readyState, 1);
  assert.equal(recovered.status, 0);
});

test("reopening an XHR clears stale request headers before the next send", () => {
  const payload = makePayloadWithFiles({ "bar.lua": "x" });
  const created = [];
  class RecordingNativeXHR extends MockNativeXHR {
    constructor() {
      super();
      created.push(this);
    }
  }
  const { context } = loadWrapper({ payload, nativeXhr: RecordingNativeXHR });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.setRequestHeader("X-Stale", "leak");
  xhr.open("GET", "https://lasthavens.com/other-endpoint");
  xhr.send();
  assert.equal(created.length, 1);
  assert.deepEqual(created[0]._requestHeaders, []);
  assert.equal(xhr.responseText, "mock-body");
});

test("reopening an XHR invalidates the previous in-flight wait", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "first", "second.lua": "second" });
  const { context } = loadWrapper({ payload });
  const xhr = new context.XMLHttpRequest();
  const events = collectEvents(xhr, context);
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/second.lua");
  xhr.send();
  await sleep(25);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "second");
  assert.equal(events.filter((entry) => entry === "load:4").length, 1);
  assert.equal(events.filter((entry) => entry === "loadstart:1").length, 2);
});

test("an aborted XHR can be reused for a fresh request", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "reused" });
  const { context } = loadWrapper({
    payload: null,
    bridgeState: { ready: false, error: null },
  });
  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  xhr.abort();
  assert.equal(xhr.readyState, 0);
  context.__LASTHAVEN_ADDON_BRIDGE__ = makeBridge(payload);
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  await sleep(25);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "reused");
});

function loadWrapperWithBridge(bridge) {
  const { context, XHR } = loadWrapper({});
  context.__LASTHAVEN_ADDON_BRIDGE__ = bridge;
  vm.runInContext(
    "this.__LASTHAVEN_ADDON_BRIDGE__ = __LASTHAVEN_ADDON_BRIDGE__",
    context,
  );
  return { context, XHR };
}

function makeCountingBridge(payload, { revision, cloneDeep = true } = {}) {
  const counts = { getBundle: 0, getState: 0, getRevision: 0 };
  const bridge = {
    getRevision: () => {
      counts.getRevision += 1;
      return revision === undefined ? payload.revision : revision;
    },
    getBundle: () => {
      counts.getBundle += 1;
      if (payload === null) return null;
      return cloneDeep ? JSON.parse(JSON.stringify(payload)) : payload;
    },
    getState: () => {
      counts.getState += 1;
      return { ready: revision !== null, error: null };
    },
  };
  return { bridge, counts };
}

test("repeated requests for one revision clone the payload only once", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "shared" });
  const { bridge, counts } = makeCountingBridge(payload);
  const { context } = loadWrapperWithBridge(bridge);
  for (let index = 0; index < 4; index += 1) {
    const xhr = new context.XMLHttpRequest();
    xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
    xhr.send();
    await sleep(25);
    assert.equal(xhr.status, 200);
    assert.equal(xhr.responseText, "shared");
  }
  assert.equal(counts.getBundle, 1);
});

test("a replaced bridge with a new revision re-clones and never returns stale data", async () => {
  const payloadA = makePayloadWithFiles({ "bar.lua": "content-a" });
  const a = makeCountingBridge(payloadA);
  const { context } = loadWrapperWithBridge(a.bridge);
  const first = new context.XMLHttpRequest();
  first.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  first.send();
  await sleep(25);
  assert.equal(first.responseText, "content-a");
  assert.equal(a.counts.getBundle, 1);

  const payloadB = makePayloadWithFiles({ "bar.lua": "content-b", "extra.lua": "extra" });
  payloadB.revision = "b".repeat(64);
  const b = makeCountingBridge(payloadB);
  context.__LASTHAVEN_ADDON_BRIDGE__ = b.bridge;
  vm.runInContext(
    "this.__LASTHAVEN_ADDON_BRIDGE__ = __LASTHAVEN_ADDON_BRIDGE__",
    context,
  );
  const second = new context.XMLHttpRequest();
  second.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  second.send();
  await sleep(25);
  assert.equal(second.responseText, "content-b");
  assert.equal(b.counts.getBundle, 1);

  const third = new context.XMLHttpRequest();
  third.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/extra.lua");
  third.send();
  await sleep(25);
  assert.equal(third.status, 200);
  assert.equal(third.responseText, "extra");
  assert.equal(b.counts.getBundle, 1);
});

test("the readiness poll resolves from the cached revision without cloning", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "polled" });
  let revision = null;
  const { bridge, counts } = makeCountingBridge(payload);
  const originalGetRevision = bridge.getRevision;
  bridge.getRevision = () => {
    counts.getRevision += 1;
    return revision;
  };
  bridge.getBundle = () => {
    counts.getBundle += 1;
    return revision === null ? null : JSON.parse(JSON.stringify(payload));
  };
  bridge.getState = () => ({ ready: revision !== null, error: null });
  const { context } = loadWrapperWithBridge(bridge);

  const xhr = new context.XMLHttpRequest();
  xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  xhr.send();
  await sleep(120);
  assert.equal(xhr.status, 0, "the request must still be waiting while the bridge is not ready");
  assert.equal(counts.getBundle, 0, "polls must not clone while no revision is published");

  revision = payload.revision;
  await sleep(150);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "polled");
  assert.equal(counts.getBundle, 1, "the poll must clone exactly once when the revision appears");
  void originalGetRevision;

  const second = new context.XMLHttpRequest();
  second.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  second.send();
  await sleep(25);
  assert.equal(second.responseText, "polled");
  assert.equal(counts.getBundle, 1, "cached revisions must not clone again");
});

test("a torn-down bridge that stops publishing its revision fails closed", async () => {
  const payload = makePayloadWithFiles({ "bar.lua": "gone" });
  let revision = payload.revision;
  let bundle = payload;
  let clones = 0;
  const bridge = {
    getRevision: () => revision,
    getBundle: () => {
      clones += 1;
      return bundle === null ? null : JSON.parse(JSON.stringify(bundle));
    },
    getState: () => ({ ready: bundle !== null, error: null }),
  };
  let fakeNow = 0;
  const { context } = loadWrapper({
    dateNow: () => {
      fakeNow += 25000;
      return fakeNow;
    },
  });
  context.__LASTHAVEN_ADDON_BRIDGE__ = bridge;
  vm.runInContext(
    "this.__LASTHAVEN_ADDON_BRIDGE__ = __LASTHAVEN_ADDON_BRIDGE__",
    context,
  );

  const first = new context.XMLHttpRequest();
  first.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  first.send();
  await sleep(25);
  assert.equal(first.responseText, "gone");
  assert.equal(clones, 1);

  revision = null;
  bundle = null;
  const second = new context.XMLHttpRequest();
  second.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  second.send();
  await sleep(60);
  assert.equal(second.status, 404, "a torn-down bridge must not serve stale cached data");
  assert.equal(clones, 1, "teardown must not clone");

  const replacement = makePayloadWithFiles({ "bar.lua": "rebuilt" });
  replacement.revision = "c".repeat(64);
  revision = replacement.revision;
  bundle = replacement;
  const third = new context.XMLHttpRequest();
  third.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/bar.lua");
  third.send();
  await sleep(25);
  assert.equal(third.status, 200);
  assert.equal(third.responseText, "rebuilt");
  assert.equal(clones, 2, "post-teardown delivery must force a fresh clone");
});

test("overlay asset stays within the deterministic packaging bounds", () => {
  assert.ok(overlayBytes.length <= 64 * 1024, "overlay must stay under 64 KiB");
  assert.equal(overlaySource.startsWith('"use strict";'), true);
});

test("large-bundle regression: repeated big-file requests decode and clone once", async () => {
  const big = Buffer.alloc(8 * 1024 * 1024, 7).toString("base64");
  const payload = makePayload([
    makeAddon("Huge", Object.assign(Object.create(null), { "Huge.toc": "", "big.bin": big })),
  ]);
  let decodeCalls = 0;
  const { bridge, counts } = makeCountingBridge(payload);
  const { context } = loadWrapper({
    atobImpl: (value) => {
      decodeCalls += 1;
      return atob(value);
    },
  });
  context.__LASTHAVEN_ADDON_BRIDGE__ = bridge;
  vm.runInContext(
    "this.__LASTHAVEN_ADDON_BRIDGE__ = __LASTHAVEN_ADDON_BRIDGE__",
    context,
  );
  for (let index = 0; index < 3; index += 1) {
    const xhr = new context.XMLHttpRequest();
    xhr.open("GET", "https://lasthavens.com/game/Interface/AddOns/Huge/big.bin");
    xhr.send();
    await sleep(80);
    assert.equal(xhr.status, 200);
    assert.equal(xhr.responseText.length, 8 * 1024 * 1024);
  }
  assert.equal(counts.getBundle, 1, "one clone for the whole revision");
  assert.equal(decodeCalls, 1, "one base64 decode for the repeated large file");
});
