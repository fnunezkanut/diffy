package com.github.fnunezkanut.bbcloud.ui.air

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class AirRebasePullRequestPromptTest {
    private val quote = '"'
    private val headSha = "a".repeat(40)
    private val backupRef = "refs/diffy/backups/pr-7/1795694861000"

    private fun request(
        sourceBranch: String = "feature/foo",
        destinationBranch: String = "main",
        remoteName: String = "origin",
        repositoryPath: String? = "/tmp/.worktrees/repo--pr-7",
        headSha: String = this.headSha,
        backupRef: String = this.backupRef,
        wipBackupRef: String? = null
    ): RebasePullRequestRequest {
        return RebasePullRequestRequest(
            pullRequestId = 7L,
            sourceBranch = sourceBranch,
            destinationBranch = destinationBranch,
            remoteName = remoteName,
            repositoryPath = repositoryPath,
            headSha = headSha,
            backupRef = backupRef,
            wipBackupRef = wipBackupRef
        )
    }

    @Test
    fun `build - should list the git commands in order`() {
        // GIVEN
        val request = request()

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        val commands = listOf(
            "git fetch origin",
            "git rev-parse HEAD",
            "git rev-list --count HEAD..origin/feature/foo",
            "git stash push --include-untracked --message ${quote}diffy: before rebase of PR #7$quote",
            "git rebase origin/main",
            "git push --force-with-lease origin feature/foo",
            "git stash pop"
        )
        commands.forEach { command -> prompt shouldContain command }
        commands.map { command -> prompt.indexOf(command) }.shouldBeStrictlyIncreasing()
    }

    @Test
    fun `build - should use the given destination branch and remote`() {
        // GIVEN
        val request = request(destinationBranch = "develop", remoteName = "upstream")

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("git rebase upstream/develop")
            shouldContain("git fetch upstream")
            shouldNotContain("origin/main")
            shouldNotContain("origin")
        }
    }

    @Test
    fun `build - should name the repository path when it is known`() {
        // GIVEN
        val request = request(repositoryPath = "/tmp/.worktrees/repo--pr-7")

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("in the local repository at `/tmp/.worktrees/repo--pr-7`")
            shouldNotContain("current working directory")
        }
    }

    @Test
    fun `build - should fall back to the working directory without a repository path`() {
        // GIVEN
        val request = request(repositoryPath = null)

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("in the current working directory, using this exact sequence:")
            shouldNotContain("local repository at")
        }
    }

    @Test
    fun `build - should contain every rule`() {
        // GIVEN
        val request = request()

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("Do not ask for permission between steps or for routine conflict resolutions.")
            shouldContain("Run every git command as its own separate command")
            shouldContain("(a) a conflict has unclear business or domain intent")
            shouldContain("(b) tests or builds fail after resolving conflicts")
            shouldContain("(c) `git push --force-with-lease` is rejected due to remote changes")
            shouldContain("(d) a check in steps 1, 2, or 8 tells you to stop")
            shouldContain("run `git rebase --abort` first unless I tell you otherwise, then restore the stash from step 3")
            shouldContain("do not create new branches, and do not change the pull request on Bitbucket")
            shouldContain("summarize all conflicted files and how each was resolved")
        }
    }

    @Test
    fun `build - should render a branch name with a backtick as a longer code span`() {
        // GIVEN
        val request = request(sourceBranch = "feat`x")

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("(``feat`x``)")
            shouldContain("``git push --force-with-lease origin feat`x``")
        }
    }

    @Test
    fun `codeSpan - should pad text that starts or ends with a backtick`() {
        // GIVEN
        val text = "`edge"

        // WHEN
        val span = AirRebasePullRequestPrompt.codeSpan(text)

        // THEN
        span shouldBe "`` `edge ``"
    }

    @Test
    fun `build - should ask the agent to confirm HEAD against the backup`() {
        // GIVEN
        val request = request()

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("confirm that it prints `$headSha`")
            shouldContain("Diffy saved this commit as `$backupRef`")
            shouldContain("the branch changed after the backup was taken: stop and ask me.")
        }
    }

    @Test
    fun `build - should protect the backup refs and name the restore command`() {
        // GIVEN
        val request = request()

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("Do not delete, move or update any ref under `refs/diffy/`")
            shouldContain("`git reset --hard $backupRef`")
            shouldNotContain("git stash apply")
        }
    }

    @Test
    fun `build - should also restore the uncommitted changes when a wip backup exists`() {
        // GIVEN
        val wipRef = "$backupRef-wip"
        val request = request(wipBackupRef = wipRef)

        // WHEN
        val prompt = AirRebasePullRequestPrompt.build(request)

        // THEN
        assertSoftly(prompt) {
            shouldContain("the uncommitted changes backup `$wipRef`")
            shouldContain("`git reset --hard $backupRef && git stash apply $wipRef`")
        }
    }
}