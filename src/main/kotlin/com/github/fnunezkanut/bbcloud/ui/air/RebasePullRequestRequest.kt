package com.github.fnunezkanut.bbcloud.ui.air

/**
 * The facts the "Rebase onto latest with AI" prompt needs. No IntelliJ types, so it can be unit tested.
 *
 * @param remoteName the git remote that holds the pull request branches, for example origin.
 * @param repositoryPath the absolute path of the work tree, or null to use the working directory of the agent.
 */
data class RebasePullRequestRequest(
    val pullRequestId: Long,
    val sourceBranch: String,
    val destinationBranch: String,
    val remoteName: String,
    val repositoryPath: String?
)