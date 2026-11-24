package com.github.fnunezkanut.bbcloud.git

import git4idea.repo.GitRemote
import git4idea.repo.GitRepository

/** Resolves the remote that holds the pull request branches of a repository. */
internal object PrRemoteResolver {
    /** The name of the `origin` remote when present, otherwise of the first remote, otherwise `origin`. */
    fun remoteName(repository: GitRepository): String {
        return ProjectGitRepositoryLookup.selectRemote(repository)?.name ?: GitRemote.ORIGIN
    }
}