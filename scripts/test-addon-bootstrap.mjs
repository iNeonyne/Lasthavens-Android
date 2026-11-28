import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import vm from "node:vm";

const bootstrapPath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/addon-bootstrap.js",
  import.meta.url,
);
const bootstrapSource = readFileSync(fileURLToPath(bootstrapPath), "utf8");

const BRIDGE_KEY = "__LASTHAVEN_ADDON_BRIDGE__";
const BOOTSTRAP_KEY = "__LASTHAVEN_ADDON_BOOTSTRAP__";
const REVISION_1 = "a".repeat(64);
const REVISION_2 = "b".repeat(64);
const BASE_HREF = "https://lasthavens.com/play";

function b64(text) {
  return Buffer.from(text, "utf8").toString("base64");
}

function fixtureBundle(revision = REVISION_1) {
  return {
    revision: revision,
    addons: [
      {
        folder: "DepCore",
        files: Object.assign(Object.create(null), {
          "DepCore.toc": b64("## Interface: 30300\ncore.js\n"),
          "core.js": b64("globalThis.__LH_HOOKS.push('DepCore:core.js');"),
        }),
      },
      {
        folder: "UnitFrame",
        files: Object.assign(Object.create(null), {
          "UnitFrame.toc": b64("## Interface: 30300\ninit.js\nskin.css\n"),
          "init.js": b64("globalThis.__LH_HOOKS.push('UnitFrame:init.js');"),
          "skin.css": b64(".unitframe { color: red; }"),
        }),
      },
    ],
  };
}

function fixturePlan(revision = REVISION_1) {
  return {
    revision: revision,
    addons: [
      { folder: "DepCore", loadFiles: ["core.js"] },
      { folder: "UnitFrame", loadFiles: ["init.js", "skin.css"] },
    ],
    blocked: [{ folder: "Broken", reason: "missingDependencies" }],
  };
}

function createContext(overrides = {}) {
  const context = vm.createContext({
    __LH_HOOKS: [],
    URL,
    atob,
    btoa,
    TextDecoder,
    console,
    setTimeout,
    clearTimeout,
    location: { href: BASE_HREF },
    ...overrides,
  });
  const appended = [];
  const removed = [];
  const head = {
    appendChild(element) {
      element.parentNode = head;
      appended.push(element);
      if (element.tagName === "script") {
        vm.runInContext(element.textContent, context, { filename: "addon-script.js" });
      }
    },
    removeChild(element) {
      element.parentNode = null;
      removed.push(element);
    },
  };
  context.document = {
    head: head,
    documentElement: null,
    readyState: "complete",
    createElement(tag) {
      return {
        tagName: tag,
        attributes: Object.create(null),
        textContent: "",
        parentNode: null,
        setAttribute(name, value) {
          this.attributes[name] = value;
        },
      };
    },
  };
  context.__appended = appended;
  context.__removed = removed;
  return context;
}

function makeBridge(overrides = {}) {
  const counters = { getState: 0, getBundle: 0, getPlan: 0 };
  const bundle = overrides.bundle === undefined ? fixtureBundle() : overrides.bundle;
  const state =
    overrides.state === undefined ? { ready: true, error: null } : overrides.state;
  const plan = overrides.plan === undefined ? fixturePlan() : overrides.plan;
  const bridge = {
    getBundle: () => {
      counters.getBundle += 1;
      return bundle;
    },
    getState: () => {
      counters.getState += 1;
      return state;
    },
    getBootstrapPlan: () => {
      counters.getPlan += 1;
      return plan;
    },
  };
  if (overrides.omitPlanGetter === true) {
    delete bridge.getBootstrapPlan;
  }
  return { bridge, counters };
}

