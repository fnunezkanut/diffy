// Background service worker (Manifest V3).
//
// Discovers the port of a running IntelliJ IDE that has the diffy plugin
// installed (the IDE built-in server uses the next free port in 63342-63361),
// caches it for the browser session, and forwards "open" requests from the
// content script as a POST to the plugin's REST endpoint.
//
// The request must originate here, in the service worker, rather than from the
// page: Chrome's Private Network Access and CORS rules block bitbucket.org
// pages (and MV3 content scripts, which use the page origin) from calling
// localhost.

// Classic scripts, so the manifest does not need "type": "module". They define
// globalThis.DiffySnapshot (pure logic) and globalThis.DiffyCapture (the 15
// minute capture and delivery job). lib/log.js defines globalThis.DiffyLog.
importScripts("lib/log.js", "lib/snapshot.js", "capture.js");

const log = globalThis.DiffyLog;

const PORTS = Array.from({ length: 20 }, (_, i) => 63342 + i); // 63342-63361
const PING_TIMEOUT_MS = 300;

function endpointUrl(port) {
  return `http://127.0.0.1:${port}/api/diffy/open`;
}

// Returns the parsed ping reply only for a port answering with this plugin's
// ping response (so other JetBrains IDEs in the same range are skipped), else
// null. The reply carries the configured workspace slug for this extension.
async function pingInfo(port) {
  try {
    const response = await fetch(endpointUrl(port), {
      signal: AbortSignal.timeout(PING_TIMEOUT_MS),
    });
    if (!response.ok) {
      log.debug("ping", "Port answered with an HTTP error", { port, status: response.status });
      return null;
    }
    const body = await response.json();
    if (!body || body.plugin !== "diffy") {
      log.debug("ping", "Port is not the diffy plugin", { port });
      return null;
    }
    if (!body.workspace) {
      // Chrome omits the Origin header on GET requests covered by host
      // permissions, so the ping cannot identify this extension. Ask again
      // over POST, which does carry the Origin header.
      const workspace = await fetchWorkspace(port);
      if (workspace) {
        body.workspace = workspace;
      }
    }
    const workspaceReported = Boolean(body.workspace);
    log.info("ping", "Found a diffy IDE", { port, version: body.version, workspaceReported });
    if (!workspaceReported) {
      log.warn("ping", "Ping reply has no workspace", {
        port,
        extensionId: chrome.runtime.id,
        hint: "The IDE has no workspace configured, or this extension ID does not match the plugin's allowed ID",
      });
    }
    return body;
  } catch (error) {
    log.debug("ping", "Ping failed", { port, reason: error && error.name });
    return null;
  }
}

// Returns the workspace slug from POST /api/diffy/workspace, or null when the
// IDE has none configured, does not recognize this extension, or is an older
// plugin without the endpoint.
async function fetchWorkspace(port) {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/api/diffy/workspace`, {
      method: "POST",
      signal: AbortSignal.timeout(PING_TIMEOUT_MS),
    });
    if (!response.ok) {
      log.debug("ping", "Workspace request answered with an HTTP error", { port, status: response.status });
      return null;
    }
    const body = await response.json();
    return body && typeof body.workspace === "string" && body.workspace ? body.workspace : null;
  } catch (error) {
    log.debug("ping", "Workspace request failed", { port, reason: error && error.name });
    return null;
  }
}

async function getCachedPort() {
  try {
    const stored = await chrome.storage.session.get("port");
    return stored.port;
  } catch (error) {
    log.debug("port", "Could not read the cached port", { reason: error && error.name });
    return undefined;
  }
}

async function setCachedPort(port) {
  try {
    await chrome.storage.session.set({ port });
  } catch (error) {
    // A storage failure only costs us a rescan next time.
    log.debug("port", "Could not cache the port", { reason: error && error.name });
  }
}

async function clearCachedPort() {
  try {
    await chrome.storage.session.remove("port");
  } catch (error) {
    // A stale value will be revalidated by ping on the next lookup.
    log.debug("port", "Could not clear the cached port", { reason: error && error.name });
  }
}

// Returns { port, info } for the running IDE (info is the ping reply), or null.
async function findIde() {
  const cached = await getCachedPort();
  if (cached) {
    const info = await pingInfo(cached);
    if (info) {
      log.debug("port", "Cached port still answers", { port: cached });
      return { port: cached, info };
    }
    log.debug("port", "Cached port no longer answers", { port: cached });
    await clearCachedPort();
  }

  const startedAt = Date.now();
  log.info("port", "Scanning ports 63342-63361");
  for (const port of PORTS) {
    const info = await pingInfo(port);
    if (info) {
      await setCachedPort(port);
      return { port, info };
    }
  }
  log.warn("port", "No diffy IDE answered on ports 63342-63361", {
    elapsedMs: Date.now() - startedAt,
    hint: "Is the IDE running with the built-in server enabled and the diffy plugin installed?",
  });
  return null;
}

async function findPort() {
  const ide = await findIde();
  return ide ? ide.port : null;
}

const capture = DiffyCapture.createCapture({ findIde, clearCachedPort, log });

log.info("worker", "Service worker started", {
  extensionVersion: chrome.runtime.getManifest().version,
  extensionId: chrome.runtime.id,
  expectedOrigin: `chrome-extension://${chrome.runtime.id}`,
});

