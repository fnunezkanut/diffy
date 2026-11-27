# CHANGELOG

## *1.17.0* - 2026-11-27
* Changed: the Merge button and the Checks card use all of Bitbucket's mergeability checks (PR state, merge permission, conflicts, merge queue, branch restrictions and app checks). Merge stays disabled with a tooltip naming the blocking checks, such as "Blocked by: 1 of 2 approvals". Before, a failed check request left Merge enabled
* Added: the Checks card shows merge conflicts as "Has conflicts", with Rebase with AI (in a PR work tree) and Open conflicts in browser links. The conflict check runs after the page loads. A queued pull request shows its queue, and a draining or suspended queue is named
* Changed: the Checks card counts only the checks Bitbucket enforces ("X of Y checks passed"). Other checks stay listed as advisory warnings, and checks that run only on merge are labeled "Evaluated when merging". Approvals, tasks and builds link to their cards
* Changed: while a check is pending or a build is running, the Checks card refreshes every 30 seconds. When Bitbucket rate limits the checks, the last result stays visible marked "(stale)", Diffy retries with a growing wait (30 seconds to 5 minutes), and a Retry link is available
* Added: "Auto-update main branches" setting (off by default). Every 5 minutes it fetches the main branch of each watched project and fast-forwards the local one when it has no uncommitted changes or local commits. The Watched Projects tab shows the last result per project. After fast-forwarding a checked-out main branch, git runs the clone's post-merge hook (if any)
* Changed: the Settings section "Pull Request Diffs" is now "Other Settings", and its checkbox reads "Make pull request diffs editable" with a clearer description
* Added: Rebase onto latest with AI first backs up the current commit and uncommitted tracked changes under refs/diffy/backups, shows a notification with a Copy restore command action, and tells the agent to stop if the branch changed after the backup. The 5 newest backups per pull request are kept. Untracked files are not backed up

## *1.16.0* - 2026-11-26
* Changed: the "Local branches are behind Bitbucket" warning is now a one-line red bar under the blue bar on the PR page, every file and every diff of the work tree, with its own icon and a Sync local branch link. It also updates while the PR page is closed
* Changed: "This pull request is N commits behind" is now a yellow bar under the blue and red bars on the PR page, every file and every diff of the work tree, with a Rebase onto latest with AI link that puts a stash, rebase and force-push-with-lease prompt into a new Air chat

## *1.15.0* - 2026-11-25
* Changed: in the Chrome and Firefox extensions the Open in IntelliJ icon now sits to the left of the pull request title instead of the right
* Added: the Open in IntelliJ icon turns into a loading spinner after you click it and goes back to the plugin icon when the IntelliJ window has opened (needs this plugin version; older plugins show the spinner for 3 seconds)
* Fixed: Explain this PR with AI and Send to Air no longer insert the prompt into the Air chat of another open project window
* Fixed: links in pull-request descriptions and comments no longer show Bitbucket markup such as `{: data-inline-card='' }` after them

## *1.14.0* - 2026-11-25
* Fixed: the Chrome Web Store extension could not sync, open pull requests, or deliver notifications because the plugin accepted only the development extension ID; the Sync button also reported this as a missing workspace
* Removed: Bitbucket API token sign-in. The plugin now signs in with Bitbucket OAuth only. If you used an API token, open Settings > Tools > Diffy - Bitbucket PR Review and authenticate with Bitbucket oauth login; the old token is deleted when you do
* Added: Notifications tab has a Filter out bots checkbox that hides notifications from bots; the choice is remembered
* Changed: PR diffs in a work tree show the editable, fully navigable work tree file on the right side when the work tree is at or ahead of the pull request source commit (Settings > Tools > Diffy)
* Added: an orange bar under the blue bar marks files with changes that are not in the pull request yet (unpushed commits or uncommitted edits), with Commit… and Push… links

## *1.13.0* - 2026-11-14
* Added: the Pull Requests and Notifications tabs show the number of open PRs and unread notifications in their titles
* Changed: the Current Project and Workspace tabs are merged into one PR Overview tab that lists PRs of the current project, PRs you opened, and PRs of watched projects, open PRs first. It has an In any project / In current project dropdown and a Create new PR button. Watched projects now list all their PRs; use Assigned to me to narrow
* Changed: work trees of merged or declined PRs are also cleaned up for watched projects
* Changed: the plugin settings moved to Settings > Tools > "Diffy - Bitbucket PR Review". Until the plugin is configured, the PR tabs show a link to that page; Recent Projects keeps working
* Fixed: the PR Overview shows "Loading PRs…" until rows are ready, instead of briefly showing "No PRs match the current filters"
* Added: Clear Recent Projects and Remove all PR Work Trees buttons on the Recent Projects tab; entries now use two lines

