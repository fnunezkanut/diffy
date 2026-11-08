package com.github.fnunezkanut.bbcloud.cache

/**
 * Builds the identifiers of listing snapshots. Each identifier names one query whose results the cache remembers:
 * the open pull requests of a project repository, the workspace's authored pull requests, or the pull requests
 * assigned to the user in one watched repository.
 */
object PullRequestListingIds {
    const val PROJECT_PREFIX = "project:"
    const val WORKSPACE_AUTHORED_PREFIX = "workspace-authored:"
    const val WORKSPACE_REVIEWING_PREFIX = "workspace-reviewing:"

    fun project(workspace: String, repoSlug: String): String {
        return PROJECT_PREFIX + repositoryPath(workspace = workspace, repoSlug = repoSlug)
    }

    fun workspaceAuthored(workspace: String): String {
        return WORKSPACE_AUTHORED_PREFIX + normalize(value = workspace)
    }

    fun workspaceReviewing(workspace: String, repoSlug: String): String {
        return WORKSPACE_REVIEWING_PREFIX + repositoryPath(workspace = workspace, repoSlug = repoSlug)
    }

    private fun repositoryPath(workspace: String, repoSlug: String): String {
        return normalize(value = workspace) + "/" + normalize(value = repoSlug)
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase()
    }
}