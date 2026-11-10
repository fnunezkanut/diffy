# Diffy - Bitbucket PR Review

![diffy](docs/diffy.jpeg)

![plugin icon](docs/icon16.png) **Diffy - Bitbucket PR Review**. Seamless Bitbucket Cloud PR reviews inside your JetBrains IDE..

* [Install **Diffy - Bitbucket PR Review** from the JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35502-diffy--bitbucket-pr-review)
* [Chrome Extension](diffy-chrome-ext/README.md) Easily launch PR reviews with Diffy in IntelliJ straight from Bitbucket.org.


## Main features

Helps you review Bitbucket Cloud pull requests without leaving your Jetbrains IDE (and its excellent tooling) with help of [Work Trees](docs/worktrees.md), inline comments and much more.


* 🔐 **Secure authentication** signs you in to Bitbucket Cloud with OAuth so tokens are obtained and stored safely through the IDE.
* ![plugin icon](docs/icon16.png)  A **Diffy - Bitbucket PR Review** tool window on the bottom strip keeps pull request triage inside IntelliJ instead of the browser.
* 🌳 Opening a PR spins up a separate **PR Work Tree** project window with its own git [work tree](docs/worktrees.md) so reviews never clash with your main branch or other PRs.
* 📂 The **Current Project** tab shows open and completed PRs for the repo in your IDE, with repo links, open-PR counts, reviewer avatars, refresh, and persistent **Exclude** filters and a **Created by** dropdown with an **Assigned to me** option.
* 🧭 The **Workspace** tab merges PRs you opened and review assignments from watched projects into one newest-first list across your Bitbucket workspace.
* 👀 **Watched Projects** controls which repositories contribute to the workspace queue, with search by slug, folder, PR number, or branch and a master checkbox for matching rows.
* 🕘 **Recent Projects** lists the IDE recent projects and their groups, with search, the project icon, path, and current branch; click a project to open or focus it. Each row has a button to remove it from the list, and PR work trees also have a button that removes the work tree.
* 📋 Each PR gets a dedicated **`<prNum>.pr`** tab plus a blue header stripe with line-change stats, refresh, file changes, and merge, approve, draft, or decline actions.
* 🕐 The PR **Activity** timeline mixes commits, approvals, tasks, builds, and file or inline comment threads in time order, with a box to add PR-scoped comments and reply in threads.
* 🤖 Every comment box has a **Send this comment to Air** button (needs the JetBrains Air plugin). It puts the file location and the comment into the Air chat input, ready to send.
* 🤖 Open pull requests have an **Explain this PR with AI** button next to Edit Description (needs the JetBrains Air plugin). It puts a prompt in a new Air chat that asks the agent to explain the pull request with Mermaid diagrams. Press Enter to run it.
* 📁 The **PR Changes** tool window lists every file in the diff; picking a file opens it in the editor or diff view driven from the stripe’s **Show pull request file changes** button.
* ✏️ **Modified File View** and **Diff View** surface existing comments and let you add line-level feedback from the gutter or context menu, view add/remove counts, and mark files reviewed.
* 📝 **Rendered Markdown side by side diff view** shows the rendered result of changed Markdown files next to each other, so documentation changes can be reviewed as readers will see them. The **Wrap Lines** button in the review bar of a Markdown diff wraps long lines so there is no horizontal scrolling, and remembers your choice.
* 🚀 **Create Pull Request** walks you through title, description, source and destination branches, reviewers (including defaults), remote cleanup after merge, then opens the new PR in a work tree window.
* 🔔 **Notifications** lists the Bitbucket notifications captured by the [Diffy Chrome extension](https://github.com/fnunezkanut/diffy/tree/main/diffy-chrome-ext), newest first, with time, type, pull request, actor, and message


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
