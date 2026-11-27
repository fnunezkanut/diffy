package com.github.fnunezkanut.bbcloud.ui.air

/**
 * The facts the "Rebase onto latest with AI" prompt needs. No IntelliJ types, so it can be unit tested.
 *
 * @param remoteName the git remote that holds the pull request branches, for example origin.
 * @param repositoryPath the absolute path of the work tree, or null to use the working directory of the agent.
 * @param headSha the `HEAD` commit at the time the link was clicked.
 * @param backupRef the full backup ref name of [headSha], for example `refs/diffy/backups/pr-7/1795694861000`.
 * @param wipBackupRef the `-wip` ref of the uncommitted tracked changes, or null when the tracked tree was clean or that
 * backup failed.
 */
data class RebasePullRequestRequest(
    val pullRequestId: Long,
    val sourceBranch: String,
    val destinationBranch: String,
    val remoteName: String,
    val repositoryPath: String?,
    val headSha: String,
    val backupRef: String,
    val wipBackupRef: String?
)