package com.github.fnunezkanut.bbcloud.bitbucket

interface BitbucketRepositoryListClient {
    suspend fun listRepositories(workspace: String, apiToken: String): RepositoryListResult

    suspend fun fetchRepository(workspace: String, repoSlug: String, apiToken: String): RepositoryLookupResult

    /** Fetches one page of repositories whose slug or name contains [query]. */
    suspend fun searchRepositories(workspace: String, query: String, apiToken: String): RepositorySearchResult
}

sealed class RepositoryListResult {
    /** [isTruncated] is true when the listing stopped at the page cap before the last page. */
    data class Success(val repositories: List<BitbucketWorkspaceRepository>, val isTruncated: Boolean) : RepositoryListResult()

    /** 401 or 403: the login is invalid, expired, or lacks the repository scope. */
    data object Unauthorized : RepositoryListResult()

    data class Failed(val cause: Throwable? = null) : RepositoryListResult()
}

sealed class RepositoryLookupResult {
    data class Found(val repository: BitbucketWorkspaceRepository) : RepositoryLookupResult()

    /** 404: the repository was deleted, renamed away, or the caller lost access. */
    data object NotFound : RepositoryLookupResult()

    data object Unauthorized : RepositoryLookupResult()

    data class Failed(val cause: Throwable? = null) : RepositoryLookupResult()
}

sealed class RepositorySearchResult {
    /** [hasMore] is true when Bitbucket has more matches than the single page that was fetched. */
    data class Success(val repositories: List<BitbucketWorkspaceRepository>, val hasMore: Boolean) : RepositorySearchResult()

    /** 401 or 403. */
    data object Unauthorized : RepositorySearchResult()

    data class Failed(val cause: Throwable? = null) : RepositorySearchResult()
}