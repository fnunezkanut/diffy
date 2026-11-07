package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

data class DiffyPullRequestEditorRestorationRequest(val projectBasePath: String, val organizationUrl: String, val pullRequestId: Long)

object DiffyPullRequestEditorRestoration {
    fun request(projectBasePath: String?, file: DiffyPullRequestVirtualFile): DiffyPullRequestEditorRestorationRequest? {
        val basePath = projectBasePath ?: return null
        return DiffyPullRequestEditorRestorationRequest(
            projectBasePath = basePath,
            organizationUrl = file.pullRequestPath.workspaceUrl,
            pullRequestId = file.pullRequestPath.pullRequestId
        )
    }
}