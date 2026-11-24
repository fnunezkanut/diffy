package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import com.github.fnunezkanut.bbcloud.validation.BitbucketRepositorySlugValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encodes one workspace repository as a compact JSON row for [DiffyWorkspaceRepositoryCacheState].
 *
 * Decoding never throws: an unparseable row, an unknown version, or an invalid slug returns null, so one corrupt row is
 * skipped rather than breaking plugin startup.
 */
object CachedWorkspaceRepositoryRow {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(repository: BitbucketWorkspaceRepository): String {
        val row = Row(
            version = VERSION,
            slug = repository.slug,
            name = repository.name,
            mainBranch = repository.mainBranch,
            httpsCloneUrl = repository.httpsCloneUrl,
            sshCloneUrl = repository.sshCloneUrl,
            avatarUrl = repository.avatarUrl
        )
        return json.encodeToString(Row.serializer(), row)
    }

    fun decode(row: String): BitbucketWorkspaceRepository? {
        val decoded = runCatching {
            json.decodeFromString(Row.serializer(), row)
        }.getOrNull() ?: return null
        if (decoded.version != VERSION || !BitbucketRepositorySlugValidator.validate(decoded.slug).isSuccess) {
            return null
        }
        return BitbucketWorkspaceRepository(
            slug = decoded.slug,
            name = decoded.name,
            mainBranch = decoded.mainBranch,
            httpsCloneUrl = decoded.httpsCloneUrl,
            sshCloneUrl = decoded.sshCloneUrl,
            avatarUrl = decoded.avatarUrl
        )
    }

    @Serializable
    private data class Row(
        @SerialName("v") val version: Int,
        val slug: String,
        val name: String,
        val mainBranch: String? = null,
        val httpsCloneUrl: String? = null,
        val sshCloneUrl: String? = null,
        val avatarUrl: String? = null
    )
}