import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import vm from "node:vm";

const overlayPath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/worker-overlay.js",
  import.meta.url,
);
const overlaySource = readFileSync(fileURLToPath(overlayPath), "utf8");
const xhrOverlaySource = readFileSync(
  fileURLToPath(
    new URL("../app/src/main/assets/extensions/addon-bridge/xhr-overlay.js", import.meta.url),
  ),
  "utf8",
);

const BASE_HREF = "https://lasthavens.com/play";
const GAME_HOST = "lasthavens.com";
const WORKER_URL = "https://lasthavens.com/game/emscripten.py";

class FakePort {
  constructor(label) {
    this.label = label;
    this.listeners = new Map();
    this.onmessage = null;
    this.posted = [];
    this.closed = false;
    this.started = false;
  }
  addEventListener(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(callback);
  }
  removeEventListener(type, callback) {
    const list = this.listeners.get(type);
    if (!list) return;
    const index = list.indexOf(callback);
    if (index !== -1) list.splice(index, 1);
  }
  postMessage(data) {
    this.posted.push(data);
  }
  start() {
    this.started = true;
  }
  close() {
    this.closed = true;
  }
  dispatch(data) {
    const event = { data: data };
    if (typeof this.onmessage === "function") this.onmessage(event);
    for (const callback of [...(this.listeners.get("message") || [])]) callback(event);
  }
}

class FakeMessageChannel {
  constructor() {
    this.port1 = new FakePort("port1");
    this.port2 = new FakePort("port2");
    FakeMessageChannel.instances.push(this);
  }
}
FakeMessageChannel.instances = [];

class FakeBlob {
  constructor(parts, options) {
    this.parts = parts;
    this.options = options;
  }
}

class FakeNativeWorker {
  constructor(url, options) {
    FakeNativeWorker.constructed.push(this);
    this.url = url;
    this.options = options;
    this.listeners = new Map();
    this.posted = [];
    this.terminated = false;
  }
  addEventListener(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(callback);
  }
  removeEventListener(type, callback) {
    const list = this.listeners.get(type);
    if (!list) return;
    const index = list.indexOf(callback);
    if (index !== -1) list.splice(index, 1);
  }
  postMessage(data, transfer) {
    this.posted.push({ data: data, transfer: transfer });
  }
  terminate() {
    this.terminated = true;
  }
  dispatch(data) {
    const event = { data: data };
    for (const callback of [...(this.listeners.get("message") || [])]) callback(event);
  }
  dispatchError() {
    for (const callback of [...(this.listeners.get("error") || [])]) callback(new Error("worker error"));
  }
}
FakeNativeWorker.constructed = [];

function base64Of(text) {
  return Buffer.from(text, "utf8").toString("base64");
}

function indexBytes(text) {
  return new TextEncoder().encode(text);
}

function maskBufferFor(records) {
  const buffer = new Uint32Array(records.length * 4);
  for (let i = 0; i < records.length; i += 1) {
    const record = records[i];
    buffer.set([record.start, record.end, record.value, record.folderIndex ?? 0], i * 4);
  }
  return buffer.buffer;
}

function createPageContext({ allowlist = [WORKER_URL], withXhrCore = true, opfsBytes = new Uint8Array(0) } = {}) {
  const state = {
    timers: [],
    timerId: 0,
    objectUrlsCreated: [],
    objectUrlsRevoked: [],
  };
  class TestURL extends URL {}
  TestURL.createObjectURL = (blob) => {
    state.objectUrlsCreated.push(blob);
    return "blob:https://lasthavens.com/fake-" + state.objectUrlsCreated.length;
  };
  TestURL.revokeObjectURL = (url) => {
    state.objectUrlsRevoked.push(url);
  };
  FakeMessageChannel.instances = [];
  FakeNativeWorker.constructed = [];
  let bridgePayload = { revision: "a".repeat(64), addons: [] };
  const bridge = {
    getBundle: () => bridgePayload,
    getState: () => ({ ready: true, error: null }),
  };
  const context = vm.createContext({
    URL: TestURL,
    TextDecoder,
    TextEncoder,
    Blob: FakeBlob,
    MessageChannel: FakeMessageChannel,
    atob,
    btoa,
    console,
    Worker: FakeNativeWorker,
    location: { href: BASE_HREF, hostname: GAME_HOST },
    setTimeout: (fn, ms) => {
      state.timerId += 1;
      state.timers.push({ id: state.timerId, fn: fn, ms: ms, cleared: false });
      return state.timerId;
    },
    clearTimeout: (id) => {
      for (const timer of state.timers) {
        if (timer.id === id) timer.cleared = true;
      }
    },
    Number,
    Math,
    Object,
    Array,
    String,
    JSON,
    RegExp,
    Error,
    Uint8Array,
    Uint32Array,
    ArrayBuffer,
    __bridge: bridge,
    navigator: {
      storage: {
        getDirectory: async () => ({
          getFileHandle: async (name) => {
            assert.equal(name, "blocks.idx");
            return { getFile: async () => ({
              size: opfsBytes.length,
              arrayBuffer: async () => opfsBytes.slice().buffer,
            }) };
          },
        }),
      },
    },
  });
  vm.runInContext("globalThis.__LASTHAVEN_ADDON_BRIDGE__ = __bridge;", context);
  if (withXhrCore) {
    vm.runInContext(xhrOverlaySource, context, { filename: "xhr-overlay.js" });
  }
  vm.runInContext(overlaySource, context, { filename: "worker-overlay.js" });
  const coreOut = context.__LHAddonWorkerOverlayCore;
  if (allowlist.length > 0) {
    assert.equal(coreOut.setWorkerUrlAllowlist(allowlist), true);
  }
  return {
    context,
    core: coreOut,
    state,
    constructed: FakeNativeWorker.constructed,
    channels: FakeMessageChannel.instances,
    setPayload: (payload) => {
      bridgePayload = payload;
    },
    getPayload: () => bridgePayload,
  };
}

const VALID_CONFIG = { baseHref: BASE_HREF, allowlist: [WORKER_URL] };

