package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyBearerRetryAuthenticator
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

interface BitbucketRepositoryClient {
    suspend fun checkRepositoryExists(workspace: String, repoSlug: String, apiToken: String): RepositoryCheckResult
}

sealed class RepositoryCheckResult {
    /**
     * The repository exists. [avatarUrl] carries the repository's configured avatar/icon URL
     * (`links.avatar.href`) when the 200 body included one, or null when it was absent, blank, or the body could not
     * be parsed. The avatar URL points to a public CDN, so it must never be requested with the Bitbucket access token.
     */
    data class Exists(val avatarUrl: String? = null) : RepositoryCheckResult()

    data object NotFound : RepositoryCheckResult()

    data class Failed(val cause: Throwable? = null) : RepositoryCheckResult()
}

class OkHttpBitbucketRepositoryClient(private val httpClient: OkHttpClient = sharedHttpClient) :
    BitbucketRepositoryClient,
    BitbucketRepositoryListClient {
    override suspend fun checkRepositoryExists(workspace: String, repoSlug: String, apiToken: String): RepositoryCheckResult {
        return withContext(Dispatchers.IO) {
            // Request only the avatar href via Bitbucket's partial-response `fields` parameter so the 200 payload stays
            // tiny; the status code still decides existence and the href is a bonus read when present.
            val apiUrl = "$BITBUCKET_API_BASE/repositories/$workspace/$repoSlug?fields=links.avatar.href"
            if (!BitbucketApiUrlPolicy.isTrustedApiUrl(apiUrl)) {
                LOG.warn("Refusing to send the access token to an untrusted URL (${BitbucketApiUrlPolicy.describeRejected(apiUrl)})")
                return@withContext RepositoryCheckResult.Failed()
            }
            val request = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer $apiToken")
                .get()
                .build()
            LOG.debug("GET $apiUrl")
            val startNanos = System.nanoTime()
            try {
                httpClient.newCall(request).execute().use { response ->
                    LOG.debug("GET $apiUrl -> ${response.code} in ${elapsedMillis(startNanos = startNanos)} ms")
                    when (response.code) {
                        200 -> {
                            RepositoryCheckResult.Exists(avatarUrl = parseAvatarUrl(body = response.body.string()))
                        }
                        404 -> {
                            RepositoryCheckResult.NotFound
                        }
                        else -> {
                            LOG.warn("Unexpected HTTP status ${response.code} for GET $apiUrl${BitbucketHttpErrors.describe(response)}")
                            RepositoryCheckResult.Failed()
                        }
                    }
                }
            } catch (exception: IOException) {
                LOG.debug("GET $apiUrl failed after ${elapsedMillis(startNanos = startNanos)} ms", exception)
                RepositoryCheckResult.Failed(exception)
            }
        }
    }

    override suspend fun listRepositories(workspace: String, apiToken: String): RepositoryListResult {
        return withContext(Dispatchers.IO) {
            val repositories = mutableListOf<BitbucketWorkspaceRepository>()
            var nextUrl: String? = BitbucketRepositoryListResponseParser.buildListRepositoriesUrl(workspace = workspace)
            var pagesFetched = 0
            while (nextUrl != null && pagesFetched < MAX_REPOSITORY_PAGES) {
                when (val outcome = fetchRepositoriesPage(url = nextUrl, apiToken = apiToken)) {
                    is PageOutcome.Stop -> {
                        return@withContext outcome.result
                    }
                    is PageOutcome.Page -> {
                        repositories += outcome.page.repositories
                        nextUrl = outcome.page.nextUrl
                        pagesFetched++
                    }
                }
            }
            val distinct = repositories.distinctBy { repository -> repository.slug }
            LOG.debug("Parsed ${distinct.size} repositories across $pagesFetched pages for $workspace (truncated=${nextUrl != null})")
            RepositoryListResult.Success(repositories = distinct, isTruncated = nextUrl != null)
        }
    }

    override suspend fun fetchRepository(workspace: String, repoSlug: String, apiToken: String): RepositoryLookupResult {
        return withContext(Dispatchers.IO) {
            val apiUrl = BitbucketRepositoryListResponseParser.buildRepositoryUrl(workspace = workspace, repoSlug = repoSlug)
            executeTrustedGet(
                url = apiUrl,
                apiToken = apiToken,
                onUntrusted = { RepositoryLookupResult.Failed() },
                onFailure = { exception -> RepositoryLookupResult.Failed(exception) }
            ) { response ->
                when (response.code) {
                    200 -> {
                        val repository = BitbucketRepositoryListResponseParser.parseRepository(responseBody = response.body.string())
                        if (repository == null) {
                            RepositoryLookupResult.Failed()
                        } else {
                            RepositoryLookupResult.Found(repository)
                        }
                    }
                    404 -> {
                        RepositoryLookupResult.NotFound
                    }
                    401, 403 -> {
                        RepositoryLookupResult.Unauthorized
                    }
                    else -> {
                        LOG.warn("Unexpected HTTP status ${response.code} for GET $apiUrl${BitbucketHttpErrors.describe(response)}")
                        RepositoryLookupResult.Failed()
                    }
                }
            }
        }
    }

    override suspend fun searchRepositories(workspace: String, query: String, apiToken: String): RepositorySearchResult {
        val apiUrl = BitbucketRepositoryListResponseParser.buildSearchRepositoriesUrl(workspace = workspace, query = query)
        // runInterruptible turns coroutine cancellation into a thread interrupt, which OkHttp reports as an IOException.
        return runInterruptible(Dispatchers.IO) {
            executeTrustedGet(
                url = apiUrl,
                apiToken = apiToken,
                onUntrusted = { RepositorySearchResult.Failed() },
                onFailure = { exception -> RepositorySearchResult.Failed(exception) }
            ) { response ->
                when (response.code) {
                    200 -> {
                        parseSearchPage(workspace = workspace, body = response.body.string())
                    }
                    401, 403 -> {
                        RepositorySearchResult.Unauthorized
                    }
                    else -> {
                        LOG.warn("Unexpected HTTP status ${response.code} for GET $apiUrl${BitbucketHttpErrors.describe(response)}")
                        RepositorySearchResult.Failed()
                    }
                }
            }
        }
    }

    private fun parseSearchPage(workspace: String, body: String): RepositorySearchResult {
        val page = BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = body)
        if (page == null) {
            LOG.debug("Could not parse a repository search page")
            return RepositorySearchResult.Failed()
        }
        val hasMore = page.nextUrl != null
        LOG.debug("Search in $workspace returned ${page.repositories.size} repositories (hasMore=$hasMore)")
        return RepositorySearchResult.Success(repositories = page.repositories, hasMore = hasMore)
    }

    private sealed class PageOutcome {
        data class Page(val page: WorkspaceRepositoryPage) : PageOutcome()

        data class Stop(val result: RepositoryListResult) : PageOutcome()
    }

    private fun fetchRepositoriesPage(url: String, apiToken: String): PageOutcome {
        return executeTrustedGet(
            url = url,
            apiToken = apiToken,
            onUntrusted = { PageOutcome.Stop(RepositoryListResult.Failed()) },
            onFailure = { exception -> PageOutcome.Stop(RepositoryListResult.Failed(exception)) }
        ) { response ->
            when (response.code) {
                200 -> {
                    val page = BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = response.body.string())
                    if (page == null) {
                        LOG.debug("Could not parse a repository list page")
                        PageOutcome.Stop(RepositoryListResult.Failed())
                    } else {
                        PageOutcome.Page(page)
                    }
                }
                401, 403 -> {
                    PageOutcome.Stop(RepositoryListResult.Unauthorized)
                }
                else -> {
                    LOG.warn("Unexpected HTTP status ${response.code} for GET $url${BitbucketHttpErrors.describe(response)}")
                    PageOutcome.Stop(RepositoryListResult.Failed())
                }
            }
        }
    }

    private fun <T> executeTrustedGet(url: String, apiToken: String, onUntrusted: () -> T, onFailure: (IOException) -> T, onResponse: (Response) -> T): T {
        if (!BitbucketApiUrlPolicy.isTrustedApiUrl(url)) {
            LOG.warn("Refusing to send the access token to an untrusted URL (${BitbucketApiUrlPolicy.describeRejected(url)})")
            return onUntrusted()
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiToken")
            .header("Accept", "application/json")
            .get()
            .build()
        LOG.debug("GET $url")
        val startNanos = System.nanoTime()
        return try {
            httpClient.newCall(request).execute().use { response ->
                LOG.debug("GET $url -> ${response.code} in ${elapsedMillis(startNanos = startNanos)} ms")
                onResponse(response)
            }
        } catch (exception: IOException) {
            LOG.debug("GET $url failed after ${elapsedMillis(startNanos = startNanos)} ms", exception)
            onFailure(exception)
        }
    }

    /**
     * Reads `links.avatar.href` from a 200 repository body, returning null when it is absent, blank, or the body does
     * not parse. A parse failure must NOT downgrade a real 200 to [RepositoryCheckResult.Failed] (that would wrongly
     * drop an existing repository), so it is logged at `debug` and treated as "no avatar".
     */
    private fun parseAvatarUrl(body: String): String? {
        return try {
            val parsed = json.decodeFromString(RepositoryResponse.serializer(), body)
            parsed.links?.avatar?.href?.takeIf { it.isNotBlank() }
        } catch (exception: Exception) {
            LOG.debug("Could not parse repository avatar href from a 200 body", exception)
            null
        }
    }

    @Serializable
    private data class RepositoryResponse(val links: Links? = null) {
        @Serializable
        data class Links(val avatar: Avatar? = null)

        @Serializable
        data class Avatar(
            @SerialName("href") val href: String? = null
        )
    }

    companion object {
        private val LOG = logger<OkHttpBitbucketRepositoryClient>()

        private const val BITBUCKET_API_BASE = "https://api.bitbucket.org/2.0"

        /** Bounds the repository listing at 5,000 repositories. */
        private const val MAX_REPOSITORY_PAGES = 50

        private val json = Json {
            ignoreUnknownKeys = true
        }

        private val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .authenticator(DiffyBearerRetryAuthenticator())
            .build()

        private fun elapsedMillis(startNanos: Long): Long {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        }
    }
}