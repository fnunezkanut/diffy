# Architecture

This file describes how the diffy IntelliJ plugin source is organized. For build commands, coding standards, and
logging rules, see [AGENTS.md](AGENTS.md). For user-facing features, see [README.md](README.md).

All Kotlin sources live under `src/main/kotlin/com/github/fnunezkanut/bbcloud/`. Extension points, services, tool
windows, and editors are registered in [`plugin.xml`](src/main/resources/META-INF/plugin.xml). User-visible strings
live in `DiffyBundle` (`messages/DiffyBundle.properties`).

## Layers

```
UI (Compose Screen  <-  UiState  <-  ViewModel, hosted by a Swing *Panel)
        |
Application services (Diffy*Service, git and project helpers)
        |
Bitbucket clients (OkHttp*Client, Bitbucket*Client)  ->  response parsers (Bitbucket*ResponseParser)
        |
Credentials (PasswordSafe) and persisted settings (DiffySettings)
```

- Higher layers depend on lower layers only. Clients return sealed result types (for example
  `PullRequestListResult`, `ReviewerQueueQueryResult`) instead of throwing, and the UI layer decides the user-facing
  outcome and logs the throwable once (see AGENTS.md, Logging).
- Parsers are pure functions over JSON strings. They are unit tested without HTTP.

## Packages

