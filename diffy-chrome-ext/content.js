// Content script for bitbucket.org pages.
//
// On pull request list pages, finds pull request links by their URL structure
// (never by CSS class, which Bitbucket changes between frontend releases) and
// injects an icon-only link (the diffy plugin glyph) next to the first
// anchor for each pull request.
//
// On pull request detail and sub-tab pages (overview, diff, commits, etc.),
// parses workspace, repo, and PR id from the browser URL and injects the same
// icon beside the PR title in the page header.
//
// A floating "Diffy sync" button (bottom left, signed-in users only) runs a capture
// and delivery now instead of waiting for the 15 minute timer.
//
// Clicking the icon asks the background service worker to forward the request
// to a running IntelliJ IDE's built-in HTTP server.

// Matches /<workspace>/<repo>/pull-requests/<n> with an optional trailing
// slash. Deliberately skips sub-pages such as /pull-requests/123/diff.
const PR_PATH = /^\/([^/]+)\/([^/]+)\/pull-requests\/(\d+)\/?$/;

// Matches the current PR page and sub-tabs (/overview, /diff, /commits, …).
const PR_DETAIL_PATH = /^\/([^/]+)\/([^/]+)\/pull-requests\/(\d+)/;

const TITLE_SELECTORS = [
  '[data-testid="pr-header"] h1',
  'h1[data-testid="pull-request-title"]',
  '[data-qa="pr-header-title"]',
  "main h1",
  '[role="main"] h1',
];

const LIST_PAGE_HEADING = "Pull requests";

// The plugin glyph, bundled with the extension and exposed via
// web_accessible_resources so a content script can load it by extension URL.
const ICON_URL = chrome.runtime.getURL("icons/icon48.png");
const RETRY_RESET_MS = 4000;

function parsePrFromPathname(pathname) {
  const match = pathname.match(PR_DETAIL_PATH);
  if (!match) {
    return null;
  }
  const [, workspace, repo, pr] = match;
  return { workspace, repo, pr };
}

function prKey(workspace, repo, pr) {
  return `${workspace}/${repo}/${pr}`;
}

function isVisible(element) {
  if (!element.isConnected) {
    return false;
  }
  const style = getComputedStyle(element);
  if (style.display === "none" || style.visibility === "hidden") {
    return false;
  }
  const rect = element.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
}

function findPrTitleElement() {
  for (const selector of TITLE_SELECTORS) {
    for (const element of document.querySelectorAll(selector)) {
      if (isVisible(element) && element.textContent.trim()) {
        return element;
      }
    }
  }

  for (const h1 of document.querySelectorAll("h1")) {
    if (!isVisible(h1)) {
      continue;
    }
    const text = h1.textContent.replace(/\s+/g, " ").trim();
    if (text && text !== LIST_PAGE_HEADING) {
      return h1;
    }
  }

  return null;
}

function createOpenLink(workspace, repo, pr) {
  const defaultTitle = `Open pull request #${pr} in IntelliJ (${workspace}/${repo})`;

  const link = document.createElement("a");
  link.href = "#";
  link.title = defaultTitle;
  link.setAttribute("aria-label", defaultTitle);
  link.style.display = "inline-flex";
  link.style.alignItems = "center";
  link.style.marginLeft = "8px";
  link.style.verticalAlign = "middle";
  link.style.lineHeight = "0";
  link.dataset.diffyInjected = "1";

  const icon = document.createElement("img");
  icon.src = ICON_URL;
  icon.alt = "Open in IntelliJ";
  icon.width = 16;
  icon.height = 16;
  icon.style.display = "block";
  icon.style.transition = "opacity 120ms ease";
  link.appendChild(icon);

  let inFlight = false;

  link.addEventListener("click", async (event) => {
    event.preventDefault();
    if (inFlight) {
      return;
    }
    inFlight = true;
    // Dim the icon while the open is in flight; the title carries the state so
    // the icon-only link stays accessible.
    icon.style.opacity = "0.4";
    link.title = `Opening pull request #${pr} in IntelliJ…`;

    let result;
    try {
      result = await chrome.runtime.sendMessage({
        type: "open",
        workspace,
        repo,
        pr: Number(pr),
      });
    } catch (error) {
      result = { ok: false, message: "IntelliJ not running" };
    }

    if (!result) {
      // sendMessage can resolve to undefined when the worker closed the
      // channel without replying (e.g. it was terminated mid-flight).
      result = { ok: false, message: "IntelliJ not running" };
    }

    icon.style.opacity = result.ok ? "1" : "0.5";
    link.title = result.ok
      ? `Opened pull request #${pr} in IntelliJ`
      : result.message || "Could not open in IntelliJ";

    // Re-enable after a short delay so the user can retry.
    window.setTimeout(() => {
      inFlight = false;
      icon.style.opacity = "1";
      link.title = defaultTitle;
    }, RETRY_RESET_MS);
  });

  return link;
}

