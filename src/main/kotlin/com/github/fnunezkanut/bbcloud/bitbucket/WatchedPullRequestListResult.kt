package com.github.fnunezkanut.bbcloud.bitbucket

sealed class WatchedPullRequestListResult {
    /**
     * The pull requests of every watched repository that could be listed. [isTruncated] means the listing is knowingly
     * incomplete: a repository failed with no stored listing to fall back to, or the watched set ran past the cap.
     */
    data class Success(
        val pullRequests: List<ReviewingPullRequest>,
        val isTruncated: Boolean,
        val failedRepoSlugs: Set<String>,
        /** Repositories whose listing was fetched live by this load (not cache, not stale fallback); work tree cleanup input. */
        val liveRepoSlugs: Set<String>,
        /** The account the listing was keyed by; used by the Created by and Approved-by-me filters. */
        val currentUserUuid: String?
    ) : WatchedPullRequestListResult()

    data class Failed(val cause: Throwable? = null) : WatchedPullRequestListResult()
}