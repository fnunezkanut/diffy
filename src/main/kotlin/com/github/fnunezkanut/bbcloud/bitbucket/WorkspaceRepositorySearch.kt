package com.github.fnunezkanut.bbcloud.bitbucket

sealed class WorkspaceRepositorySearch {
    /** [hasMore] is true when Bitbucket has more matches than the single page that was fetched. */
    data class Found(val repositories: List<BitbucketWorkspaceRepository>, val hasMore: Boolean) : WorkspaceRepositorySearch()

    data object Unauthorized : WorkspaceRepositorySearch()

    data class Failed(val cause: Throwable? = null) : WorkspaceRepositorySearch()
}