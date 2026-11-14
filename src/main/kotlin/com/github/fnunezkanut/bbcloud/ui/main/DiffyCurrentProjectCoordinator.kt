@file:Suppress("ktlint:standard:function-naming")

package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryUrls
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyProjectRepositoryLinkService
import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.BranchCheckoutResult
import com.github.fnunezkanut.bbcloud.git.CommitMessageParser
import com.github.fnunezkanut.bbcloud.git.DiffyBranchCheckoutService
import com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService
import com.github.fnunezkanut.bbcloud.git.LastCommitMessageReader
import com.github.fnunezkanut.bbcloud.git.ParsedCommitMessage
import com.github.fnunezkanut.bbcloud.project.ProjectRepositoryResolver
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeResolution
import com.github.fnunezkanut.bbcloud.project.ResolvedProjectRepository
import com.github.fnunezkanut.bbcloud.ui.otherprojects.CurrentProjectListing
import com.github.fnunezkanut.bbcloud.ui.otherprojects.CurrentProjectLookup
import com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestEditorOpener
import com.github.fnunezkanut.bbcloud.ui.pullrequest.create.NewPullRequestContext
import com.github.fnunezkanut.bbcloud.ui.worktree.PullRequestWorktreeOpener
import com.github.fnunezkanut.bbcloud.ui.worktree.WorktreeBranchCollisionDialog
import com.github.fnunezkanut.bbcloud.ui.worktree.WorktreeBranchCollisionResolver
import com.github.fnunezkanut.bbcloud.ui.worktree.WorktreeOpenOutcome
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * The non-UI side of the IDE project in the Pull Requests: it resolves and verifies the project's Bitbucket repository,
 * lists its pull requests, opens the new pull request form, and runs the flow after a pull request was created
 * (branch switch, reload, work tree). It owns no Compose UI; the Pull Requests panel shows the rows.
 *
 * [onCurrentProjectReloadRequested] asks the Pull Requests to reload the current project source with a reason for the log,
 * after a branch switch or a created pull request.
 */
