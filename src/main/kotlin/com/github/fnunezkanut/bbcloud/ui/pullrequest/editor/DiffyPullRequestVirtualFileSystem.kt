package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.editor.ComplexPathVirtualFileSystem

class DiffyPullRequestVirtualFileSystem : ComplexPathVirtualFileSystem<DiffyPullRequestPath>(DiffyPullRequestPathSerializer) {
    override fun getProtocol(): String {
        return PROTOCOL
    }

    public override fun findOrCreateFile(project: Project, path: DiffyPullRequestPath): VirtualFile? {
        if (project.locationHash != path.projectHash) {
            LOG.debug("Rejected pull request editor path for project ${project.name}: project hash did not match")
            return null
        }
        return DiffyPullRequestVirtualFile(
            project = project,
            pullRequestPath = path,
            fileSystem = this
        )
    }

    companion object {
        const val PROTOCOL = "diffypr"

        fun getInstance(): DiffyPullRequestVirtualFileSystem {
            return ProjectManager.getInstance().openProjects.firstOrNull()?.let { project ->
                com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                    .getFileSystem(PROTOCOL) as? DiffyPullRequestVirtualFileSystem
            } ?: DiffyPullRequestVirtualFileSystem()
        }

        fun sessionId(project: Project): String {
            return project.locationHash
        }

        fun getPath(sessionId: String, project: Project, workspaceUrl: String, repositorySlug: String, pullRequestId: Long): String {
            val path = DiffyPullRequestPath(
                sessionId = sessionId,
                projectHash = project.locationHash,
                workspaceUrl = DiffyPullRequestPathSerializer.normalizeWorkspaceUrl(workspaceUrl),
                repositorySlug = repositorySlug,
                pullRequestId = pullRequestId
            )
            return getInstance().getPath(path)
        }

        private val LOG = logger<DiffyPullRequestVirtualFileSystem>()
    }
}