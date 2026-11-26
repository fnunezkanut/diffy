package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.GitBranchNames
import com.github.fnunezkanut.bbcloud.git.ProjectGitRepositoryLookup
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository

/**
 * The tips of the branches the red bar depends on. A change of any of them, not only of HEAD, re-checks the bar, so a
 * destination-only fast-forward refreshes it too.
 */
internal data class ObservedBranchTips(
    val headBranch: String?,
    val headRevision: String?,
    val sourceLocal: String?,
    val destinationLocal: String?,
    val sourceRemote: String?,
    val destinationRemote: String?
) {
    companion object {
        fun read(repository: GitRepository, sourceBranch: String, destinationBranch: String): ObservedBranchTips {
            val remoteName = ProjectGitRepositoryLookup.selectRemote(repository)?.name ?: GitRemote.ORIGIN
            return ObservedBranchTips(
                headBranch = repository.currentBranchName,
                headRevision = repository.currentRevision,
                sourceLocal = localTip(repository = repository, branch = sourceBranch, remoteName = remoteName),
                destinationLocal = localTip(repository = repository, branch = destinationBranch, remoteName = remoteName),
                sourceRemote = remoteTip(repository = repository, branch = sourceBranch, remoteName = remoteName),
                destinationRemote = remoteTip(repository = repository, branch = destinationBranch, remoteName = remoteName)
            )
        }

        private fun localTip(repository: GitRepository, branch: String, remoteName: String): String? {
            val name = GitBranchNames.normalize(branchName = branch, remoteName = remoteName)
            val local = repository.branches.findLocalBranch(name) ?: return null
            return repository.branches.getHash(local)?.asString()
        }

        private fun remoteTip(repository: GitRepository, branch: String, remoteName: String): String? {
            val name = GitBranchNames.normalize(branchName = branch, remoteName = remoteName)
            val remote = repository.branches.findRemoteBranch("$remoteName/$name") ?: return null
            return repository.branches.getHash(remote)?.asString()
        }
    }
}