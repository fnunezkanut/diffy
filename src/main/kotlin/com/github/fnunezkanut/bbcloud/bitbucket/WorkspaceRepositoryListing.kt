package com.github.fnunezkanut.bbcloud.bitbucket

import kotlin.time.Instant

enum class WorkspaceRepositoryRefresh {
    /** Use a snapshot younger than the max age without any request. */
    IfStale,

    /** Always ask Bitbucket (the dialog's Refresh List button). */
    Force
}

sealed class WorkspaceRepositoryListing {
    /**
     * [fromCache] is true when no request was made or the request failed and the stale snapshot was used
     * ([refreshFailed] then is true).
     */
    data class Listed(
        val repositories: List<BitbucketWorkspaceRepository>,
        val isTruncated: Boolean,
        val fetchedAt: Instant,
        val fromCache: Boolean,
        val refreshFailed: Boolean
    ) : WorkspaceRepositoryListing()

    data object Unauthorized : WorkspaceRepositoryListing()

    data class Failed(val cause: Throwable? = null) : WorkspaceRepositoryListing()
}