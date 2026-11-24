package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyWorkspaceRepositoryService
import com.github.fnunezkanut.bbcloud.bitbucket.RepositoryLookupResult
import com.github.fnunezkanut.bbcloud.config.CheckedOutProjectRegistration
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyProjectCloneService
import com.github.fnunezkanut.bbcloud.git.ProjectCloneResult
import com.github.fnunezkanut.bbcloud.project.ProjectsDirectoryEntries
import com.github.fnunezkanut.bbcloud.project.sshCloneUrlFor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.ide.progress.withModalProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * The "check out a workspace repository" flow of the Watched Projects tab: let the user search Bitbucket for a
 * repository that is not checked out yet, re-check the chosen one against Bitbucket, clone it over SSH, and add it to
 * Watched Projects. The clone step answers [ProjectCloneResult.TargetExists] when the folder appeared after the dialog
 * opened, so no extra re-check is needed here.
 */
internal class WatchedProjectCheckoutLauncher(
    private val project: Project,
    private val configurationService: DiffyConfigurationService,
    private val repositoryService: DiffyWorkspaceRepositoryService,
    private val cloneService: DiffyProjectCloneService,
    private val coroutineScope: CoroutineScope,
    private val onInProgressChanged: (Boolean) -> Unit,
    private val onCheckedOut: (String) -> Unit
) {
    private val inProgress = AtomicBoolean(false)

    /** A repository chosen in the dialog, or the reason the selection ended. */
    private sealed interface Selection {
        data class Chosen(val repository: BitbucketWorkspaceRepository) : Selection

        data object Stopped : Selection
    }

    /** Starts the flow. Call it on the EDT. */
    fun start() {
        val workspace = configurationService.workspaceSlugOrNull()
        val projectsDirectory = configurationService.projectsDirectoryOrNull()
        if (workspace == null || projectsDirectory == null) {
            LOG.info("Cannot check out a project: workspace or projects directory not configured")
            return
        }
        if (!inProgress.compareAndSet(false, true)) {
            LOG.info("Ignoring Checkout Project: a checkout is already running")
            return
        }
        onInProgressChanged(true)
        coroutineScope.launch {
            try {
                runCheckout(workspace = workspace, projectsDirectory = projectsDirectory)
            } catch (exception: CancellationException) {
                LOG.info("Checkout Project was cancelled")
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Checkout Project failed", exception)
                notify(
                    title = DiffyBundle.message("workspace.watchedProjects.checkout.failed.title"),
                    content = DiffyBundle.message("workspace.watchedProjects.checkout.failed.generic"),
                    type = NotificationType.ERROR
                )
            } finally {
                inProgress.set(false)
                ApplicationManager.getApplication().invokeLater { onInProgressChanged(false) }
            }
        }
    }

    private suspend fun runCheckout(workspace: String, projectsDirectory: String) {
        val selection = chooseRepository(workspace = workspace, projectsDirectory = projectsDirectory)
        if (selection !is Selection.Chosen) {
            return
        }
        val selected = selection.repository
        val lookup = withModalProgress(
            owner = ModalTaskOwner.project(project),
            title = DiffyBundle.message("workspace.watchedProjects.checkout.verifying", selected.slug),
            cancellation = TaskCancellation.cancellable()
        ) {
            repositoryService.verifyRepository(workspace = workspace, repoSlug = selected.slug)
        }
        val verified = when (lookup) {
            is RepositoryLookupResult.Found -> {
                lookup.repository
            }
            RepositoryLookupResult.NotFound -> {
                notifyWarning(DiffyBundle.message("workspace.watchedProjects.checkout.failed.notFound", selected.slug))
                return
            }
            RepositoryLookupResult.Unauthorized -> {
                LOG.info("Checkout Project: Bitbucket refused the existence check of ${selected.slug}")
                notifyError(DiffyBundle.message("workspace.watchedProjects.checkout.failed.unauthorized"))
                return
            }
            is RepositoryLookupResult.Failed -> {
                LOG.warn("Could not verify that ${selected.slug} still exists", lookup.cause)
                notifyError(DiffyBundle.message("workspace.watchedProjects.checkout.failed.verify", selected.slug))
                return
            }
        }
        cloneAndRegister(workspace = workspace, projectsDirectory = projectsDirectory, verified = verified)
    }

    /**
     * Reads the Projects Directory once, then shows the search dialog until the user picks a repository or cancels.
     * The dialog asks Bitbucket for matches while the user types, so no full repository list is loaded.
     */
    private suspend fun chooseRepository(workspace: String, projectsDirectory: String): Selection {
        val names = withContext(Dispatchers.IO) {
            ProjectsDirectoryEntries.lowerCaseNames(projectsDirectory = Path.of(projectsDirectory))
        }
        if (names == null) {
            LOG.info("Checkout Project: the projects directory could not be read")
            notifyWarning(DiffyBundle.message("workspace.watchedProjects.checkout.failed.projectsDirectoryUnreadable"))
            return Selection.Stopped
        }
        val choice = withContext(Dispatchers.EDT) {
            CheckoutProjectDialog(
                project = project,
                workspace = workspace,
                projectsDirectory = projectsDirectory,
                existingFolderNames = names,
                search = { query -> repositoryService.searchRepositories(workspace = workspace, query = query) }
            ).showAndGetChoice()
        }
        return when (choice) {
            CheckoutProjectDialogChoice.Cancelled -> {
                LOG.info("User cancelled the Checkout Project dialog")
                Selection.Stopped
            }
            is CheckoutProjectDialogChoice.Selected -> {
                LOG.info("User chose to check out ${choice.repository.slug}")
                Selection.Chosen(repository = choice.repository)
            }
        }
    }

    private suspend fun cloneAndRegister(workspace: String, projectsDirectory: String, verified: BitbucketWorkspaceRepository) {
        val cloneUrl = sshCloneUrlFor(repository = verified, workspace = workspace)
        if (cloneUrl != verified.sshCloneUrl) {
            LOG.info("Bitbucket returned no usable SSH clone URL for ${verified.slug}; using $cloneUrl")
        } else {
            LOG.debug("Cloning ${verified.slug} over SSH")
        }
        val cloneResult = withBackgroundProgress(
            project,
            DiffyBundle.message("workspace.watchedProjects.checkout.progress", verified.slug),
            TaskCancellation.cancellable()
        ) {
            cloneService.cloneProject(projectsDirectory = Path.of(projectsDirectory), repoSlug = verified.slug, cloneUrl = cloneUrl)
        }
        when (cloneResult) {
            is ProjectCloneResult.Success -> {
                register(verified = verified, branch = cloneResult.branch)
            }
            is ProjectCloneResult.TargetExists -> {
                notifyWarning(DiffyBundle.message("workspace.watchedProjects.checkout.failed.exists"))
            }
            ProjectCloneResult.InvalidTarget -> {
                notifyError(DiffyBundle.message("workspace.watchedProjects.checkout.failed.generic"))
            }
            is ProjectCloneResult.Failed -> {
                LOG.warn("Cloning ${verified.slug} failed")
                notify(
                    title = DiffyBundle.message("workspace.watchedProjects.checkout.failed.title"),
                    content = failureBody(output = cloneResult.output),
                    type = NotificationType.ERROR
                )
            }
        }
    }

    private suspend fun register(verified: BitbucketWorkspaceRepository, branch: String?) {
        val registration = configurationService.registerCheckedOutProject(repoSlug = verified.slug, avatarUrl = verified.avatarUrl)
        withContext(Dispatchers.EDT) {
            onCheckedOut(verified.slug)
        }
        when (registration) {
            CheckedOutProjectRegistration.Watched -> {
                notify(
                    title = DiffyBundle.message("workspace.watchedProjects.checkout.title"),
                    content = DiffyBundle.message(
                        "workspace.watchedProjects.checkout.done",
                        verified.slug,
                        branch ?: verified.mainBranch ?: "?"
                    ),
                    type = NotificationType.INFORMATION
                )
            }
            CheckedOutProjectRegistration.AddedUnwatchedLimitReached -> {
                notifyWarning(
                    DiffyBundle.message(
                        "workspace.watchedProjects.checkout.doneLimit",
                        verified.slug,
                        WatchedProjectsViewModel.MAX_WATCHED_PROJECTS
                    )
                )
            }
            CheckedOutProjectRegistration.NotResolvable, CheckedOutProjectRegistration.NotConfigured -> {
                notifyWarning(DiffyBundle.message("workspace.watchedProjects.checkout.doneNotWatched", verified.slug))
            }
        }
    }

    private fun failureBody(output: String): String {
        val trimmed = output.trim()
        return when {
            trimmed.isBlank() -> DiffyBundle.message("workspace.watchedProjects.checkout.failed.generic")
            trimmed.length > MAX_FAILURE_OUTPUT_LENGTH -> trimmed.substring(0, MAX_FAILURE_OUTPUT_LENGTH) + "\u2026"
            else -> trimmed
        }
    }

    private fun notifyError(content: String) {
        notify(title = DiffyBundle.message("workspace.watchedProjects.checkout.title"), content = content, type = NotificationType.ERROR)
    }

    private fun notifyWarning(content: String) {
        notify(title = DiffyBundle.message("workspace.watchedProjects.checkout.title"), content = content, type = NotificationType.WARNING)
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project)
        }
    }

    companion object {
        private val LOG = logger<WatchedProjectCheckoutLauncher>()
        private const val NOTIFICATION_GROUP_ID = "Diffy"
        private const val MAX_FAILURE_OUTPUT_LENGTH = 600
    }
}