// Listeners must be registered synchronously at top level, or a wake-up event
// can be missed. Alarms can be dropped on browser restart, hence every entry
// point calls ensureAlarm.
function logFailure(scope, message) {
  return (error) => log.error(scope, message, { reason: error && error.message });
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === DiffyCapture.CAPTURE_ALARM) {
    log.info("alarm", "Capture alarm fired", { scheduledTime: alarm.scheduledTime });
    capture.runCycle({ trigger: "alarm" }).catch(logFailure("alarm", "Capture cycle failed"));
  }
});
chrome.runtime.onInstalled.addListener(() => {
  log.info("worker", "onInstalled");
  capture.ensureAlarm().catch(logFailure("alarm", "Could not ensure the alarm"));
});
chrome.runtime.onStartup.addListener(() => {
  log.info("worker", "onStartup");
  capture.ensureAlarm().catch(logFailure("alarm", "Could not ensure the alarm"));
  // One delivery attempt on browser start for a snapshot cached while the IDE was down.
  capture.deliver().catch(logFailure("deliver", "Startup delivery failed"));
});
capture.ensureAlarm().catch(logFailure("alarm", "Could not ensure the alarm"));

async function openPullRequest(msg) {
  const startedAt = Date.now();
  log.info("open", "Open requested", { workspace: msg.workspace, repo: msg.repo, pr: msg.pr });
  const port = await findPort();
  if (!port) {
    log.warn("open", "IntelliJ not running");
    return { ok: false, message: "IntelliJ not running" };
  }

  try {
    const response = await fetch(endpointUrl(port), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      // Forward only the three contract fields, never the raw message.
      body: JSON.stringify({
        workspace: msg.workspace,
        repo: msg.repo,
        pr: msg.pr,
      }),
    });
    const body = await response.json();
    log.info("open", "Open answered", {
      port,
      status: response.status,
      replyStatus: typeof body.status === "string" ? body.status : null,
      elapsedMs: Date.now() - startedAt,
    });
    return {
      ok: body.status === "accepted",
      message: body.message ?? body.status,
    };
  } catch (error) {
    // The IDE may have been quit between the ping and the POST; drop the cache
    // so the next click rescans.
    log.warn("open", "IntelliJ not reachable", { port, reason: error && error.name });
    await clearCachedPort();
    return { ok: false, message: "IntelliJ not reachable" };
  }
}

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  log.debug("message", "Received a message", { type: msg && msg.type });
  if (msg && msg.type === "syncNow") {
    // The floating button on bitbucket.org pages: capture and deliver now, restart the 15 minute timer.
    (async () => {
      reply(await capture.syncNow());
    })().catch((error) => {
      log.error("sync", "Manual sync failed", { reason: error && error.message });
      reply({ ok: false, state: "error", message: "Sync failed" });
    });
    return true;
  }
  if (msg && msg.type === "getLog") {
    log.getEntries().then(reply).catch(() => reply([]));
    return true;
  }
  if (msg && msg.type === "clearLog") {
    log.clear().then(() => reply(true)).catch(() => reply(false));
    return true;
  }
  if (msg && msg.type === "captureNow") {
    // Debug hook: runs one cycle now and reports the status record. Takes no parameters.
    (async () => {
      await capture.runCycle({ force: true, trigger: "manual" });
      await capture.deliver();
      const stored = await chrome.storage.local.get(DiffyCapture.STATUS_KEY);
      reply(stored[DiffyCapture.STATUS_KEY] || null);
    })().catch((error) => {
      log.error("capture", "captureNow failed", { reason: error && error.message });
      reply(null);
    });
    return true;
  }
  if (!msg || msg.type !== "open") {
    return false;
  }
  (async () => {
    reply(await openPullRequest(msg));
  })();
  // Keep the reply channel open for the async work.
  return true;
});
