package com.github.fnunezkanut.bbcloud.review

import androidx.compose.runtime.snapshotFlow
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.git.DiffyPrBranchSyncService
import com.github.fnunezkanut.bbcloud.git.PrBranchRole
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncResult
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSkipReason
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncStepResult
import com.github.fnunezkanut.bbcloud.git.PrBranchesSyncedListener
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestReviewBarRefresh
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyWorktreeLocalBehindRemoteEditorNotificationProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the state of the red "local branches are behind Bitbucket" bar of a pull request work tree, as a project-level
 * service that every bar (editors and diffs) reads, so they all agree.
 *
 * The service evaluates the local source and destination branches when a branch snapshot is published, when the tips
 * of the observed branches change in git, and when the `.pr` editor asks for it. It works without the `.pr` editor.
 * The bar is visible while the pull request is proven open and there is something to show. Editors add and remove the
 * bar through `EditorNotifications`; diffs follow [visible].
 *
 * A snapshot is applied only when the banner knows both branch names and they match the snapshot. All state changes
 * happen on the EDT.
 */
@Service(Service.Level.PROJECT)
class WorktreeLocalBehindRemoteBarViewModel(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    /** The collaborators of the view model, replaceable in unit tests. */
    internal class Dependencies(
        val lastSnapshot: () -> PrBranchSyncSnapshot?,
        val evaluate: suspend (sourceBranch: String, destinationBranch: String, fetch: Boolean) -> PrBranchSyncSnapshot?,
        val updateOnUserRequest: suspend (sourceBranch: String, destinationBranch: String) -> PrBranchSyncResult?,
        val bannerState: () -> WorktreeTodoBannerUiState,
        val requestReload: () -> Unit,
        val notifyFailure: (title: String, message: String?) -> Unit,
        val onVisibilityChanged: () -> Unit,
        val runOnEdt: (action: () -> Unit) -> Unit,
        val readTips: (repository: GitRepository, sourceBranch: String, destinationBranch: String) -> ObservedBranchTips
    )

    private var dependencies: Dependencies = productionDependencies(project)
    private val mutableState = MutableStateFlow<LocalBehindRemoteBarUiState?>(null)
    private val mutableVisible = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private var evaluationJob: Job? = null
    private var updateInProgress = false
    private var lastVisible = false

    @Volatile
    private var lastObservedTips: ObservedBranchTips? = null

    /** What the bar shows; null when nothing is behind. */
    val state: StateFlow<LocalBehindRemoteBarUiState?> = mutableState.asStateFlow()

    /** Whether the bar is shown: the pull request is proven open and [state] is not null. */
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

    /** Whether the bar is shown right now. */
    fun isVisible(): Boolean {
        return dependencies.bannerState().isWorktree && mutableState.value != null
    }

    /** Subscribes to the triggers and applies the last known snapshot. Idempotent, and a no-op outside a PR work tree. */
    fun ensureStarted() {
        if (started.get()) {
            return
        }
        if (!DiffyPullRequestReviewBarRefresh.isPullRequestWorkTree(project.basePath)) {
            LOG.debug("Not starting the red bar: the project is not a pull request work tree")
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }
        LOG.debug("Starting the red bar view model")
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            DiffyPrBranchSyncService.PR_BRANCHES_SYNCED,
            PrBranchesSyncedListener { snapshot -> dependencies.runOnEdt { onSnapshot(snapshot) } }
        )
        connection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository -> onRepositoryChanged(repository) }
        )
        observeBanner()
        dependencies.runOnEdt {
            dependencies.lastSnapshot()?.let { snapshot -> onSnapshot(snapshot) }
        }
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

    /** Applies [snapshot] to the bar. Called on the EDT. */
    internal fun onSnapshot(snapshot: PrBranchSyncSnapshot) {
        val banner = dependencies.bannerState()
        if (!snapshotMatchesBranches(snapshot = snapshot, sourceBranch = banner.sourceBranch, destinationBranch = banner.destinationBranch)) {
            LOG.debug("Ignoring a branch snapshot that does not match the branches of pull request #${banner.pullRequestId}")
            return
        }
        if (updateInProgress) {
            LOG.debug("Ignoring a branch snapshot while a branch update is in progress")
            return
        }
        setState(LocalBranchesBehindWarning.from(snapshot))
    }

    /** Re-checks the bar when the tips of the observed branches changed in git. May be called on any thread. */
    internal fun onRepositoryChanged(repository: GitRepository) {
        val banner = dependencies.bannerState()
        val source = banner.sourceBranch
        val destination = banner.destinationBranch
        if (source.isNullOrBlank() || destination.isNullOrBlank()) {
            return
        }
        val tips = dependencies.readTips(repository, source, destination)
        if (tips == lastObservedTips) {
            return
        }
        lastObservedTips = tips
        // The event is often a fetch itself, so the evaluation does not fetch again.
        requestEvaluation(fetch = false)
    }

    /** Reacts to a change of the banner: new branches re-apply the last snapshot, a new visibility adds or removes the bar. */
    internal fun onBannerChanged(previous: WorktreeTodoBannerUiState, current: WorktreeTodoBannerUiState) {
        val branchesChanged = previous.sourceBranch != current.sourceBranch || previous.destinationBranch != current.destinationBranch
        if (branchesChanged) {
            lastObservedTips = null
            applyLastSnapshotOrClear(banner = current)
            if (!current.sourceBranch.isNullOrBlank() && !current.destinationBranch.isNullOrBlank()) {
                requestEvaluation(fetch = false)
            }
        }
        refreshVisibility()
    }

    private fun applyLastSnapshotOrClear(banner: WorktreeTodoBannerUiState) {
        val snapshot = dependencies.lastSnapshot()
        val matches = snapshot != null &&
            snapshotMatchesBranches(snapshot = snapshot, sourceBranch = banner.sourceBranch, destinationBranch = banner.destinationBranch)
        if (snapshot != null && matches) {
            onSnapshot(snapshot)
        } else if (!updateInProgress) {
            setState(null)
        }
    }

    /**
     * Evaluates the local branches again, fetching first when [fetch] is true. The result reaches the bar through the
     * `PR_BRANCHES_SYNCED` topic when it changed, and directly so a first, unchanged result is shown too. The last
     * requested evaluation wins. May be called on any thread.
     */
    fun requestEvaluation(fetch: Boolean) {
        dependencies.runOnEdt { startEvaluation(fetch = fetch) }
    }

    private fun startEvaluation(fetch: Boolean) {
        val banner = dependencies.bannerState()
        if (!banner.isWorktree) {
            evaluationJob?.cancel()
            evaluationJob = null
            if (!updateInProgress) {
                setState(null)
            }
            return
        }
        val source = banner.sourceBranch
        val destination = banner.destinationBranch
        if (source.isNullOrBlank() || destination.isNullOrBlank()) {
            return
        }
        if (updateInProgress) {
            LOG.debug("Skipping the local branch check of pull request #${banner.pullRequestId}; a branch update is in progress")
            return
        }
        evaluationJob?.cancel()
        evaluationJob = coroutineScope.launch {
            val snapshot = try {
                dependencies.evaluate(source, destination, fetch)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not evaluate the local branches of pull request #${banner.pullRequestId}", exception)
                null
            }
            if (snapshot != null) {
                dependencies.runOnEdt { onSnapshot(snapshot) }
            }
        }
    }

    /**
     * Updates every local branch that is behind Bitbucket (the link of the bar), then reloads the pull request page.
     * Branches that cannot be updated are reported in one failure notification each. Called on the EDT.
     */
    fun requestUpdate() {
        val banner = dependencies.bannerState()
        val source = banner.sourceBranch
        val destination = banner.destinationBranch
        val current = mutableState.value
        if (updateInProgress || !banner.refreshEnabled || source.isNullOrBlank() || destination.isNullOrBlank() || current == null) {
            LOG.info("Ignored the red bar link of pull request #${banner.pullRequestId}; an update or reload is in progress, or the branches are unknown")
            return
        }
        LOG.info("User clicked \u0027${current.linkText}\u0027 in the red bar of pull request #${banner.pullRequestId}")
        updateInProgress = true
        setState(current.copy(updateInProgress = true))
        coroutineScope.launch {
            val result = try {
                dependencies.updateOnUserRequest(source, destination)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Failed to update the local branches of pull request #${banner.pullRequestId} from remote", exception)
                null
            }
            dependencies.runOnEdt {
                onUpdateFinished(pullRequestId = banner.pullRequestId, sourceBranch = source, result = result)
            }
        }
    }

    private fun onUpdateFinished(pullRequestId: Long, sourceBranch: String, result: PrBranchSyncResult?) {
        finishUpdate()
        if (result == null) {
            dependencies.notifyFailure(DiffyBundle.message("main.branch.localBehindRemote.failedUpdate", sourceBranch), null)
            return
        }
        var anyProblem = false
        for (role in PrBranchRole.entries) {
            val stepResult = result.outcome.resultFor(role)
            val branch = result.after.branchFor(role)
            val message = when (stepResult) {
                is PrBranchSyncStepResult.Skipped -> skippedMessage(branch = branch, stepResult = stepResult)
                is PrBranchSyncStepResult.Failed -> stepResult.output
                else -> null
            }
            if (message != null) {
                anyProblem = true
                LOG.info("Local $role branch of pull request #$pullRequestId was not updated")
                dependencies.notifyFailure(DiffyBundle.message("main.branch.localBehindRemote.failedUpdate", branch), message)
            }
        }
        onSnapshot(result.after)
        if (anyProblem && !result.outcome.anyRefMoved) {
            return
        }
        LOG.info("Updated the local branches of pull request #$pullRequestId from remote; reloading the page")
        dependencies.requestReload()
    }

    private fun finishUpdate() {
        updateInProgress = false
        mutableState.value?.let { current -> setState(current.copy(updateInProgress = false)) }
    }

    private fun skippedMessage(branch: String, stepResult: PrBranchSyncStepResult.Skipped): String? {
        return when (stepResult.reason) {
            PrBranchSyncSkipReason.UncommittedChanges -> {
                DiffyBundle.message("main.branch.localBehindRemote.dirtyWorktree", stepResult.path?.toString().orEmpty(), branch)
            }
            PrBranchSyncSkipReason.LocalCommitsNotOnRemote -> {
                DiffyBundle.message("main.branch.localBehindRemote.diverged", branch)
            }
            PrBranchSyncSkipReason.CheckedOutInOtherWorktree, PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown -> {
                null
            }
        }
    }

    private fun setState(newState: LocalBehindRemoteBarUiState?) {
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
        LOG.debug("The red bar is now ${if (now) "visible" else "hidden"}")
        dependencies.onVisibilityChanged()
    }

    override fun dispose() {
        evaluationJob?.cancel()
        evaluationJob = null
    }

    companion object {
        private val LOG = logger<WorktreeLocalBehindRemoteBarViewModel>()
        private const val NOTIFICATION_GROUP_ID = "Diffy"

        fun getInstance(project: Project): WorktreeLocalBehindRemoteBarViewModel {
            return project.service()
        }

        /** Builds an instance with stubbed [dependencies], for unit tests only. */
        internal fun forTesting(project: Project, coroutineScope: CoroutineScope, dependencies: Dependencies): WorktreeLocalBehindRemoteBarViewModel {
            return WorktreeLocalBehindRemoteBarViewModel(project, coroutineScope).apply {
                this.dependencies = dependencies
            }
        }

        private fun productionDependencies(project: Project): Dependencies {
            return Dependencies(
                lastSnapshot = { DiffyPrBranchSyncService.getInstance(project).lastSnapshot },
                evaluate = { source, destination, fetch ->
                    DiffyPrBranchSyncService.getInstance(project).evaluate(sourceBranch = source, destinationBranch = destination, fetch = fetch)
                },
                updateOnUserRequest = { source, destination ->
                    DiffyPrBranchSyncService.getInstance(project).updateOnUserRequest(sourceBranch = source, destinationBranch = destination)
                },
                bannerState = { WorktreeTodoBannerViewModel.getInstance(project).state.value },
                requestReload = { WorktreePullRequestPageReloader.getInstance(project).requestReload() },
                notifyFailure = { title, message -> showFailureNotification(project = project, title = title, message = message) },
                onVisibilityChanged = { refreshEditorNotifications(project) },
                runOnEdt = { action ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            action()
                        }
                    }
                },
                readTips = { repository, source, destination ->
                    ObservedBranchTips.read(repository = repository, sourceBranch = source, destinationBranch = destination)
                }
            )
        }

        private fun showFailureNotification(project: Project, title: String, message: String?) {
            val content = message ?: DiffyBundle.message("main.branch.action.failed.generic")
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project)
        }

        /** Re-collects the notifications of the red bar provider only, so the other bars do not flicker. Runs on the EDT. */
        private fun refreshEditorNotifications(project: Project) {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                val notifications = EditorNotifications.getInstance(project)
                val provider = EditorNotificationProvider.EP_NAME.findExtension(
                    DiffyWorktreeLocalBehindRemoteEditorNotificationProvider::class.java,
                    project
                )
                if (provider == null) {
                    LOG.debug("The red bar provider is not registered; refreshing all editor notifications")
                    notifications.updateAllNotifications()
                } else {
                    notifications.updateNotifications(provider)
                }
            }
        }
    }
}