class DiffyCurrentProjectCoordinator(
    private val configurationService: DiffyConfigurationService,
    private val projectRepositoryLinkService: DiffyProjectRepositoryLinkService,
    private val openPullRequestService: DiffyOpenPullRequestService,
    private val onCurrentProjectReloadRequested: (reason: String) -> Unit
) {
    private var boundProject: Project? = null
    private var boundCoroutineScope: CoroutineScope? = null
    private var repositoryWebUrl: String? = null
    private var repositoryAvatarUrl: String? = null
    private var currentRepository: ResolvedProjectRepository? = null

    /** The last Found verification of the project's repository; reused by automatic loads for a few minutes. Written on the EDT. */
    @Volatile
    private var lastVerified: VerifiedRepository? = null
    private val worktreeService = DiffyWorktreeService.getInstance()
    private val branchCheckoutService = DiffyBranchCheckoutService()

    /** Shared resolution of a work-tree branch collision: switch this window to main, or warn, or fall back to the modal. */
    private val collisionResolver = WorktreeBranchCollisionResolver(
        branchCheckoutService = branchCheckoutService,
        openPullRequestService = openPullRequestService
    )

    /** The shared open flow. It also guards against duplicate opens of the same worktree path. */
    private val worktreeOpener = PullRequestWorktreeOpener.getInstance()

    /** Binds the coordinator to the IDE project and its coroutine scope. Call before the first lookup. */
    fun bind(project: Project, coroutineScope: CoroutineScope) {
        LOG.debug("Binding the Pull Requests current project to project ${project.name}")
        boundProject = project
        boundCoroutineScope = coroutineScope
    }

    /**
     * Resolves and verifies the Bitbucket repository of the IDE project. An [PullRequestCacheRefresh.IfStale] lookup
     * reuses a verification younger than a few minutes. [PullRequestCacheRefresh.Force] verifies again, because the
     * remote or the Bitbucket repository may have changed, and drops the cached listings of a repository that no longer
     * verifies, so stale rows are not served for it. The fields the UI reads are written on the EDT.
     */
    suspend fun lookupCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectLookup {
        val project = boundProject
        val organizationUrl = configurationService.organizationUrlOrNull()
        if (project == null || organizationUrl == null) {
            LOG.info("Cannot look up the current project: the coordinator is not bound or no organization URL is configured")
            return CurrentProjectLookup.Unavailable(status = ProjectRepositoryStatus.CheckFailed(repositoryWebUrl = null, cause = null))
        }
        val resolvedRepository = resolveCurrentRepository(project = project)
        val reusable = if (refresh == PullRequestCacheRefresh.IfStale) {
            lastVerified?.takeIf { verified ->
                canReuseVerification(
                    verified = verified,
                    projectBasePath = project.basePath,
                    organizationUrl = organizationUrl,
                    elapsed = verified.verifiedAt.elapsedNow()
                )
            }
        } else {
            null
        }
        val status = reusable?.status ?: projectRepositoryLinkService.resolveAndVerify(
            projectBasePath = project.basePath,
            organizationUrl = organizationUrl
        )
        if (refresh == PullRequestCacheRefresh.Force && status !is ProjectRepositoryStatus.Found && resolvedRepository != null) {
            DiffyPullRequestCache.getInstance().dropListingsForRepo(
                workspace = resolvedRepository.workspace,
                repoSlug = resolvedRepository.repoSlug
            )
        }
        return withContext(Dispatchers.EDT) {
            currentRepository = resolvedRepository
            if (reusable == null) {
                rememberVerification(status = status, projectBasePath = project.basePath, organizationUrl = organizationUrl)
            }
            toLookup(status = status, repository = resolvedRepository)
        }
    }

    /** Stores the repository details the UI reads and maps [status] to the lookup outcome. EDT only. */
    private fun toLookup(status: ProjectRepositoryStatus, repository: ResolvedProjectRepository?): CurrentProjectLookup {
        LOG.debug("Project repository status resolved: $status")
        repositoryWebUrl = null
        repositoryAvatarUrl = null
        if (status is ProjectRepositoryStatus.CheckFailed) {
            val detail = status.repositoryWebUrl?.let { url -> " at $url" }.orEmpty()
            if (status.cause != null) {
                LOG.warn("Could not verify Bitbucket repository$detail", status.cause)
            } else {
                LOG.warn("Could not verify Bitbucket repository$detail")
            }
        }
        if (status !is ProjectRepositoryStatus.Found) {
            return CurrentProjectLookup.Unavailable(status = status)
        }
        if (repository == null) {
            LOG.info("The project repository verified but its workspace and slug could not be resolved")
            return CurrentProjectLookup.Unavailable(
                status = ProjectRepositoryStatus.CheckFailed(repositoryWebUrl = status.repositoryWebUrl, cause = null)
            )
        }
        repositoryWebUrl = status.repositoryWebUrl
        repositoryAvatarUrl = status.avatarUrl
        configurationService.cacheRepoAvatarUrl(repoSlug = repository.repoSlug, avatarUrl = status.avatarUrl)
        return CurrentProjectLookup.Found(
            workspace = repository.workspace,
            repoSlug = repository.repoSlug,
            repositoryWebUrl = status.repositoryWebUrl,
            avatarUrl = status.avatarUrl
        )
    }

    /** Remembers a Found verification for reuse, and forgets any earlier one when the repository no longer verifies. */
    private fun rememberVerification(status: ProjectRepositoryStatus, projectBasePath: String?, organizationUrl: String) {
        lastVerified = if (status is ProjectRepositoryStatus.Found) {
            VerifiedRepository(
                projectBasePath = projectBasePath,
                organizationUrl = organizationUrl,
                status = status,
                verifiedAt = TimeSource.Monotonic.markNow()
            )
        } else {
            null
        }
    }

    /**
     * Lists the recent pull requests of the IDE project's repository together with the current user's uuid, which the
     * Created by and Approved-by-me filters need. The service caches the uuid per token, so only the first call makes
     * a request.
     */
    suspend fun listCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectListing {
        val project = boundProject
        val organizationUrl = configurationService.organizationUrlOrNull()
        if (project == null || organizationUrl == null) {
            LOG.info("Cannot list the current project pull requests: the coordinator is not bound or no organization URL is configured")
            return CurrentProjectListing(result = PullRequestListResult.Failed(), currentUserUuid = null)
        }
        val currentUserUuid = openPullRequestService.currentUserUuid(
            projectBasePath = project.basePath,
            organizationUrl = organizationUrl
        )
        val result = openPullRequestService.listRecentPullRequests(
            projectBasePath = project.basePath,
            organizationUrl = organizationUrl,
            refresh = refresh
        )
        return CurrentProjectListing(result = result, currentUserUuid = currentUserUuid)
    }

    private suspend fun resolveCurrentRepository(project: Project): ResolvedProjectRepository? {
        val resolved = try {
            withContext(Dispatchers.IO) {
                ProjectRepositoryResolver.resolve(
                    projectBasePath = project.basePath,
                    configuredWorkspace = configurationService.workspaceSlugOrNull(),
                    workTreeDirectory = configurationService.workTreeDirectoryOrNull()
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.info("Could not resolve the current project repository", exception)
            return null
        }
        if (resolved == null) {
            LOG.info("Could not resolve the current project repository")
        } else {
            LOG.debug("Resolved current project repository: ${resolved.workspace}/${resolved.repoSlug}")
        }
        return resolved
    }

    private fun openPullRequestInWorktree(summary: PullRequestSummary, source: String = "card") {
        val project = boundProject
        val coroutineScope = boundCoroutineScope
        if (project == null || coroutineScope == null) {
            LOG.info("Cannot open pull request #${summary.id}: the Pull Requests current project is not bound to a project")
            return
        }
        LOG.info("User requested to open pull request #${summary.id} in its own work tree via $source")
        coroutineScope.launch {
            val resolvedRepository = currentRepository ?: resolveCurrentRepository(project = project)
            if (resolvedRepository == null) {
                showWorktreeErrorNotification(
                    summary = summary,
                    content = DiffyBundle.message("otherProjects.worktree.failed.workTreeDirMissing")
                )
                return@launch
            }
            val resolution = worktreeOpener.resolve(
                workspace = resolvedRepository.workspace,
                repoSlug = resolvedRepository.repoSlug,
                prNumber = summary.id
            )
            val resolved = when (resolution) {
                is PullRequestWorktreeResolution.Resolved -> {
                    resolution
                }
                PullRequestWorktreeResolution.WorkTreeDirMissing -> {
                    LOG.info("Cannot open pull request #${summary.id}: work tree directory is unavailable")
                    showWorktreeErrorNotification(
                        summary = summary,
                        content = DiffyBundle.message("otherProjects.worktree.failed.workTreeDirMissing")
                    )
                    return@launch
                }
                PullRequestWorktreeResolution.SourceCheckoutMissing -> {
                    LOG.info("Cannot open pull request #${summary.id}: source checkout is unavailable")
                    showWorktreeErrorNotification(
                        summary = summary,
                        content = DiffyBundle.message(
                            "otherProjects.worktree.failed.sourceCheckoutMissing",
                            resolvedRepository.repoSlug
                        )
                    )
                    return@launch
                }
            }
            val worktreePath = resolved.worktreePath
            if (!worktreeOpener.tryBeginOpen(worktreePath = worktreePath)) {
                LOG.info("Opening pull request #${summary.id} is already in progress for $worktreePath")
                return@launch
            }
            try {
                // Attempt 1. On a branch collision, try one automatic switch to the main branch and retry (decision
                // 8a: exactly one switch and retry per click; a repeated collision falls back to the modal).
                val firstAttempt = runWorktreeModal(
                    summary = summary,
                    repository = resolvedRepository,
                    resolution = resolved
                )
                if (firstAttempt is WorktreeOpenOutcome.BranchCollision) {
                    val switched = resolveBranchCollision(
                        project = project,
                        summary = summary,
                        repository = resolvedRepository,
                        preparation = firstAttempt
                    )
                    if (switched) {
                        val secondAttempt = runWorktreeModal(
                            summary = summary,
                            repository = resolvedRepository,
                            resolution = resolved
                        )
                        handleWorktreePreparation(
                            project = project,
                            summary = summary,
                            repository = resolvedRepository,
                            preparation = secondAttempt
                        )
                    }
                } else {
                    handleWorktreePreparation(
                        project = project,
                        summary = summary,
                        repository = resolvedRepository,
                        preparation = firstAttempt
                    )
                }
            } catch (exception: CancellationException) {
                LOG.info("User cancelled work tree preparation for pull request #${summary.id}")
                throw exception
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    worktreeOpener.endOpen(worktreePath = worktreePath)
                }
            }
        }
    }

    /**
     * Handles a [runWorktreeModal] result: opens the work tree project, shows the failure balloon, or shows the
     * fallback collision modal. Shared by the first and the retried attempt. A [WorktreeOpenOutcome.BranchCollision]
     * here is a collision that could not be (or was already) auto-resolved, so it always shows the explanatory modal.
     */
    private suspend fun handleWorktreePreparation(
        project: Project,
        summary: PullRequestSummary,
        repository: ResolvedProjectRepository,
        preparation: WorktreeOpenOutcome
    ) {
        when (preparation) {
            is WorktreeOpenOutcome.Prepared -> {
                openWorktreeProjectAndEditor(
                    summary = preparation.summary,
                    repository = repository,
                    worktreePath = preparation.worktreePath
                )
            }
            is WorktreeOpenOutcome.Failed -> {
                showWorktreeErrorNotification(summary = summary, content = preparation.content)
            }
            is WorktreeOpenOutcome.BranchCollision -> {
                // The modal marshals to and blocks on the EDT itself, so call it directly from the coroutine
                // (the progress modal has already closed and no write lock is held here).
                val choice = WorktreeBranchCollisionDialog.show(
                    project = project,
                    prNumber = summary.id,
                    branchName = preparation.branchName,
                    sourceCheckoutPath = preparation.sourceCheckoutPath
                )
                if (choice == WorktreeBranchCollisionDialog.Choice.OPEN_IN_BITBUCKET) {
                    LOG.info("User opened Bitbucket PR #${summary.id} from the branch-collision modal")
                    BrowserUtil.browse(summary.webUrl)
                }
            }
        }
    }

    /**
     * Tries to free the pull request branch by switching the current checkout to the main branch via the shared
     * [WorktreeBranchCollisionResolver]. Returns true when the switch succeeded (the caller retries the work tree),
     * false when the resolver already handled the collision (uncommitted-changes modal, switch-failed balloon) or when
     * the collision must fall back to the explanatory modal, which this method shows itself.
     */
    private suspend fun resolveBranchCollision(
        project: Project,
        summary: PullRequestSummary,
        repository: ResolvedProjectRepository,
        preparation: WorktreeOpenOutcome.BranchCollision
    ): Boolean {
        val resolution = collisionResolver.resolve(
            project = project,
            workspace = repository.workspace,
            repoSlug = repository.repoSlug,
            summary = summary,
            branchName = preparation.branchName,
            sourceCheckoutPath = preparation.sourceCheckoutPath
        )
        return when (resolution) {
            WorktreeBranchCollisionResolver.CollisionResolution.Switched -> {
                ApplicationManager.getApplication().invokeLater {
                    onCurrentProjectReloadRequested("branch switched for work tree")
                }
                true
            }
            WorktreeBranchCollisionResolver.CollisionResolution.Handled -> {
                false
            }
            WorktreeBranchCollisionResolver.CollisionResolution.ShowDialog -> {
                val choice = WorktreeBranchCollisionDialog.show(
                    project = project,
                    prNumber = summary.id,
                    branchName = preparation.branchName,
                    sourceCheckoutPath = preparation.sourceCheckoutPath
                )
                if (choice == WorktreeBranchCollisionDialog.Choice.OPEN_IN_BITBUCKET) {
                    LOG.info("User opened Bitbucket PR #${summary.id} from the branch-collision modal")
                    BrowserUtil.browse(summary.webUrl)
                }
                false
            }
        }
    }

    private suspend fun runWorktreeModal(
        summary: PullRequestSummary,
        repository: ResolvedProjectRepository,
        resolution: PullRequestWorktreeResolution.Resolved
    ): WorktreeOpenOutcome {
        val organizationUrl = configurationService.organizationUrlOrNull()
        if (organizationUrl == null) {
            LOG.info("Cannot open pull request #${summary.id}: no organization URL configured")
            return WorktreeOpenOutcome.Failed(content = DiffyBundle.message("otherProjects.worktree.failed.noOrgUrl"))
        }
        return withModalProgress(
            owner = ModalTaskOwner.guess(),
            title = DiffyBundle.message("otherProjects.worktree.modalTitle"),
            cancellation = TaskCancellation.cancellable()
        ) {
            worktreeOpener.prepareWorktree(
                workspace = repository.workspace,
                repoSlug = repository.repoSlug,
                prNumber = summary.id,
                organizationUrl = organizationUrl,
                resolved = resolution
            )
        }
    }

    /** Opens the work tree window and queues the pull request editor in it. A failed window open shows the error balloon. */
    private suspend fun openWorktreeProjectAndEditor(summary: PullRequestSummary, repository: ResolvedProjectRepository, worktreePath: Path) {
        val openedProject = worktreeOpener.openProjectAndEditor(
            summary = summary,
            workspace = repository.workspace,
            repoSlug = repository.repoSlug,
            organizationUrl = configurationService.organizationUrlOrNull(),
            worktreePath = worktreePath
        )
        if (openedProject == null) {
            showWorktreeErrorNotification(
                summary = summary,
                content = DiffyBundle.message("otherProjects.worktree.failed.openProjectFailed")
            )
        }
    }

    private fun showWorktreeErrorNotification(summary: PullRequestSummary, content: String) {
        val project = boundProject ?: return
        val trimmed = content.trim()
        val balloonContent = if (trimmed.isBlank()) {
            DiffyBundle.message("otherProjects.worktree.failed.generic")
        } else if (trimmed.length > MAX_WORKTREE_FAILURE_POPUP_LENGTH) {
            trimmed.substring(0, MAX_WORKTREE_FAILURE_POPUP_LENGTH) + "\u2026"
        } else {
            trimmed
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                DiffyBundle.message("otherProjects.worktree.failed.title", summary.id),
                balloonContent,
                NotificationType.ERROR
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(DiffyBundle.message("otherProjects.worktree.openInBitbucket")) {
                LOG.info("User opened Bitbucket PR #${summary.id} from the work tree error popup")
                BrowserUtil.browse(summary.webUrl)
            }
        )
        notification.notify(project)
    }

    /** Opens (or focuses) the new pull request form in a `new.pr` editor tab of the current project. */
    fun openNewPullRequestEditor() {
        val project = boundProject
        val repository = currentRepository
        val organizationUrl = configurationService.organizationUrlOrNull()
        if (project == null || repository == null || organizationUrl == null) {
            LOG.info("Cannot open the new pull request form: the project repository or organization URL is unavailable")
            return
        }
        val repositoryWebUrl = repositoryWebUrl
            ?: BitbucketRepositoryUrls.repositoryWebUrlForWorkspace(workspace = repository.workspace, repoSlug = repository.repoSlug)
        if (repositoryWebUrl == null) {
            LOG.info("Cannot open the new pull request form: the repository web URL is unavailable")
            return
        }
        val coroutineScope = boundCoroutineScope
        if (coroutineScope == null) {
            LOG.info("Cannot open the new pull request form: the Pull Requests current project is not bound to a coroutine scope")
            return
        }
        coroutineScope.launch {
            val currentLocalBranch = withContext(Dispatchers.IO) { readCurrentLocalBranch(project = project) }
            val lastCommit = withContext(Dispatchers.IO) { readLastCommitPrefill(project = project) }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                project.service<DiffyNewPullRequestEditorOpener>().open(
                    NewPullRequestContext(
                        organizationUrl = organizationUrl,
                        workspace = repository.workspace,
                        repositorySlug = repository.repoSlug,
                        repositoryWebUrl = repositoryWebUrl,
                        currentLocalBranch = currentLocalBranch,
                        prefilledTitle = lastCommit.title,
                        prefilledDescription = lastCommit.description,
                        worktreePath = project.basePath
                    )
                )
            }
        }
    }

    /** Reads the last commit of the current checkout and splits it into a title and description; empty when unreadable. */
    private suspend fun readLastCommitPrefill(project: Project): ParsedCommitMessage {
        val rawMessage = project.basePath?.let { basePath ->
            LastCommitMessageReader.readLastCommitMessage(worktreePath = Path.of(basePath))
        }
        LOG.debug("Read the last commit for the new pull request prefill: found=${rawMessage != null}")
        return rawMessage?.let { raw -> CommitMessageParser.parse(rawMessage = raw) }
            ?: ParsedCommitMessage(title = "", description = "")
    }

    private fun readCurrentLocalBranch(project: Project): String? {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val basePath = project.basePath
        val baseDirectory = basePath?.let { path -> LocalFileSystem.getInstance().findFileByPath(path) }
        val repository = baseDirectory?.let { directory -> repositoryManager.getRepositoryForRootQuick(directory) }
            ?: repositoryManager.repositories.singleOrNull()
        return repository?.currentBranchName
    }

    /**
     * Orchestrates everything after a successful create: switches the current checkout to the main branch, reloads the
     * listings, and opens the created pull request in its own work tree. Runs in [boundCoroutineScope], which outlives
     * the `new.pr` tab. Returns false when the panel is not bound to a project or scope.
     */
    fun openCreatedPullRequest(summary: PullRequestSummary, localMainBranch: String?): Boolean {
        val project = boundProject
        val coroutineScope = boundCoroutineScope
        if (project == null || coroutineScope == null) {
            LOG.info("Cannot open created pull request #${summary.id}: the Pull Requests current project is not bound to a project")
            return false
        }
        LOG.info("Opening created pull request #${summary.id} in its own work tree")
        coroutineScope.launch {
            val switched = switchCurrentCheckoutToMainBranch(project = project, localMainBranch = localMainBranch, summary = summary)
            if (!switched) {
                return@launch
            }
            ApplicationManager.getApplication().invokeLater {
                onCurrentProjectReloadRequested("pull request created")
                openPullRequestInWorktree(summary = summary, source = "pull request creation")
            }
        }
        return true
    }

    /**
     * Switches the current project's checkout to [localMainBranch] so the created pull request's branch is free for a
     * work tree. Never switches over uncommitted work. Returns false when the switch could not be done, having already
     * shown the failure balloon.
     */
    private suspend fun switchCurrentCheckoutToMainBranch(project: Project, localMainBranch: String?, summary: PullRequestSummary): Boolean {
        if (localMainBranch == null) {
            LOG.info("No main branch known; skipping the branch switch before opening pull request #${summary.id}")
            return true
        }
        val status = branchCheckoutService.workingTreeStatus(project = project)
        when {
            status?.currentBranch == localMainBranch -> {
                LOG.debug("Current checkout is already on the main branch")
                return true
            }
            status != null && status.hasUncommittedChanges -> {
                LOG.info("Not switching to $localMainBranch before opening pull request #${summary.id}: uncommitted changes")
                showSwitchMainDirtyNotification(summary = summary, localMainBranch = localMainBranch)
                return false
            }
            status == null -> {
                LOG.info("Could not read the working tree before opening pull request #${summary.id}; attempting the checkout anyway")
            }
        }
        return when (val result = branchCheckoutService.checkout(project = project, branchName = localMainBranch)) {
            is BranchCheckoutResult.Success -> {
                LOG.info("Switched the current checkout to $localMainBranch before opening pull request #${summary.id}")
                true
            }
            is BranchCheckoutResult.Failed -> {
                LOG.warn("Could not switch the current checkout to the main branch before opening pull request #${summary.id}", result.cause)
                showSwitchMainFailedNotification(summary = summary, output = result.output)
                false
            }
        }
    }

    private fun showSwitchMainDirtyNotification(summary: PullRequestSummary, localMainBranch: String) {
        val project = boundProject ?: return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                DiffyBundle.message("newPr.switchMain.dirty.title"),
                DiffyBundle.message("newPr.switchMain.dirty.message", localMainBranch),
                NotificationType.ERROR
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(DiffyBundle.message("otherProjects.worktree.openInBitbucket")) {
                BrowserUtil.browse(summary.webUrl)
            }
        )
        notification.notify(project)
    }

    private fun showSwitchMainFailedNotification(summary: PullRequestSummary, output: String) {
        val project = boundProject ?: return
        val trimmed = output.trim()
        val balloonContent = if (trimmed.isBlank()) {
            DiffyBundle.message("newPr.switchMain.failed.generic")
        } else if (trimmed.length > MAX_WORKTREE_FAILURE_POPUP_LENGTH) {
            trimmed.substring(0, MAX_WORKTREE_FAILURE_POPUP_LENGTH) + "\u2026"
        } else {
            trimmed
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                DiffyBundle.message("newPr.switchMain.failed.title", summary.id),
                balloonContent,
                NotificationType.ERROR
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(DiffyBundle.message("otherProjects.worktree.openInBitbucket")) {
                BrowserUtil.browse(summary.webUrl)
            }
        )
        notification.notify(project)
    }

    companion object {
        private val LOG = logger<DiffyCurrentProjectCoordinator>()
        private const val NOTIFICATION_GROUP_ID = "Diffy"
        private const val MAX_WORKTREE_FAILURE_POPUP_LENGTH = 2_000
    }
}