package com.github.fnunezkanut.bbcloud.ui.pullrequests

import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest

/**
 * One row of a merged pull request tab: the pull request with its repository, the source it came from (which decides
 * title-click behavior), and the repository avatar URL resolved for its row.
 */
data class PullRequestListEntry(val pullRequest: ReviewingPullRequest, val source: PullRequestListingSource, val repoAvatarUrl: String?) {
    /** The repository of this row, used by the project scope filter. */
    val repositoryKey: PullRequestRepositoryKey
        get() {
            return PullRequestRepositoryKey.of(workspace = pullRequest.workspace, repoSlug = pullRequest.repoSlug)
        }

    /** The de-duplication key: workspace and repository slug lowercased, plus the pull request id. */
    val key: PullRequestListKey
        get() {
            return PullRequestListKey(
                workspace = pullRequest.workspace.lowercase(),
                repoSlug = pullRequest.repoSlug.lowercase(),
                id = pullRequest.summary.id
            )
        }
}

/** Identifies one pull request across sources; [workspace] and [repoSlug] are lowercased so case cannot split a row. */
data class PullRequestListKey(val workspace: String, val repoSlug: String, val id: Long)

/** Identifies one repository; [workspace] and [repoSlug] are trimmed and lowercased so case cannot split a repository. */
data class PullRequestRepositoryKey(val workspace: String, val repoSlug: String) {
    companion object {
        fun of(workspace: String, repoSlug: String): PullRequestRepositoryKey {
            return PullRequestRepositoryKey(workspace = workspace.trim().lowercase(), repoSlug = repoSlug.trim().lowercase())
        }
    }
}