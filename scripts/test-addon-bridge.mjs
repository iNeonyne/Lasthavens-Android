import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import vm from "node:vm";

const validatorPath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/payload-validation.js",
  import.meta.url,
);
const validatorSource = readFileSync(fileURLToPath(validatorPath), "utf8");

const manifestPath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/manifest.json",
  import.meta.url,
);
const manifest = JSON.parse(readFileSync(fileURLToPath(manifestPath), "utf8"));

test("manifest has the exact built-in identity, permissions and game route", () => {
  assert.equal(manifest.manifest_version, 2);
  assert.equal(manifest.version, "0.5.1");
  assert.equal(manifest.browser_specific_settings.gecko.id, "lasthaven-addon-bridge@neonyne");
  assert.deepEqual(manifest.background, {
    scripts: ["background.js"],
    persistent: true,
  });
  assert.deepEqual(manifest.permissions, ["geckoViewAddons", "nativeMessaging"]);
  assert.deepEqual(manifest.content_scripts, [
    {
      matches: [
        "https://lasthavens.com/play",
        "https://lasthavens.com/play/",
        "https://alt.lasthavens.com/play",
        "https://alt.lasthavens.com/play/",
      ],
      js: ["payload-validation.js", "bridge.js"],
      run_at: "document_start",
      all_frames: false,
      match_about_blank: false,
    },
    {
      matches: [
        "https://lasthavens.com/play",
        "https://lasthavens.com/play/",
        "https://alt.lasthavens.com/play",
        "https://alt.lasthavens.com/play/",
      ],
      js: ["xhr-overlay.js"],
      run_at: "document_start",
      all_frames: false,
      match_about_blank: false,
      world: "MAIN",
    },
    {
      matches: [
        "https://lasthavens.com/play",
        "https://lasthavens.com/play/",
        "https://alt.lasthavens.com/play",
        "https://alt.lasthavens.com/play/",
      ],
      js: ["worker-overlay.js"],
      run_at: "document_start",
      all_frames: false,
      match_about_blank: false,
      world: "MAIN",
    },
    {
      matches: [
        "https://lasthavens.com/play",
        "https://lasthavens.com/play/",
        "https://alt.lasthavens.com/play",
        "https://alt.lasthavens.com/play/",
      ],
      js: ["addon-bootstrap.js"],
      run_at: "document_start",
      all_frames: false,
      match_about_blank: false,
      world: "MAIN",
    },
  ]);
  assert.equal(JSON.stringify(manifest).includes("<all_urls>"), false);
});

function loadCore() {
  const context = vm.createContext({});
  vm.runInContext(validatorSource, context, { filename: "payload-validation.js" });
  return context.__LHAddonRuntimeCore;
}

function makeAddon(folder = "UnitFrame", files = {}) {
  return {
    folder,
    files: Object.assign(Object.create(null), files),
  };
}

function makePayload(addons = [makeAddon("UnitFrame", { "UnitFrame.toc": "dG9j" })]) {
  return {
    revision: "a".repeat(64),
    addons,
  };
}

test("accepts empty and valid ZIP 0.5.1 payloads", () => {
  const core = loadCore();
  assert.equal(core.validatePayload(makePayload([])), true);
  assert.equal(core.validatePayload(makePayload()), true);
});

test("rejects malformed payload shapes, revisions and missing TOCs", () => {
  const core = loadCore();
  assert.equal(core.validatePayload({ revision: "short", addons: [] }), false);
  assert.equal(core.validatePayload({ revision: "a".repeat(64), addons: [], extra: true }), false);
  assert.equal(core.validatePayload(makePayload([makeAddon()])), false);
  assert.equal(
    core.validatePayload(makePayload([makeAddon("Blizzard_Unit", { "Blizzard_Unit.toc": "" })])),
    false,
  );
});

test("rejects unsafe and case-colliding folders and paths", () => {
  const core = loadCore();
  for (const path of ["../escape.lua", "/absolute.lua", "a\\b.lua", "a:b.lua", "a//b.lua"]) {
    assert.equal(
      core.validatePayload(
        makePayload([makeAddon("UnitFrame", { "UnitFrame.toc": "", [path]: "" })]),
      ),
      false,
      path,
    );
  }

  assert.equal(
    core.validatePayload(
      makePayload([
        makeAddon("UnitFrame", { "UnitFrame.toc": "" }),
        makeAddon("unitframe", { "unitframe.toc": "" }),
      ]),
    ),
    false,
  );
  assert.equal(
    core.validatePayload(
      makePayload([
        makeAddon("UnitFrame", {
          "UnitFrame.toc": "",
          "Data.bin": "",
          "data.bin": "",
        }),
      ]),
    ),
    false,
  );
});