async function install(context, { bridge = null, fakeClock = false } = {}) {
  if (bridge) {
    context[BRIDGE_KEY] = bridge;
  }
  if (fakeClock) {
    let index = 0;
    context.Date = {
      now: () => 5000 * index++,
      parse: Date.parse,
      UTC: Date.UTC,
    };
  }
  await vm.runInContext(bootstrapSource, context, { filename: "addon-bootstrap.js" });
  await new Promise((resolve) => setTimeout(resolve, 120));
  let marker = context[BOOTSTRAP_KEY] || null;
  for (let attempt = 0; attempt < 20 && marker === null; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 50));
    marker = context[BOOTSTRAP_KEY] || null;
  }
  return marker;
}

function loadCore() {
  const context = createContext();
  vm.runInContext(bootstrapSource, context, { filename: "addon-bootstrap.js" });
  return context.__LHAddonBootstrapCore;
}

test("core exposes the bounded bootstrap constants and pure helpers", () => {
  const core = loadCore();
  assert.equal(core.MAX_STARTUP_WAIT_MS, 20000);
  assert.equal(core.POLL_MS, 50);
  assert.equal(core.MAX_TRACKED_ELEMENTS, 4096);
  assert.deepEqual(Array.from(core.EXECUTABLE_EXTENSIONS), ["js", "css"]);
  assert.deepEqual(Array.from(core.GAME_HOSTS), ["lasthavens.com", "alt.lasthavens.com"]);
  assert.equal(core.extensionOf("AddOns/Skin/skin.CSS"), "css");
  assert.equal(core.extensionOf("noextension"), "");
  assert.equal(core.decodeText(b64("hook();")), "hook();");
  assert.equal(core.decodeText(""), "");
  assert.equal(core.pageUrlAllowed(), true);
});

test("validatePlan rejects revision mismatch, unsafe folders and unknown paths", () => {
  const core = loadCore();
  const bundle = fixtureBundle();
  assert.equal(core.validatePlan({ ...fixturePlan(), revision: "c".repeat(64) }, bundle), null);
  assert.equal(
    core.validatePlan({ ...fixturePlan(), addons: [{ folder: "Missing", loadFiles: [] }] }, bundle),
    null,
  );
  assert.equal(
    core.validatePlan(
      { ...fixturePlan(), addons: [{ folder: "DepCore", loadFiles: ["../escape.js"] }] },
      bundle,
    ),
    null,
  );
  assert.equal(
    core.validatePlan(
      { ...fixturePlan(), blocked: [{ folder: "X", reason: "unknown" }] },
      bundle,
    ),
    null,
  );
  assert.equal(core.validatePlan(null, bundle), null);
  assert.deepEqual(
    Array.from(core.validatePlan(fixturePlan(), bundle).addons[1].loadFiles),
    ["init.js", "skin.css"],
  );
});

test("enabled fixture addons execute in dependency order and styles apply", async () => {
  const context = createContext();
  const { bridge } = makeBridge();
  const marker = await install(context, { bridge });

  assert.deepEqual(context.__LH_HOOKS, ["DepCore:core.js", "UnitFrame:init.js"]);
  assert.equal(marker.revision, REVISION_1);
  assert.deepEqual(Array.from(marker.applied), [
    "DepCore/core.js",
    "UnitFrame/init.js",
    "UnitFrame/skin.css",
  ]);
  assert.deepEqual(Array.from(marker.skipped), []);
  assert.deepEqual(Array.from(marker.blocked), ["Broken:missingDependencies"]);
  assert.equal(context.__appended.length, 3);
  const style = context.__appended[2];
  assert.equal(style.tagName, "style");
  assert.equal(style.textContent, ".unitframe { color: red; }");
  assert.equal(style.attributes["data-lh-addon"], "UnitFrame");
  assert.equal(style.attributes["data-lh-addon-revision"], REVISION_1);
  assert.ok(Object.isFrozen(marker));
  assert.equal(Object.getOwnPropertyDescriptor(context, BOOTSTRAP_KEY).writable, false);
});