## *1.12.0* - 2026-11-11
* Added: Firefox extension "Diffy - Bitbucket PR Review" in `diffy-firefox-ext` with the same features as the Chrome extension
* Changed: the plugin accepts requests from Firefox extension origins as well as the Diffy Chrome extension
* Changed: Slight update to the "Explain this PR with AI" prompt

## *1.11.0* - 2026-11-10
* Added: an "Explain this PR with AI" button next to Edit Description, shown when the JetBrains Air plugin is installed; it puts a prompt in a new Air chat that asks the agent for a structured explanation of the open pull request with Mermaid diagrams
* Added: Wrap and Unwrap lines button on diff view
* Added: Jira ticket id parsing from PR title and/or branch title, in a new Tickets panel that links to Jira
* Changed: Collapse Unchanged Fragments setting is the default (can be changed)

## *1.10.0* - 2026-11-08
* Added: the Chrome extension (1.4.0) shows a floating "Diffy sync" button at the bottom left of bitbucket.org pages for signed-in users; it also auto syncs every 15 minutes on timer
* Added: a Notifications tab before Settings shows the Bitbucket notifications the Chrome extension captured, newest first
* Added: the Chrome extension's pull request data refreshes the plugin's pull request cache (comment counts, titles, draft flag, approvals, merged or declined)

## *1.9.0* - 2026-11-07
* Changed: renamed plugin to "Diffy - Bitbucket PR Review"; the plugin id is unchanged, so existing installs and settings carry over
* Changed: the Chrome extension is renamed to "Diffy - Bitbucket PR Review" and its folder is now `diffy-chrome-ext`
* Changed: README and Marketplace description refreshed with the new name, logo, and an up-to-date feature list

## *1.8.0* - 2026-11-05
* Changed: requires IntelliJ IDEA 2026.3 or newer; updated to Jewel 0.42 and Compose Multiplatform 1.12.1
* Added: a "Send this comment to Air" button on every comment box, shown when the JetBrains Air plugin is installed; it puts the comment's file location and text into the Air chat input 
* Added: PR work trees are listed under a "PR work trees" group in Recent Projects and the Welcome screen; deleting a work tree removes it from that list
* Added: a Recent Projects tab between Watched Projects and Settings lists the IDE recent projects and their groups, with search, icon, path, and current branch; click a project to open or focus it
* Added: Recent Projects rows show the Bitbucket repository icon and a "Remove from Recent Projects" button; PR work trees also get a red "Remove work tree" button that closes the window and deletes the work tree
* Added: a "View Rendered Diff" button in the PR review bar of Markdown files opens the rendered Markdown diff
* Changed: the remove work tree confirmation warns about uncommitted or untracked changes
* Added: opening a PR work tree window or clicking refresh on the blue bar fetches the PR source and destination branches and fast-forwards the local branches when safe; a missing local branch is created from its Bitbucket counterpart
* Changed: the red warning on the PR page covers both branches, explains why a branch was not updated, and has one link to update both
* Fixed: PR diff and line counts compare against the remote destination branch, so a stale local branch no longer inflates them

## *1.7.0* - 2026-11-04
* Added: a red delete button beside each PR work tree on Watched Projects removes that work tree after confirmation, and closes its IDE window first if one is open; the progress dialog closes after 5 seconds while the removal keeps running in the background
* Added: @mention users in comments and replies
* Added: PRs assigned to me option on Current Project and Workspace tabs in the dropdown

## *1.6.0* - 2026-11-03
* Fixed: the Previous, Next, and Add Comment buttons in the diff review bar no longer show a hover tooltip that covered the buttons and did not close
* Changed: when every other file is marked as viewed, the diff review bar shows this as text next to the disabled Previous and Next buttons
* Added: the Create Pull Request form prefills the title and description from the selected source branch's last commit, and updates them when you switch branches unless you have edited the fields
* Changed: the "Mine" exclude checkbox on both PR tabs is replaced by a "Created by any user" / "Created by me" dropdown, and the remaining checkboxes are relabeled "Exclude Resolved", "Exclude Approved by me", and "Exclude Drafts"; each tab remembers its choice
* Changed: Watched Projects now caps how many projects you can watch to help avoid Bitbucket rate limits, and the footer and workspace scope notes link to the related tab
* Changed: `@mentions` in the Activity timeline show a "loading…" placeholder until the account name resolves

