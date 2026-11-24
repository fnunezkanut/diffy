package com.github.fnunezkanut.bbcloud.bitbucket

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException

@Tag("unit")
class OkHttpBitbucketRepositoryListClientTest {
    @Nested
    inner class ListRepositoriesTest {
        @Test
        fun `should concatenate two pages and send the bearer header on both`(): Unit =
            runBlocking {
                // GIVEN
                val requests = mutableListOf<Request>()
                val next = "https://api.bitbucket.org/2.0/repositories/acme?page=2"
                val client = clientServing(
                    captured = requests,
                    responses = mapOf(
                        "page=2" to (200 to """{"values":[{"slug":"repo-b"}]}"""),
                        "" to (200 to """{"values":[{"slug":"repo-a"}],"next":"$next"}""")
                    )
                )

                // WHEN
                val result = client.listRepositories(workspace = "acme", apiToken = "token")

                // THEN
                assertSoftly {
                    result.shouldBeInstanceOf<RepositoryListResult.Success>().repositories.map { repository -> repository.slug } shouldBe
                        listOf("repo-a", "repo-b")
                    result.shouldBeInstanceOf<RepositoryListResult.Success>().isTruncated shouldBe false
                    requests.map { request -> request.header("Authorization") } shouldBe listOf("Bearer token", "Bearer token")
                }
            }

        @Test
        fun `should map 401 and 403 to Unauthorized`(): Unit =
            runBlocking {
                assertSoftly {
                    clientReturning(code = 401, body = "").listRepositories(workspace = "acme", apiToken = "t") shouldBe RepositoryListResult.Unauthorized
                    clientReturning(code = 403, body = "").listRepositories(workspace = "acme", apiToken = "t") shouldBe RepositoryListResult.Unauthorized
                }
            }

        @Test
        fun `should map 500, unparseable 200, and io errors to Failed`(): Unit =
            runBlocking {
                // GIVEN
                val failing = OkHttpBitbucketRepositoryClient(
                    OkHttpClient.Builder().addInterceptor { _ -> throw IOException("boom") }.build()
                )

                // THEN
                assertSoftly {
                    clientReturning(code = 500, body = "").listRepositories(workspace = "acme", apiToken = "t")
                        .shouldBeInstanceOf<RepositoryListResult.Failed>()
                    clientReturning(code = 200, body = "not-json").listRepositories(workspace = "acme", apiToken = "t")
                        .shouldBeInstanceOf<RepositoryListResult.Failed>()
                    failing.listRepositories(workspace = "acme", apiToken = "t").shouldBeInstanceOf<RepositoryListResult.Failed>().cause
                        .shouldBeInstanceOf<IOException>()
                }
            }

        @Test
        fun `should not follow an untrusted next link`(): Unit =
            runBlocking {
                // GIVEN
                val requests = mutableListOf<Request>()
                val client = clientServing(
                    captured = requests,
                    responses = mapOf("" to (200 to """{"values":[{"slug":"repo-a"}],"next":"https://evil.example.com/steal"}"""))
                )

                // WHEN
                val result = client.listRepositories(workspace = "acme", apiToken = "token")

                // THEN
                assertSoftly {
                    result.shouldBeInstanceOf<RepositoryListResult.Failed>()
                    requests.size shouldBe 1
                }
            }

        @Test
        fun `should flag truncation when the page cap is reached`(): Unit =
            runBlocking {
                // GIVEN a listing that never ends
                var page = 0
                val interceptor = Interceptor { chain ->
                    page++
                    respond(
                        request = chain.request(),
                        code = 200,
                        body = """{"values":[{"slug":"repo-$page"}],"next":"https://api.bitbucket.org/2.0/repositories/acme?page=${page + 1}"}"""
                    )
                }
                val client = OkHttpBitbucketRepositoryClient(OkHttpClient.Builder().addInterceptor(interceptor).build())

                // WHEN
                val result = client.listRepositories(workspace = "acme", apiToken = "token")

                // THEN
                assertSoftly {
                    result.shouldBeInstanceOf<RepositoryListResult.Success>().isTruncated shouldBe true
                    result.shouldBeInstanceOf<RepositoryListResult.Success>().repositories.size shouldBe 50
                }
            }
    }