function createWorkerScopeContext({ importScriptsImpl, nativeXHR } = {}) {
  const scope = {
    imported: [],
    closed: false,
    posted: [],
    onmessage: null,
    listeners: new Map(),
    location: { href: "blob:https://lasthavens.com/worker-uuid" },
    addEventListener(type, callback) {
      if (!scope.listeners.has(type)) scope.listeners.set(type, []);
      scope.listeners.get(type).push(callback);
    },
    removeEventListener() {},
    dispatchEvent(event) {
      for (const callback of [...(scope.listeners.get(event.type) || [])]) callback(event);
      return true;
    },
    postMessage(data) {
      scope.posted.push(data);
    },
    close() {
      scope.closed = true;
    },
    importScripts: (...urls) => {
      if (importScriptsImpl) return importScriptsImpl(...urls);
      scope.imported.push(...urls);
    },
    receive(event) {
      if (scope.closed) return;
      if (event.type === "message" && typeof scope.onmessage === "function") scope.onmessage(event);
      for (const callback of [...(scope.listeners.get(event.type) || [])]) callback(event);
    },
  };
  if (nativeXHR) {
    scope.XMLHttpRequest = nativeXHR;
  }
  const context = vm.createContext({
    self: scope,
    URL,
    TextDecoder,
    TextEncoder,
    atob,
    btoa,
    setTimeout: (fn) => {
      fn();
      return 0;
    },
    clearTimeout: () => {},
    Number,
    Math,
    Object,
    Array,
    String,
    JSON,
    RegExp,
    Error,
    Uint8Array,
    Uint32Array,
    ArrayBuffer,
    decodeURIComponent,
    console,
  });
  return { scope, context };
}

function bootWorkerScope(core, { url = WORKER_URL, importScriptsImpl, nativeXHR } = {}) {
  const { scope, context } = createWorkerScopeContext({ importScriptsImpl, nativeXHR });
  const bootstrapSource = core.buildBootstrapSource(url);
  vm.runInContext(bootstrapSource, context, { filename: "bootstrap.js" });
  const channel = new FakeMessageChannel();
  function dispatchBoot(ports, mask = {}) {
    const bootData = Object.assign({ maskBuffer: new ArrayBuffer(0), indexSize: 0, enabledFolders: [], maskError: null }, mask);
    Object.defineProperty(bootData, "__lhBoot", { value: true, enumerable: true });
    scope.receive({
      type: "message",
      data: bootData,
      ports: ports === undefined ? [channel.port2] : ports,
    });
  }
  return { scope, context, workerPort: channel.port2, pagePort: channel.port1, dispatchBoot };
}

test("gate rejects wrong worker name", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(WORKER_URL, { name: "other" }, VALID_CONFIG);
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "worker-name");
});

test("gate rejects module workers", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(WORKER_URL, { name: "em-pthread", type: "module" }, VALID_CONFIG);
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "worker-type");
});

test("gate rejects missing options", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(WORKER_URL, undefined, VALID_CONFIG);
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "worker-name");
});

test("gate rejects non-string and empty urls", () => {
  const { core } = createPageContext();
  assert.equal(core.shouldInterceptWorker(123, { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("", { name: "em-pthread" }, VALID_CONFIG).reason, "url-type");
});

test("gate rejects blob and data urls", () => {
  const { core } = createPageContext();
  assert.equal(core.shouldInterceptWorker("blob:https://lasthavens.com/x", { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("data:text/javascript,", { name: "em-pthread" }, VALID_CONFIG).ok, false);
});

test("gate rejects non-https schemes", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker("http://lasthavens.com/game/worker.js", { name: "em-pthread" }, VALID_CONFIG);
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "url-scheme");
});

test("gate rejects cross-origin workers", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker("https://cdn.example.com/game/worker.js", { name: "em-pthread" }, VALID_CONFIG);
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "url-origin");
});