## *1.5.0* - 2026-11-02
* Bugfix: Unable to leave multiple comments on same line, thanks Olszewski-Jakub!

## *1.4.0* - 2026-11-01
* Fixed: keyboard input in diff and file-editor comment boxes (including paste) edits the comment instead of the read-only file
* Changed: reply, edit, line, and file comment boxes take focus when opened and keep their text and focus when comments refresh; reply and edit boxes close if the comment was deleted or resolved elsewhere
* Fixed: inline and file comments on added files appear in the file diff view, as in the file editor
* Fixed: "Add Comment" in added-file, deleted-file, and unified diffs opens the correct comment UI; deleted-file comments are created on the pull request and shown on the pull request page
* Changed: Bitbucket sign-in uses OAuth or an API token
* Removed: Overview, Conversation, and Changes tabs on the pull request page; that content is on one page, and changed files are listed in the PR Changes tool window
* Added: PR Changes tool window from "Show changed files" in a work tree — file list with line and comment counts, branch names only in the header, Enter to open the selected diff, purple tick instead of line counts for viewed files, and optional diff navigation over unviewed files only
* Changed: the History card is renamed Activity; commits, approvals, tasks, builds, and comment threads appear in chronological order, with the main comment box at the top
* Changed: opening a PR work tree while the project is on that PR's branch switches to the main branch first; uncommitted changes trigger a warning instead of switching
* Added: a local REST endpoint at `/api/bbcloud/open` on the IDE built-in server, used by the bbcloud Chrome extension to open a pull request in its own work tree from bitbucket.org. The IDE must be running; open requests are accepted only from the extension origin

## *1.3.0* - 2026-10-30
* Changed: "Project - Open PRs" and "Project - Completed PRs" are merged into one "Current Project - PRs" tab
* Changed: "Workspace - My PRs" and "Workspace - Assigned PRs" are merged into one "Workspace - PRs" tab
* Added: "Exclude:" filters on both PR tabs — Resolved (on by default, Current Project - PRs only), Mine (hides PRs you created), Approved by me, Drafts (on by default); each tab remembers its choices
* Added: Compatibility with IntelliJ 2026.3 Beta

## *1.2.0* - 2020-10-29
* Changed: the add-comment icon in diff gutters uses the standard IDE "+" icon
* Fixed: Clipping of comment boxes on multi-monitor (with different scales) setup
* Fixed: emoji in pull-request descriptions, comments and tasks are shown as the emoji instead of Bitbucket markup such as `:slight_smile:{: data-emoji-id='1f642' ... }`
* Fixed: Backspace, Delete, Select All, arrow keys, copy and cut in comment boxes of diff views act on the comment text instead of the file ("This view is read-only")
* Fixed: inline code and code blocks in comments and descriptions keep text in angle brackets, instead of losing it as HTML
* Fixed: plain emoji shortcodes such as `:thumbs_up:` are shown as the emoji
* Fixed: use of internal apis

## *1.1.0* - 2020-10-28
* Added: "Request Changes" action on pull requests; it changes to "Changes Requested" when the current user has requested changes, and is unavailable on your own pull requests
* Added: `@mention` tokens in comments show the mentioned account's name, resolved from comment authors, workspace members, or a direct user lookup
* Added: "Prune Work Trees" action in the Watched Projects footer removes PR work trees of all watched projects, then reports the totals in one notification
* Changed: the "Activities" tab of a pull request is renamed to "History", and its day groups and rows are reworked
* Fixed: pull-request descriptions, comment bodies and task text can be selected and copied
* Fixed: Conversation threads on files that no longer exist show the pull-request icon instead of a dead file link
* Fixed: the pull-request blue bar and the review bar ("Viewed", "Add comment") are shown in diff views of a pull-request worktree, including diffs opened from the "Pull request changes" view
* Fixed: the blue bar updates in editors that were open before the pull-request state was resolved
* Fixed: inline comment threads in diff views no longer fail with "layout state is not idle" when Compose content is measured

## *1.0.0* - 2020-10-25
* Initial release