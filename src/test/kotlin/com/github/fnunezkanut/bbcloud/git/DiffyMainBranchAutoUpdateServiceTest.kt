package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Tag("unit")
class DiffyMainBranchAutoUpdateServiceTest {
    private data class Call(val workingDir: Path, val command: GitCommand, val parameters: List<String>, val background: Boolean)

    /** A scripted git for one clone with a `main` branch; it records every call. */
    private class FakeGit : GitCommandRunner {
        val calls = mutableListOf<Call>()
        var fetchSucceeds = true
        var remoteRevision: String? = "new"
        var localRevision: String? = "old"
        var counts = "0\t3"
        var worktreeList: String = ""
        var dirty = false
        var mergeSucceeds = true

        override fun run(
            project: Project?,
            workingDir: Path,
            command: GitCommand,
            parameters: List<String>,
            remoteUrl: String?,
            background: Boolean
        ): GitCommandResult {
            calls += Call(workingDir = workingDir, command = command, parameters = parameters, background = background)
            return when (command) {
                GitCommand.REMOTE -> ok("origin")
                GitCommand.FETCH -> if (fetchSucceeds) ok("") else bad()
                GitCommand.REV_PARSE -> revParse(parameters.last())
                GitCommand.REV_LIST -> ok(counts)
                GitCommand.WORKTREE -> ok(worktreeList)
                GitCommand.STATUS -> ok(if (dirty) " M file.txt" else "")
                GitCommand.MERGE -> if (mergeSucceeds) ok("") else bad()
                else -> ok("")
            }
        }

        private fun revParse(argument: String): GitCommandResult {
            val ref = argument.removeSuffix("^{commit}")
            val revision = when {
                ref.startsWith("refs/remotes/origin/") -> remoteRevision
                ref.startsWith("refs/heads/") -> localRevision
                else -> null
            }
            return if (revision == null) bad() else ok(revision)
        }

        fun commands(command: GitCommand): List<Call> {
            return calls.filter { call -> call.command == command }
        }

        private fun ok(output: String): GitCommandResult {
            return GitCommandResult(success = true, combinedOutput = listOf("$ git x", output).filter { part -> part.isNotEmpty() }.joinToString("\n"))
        }