test("gate rejects urls with credentials, query or hash", () => {
  const { core } = createPageContext();
  assert.equal(core.shouldInterceptWorker("https://user:pw@lasthavens.com/game/worker.js", { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("https://lasthavens.com/game/worker.js?v=1", { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("https://lasthavens.com/game/worker.js#frag", { name: "em-pthread" }, VALID_CONFIG).ok, false);
});

test("gate rejects path traversal, backslashes and control characters", () => {
  const { core } = createPageContext();
  assert.equal(core.shouldInterceptWorker("https://lasthavens.com/game/../secret.js", { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("https://lasthavens.com/game/a\\b.js", { name: "em-pthread" }, VALID_CONFIG).ok, false);
  assert.equal(core.shouldInterceptWorker("https://lasthavens.com/game/a\u0000b.js", { name: "em-pthread" }, VALID_CONFIG).ok, false);
});

test("gate is inert when the allowlist is empty (fail closed)", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(WORKER_URL, { name: "em-pthread" }, {
    baseHref: BASE_HREF,
    allowlist: [],
  });
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "allowlist-empty");
});

test("runtime worker gate pins the first validated em-pthread URL", () => {
  const { core } = createPageContext({ allowlist: [] });
  const config = { baseHref: BASE_HREF };
  const first = core.shouldInterceptWorker(WORKER_URL, { name: "em-pthread" }, config);
  assert.equal(first.ok, true);
  assert.equal(first.reason, "learned");
  assert.equal(first.href, WORKER_URL);

  const repeated = core.shouldInterceptWorker(WORKER_URL, { name: "em-pthread" }, config);
  assert.equal(repeated.ok, true);
  const different = core.shouldInterceptWorker(
    "https://lasthavens.com/game/other-worker.js",
    { name: "em-pthread" },
    config,
  );
  assert.equal(different.ok, false);
  assert.equal(different.reason, "allowlist-miss");
});

test("runtime worker gate supports the alternate approved game origin only in its own page", () => {
  const { core } = createPageContext({ allowlist: [] });
  const altUrl = "https://alt.lasthavens.com/game/emscripten.py";
  const decision = core.shouldInterceptWorker(altUrl, { name: "em-pthread" }, {
    baseHref: "https://alt.lasthavens.com/play",
  });
  assert.equal(decision.ok, true);
  assert.equal(decision.href, altUrl);
  assert.doesNotThrow(() => core.buildBootstrapSource(altUrl));

  const crossOrigin = core.shouldInterceptWorker(altUrl, { name: "em-pthread" }, {
    baseHref: BASE_HREF,
  });
  assert.equal(crossOrigin.ok, false);
  assert.equal(crossOrigin.reason, "url-origin");
});

test("gate accepts the exact allowlisted same-origin classic worker", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(WORKER_URL, { name: "em-pthread" }, VALID_CONFIG);
  assert.equal(decision.ok, true);
  assert.equal(decision.href, WORKER_URL);
});

test("gate rejects allowlist miss even with the correct name", () => {
  const { core } = createPageContext();
  const decision = core.shouldInterceptWorker(
    "https://lasthavens.com/game/other-worker.js",
    { name: "em-pthread" },
    VALID_CONFIG,
  );
  assert.equal(decision.ok, false);
  assert.equal(decision.reason, "allowlist-miss");
});

test("manifest entry builder excludes game-provided folders and validates addon files", () => {
  const { core } = createPageContext();
  const payload = {
    revision: "a".repeat(64),
    addons: [
      { folder: "AlreadyBuiltIn", files: { "main.lua": base64Of("builtin") } },
      { folder: "CustomAddon", files: { "main.lua": base64Of("hello"), "CustomAddon.toc": base64Of("## Interface: 30300") } },
    ],
  };
  const manifest = "7\tInterface/AddOns/AlreadyBuiltIn/main.lua\n";
  const plan = core.buildManifestEntries(payload, manifest);
  assert.equal(plan.ok, true);
  assert.deepEqual(Array.from(plan.enabledFolders), ["CustomAddon"]);
  assert.deepEqual(
    Array.from(plan.entries, (entry) => ({
      path: String(entry.path),
      sizeBytes: Number(entry.sizeBytes),
    })),
    [
      { path: "Interface/AddOns/CustomAddon/main.lua", sizeBytes: 5 },
      { path: "Interface/AddOns/CustomAddon/CustomAddon.toc", sizeBytes: 19 },
    ],
  );
});

test("manifest entry builder rejects unsafe paths, malformed base64 and duplicate folders", () => {
  const { core } = createPageContext();
  assert.equal(
    core.buildManifestEntries({ addons: [{ folder: "Unsafe", files: { "../escape.lua": base64Of("x") } }] }, "").ok,
    false,
  );
  assert.equal(
    core.buildManifestEntries({ addons: [{ folder: "BadData", files: { "main.lua": "not-base64!" } }] }, "").ok,
    false,
  );
  assert.equal(
    core.buildManifestEntries({
      addons: [
        { folder: "Same", files: { "main.lua": base64Of("one") } },
        { folder: "same", files: { "next.lua": base64Of("two") } },
      ],
    }, "").ok,
    false,
  );
});

test("worker manifest validator rejects entries outside enabled folders and duplicate paths", () => {
  const { core } = createPageContext();
  assert.equal(core.validateManifestEntries([
    { path: "Interface/AddOns/Other/main.lua", sizeBytes: 1 },
  ], ["Enabled"]).ok, false);
  assert.equal(core.validateManifestEntries([
    { path: "Interface/AddOns/Enabled/main.lua", sizeBytes: 1 },
    { path: "Interface/AddOns/Enabled/main.lua", sizeBytes: 1 },
  ], ["Enabled"]).ok, false);
  const valid = core.validateManifestEntries([
    { path: "Interface/AddOns/Enabled/main.lua", sizeBytes: 1 },
  ], ["Enabled"]);
  assert.equal(valid.ok, true);
  assert.equal(Object.isFrozen(valid.entries), true);
});

test("manifest appender adds size/path records without duplicating an existing addon folder", () => {
  const { core } = createPageContext();
  const entries = [
    { path: "Interface/AddOns/Enabled/main.lua", sizeBytes: 5 },
    { path: "Interface/AddOns/AlreadyBuiltIn/main.lua", sizeBytes: 7 },
  ];
  const patched = core.appendAddonManifest(
    "1\tInterface/FrameXML/FrameXML.lua\n7\tInterface/AddOns/AlreadyBuiltIn/main.lua\n",
    entries,
  );
  assert.equal(
    patched,
    "1\tInterface/FrameXML/FrameXML.lua\n7\tInterface/AddOns/AlreadyBuiltIn/main.lua\n5\tInterface/AddOns/Enabled/main.lua\n",
  );
  assert.equal(
    core.appendAddonManifest(
      "7\tInterface/AddOns/AlreadyBuiltIn/main.lua\n",
      entries,
    ),
    "7\tInterface/AddOns/AlreadyBuiltIn/main.lua\n5\tInterface/AddOns/Enabled/main.lua\n",
  );
});

test("page Worker wrapper passes non-target workers through untouched", () => {
  const { context, constructed } = createPageContext();
  vm.runInContext(`
    new Worker("https://lasthavens.com/game/other.js", { name: "em-pthread" });
    new Worker(${JSON.stringify(WORKER_URL)}, { name: "not-em-pthread" });
    new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread", type: "module" });
  `, context);
  assert.equal(constructed.length, 3);
  for (const worker of constructed) {
    assert.ok(!worker.url.startsWith("blob:"));
  }
});

test("page Worker wrapper pins the first validated worker when no static allowlist is supplied", () => {
  const { context, constructed } = createPageContext({ allowlist: [] });
  vm.runInContext(`
    new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });
    new Worker("https://lasthavens.com/game/other-worker.js", { name: "em-pthread" });
  `, context);
  assert.equal(constructed.length, 2);
  assert.ok(constructed[0].url.startsWith("blob:"));
  assert.ok(!constructed[1].url.startsWith("blob:"));
});

test("intercepted worker bootstraps through a Blob with the exact original url", async () => {
  const { context, constructed, state } = createPageContext();
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  assert.equal(constructed.length, 1);
  const worker = constructed[0];
  for (let attempt = 0; attempt < 20 && worker.posted.length === 0; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  assert.equal(worker.posted.length, 1, "worker boot message should be posted");
  assert.ok(worker.url.startsWith("blob:"));
  assert.equal(worker.options.name, "em-pthread");
  const blob = state.objectUrlsCreated[0];
  assert.ok(blob instanceof FakeBlob);
  const source = blob.parts.join("");
  assert.ok(source.includes("importScripts(originalUrl)"));
  assert.ok(source.includes("installAddonManifestFetch"));
  assert.ok(source.includes("validateManifestEntries"));
  assert.ok(source.includes("MAX_MANIFEST_TEXT_BYTES"));
  assert.ok(source.includes(JSON.stringify(WORKER_URL)));
  assert.ok(!source.includes("eval("));
  const bootMessage = worker.posted[0];
  assert.equal(bootMessage.data.__lhBoot, true);
  assert.equal(bootMessage.transfer.length, 2);
  assert.ok(bootMessage.transfer[0] instanceof FakePort);
  assert.ok(bootMessage.transfer[1] instanceof ArrayBuffer);
});

test("boot timeout terminates the worker, revokes the url and frees the registry slot", () => {
  const { context, constructed, state } = createPageContext();
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  const worker = constructed[0];
  const timer = state.timers.find((entry) => entry.ms === 20000);
  assert.ok(timer, "boot timeout scheduled");
  assert.equal(state.objectUrlsRevoked.length, 0);
  timer.fn();
  assert.equal(worker.terminated, true);
  assert.equal(state.objectUrlsRevoked.length, 1);
  assert.equal(state.objectUrlsRevoked[0], worker.url);
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  assert.equal(constructed.length, 2);
});

test("ready signal clears the boot timer and revokes the object url", () => {
  const { context, constructed, state, channels } = createPageContext();
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  const worker = constructed[0];
  const pagePort = channels[0].port1;
  pagePort.dispatch({ __lhReady: true });
  const timer = state.timers.find((entry) => entry.ms === 20000);
  assert.equal(timer.cleared, true);
  assert.equal(state.objectUrlsRevoked.length, 1);
  assert.equal(worker.terminated, false);
});

test("failure signal terminates the worker and cleans up", () => {
  const { context, constructed, state, channels } = createPageContext();
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  const worker = constructed[0];
  channels[0].port1.dispatch({ type: "lhWorkerFailed", stage: "queue-overflow" });
  assert.equal(worker.terminated, true);
  assert.equal(state.objectUrlsRevoked.length, 1);
  const timer = state.timers.find((entry) => entry.ms === 20000);
  assert.equal(timer.cleared, true);
});

test("worker error event terminates and cleans up", () => {
  const { context, constructed, state } = createPageContext();
  vm.runInContext(`new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  const worker = constructed[0];
  worker.dispatchError();
  assert.equal(worker.terminated, true);
  assert.equal(state.objectUrlsRevoked.length, 1);
});

test("terminate override cleans up and still terminates natively", () => {
  const { context, constructed, state } = createPageContext();
  vm.runInContext(`globalThis.__w = new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });`, context);
  const worker = constructed[0];
  vm.runInContext("__w.terminate();", context);
  assert.equal(worker.terminated, true);
  assert.equal(state.objectUrlsRevoked.length, 1);
});

test("worker count cap deterministically rejects the ninth intercepted worker", () => {
  const { context } = createPageContext();
  assert.throws(() => {
    vm.runInContext(`
      for (let i = 0; i < 9; i += 1) {
        new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });
      }
    `, context);
  }, /lh-worker-cap-reached/);
});

test("native workers do not consume intercepted worker slots", () => {
  const { context, constructed } = createPageContext();
  vm.runInContext(`
    for (let i = 0; i < 10; i += 1) {
      new Worker("https://lasthavens.com/game/worker" + i + ".js", { name: "other" });
    }
    new Worker(${JSON.stringify(WORKER_URL)}, { name: "em-pthread" });
  `, context);
  assert.equal(constructed.length, 11);
});

test("page serves addon GET file requests from the bridge payload", () => {
  const { core, getPayload, setPayload } = createPageContext();
  setPayload({ revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "main.lua": base64Of("hello addon") } }] });
  const response = core.serveWorkerFileRequest(
    getPayload(),
    { requestId: "r1", method: "GET", path: "/game/Interface/AddOns/Foo/main.lua" },
    null,
  );
  assert.equal(response.status, 200);
  assert.match(response.headers, /Content-Type: text\/plain/);
  assert.match(response.headers, /Content-Length: 11/);
  assert.equal(Buffer.from(response.base64, "base64").toString("utf8"), "hello addon");
});

test("page serves HEAD without body", () => {
  const { core, getPayload, setPayload } = createPageContext();
  setPayload({ revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "main.lua": base64Of("hello") } }] });
  const response = core.serveWorkerFileRequest(
    getPayload(),
    { requestId: "r1", method: "HEAD", path: "/game/Interface/AddOns/Foo/main.lua" },
    null,
  );
  assert.equal(response.status, 200);
  assert.equal(response.base64, null);
  assert.match(response.headers, /Content-Length: 5/);
});

test("page serves block slice requests with content-range", () => {
  const { context, core, getPayload, setPayload } = createPageContext();
  const xhrCore = vm.runInContext("globalThis.__LHAddonXhrOverlayCore", context);
  setPayload({ revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "data.bin": base64Of("0123456789ABCDEF") } }] });
  const response = core.serveWorkerFileRequest(
    getPayload(),
    { requestId: "r1", method: "GET", path: "/blocks/Interface/AddOns/Foo/data.bin-1024-0-8k.bin" },
    xhrCore,
  );
  assert.equal(response.status, 206);
  assert.match(response.headers, /content-range: bytes 0-15\/16/);
});

test("page rejects unsupported methods on addon routes with 405", () => {
  const { core } = createPageContext();
  const response = core.serveWorkerFileRequest(
    { revision: "a".repeat(64), addons: [] },
    { requestId: "r1", method: "POST", path: "/game/Interface/AddOns/Foo/main.lua" },
    null,
  );
  assert.equal(response.status, 405);
});

test("page fails closed on non-addon, traversal, blizzard and missing paths", () => {
  const { core, setPayload } = createPageContext();
  const payload = { revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "main.lua": base64Of("x") } }] };
  setPayload(payload);
  const cases = [
    "/game/scripts/main.lua",
    "/game/Interface/AddOns/../Foo/main.lua",
    "/game/Interface/AddOns/blizzard_ui/main.lua",
    "/game/Interface/AddOns/Bar/main.lua",
    "/game/Interface/AddOns/Foo/missing.lua",
    "/elsewhere/Interface/AddOns/Foo/main.lua",
  ];
  for (const path of cases) {
    const response = core.serveWorkerFileRequest(
      payload,
      { requestId: "r1", method: "GET", path: path },
      null,
    );
    assert.equal(response.status, 404, "expected 404 for " + path);
  }
});

test("page fails closed on oversized or invalid base64 payloads", () => {
  const { core } = createPageContext();
  const oversized = { revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "big.bin": "A".repeat(12 * 1024 * 1024) } }] };
  let response = core.serveWorkerFileRequest(
    oversized,
    { requestId: "r1", method: "GET", path: "/game/Interface/AddOns/Foo/big.bin" },
    null,
  );
  assert.equal(response.status, 404);
  const invalid = { revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "bad.bin": "not+base64!!" } }] };
  response = core.serveWorkerFileRequest(
    invalid,
    { requestId: "r1", method: "GET", path: "/game/Interface/AddOns/Foo/bad.bin" },
    null,
  );
  assert.equal(response.status, 404);
});

test("page 416s block requests beyond the file size", () => {
  const { context, core } = createPageContext();
  const xhrCore = vm.runInContext("globalThis.__LHAddonXhrOverlayCore", context);
  const payload = { revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "data.bin": base64Of("tiny") } }] };
  const response = core.serveWorkerFileRequest(
    payload,
    { requestId: "r1", method: "GET", path: "/blocks/Interface/AddOns/Foo/data.bin-1024-999-8k.bin" },
    xhrCore,
  );
  assert.equal(response.status, 416);
});

test("page reuses the xhr overlay core resolveRoute when available", () => {
  const { context, core, getPayload, setPayload } = createPageContext();
  setPayload({ revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "main.toc": base64Of("toc") } }] });
  const xhrCore = vm.runInContext("globalThis.__LHAddonXhrOverlayCore", context);
  assert.ok(xhrCore, "xhr core exported");
  const response = core.serveWorkerFileRequest(
    getPayload(),
    { requestId: "r1", method: "GET", path: "/game/Interface/AddOns/Foo/main.toc" },
    xhrCore,
  );
  assert.equal(response.status, 200);
  assert.equal(Buffer.from(response.base64, "base64").toString("utf8"), "toc");
});

test("bootstrap installs the forwarder, imports the original url, signals ready", () => {
  const { core } = createPageContext();
  const { scope, context, workerPort, dispatchBoot } = bootWorkerScope(core, {
    importScriptsImpl: (url) => {
      scope.imported.push(url);
      scope.onmessage = (event) => {
        scope.received = scope.received || [];
        scope.received.push(event.data);
      };
    },
    nativeXHR: class NativeWorkerXHR {
      open() {}
      send() {}
    },
  });
  dispatchBoot();
  assert.equal(scope.imported.length, 1);
  assert.equal(scope.imported[0], WORKER_URL);
  const ready = workerPort.posted.find((data) => data && data.__lhReady === true);
  assert.ok(ready, "ready signal posted");
  assert.equal(vm.runInContext("typeof self.XMLHttpRequest", context), "function");
  assert.equal(vm.runInContext("self.XMLHttpRequest.name", context), "OverlayXMLHttpRequest");
});

test("bootstrap queues pre-boot messages and delivers them after boot", () => {
  const { core } = createPageContext();
  const { scope, context, dispatchBoot } = bootWorkerScope(core, {
    importScriptsImpl: (url) => {
      scope.imported.push(url);
      scope.onmessage = (event) => {
        scope.received = scope.received || [];
        scope.received.push(event.data);
      };
    },
  });
  for (let i = 0; i < 5; i += 1) {
    scope.receive({ type: "message", data: "pre-" + i, ports: [] });
  }
  dispatchBoot();
  assert.deepEqual(scope.received, ["pre-0", "pre-1", "pre-2", "pre-3", "pre-4"]);
  scope.receive({ type: "message", data: "late-game-message", ports: [] });
  assert.deepEqual(scope.received, ["pre-0", "pre-1", "pre-2", "pre-3", "pre-4", "late-game-message"]);
});

test("bootstrap fails closed on pre-boot queue overflow", () => {
  const { core } = createPageContext();
  const { scope, workerPort, dispatchBoot } = bootWorkerScope(core);
  for (let i = 0; i < 65; i += 1) {
    scope.receive({ type: "message", data: "msg-" + i, ports: [] });
  }
  assert.equal(scope.closed, true);
  dispatchBoot();
  assert.equal(workerPort.posted.find((data) => data && data.__lhReady === true), undefined);
  const failure = scope.posted.find((data) => data && data.type === "lhWorkerFailed");
  assert.ok(failure);
  assert.equal(failure.stage, "queue-overflow");
});

test("bootstrap fails closed when importScripts throws", () => {
  const { core } = createPageContext();
  const { scope, workerPort, dispatchBoot } = bootWorkerScope(core, {
    importScriptsImpl: () => {
      throw new Error("network blocked");
    },
  });
  dispatchBoot();
  assert.equal(scope.closed, true);
  const failure = workerPort.posted.find((data) => data && data.type === "lhWorkerFailed");
  assert.ok(failure);
  assert.equal(failure.stage, "import-scripts");
});

test("bootstrap fails closed when the boot message has no port", () => {
  const { core } = createPageContext();
  const { scope, dispatchBoot } = bootWorkerScope(core);
  dispatchBoot([]);
  assert.equal(scope.closed, true);
});

test("worker XHR forwarder intercepts addon routes and serves page responses", () => {
  const { core } = createPageContext();
  const { context, workerPort, dispatchBoot } = bootWorkerScope(core, {
    nativeXHR: class NativeWorkerXHR {
      open() {}
      send() {}
    },
  });
  dispatchBoot();
  const xhr = vm.runInContext(`
    (function () {
      var request = new self.XMLHttpRequest();
      request.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/main.lua", true);
      request.send();
      return request;
    })()
  `, context);
  const requestPosted = workerPort.posted.find((data) => data && data.type === "lhAddonRequest");
  assert.ok(requestPosted, "request forwarded to page");
  assert.equal(requestPosted.path, "game/Interface/AddOns/Foo/main.lua");
  assert.equal(requestPosted.method, "GET");
  workerPort.dispatch({
    type: "lhAddonResponse",
    requestId: requestPosted.requestId,
    status: 200,
    statusText: "OK",
    headers: "Content-Type: text/plain\r\nContent-Length: 5",
    base64: base64Of("hello"),
  });
  assert.equal(xhr.readyState, 4);
  assert.equal(xhr.status, 200);
  assert.equal(xhr.responseText, "hello");
  assert.equal(xhr.getResponseHeader("Content-Type"), "text/plain");
});

test("worker XHR forwarder passes non-addon routes through to the native worker XHR", () => {
  const { core } = createPageContext();
  const { context, dispatchBoot } = bootWorkerScope(core, {
    nativeXHR: class NativeWorkerXHR {
      open(method, url) {
        this.opened = { method, url };
      }
      send() {
        this.sent = true;
      }
    },
  });
  dispatchBoot();
  const xhr = vm.runInContext(`
    (function () {
      var request = new self.XMLHttpRequest();
      request.open("GET", "https://lasthavens.com/game/data.txt", true);
      request.send();
      return request;
    })()
  `, context);
  assert.ok(xhr.__lhState, "overlay facade used");
  assert.equal(xhr.__lhState.intercepted, false);
  assert.ok(xhr.__lhState.native, "native worker XHR used");
  assert.equal(xhr.__lhState.native.sent, true);
});

test("worker XHR forwarder fail-closes sync requests on addon routes", () => {
  const { core } = createPageContext();
  const { context, workerPort, dispatchBoot } = bootWorkerScope(core, {
    nativeXHR: class NativeWorkerXHR {
      open() {}
      send() {}
    },
  });
  dispatchBoot();
  const xhr = vm.runInContext(`
    (function () {
      var request = new self.XMLHttpRequest();
      request.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/main.lua", false);
      request.send();
      return request;
    })()
  `, context);
  assert.equal(xhr.status, 404);
  assert.equal(workerPort.posted.filter((data) => data && data.type === "lhAddonRequest").length, 0);
});

test("worker XHR forwarder fail-closes unsupported methods on addon routes", () => {
  const { core } = createPageContext();
  const { context, workerPort, dispatchBoot } = bootWorkerScope(core, {
    nativeXHR: class NativeWorkerXHR {
      open() {}
      send() {}
    },
  });
  dispatchBoot();
  const xhr = vm.runInContext(`
    (function () {
      var request = new self.XMLHttpRequest();
      request.open("POST", "https://lasthavens.com/game/Interface/AddOns/Foo/main.lua", true);
      request.send("{}");
      return request;
    })()
  `, context);
  assert.equal(xhr.status, 405);
  assert.equal(workerPort.posted.filter((data) => data && data.type === "lhAddonRequest").length, 0);
});

test("worker XHR forwarder bounds concurrent inflight requests", () => {
  const { core } = createPageContext();
  const { context, workerPort, dispatchBoot } = bootWorkerScope(core, {
    nativeXHR: class NativeWorkerXHR {
      open() {}
      send() {}
    },
  });
  dispatchBoot();
  const statuses = vm.runInContext(`
    (function () {
      var statuses = [];
      for (var i = 0; i < 5; i += 1) {
        var request = new self.XMLHttpRequest();
        request.open("GET", "https://lasthavens.com/game/Interface/AddOns/Foo/main.lua", true);
        request.send();
        statuses.push(request.status);
      }
      return statuses;
    })()
  `, context);
  assert.equal(statuses.filter((status) => status === 0).length, 4);
  assert.equal(statuses[4], 503);
  assert.equal(workerPort.posted.filter((data) => data && data.type === "lhAddonRequest").length, 4);
});

test("bootstrap wraps the loader's global wasmImports assignment and masks only guarded reads", () => {
  const { core } = createPageContext();
  const { scope, context, dispatchBoot } = bootWorkerScope(core, {
    importScriptsImpl: (url) => {
      scope.imported.push(url);
      scope.assignCalls = 0;
      scope.reads = [];
      scope.assignWasmImports = function () {
        scope.assignCalls += 1;
        scope.wasmImports = {
          WebVfsStoreIo: function (file, write, offset, count) {
            scope.reads.push({ file: file, write: write, offset: offset, count: count });
          },
          HEAPU8: scope.heap || (scope.heap = new Uint8Array(16)),
        };
      };
    },
  });
  dispatchBoot(undefined, {
    indexSize: 16,
    enabledFolders: ["Foo"],
    maskBuffer: maskBufferFor([
      { start: 2, end: 4, value: 48 },
      { start: 8, end: 9, value: 35 },
    ]),
  });
  vm.runInContext(`
    (function () {
      self.assignWasmImports();
      var imports = self.wasmImports;
      imports.WebVfsStoreIo(1, false, 0, 12);
      imports.WebVfsStoreIo(1, true, 0, 12);
      imports.WebVfsStoreIo(2, false, 0, 12);
    })()
  `, context);
  assert.equal(vm.runInContext("self.assignCalls", context), 1);
  assert.equal(vm.runInContext("self.reads.length", context), 3);
  const heap = vm.runInContext("Array.from(self.heap)", context);
  assert.equal(heap[2], 48);
  assert.equal(heap[3], 48);
  assert.equal(heap[8], 35);
  assert.equal(heap[0], 0);
  assert.equal(heap[4], 0);
  assert.equal(vm.runInContext("self.wasmImports.WebVfsStoreIo.__lhMasked", context), true);
});

test("wrapAssignWasmImports rejects non-function or already-wrapped targets", () => {
  const { core } = createPageContext();
  assert.equal(core.wrapAssignWasmImports(null, null), false);
  assert.equal(core.wrapAssignWasmImports({}, null), false);
  const scope = { assignWasmImports: function () {} };
  assert.equal(core.wrapAssignWasmImports(scope, () => []), true);
  assert.equal(scope.assignWasmImports.__lhWrapped, true);
  assert.equal(core.wrapAssignWasmImports(scope, () => []), false);
});

test("maskWebVfsStoreIo guards types, ranges and heap bounds", () => {
  const { core } = createPageContext();
  assert.equal(core.maskWebVfsStoreIo(null, []), false);
  assert.equal(core.maskWebVfsStoreIo({}, []), false);
  assert.equal(core.maskWebVfsStoreIo({ WebVfsStoreIo: 42 }, []), false);
  const heap = new Uint8Array(16);
  const imports = { WebVfsStoreIo: function () {}, HEAPU8: heap };
  const records = new Uint32Array([2, 6, 48, 0]);
  assert.equal(core.maskWebVfsStoreIo(imports, records), true);
  assert.equal(imports.WebVfsStoreIo.__lhMasked, true);
  imports.WebVfsStoreIo(1, false, 0, 8);
  assert.equal(heap[2], 48);
  assert.equal(heap[3], 48);
  assert.equal(heap[4], 48);
  assert.equal(heap[5], 48);
  assert.equal(heap[0], 0);
  assert.equal(heap[6], 0);
  assert.equal(core.maskWebVfsStoreIo(imports, records), false);
});

test("maskWebVfsStoreIo skips writes, other files and out-of-bounds reads", () => {
  const { core } = createPageContext();
  const heap = new Uint8Array(16);
  const imports = { WebVfsStoreIo: function () {}, HEAPU8: heap };
  const records = new Uint32Array([0, 16, 48, 0]);
  core.maskWebVfsStoreIo(imports, records);
  imports.WebVfsStoreIo(1, true, 0, 16);
  assert.ok(heap.every((byte) => byte === 0));
  imports.WebVfsStoreIo(2, false, 0, 16);
  assert.ok(heap.every((byte) => byte === 0));
  imports.WebVfsStoreIo(1, false, 8, 999);
  assert.ok(heap.every((byte) => byte === 0));
  imports.WebVfsStoreIo(1, false, -1, 4);
  assert.ok(heap.every((byte) => byte === 0));
  imports.WebVfsStoreIo(1, false, 1.5, 4);
  assert.ok(heap.every((byte) => byte === 0));
});

test("maskReadSpan preserves byte positions and only fills intersections", () => {
  const { core } = createPageContext();
  const heap = new Uint8Array(20);
  for (let i = 0; i < heap.length; i += 1) heap[i] = 0xAA;
  const records = new Uint32Array([2, 6, 48, 0, 10, 18, 35, 0]);
  const written = core.maskReadSpan(heap, 0, 20, records);
  assert.equal(written, 12);
  for (let i = 0; i < heap.length; i += 1) {
    if (i >= 2 && i < 6) {
      assert.equal(heap[i], 48, "position " + i);
    } else if (i >= 10 && i < 18) {
      assert.equal(heap[i], 35, "position " + i);
    } else {
      assert.equal(heap[i], 0xAA, "position " + i);
    }
  }
});

test("maskReadSpan rejects invalid heaps, offsets and counts", () => {
  const { core } = createPageContext();
  const heap = new Uint8Array(8);
  const records = new Uint32Array([0, 4, 48, 0]);
  assert.equal(core.maskReadSpan(null, 0, 4, records), -1);
  assert.equal(core.maskReadSpan("nope", 0, 4, records), -1);
  assert.equal(core.maskReadSpan(heap, -1, 4, records), -1);
  assert.equal(core.maskReadSpan(heap, 0, -1, records), -1);
  assert.equal(core.maskReadSpan(heap, 0, 1.5, records), -1);
  assert.equal(core.maskReadSpan(heap, 0, 9, records), -1);
  assert.equal(core.maskReadSpan(heap, 0, 4, null), 0);
});

test("maskReadSpan fills overlapping masked records", () => {
  const { core } = createPageContext();
  const heap = new Uint8Array(10);
  const records = new Uint32Array([0, 4, 48, 0, 4, 8, 35, 0]);
  const written = core.maskReadSpan(heap, 2, 4, records);
  assert.equal(written, 4);
  assert.deepEqual(Array.from(heap.subarray(0, 8)), [0, 0, 48, 48, 35, 35, 0, 0]);
});


test("blocks.idx parser masks column two digits and first byte of enabled addon path", () => {
  const { core } = createPageContext();
  const row = "fïle\t12345\tkind\tmeta\tInterface/AddOns/Foo/main.lua\r\n";
  const other = "file\t9\tkind\tmeta\tInterface/AddOns/Other/main.lua\n";
  const bytes = indexBytes(row + other);
  const snapshot = Array.from(bytes);
  const result = core.parseBlocksIndex(bytes, ["Foo"]);
  assert.equal(result.ok, true);
  assert.equal(result.records.length, 2);
  const digitStart = new TextEncoder().encode("fïle\t").length;
  const pathStart = new TextEncoder().encode("fïle\t12345\tkind\tmeta\t").length;
  assert.deepEqual(Array.from(result.records, ({ start, end, value }) => ({ start, end, value })), [
    { start: digitStart, end: digitStart + 5, value: 48 },
    { start: pathStart, end: pathStart + 1, value: 35 },
  ]);
  assert.equal(result.indexSize, bytes.length);
  assert.deepEqual(Array.from(bytes), snapshot, "the persistent index input is never mutated");
});

test("blocks.idx parser computes UTF-8 offsets and handles CRLF", () => {
  const { core } = createPageContext();
  const row = "ïle\t7\tkind\tmeta\tInterface/AddOns/Foo/é.lua\r\n";
  const bytes = indexBytes(row);
  const result = core.parseBlocksIndex(bytes, ["Foo"]);
  assert.equal(result.ok, true);
  assert.equal(result.records[0].start, new TextEncoder().encode("ïle\t").length);
  assert.equal(result.records[1].start, new TextEncoder().encode("ïle\t7\tkind\tmeta\t").length);
});

test("blocks.idx parser ignores non-enabled addon folders and non-addon rows", () => {
  const { core } = createPageContext();
  const bytes = indexBytes(
    "file\t12\tkind\tmeta\tInterface/AddOns/Other/main.lua\n" +
    "file\t34\tkind\tmeta\tInterface/Other/Foo/main.lua\n",
  );
  const result = core.parseBlocksIndex(bytes, ["Foo"]);
  assert.equal(result.ok, true);
  assert.equal(result.records.length, 0);
});

test("blocks.idx parser rejects malformed rows and invalid numeric mask fields", () => {
  const { core } = createPageContext();
  assert.equal(core.parseBlocksIndex(indexBytes("a\t1\t2\t3\n"), ["Foo"]).stage, "record-columns");
  assert.equal(core.parseBlocksIndex(indexBytes("a\t-1\tc\td\tInterface/AddOns/Foo/a\n"), ["Foo"]).stage, "record-numbers");
  assert.equal(core.parseBlocksIndex(indexBytes("a\t1.5\tc\td\tInterface/AddOns/Foo/a\n"), ["Foo"]).stage, "record-numbers");
  assert.equal(core.parseBlocksIndex(indexBytes("a\t1\tc\td\tInterface/AddOns/Foo/../a\n"), ["Foo"]).stage, "record-path");
});

test("blocks.idx parser enforces file, line, field, record and UTF-8 ceilings", () => {
  const { core } = createPageContext();
  assert.equal(core.parseBlocksIndex(new Uint8Array(3 * 1024 * 1024), ["Foo"]).stage, "file-size");
  const manyLines = Array.from({ length: 70000 }, () => "a\t1\tb\tc\td").join("\n");
  assert.equal(core.parseBlocksIndex(indexBytes(manyLines), ["Foo"]).stage, "line-count");
  const longLine = "a".repeat(5000) + "\t1\tb\tc\td";
  assert.equal(core.parseBlocksIndex(indexBytes(longLine), ["Foo"]).stage, "line-length");
  const manyRecords = Array.from({ length: 40000 }, () => "a\t1\tb\tc\td").join("\n");
  assert.equal(core.parseBlocksIndex(indexBytes(manyRecords), ["Foo"]).stage, "record-count");
  assert.equal(core.parseBlocksIndex(new Uint8Array([0x49, 0xff, 0xfe, 0x0a]), ["Foo"]).stage, "utf-8");
});

test("blocks.idx parser rejects invalid enabled folder lists and bad limits", () => {
  const { core } = createPageContext();
  assert.equal(core.parseBlocksIndex(indexBytes("a\t1\tb\tc\td\n"), ["blizzard_ui"]).stage, "folders");
  assert.equal(core.parseBlocksIndex(indexBytes("a\t1\tb\tc\td\n"), ["Foo"], { maxLines: "-5" }).stage, "limits");
  assert.equal(core.parseBlocksIndex("text", ["Foo"]).stage, "input-type");
});

test("serialized worker bootstrap can parse index rows and validate paths", () => {
  const { core } = createPageContext();
  const { scope, context } = createWorkerScopeContext();
  const source = core.buildBootstrapSource(WORKER_URL).replace(
    /\}\)\(\);\s*$/,
    "self.__maskTest = { parseBlocksIndex: parseBlocksIndex, isSafeAddonPath: isSafeAddonPath };\n})();",
  );
  vm.runInContext(source, context, { filename: "bootstrap-mask-test.js" });
  const result = vm.runInContext(
    "self.__maskTest.parseBlocksIndex(new TextEncoder().encode('f\\t12\\tk\\tm\\tInterface/AddOns/Foo/a\\n'), ['Foo'])",
    context,
  );
  assert.equal(result.ok, true);
  assert.equal(result.records.length, 2);
  assert.equal(vm.runInContext("self.__maskTest.isSafeAddonPath('Interface/AddOns/Foo/a')", context), true);
  assert.equal(scope.closed, false);
});

test("OPFS blocks.idx is read bounded and its spans are sent in the worker boot message", async () => {
  const bytes = indexBytes("file\t123\tkind\tmeta\tInterface/AddOns/Foo/a.lua\n");
  const snapshot = Array.from(bytes);
  const page = createPageContext({ opfsBytes: bytes });
  page.setPayload({ revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "a.lua": base64Of("x") } }] });
  vm.runInContext('new Worker("' + WORKER_URL + '", { name: "em-pthread" })', page.context);
  for (let attempt = 0; attempt < 20 && page.constructed[0].posted.length === 0; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  assert.equal(page.constructed[0].posted.length, 1, "worker boot message should be posted");
  const boot = page.constructed[0].posted[0].data;
  assert.equal(boot.__lhBoot, true);
  assert.equal(boot.enabledFolders.length, 1);
  assert.equal(boot.enabledFolders[0], "Foo");
  assert.equal(boot.indexSize, bytes.length);
  assert.equal(boot.maskError, null);
  const spans = Array.from(new Uint32Array(boot.maskBuffer));
  const digitStart = new TextEncoder().encode("file\t").length;
  const pathStart = new TextEncoder().encode("file\t123\tkind\tmeta\t").length;
  assert.deepEqual(spans, [digitStart, digitStart + 3, 48, 0, pathStart, pathStart + 1, 35, 0]);
  assert.deepEqual(Array.from(bytes), snapshot);
});

test("mask-record receiver validates compact span shape, bounds and enabled-folder indices", () => {
  const { core } = createPageContext();
  const valid = maskBufferFor([{ start: 2, end: 4, value: 48, folderIndex: 0 }]);
  assert.equal(core.validateMaskRecords(valid, 8, ["Foo"]).ok, true);
  for (const invalid of [
    maskBufferFor([{ start: -1, end: 4, value: 48, folderIndex: 0 }]),
    maskBufferFor([{ start: 2, end: 9, value: 48, folderIndex: 0 }]),
    maskBufferFor([{ start: 2, end: 4, value: 0, folderIndex: 0 }]),
    maskBufferFor([{ start: 2, end: 4, value: 48, folderIndex: 1 }]),
    maskBufferFor([
      { start: 2, end: 5, value: 48, folderIndex: 0 },
      { start: 4, end: 6, value: 35, folderIndex: 0 },
    ]),
    new ArrayBuffer(3),
    {},
  ]) {
    assert.equal(core.validateMaskRecords(invalid, 8, ["Foo"]).ok, false);
  }
  assert.equal(core.validateMaskRecords(valid, 1, ["Foo"]).ok, false);
  assert.equal(core.validateMaskRecords(valid, 8, ["blizzard_ui"]).ok, false);
});

test("worker rejects malformed mask records as a whole", () => {
  const { core } = createPageContext();
  const { scope, context, dispatchBoot } = bootWorkerScope(core, {
    importScriptsImpl: () => {
      scope.wasmImports = { HEAPU8: new Uint8Array(16), WebVfsStoreIo: function () {} };
      scope.assignWasmImports = function () {};
    },
  });
  dispatchBoot(undefined, {
    indexSize: 16,
    enabledFolders: ["Foo"],
    maskBuffer: maskBufferFor([
      { start: 2, end: 4, value: 48, folderIndex: 0 },
      { start: 4, end: 5, value: 35, folderIndex: 1 },
    ]),
  });
  vm.runInContext("self.assignWasmImports(); self.wasmImports.WebVfsStoreIo(1, false, 0, 16)", context);
  assert.ok(vm.runInContext("Array.from(self.wasmImports.HEAPU8).every((value) => value === 0)", context));
});

test("canonicalUnsigned accepts canonical decimal only", () => {
  const { core } = createPageContext();
  assert.equal(core.canonicalUnsigned("0"), 0);
  assert.equal(core.canonicalUnsigned("42"), 42);
  assert.equal(core.canonicalUnsigned(""), null);
  assert.equal(core.canonicalUnsigned("007"), null);
  assert.equal(core.canonicalUnsigned("+1"), null);
  assert.equal(core.canonicalUnsigned("1.5"), null);
  assert.equal(core.canonicalUnsigned("1e3"), null);
  assert.equal(core.canonicalUnsigned("0x10"), null);
  assert.equal(core.canonicalUnsigned("-1"), null);
  assert.equal(core.canonicalUnsigned("999999999999999999"), null);
});

test("isSafeAddonPath enforces the addon prefix and folder contract", () => {
  const { core } = createPageContext();
  assert.equal(core.isSafeAddonPath("Interface/AddOns/Foo/main.lua"), true);
  assert.equal(core.isSafeAddonPath("Interface/AddOns/Foo/sub/dir.lua"), true);
  assert.equal(core.isSafeAddonPath("Interface/AddOns/blizzard_ui/main.lua"), false);
  assert.equal(core.isSafeAddonPath("Interface/AddOns/Foo/../main.lua"), false);
  assert.equal(core.isSafeAddonPath("scripts/main.lua"), false);
  assert.equal(core.isSafeAddonPath("Interface/AddOns/Foo"), false);
  assert.equal(core.isSafeAddonPath("Interface/AddOns/"), false);
});

test("resolveBundleEntry finds exact folder and file matches only", () => {
  const { core } = createPageContext();
  const payload = { revision: "a".repeat(64), addons: [{ folder: "Foo", files: { "main.lua": "QQ==" } }] };
  assert.ok(core.resolveBundleEntry(payload, "Interface/AddOns/Foo/main.lua"));
  assert.equal(core.resolveBundleEntry(payload, "Interface/AddOns/Foo/other.lua"), null);
  assert.equal(core.resolveBundleEntry(payload, "Interface/AddOns/Bar/main.lua"), null);
  assert.equal(core.resolveBundleEntry(payload, "Interface/AddOns/Foo"), null);
  assert.equal(core.resolveBundleEntry(null, "Interface/AddOns/Foo/main.lua"), null);
});

test("bootstrap source embeds the exact original url without remote fetches", () => {
  const { core } = createPageContext();
  const source = core.buildBootstrapSource(WORKER_URL);
  assert.ok(!source.includes("eval("));
  assert.ok(!source.includes("fetch("));
  assert.ok(!source.includes("document."));
  assert.ok(!source.includes("window."));
  assert.ok(source.includes(JSON.stringify(WORKER_URL)));
  assert.ok(source.includes('"use strict"'));
  assert.ok(source.includes("lhMaskRecords"));
});

test("buildBootstrapSource rejects non-https urls", () => {
  const { core } = createPageContext();
  assert.throws(() => core.buildBootstrapSource("http://lasthavens.com/game/w.js"), /lh-worker-bootstrap-url/);
  assert.throws(() => core.buildBootstrapSource("https://evil.example.com/w.js"), /lh-worker-bootstrap-url/);
});