function addPullRequestTitleLink() {
  const context = parsePrFromPathname(location.pathname);
  if (!context) {
    return;
  }

  const { workspace, repo, pr } = context;
  const key = prKey(workspace, repo, pr);

  const title = findPrTitleElement();
  if (!title) {
    return;
  }

  const existing = title.querySelector('[data-diffy-injected="1"]');
  if (existing) {
    if (existing.dataset.diffyPrKey === key) {
      return;
    }
    existing.remove();
  }

  const link = createOpenLink(workspace, repo, pr);
  link.dataset.diffyPrKey = key;
  title.append(link);
}

function addLinks() {
  if (parsePrFromPathname(location.pathname)) {
    return;
  }

  // De-duplicate per pull request within this pass: a single row can hold
  // several anchors to the same PR (title, number, avatar). We inject next to
  // the first matching anchor only.
  const seen = new Set();

  for (const anchor of document.querySelectorAll('a[href*="/pull-requests/"]')) {
    if (anchor.dataset.diffy || anchor.dataset.diffyInjected) {
      continue;
    }

    let pathname;
    try {
      pathname = new URL(anchor.href).pathname;
    } catch (error) {
      continue;
    }

    const match = pathname.match(PR_PATH);
    if (!match) {
      continue;
    }

    // Mark before injecting so a re-entrant observer callback cannot
    // double-process this anchor.
    anchor.dataset.diffy = "1";

    const [, workspace, repo, pr] = match;
    const key = prKey(workspace, repo, pr);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);

    const link = createOpenLink(workspace, repo, pr);
    anchor.after(link);
  }
}

// ---- Sync button ----------------------------------------------------------
//
// A floating "Diffy sync" button at the bottom left of every bitbucket.org page,
// shown only while the user is signed in. Clicking it asks the service worker to
// capture and deliver now, which also restarts the 15 minute timer.

const SYNC_HOST_SELECTOR = '[data-diffy-sync="1"]';
const SIGNED_IN_TTL_MS = 5 * 60 * 1000;
const SYNC_REPLY_TIMEOUT_MS = 60000;
const SYNC_LABEL = "Diffy sync";

let signedIn = false;
let signedInCheckedAt = 0;
let signedInCheck = null;

async function checkSignedIn() {
  let status = null;
  try {
    const response = await fetch("/!api/2.0/user", {
      credentials: "include",
      headers: { accept: "application/json", "x-requested-with": "XMLHttpRequest" },
    });
    status = response.status;
    if (!response.ok) {
      return false;
    }
    const body = await response.json();
    return Boolean(body) && typeof body.uuid === "string";
  } catch (error) {
    return false;
  } finally {
    console.debug("[diffy] signed-in check", { status });
  }
}

// Resolves the cached sign-in state, re-checking at most every SIGNED_IN_TTL_MS.
async function isSignedIn(force) {
  if (!force && signedInCheckedAt > 0 && Date.now() - signedInCheckedAt < SIGNED_IN_TTL_MS) {
    return signedIn;
  }
  if (!signedInCheck) {
    signedInCheck = checkSignedIn()
      .then((result) => {
        signedIn = result;
        signedInCheckedAt = Date.now();
        return result;
      })
      .finally(() => {
        signedInCheck = null;
      });
  }
  return signedInCheck;
}

function removeSyncButton() {
  for (const host of document.querySelectorAll(SYNC_HOST_SELECTOR)) {
    host.remove();
  }
}

