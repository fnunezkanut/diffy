package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.validation.BitbucketRepositorySlugValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds the repository list and lookup URLs and parses their responses. Pure, no IO. */
object BitbucketRepositoryListResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun buildListRepositoriesUrl(workspace: String): String {
        return "$BITBUCKET_API_BASE/repositories/$workspace?pagelen=$PAGE_LEN&sort=slug&fields=$LIST_FIELDS"
    }

    /**
     * Builds the single-page search URL for [workspace]. The BBQL filter is added through `addQueryParameter`, so
     * OkHttp encodes it.
     */
    fun buildSearchRepositoriesUrl(workspace: String, query: String): String {
        return "$BITBUCKET_API_BASE/repositories".toHttpUrl()
            .newBuilder()
            .addPathSegment(workspace)
            .addQueryParameter(name = "q", value = bbqlContainsFilter(query = query))
            .addQueryParameter(name = "sort", value = "slug")
            .addQueryParameter(name = "pagelen", value = SEARCH_PAGE_LEN.toString())
            .addQueryParameter(name = "fields", value = LIST_FIELDS)
            .build()
            .toString()
    }

    /**
     * Builds the BBQL expression that matches repositories whose slug or name contains [query]. The text is trimmed,
     * stripped of control characters, and escaped for a double-quoted BBQL string.
     */
    internal fun bbqlContainsFilter(query: String): String {
        val escaped = query
            .trim()
            .filterNot { character -> character.isISOControl() }
            .replace(oldValue = "\\", newValue = "\\\\")
            .replace(oldValue = "\"", newValue = "\\\"")
        return "(slug ~ \"$escaped\" OR name ~ \"$escaped\")"
    }

    fun buildRepositoryUrl(workspace: String, repoSlug: String): String {
        return "$BITBUCKET_API_BASE/repositories/$workspace/$repoSlug?fields=$REPOSITORY_FIELDS"
    }

    /** Returns null when the body does not decode, so an unreadable page never looks like an empty workspace. */
    fun parseRepositoriesPage(responseBody: String): WorkspaceRepositoryPage? {
        val page = runCatching {
            json.decodeFromString(RepositoriesResponse.serializer(), responseBody)
        }.getOrNull() ?: return null
        val repositories = page.values
            .mapNotNull { dto -> dto.toRepository() }
            .distinctBy { repository -> repository.slug }
        return WorkspaceRepositoryPage(repositories = repositories, nextUrl = page.next?.takeIf { next -> next.isNotBlank() })
    }

    fun parseRepository(responseBody: String): BitbucketWorkspaceRepository? {
        val dto = runCatching {
            json.decodeFromString(RepositoryDto.serializer(), responseBody)
        }.getOrNull() ?: return null
        return dto.toRepository()
    }

    private fun RepositoryDto.toRepository(): BitbucketWorkspaceRepository? {
        if (slug.isBlank() || !BitbucketRepositorySlugValidator.validate(slug).isSuccess) {
            return null
        }
        val cloneLinks = links?.clone.orEmpty()
        return BitbucketWorkspaceRepository(
            slug = slug,
            name = name.ifBlank { slug },
            mainBranch = mainbranch?.name?.trim()?.takeIf { branch -> branch.isNotEmpty() },
            httpsCloneUrl = cloneHref(links = cloneLinks, linkName = "https"),
            sshCloneUrl = cloneHref(links = cloneLinks, linkName = "ssh"),
            avatarUrl = links?.avatar?.href?.takeIf { href -> href.isNotBlank() }
        )
    }

    private fun cloneHref(links: List<CloneLinkDto>, linkName: String): String? {
        return links
            .firstOrNull { link -> link.name.equals(other = linkName, ignoreCase = true) }
            ?.href
            ?.takeIf { href -> href.isNotBlank() }
    }

    @Serializable
    private data class RepositoriesResponse(val values: List<RepositoryDto> = emptyList(), val next: String? = null)

    @Serializable
    private data class RepositoryDto(val slug: String = "", val name: String = "", val mainbranch: BranchDto? = null, val links: LinksDto? = null)

    @Serializable
    private data class BranchDto(val name: String? = null)

    @Serializable
    private data class LinksDto(val clone: List<CloneLinkDto> = emptyList(), val avatar: HrefDto? = null)

    @Serializable
    private data class CloneLinkDto(val name: String? = null, val href: String? = null)

    @Serializable
    private data class HrefDto(val href: String? = null)

    private const val BITBUCKET_API_BASE = "https://api.bitbucket.org/2.0"
    private const val PAGE_LEN = 100
    private const val SEARCH_PAGE_LEN = 50

    /** The fewest trimmed characters that start a repository search. */
    const val MIN_SEARCH_QUERY_LENGTH = 2
    private const val REPOSITORY_FIELDS = "slug,name,mainbranch.name,links.clone.name,links.clone.href,links.avatar.href"
    private const val LIST_FIELDS =
        "next,values.slug,values.name,values.mainbranch.name,values.links.clone.name,values.links.clone.href,values.links.avatar.href"
}