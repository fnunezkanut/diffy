package com.github.fnunezkanut.bbcloud.review

import androidx.compose.runtime.snapshotFlow
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestPathMatcher
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyPrBranchSyncService
import com.github.fnunezkanut.bbcloud.git.EditableSourceSide
import com.github.fnunezkanut.bbcloud.git.LocalBranchState
import com.github.fnunezkanut.bbcloud.git.LocalBranchStateLoader
import com.github.fnunezkanut.bbcloud.git.PrBranchesSyncedListener
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.github.fnunezkanut.bbcloud.settings.DiffySettings
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestReviewBarRefresh
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shared [LocalBranchState] of a pull request work tree.
 *
 * The orange "not in the pull request yet" bar and the editable PR diff both read this state, so git runs once and
 * the two features never disagree. The state is null when there is no pull request binding, the pull request is known
 * closed, the project is not a plugin-managed work tree, or the remote ref of the source branch does not exist. It
 * never fetches.
 *
 * Refreshes are debounced and run off the EDT. [refreshNow] computes at once and is used by the PR Changes reload.
 */
@Service(Service.Level.PROJECT)
class DiffyLocalBranchStateService(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    private val loader = LocalBranchStateLoader()
    private val mutableState = MutableStateFlow<LocalBranchState?>(null)
    private val started = AtomicBoolean(false)
    private val computeMutex = Mutex()
    private val jobLock = Any()
    private var scheduledJob: Job? = null

    /** The latest state; null when the bars have nothing to show. */
    val state: StateFlow<LocalBranchState?> = mutableState.asStateFlow()

    /** Subscribes to the refresh triggers and schedules the first refresh. Idempotent. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        LOG.debug("Starting the local branch state service")
        subscribeToTriggers()
        scheduleRefresh(reason = "started")
    }

    /** Schedules a debounced refresh; a later call replaces a pending one. */
    fun scheduleRefresh(reason: String) {
        if (project.isDisposed) {
            return
        }
        val job = coroutineScope.launch {
            delay(REFRESH_DEBOUNCE)
            compute(reason = reason)
        }
        synchronized(jobLock) {
            scheduledJob?.cancel()
            scheduledJob = job
        }
    }

    /** Cancels a pending scheduled refresh, computes the state at once, publishes it, and returns it. */
    suspend fun refreshNow(reason: String): LocalBranchState? {
        synchronized(jobLock) {
            scheduledJob?.cancel()
            scheduledJob = null
        }
        return compute(reason = reason)
    }

    /**
     * Decides whether pull request diffs may show the editable work tree file, given [localBranchState]. Editing needs
     * a pull request work tree and the "editable source side" setting.
     */
    fun editableSourceSideFor(localBranchState: LocalBranchState?): EditableSourceSide {
        val allowed = DiffyPullRequestReviewBarRefresh.isPullRequestWorkTree(project.basePath) &&
            DiffySettings.getInstance().editableSourceSideInDiffs
        return EditableSourceSide(allowed = allowed, localBranchState = localBranchState)
    }

    private suspend fun compute(reason: String): LocalBranchState? {
        return computeMutex.withLock {
            val result = withContext(Dispatchers.IO) { load() }
            publish(result = result, reason = reason)
            result
        }
    }

    private suspend fun load(): LocalBranchState? {
        if (project.isDisposed) {
            return null
        }
        val context = DiffyWorktreePullRequestContext.getInstance(project)
        val binding = context.binding
        if (binding == null || context.pullRequestKnownClosed) {
            LOG.debug("Local branch state cleared: no open pull request binding")
            return null
        }
        val isManaged = PullRequestWorktreeProject.isPluginManagedWorktree(
            projectBasePath = project.basePath,
            workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        )
        if (!isManaged) {
            LOG.debug("Local branch state cleared: the project is not a plugin-managed work tree")
            return null
        }
        return try {
            val uncommitted = uncommittedPaths()
            loader.load(project = project, sourceBranch = binding.sourceBranch, uncommittedPaths = uncommitted)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            LOG.warn("Failed to load the local branch state", exception)
            null
        }
    }

    private suspend fun uncommittedPaths(): Set<String> {
        val changeListManager = ChangeListManager.getInstance(project)
        val paths = mutableSetOf<String>()
        for (change in changeListManager.allChanges) {
            paths.add(ChangesUtil.getFilePath(change).path)
        }
        for (filePath in changeListManager.unversionedFilesPaths) {
            paths.add(filePath.path)
        }
        // Ignored files are neither changes nor unversioned files, so they get no bar.
        val basePath = project.basePath
        if (basePath != null) {
            val unsavedPaths = withContext(Dispatchers.EDT) {
                val documentManager = FileDocumentManager.getInstance()
                documentManager.unsavedDocuments.mapNotNull { document -> documentManager.getFile(document)?.path }
            }
            val normalizedBase = PullRequestPathMatcher.normalize(basePath)
            for (unsaved in unsavedPaths) {
                if (PullRequestPathMatcher.normalize(unsaved).startsWith("$normalizedBase/")) {
                    paths.add(unsaved)
                }
            }
        }
        return paths
            .map { path -> PullRequestPathMatcher.relativeProjectPath(projectBasePath = basePath, absoluteOrRelativePath = path) }
            .map { path -> PullRequestPathMatcher.normalize(path) }
            .toSet()
    }

    private fun publish(result: LocalBranchState?, reason: String) {
        val previous = mutableState.value
        mutableState.value = result
        if (previous != result) {
            LOG.debug(
                "Local branch state changed ($reason): present=${result != null}, " +
                    "ahead=${result?.commitsAheadOfRemote}, unpushedFiles=${result?.unpushedPaths?.size}, " +
                    "uncommittedFiles=${result?.uncommittedPaths?.size}"
            )
        }
    }

    private fun subscribeToTriggers() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { scheduleRefresh(reason = "repository changed") }
        )
        connection.subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    scheduleRefresh(reason = "change lists updated")
                }
            }
        )
        connection.subscribe(
            DiffyPrBranchSyncService.PR_BRANCHES_SYNCED,
            PrBranchesSyncedListener { scheduleRefresh(reason = "branches synced") }
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun unsavedDocumentDropped(document: Document) {
                    scheduleRefreshForDocument(document = document, reason = "unsaved document dropped")
                }
            }
        )
        // Only reschedules the debounced refresh; it must never compute inline because it fires on every keystroke.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    scheduleRefreshForDocument(document = event.document, reason = "document changed")
                }
            },
            this
        )
        observeBinding()
    }

    private fun scheduleRefreshForDocument(document: Document, reason: String) {
        val basePath = project.basePath ?: return
        val path = FileDocumentManager.getInstance().getFile(document)?.path ?: return
        val prefix = "${PullRequestPathMatcher.normalize(basePath)}/"
        if (PullRequestPathMatcher.normalize(path).startsWith(prefix)) {
            scheduleRefresh(reason = reason)
        }
    }

    /** Refreshes when the pull request binding or open/closed status of the work tree context changes. */
    private fun observeBinding() {
        val context = DiffyWorktreePullRequestContext.getInstance(project)
        coroutineScope.launch {
            snapshotFlow { context.pullRequestStatusRevision.value to context.commentsRevision.value }.collect {
                scheduleRefresh(reason = "pull request binding changed")
            }
        }
    }

    override fun dispose() {
        synchronized(jobLock) {
            scheduledJob?.cancel()
            scheduledJob = null
        }
    }

    companion object {
        private val LOG = logger<DiffyLocalBranchStateService>()

        /** The pause a refresh waits for, so that a burst of triggers computes the state only once. */
        private val REFRESH_DEBOUNCE = 300.milliseconds

        fun getInstance(project: Project): DiffyLocalBranchStateService {
            return project.service()
        }
    }
}