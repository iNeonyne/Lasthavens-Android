"use strict";

/**
 * Launcher-owned background relay for the ZIP 0.5.1 addon payload.
 *
 * Only this extension context opens the GeckoView native port. Content scripts can request only
 * bounded bundle metadata, manifest pages and file chunks from the exact game route. Requests are
 * forwarded one at a time by the content script; this background never assembles a second library
 * copy and never writes extension storage.
 */
const NATIVE_APP = "neonyneAddonBridge";
const REQUEST_TIMEOUT_MS = 15000;
const INITIAL_RECONNECT_DELAY_MS = 250;
const MAX_RECONNECT_DELAY_MS = 30000;
const MAX_PENDING_REQUESTS = 8;
const MAX_PORT_WAITERS = 8;
const MAX_RUNTIME_BUNDLE_BYTES = 16 * 1024 * 1024;
const MAX_RUNTIME_PATH_CHARS = 512;
const MAX_MANIFEST_PAGES = 32;

const pending = new Map();
const portWaiters = [];
let port = null;
let reconnectTimer = null;
let reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
let requestSequence = 0;

function scheduleReconnect() {
  if (reconnectTimer !== null) {
    return;
  }
  const delay = reconnectDelayMs;
  reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

function rejectPending(code) {
  for (const [requestId, entry] of pending) {
    clearTimeout(entry.timeout);
    pending.delete(requestId);
    entry.reject(new Error(code));
  }
}

function resetPort(code) {
  const stalePort = port;
  port = null;
  rejectPending(code);
  if (stalePort) {
    try {
      stalePort.disconnect();
    } catch (_error) {
      // The port is already gone; reconnect below.
    }
  }
  scheduleReconnect();
}

function resolvePortWaiters() {
  while (portWaiters.length > 0 && port) {
    const waiter = portWaiters.shift();
    clearTimeout(waiter.timeout);
    waiter.resolve(port);
  }
}

function waitForPort() {
  if (port) {
    return Promise.resolve(port);
  }
  if (portWaiters.length >= MAX_PORT_WAITERS) {
    return Promise.reject(new Error("native_unavailable"));
  }
  return new Promise((resolve, reject) => {
    const waiter = { resolve: resolve, reject: reject, timeout: null };
    waiter.timeout = setTimeout(() => {
      const index = portWaiters.indexOf(waiter);
      if (index !== -1) {
        portWaiters.splice(index, 1);
      }
      reject(new Error("native_unavailable"));
    }, REQUEST_TIMEOUT_MS);
    portWaiters.push(waiter);
  });
}

function requestNative(type, fields = {}) {
  return waitForPort().then((activePort) => {
    if (activePort !== port || pending.size >= MAX_PENDING_REQUESTS) {
      throw new Error("native_unavailable");
    }
    requestSequence += 1;
    const requestId = "native-" + Date.now() + "-" + requestSequence;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        if (!pending.has(requestId)) {
          return;
        }
        pending.delete(requestId);
        reject(new Error("native_timeout"));
        resetPort("native_disconnected");
      }, REQUEST_TIMEOUT_MS);
      pending.set(requestId, { resolve: resolve, reject: reject, timeout: timeout });
      try {
        activePort.postMessage(Object.assign({}, fields, { type: type, requestId: requestId }));
      } catch (_error) {
        clearTimeout(timeout);
        pending.delete(requestId);
        reject(new Error("native_disconnected"));
        resetPort("native_disconnected");
      }
    });
  });
}

function connect() {
  let nextPort;
  try {
    nextPort = browser.runtime.connectNative(NATIVE_APP);
  } catch (_error) {
    scheduleReconnect();
    return;
  }

  port = nextPort;
  resolvePortWaiters();

  nextPort.onMessage.addListener((message) => {
    if (!message || typeof message !== "object" || typeof message.requestId !== "string") {
      return;
    }
    const entry = pending.get(message.requestId);
    if (!entry) {
      return;
    }
    pending.delete(message.requestId);
    clearTimeout(entry.timeout);
    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
    entry.resolve(message);
  });

  nextPort.onDisconnect.addListener(() => {
    if (port !== nextPort) {
      return;
    }
    port = null;
    rejectPending("native_disconnected");
    scheduleReconnect();
  });

  requestNative("ping").then((response) => {
    if (response && response.type === "ack") {
      reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS;
    }
  }).catch(() => {
    // The request timeout owns recovery and bounded reconnection.
  });
}

function isAllowedGameSender(sender) {
  if (!sender || sender.id !== browser.runtime.id || sender.frameId !== 0) {
    return false;
  }
  let url;
  try {
    url = new URL(sender.url);
  } catch (_error) {
    return false;
  }
  return url.protocol === "https:" &&
    (url.hostname === "lasthavens.com" || url.hostname === "alt.lasthavens.com") &&
    url.port === "" &&
    url.username === "" &&
    url.password === "" &&
    (url.pathname === "/play" || url.pathname === "/play/");
}

function isSafeFolder(folder) {
  return typeof folder === "string" &&
    folder.length > 0 &&
    folder.length <= 128 &&
    /^[A-Za-z0-9_-]+$/.test(folder) &&
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

function validateContentRequest(message) {
  if (!message || typeof message !== "object" || Array.isArray(message)) {
    return null;
  }
  if (message.type === "getBundleInfo" || message.type === "getBootstrapPlan") {
    return Object.keys(message).length === 1 ? { type: message.type, fields: {} } : null;
  }
  if (message.type === "getBundleManifestPage") {
    if (Object.keys(message).length !== 3 ||
        !/^[a-f0-9]{64}$/.test(message.revision) ||
        typeof message.page !== "string" ||
        !/^(0|[1-9][0-9]*)$/.test(message.page)) {
      return null;
    }
    const page = Number(message.page);
    if (!Number.isSafeInteger(page) || page < 0 || page >= MAX_MANIFEST_PAGES) {
      return null;
    }
    return { type: message.type, fields: { revision: message.revision, page: message.page } };
  }
  if (message.type === "getFileChunk") {
    if (Object.keys(message).length !== 5 ||
        !/^[a-f0-9]{64}$/.test(message.revision) ||
        !isSafeFolder(message.folder) ||
        !isSafeRelativePath(message.path) ||
        typeof message.offset !== "string" ||
        !/^(0|[1-9][0-9]*)$/.test(message.offset)) {
      return null;
    }
    const offset = Number(message.offset);
    if (!Number.isSafeInteger(offset) || offset < 0 || offset >= MAX_RUNTIME_BUNDLE_BYTES) {
      return null;
    }
    return {
      type: message.type,
      fields: {
        revision: message.revision,
        folder: message.folder,
        path: message.path,
        offset: message.offset
      }
    };
  }
  return null;
}

browser.runtime.onMessage.addListener((message, sender) => {
  if (!isAllowedGameSender(sender)) {
    return undefined;
  }
  const request = validateContentRequest(message);
  if (!request) {
    return Promise.resolve({ type: "error", error: "invalidRequest" });
  }
  return requestNative(request.type, request.fields).catch((error) => ({
    type: "error",
    error: typeof error?.message === "string" ? error.message.slice(0, 32) : "unavailable"
  }));
});

connect();
