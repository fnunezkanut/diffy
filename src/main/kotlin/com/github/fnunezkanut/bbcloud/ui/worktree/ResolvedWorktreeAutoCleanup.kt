package com.github.fnunezkanut.bbcloud.ui.worktree

import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListResult
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService
import com.github.fnunezkanut.bbcloud.git.OpenProjectLocatorImpl
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeListing
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeResolver
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCandidate
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCleaner
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCleanupSelection
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCleanupSummary
import com.github.fnunezkanut.bbcloud.project.WatchedProjectWorktree
import com.github.fnunezkanut.bbcloud.project.WatchedWorktreeRemover
import com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.IntelliJProjectCloser
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestRepositoryKey
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Removes the work trees of merged or declined pull requests in the background, without any prompt, after the Pull Requests
 * listed a repository live (the current project and every watched project). An IDE window open on a work tree is closed
 * first and uncommitted changes are discarded, as in [ResolvedWorktreeCleaner].
 *
 * To keep the request budget small, a repository without local PR work trees costs no Bitbucket request at all. Only a
 * repository that has some asks Bitbucket for the complete open set, so a reopened pull request is never removed.
 */
internal class ResolvedWorktreeAutoCleanup(
    private val configurationService: DiffyConfigurationService,
    private val openPullRequestService: DiffyOpenPullRequestService,
    private val worktreeService: DiffyWorktreeService,
    private val worktreeOpener: PullRequestWorktreeOpener,
    private val coroutineScope: CoroutineScope,
    private val isPruneAllInProgress: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val listWorktreesForRepo: (workTreeDirectory: Path, repoSlug: String) -> List<WatchedProjectWorktree> =
        { workTreeDirectory, repoSlug ->
            PullRequestWorktreeListing.worktreesForRepo(workTreeDirectory = workTreeDirectory, repoSlug = repoSlug)
        },
    private val registeredWorktrees: suspend (sourceCheckoutRoot: Path) -> List<Path> =
        { sourceCheckoutRoot -> worktreeService.registeredWorktrees(sourceCheckoutRoot = sourceCheckoutRoot) },
    private val resolveSourceCheckout: (workspace: String, repoSlug: String) -> Path? =
        { workspace, repoSlug ->
            PullRequestWorktreeResolver.resolveSourceCheckout(
                mappings = configurationService.watchedProjectsForCurrentWorkspaceWithLocalPaths(),
                projectsDirectory = configurationService.projectsDirectoryOrNull(),
                workspace = workspace,
                repoSlug = repoSlug,
                isDirectory = { path -> Files.isDirectory(path) }
            )
        },
    private val removeCandidates: suspend (
        sourceCheckoutRoot: Path,
        workTreeDirectory: Path,
        candidates: List<ResolvedWorktreeCandidate>
    ) -> ResolvedWorktreeCleanupSummary =
        { sourceCheckoutRoot, workTreeDirectory, candidates ->
            val inProgress = worktreeOpener.worktreesBeingOpened()
            ResolvedWorktreeCleaner(
                isOpenInProgress = { path -> path in inProgress },
                remover = WatchedWorktreeRemover(
                    openProjectLocator = OpenProjectLocatorImpl(),
                    projectCloser = IntelliJProjectCloser(),
                    removeWorktree = worktreeService::removeWorktree
                )
            ).clean(sourceCheckoutRoot = sourceCheckoutRoot, workTreeDirectory = workTreeDirectory, candidates = candidates)
        }
) {
    private val repositoriesInProgress: MutableSet<PullRequestRepositoryKey> = ConcurrentHashMap.newKeySet()

    private val permits = Semaphore(MAX_PARALLEL_CLEANUPS)

    /** True while any repository is being cleaned. */
    fun isRunning(): Boolean {
        return repositoriesInProgress.isNotEmpty()
    }

    /**
     * Starts the cleanup of the work trees of [repoSlug] whose pull requests are in [resolvedPrNumbers]. Returns the
     * launched job, or null when a guard skipped the cleanup.
     */
    fun clean(workspace: String, repoSlug: String, resolvedPrNumbers: Set<Long>): Job? {
        if (resolvedPrNumbers.isEmpty()) {
            LOG.debug("No resolved pull requests of $workspace/$repoSlug; skipping work tree cleanup")
            return null
        }
        val workTreeDirectory = configurationService.workTreeDirectoryOrNull()
        val organizationUrl = configurationService.organizationUrlOrNull()
        if (workTreeDirectory == null || organizationUrl == null) {
            LOG.info("Cannot clean resolved work trees: work tree directory or organization URL is unavailable")
            return null
        }
        if (isPruneAllInProgress()) {
            LOG.info("Skipping resolved work tree cleanup of $repoSlug: pruning all watched projects is running")
            return null
        }
        if (PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress()) {
            LOG.info("Skipping resolved work tree cleanup of $repoSlug: removing all PR work trees is running")
            return null
        }
        val key = PullRequestRepositoryKey.of(workspace = workspace, repoSlug = repoSlug)
        if (!repositoriesInProgress.add(key)) {
            LOG.debug("Resolved work tree cleanup of $repoSlug is already in progress")
            return null
        }
        return coroutineScope.launch {
            try {
                permits.withPermit {
                    cleanRepository(
                        workspace = workspace,
                        repoSlug = repoSlug,
                        workTreeDirectory = workTreeDirectory,
                        resolvedPrNumbers = resolvedPrNumbers
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Resolved work tree cleanup of $repoSlug failed", exception)
            } finally {
                repositoriesInProgress.remove(key)
            }
        }
    }

    private suspend fun cleanRepository(workspace: String, repoSlug: String, workTreeDirectory: String, resolvedPrNumbers: Set<Long>) {
        val workTreePath = try {
            Path.of(workTreeDirectory)
        } catch (exception: Exception) {
            LOG.info("Skipping resolved work tree cleanup because the work tree directory is invalid", exception)
            return
        }
        val discovered = withContext(ioDispatcher) { listWorktreesForRepo(workTreePath, repoSlug) }
        if (discovered.isEmpty()) {
            LOG.debug("No local work trees of $repoSlug; skipping work tree cleanup")
            return
        }
        val sourceCheckoutPath = resolveSourceCheckout(workspace, repoSlug)
        if (sourceCheckoutPath == null) {
            LOG.info("Skipping resolved work tree cleanup of $repoSlug because the source checkout could not be resolved")
            return
        }
        val openResult = openPullRequestService.listAllOpenPullRequestsForRepository(workspace = workspace, repoSlug = repoSlug)
        val completeOpenPrNumbers = (openResult as? PullRequestListResult.Success)
            ?.pullRequests
            ?.map { summary -> summary.id }
            ?.toSet()
        if (completeOpenPrNumbers == null) {
            LOG.info("The complete open pull request listing of $repoSlug failed; cleaning only the resolved pull requests already loaded")
        }
        val candidates = withContext(ioDispatcher) {
            ResolvedWorktreeCleanupSelection.candidates(
                repoSlug = repoSlug,
                workTreeDirectory = workTreePath,
                discoveredDirectories = discovered,
                registeredPaths = registeredWorktrees(sourceCheckoutPath),
                resolvedPrNumbers = resolvedPrNumbers,
                completeOpenPrNumbers = completeOpenPrNumbers
            )
        }
        if (candidates.isEmpty()) {
            return
        }
        val summary = removeCandidates(sourceCheckoutPath, workTreePath, candidates)
        LOG.debug("Resolved work tree cleanup of $repoSlug completed: $summary")
    }

    companion object {
        private val LOG = logger<ResolvedWorktreeAutoCleanup>()

        /** Bounds the git work when many watched repositories were listed live at once. */
        private const val MAX_PARALLEL_CLEANUPS = 2
    }
}