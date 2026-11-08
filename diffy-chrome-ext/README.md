# Diffy - Bitbucket PR Review (Chrome extension)

A Manifest V3 Chrome extension that adds an **Open in IntelliJ** icon (the diffy plugin glyph) next to each pull
request on `bitbucket.org`. Clicking it opens that pull request in a git worktree in a running IntelliJ IDE that has
the **diffy** plugin installed.


## Install for your own use

1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top-right).
3. Click **Load unpacked** and select this `diffy-chrome-ext/` directory.
4. Open a pull request list or a pull request page on `bitbucket.org`. An **Open in IntelliJ** icon appears next to
   each PR in a list, or beside the PR title on detail pages (`/overview`, `/diff`, `/commits`, and other sub-tabs).


## How it works

```
bitbucket.org page            this extension                         IntelliJ (diffy plugin)
──────────────────            ──────────────                         ─────────────────────────
content.js adds     ──click──▶ background.js finds the   ──HTTP──▶   /api/diffy/open
the plugin icon                IDE port and POSTs the                 opens the PR worktree
                    ◀─result── {workspace, repo, pr}     ◀──JSON──    and replies with JSON
```

- `content.js` runs on `https://bitbucket.org/*`.
  - **List pages:** finds pull request links by **URL structure** (`/<workspace>/<repo>/pull-requests/<n>`), not by
    CSS class, because Bitbucket's class names change between frontend releases.
  - **Detail and sub-tab pages** (`/<workspace>/<repo>/pull-requests/<n>/overview`, `/diff`, `/commits`, etc.): reads
    `workspace`, `repo`, and `pr` from the browser URL and injects the icon beside the PR title (`h1`), using stable
    `data-testid` selectors where available.
  - A `MutationObserver` and history hooks handle Bitbucket's single-page-app navigation after the initial render.
- `background.js` (the service worker) scans ports **63342–63361** for a running JetBrains IDE that answers the
  diffy ping, caches the port for the browser session, then forwards the open request as a `POST`. The request has
  to come from the service worker because Chrome's Private Network Access and CORS rules block the page itself from
  calling `localhost`.

## Background capture and delivery

Every 15 minutes the service worker (`capture.js`, alarm `diffy-capture`) reads two things from `bitbucket.org` with
your existing browser session, read-only, even when no Bitbucket tab is open:

- **`pullRequests`**: your open and draft pull requests where you are a reviewer (at most 5 pages of 20).
- **`notificationEvents`**: Bitbucket notifications from the last 7 days (Jira, Confluence and other products are
  dropped and only counted). Comment text is never requested or stored.

Both lists go into one JSON snapshot that is POSTed to the diffy plugin at `/api/diffy/snapshot`. Each event gets a
`category`:

| `category` | Meaning |
| --- | --- |
| `bot_review_comment` | Comment by an actor whose name has a whole word `PR`, `Ranger`, `Buddy`, `Agent` or `Bot` |
| `human_comment` | Any other comment |
| `approval` | Someone approved a pull request |
| `reviewer_added` | You were added as a reviewer |
| `mention` | Someone mentioned you on Bitbucket |
| `pr_updated`, `merged` | Reserved, never produced (Bitbucket sends no such notifications) |
| `other_bitbucket` | Any other Bitbucket event |

Each pull request has `jiraKeys` (a list; from the title, else from the branch name) and `jiraKeySource`.

- **Workspace:** it comes from the diffy plugin's ping reply, so nothing is captured until IntelliJ has answered at
  least once. After that the slug is remembered and capture works while IntelliJ is closed.
- **Offline cache:** if IntelliJ is not listening, the newest snapshot stays in `chrome.storage.local` and is resent
  on a later cycle (and once at browser start). A new capture replaces an undelivered one. A snapshot that IntelliJ
  rejects three times is dropped.
- **Transport:** requests go from the service worker. If Bitbucket refuses them, the extension retries inside an
  open `bitbucket.org` tab.
- **Several IDEs:** the lowest port in 63342-63361 that answers as diffy receives the data.
- **Permission:** the extension asks for access to `bitbucket.org` for this.
- **Sync now:** a floating **Diffy sync** button sits at the bottom left of every `bitbucket.org` page while you are
  signed in. Clicking it captures and delivers right away and restarts the 15 minute timer.
- **Debug:** from the service worker console (`chrome://extensions` > Diffy > "service worker"):
  - `chrome.runtime.sendMessage({ type: "captureNow" })` runs one cycle and returns the status record
    (`diffyCaptureStatus` in `chrome.storage.local`).
  - `chrome.runtime.sendMessage({ type: "syncNow" })` does what the button does.
  - `chrome.runtime.sendMessage({ type: "getLog" })` returns the last 200 log entries (`diffyLog`), which survive
    service worker restarts; `{ type: "clearLog" }` empties them.
  - `chrome.storage.local.set({ diffyDebug: true })` turns on debug entries (off by default).

## Troubleshooting

Look for these entries in the log (all lines start with `[diffy]`):

| Log line | Meaning |
| --- | --- |
| `No diffy IDE answered on ports 63342-63361` | IDE closed, built-in server off, or the plugin is not installed |
| `Ping reply has no workspace` | No workspace set in the plugin, or this extension ID differs from the plugin's allowed ID |
| `IntelliJ answered with an HTTP error status` (404) | Extension ID mismatch or an outdated plugin |
| `Waiting for IntelliJ to report a workspace` | Capture cannot start until a ping reply carries the workspace. Set the workspace slug in the Diffy settings in IntelliJ; the Sync button then shows that message |
| `Not signed in to Bitbucket` | The Bitbucket session expired; sign in again |
| `Cycle skipped: another cycle holds the lock` | A cycle is running or died mid-run; the Sync button overrides the lock |

The plugin logs the other side in `idea.log` (category `com.github.fnunezkanut.bbcloud.external`), including the origin
it received and the one it expects.

## Requirements and limitations

- **The IDE must already be running.** This extension cannot start a closed IDE; it only talks to a live one.
- The diffy plugin's REST endpoint must be installed and the IDE built-in server must be enabled (it is by default:
  Settings → Build, Execution, Deployment → Debugger → Built-in Server).
- Chrome 116 or newer.

## Privacy

The extension reads `bitbucket.org` (your own pull requests and notifications, using your existing browser session)
every 15 minutes and caches the latest result in `chrome.storage.local`. It sends that result only to the local
IntelliJ plugin on `127.0.0.1`, plus the `workspace`, `repo` and `pr` values when you click Open in IntelliJ. It never
sends anything to any other server. Cookies, tokens, avatar URLs and comment text are never stored or sent, and the
diagnostic log never contains them or pull request titles.

## Extension ID 

`knninefpkpeielbdhlbhdheabjmkebpb`


## Files

| File | Purpose |
| --- | --- |
| `manifest.json` | MV3 manifest: permissions, content script match, service worker, icons, web-accessible icon. |
| `content.js` | Injects the Open in IntelliJ icon link and the floating Diffy sync button on bitbucket.org pages. |
| `background.js` | Service worker: IDE port discovery, caching, the POST request, and the capture alarm wiring. |
| `lib/log.js` | Diagnostic logger: console plus a 200 entry ring buffer in `chrome.storage.local`. |
| `capture.js` | Bitbucket client, 15 minute capture cycle, and delivery to IntelliJ with offline cache. |
| `lib/snapshot.js` | Pure functions: query building, mapping, Jira keys, bot detection, classification. |
| `icons/` | Toolbar icons (16/48/128 px). |
