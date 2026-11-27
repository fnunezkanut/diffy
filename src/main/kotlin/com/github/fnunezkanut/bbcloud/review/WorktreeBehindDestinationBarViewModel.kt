package com.github.fnunezkanut.bbcloud.review

import androidx.compose.runtime.snapshotFlow
import com.github.fnunezkanut.bbcloud.bitbucket.ItemCount
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.git.ProjectGitRepositoryLookup
import com.github.fnunezkanut.bbcloud.ui.air.AirPullRequestRebaser
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestReviewBarRefresh
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the state of the yellow "this pull request is N commits behind the destination branch" bar of a pull request
 * work tree, as a project-level service that every bar (editors and diffs) reads, so they all agree.
 *
 * The count comes from the Bitbucket overview that the `.pr` editor session loads, which calls [show] on every
 * result and [clear] when the session is disposed. The bar is visible while the pull request is proven open and
 * [state] is not null. Editors add and remove the bar through `EditorNotifications`; diffs follow [visible].
 *
 * When the remote-tracking source or destination branch moves in git, for example after a force push, the page is
 * reloaded after a short debounce so the count is read again. State changes happen on the EDT.
 */
@Service(Service.Level.PROJECT)
class WorktreeBehindDestinationBarViewModel(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    /** The collaborators of the view model, replaceable in unit tests. */
    internal class Dependencies(
        val bannerState: () -> WorktreeTodoBannerUiState,
        val refreshNotifications: () -> Unit,
        val rebaseWithAi: (BehindDestinationBarUiState) -> Unit,
        val isRedBarVisible: () -> Boolean,
        val readTips: (repository: GitRepository, sourceBranch: String, destinationBranch: String) -> ObservedBranchTips,
        val isRepositoryRebasing: (repository: GitRepository) -> Boolean,
        val isRebasing: (repository: GitRepository) -> Boolean,
        val currentRepository: () -> GitRepository?,
        val requestReload: () -> Unit,
        val reloadDebounce: Duration = REBASE_RELOAD_DEBOUNCE,
        val retryDelays: List<Duration> = RELOAD_RETRY_DELAYS
    )

    private var dependencies: Dependencies = productionDependencies(project)
    private val mutableState = MutableStateFlow<BehindDestinationBarUiState?>(null)
    private val mutableVisible = MutableStateFlow(false)
    private val mutableRebaseInProgress = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private val reloadLock = Any()
    private var reloadJob: Job? = null
    private var lastVisible = false

    @Volatile
    private var lastObservedRemoteTips: Pair<String?, String?>? = null

    /** Set when the remote tips moved while a git operation was running, so that the reload is not lost. */
    @Volatile
    private var reloadAfterRebase = false

    /** What the bar shows; null when nothing is behind. */
    val state: StateFlow<BehindDestinationBarUiState?> = mutableState.asStateFlow()

    /** Whether the bar is shown: the pull request is proven open and [state] is not null. */
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

    /** Whether a git rebase is running in the work tree, so the bar shows a text instead of the rebase link. */
    val isRebaseInProgress: StateFlow<Boolean> = mutableRebaseInProgress.asStateFlow()

    /** Whether the bar is shown right now. */
    fun isVisible(): Boolean {
        val current = mutableState.value ?: return false
        val banner = dependencies.bannerState()
        return banner.isWorktree && banner.pullRequestId == current.pullRequestId
    }

    /** Subscribes to the triggers. Idempotent, and a no-op outside a PR work tree. */
    fun ensureStarted() {
        if (started.get()) {
            return
        }
        if (!DiffyPullRequestReviewBarRefresh.isPullRequestWorkTree(project.basePath)) {
            LOG.debug("Not starting the yellow bar: the project is not a pull request work tree")
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }
        LOG.debug("Starting the yellow bar view model")
        seedRebaseState()
        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository -> onRepositoryChanged(repository) }
        )
        observeBanner()
    }

    /** Reads the repository state once, so a rebase that was already running when the bar started is shown. */
    internal fun seedRebaseState() {
        val repository = dependencies.currentRepository()
        mutableRebaseInProgress.value = repository != null && dependencies.isRebasing(repository)
    }

    /** Calls [onBannerChanged] whenever the banner state changes, starting with its current value. */
    private fun observeBanner() {
        coroutineScope.launch(Dispatchers.EDT) {
            var previous = WorktreeTodoBannerUiState()
            snapshotFlow { dependencies.bannerState() }.collect { current ->
                onBannerChanged(previous = previous, current = current)
                previous = current
            }
        }
    }

    /** Reacts to a change of the banner: new branches reset the observed tips, a new visibility adds or removes the bar. */
    internal fun onBannerChanged(previous: WorktreeTodoBannerUiState, current: WorktreeTodoBannerUiState) {
        val branchesChanged = previous.sourceBranch != current.sourceBranch || previous.destinationBranch != current.destinationBranch
        if (branchesChanged) {
            lastObservedRemoteTips = null
            reloadAfterRebase = false
            cancelPendingReload()
        }
        refreshVisibility()
    }

    /**
     * Shows the bar for the overview that was just loaded, or hides it when the pull request is not open, the count is
     * unknown, or it is 0. Called on the EDT.
     */
    fun show(pullRequestId: Long, state: PullRequestState, commitsBehind: ItemCount?, sourceBranch: String, destinationBranch: String) {
        val newState = behindDestinationBarStateOf(
            pullRequestId = pullRequestId,
            state = state,
            commitsBehind = commitsBehind,
            sourceBranch = sourceBranch,
            destinationBranch = destinationBranch
        )
        setState(newState)
    }

    /** Hides the bar when it belongs to [pullRequestId]. Called on the EDT. */
    fun clear(pullRequestId: Long) {
        if (mutableState.value?.pullRequestId == pullRequestId) {
            setState(null)
        }
    }

    /** Puts the rebase prompt into a new Air chat (the link of the bar). Called on the EDT. */
    fun rebaseWithAi() {
        val current = mutableState.value
        if (current == null) {
            LOG.info("Rebase onto latest with AI ignored: no behind-destination state")
            return
        }
        if (mutableRebaseInProgress.value) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${current.pullRequestId}: a rebase is in progress")
            return
        }
        if (dependencies.isRedBarVisible()) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${current.pullRequestId}: the local branches are behind Bitbucket")
            return
        }
        LOG.info("Rebase onto latest with AI clicked for pull request #${current.pullRequestId}")
        dependencies.rebaseWithAi(current)
    }

    /**
     * Follows the git state of the work tree. It tracks whether a rebase is running, and reloads the page when the
     * remote-tracking source or destination branch moved. The first observation only records the tips. A move that is
     * seen while a git operation is running is remembered, and the reload runs on the first change after it ended.
     * May be called on any thread.
     */
    internal fun onRepositoryChanged(repository: GitRepository) {
        val current = dependencies.currentRepository()
        if (current != null && current != repository) {
            return
        }
        val isRebasing = dependencies.isRebasing(repository)
        val wasRebasing = mutableRebaseInProgress.getAndUpdate { isRebasing }
        if (wasRebasing != isRebasing) {
            LOG.debug("A rebase is now ${if (isRebasing) "in progress" else "finished"} in the work tree")
        }
        val banner = dependencies.bannerState()
        val source = banner.sourceBranch
        val destination = banner.destinationBranch
        if (source.isNullOrBlank() || destination.isNullOrBlank()) {
            return
        }
        val tips = dependencies.readTips(repository, source, destination)
        val remoteTips = Pair(tips.sourceRemote, tips.destinationRemote)
        val previous = lastObservedRemoteTips
        lastObservedRemoteTips = remoteTips
        val tipsMoved = previous != null && previous != remoteTips
        if (dependencies.isRepositoryRebasing(repository)) {
            if (tipsMoved) {
                reloadAfterRebase = true
            }
            LOG.debug("Not reloading pull request #${banner.pullRequestId} yet: a git operation is in progress (tipsMoved=$tipsMoved)")
            return
        }
        if (!tipsMoved && !reloadAfterRebase) {
            return
        }
        reloadAfterRebase = false
        LOG.debug("The remote tips of pull request #${banner.pullRequestId} changed; scheduling a reload")
        scheduleReload(pullRequestId = banner.pullRequestId)
    }

    private fun scheduleReload(pullRequestId: Long) {
        synchronized(reloadLock) {
            val pending = reloadJob?.isActive == true
            if (mutableState.value == null && !pending) {
                return
            }
            reloadJob?.cancel()
            reloadJob = coroutineScope.launch {
                delay(dependencies.reloadDebounce)
                LOG.debug("Reloading pull request #$pullRequestId after its branch tips changed")
                dependencies.requestReload()
                reloadWhileStillBehind(pullRequestId = pullRequestId)
            }
        }
    }

    /**
     * Bitbucket can take a moment to count the new commits after a push, so the page is read again after each of the
     * [Dependencies.retryDelays] while the bar is still shown. The job is cancelled as soon as the bar hides.
     */
    private suspend fun reloadWhileStillBehind(pullRequestId: Long) {
        for ((index, wait) in dependencies.retryDelays.withIndex()) {
            delay(wait)
            if (mutableState.value == null) {
                return
            }
            LOG.debug("Pull request #$pullRequestId is still behind; reloading again (retry ${index + 1})")
            dependencies.requestReload()
        }
    }

    private fun cancelPendingReload() {
        synchronized(reloadLock) {
            reloadJob?.cancel()
            reloadJob = null
        }
    }

    private fun setState(newState: BehindDestinationBarUiState?) {
        if (mutableState.value == newState) {
            return
        }
        mutableState.value = newState
        if (newState == null) {
            cancelPendingReload()
        }
        refreshVisibility()
    }

    /** Tells the editors to add or remove the bar when its visibility changed since the last call. */
    private fun refreshVisibility() {
        val now = isVisible()
        if (now == lastVisible) {
            return
        }
        lastVisible = now
        mutableVisible.value = now
        LOG.debug("The yellow bar is now ${if (now) "visible" else "hidden"}")
        dependencies.refreshNotifications()
    }

    override fun dispose() {
        cancelPendingReload()
    }

    companion object {
        private val LOG = logger<WorktreeBehindDestinationBarViewModel>()

        /** How long the branch tips must stay unchanged before the page is reloaded. */
        internal val REBASE_RELOAD_DEBOUNCE = 2.seconds

        /** The waits before the page is read again while the bar is still shown after a reload, each after the previous read. */
        internal val RELOAD_RETRY_DELAYS = listOf(5.seconds, 15.seconds)

        fun getInstance(project: Project): WorktreeBehindDestinationBarViewModel {
            return project.service()
        }

        /** Builds an instance with stubbed [dependencies], for unit tests only. */
        internal fun forTesting(project: Project, coroutineScope: CoroutineScope, dependencies: Dependencies): WorktreeBehindDestinationBarViewModel {
            return WorktreeBehindDestinationBarViewModel(project, coroutineScope).apply {
                this.dependencies = dependencies
            }
        }

        private fun productionDependencies(project: Project): Dependencies {
            return Dependencies(
                bannerState = { WorktreeTodoBannerViewModel.getInstance(project).state.value },
                refreshNotifications = { refreshEditorNotifications(project) },
                rebaseWithAi = { state -> AirPullRequestRebaser.rebase(project = project, state = state) },
                isRedBarVisible = { WorktreeLocalBehindRemoteBarViewModel.getInstance(project).isVisible() },
                readTips = { repository, source, destination ->
                    ObservedBranchTips.read(repository = repository, sourceBranch = source, destinationBranch = destination)
                },
                isRepositoryRebasing = { repository -> repository.state != Repository.State.NORMAL },
                isRebasing = { repository -> repository.state == Repository.State.REBASING },
                currentRepository = { ProjectGitRepositoryLookup.findRepository(project) },
                requestReload = {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            WorktreePullRequestPageReloader.getInstance(project).requestReload()
                        }
                    }
                }
            )
        }

        /** Re-collects the editor notifications, because the platform no longer refreshes a single provider. */
        private fun refreshEditorNotifications(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }
}