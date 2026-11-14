package com.github.fnunezkanut.bbcloud.bitbucket

/**
 * A pull request together with the repository it belongs to. The Pull Requests listings are built from it: the
 * pull requests of the current project and of the watched projects, collected per repository, and the pull requests
 * the user opened, collected across the whole workspace.
 *
 * The repository is not part of [PullRequestSummary] because the Pull Requests mixes several repositories in one table,
 * where the pull request id alone no longer identifies a row.
 */
data class ReviewingPullRequest(val summary: PullRequestSummary, val workspace: String, val repoSlug: String) {
    /** The repository slug (workspace is stripped). */
    fun repositoryName(): String {
        return repoSlug
    }
}