test("repeated initialization with the same revision applies exactly once", async () => {
  const context = createContext();
  const { bridge } = makeBridge();
  await install(context, { bridge });
  const hooksAfterFirst = context.__LH_HOOKS.length;
  const appendedAfterFirst = context.__appended.length;

  await install(context, { bridge });

  assert.equal(context.__LH_HOOKS.length, hooksAfterFirst);
  assert.equal(context.__appended.length, appendedAfterFirst);
});

test("a revision change removes old elements and applies the fresh revision once", async () => {
  const context = createContext();
  const first = makeBridge();
  await install(context, { bridge: first.bridge });
  const firstElements = context.__appended.slice();

  const second = makeBridge({
    bundle: fixtureBundle(REVISION_2),
    plan: fixturePlan(REVISION_2),
  });
  await install(context, { bridge: second.bridge });

  assert.ok(context.__removed.length >= firstElements.length);
  for (const element of firstElements) {
    assert.equal(element.parentNode, null);
  }
  assert.equal(context[BOOTSTRAP_KEY].revision, REVISION_2);
  assert.equal(
    context.__appended.filter((element) => element.parentNode === context.document.head).length,
    3,
  );
});

test("blocked and absent addons never execute but reasons are published", async () => {
  const context = createContext();
  const { bridge } = makeBridge();
  const marker = await install(context, { bridge });

  assert.equal(context.__LH_HOOKS.includes("Broken:script.js"), false);
  assert.deepEqual(Array.from(marker.blocked), ["Broken:missingDependencies"]);
});

test("unsupported entry types are skipped with a reason and others still apply", async () => {
  const context = createContext();
  const bundle = fixtureBundle();
  bundle.addons[1].files["notes.lua"] = b64("-- lua content");
  const plan = fixturePlan();
  plan.addons[1].loadFiles = ["init.js", "skin.css", "notes.lua"];
  const { bridge } = makeBridge({ bundle, plan });
  const marker = await install(context, { bridge });

  assert.equal(
    JSON.stringify(Array.from(marker.skipped)),
    JSON.stringify([{ folder: "UnitFrame", path: "notes.lua", reason: "unsupportedType" }]),
  );
  assert.equal(marker.applied.length, 3);
  assert.deepEqual(context.__LH_HOOKS, ["DepCore:core.js", "UnitFrame:init.js"]);
});

test("a load entry that fails to decode is skipped with invalidEntry", async () => {
  const context = createContext();
  const bundle = fixtureBundle();
  bundle.addons[0].files["core.js"] = "!!!not base64!!!";
  const { bridge } = makeBridge({ bundle });
  const marker = await install(context, { bridge });

  assert.equal(
    JSON.stringify(Array.from(marker.skipped)),
    JSON.stringify([{ folder: "DepCore", path: "core.js", reason: "invalidEntry" }]),
  );
  assert.deepEqual(context.__LH_HOOKS, ["UnitFrame:init.js"]);
});

test("a plan referencing a file outside the bundle fails closed without executing", async () => {
  const context = createContext();
  const plan = fixturePlan();
  plan.addons[0].loadFiles = ["ghost.js"];
  const { bridge } = makeBridge({ plan });
  const marker = await install(context, { bridge });

  assert.equal(marker.error, "invalidPlan");
  assert.deepEqual(context.__LH_HOOKS, []);
  assert.equal(context.__appended.length, 0);
});

test("a bridge error state publishes the error without executing", async () => {
  const context = createContext();
  const { bridge } = makeBridge({ state: { ready: true, error: "payloadTooLarge" } });
  const marker = await install(context, { bridge });

  assert.equal(marker.error, "payloadTooLarge");
  assert.deepEqual(context.__LH_HOOKS, []);
  assert.equal(marker.applied.length, 0);
});

