package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import git4idea.commands.GitCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The switch the Settings screen uses to start and stop the job. Implemented by [DiffyMainBranchAutoUpdateService]. */
fun interface MainBranchAutoUpdateControl {
    fun onSettingChanged(enabled: Boolean)
}

/**
 * The "Auto-update main branches" job. Every [interval] (plus a random jitter) it visits each watched project that has a
 * local clone, one at a time. For each one it fetches only the Bitbucket main branch into
 * `refs/remotes/<remote>/<main>`, then fast-forwards the local main branch when [MainBranchSyncPolicy] says it is safe.
 * The result of each project is published in [statusBySlug] for the Watched Projects tab.
 *
 * Git runs unattended (see [GitCommandRunner.run]): no credential prompts and no IDE dialogs. Hooks are not skipped:
 * `git merge --no-verify` does not bypass `post-merge`, so a fast-forward of a checked-out main branch runs that hook.
 *
 * Only the main branch is handled. The destination branch of a pull request that is not the main branch, and the
 * branches of an open PR work tree, are kept current by [DiffyPrBranchSyncService] when a PR window opens or refreshes.
 * A PR work tree shares refs with its source clone, so it also benefits from this job. Do not add a second trigger for
 * the PR open case.
 */
@Service(Service.Level.APP)
class DiffyMainBranchAutoUpdateService(private val coroutineScope: CoroutineScope) : MainBranchAutoUpdateControl {
    internal var engine: PrBranchSyncEngine = PrBranchSyncEngine()
    internal var gitRunner: GitCommandRunner = PlatformGitCommandRunner()
    internal var openProjectLocator: OpenProjectLocator = OpenProjectLocatorImpl()
    internal var settingsReader: MainBranchSyncSettings = DiffySettingsMainBranchSyncSettings()
    internal var cloneResolver: WatchedCloneResolver = SettingsWatchedCloneResolver()
    internal var mainBranchNameResolver: MainBranchNameResolver = BitbucketMainBranchNameResolver()
    internal var unsavedDocumentChecker: UnsavedDocumentChecker = PlatformUnsavedDocumentChecker()
    internal var refresher: MainBranchRefresher = PlatformMainBranchRefresher()
    internal var failureNotifier: FetchFailureNotifier = BalloonFetchFailureNotifier()
    internal var clock: () -> Instant = { Clock.System.now() }
    internal var interval: Duration = INTERVAL
    internal var startupDelay: Duration = STARTUP_DELAY
    internal var jitter: () -> Duration = { Random.nextLong(from = 0, until = MAX_JITTER_MILLIS).milliseconds }
    internal var ioContext: CoroutineContext = Dispatchers.IO

    private val statuses = MutableStateFlow<Map<String, MainBranchSyncStatus>>(emptyMap())
    private val streaks = HashMap<String, FailureStreak>()
    private val passMutex = Mutex()
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val loopLock = Any()
    private var loopJob: Job? = null

    /** The last result per repository slug. Empty while the setting is off. */
    val statusBySlug: StateFlow<Map<String, MainBranchSyncStatus>> = statuses

    /** The number of passes that ran, for tests. */
    @Volatile
    internal var passCount: Int = 0
        private set

    private data class FailureStreak(val failures: Int, val notified: Boolean, val nextAttemptAt: Instant?)

    /** Starts the loop when the setting is on and the loop is not running yet. Safe to call many times. */
    fun ensureStarted() {
        if (!settingsReader.read().enabled) {
            return
        }
        synchronized(loopLock) {
            if (loopJob?.isActive == true) {
                return
            }
            LOG.info("Starting the main branch auto-update loop")
            loopJob = coroutineScope.launch { runLoop() }
        }
    }

    /** Starts the loop and runs a pass at once when [enabled], otherwise stops the loop and clears every result. */
    override fun onSettingChanged(enabled: Boolean) {
        if (enabled) {
            ensureStarted()
            requestRunNow(reason = "setting switched on")
            return
        }
        LOG.info("Stopping the main branch auto-update loop")
        synchronized(loopLock) {
            loopJob?.cancel()
            loopJob = null
        }
        trigger.tryReceive()
        clearStatuses()
    }

