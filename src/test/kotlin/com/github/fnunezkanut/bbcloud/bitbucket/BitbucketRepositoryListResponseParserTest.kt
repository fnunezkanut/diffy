package com.github.fnunezkanut.bbcloud.bitbucket

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class BitbucketRepositoryListResponseParserTest {
    @Nested
    inner class BuildSearchRepositoriesUrlTest {
        private fun filterOf(query: String): String? {
            return BitbucketRepositoryListResponseParser
                .buildSearchRepositoriesUrl(workspace = "acme", query = query)
                .toHttpUrl()
                .queryParameter("q")
        }

        @Test
        fun `should build a single sorted page request with the contains filter`() {
            // WHEN
            val url = BitbucketRepositoryListResponseParser.buildSearchRepositoriesUrl(workspace = "acme", query = "foo").toHttpUrl()

            // THEN
            assertSoftly {
                url.host shouldBe "api.bitbucket.org"
                url.encodedPath shouldBe "/2.0/repositories/acme"
                url.queryParameter("q") shouldBe "(slug ~ \"foo\" OR name ~ \"foo\")"
                url.queryParameter("sort") shouldBe "slug"
                url.queryParameter("pagelen") shouldBe "50"
                url.queryParameter("fields")?.startsWith("next,values.slug") shouldBe true
            }
        }

        @Test
        fun `should escape quotes and backslashes`() {
            assertSoftly {
                filterOf("a\"b") shouldBe "(slug ~ \"a\\\"b\" OR name ~ \"a\\\"b\")"
                filterOf("a\\b") shouldBe "(slug ~ \"a\\\\b\" OR name ~ \"a\\\\b\")"
            }
        }

        @Test
        fun `should remove control characters and trim surrounding whitespace`() {
            assertSoftly {
                filterOf("  foo\n\tbar ") shouldBe "(slug ~ \"foobar\" OR name ~ \"foobar\")"
                filterOf("\n foo \t") shouldBe "(slug ~ \"foo\" OR name ~ \"foo\")"
            }
        }
    }

    @Test
    fun `build urls - should contain the workspace, page length, sort, and fields`() {
        // WHEN
        val listUrl = BitbucketRepositoryListResponseParser.buildListRepositoriesUrl(workspace = "acme")
        val repositoryUrl = BitbucketRepositoryListResponseParser.buildRepositoryUrl(workspace = "acme", repoSlug = "repo-a")

        // THEN
        assertSoftly {
            listUrl shouldContain "/2.0/repositories/acme?"
            listUrl shouldContain "pagelen=100"
            listUrl shouldContain "sort=slug"
            listUrl shouldContain "fields=next,values.slug"
            repositoryUrl shouldContain "/2.0/repositories/acme/repo-a?fields=slug,name,mainbranch.name"
        }
    }

    @Test
    fun `parseRepositoriesPage - should map every field and the next link`() {
        // GIVEN
        val body =
            """
            {"values":[{"slug":"repo-a","name":"Repo A","mainbranch":{"name":"main"},
              "links":{"clone":[{"name":"https","href":"https://bitbucket.org/acme/repo-a.git"},
                                {"name":"ssh","href":"git@bitbucket.org:acme/repo-a.git"}],
                       "avatar":{"href":"https://cdn.example.com/a.png"}}}],
             "next":"https://api.bitbucket.org/2.0/repositories/acme?page=2"}
            """.trimIndent()

        // WHEN
        val page = BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = body)

        // THEN
        assertSoftly {
            page?.nextUrl shouldBe "https://api.bitbucket.org/2.0/repositories/acme?page=2"
            page?.repositories shouldBe listOf(
                BitbucketWorkspaceRepository(
                    slug = "repo-a",
                    name = "Repo A",
                    mainBranch = "main",
                    httpsCloneUrl = "https://bitbucket.org/acme/repo-a.git",
                    sshCloneUrl = "git@bitbucket.org:acme/repo-a.git",
                    avatarUrl = "https://cdn.example.com/a.png"
                )
            )
        }
    }

    @Test
    fun `parseRepositoriesPage - should drop blank and invalid slugs and collapse duplicates`() {
        // GIVEN
        val body = """{"values":[{"slug":""},{"slug":"bad slug"},{"slug":"repo-a"},{"slug":"repo-a"}]}"""

        // WHEN
        val page = BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = body)

        // THEN
        page?.repositories?.map { repository -> repository.slug } shouldBe listOf("repo-a")
    }

    @Test
    fun `parseRepositoriesPage - should use nulls for missing branch and links and the slug for a blank name`() {
        // GIVEN
        val body = """{"values":[{"slug":"repo-a","name":" "}]}"""

        // WHEN
        val repository = BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = body)?.repositories?.single()

        // THEN
        assertSoftly {
            repository?.name shouldBe "repo-a"
            repository?.mainBranch shouldBe null
            repository?.sshCloneUrl shouldBe null
            repository?.httpsCloneUrl shouldBe null
            repository?.avatarUrl shouldBe null
        }
    }

    @Test
    fun `parse functions - should return null for malformed JSON`() {
        assertSoftly {
            BitbucketRepositoryListResponseParser.parseRepositoriesPage(responseBody = "not-json") shouldBe null
            BitbucketRepositoryListResponseParser.parseRepository(responseBody = "not-json") shouldBe null
        }
    }

    @Test
    fun `parseRepository - should map a single repository`() {
        // WHEN
        val repository = BitbucketRepositoryListResponseParser.parseRepository(
            responseBody = """{"slug":"repo-a","name":"A","mainbranch":{"name":"develop"}}"""
        )

        // THEN
        repository?.mainBranch shouldBe "develop"
    }
}