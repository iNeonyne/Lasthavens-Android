"use strict";

/**
 * MAIN-world bootstrap for enabled addon entry points (issue #245).
 *
 * Reads the launcher-owned, read-only bridge state published by the isolated content script on
 * window.__LASTHAVEN_ADDON_BRIDGE__, waits for the validated payload and the native bootstrap
 * plan (per-addon TOC load-file lists in deterministic dependency order), then executes the
 * enabled addons' .js entry scripts and .css styles in the exact page realm. Disabled, blocked
 * and malformed addons never execute; their reasons are published for diagnostics. Application
 * is one-shot per payload revision: repeated document initialization with the same revision does
 * nothing, a revision change removes the previous revision's elements and re-applies exactly
 * once. Inline element injection is subject to the game page's content security policy; a
 * diagnosable error state is published when execution is not possible.
 */
(function installAddonBootstrap(global) {
  const CORE_KEY = "__LHAddonBootstrapCore";
  const BRIDGE_KEY = "__LASTHAVEN_ADDON_BRIDGE__";
  const BOOTSTRAP_KEY = "__LASTHAVEN_ADDON_BOOTSTRAP__";
  const GAME_HOST = "lasthavens.com";
  const GAME_HOSTS = new Set([GAME_HOST, "alt.lasthavens.com"]);
  const MAX_STARTUP_WAIT_MS = 20000;
  const POLL_MS = 50;
  const MAX_TRACKED_ELEMENTS = 4096;
  const EXECUTABLE_EXTENSIONS = new Set(["js", "css"]);
  const REVISION_PATTERN = /^[a-f0-9]{64}$/;

  function pageUrlAllowed() {
    let url;
    try {
      url = new URL(global.location && typeof global.location.href === "string"
        ? global.location.href
        : "about:blank");
    } catch (_error) {
      return false;
    }
    return url.protocol === "https:" &&
      GAME_HOSTS.has(url.hostname) &&
      url.port === "" &&
      url.username === "" &&
      url.password === "" &&
      (url.pathname === "/play" || url.pathname === "/play/");
  }

  function extensionOf(path) {
    const index = path.lastIndexOf(".");
    return index === -1 ? "" : path.slice(index + 1).toLowerCase();
  }

  function decodeText(encoded) {
    if (typeof encoded !== "string") {
      throw new Error("invalidEntry");
    }
    if (encoded.length === 0) {
      return "";
    }
    const binary = atob(encoded);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  }

  function isPlainObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  function validatePlan(plan, bundle) {
    if (!isPlainObject(plan) || typeof plan.revision !== "string" ||
        !REVISION_PATTERN.test(plan.revision) ||
        plan.revision !== bundle.revision ||
        !Array.isArray(plan.addons) ||
        !Array.isArray(plan.blocked)) {
      return null;
    }
    const bundleAddons = new Map();
    for (const addon of bundle.addons) {
      if (isPlainObject(addon) && typeof addon.folder === "string") {
        bundleAddons.set(addon.folder, addon);
      }
    }
    const seenFolders = new Set();
    const addons = [];
    for (const entry of plan.addons) {
      if (!isPlainObject(entry) ||
          typeof entry.folder !== "string" ||
          /^[A-Za-z0-9_-]{1,128}$/.test(entry.folder) === false ||
          entry.folder.toLowerCase().startsWith("blizzard_") ||
          seenFolders.has(entry.folder.toLowerCase()) ||
          !Array.isArray(entry.loadFiles)) {
        return null;
      }
      seenFolders.add(entry.folder.toLowerCase());
      const bundleAddon = bundleAddons.get(entry.folder);
      if (!bundleAddon || !isPlainObject(bundleAddon.files)) {
        return null;
      }
      const seenPaths = new Set();
      const loadFiles = [];
      for (const path of entry.loadFiles) {
        if (typeof path !== "string" ||
            path.length === 0 ||
            path.length > 512 ||
            seenPaths.has(path.toLowerCase()) ||
            !(path in bundleAddon.files)) {
          return null;
        }
        seenPaths.add(path.toLowerCase());
        loadFiles.push(path);
      }
      addons.push({ folder: entry.folder, loadFiles: loadFiles });
    }
    const blocked = [];
    for (const entry of plan.blocked) {
      if (!isPlainObject(entry) ||
          typeof entry.folder !== "string" ||
          typeof entry.reason !== "string" ||
          ["missingDependencies", "dependencyCycle", "payloadTooLarge"].includes(entry.reason) ===
            false) {
        return null;
      }
      blocked.push({ folder: entry.folder, reason: entry.reason });
    }
    return { revision: plan.revision, addons: addons, blocked: blocked };
  }

  const core = Object.freeze({
    GAME_HOST: GAME_HOST,
    GAME_HOSTS: Object.freeze(Array.from(GAME_HOSTS)),
    BOOTSTRAP_KEY: BOOTSTRAP_KEY,
    MAX_STARTUP_WAIT_MS: MAX_STARTUP_WAIT_MS,
    POLL_MS: POLL_MS,
    MAX_TRACKED_ELEMENTS: MAX_TRACKED_ELEMENTS,
    EXECUTABLE_EXTENSIONS: Object.freeze(Array.from(EXECUTABLE_EXTENSIONS)),
    extensionOf: extensionOf,
    decodeText: decodeText,
    validatePlan: validatePlan,
    pageUrlAllowed: pageUrlAllowed
  });
  try {
    Object.defineProperty(global, CORE_KEY, {
      value: core,
      configurable: false,
      enumerable: false,
      writable: false
    });
  } catch (_error) {
    // Re-installation into the same realm keeps the first core.
  }

  if (!pageUrlAllowed()) {
    return;
  }

  function waitFor(predicate) {
    return new Promise((resolve) => {
      const startedAt = Date.now();
      const poll = () => {
        let value = null;
        try {
          value = predicate();
        } catch (_error) {
          value = null;
        }
        if (value !== null && value !== undefined) {
          resolve(value);
          return;
        }
        if (Date.now() - startedAt >= MAX_STARTUP_WAIT_MS) {
          resolve(null);
          return;
        }
        setTimeout(poll, POLL_MS);
      };
      poll();
    });
  }

  function waitForHead() {
    return waitFor(() => {
      const owner = global.document;
      if (owner && (owner.head || owner.documentElement)) {
        return owner.head || owner.documentElement;
      }
      return null;
    });
  }

  function currentMarker() {
    try {
      return global[BOOTSTRAP_KEY] || null;
    } catch (_error) {
      return null;
    }
  }

  function publishMarker(fields, elements, force) {
    const previous = currentMarker();
    if (!force && previous) {
      // A failed re-application keeps the previously applied revision published.
      return;
    }
    const marker = Object.freeze({
      revision: fields.revision === undefined ? null : fields.revision,
      error: fields.error === undefined ? null : fields.error,
      applied: Object.freeze(fields.applied || []),
      skipped: Object.freeze(fields.skipped || []),
      blocked: Object.freeze(fields.blocked || []),
      remove: function () {
        for (const element of elements) {
          try {
            if (element && element.parentNode) {
              element.parentNode.removeChild(element);
            }
          } catch (_error) {
            // Elements already detached stay removed.
          }
        }
      }
    });
    // Page-realm diagnostic state: re-definable so a revision change can replace it, but never
    // writable through the marker reference itself.
    Object.defineProperty(global, BOOTSTRAP_KEY, {
      value: marker,
      configurable: true,
      enumerable: false,
      writable: false
    });
  }

  function fail(errorCode) {
    publishMarker({ error: errorCode, applied: [], skipped: [], blocked: [] }, [],
      currentMarker() === null);
  }

  function failAfterRemoval(errorCode) {
    publishMarker({ error: errorCode, applied: [], skipped: [], blocked: [] }, [], true);
  }

  async function run() {
    const bridge = await waitFor(() => {
      const candidate = global[BRIDGE_KEY];
      return candidate && typeof candidate.getState === "function" ? candidate : null;
    });
    if (!bridge) {
      fail("bridgeUnavailable");
      return;
    }
    const readyState = await waitFor(() => {
      const state = bridge.getState();
      if (state && state.ready === true) {
        return state;
      }
      return null;
    });
    if (!readyState || typeof readyState.error === "string") {
      fail(readyState ? readyState.error.slice(0, 32) : "unavailable");
      return;
    }
    const planOrError = await waitFor(() => {
      if (typeof bridge.getBootstrapPlan !== "function") {
        return null;
      }
      const candidate = bridge.getBootstrapPlan();
      return candidate || null;
    });
    if (!planOrError || typeof planOrError.error === "string") {
      fail(planOrError ? planOrError.error.slice(0, 32) : "unavailable");
      return;
    }
    const bundle = await waitFor(() => bridge.getBundle() || null);
    if (!bundle) {
      fail("unavailable");
      return;
    }
    const plan = validatePlan(planOrError, bundle);
    if (!plan) {
      fail("invalidPlan");
      return;
    }
    await waitForHead();
    const head =
      global.document && (global.document.head || global.document.documentElement) || null;
    if (!head) {
      fail("noDocumentHead");
      return;
    }
    apply(plan, bundle, head);
  }

  function apply(plan, bundle, head) {
    const previous = global[BOOTSTRAP_KEY] || null;
    if (previous && previous.revision === plan.revision) {
      return;
    }
    if (previous && typeof previous.remove === "function") {
      previous.remove();
    }
    const bundleAddons = new Map();
    for (const addon of bundle.addons) {
      if (isPlainObject(addon) && typeof addon.folder === "string") {
        bundleAddons.set(addon.folder, addon);
      }
    }
    const elements = [];
    const applied = [];
    const skipped = [];
    try {
      for (const entry of plan.addons) {
        const bundleAddon = bundleAddons.get(entry.folder);
        for (const path of entry.loadFiles) {
          const extension = extensionOf(path);
          if (!EXECUTABLE_EXTENSIONS.has(extension)) {
            skipped.push({
              folder: entry.folder,
              path: path,
              reason: "unsupportedType"
            });
            continue;
          }
          const encoded = bundleAddon.files[path] || "";
          let text;
          try {
            text = decodeText(encoded);
          } catch (_error) {
            skipped.push({ folder: entry.folder, path: path, reason: "invalidEntry" });
            continue;
          }
          if (elements.length >= MAX_TRACKED_ELEMENTS) {
            failAfterRemoval("payloadTooLarge");
            return;
          }
          const element = global.document.createElement(
            extension === "css" ? "style" : "script"
          );
          element.setAttribute("data-lh-addon", entry.folder);
          element.setAttribute("data-lh-addon-revision", plan.revision);
          element.textContent = text;
          head.appendChild(element);
          elements.push(element);
          applied.push(entry.folder + "/" + path);
        }
      }
    } catch (_error) {
      for (const element of elements) {
        try {
          if (element.parentNode) {
            element.parentNode.removeChild(element);
          }
        } catch (_ignored) {
          // Already detached.
        }
      }
      failAfterRemoval("injectionFailed");
      return;
    }
    publishMarker(
      {
        revision: plan.revision,
        applied: applied,
        skipped: skipped,
        blocked: plan.blocked.map((entry) => entry.folder + ":" + entry.reason)
      },
      elements,
      true
    );
  }

  run();
})(typeof window !== "undefined" ? window : globalThis);