function createSyncButton() {
  const host = document.createElement("div");
  host.dataset.diffySync = "1";
  host.style.cssText =
    "all: initial; position: fixed; left: 12px; bottom: 12px; z-index: 2147483647;";
  const shadow = host.attachShadow({ mode: "closed" });

  const style = document.createElement("style");
  style.textContent = `
    button {
      display: inline-flex; align-items: center; gap: 6px;
      font: 12px/1 system-ui, -apple-system, "Segoe UI", sans-serif;
      padding: 6px 10px; color: #172b4d; background: #f4f5f7;
      border: 1px solid #c1c7d0; border-radius: 14px; cursor: pointer;
      box-shadow: 0 1px 4px rgba(9, 30, 66, 0.25);
    }
    button:hover { background: #ebecf0; }
    button:focus-visible { outline: 2px solid #0052cc; outline-offset: 2px; }
    button[disabled] { opacity: 0.6; cursor: default; }
    img { display: block; width: 16px; height: 16px; }
  `;

  const button = document.createElement("button");
  button.type = "button";
  const defaultTitle = "Sync Diffy now (otherwise every 15 minutes)";
  button.title = defaultTitle;
  button.setAttribute("aria-label", defaultTitle);

  const icon = document.createElement("img");
  icon.src = ICON_URL;
  icon.alt = "";
  const label = document.createElement("span");
  label.textContent = SYNC_LABEL;
  button.append(icon, label);
  shadow.append(style, button);

  let inFlight = false;

  function showResult(text, title) {
    label.textContent = text;
    button.title = title;
    window.setTimeout(() => {
      inFlight = false;
      button.disabled = false;
      button.removeAttribute("aria-busy");
      label.textContent = SYNC_LABEL;
      button.title = defaultTitle;
    }, RETRY_RESET_MS);
  }

  button.addEventListener("click", async () => {
    if (inFlight) {
      return;
    }
    inFlight = true;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    label.textContent = "Syncing…";
    console.info("[diffy] sync button clicked");

    let result;
    try {
      result = await Promise.race([
        chrome.runtime.sendMessage({ type: "syncNow" }),
        new Promise((resolve) => window.setTimeout(() => resolve(null), SYNC_REPLY_TIMEOUT_MS)),
      ]);
    } catch (error) {
      console.warn("[diffy] syncNow got no reply", error && error.message);
      const invalidated = error && /context invalidated/i.test(error.message || "");
      result = { ok: false, message: invalidated ? "Reload the page" : "Extension is not responding" };
    }
    if (!result) {
      result = { ok: false, message: "Extension is not responding" };
    }

    if (result.ok) {
      showResult("Synced", "Synced just now");
      return;
    }
    console.warn("[diffy] sync failed", { state: result.state, message: result.message });
    showResult("Sync failed", result.message || "Sync failed");
    if (result.message === "Not signed in to Bitbucket") {
      signedIn = false;
      signedInCheckedAt = 0;
      removeSyncButton();
    }
  });

  return host;
}

async function ensureSyncButton() {
  if (window.top !== window) {
    return;
  }
  let allowed;
  try {
    allowed = await isSignedIn(false);
  } catch (error) {
    allowed = false;
  }
  const existing = document.querySelector(SYNC_HOST_SELECTOR);
  if (!allowed) {
    if (existing) {
      removeSyncButton();
    }
    return;
  }
  if (!existing && document.body) {
    document.body.append(createSyncButton());
  }
}

// Bitbucket is a single-page app; rows load after the initial render. Coalesce
// observer callbacks with requestAnimationFrame so a burst of DOM mutations
// triggers at most one injection pass per frame.
let scheduled = false;

function scheduleAddLinks() {
  if (scheduled) {
    return;
  }
  scheduled = true;
  window.requestAnimationFrame(() => {
    scheduled = false;
    addPullRequestTitleLink();
    addLinks();
    ensureSyncButton();
  });
}

function installNavigationHooks() {
  window.addEventListener("popstate", scheduleAddLinks);
  for (const method of ["pushState", "replaceState"]) {
    const original = history[method].bind(history);
    history[method] = (...args) => {
      original(...args);
      scheduleAddLinks();
    };
  }
}

addPullRequestTitleLink();
addLinks();
ensureSyncButton();
installNavigationHooks();
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") {
    isSignedIn(true).then(ensureSyncButton);
  }
});
new MutationObserver(scheduleAddLinks).observe(document.body, {
  childList: true,
  subtree: true,
});