| Package | Responsibility |
| --- | --- |
| `auth` | Bitbucket Cloud OAuth 2.0 (PKCE) login and the access-token layer: the login service and flow, the local callback server, the token-endpoint client, the access-token provider (OAuth first, API token fallback), the online credential validator, and the 401-retry OkHttp `Authenticator`. See "Authentication" below. |
| `bitbucket` | Bitbucket Cloud REST clients, URL builders, JSON parsers, and domain models (pull requests, comments, tasks, build statuses, reviewers). Also holds the application services that combine clients with the access-token provider (`DiffyAuthoredPullRequestService`, `DiffyReviewQueueService`, `DiffyOpenPullRequestService`, `DiffyProjectRepositoryLinkService`). |
| `cache` | Persisted, application-level cache of Bitbucket pull request metadata (`DiffyPullRequestCache`, its XML state `DiffyPullRequestCacheState`, the row codec, listing snapshots, and retention). Read and written by the Bitbucket services; see "Pull request cache" below. |
| `config` | `DiffyConfigurationService`: the single facade the UI uses for setup state, workspace, projects directory, watched projects, and list filters. |
| `credentials` | `DiffyCredentialStore` and its `PasswordSafe` implementation. Holds the API token and, as a separate entry, the OAuth token bundle (access token, refresh token, expiry); both may exist at once. |
| `git` | Git command execution, worktree creation and removal, branch checkout, parsers for git output, `GitHeadBranchReader` (reads a project's current branch from its `HEAD` file), `PrBranchSyncEngine` (fetches, inspects and fast-forwards the local PR source and destination branches through the `GitCommandRunner` seam, driven by the pure `PrBranchFastForwardPolicy`), and `DiffyPrBranchSyncService` (project service in a PR work tree window that serializes syncs and publishes the `PR_BRANCHES_SYNCED` topic, which the `.pr` editor session uses to refresh the red "branches are behind" warning). |
| `project` | Mapping local checkouts and PR worktrees to Bitbucket repositories: watched-project discovery, worktree layout and resolution, `ResolvedWorktreeCleanupSelection` and `ResolvedWorktreeCleaner` (background cleanup of the work trees and git links of merged or declined PRs when the Current Project tab loads), opening local projects, `PullRequestWorktreeRecentProjects` (groups PR worktrees in Recent Projects and forgets removed ones), `RecentProjectsSource` (read side of the IDE Recent Projects list), `PullRequestWorktreePaths` (detects PR work tree folders under the work tree root and maps recent projects to repository slugs), and `WorktreeSourceCheckoutResolver` (finds the source clone of a work tree from its `.git` pointer, the watched mapping, or the projects directory). |
| `review` | Per-project review state kept while a PR worktree is open: viewed files and threads, the worktree PR context, banners, page reloads, and the persisted recently-seen mention users cache (`DiffyKnownUsersCache` / `DiffyKnownUsersState`). |
| `settings` | Persisted plugin settings (`DiffySettings`) and the Settings > Tools configurable. |
| `toolwindow` | `DiffyToolWindowFactory` and the tool window tab ids. |
| `ui` | `DiffyToolWindowPanel` (the project service that owns the tool window tabs) and the sub-packages below. |
| `ui/main` | Current Project tab: the pull request detail views (overview, conversation, changes, history), comment and description editors, reviewer search, and @mention suggestions (`CommentMentionSuggestions`, `CommentMentionSource`). The Overview right column starts with a Tickets card (shown only when `JiraTicketIds` finds Jira ids in the PR title or source branch) that links to `https://{workspace}.atlassian.net/browse/{id}`. |
| `ui/pullrequests` | The shared pull request listing (`PullRequestsViewModel`, filtering, merging of listing sources) used by both PR tabs. |
| `ui/otherprojects` | Workspace and Workspace - Watched Projects tabs. |
| `ui/recentprojects` | Recent Projects tab: a read-only view of the IDE Recent Projects list, groups included, with search; clicking opens or focuses the project. Rows show the Bitbucket repository avatar (the Watched Projects avatar cache, through `RepoAvatarProvider`) when known, a Remove from Recent Projects button, and for PR work trees a Remove work tree button. |
| `ui/notifications` | Notifications tab: the persisted notifications in a Compose table, newest first (`NotificationsUiState` builds the rows from `DiffyStoredNotification`s, `NotificationsViewModel` collects `DiffyNotificationsStore.content`, so the table also updates when another IDE process changes the notifications). A row has a selection checkbox (view model state, with a tri-state select all header) that drives the Mark as read and Mark as unread buttons, a type icon, and an event cell: the actor avatar from `AuthorAvatarCache` (URL looked up in the pull request cache, default user icon otherwise), the event message as a title link, and `actor · #id · time · repository`. `NotificationPullRequestOpener` routes the clicks: the title opens the pull request for review through `PullRequestWorktreeOpener.openInBackground`, or on Bitbucket web when it is merged, declined, not found or in another workspace than the configured one; `#id` opens the pull request page; the repository opens the local checkout, else the Bitbucket page. Only `https`/`http` links are opened, and the pull request page is rebuilt from validated `bitbucket.org` link parts. Unread rows have a bold title and an "Unread" content description; the read flag is owned by the plugin, never taken from Bitbucket. The tab shows the not-configured link while the plugin is not configured. |
| `ui/pullrequest/*` | PR-specific editors and IDE integration: `editor` (PR virtual file system, file editor, review bar, banners), `create` (new PR editor), `help` (Work Trees help tab), `decline`, `projectView`, `session`, `changes` (worktree changes tool window). |
| `ui/diff` | Inline comment threads and gutter actions in diff and editor views. |
| `ui/air` | Optional integration with the JetBrains Air plugin: `AirCommentSender` builds the prompt for a comment, `AirPullRequestExplainer` builds the "Explain this PR with AI" prompt, and `AirChatBridge` puts the text into the Air chat input. For the explain prompt, `AirChatBridge` starts a new thread with the runtime-resolved action id `AgentThreads.MainToolbar.NewThread` and inserts the prompt without submitting it. The only package that touches Air. Air is resolved by name at runtime and is not compiled against, so the plugin works without it. |
| `ui/setup` | First-run setup and the Settings screen. |
| `ui/components` | Reusable Compose components. |
| `ui/worktree` | Worktree dialogs and `PullRequestWorktreeOpener`, the one implementation of "open a pull request in its own work tree" used by the Workspace and Current Project panels and by the REST open path. It also holds `PullRequestWorktreeRemovalLauncher`, the one "remove a PR work tree" flow (guards, confirmation with an uncommitted-changes warning, removal, notifications) shared by the Watched Projects and Recent Projects tabs. It lives under `ui` because it opens editor tabs and shows notifications. |
| `external` | Browser entry points: `DiffyOpenPullRequestRestService` at `/api/diffy/open` (ping and open), its request validator, and the allowed extension origin. `DiffyBrowserSnapshotRestService` at `/api/diffy/snapshot` (POST only, same origin rule) receives the Chrome extension's Bitbucket snapshot, `DiffyBrowserSnapshotParser` validates it, and `DiffyBrowserSnapshotStore` keeps the latest one in memory only; its notification events are persisted (see "Persisted notifications"). Request bodies are never logged, only counts. Requests run on a Netty thread, so work is handed to `ui/worktree`. `DiffyBrowserSnapshotCacheService` collects the store on `Dispatchers.IO` and `DiffyBrowserSnapshotCacheUpdater` folds the snapshot's pull requests into the pull request cache (see "Pull request cache"). |
| `validation` | Input validators for workspace and repository slugs. |

## UI pattern: UiState / ViewModel / Screen

Each screen has three parts:

- `*UiState`: an immutable data class, plus pure derivation functions that can be unit tested.
- `*ViewModel`: owns a `MutableState<UiState>`, runs the orchestration for the screen (loading, refresh generations,
  user actions), and receives its dependencies as constructor parameters (data-source interfaces or lambdas) so it can
  be tested without the IntelliJ platform.
- `*Screen`: a stateless `@Composable` that renders the state and forwards events to the ViewModel.

Example: `PullRequestOverviewUiState` / `PullRequestOverviewViewModel` / `PullRequestOverviewScreen` in `ui/main`.

`*Panel` classes (`DiffyMainPanel`, `DiffyOtherProjectsPanel`, `CheckedOutBranchPanel`) are Swing hosts. They wire
services into ViewModels, switch between not-configured and content cards, and show platform UI that needs a Swing
parent (dialogs, notifications). For example, on the Workspace tabs:

- `WatchedProjectsViewModel` runs bind, rescan, and watched-flag changes against a `WatchedProjectsDataSource`.
- `WorkspacePullRequestsLoader` loads the authored and reviewing sources into the shared `PullRequestsViewModel`
  against a `WorkspacePullRequestsDataSource`.

`DiffyOtherProjectsPanel` still runs the worktree open, prune, and remove flows itself. They are candidates for the
same extraction. The Watched Projects remove button is the exception that delegates the work: the panel asks for
confirmation, then `DiffyPullRequestDeclineCoordinator` runs `WatchedWorktreeRemover` on its application scope. The
remover closes an open IDE window on the work tree and then removes it, so the removal survives the project closing.
`WatchedWorktreeRemovalOrchestrator` bounds the progress modal to 5 seconds. The removal continues on the application
scope after the modal closes.

## Threading

- HTTP and git calls run on `Dispatchers.IO` inside coroutines launched from the panel's or service's
  `CoroutineScope`.
- Results go back to the EDT through `ApplicationManager.getApplication().invokeLater`. ViewModels take this hand-off
  as an injectable `runOnEdt` parameter, so tests can run it inline.
- Listings use refresh generations (for example `PullRequestsViewModel.beginRefresh`), so a slow response cannot
  overwrite a newer one.

## HTTP and credentials

- `OkHttpBitbucketPullRequestClient` sends every authenticated GET through one helper (`executeGet`). That helper
  attaches `Authorization: Bearer <token>` only when `BitbucketApiUrlPolicy.isTrustedApiUrl` accepts the URL: HTTPS,
  the default port, and the exact host `api.bitbucket.org`. This guards pagination `next` links, which come from
  response bodies. `OkHttpBitbucketWorkspaceClient` and `OkHttpBitbucketRepositoryClient` apply the same check.
- Avatar images are public CDN URLs and are fetched without the token (`BitbucketAvatarClient`).
- Never log tokens or raw response bodies (see AGENTS.md, Secrets and privacy).

## Pull request cache

- Each pull request is stored once, with the fields the listings show. Listings are stored as snapshots of ordered keys:
  the Current Project list (`project:`), the authored list (`workspace-authored:`), and each watched repository's review
  list (`workspace-reviewing:`).
- Automatic loads (a tab opened or bound) use a snapshot younger than 60 seconds with no request. A Refresh button
  click (`PullRequestCacheRefresh.Force`) always asks Bitbucket, and the Workspace Refresh first re-checks the watched
  projects. A failed request falls back to the last snapshot, silently.
- Every successful read from Bitbucket updates the entry and its fetch time. Merge, decline, approvals, and comment
  counts patch the entry in place. Reviewer and description edits invalidate the listings that contain the pull request.
- Live only, never served from the cache: worktree pruning (`listAllOpenPullRequests`) and the safety checks before a
  worktree is removed or opened.
- Retention: merged or declined entries drop 30 days after closing, untouched open entries after 30 days, and the cache
  holds at most 2000 entries. Entries that a recent snapshot still lists are kept until that snapshot changes.
- The cache belongs to one Bitbucket account. A change of account clears it after two consecutive reads agree on the new
  account. Changing the workspace slug, resetting the API token, or resetting the plugin clears it too.
- The Chrome extension snapshot feeds the cache (`DiffyBrowserSnapshotCacheUpdater`, started at project open, so it
  runs even when the Notifications tab was never opened). Only pull requests of the configured workspace apply. A cached
  entry is patched only when the snapshot is strictly newer (an equal `updatedOn` may only raise the comment count);
  a patch never moves `lastFetchedAt`. An open pull request that is not cached is inserted, and the insert stamps the
  fetch time as now. A pull request that was in the previous complete snapshot and is missing now has its listings
  invalidated, because it may have been merged or declined. A change reaches an open tab on its next selection or
  Refresh click: the updater only invalidates listings and posts no event.
- The state is an application-level XML file with roaming disabled. Cache services must not run on the event dispatch
  thread, because the write encodes the whole cache.

## Persisted notifications

- `DiffyNotificationsState` is an application-level `PersistentStateComponent` (`bbcloud-notifications.xml`, roaming
  disabled). Each notification is one versioned JSON row (`DiffyStoredNotificationRow`) holding the event, the `unread`
  flag (default `true`), and `changedAt`, the time the flag last changed. The `readState` Bitbucket sends is never stored.
- `DiffyNotificationsRepository` (platform free, unit tested) merges each snapshot in (`mergeSnapshot`: unknown ids are
  added unread, known ids keep their flag, ids missing from the snapshot are kept), keeps at most 2000 notifications by
  evicting the oldest `occurredAt` first (`applyCap`), records the last sync time (the snapshot's `capturedAt`, or the
  receive time when it does not parse), and publishes a `StateFlow`. `DiffyNotificationsStore` is the service in front of
  it. `DiffyBrowserSnapshotCacheService` feeds it for every snapshot, even if the tab was never opened.
- Sharing between IDE processes: the state is reloadable, so when another IDE process of the same product and version
  rewrites the file, the platform calls `loadState`. The repository then merges (`mergeExternal`: union by id, the read
  flag changed last wins) and writes only when the merge adds something, so two IDEs cannot rewrite each other forever.
  Every change schedules a save, so other IDEs see it soon; the platform file watcher can add a delay of a few seconds.
  Different IDE products or versions have separate config directories and do not share notifications.
- New event balloon: `DiffyNotificationsRepository.recordSnapshot` returns how many notifications the snapshot added
  that were never stored (`countNewNotifications`; ids the cap evicts right away do not count). The first sync, when
  nothing was ever synced (also right after a clear), returns 0 so a backlog is not announced. `DiffyNotificationsRecorder`
  (platform free, unit tested) passes a count above zero to `ui/notifications/DiffyNewEventsBalloon`, which shows one
  "X new event notifications from Diffy browser extension" balloon in the `Diffy` group, in one open project (the active
  window, else the first), replaces the previous one, and stays silent without a configured workspace. Its "Open
  Notifications" action opens the tool window on the Notifications tab (`DiffyToolWindowPanel.showNotificationsTab`).
  Reloads from another IDE process and read-flag changes never announce.
- Rows of a newer plugin version are kept untouched and written back.
- Clearing: `DiffyPullRequestCache.clear()` and an account change also clear the notifications (`onCleared`), so a
  workspace change, an API token reset, and the Reset Plugin button forget them too. The next snapshot refills the list.
- The merged/declined state of a row comes from the latest snapshot, or from the persisted pull request cache when no
  snapshot arrived since the IDE started.

## Authentication (`auth`)

Two credentials may be stored at once, and every API request resolves its bearer token through
`DiffyAccessTokenProvider` (implemented by the application service `DiffyAuthService`):

- **OAuth first, API token fallback.** `bearerTokenOrNull()` returns a working OAuth access token when one is
  available, otherwise the stored API token. `DiffyAccessTokenResolver` holds the logic.
- **Proactive refresh.** An OAuth access token within five minutes of expiry is refreshed under a single-flight
  `Mutex`, so concurrent callers cause one refresh. A permanent refresh failure (`invalid_grant` / `invalid_client`)
  clears the OAuth tokens, shows one balloon, and falls back to the API token. A transient failure keeps the tokens
  and applies a one-minute backoff before retrying.
- **401 retry.** `DiffyBearerRetryAuthenticator` is attached to the authenticated OkHttp clients. After a 401 on a
  trusted Bitbucket API URL it asks the provider for a refreshed token and retries the request once.

The OAuth login itself:

- `DiffyOAuthLoginService` is an application service that owns the one login allowed per IDE; every project's
  Settings tab observes its `StateFlow` and can cancel it. `DiffyOAuthLoginFlow` is the testable core (PKCE request,
  browser launch, callback wait, token exchange, result page).
- `JdkOAuthCallbackServer` is a short-lived `com.sun.net.httpserver` server bound to `127.0.0.1:42424`. It serves
  `/diffy` (the OAuth redirect, answered with a 302 to a code-free result page) and `/diffy/done` (the result
  page). The OAuth consumer callback URL must be exactly `http://127.0.0.1:42424/diffy`.

The configured gate:

- `DiffyConfigurationService.isConfigured()` requires a workspace slug, an existing projects directory, and a
  credential that `DiffyCredentialValidator` verifies working online with `GET /2.0/user`. The check result is
  cached for five minutes (30 seconds when the check could not reach Bitbucket). When offline, the validator falls
  back to the last known working method so the UI is not locked.

## Tests

- Unit tests live in `src/test/kotlin/com/github/fnunezkanut/bbcloud/`, in the same package as the code under test,
  and carry `@Tag("unit")`.
- HTTP clients are tested with an OkHttp `Interceptor` that captures requests and returns canned responses. No
  network is used.
- ViewModels are tested with fake data sources, `CoroutineScope(Dispatchers.Unconfined)`, and an inline `runOnEdt`.
- `plugin/` holds descriptor and service-constructor checks for `plugin.xml`.
