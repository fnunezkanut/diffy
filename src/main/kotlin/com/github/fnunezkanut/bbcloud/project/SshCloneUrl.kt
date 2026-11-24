package com.github.fnunezkanut.bbcloud.project

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryRemoteUrl
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository

internal const val FALLBACK_SSH_HOST = "ssh.bitbucket.org"

internal fun fallbackSshCloneUrl(workspace: String, repoSlug: String): String {
    return "git@$FALLBACK_SSH_HOST:$workspace/$repoSlug"
}

/**
 * The SSH URL to clone [repository] from: Bitbucket's own SSH link when it parses to [workspace] and the repository's
 * slug, otherwise the fallback form. Never an HTTPS URL.
 */
internal fun sshCloneUrlFor(repository: BitbucketWorkspaceRepository, workspace: String): String {
    val url = repository.sshCloneUrl
    if (url != null && isAcceptedSshUrl(url = url, workspace = workspace, repoSlug = repository.slug)) {
        return url
    }
    return fallbackSshCloneUrl(workspace = workspace, repoSlug = repository.slug)
}

private fun isAcceptedSshUrl(url: String, workspace: String, repoSlug: String): Boolean {
    if (url.startsWith(prefix = "https://", ignoreCase = true)) {
        return false
    }
    val remote = BitbucketRepositoryRemoteUrl.parse(url) ?: return false
    return remote.workspace == workspace && remote.repoSlug == repoSlug
}