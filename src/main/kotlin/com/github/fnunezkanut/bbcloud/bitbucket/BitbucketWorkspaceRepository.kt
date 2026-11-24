package com.github.fnunezkanut.bbcloud.bitbucket

/**
 * One repository of a Bitbucket workspace, as listed by `GET /2.0/repositories/{workspace}`. [avatarUrl] is a public
 * CDN URL and must never be requested with the access token.
 */
data class BitbucketWorkspaceRepository(
    val slug: String,
    val name: String,
    val mainBranch: String?,
    val httpsCloneUrl: String?,
    val sshCloneUrl: String?,
    val avatarUrl: String?
)

/** One page of workspace repositories and the URL of the next page. */
data class WorkspaceRepositoryPage(val repositories: List<BitbucketWorkspaceRepository>, val nextUrl: String?)