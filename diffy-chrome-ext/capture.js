// Background capture and delivery (loaded by background.js with importScripts).
//
// Every 15 minutes the service worker reads the user's reviewing pull requests
// and recent notifications from bitbucket.org (read-only, using the browser
// session), then POSTs one snapshot to the local IntelliJ plugin. When IntelliJ
// is not listening, the latest snapshot stays in chrome.storage.local and is
// resent on a later cycle.
//
// This file only reads. It never marks notifications as seen.
(function (root) {
  const CAPTURE_ALARM = "diffy-capture";
  const CAPTURE_PERIOD_MINUTES = 15;
  const PENDING_KEY = "diffyPendingSnapshot";
  const STATUS_KEY = "diffyCaptureStatus";
  const LOCK_KEY = "diffyCaptureLockUntil";
  const WORKSPACE_KEY = "diffyWorkspaceSlug";
  const REJECT_KEY = "diffyRejectCount";
  const LOCK_MS = 14 * 60 * 1000;
  const SNAPSHOT_PATH = "/api/diffy/snapshot";
  const DELIVERY_TIMEOUT_MS = 10000;
  const MAX_REJECTIONS = 3;
  const GROUP_CONCURRENCY = 3;
  const GROUP_MAX_PAGES = 10;
  const BITBUCKET = "https://bitbucket.org";
  const FORBIDDEN_TEXT = ["bb_session", "cloud.session.token", "csrftoken"];

  const NOOP_LOG = { debug() {}, info() {}, warn() {}, error() {} };

  function resolveLog(log) {
    return log || root.DiffyLog || NOOP_LOG;
  }

  // The path without its query string, so logs never carry query values.
  function pathOnly(path) {
    const index = path.indexOf("?");
    return index === -1 ? path : path.slice(0, index);
  }

  class AuthError extends Error {}
  class RateLimitError extends Error {}
  class HttpError extends Error {
    constructor(status) {
      super(`HTTP ${status}`);
      this.status = status;
    }
  }
  class GraphqlError extends Error {}

  // ---- transports ---------------------------------------------------------

  function requestHeaders(hasBody) {
    const headers = { accept: "application/json", "x-requested-with": "XMLHttpRequest" };
    if (hasBody) {
      headers["content-type"] = "application/json";
    }
    return headers;
  }

  async function workerTransport(fetchImpl, { path, method, body }) {
    const response = await fetchImpl(BITBUCKET + path, {
      method,
      credentials: "include",
      headers: requestHeaders(Boolean(body)),
      body,
    });
    return {
      status: response.status,
      text: await response.text(),
      quotaRemaining: response.headers.get("x-usage-quota-remaining"),
      requestCost: response.headers.get("x-usage-request-cost"),
    };
  }

  // Runs inside a bitbucket.org tab (serialized by executeScript, so self-contained).
  async function pageFetch(url, method, body, headers) {
    const response = await fetch(url, { method, credentials: "include", headers, body });
    return {
      status: response.status,
      text: await response.text(),
      quotaRemaining: response.headers.get("x-usage-quota-remaining"),
      requestCost: response.headers.get("x-usage-request-cost"),
    };
  }

  async function tabTransport({ path, method, body }) {
    const tabs = await chrome.tabs.query({ url: "https://bitbucket.org/*" });
    if (tabs.length === 0) {
      throw new Error("No bitbucket.org tab open");
    }
    const results = await chrome.scripting.executeScript({
      target: { tabId: tabs[0].id },
      func: pageFetch,
      args: [BITBUCKET + path, method, body || null, requestHeaders(Boolean(body))],
    });
    return results[0].result;
  }

  // ---- Bitbucket client ---------------------------------------------------

  function createBitbucketClient({ fetchImpl = fetch, tabImpl = tabTransport, onQuota = () => {}, log } = {}) {
    const logger = resolveLog(log);
    let preferred = "worker";

    function check(result, request, transport, startedAt) {
      onQuota(result.quotaRemaining, result.requestCost);
      logger.debug("bitbucket", "response", {
        method: request.method,
        path: pathOnly(request.path),
        transport,
        status: result.status,
        elapsedMs: Date.now() - startedAt,
      });
      if (result.status === 401 || result.status === 403) {
        logger.warn("bitbucket", "Not signed in to Bitbucket", { path: pathOnly(request.path), status: result.status });
        throw new AuthError("Not signed in to Bitbucket");
      }
      if (result.status === 429) {
        logger.warn("bitbucket", "Rate limited", {
          path: pathOnly(request.path),
          quotaRemaining: Number(result.quotaRemaining) || null,
          requestCost: Number(result.requestCost) || null,
        });
        throw new RateLimitError("Rate limited");
      }
      if (result.status < 200 || result.status >= 300) {
        logger.warn("bitbucket", "Unexpected HTTP status", { path: pathOnly(request.path), status: result.status });
        throw new HttpError(result.status);
      }
      return JSON.parse(result.text);
    }

    async function send(request) {
      const startedAt = Date.now();
      if (preferred === "tab") {
        return check(await tabImpl(request), request, "tab", startedAt);
      }
      let workerFailure;
      try {
        return check(await workerTransport(fetchImpl, request), request, "worker", startedAt);
      } catch (error) {
        if (error instanceof RateLimitError || error instanceof HttpError) {
          throw error;
        }
        workerFailure = error;
      }
      // Auth or network failure from the worker: retry inside a Bitbucket tab, if one is open.
      logger.warn("bitbucket", "Worker request failed, trying a bitbucket.org tab", {
        path: pathOnly(request.path),
        workerFailure: workerFailure && workerFailure.name,
      });
      try {
        const data = check(await tabImpl(request), request, "tab", Date.now());
        if (preferred !== "tab") {
          logger.info("bitbucket", "Switched to the bitbucket.org tab transport");
        }
        preferred = "tab";
        return data;
      } catch (tabError) {
        if (tabError instanceof AuthError || tabError instanceof RateLimitError || tabError instanceof HttpError) {
          throw tabError;
        }
        logger.warn("bitbucket", "Tab transport failed", {
          path: pathOnly(request.path),
          reason: tabError && tabError.message,
        });
        if (workerFailure instanceof AuthError) {
          throw workerFailure;
        }
        throw new Error("Bitbucket not reachable");
      }
    }

    function assertPath(path) {
      if (!path.startsWith("/!api/") && !path.startsWith("/gateway/api/")) {
        throw new Error("Path not allowed");
      }
    }

    return {
      async getJson(path) {
        assertPath(path);
        return send({ path, method: "GET", body: null });
      },
      async postGraphql(operationName, query, variables) {
        const path = `/gateway/api/graphql?q=${encodeURIComponent(operationName)}`;
        assertPath(path);
        const data = await send({ path, method: "POST", body: JSON.stringify({ query, variables }) });
        if (data.errors && data.errors.length > 0) {
          throw new GraphqlError("GraphQL returned errors");
        }
        return data.data;
      },
    };
  }

  // ---- storage ------------------------------------------------------------

  const chromeStorage = {
    async get(key) {
      const stored = await chrome.storage.local.get(key);
      return stored[key];
    },
    async set(key, value) {
      await chrome.storage.local.set({ [key]: value });
    },
    async remove(key) {
      await chrome.storage.local.remove(key);
    },
  };

  // ---- capture ------------------------------------------------------------

  async function mapLimit(items, limit, worker) {
    const results = [];
    let next = 0;
    async function run() {
      while (next < items.length) {
        const index = next++;
        results[index] = await worker(items[index]);
      }
    }
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
    return results;
  }

  async function capturePullRequests(client, S, workspace, user) {
    const pullRequests = [];
    let pages = 0;
    let total = 0;
    let truncated = false;
    for (let page = 1; page <= S.PR_MAX_PAGES; page++) {
      const data = await client.getJson(S.buildPullRequestUrl({ workspace, userUuid: user.uuid, page }));
      pages++;
      total = data.size || total;
      for (const raw of data.values || []) {
        pullRequests.push(S.mapPullRequest(raw, workspace, user.uuid));
      }
      if (!data.next) {
        break;
      }
      truncated = page === S.PR_MAX_PAGES;
    }
    return { pullRequests, pages, total, truncated };
  }

  async function captureNotifications(client, S, workspaceAri, since) {
    const filter = { categoryFilter: S.NOTIFICATION_CATEGORIES[0], afterTimestamp: since };
    const events = [];
    let skipped = 0;
    let groupsFetched = 0;
    let truncated = false;
    const groups = [];
    let after = null;
    for (let page = 1; page <= S.NOTIFICATION_MAX_PAGES; page++) {
      const data = await client.postGraphql("DiffyNotificationFeed", S.buildNotificationQuery(), {
        first: S.NOTIFICATION_PAGE_SIZE,
        after,
        filter,
        ari: workspaceAri,
      });
      const feed = data.notifications.notificationFeed;
      groups.push(...(feed.nodes || []));
      if (!feed.pageInfo || !feed.pageInfo.hasNextPage) {
        break;
      }
      after = feed.pageInfo.endCursor;
      truncated = page === S.NOTIFICATION_MAX_PAGES;
    }
    groupsFetched = groups.length;

    const keep = (notification, group) => {
      const event = S.mapNotificationEvent(notification, group);
      if (event.category === null) {
        skipped++;
      } else {
        events.push(event);
      }
    };

    const bitbucketGroups = [];
    for (const group of groups) {
      if (S.classifyNotification(group.headNotification) === null) {
        skipped++;
      } else {
        bitbucketGroups.push(group);
        keep(group.headNotification, group);
      }
    }

    await mapLimit(bitbucketGroups.filter((group) => group.groupSize > 1), GROUP_CONCURRENCY, async (group) => {
      let groupAfter = null;
      for (let page = 1; page <= GROUP_MAX_PAGES; page++) {
        const data = await client.postGraphql("DiffyNotificationGroup", S.buildNotificationGroupQuery(), {
          groupId: group.groupId,
          first: S.NOTIFICATION_PAGE_SIZE,
          after: groupAfter,
          filter,
          ari: workspaceAri,
        });
        const result = data.notifications.notificationGroup;
        for (const node of result.nodes || []) {
          keep(node, group);
        }
        if (!result.pageInfo || !result.pageInfo.hasNextPage) {
          break;
        }
        groupAfter = result.pageInfo.endCursor;
      }
    });

    return { events: S.dedupeEvents(events), skipped, groupsFetched, truncated };
  }

  async function captureSnapshot({ client, S, workspace, now }) {
    const user = await client.getJson("/!api/2.0/user");
    const workspaceInfo = await client.getJson(`/!api/2.0/workspaces/${workspace}`);
    const workspaceAri = `ari:cloud:bitbucket::workspace/${S.stripBraces(workspaceInfo.uuid)}`;
    const since = new Date(now - S.NOTIFICATION_LOOKBACK_DAYS * 24 * 60 * 60 * 1000).toISOString();

    const prs = await capturePullRequests(client, S, workspace, user);
    const errors = [];
    let notifications = { events: [], skipped: 0, groupsFetched: 0, truncated: false };
    try {
      notifications = await captureNotifications(client, S, workspaceAri, since);
    } catch (error) {
      if (error instanceof GraphqlError) {
        errors.push("Notifications unavailable");
      } else {
        throw error;
      }
    }

    return S.buildSnapshot({
      meta: {
        capturedAt: new Date(now).toISOString(),
        workspaces: [workspace],
        user: { uuid: S.stripBraces(user.uuid), accountId: user.account_id, displayName: user.display_name },
        pullRequestPagesFetched: prs.pages,
        pullRequestsTotalReported: prs.total,
        notificationsSince: since,
        notificationGroupsFetched: notifications.groupsFetched,
        notificationEventsStored: notifications.events.length,
        skippedNonBitbucket: notifications.skipped,
        truncated: { pullRequests: prs.truncated, notifications: notifications.truncated },
        errors,
      },
      pullRequests: prs.pullRequests,
      notificationEvents: notifications.events,
    });
  }

  // ---- delivery -----------------------------------------------------------

  async function deliverPhase({ storage, findIde, clearCachedPort, fetchImpl = fetch, ide, log }) {
    const logger = resolveLog(log);
    const pending = await storage.get(PENDING_KEY);
    if (!pending) {
      logger.info("deliver", "Nothing pending to deliver");
      return { delivered: false, reason: "nothing-pending" };
    }
    const found = ide === undefined ? await findIde() : ide;
    if (!found) {
      logger.warn("deliver", "IntelliJ not running; the snapshot stays cached");
      return { delivered: false, error: "IntelliJ not running" };
    }

    const text = JSON.stringify(pending);
    if (FORBIDDEN_TEXT.some((word) => text.includes(word))) {
      await storage.remove(PENDING_KEY);
      logger.warn("deliver", "Snapshot refused: contains credentials");
      return { delivered: false, error: "Snapshot refused: contains credentials" };
    }

    const startedAt = Date.now();
    const counts = {
      port: found.port,
      snapshotBytes: text.length,
      pullRequests: Array.isArray(pending.pullRequests) ? pending.pullRequests.length : 0,
      notificationEvents: Array.isArray(pending.notificationEvents) ? pending.notificationEvents.length : 0,
    };
    logger.info("deliver", "Posting the snapshot to IntelliJ", counts);

    let reply;
    let httpStatus = null;
    try {
      const response = await fetchImpl(`http://127.0.0.1:${found.port}${SNAPSHOT_PATH}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: text,
        signal: AbortSignal.timeout(DELIVERY_TIMEOUT_MS),
      });
      httpStatus = response.status;
      if (!response.ok) {
        logger.warn("deliver", "IntelliJ answered with an HTTP error status", {
          port: found.port,
          status: httpStatus,
          elapsedMs: Date.now() - startedAt,
          hint: "404 usually means the extension ID differs from the plugin's allowed ID, or the plugin is outdated",
        });
      }
      reply = await response.json();
    } catch (error) {
      logger.warn("deliver", "Could not deliver the snapshot", {
        port: found.port,
        status: httpStatus,
        reason: error && error.name,
        elapsedMs: Date.now() - startedAt,
      });
      await clearCachedPort();
      return { delivered: false, error: "IntelliJ not reachable", httpStatus, port: found.port };
    }

    logger.info("deliver", "IntelliJ replied", {
      port: found.port,
      status: httpStatus,
      replyStatus: reply && typeof reply.status === "string" ? reply.status : null,
      elapsedMs: Date.now() - startedAt,
    });

    if (reply && reply.status === "accepted") {
      await storage.remove(PENDING_KEY);
      await storage.remove(REJECT_KEY);
      return { delivered: true, httpStatus, port: found.port };
    }

    // A rejected payload must not block newer snapshots forever.
    const capturedAt = pending.meta && pending.meta.capturedAt;
    const previous = await storage.get(REJECT_KEY);
    const count = previous && previous.capturedAt === capturedAt ? previous.count + 1 : 1;
    const message = (reply && typeof reply.message === "string" && reply.message) || "Rejected by IntelliJ";
    logger.warn("deliver", "IntelliJ rejected the snapshot", { message, rejectionCount: count });
    if (count >= MAX_REJECTIONS) {
      await storage.remove(PENDING_KEY);
      await storage.remove(REJECT_KEY);
      logger.warn("deliver", "Dropped the snapshot after repeated rejections", { maxRejections: MAX_REJECTIONS });
    } else {
      await storage.set(REJECT_KEY, { capturedAt, count });
    }
    return { delivered: false, error: message, httpStatus, port: found.port };
  }

  // ---- sync summary -------------------------------------------------------

  // Turns the status record of a finished manual sync into { ok, state, message }.
  // Errors only count when they belong to this run (lastRunAt >= startedAtIso).
  function summarizeSync(status, startedAtIso) {
    if (!status || !status.lastRunAt || status.lastRunAt < startedAtIso) {
      return { ok: false, state: "skipped", message: "Sync did not run" };
    }
    if (status.lastCaptureError) {
      return { ok: false, state: "capture-failed", message: status.lastCaptureError };
    }
    if (status.lastDeliveryError) {
      return { ok: false, state: "delivery-failed", message: status.lastDeliveryError };
    }
    return { ok: true, state: "delivered", message: "Synced" };
  }

  // ---- cycle --------------------------------------------------------------

  function createCapture({
    findIde,
    clearCachedPort,
    storage = chromeStorage,
    fetchImpl = fetch,
    snapshotLib = root.DiffySnapshot,
    now = () => Date.now(),
    log,
  }) {
    const S = snapshotLib;
    const logger = resolveLog(log);
    let quota = {};
    let syncInFlight = null;

    async function updateStatus(changes) {
      const current = (await storage.get(STATUS_KEY)) || {};
      const pending = Boolean(await storage.get(PENDING_KEY));
      await storage.set(STATUS_KEY, { ...current, ...changes, ...quota, pending });
    }

    async function ensureAlarm() {
      const existing = await chrome.alarms.get(CAPTURE_ALARM);
      if (!existing) {
        chrome.alarms.create(CAPTURE_ALARM, { delayInMinutes: 0.1, periodInMinutes: CAPTURE_PERIOD_MINUTES });
        logger.info("alarm", "Created the capture alarm", { periodInMinutes: CAPTURE_PERIOD_MINUTES });
      } else {
        logger.info("alarm", "Capture alarm already exists", { periodInMinutes: existing.periodInMinutes });
      }
    }

    // Restarts the 15 minute countdown from now.
    async function resetAlarm() {
      try {
        await chrome.alarms.clear(CAPTURE_ALARM);
        chrome.alarms.create(CAPTURE_ALARM, {
          delayInMinutes: CAPTURE_PERIOD_MINUTES,
          periodInMinutes: CAPTURE_PERIOD_MINUTES,
        });
        logger.info("alarm", "Timer reset", { nextInMinutes: CAPTURE_PERIOD_MINUTES });
      } catch (error) {
        logger.warn("alarm", "Could not reset the timer", { reason: error && error.message });
      }
    }

    async function capturePhase(ide) {
      if (ide && ide.info && typeof ide.info.workspace === "string" && S.isValidWorkspace(ide.info.workspace)) {
        await storage.set(WORKSPACE_KEY, ide.info.workspace);
      }
      const workspace = await storage.get(WORKSPACE_KEY);
      if (!workspace) {
        const message = ide
          ? "IntelliJ has no Bitbucket workspace configured. Set the workspace slug in the Diffy settings in IntelliJ, then sync again."
          : "No IntelliJ with the Diffy plugin was found. Start IntelliJ with the Diffy plugin, then sync again.";
        logger.warn("capture", "Waiting for IntelliJ to report a workspace", {
          idePresent: Boolean(ide),
          hint: "No workspace in the ping reply: configure the workspace in the plugin, or check the extension ID",
        });
        await updateStatus({ lastCaptureError: message });
        return;
      }
      logger.info("capture", "Capture started", { workspace });
      const startedAt = Date.now();
      const client = createBitbucketClient({
        fetchImpl,
        log: logger,
        onQuota: (remaining, cost) => {
          quota = { quotaRemaining: Number(remaining) || null, lastRequestCost: Number(cost) || null };
        },
      });
      try {
        const snapshot = await captureSnapshot({ client, S, workspace, now: now() });
        await storage.set(PENDING_KEY, snapshot);
        const meta = snapshot.meta || {};
        logger.info("capture", "Capture finished", {
          pullRequests: Array.isArray(snapshot.pullRequests) ? snapshot.pullRequests.length : 0,
          notificationEvents: Array.isArray(snapshot.notificationEvents) ? snapshot.notificationEvents.length : 0,
          skippedNonBitbucket: meta.skippedNonBitbucket,
          pullRequestPagesFetched: meta.pullRequestPagesFetched,
          elapsedMs: Date.now() - startedAt,
        });
        await updateStatus({ lastCaptureAt: new Date(now()).toISOString(), lastCaptureError: null });
      } catch (error) {
        logger.warn("capture", "Capture failed", {
          errorName: error && error.name,
          message: error instanceof Error ? error.message : "Capture failed",
          elapsedMs: Date.now() - startedAt,
        });
        await updateStatus({ lastCaptureError: error instanceof Error ? error.message : "Capture failed" });
      }
    }

    async function deliver(ide) {
      const result = await deliverPhase({ storage, findIde, clearCachedPort, fetchImpl, ide, log: logger });
      if (result.delivered) {
        await updateStatus({
          lastDeliveredAt: new Date(now()).toISOString(),
          lastDeliveryError: null,
          lastDeliveryHttpStatus: result.httpStatus,
          lastDeliveryPort: result.port,
        });
      } else if (result.error) {
        await updateStatus({
          lastDeliveryError: result.error,
          lastDeliveryHttpStatus: result.httpStatus === undefined ? null : result.httpStatus,
          lastDeliveryPort: result.port === undefined ? null : result.port,
        });
      }
      return result;
    }

    async function runCycle({ force = false, trigger = "alarm" } = {}) {
      const lockUntil = (await storage.get(LOCK_KEY)) || 0;
      if (lockUntil > now()) {
        if (!force) {
          logger.warn("cycle", "Cycle skipped: another cycle holds the lock", { remainingMs: lockUntil - now(), trigger });
          return null;
        }
        logger.warn("cycle", "Overriding a held lock for a manual sync", { remainingMs: lockUntil - now() });
      }
      const startedAt = Date.now();
      logger.info("cycle", "Cycle started", { trigger });
      await storage.set(LOCK_KEY, now() + LOCK_MS);
      try {
        const ide = await findIde();
        await updateStatus({ lastCaptureTrigger: trigger });
        await capturePhase(ide);
        await deliver(ide);
        await updateStatus({ lastRunAt: new Date(now()).toISOString() });
      } finally {
        await storage.remove(LOCK_KEY);
        logger.info("cycle", "Cycle finished", { trigger, elapsedMs: Date.now() - startedAt });
      }
      return storage.get(STATUS_KEY);
    }

    // Manual sync: restarts the 15 minute timer, forces a cycle, retries delivery, restarts the timer again.
    function syncNow() {
      if (syncInFlight) {
        logger.info("sync", "Sync already running; sharing the running one");
        return syncInFlight;
      }
      syncInFlight = (async () => {
        logger.info("sync", "Manual sync requested");
        const startedAtIso = new Date(now()).toISOString();
        const keepAlive = setInterval(() => {
          try {
            chrome.runtime.getPlatformInfo(() => {});
          } catch (error) {
            // Keep-alive is best effort.
          }
        }, 20000);
        try {
          await resetAlarm();
          const status = await runCycle({ force: true, trigger: "manual" });
          await deliver(undefined);
          const finalStatus = (await storage.get(STATUS_KEY)) || status;
          const summary = summarizeSync(finalStatus, startedAtIso);
          logger.info("sync", "Manual sync finished", { ok: summary.ok, state: summary.state });
          return summary;
        } finally {
          clearInterval(keepAlive);
          await resetAlarm();
        }
      })().finally(() => {
        syncInFlight = null;
      });
      return syncInFlight;
    }

    return { ensureAlarm, runCycle, syncNow, deliver: () => deliver(undefined) };
  }

  const api = {
    CAPTURE_ALARM,
    CAPTURE_PERIOD_MINUTES,
    PENDING_KEY,
    STATUS_KEY,
    AuthError,
    RateLimitError,
    HttpError,
    createBitbucketClient,
    captureSnapshot,
    deliverPhase,
    createCapture,
    summarizeSync,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    root.DiffyCapture = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