    /** Asks for a pass as soon as the current one ends. Overlapping requests merge into one. */
    fun requestRunNow(reason: String) {
        LOG.debug("Main branch update requested: $reason")
        trigger.trySend(Unit)
    }

    /** Forgets every result and failure streak, so stale rows are not shown. */
    fun clearStatuses() {
        synchronized(streaks) {
            streaks.clear()
        }
        statuses.value = emptyMap()
    }

    private suspend fun runLoop() {
        withTimeoutOrNull(startupDelay) { trigger.receive() }
        while (currentCoroutineContext().isActive) {
            runPass()
            withTimeoutOrNull(interval + jitter()) { trigger.receive() }
        }
    }

    /** Visits every watched clone once, one at a time. Overlapping calls wait for each other. */
    internal suspend fun runPass() {
        passMutex.withLock {
            val config = settingsReader.read()
            if (!config.enabled) {
                LOG.info("Not updating main branches: the setting is off")
                clearStatuses()
                return
            }
            if (config.workspace.isBlank() || config.projectsDirectory.isBlank()) {
                // Also covers a sign-out or any change that clears the workspace without a reset: no stale rows stay.
                LOG.info("Not updating main branches: the workspace or the projects directory is not set")
                clearStatuses()
                return
            }
            passCount++
            val results = mutableListOf<MainBranchSyncStatus>()
            for (slug in config.watchedSlugs) {
                val status = syncOneSafely(config = config, slug = slug)
                if (status == null) {
                    publish { current -> current - slug }
                } else {
                    results.add(status)
                    publish { current -> current + (slug to status) }
                }
            }
            publish { current -> current.filterKeys { slug -> slug in config.watchedSlugs } }
            logPass(results = results)
        }
    }

    private suspend fun publish(change: (Map<String, MainBranchSyncStatus>) -> Map<String, MainBranchSyncStatus>) {
        currentCoroutineContext().ensureActive()
        statuses.value = change(statuses.value)
    }

    private fun logPass(results: List<MainBranchSyncStatus>) {
        val updated = results.count { status -> status is MainBranchSyncStatus.Updated }
        val upToDate = results.count { status -> status is MainBranchSyncStatus.UpToDate }
        val failed = results.count { status -> status is MainBranchSyncStatus.FetchFailed }
        val skipped = results.size - updated - upToDate - failed
        LOG.info("Main branch update pass finished: updated=$updated, upToDate=$upToDate, skipped=$skipped, failed=$failed")
    }