        private fun bad(): GitCommandResult {
            return GitCommandResult(success = false, combinedOutput = "$ git x\nfatal: nope")
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()
    private var now: Instant = Instant.parse("2030-01-01T00:00:00Z")
    private var enabled = true
    private var cloneRoot: Path? = null
    private var openProject: Project? = null
    private var unsavedUnderRoot = false
    private val notifications = mutableListOf<Pair<String, Int>>()
    private val refreshes = mutableListOf<Boolean>()

    @AfterEach
    fun tearDown() {
        scopes.forEach { scope -> scope.cancel() }
    }

    private fun service(git: FakeGit, root: Path?, mainBranch: String? = "main"): DiffyMainBranchAutoUpdateService {
        cloneRoot = root
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return DiffyMainBranchAutoUpdateService(coroutineScope = scope).apply {
            engine = PrBranchSyncEngine(gitRunner = git, openProjectLocator = { null })
            gitRunner = git
            openProjectLocator = OpenProjectLocator { openProject }
            settingsReader = MainBranchSyncSettings {
                MainBranchSyncConfig(enabled = enabled, workspace = "acme", projectsDirectory = "/projects", watchedSlugs = listOf("repo"))
            }
            cloneResolver = WatchedCloneResolver { cloneRoot }
            mainBranchNameResolver = MainBranchNameResolver { _, _ -> mainBranch }
            unsavedDocumentChecker = UnsavedDocumentChecker { unsavedUnderRoot }
            refresher = MainBranchRefresher { _, _, filesChanged -> refreshes += filesChanged }
            failureNotifier = FetchFailureNotifier { slug, failures -> notifications += slug to failures }
            clock = { now }
        }
    }

    private fun cloneIn(dir: Path): Path {
        Files.createDirectories(dir.resolve(".git"))
        return dir
    }

    private fun statusOf(service: DiffyMainBranchAutoUpdateService): MainBranchSyncStatus? {
        return service.statusBySlug.value["repo"]
    }

    private fun worktreeBlock(path: Path): String {
        return "worktree ${path.toAbsolutePath().normalize()}\nHEAD abc\nbranch refs/heads/main"
    }

    @Test
    fun `runPass - setting off should run no git command`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir))
        enabled = false

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.calls.shouldBeEmpty()
            service.statusBySlug.value shouldBe emptyMap()
        }
    }

    @Test
    fun `runPass - behind and not checked out should update the ref and report Updated`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        val update = git.commands(GitCommand.UPDATE_REF).single()
        assertSoftly {
            update.parameters.takeLast(3) shouldBe listOf("refs/heads/main", "new", "old")
            statusOf(service) shouldBe MainBranchSyncStatus.Updated(branch = "main", commits = 3, checkedAt = now)
            git.commands(GitCommand.MERGE).shouldBeEmpty()
        }
    }

    @Test
    fun `runPass - every git command should run in background mode and fetch only the main branch`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.calls.all { call -> call.background } shouldBe true
            git.commands(GitCommand.FETCH).single().parameters shouldBe listOf("origin", "+refs/heads/main:refs/remotes/origin/main")
        }
    }

    @Test
    fun `runPass - checked out and clean should fast-forward in the clone root`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { worktreeList = worktreeBlock(dir) }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        val merge = git.commands(GitCommand.MERGE).single()
        assertSoftly {
            merge.parameters shouldBe listOf("--ff-only", "refs/remotes/origin/main")
            merge.workingDir shouldBe dir
            statusOf(service).shouldBeInstanceOf<MainBranchSyncStatus.Updated>()
        }
    }

    @Test
    fun `runPass - checked out with uncommitted changes should skip`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply {
            worktreeList = worktreeBlock(dir)
            dirty = true
        }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.commands(GitCommand.MERGE).shouldBeEmpty()
            (statusOf(service) as MainBranchSyncStatus.Skipped).reason shouldBe MainBranchSkipReason.UncommittedChanges
        }
    }

    @Test
    fun `runPass - diverged branch should skip`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { counts = "2\t3" }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.commands(GitCommand.UPDATE_REF).shouldBeEmpty()
            (statusOf(service) as MainBranchSyncStatus.Skipped).reason shouldBe MainBranchSkipReason.LocalCommitsNotOnRemote
        }
    }

    @Test
    fun `runPass - main checked out in a pr work tree should skip and change nothing there`(
        @TempDir dir: Path,
        @TempDir other: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { worktreeList = worktreeBlock(other) }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            (statusOf(service) as MainBranchSyncStatus.Skipped).reason shouldBe MainBranchSkipReason.CheckedOutInOtherWorktree
            git.calls.none { call -> call.workingDir == other && call.command != GitCommand.STATUS } shouldBe true
            git.commands(GitCommand.MERGE).shouldBeEmpty()
        }
    }

    @Test
    fun `runPass - rebase in progress should skip`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val root = cloneIn(dir)
        Files.createDirectories(root.resolve(".git").resolve("rebase-merge"))
        val service = service(git = git, root = root)

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            (statusOf(service) as MainBranchSyncStatus.Skipped).reason shouldBe MainBranchSkipReason.GitOperationInProgress
            git.commands(GitCommand.UPDATE_REF).shouldBeEmpty()
        }
    }

    @Test
    fun `runPass - missing local main should create a tracking branch`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { localRevision = null }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.commands(GitCommand.BRANCH).single().parameters shouldBe listOf("--track", "main", "refs/remotes/origin/main")
            statusOf(service) shouldBe MainBranchSyncStatus.Updated(branch = "main", commits = 0, checkedAt = now)
        }
    }

    @Test
    fun `runPass - unsaved editor under an open clone should skip the merge`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { worktreeList = worktreeBlock(dir) }
        val service = service(git = git, root = cloneIn(dir))
        openProject = mockk<Project>(relaxed = true)
        unsavedUnderRoot = true

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            git.commands(GitCommand.MERGE).shouldBeEmpty()
            (statusOf(service) as MainBranchSyncStatus.Skipped).reason shouldBe MainBranchSkipReason.UncommittedChanges
        }
    }

    @Test
    fun `runPass - a fast-forward of an open clone should refresh the ide`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { worktreeList = worktreeBlock(dir) }
        val service = service(git = git, root = cloneIn(dir))
        openProject = mockk<Project>(relaxed = true)

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        refreshes shouldBe listOf(true)
    }

    @Test
    fun `runPass - up to date should report UpToDate`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { counts = "0\t0" }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        statusOf(service) shouldBe MainBranchSyncStatus.UpToDate(branch = "main", checkedAt = now)
    }

    @Test
    fun `runPass - unknown main branch name should report NoMainBranch`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { remoteRevision = null }
        val service = service(git = git, root = cloneIn(dir), mainBranch = null)

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        statusOf(service) shouldBe MainBranchSyncStatus.NoMainBranch(checkedAt = now)
    }

    @Test
    fun `runPass - a project without a clone should have no status`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = null)

        // WHEN
        runBlocking { service.runPass() }

        // THEN
        assertSoftly {
            service.statusBySlug.value shouldBe emptyMap()
            git.calls.shouldBeEmpty()
        }
    }

    @Test
    fun `runPass - fetch failures should back off for 30 minutes and notify once per streak`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { fetchSucceeds = false }
        val service = service(git = git, root = cloneIn(dir))

        // WHEN three failures happen in a row
        repeat(3) { runBlocking { service.runPass() } }
        val fetchesAfterThree = git.commands(GitCommand.FETCH).size
        val notificationsAfterThree = notifications.toList()

        // AND a pass runs inside the back-off window
        now += 10.minutes
        runBlocking { service.runPass() }
        val fetchesInBackoff = git.commands(GitCommand.FETCH).size

        // AND passes run after the back-off ended and keep failing
        now += 25.minutes
        repeat(2) { runBlocking { service.runPass() } }
        val notificationsAfterMore = notifications.toList()

        // THEN
        assertSoftly {
            fetchesAfterThree shouldBe 3
            notificationsAfterThree shouldBe listOf("repo" to 3)
            fetchesInBackoff shouldBe 3
            git.commands(GitCommand.FETCH).size shouldBe 4
            notificationsAfterMore shouldBe listOf("repo" to 3)
            statusOf(service).shouldBeInstanceOf<MainBranchSyncStatus.FetchFailed>()
        }
    }

    @Test
    fun `runPass - a success should end the streak so a new streak notifies again`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit().apply { fetchSucceeds = false }
        val service = service(git = git, root = cloneIn(dir))
        repeat(3) { runBlocking { service.runPass() } }
        now += 31.minutes
        git.fetchSucceeds = true
        runBlocking { service.runPass() }

        // WHEN
        git.fetchSucceeds = false
        repeat(3) { runBlocking { service.runPass() } }

        // THEN
        notifications shouldBe listOf("repo" to 3, "repo" to 3)
    }

    @Test
    fun `onSettingChanged - false should clear the statuses and stop the loop`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir))
        runBlocking { service.runPass() }
        val before = service.statusBySlug.value.size

        // WHEN
        service.onSettingChanged(enabled = false)

        // THEN
        assertSoftly {
            before shouldBe 1
            service.statusBySlug.value shouldBe emptyMap()
        }
    }

    @Test
    fun `requestRunNow - two requests should run a single extra pass`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir)).apply {
            startupDelay = 1.minutes
            interval = 10.minutes
            jitter = { Duration.ZERO }
        }

        // WHEN both requests arrive before the loop can take either of them
        service.requestRunNow(reason = "first")
        service.requestRunNow(reason = "second")
        service.ensureStarted()
        waitUntil { service.passCount >= 1 }
        Thread.sleep(300)

        // THEN
        service.passCount shouldBe 1
    }

    @Test
    fun `ensureStarted - should run a pass after the startup delay`(
        @TempDir dir: Path
    ) {
        // GIVEN
        val git = FakeGit()
        val service = service(git = git, root = cloneIn(dir)).apply {
            startupDelay = 50.milliseconds
            interval = 10.minutes
            jitter = { Duration.ZERO }
        }

        // WHEN
        service.ensureStarted()
        service.ensureStarted()
        waitUntil { service.passCount >= 1 }

        // THEN
        service.statusBySlug.value.keys shouldContain "repo"
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }
}