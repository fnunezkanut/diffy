# Diffy - Bitbucket PR Review (Firefox extension)

A Manifest V3 Firefox extension that adds an **Open in IntelliJ** icon (the diffy plugin glyph) next to each pull
request on `bitbucket.org`. Clicking it opens that pull request in a git worktree in a running IntelliJ IDE that has
the **diffy** plugin (1.12.0 or newer) installed. It has the same features as the Chrome extension in
[`diffy-chrome-ext/`](../diffy-chrome-ext/README.md).


## Install for your own use (temporary)

1. Open `about:debugging#/runtime/this-firefox`.
2. Click **Load Temporary Add-on...** and select `manifest.json` in this `diffy-firefox-ext/` directory.
3. Grant the host permissions (Firefox MV3 does not grant them at install): open `about:addons`, select **Diffy -
   Bitbucket PR Review**, open the **Permissions** tab and turn on access to `bitbucket.org` and `127.0.0.1`/`localhost`.
4. Open a pull request list or a pull request page on `bitbucket.org`. An **Open in IntelliJ** icon appears next to
   each PR in a list, or beside the PR title on detail pages.

A temporary add-on is removed when Firefox restarts; load it again after a restart.


## How it works

The flow is the same as in the Chrome extension:

```
bitbucket.org page            this extension                         IntelliJ (diffy plugin)
──────────────────            ──────────────                         ─────────────────────────
content.js adds     ──click──▶ background.js finds the   ──HTTP──▶   /api/diffy/open
the plugin icon                IDE port and POSTs the                 opens the PR worktree
                    ◀─result── {workspace, repo, pr}     ◀──JSON──    and replies with JSON
```

- `content.js` runs on `https://bitbucket.org/*`, injects the icon on list and detail pages, and shows the floating
  **Diffy sync** button while you are signed in.
- `background.js` (an event page) scans ports **63342-63361** for a running JetBrains IDE that answers the diffy ping,
  caches the port for the browser session, and forwards open requests as a `POST`.
- `capture.js` runs every 15 minutes (alarm `diffy-capture`), reads your reviewing pull requests and Bitbucket
  notifications from the last 7 days with your existing browser session (read-only), and POSTs one snapshot to
  `/api/diffy/snapshot`. Comment text is never requested or stored. See the Chrome README for the snapshot categories.
- The IDE identifies the extension by its `Origin` header. Firefox sends `moz-extension://<uuid>`, where the UUID is
  random per browser profile, so the plugin accepts any `moz-extension://` origin. Web pages cannot forge this header,
  but any other Firefox add-on that has access to `127.0.0.1` could call the same endpoints.
- The event page can be unloaded when idle. The port cache lives in `storage.session` and the pending snapshot in
  `storage.local`, so nothing is lost; the alarm wakes the page.

## Debugging

From `about:debugging#/runtime/this-firefox`, click **Inspect** on the add-on, then in the console:

- `chrome.runtime.sendMessage({ type: "captureNow" })` runs one cycle and returns the status record.
- `chrome.runtime.sendMessage({ type: "syncNow" })` does what the Sync button does.
- `chrome.runtime.sendMessage({ type: "getLog" })` returns the last 200 log entries; `{ type: "clearLog" }` empties them.
- `chrome.storage.local.set({ diffyDebug: true })` turns on debug entries.

| Log line | Meaning |
| --- | --- |
| `Host permissions not granted` | Grant access to `bitbucket.org` and `127.0.0.1` in `about:addons` |
| `No diffy IDE answered on ports 63342-63361` | IDE closed, built-in server off, or the plugin is not installed |
| `Ping reply has no workspace` | No workspace set in the plugin, or the plugin is older than 1.12.0 |
| `Waiting for IntelliJ to report a workspace` | Set the workspace slug in the Diffy settings in IntelliJ |
| `Not signed in to Bitbucket` | The Bitbucket session expired; sign in again |

## Requirements and limitations

- Firefox 140 or newer (needed for the data collection declaration).
- **The IDE must already be running**, with the built-in server enabled (Settings → Build, Execution, Deployment →
  Debugger → Built-in Server).
- Distribution is a local temporary install only; the add-on is not signed or published.

## Privacy

The extension reads `bitbucket.org` (your own pull requests and notifications, using your existing browser session)
every 15 minutes and caches the latest result in `storage.local`. It sends that result only to the local IntelliJ
plugin on `127.0.0.1`, plus the `workspace`, `repo` and `pr` values when you click Open in IntelliJ. Nothing goes to any
other server. Because it reads Bitbucket page data, the manifest declares the `websiteContent` data collection
permission. Cookies, tokens, avatar URLs and comment text are never stored or sent.

## Keeping in sync with the Chrome extension

`content.js`, `capture.js`, `lib/` and `icons/` are identical copies of the files in `diffy-chrome-ext/`. Edit both
directories together. Check from the repository root:

```
diff -r diffy-chrome-ext/lib diffy-firefox-ext/lib && diff -r diffy-chrome-ext/icons diffy-firefox-ext/icons \
  && diff diffy-chrome-ext/content.js diffy-firefox-ext/content.js && diff diffy-chrome-ext/capture.js diffy-firefox-ext/capture.js
```

## Files

| File | Purpose |
| --- | --- |
| `manifest.json` | MV3 manifest: Firefox add-on ID, `background.scripts`, permissions, content script match, icons. |
| `content.js` | Injects the Open in IntelliJ icon link and the floating Diffy sync button on bitbucket.org pages. |
| `background.js` | Event page: IDE port discovery, caching, the POST request, host permission hint, capture alarm wiring. |
| `lib/log.js` | Diagnostic logger: console plus a 200 entry ring buffer in `storage.local`. |
| `capture.js` | Bitbucket client, 15 minute capture cycle, and delivery to IntelliJ with offline cache. |
| `lib/snapshot.js` | Pure functions: query building, mapping, Jira keys, bot detection, classification. |
| `icons/` | Toolbar icons (16/48/128 px). |