    @Nested
    inner class FetchRepositoryTest {
        @Test
        fun `should return Found with the mapped repository on 200`(): Unit =
            runBlocking {
                // GIVEN
                val client = clientReturning(code = 200, body = """{"slug":"repo-a","name":"A","mainbranch":{"name":"main"}}""")

                // WHEN
                val result = client.fetchRepository(workspace = "acme", repoSlug = "repo-a", apiToken = "t")

                // THEN
                result.shouldBeInstanceOf<RepositoryLookupResult.Found>().repository.mainBranch shouldBe "main"
            }

        @Test
        fun `should map 404, 401, and 500`(): Unit =
            runBlocking {
                assertSoftly {
                    clientReturning(code = 404, body = "").fetchRepository(workspace = "acme", repoSlug = "r", apiToken = "t") shouldBe
                        RepositoryLookupResult.NotFound
                    clientReturning(code = 401, body = "").fetchRepository(workspace = "acme", repoSlug = "r", apiToken = "t") shouldBe
                        RepositoryLookupResult.Unauthorized
                    clientReturning(code = 500, body = "").fetchRepository(workspace = "acme", repoSlug = "r", apiToken = "t")
                        .shouldBeInstanceOf<RepositoryLookupResult.Failed>()
                }
            }
    }

    @Nested
    inner class SearchRepositoriesTest {
        @Test
        fun `should report hasMore when a next link exists, without following it`(): Unit =
            runBlocking {
                // GIVEN
                val requests = mutableListOf<Request>()
                val next = "https://api.bitbucket.org/2.0/repositories/acme?page=2"
                val client = clientServing(
                    captured = requests,
                    responses = mapOf("" to (200 to """{"values":[{"slug":"repo-a"}],"next":"$next"}"""))
                )

                // WHEN
                val result = client.searchRepositories(workspace = "acme", query = "repo", apiToken = "token")

                // THEN
                assertSoftly {
                    result.shouldBeInstanceOf<RepositorySearchResult.Success>().hasMore shouldBe true
                    result.shouldBeInstanceOf<RepositorySearchResult.Success>().repositories.map { repository -> repository.slug } shouldBe
                        listOf("repo-a")
                    requests.size shouldBe 1
                    requests.single().header("Authorization") shouldBe "Bearer token"
                }
            }

        @Test
        fun `should report no more matches when there is no next link`(): Unit =
            runBlocking {
                // GIVEN
                val client = clientReturning(code = 200, body = """{"values":[{"slug":"repo-a"}]}""")

                // WHEN
                val result = client.searchRepositories(workspace = "acme", query = "repo", apiToken = "token")

                // THEN
                result.shouldBeInstanceOf<RepositorySearchResult.Success>().hasMore shouldBe false
            }

        @Test
        fun `should map 401 and 403 to Unauthorized`(): Unit =
            runBlocking {
                assertSoftly {
                    clientReturning(code = 401, body = "").searchRepositories(workspace = "acme", query = "ab", apiToken = "t") shouldBe
                        RepositorySearchResult.Unauthorized
                    clientReturning(code = 403, body = "").searchRepositories(workspace = "acme", query = "ab", apiToken = "t") shouldBe
                        RepositorySearchResult.Unauthorized
                }
            }

        @Test
        fun `should map 400, 500, unparseable 200, and io errors to Failed`(): Unit =
            runBlocking {
                // GIVEN
                val failing = OkHttpBitbucketRepositoryClient(
                    OkHttpClient.Builder().addInterceptor { _ -> throw IOException("boom") }.build()
                )

                // THEN
                assertSoftly {
                    clientReturning(code = 400, body = "").searchRepositories(workspace = "acme", query = "ab", apiToken = "t")
                        .shouldBeInstanceOf<RepositorySearchResult.Failed>()
                    clientReturning(code = 500, body = "").searchRepositories(workspace = "acme", query = "ab", apiToken = "t")
                        .shouldBeInstanceOf<RepositorySearchResult.Failed>()
                    clientReturning(code = 200, body = "not-json").searchRepositories(workspace = "acme", query = "ab", apiToken = "t")
                        .shouldBeInstanceOf<RepositorySearchResult.Failed>()
                    failing.searchRepositories(workspace = "acme", query = "ab", apiToken = "t")
                        .shouldBeInstanceOf<RepositorySearchResult.Failed>().cause
                        .shouldBeInstanceOf<IOException>()
                }
            }
    }

    private fun respond(request: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun clientReturning(code: Int, body: String): OkHttpBitbucketRepositoryClient {
        val interceptor = Interceptor { chain -> respond(request = chain.request(), code = code, body = body) }
        return OkHttpBitbucketRepositoryClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    /** Serves the first response whose key is contained in the request URL; the empty key is the fallback. */
    private fun clientServing(captured: MutableList<Request>, responses: Map<String, Pair<Int, String>>): OkHttpBitbucketRepositoryClient {
        val interceptor = Interceptor { chain ->
            captured.add(chain.request())
            val url = chain.request().url.toString()
            val match = responses.entries.first { entry -> entry.key.isEmpty() || url.contains(entry.key) }
            respond(request = chain.request(), code = match.value.first, body = match.value.second)
        }
        return OkHttpBitbucketRepositoryClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }
}