test("a plan error from the bridge publishes the error without executing", async () => {
  const context = createContext();
  const { bridge } = makeBridge({ plan: { error: "invalidResponse" } });
  const marker = await install(context, { bridge });

  assert.equal(marker.error, "invalidResponse");
  assert.deepEqual(context.__LH_HOOKS, []);
});

test("a missing bootstrap plan getter times out bounded without executing", async () => {
  const context = createContext();
  const { bridge, counters } = makeBridge({ omitPlanGetter: true });
  const marker = await install(context, {
    bridge,
    fakeClock: true,
  });

  assert.equal(marker.error, "unavailable");
  assert.deepEqual(context.__LH_HOOKS, []);
  assert.ok(counters.getState <= 8, `getState polled ${counters.getState} times`);
});

test("a never-ready bridge times out bounded and publishes unavailable", async () => {
  const context = createContext();
  const { bridge, counters } = makeBridge({ state: { ready: false, error: null } });
  const marker = await install(context, {
    bridge,
    fakeClock: true,
  });

  assert.equal(marker.error, "unavailable");
  assert.deepEqual(context.__LH_HOOKS, []);
  assert.ok(counters.getState <= 8, `getState polled ${counters.getState} times`);
});

test("a missing bridge times out bounded and publishes bridgeUnavailable", async () => {
  const context = createContext();
  const marker = await install(context, { fakeClock: true });

  assert.equal(marker.error, "bridgeUnavailable");
  assert.deepEqual(context.__LH_HOOKS, []);
});

test("the alternate approved origin executes; other origins and paths are inert", async () => {
  const altContext = createContext({ location: { href: "https://alt.lasthavens.com/play" } });
  const altMarker = await install(altContext, { bridge: makeBridge().bridge });
  assert.deepEqual(altContext.__LH_HOOKS, ["DepCore:core.js", "UnitFrame:init.js"]);
  assert.equal(altMarker.revision, REVISION_1);

  const evilContext = createContext({ location: { href: "https://evil.example.com/play" } });
  const evilBridge = makeBridge();
  const evilMarker = await install(evilContext, { bridge: evilBridge.bridge });
  assert.equal(evilMarker, null);
  assert.deepEqual(evilBridge.counters.getState, 0);

  const portContext = createContext({
    location: { href: "https://lasthavens.com:8443/play" },
  });
  const portMarker = await install(portContext, { bridge: makeBridge().bridge });
  assert.equal(portMarker, null);

  const pathContext = createContext({ location: { href: "https://lasthavens.com/games/play" } });
  const pathMarker = await install(pathContext, { bridge: makeBridge().bridge });
  assert.equal(pathMarker, null);
});

test("a large bundle with many entry points applies in bounded order", async () => {
  const context = createContext();
  const loadFiles = [];
  const files = Object.assign(Object.create(null), { "Big.toc": b64("## Interface: 30300\n") });
  for (let index = 0; index < 64; index += 1) {
    const name = `module${index}.${index % 2 === 0 ? "js" : "css"}`;
    files[name] = b64(
      index % 2 === 0 ? `globalThis.__LH_HOOKS.push('m${index}');` : `.s${index} {}`,
    );
    loadFiles.push(name);
  }
  const bundle = {
    revision: REVISION_1,
    addons: [{ folder: "Big", files: files }],
  };
  const plan = {
    revision: REVISION_1,
    addons: [{ folder: "Big", loadFiles: loadFiles }],
    blocked: [],
  };
  const { bridge, counters } = makeBridge({ bundle, plan });
  const marker = await install(context, { bridge });

  assert.equal(marker.applied.length, 64);
  assert.equal(context.__LH_HOOKS.length, 32);
  assert.equal(
    context.__LH_HOOKS.join(","),
    Array.from({ length: 32 }, (_value, index) => `m${index * 2}`).join(","),
  );
  assert.ok(counters.getState <= 8, `getState polled ${counters.getState} times`);
  assert.ok(counters.getBundle <= 4, `getBundle called ${counters.getBundle} times`);
});
