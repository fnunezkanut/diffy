package com.github.fnunezkanut.bbcloud.review

import androidx.compose.runtime.snapshotFlow
import com.github.fnunezkanut.bbcloud.bitbucket.ItemCount
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.ui.air.AirPullRequestRebaser
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestReviewBarRefresh
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyWorktreeBehindDestinationEditorNotificationProvider
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotificationProvider
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
        val requestReload: () -> Unit,
        val reloadDebounce: Duration = REBASE_RELOAD_DEBOUNCE
    )

    private var dependencies: Dependencies = productionDependencies(project)
    private val mutableState = MutableStateFlow<BehindDestinationBarUiState?>(null)
    private val mutableVisible = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private val reloadLock = Any()
    private var reloadJob: Job? = null
    private var lastVisible = false

    @Volatile
    private var lastObservedRemoteTips: Pair<String?, String?>? = null

    /** What the bar shows; null when nothing is behind. */
    val state: StateFlow<BehindDestinationBarUiState?> = mutableState.asStateFlow()

    /** Whether the bar is shown: the pull request is proven open and [state] is not null. */
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

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
        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository -> onRepositoryChanged(repository) }
        )
        observeBanner()
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
        if (dependencies.isRedBarVisible()) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${current.pullRequestId}: the local branches are behind Bitbucket")
            return
        }
        LOG.info("Rebase onto latest with AI clicked for pull request #${current.pullRequestId}")
        dependencies.rebaseWithAi(current)
    }

    /**
     * Reloads the page when the remote-tracking source or destination branch moved. The first observation only records
     * the tips. May be called on any thread.
     */
    internal fun onRepositoryChanged(repository: GitRepository) {
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
        if (previous == null || previous == remoteTips) {
            return
        }
        if (dependencies.isRepositoryRebasing(repository)) {
            LOG.debug("Not reloading pull request #${banner.pullRequestId} yet: a rebase is in progress")
            return
        }
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
            }
        }
    }

    private fun setState(newState: BehindDestinationBarUiState?) {
        if (mutableState.value == newState) {
            return
        }
        mutableState.value = newState
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
        synchronized(reloadLock) {
            reloadJob?.cancel()
            reloadJob = null
        }
    }

    companion object {
        private val LOG = logger<WorktreeBehindDestinationBarViewModel>()

        /** How long the branch tips must stay unchanged before the page is reloaded. */
        internal val REBASE_RELOAD_DEBOUNCE = 2.seconds

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
                requestReload = {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            WorktreePullRequestPageReloader.getInstance(project).requestReload()
                        }
                    }
                }
            )
        }

        /** Re-collects the notifications of the yellow bar provider only, so the other bars do not flicker. */
        private fun refreshEditorNotifications(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                val notifications = EditorNotifications.getInstance(project)
                val provider = EditorNotificationProvider.EP_NAME.findExtension(
                    DiffyWorktreeBehindDestinationEditorNotificationProvider::class.java,
                    project
                )
                if (provider == null) {
                    LOG.debug("The yellow bar provider is not registered; refreshing all editor notifications")
                    notifications.updateAllNotifications()
                } else {
                    notifications.updateNotifications(provider)
                }
            }
        }
    }
}