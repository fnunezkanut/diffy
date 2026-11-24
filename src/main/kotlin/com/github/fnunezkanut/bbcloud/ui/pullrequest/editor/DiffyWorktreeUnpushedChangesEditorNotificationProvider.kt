package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestPathMatcher
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel
import com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestVirtualFile
import com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyWorktreesHelpVirtualFile
import com.intellij.diff.editor.DiffViewerVirtualFile
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import java.util.WeakHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows the orange "not in the pull request yet" bar under the blue bar for every local file of a plugin-managed pull
 * request work tree whose pull request is proven Open. The bar renders itself only for files with unpushed commits or
 * uncommitted changes.
 */
class DiffyWorktreeUnpushedChangesEditorNotificationProvider :
    EditorNotificationProvider,
    DumbAware {
    private val bars = WeakHashMap<FileEditor, WorktreeUnpushedChangesBarPanel>()

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file is DiffyNewPullRequestVirtualFile || file is DiffyWorktreesHelpVirtualFile) {
            LOG.debug("Orange bar skipped: the file is not a work tree file")
            return null
        }
        // The diff views get the orange bar inside the diff through PullRequestDiffBarsDiffExtension.
        if (file is DiffViewerVirtualFile) {
            LOG.debug("Orange bar skipped: the diff view gets the orange bar from the diff extension")
            return null
        }
        if (!file.isInLocalFileSystem) {
            LOG.debug("Orange bar skipped: the file is not in the local file system")
            return null
        }
        val projectBasePath = project.basePath
        if (projectBasePath == null) {
            LOG.debug("Orange bar skipped: the project has no base path")
            return null
        }
        val normalizedBase = PullRequestPathMatcher.normalize(projectBasePath)
        if (!PullRequestPathMatcher.normalize(file.path).startsWith("$normalizedBase/")) {
            LOG.debug("Orange bar skipped: the file is outside the work tree")
            return null
        }
        val isManaged = PullRequestWorktreeProject.isPluginManagedWorktree(
            projectBasePath = projectBasePath,
            workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        )
        if (!isManaged) {
            LOG.debug("Orange bar skipped: the project is not a plugin-managed pull request worktree")
            return null
        }
        val viewModel = WorktreeTodoBannerViewModel.getInstance(project)
        viewModel.ensureInitialized()
        if (!viewModel.state.value.isWorktree) {
            LOG.debug("Orange bar skipped: the pull request of the work tree is not proven open")
            return null
        }
        val relativePath = PullRequestPathMatcher.normalize(
            PullRequestPathMatcher.relativeProjectPath(projectBasePath = projectBasePath, absoluteOrRelativePath = file.path)
        )
        return Function<FileEditor, JComponent?> { fileEditor ->
            barFor(project = project, fileEditor = fileEditor, relativePath = relativePath)
        }
    }

    private fun barFor(project: Project, fileEditor: FileEditor, relativePath: String): JComponent {
        val existing = bars[fileEditor]
        if (existing != null) {
            return existing
        }
        val bar = WorktreeUnpushedChangesBarPanel.create(project = project, relativePath = relativePath)
        bars[fileEditor] = bar
        if (!Disposer.tryRegister(fileEditor, bar)) {
            bar.dispose()
        }
        return bar
    }

    companion object {
        private val LOG = logger<DiffyWorktreeUnpushedChangesEditorNotificationProvider>()
    }
}