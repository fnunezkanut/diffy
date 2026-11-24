package com.github.fnunezkanut.bbcloud.git

import git4idea.repo.GitRepository

/** Resolves a Bitbucket branch name to a ref of the local repository without fetching. */
internal object GitBranchRefResolver {
    /**
     * Returns the first existing candidate ref for [branchName], or null when none exists. With [preferRemote] the
     * remote tracking ref wins over the local branch.
     */
    fun resolve(repository: GitRepository, branchName: String, preferRemote: Boolean): String? {
        val candidates = GitBranchRefCandidates.candidates(
            branchName = branchName,
            remoteName = PrRemoteResolver.remoteName(repository),
            preferRemote = preferRemote
        )
        for (candidate in candidates) {
            if (repository.branches.findBranchByName(candidate) != null) {
                return candidate
            }
        }
        return null
    }
}