    private suspend fun syncOneSafely(config: MainBranchSyncConfig, slug: String): MainBranchSyncStatus? {
        return try {
            withContext(ioContext) { syncOne(config = config, slug = slug) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Updating the main branch of a watched project failed unexpectedly", exception)
            MainBranchSyncStatus.Skipped(
                branch = statuses.value[slug]?.branch.orEmpty(),
                reason = MainBranchSkipReason.StateUnknown,
                path = null,
                checkedAt = clock()
            )
        }
    }

    private suspend fun syncOne(config: MainBranchSyncConfig, slug: String): MainBranchSyncStatus? {
        val root = cloneResolver.resolve(slug)
        if (root == null) {
            LOG.debug("No local clone for a watched project")
            return null
        }
        val remoteName = readRemoteName(root = root)
        val knownName = mainBranchNameResolver.resolve(workspace = config.workspace, slug = slug)
        val mainBranch = knownName ?: remoteName?.let { remote -> readMainBranchFromGit(root = root, remoteName = remote) }
        if (mainBranch == null) {
            return MainBranchSyncStatus.NoMainBranch(checkedAt = clock())
        }
        if (remoteName == null) {
            LOG.info("Not updating the main branch: the clone has no remote")
            return skipped(branch = mainBranch, reason = MainBranchSkipReason.StateUnknown)
        }
        val backoff = streaks[slug]?.nextAttemptAt
        if (backoff != null && clock() < backoff) {
            return statuses.value[slug] ?: MainBranchSyncStatus.FetchFailed(branch = mainBranch, checkedAt = clock())
        }
        val fetched = engine.fetch(
            project = null,
            prWorktreePath = root,
            remoteName = remoteName,
            remoteUrl = null,
            branches = listOf(mainBranch),
            background = true
        )
        if (!fetched) {
            return onFetchFailed(slug = slug, branch = mainBranch)
        }
        onFetchSucceeded(slug = slug)
        return updateLocalBranch(root = root, remoteName = remoteName, mainBranch = mainBranch)
    }

    private fun updateLocalBranch(root: Path, remoteName: String, mainBranch: String): MainBranchSyncStatus {
        if (isGitOperationInProgress(gitDir = resolveGitDir(root = root))) {
            LOG.info("Not updating the main branch: a git operation is in progress")
            return skipped(branch = mainBranch, reason = MainBranchSkipReason.GitOperationInProgress)
        }
        val status = engine.inspectSingle(project = null, workingDir = root, remoteName = remoteName, branch = mainBranch, background = true)
        if (status is BranchSyncStatus.Unknown) {
            return skipped(branch = mainBranch, reason = MainBranchSkipReason.StateUnknown)
        }
        if (status is BranchSyncStatus.NoRemoteBranch) {
            return MainBranchSyncStatus.NoMainBranch(checkedAt = clock())
        }
        val action = MainBranchSyncPolicy.decide(status = status, cloneRoot = root, gitOperationInProgress = false)
        val openProject = openProjectLocator.findOpenProjectForPath(root)
        if (action is PrBranchSyncAction.MergeFastForwardInWorktree && openProject != null && unsavedDocumentChecker.hasUnsavedDocumentUnder(root)) {
            LOG.info("Not updating the main branch: an editor has unsaved changes")
            return skipped(branch = mainBranch, reason = MainBranchSkipReason.UncommittedChanges)
        }
        val result = engine.execute(
            project = null,
            workingDir = root,
            remoteName = remoteName,
            branch = mainBranch,
            status = status,
            action = action,
            remoteUrl = null,
            background = true
        )
        val outcome = toStatus(branch = mainBranch, status = status, result = result)
        if (result is PrBranchSyncStepResult.Updated && openProject != null) {
            refresher.refresh(project = openProject, root = root, filesChanged = result.path != null)
        }
        return outcome
    }

    private fun toStatus(branch: String, status: BranchSyncStatus, result: PrBranchSyncStepResult): MainBranchSyncStatus {
        val now = clock()
        return when (result) {
            PrBranchSyncStepResult.NotNeeded -> {
                MainBranchSyncStatus.UpToDate(branch = branch, checkedAt = now)
            }
            is PrBranchSyncStepResult.Updated -> {
                val commits = (status as? BranchSyncStatus.Behind)?.commitsBehind ?: 0
                MainBranchSyncStatus.Updated(branch = branch, commits = commits, checkedAt = now)
            }
            is PrBranchSyncStepResult.Skipped -> {
                LOG.info("Not updating the main branch: ${result.reason}")
                MainBranchSyncStatus.Skipped(branch = branch, reason = mapReason(result.reason), path = result.path, checkedAt = now)
            }
            is PrBranchSyncStepResult.Failed -> {
                LOG.info("Updating the main branch failed; it is left as it is")
                skipped(branch = branch, reason = MainBranchSkipReason.StateUnknown)
            }
        }
    }

    private fun mapReason(reason: PrBranchSyncSkipReason): MainBranchSkipReason {
        return when (reason) {
            PrBranchSyncSkipReason.UncommittedChanges -> MainBranchSkipReason.UncommittedChanges
            PrBranchSyncSkipReason.LocalCommitsNotOnRemote -> MainBranchSkipReason.LocalCommitsNotOnRemote
            PrBranchSyncSkipReason.CheckedOutInOtherWorktree -> MainBranchSkipReason.CheckedOutInOtherWorktree
            PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown -> MainBranchSkipReason.StateUnknown
        }
    }

    private fun skipped(branch: String, reason: MainBranchSkipReason): MainBranchSyncStatus.Skipped {
        return MainBranchSyncStatus.Skipped(branch = branch, reason = reason, path = null, checkedAt = clock())
    }

    private fun onFetchFailed(slug: String, branch: String): MainBranchSyncStatus {
        val now = clock()
        val shouldNotify: Boolean
        val failures: Int
        synchronized(streaks) {
            val previous = streaks[slug] ?: FailureStreak(failures = 0, notified = false, nextAttemptAt = null)
            failures = previous.failures + 1
            shouldNotify = failures == NOTIFY_AFTER_FAILURES && !previous.notified
            streaks[slug] = FailureStreak(
                failures = failures,
                notified = previous.notified || shouldNotify,
                nextAttemptAt = if (failures >= NOTIFY_AFTER_FAILURES) now + BACKOFF else null
            )
        }
        LOG.info("Fetching the main branch failed: consecutiveFailures=$failures")
        if (shouldNotify) {
            failureNotifier.notifyFetchFailed(slug = slug, failures = failures)
        }
        return MainBranchSyncStatus.FetchFailed(branch = branch, checkedAt = now)
    }

    private fun onFetchSucceeded(slug: String) {
        synchronized(streaks) {
            streaks.remove(slug)
        }
    }

    private fun readRemoteName(root: Path): String? {
        val result = gitRunner.run(project = null, workingDir = root, command = GitCommand.REMOTE, parameters = emptyList(), background = true)
        if (!result.success) {
            return null
        }
        val remotes = GitCommandOutput.withoutCommandLine(result.combinedOutput)
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
        return remotes.firstOrNull { remote -> remote == ORIGIN } ?: remotes.firstOrNull()
    }

    private fun readMainBranchFromGit(root: Path, remoteName: String): String? {
        val head = gitRunner.run(
            project = null,
            workingDir = root,
            command = GitCommand.REV_PARSE,
            parameters = listOf("--abbrev-ref", "refs/remotes/$remoteName/HEAD"),
            background = true
        )
        if (head.success) {
            val name = GitCommandOutput.lastOutputLine(head.combinedOutput)?.removePrefix("$remoteName/")
            if (!name.isNullOrBlank() && name != "HEAD") {
                return name
            }
        }
        return FALLBACK_NAMES.firstOrNull { name ->
            gitRunner.run(
                project = null,
                workingDir = root,
                command = GitCommand.REV_PARSE,
                parameters = listOf("--verify", "--quiet", "refs/remotes/$remoteName/$name"),
                background = true
            ).success
        }
    }

    private fun resolveGitDir(root: Path): Path {
        val dotGit = root.resolve(".git")
        if (Files.isRegularFile(dotGit)) {
            val pointer = runCatching { parseGitDirPointer(Files.readString(dotGit)) }.getOrNull()
            if (pointer != null) {
                return root.resolve(pointer).normalize()
            }
        }
        return dotGit
    }

    companion object {
        private val LOG = logger<DiffyMainBranchAutoUpdateService>()

        /** The time between two passes, before the jitter. */
        val INTERVAL: Duration = 5.minutes

        /** The longest wait after startup before the first pass. */
        val STARTUP_DELAY: Duration = 30.seconds

        private const val MAX_JITTER_MILLIS = 30_000L
        private const val NOTIFY_AFTER_FAILURES = 3
        private val BACKOFF = 30.minutes
        private const val ORIGIN = "origin"
        private val FALLBACK_NAMES = listOf("main", "master")

        fun getInstance(): DiffyMainBranchAutoUpdateService {
            return service()
        }
    }
}