package com.github.fnunezkanut.bbcloud.ui.diffmain

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.git.GitMainBranchLocator
import com.github.fnunezkanut.bbcloud.git.MainBranchRef
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.util.GitFileUtils

/**
 * Shows the diff of a file against the main branch (`main` or `master`): the version on the tip of the main ref on the
 * left, the working tree file (the live document, so unsaved edits count) on the right.
 *
 * The file is read from git in a background task, and the diff opens on the EDT.
 */
internal object DiffAgainstMainOpener {
    /** Set on the diff request, so the pull request bars (which attach to every diff of the project) skip it. */
    val DIFF_AGAINST_MAIN_KEY: Key<Boolean> = Key.create("diffy.diffAgainstMain")

    private val LOG = logger<DiffAgainstMainOpener>()
    private const val NOTIFICATION_GROUP_ID = "Diffy"
    private val MISSING_PATH_MARKERS = listOf("does not exist", "exists on disk, but not in", "invalid object name", "bad revision")

    fun open(project: Project, file: VirtualFile) {
        ProgressManager.getInstance().run(LoadMainVersionTask(project = project, file = file))
    }

    /** What the background load found. */
    private sealed interface LoadResult {
        /** [text] is null when the file does not exist on the main branch. */
        data class Loaded(val mainRef: MainBranchRef, val text: String?) : LoadResult

        data object NoMainBranch : LoadResult

        data object GitFailed : LoadResult
    }

    private class LoadMainVersionTask(private val project: Project, private val file: VirtualFile) :
        Task.Backgroundable(project, DiffyBundle.message("diffMain.progress"), true) {
        private var result: LoadResult = LoadResult.GitFailed

        override fun run(indicator: ProgressIndicator) {
            result = load(project = project, file = file)
        }

        override fun onSuccess() {
            show(project = project, file = file, result = result)
        }
    }

    private fun load(project: Project, file: VirtualFile): LoadResult {
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
        val relativePath = repository?.let { found -> VfsUtilCore.getRelativePath(file, found.root) }
        if (repository == null || relativePath == null) {
            LOG.info("Diff against main skipped: the file is not in a Git repository of the project")
            return LoadResult.GitFailed
        }
        val mainRef = GitMainBranchLocator.locate(project = project, repository = repository)
        if (mainRef == null) {
            LOG.info("Diff against main skipped: the repository has no main or master branch")
            return LoadResult.NoMainBranch
        }
        return readMainVersion(project = project, repository = repository, mainRef = mainRef, relativePath = relativePath, file = file)
    }

    private fun readMainVersion(project: Project, repository: GitRepository, mainRef: MainBranchRef, relativePath: String, file: VirtualFile): LoadResult {
        return try {
            val content = GitFileUtils.getFileContent(project, repository.root, mainRef.ref, relativePath)
            LoadResult.Loaded(mainRef = mainRef, text = content.toString(file.charset))
        } catch (exception: VcsException) {
            if (isMissingPath(exception = exception)) {
                LOG.debug("The file does not exist at '${mainRef.ref}'; comparing against an empty side")
                LoadResult.Loaded(mainRef = mainRef, text = null)
            } else {
                LOG.warn("Could not read a file from '${mainRef.ref}' for the diff against the main branch", exception)
                LoadResult.GitFailed
            }
        }
    }

    private fun isMissingPath(exception: VcsException): Boolean {
        val message = exception.message.orEmpty()
        return MISSING_PATH_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }
    }

    /** Runs on the EDT after the background load. */
    private fun show(project: Project, file: VirtualFile, result: LoadResult) {
        if (project.isDisposed || !file.isValid) {
            LOG.info("Diff against main skipped: the project or the file is gone")
            return
        }
        when (result) {
            is LoadResult.Loaded -> showDiff(project = project, file = file, loaded = result)
            LoadResult.NoMainBranch -> notifyError(project = project, messageKey = "diffMain.error.noMainBranch")
            LoadResult.GitFailed -> notifyError(project = project, messageKey = "diffMain.error.gitFailed")
        }
    }

    private fun showDiff(project: Project, file: VirtualFile, loaded: LoadResult.Loaded) {
        val factory = DiffContentFactory.getInstance()
        val mainRef = loaded.mainRef
        val leftContent: DiffContent = if (loaded.text == null) {
            factory.createEmpty()
        } else {
            factory.create(project, loaded.text, file)
        }
        val leftTitle = if (loaded.text == null) {
            DiffyBundle.message("diffMain.side.missing", mainRef.ref)
        } else {
            mainRef.ref
        }
        val request = SimpleDiffRequest(
            DiffyBundle.message("diffMain.diff.title", file.name, mainRef.ref),
            leftContent,
            factory.create(project, file),
            leftTitle,
            DiffyBundle.message("diffMain.side.workingTree")
        )
        request.putUserData(DIFF_AGAINST_MAIN_KEY, true)
        DiffManager.getInstance().showDiff(project, request)
        LOG.debug("Opened the diff of a file against ${mainRef.ref}")
    }

    private fun notifyError(project: Project, messageKey: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(DiffyBundle.message(messageKey), NotificationType.WARNING)
            .notify(project)
    }
}