test("rejects invalid base64 and files above the per-file limit", () => {
  const core = loadCore();
  assert.equal(
    core.validatePayload(makePayload([makeAddon("UnitFrame", { "UnitFrame.toc": "not-base64!" })])),
    false,
  );

  const oversized = Buffer.alloc(8 * 1024 * 1024 + 1).toString("base64");
  assert.equal(
    core.validatePayload(
      makePayload([makeAddon("UnitFrame", { "UnitFrame.toc": "", "large.bin": oversized })]),
    ),
    false,
  );
});

test("enforces aggregate byte, file-count and addon-count bounds", () => {
  const core = loadCore();
  const eightMiB = Buffer.alloc(8 * 1024 * 1024).toString("base64");
  const aggregate = makePayload([
    makeAddon("UnitFrame", {
      "UnitFrame.toc": "",
      "first.bin": eightMiB,
      "second.bin": eightMiB,
      "third.bin": "AA==",
    }),
  ]);
  assert.equal(core.validatePayload(aggregate), false);

  const manyFiles = Object.create(null);
  manyFiles["UnitFrame.toc"] = "";
  for (let index = 0; index < 1024; index += 1) {
    manyFiles["file-" + index + ".bin"] = "";
  }
  assert.equal(core.validatePayload(makePayload([makeAddon("UnitFrame", manyFiles)])), false);

  const manyAddons = [];
  for (let index = 0; index < 129; index += 1) {
    const folder = "Addon" + index;
    manyAddons.push(makeAddon(folder, { [folder + ".toc"]: "" }));
  }
  assert.equal(core.validatePayload(makePayload(manyAddons)), false);
});

test("deep-freezes the payload and nested addon file maps", () => {
  const core = loadCore();
  const payload = makePayload();
  core.deepFreeze(payload);
  assert.equal(Object.isFrozen(payload), true);
  assert.equal(Object.isFrozen(payload.addons), true);
  assert.equal(Object.isFrozen(payload.addons[0]), true);
  assert.equal(Object.isFrozen(payload.addons[0].files), true);
});

const bridgeSourcePath = new URL(
  "../app/src/main/assets/extensions/addon-bridge/bridge.js",
  import.meta.url,
);
const bridgeSource = readFileSync(fileURLToPath(bridgeSourcePath), "utf8");

test("the page bundle getter clones once per revision and publishes a revision getter", () => {
  assert.equal(bridgeSource.includes("pageBundleClone"), true);
  assert.equal(
    bridgeSource.includes("pageBundleClone = deepFreezePageClone(cloneInto(currentBundle, targetWindow))"),
    true,
    "getBundle must reuse one cached page-realm clone per revision",
  );
  assert.equal(
    bridgeSource.includes("pageBundleClone = null;\n        ready = true;"),
    true,
    "a freshly fetched bundle revision must invalidate the cached clone",
  );
  assert.equal(
    bridgeSource.includes("getRevision: exportFunction("),
    true,
    "the page bridge must expose a cheap revision getter for readiness checks",
  );
});

test("the page-realm cached clone is recursively frozen after structured cloning", () => {
  assert.equal(bridgeSource.includes("function deepFreezePageClone(value, seen)"), true);
  assert.equal(
    bridgeSource.includes("pageBundleClone = deepFreezePageClone(cloneInto(currentBundle, targetWindow))"),
    true,
    "structured-clone descriptors must be re-frozen before exposing the cached object",
  );
});

test("the overlay checks the bridge revision before cloning the payload", () => {
  const overlayPath = new URL(
    "../app/src/main/assets/extensions/addon-bridge/xhr-overlay.js",
    import.meta.url,
  );
  const overlaySource = readFileSync(fileURLToPath(overlayPath), "utf8");
  assert.equal(
    overlaySource.includes("bridge.getRevision()"),
    true,
    "readiness checks must consult the cheap revision getter first",
  );
});
