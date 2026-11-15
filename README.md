# Diffy - Bitbucket PR Review

![diffy](docs/diffy.jpeg)

![plugin icon](docs/icon16.png) **Diffy - Bitbucket PR Review**. Seamless Bitbucket Cloud PR reviews inside your JetBrains IDE..

* [Install **Diffy - Bitbucket PR Review** from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35502-diffy--bitbucket-pr-review)

Optionally there are a couple of browser extensions to enhance this plugin:
* [Install Firefox Extension](https://addons.mozilla.org/addon/diffy-bitbucket-pr-review/) - [more info](diffy-chrome-ext/README.md)
* Chrome Extension (under development) - [more info](diffy-chrome-ext/README.md) 

They permit one to launch PR reviews with Diffy in IntelliJ straight from Bitbucket.org and sync notifications.


## Main features

Helps you review Bitbucket Cloud pull requests without leaving your Jetbrains IDE (and its excellent tooling) with help of [Work Trees](docs/worktrees.md), inline comments and much more.


* 🔐 **Secure sign-in**: connects to Bitbucket Cloud with OAuth, and the IDE stores your tokens.
* ![plugin icon](docs/icon16.png) **Diffy - Bitbucket PR Review tool window**: triage pull requests from the bottom tool window strip instead of the browser.
* 🌳 **PR Work Trees**: each PR opens in its own project window backed by a separate git [work tree](docs/worktrees.md), so a review never touches your main checkout or other PRs.
* 📋 **Pull Requests tab**: one list of PRs from the current repository, your own PRs across the workspace, and your watched projects, open PRs first. Filter by author, assignee, or project, or start a new PR.
* 👀 **Watched Projects tab**: choose which repositories feed the Pull Requests tab. Search by slug, folder, PR number, or branch, and use the master checkbox to toggle every matching row.
* 🕘 **Recent Projects tab**: your IDE recent projects and PR Work Tree projects in one list.
* 🔖 **PR editor tab**: each PR opens as a **`<prNum>.pr`** tab with a blue header stripe that shows line-change stats and has buttons to refresh, show file changes, merge, approve, toggle draft, or decline.
* 🕐 **Activity timeline**: commits, approvals, tasks, builds, and file and inline comment threads in time order. Add PR-level comments or reply in any thread.
* 🤖 **Send this comment to Air**: a button on every comment box that copies the file location and the comment into the JetBrains Air chat input, ready to send. Requires the JetBrains Air plugin.
* ✨ **Explain this PR with AI**: a button next to Edit Description on open PRs that starts a new Air chat with a prompt asking the agent to explain the PR with Mermaid diagrams. Press Enter to run it. Requires the JetBrains Air plugin.
* 📁 **PR Changes tool window**: lists every file in the diff. Open it with the stripe's **Show pull request file changes** button, then pick a file to view it in the editor or in a diff view.
* ✏️ **Modified File View and Diff View**: see existing comments, add line comments from the gutter or context menu, check added and removed line counts, and mark files as reviewed.
* 📝 **Rendered Markdown diff**: changed Markdown files render side by side, so you review documentation the way readers will see it. The **Wrap Lines** button in the review bar removes horizontal scrolling and remembers your choice.
* 🚀 **Create Pull Request**: set the title, description, source and destination branches, and reviewers (defaults included), choose whether to clean up the remote branch after merge, then open the new PR in a work tree window.
* 🔔 **Notifications tab**: Bitbucket notifications captured by the [Diffy Chrome extension](https://github.com/fnunezkanut/diffy/tree/main/diffy-chrome-ext) or [Firefox extension](https://github.com/fnunezkanut/diffy/tree/main/diffy-firefox-ext), newest first, with time, type, pull request, actor, and message.


## Notice
This project is not affiliated, associated, authorized, endorsed by, or in any way officially connected with Atlassian, or any of its subsidiaries or its affiliates.

## Changelog
[Changelog](CHANGELOG.md)


## Contributions

Pull requests and forks are welcome :)

Leave any feedback on [Issues page](https://github.com/fnunezkanut/diffy/issues) but please keep in mind this is a **free** plugin maintained on free time, with the selfish aim of helping myself review pull requests faster.
In some cases Atlassian *public* apis are lacking so feature parity with web version is hard to reach. 
This plugin is not for the hosted version of Bitbucket and only works with Bitbucket Cloud apis.


## Plugin Development

### Requirements

- **JDK 25.** The build uses `jvmToolchain(25)`, matching the JetBrains Runtime bundled with IntelliJ IDEA 2026.3. [`.sdkmanrc`](.sdkmanrc) pins `java=25.0.4-amzn`; run `sdk env` to switch to it.
- **Gradle 9.8.2.** Provided by the Gradle wrapper (`./gradlew`), so no separate install is needed. It is also pinned in `.sdkmanrc`.
- **IntelliJ IDEA 2026.3 (build `263.7307.200`) and newer**

### Build

See [ARCHITECTURE.md](ARCHITECTURE.md) for how the source is organized.

```bash
./gradlew build
```

Unit tests use **JUnit Jupiter 6.1.3** and Kotest assertions, with the `@Tag("unit")` annotation—not JUnit 4. `./gradlew build` runs them through Gradle’s `check` lifecycle (after the formatter on local machines) and runs `buildPlugin`, which produces the installable plugin at `build/distributions/bbcloud-<version>.zip`. The first build downloads IntelliJ IDEA 2026.3 into the Gradle cache, so it takes a while.

`./gradlew buildPlugin` is equivalent to the packaging step alone if you only need the ZIP after a prior compile.

To check binary compatibility with 2026.3 and newer IDE releases (including EAPs), run the JetBrains Plugin Verifier:

```bash
./gradlew verifyPlugin
```

The first run downloads several IDE builds. Reports are written to `build/reports/pluginVerifier`.

### Linting

Kotlin style is enforced with [ktlint](https://pinterest.github.io/ktlint/latest/) via the [Kotlinter](https://github.com/jeremymailen/kotlinter-gradle) Gradle plugin. Rule overrides live in [`.editorconfig`](.editorconfig).

```bash
./gradlew lint        # check only (alias for lintKotlin)
./gradlew format      # auto-format (alias for formatKotlin)
```

On a local machine, `./gradlew build` runs the formatter first, then compile and tests. In CI (`CI` or `JENKINS_URL` set), lint violations fail the build. The `build` task also runs `lintKotlin` through Gradle’s `check` lifecycle.

### Test in IntelliJ

#### Your own IDE

1. Build the plugin with `./gradlew build`.
2. In IntelliJ IDEA, open **Settings > Plugins**, click the gear icon, and choose **Install Plugin from Disk...**.
3. Select the zip from `build/distributions` and restart the IDE if prompted.

Always install the zip, not a jar from `build/libs`. The jars there contain only the plugin classes, without bundled libraries such as OkHttp, and fail at runtime with `NoClassDefFoundError`.


### Troubleshooting

IntelliJ > Help > Show Log in Finder > open `idea.log`

Logs for this plugin appear under the `com.github.fnunezkanut.bbcloud` namespace (`bbcloud` was the initial short name for this plugin)
