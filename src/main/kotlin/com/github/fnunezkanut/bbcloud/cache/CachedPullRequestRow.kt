package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Encodes cache entries and listing snapshots as compact JSON strings for [DiffyPullRequestCacheState].
 *
 * Every row carries a schema version `v`. Decoding never throws: a row that fails to parse, has an unknown version, or
 * names an unknown state returns null, so one corrupt row is skipped rather than breaking plugin startup.
 */
object CachedPullRequestRow {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encodeEntry(entry: CachedPullRequest): String {
        val row = EntryRow(
            version = VERSION,
            workspace = entry.workspace,
            repoSlug = entry.repoSlug,
            prNum = entry.key.prNum,
            lastFetchedAt = entry.lastFetchedAt.toString(),
            author = entry.author?.let { person -> PersonRow.of(person = person) },
            title = entry.title,
            state = entry.state.name,
            isDraft = entry.isDraft,
            createdOn = entry.createdOn.toString(),
            updatedOn = entry.updatedOn.toString(),
            closedOn = entry.closedOn?.toString(),
            sourceBranch = entry.sourceBranch,
            destinationBranch = entry.destinationBranch,
            commentCount = entry.commentCount,
            webUrl = entry.webUrl,
            reviewers = entry.reviewers.map { reviewer -> ReviewerRow.of(reviewer = reviewer) },
            approverUuids = entry.approverUuids.sorted()
        )
        return json.encodeToString(EntryRow.serializer(), row)
    }

    /** Returns null for a row that cannot be decoded. */
    fun decodeEntry(encoded: String): CachedPullRequest? {
        return runCatching {
            val row = json.decodeFromString(EntryRow.serializer(), encoded)
            if (row.version != VERSION) {
                return null
            }
            val key = PullRequestCacheKey.of(workspace = row.workspace, repoSlug = row.repoSlug, prNum = row.prNum)
            CachedPullRequest(
                key = key,
                workspace = row.workspace,
                repoSlug = row.repoSlug,
                lastFetchedAt = Instant.parse(row.lastFetchedAt),
                author = row.author?.toPerson(),
                title = row.title,
                state = PullRequestState.valueOf(row.state),
                isDraft = row.isDraft,
                createdOn = Instant.parse(row.createdOn),
                updatedOn = Instant.parse(row.updatedOn),
                closedOn = row.closedOn?.let { value -> Instant.parse(value) },
                sourceBranch = row.sourceBranch,
                destinationBranch = row.destinationBranch,
                commentCount = row.commentCount,
                webUrl = row.webUrl,
                reviewers = row.reviewers.map { reviewer -> reviewer.toReviewer() },
                approverUuids = row.approverUuids.toSet()
            )
        }.getOrNull()
    }

    fun encodeListing(snapshot: ListingSnapshot): String {
        val row = ListingRow(
            version = VERSION,
            listingId = snapshot.listingId,
            fetchedAt = snapshot.fetchedAt.toString(),
            isTruncated = snapshot.isTruncated,
            isValid = snapshot.isValid,
            keys = snapshot.keys.map { key -> key.encode() }
        )
        return json.encodeToString(ListingRow.serializer(), row)
    }

    /** Returns null for a row that cannot be decoded. Keys that fail to parse are left out of the snapshot. */
    fun decodeListing(encoded: String): ListingSnapshot? {
        return runCatching {
            val row = json.decodeFromString(ListingRow.serializer(), encoded)
            if (row.version != VERSION) {
                return null
            }
            ListingSnapshot(
                listingId = row.listingId,
                keys = row.keys.mapNotNull { key -> PullRequestCacheKey.decode(encoded = key) },
                fetchedAt = Instant.parse(row.fetchedAt),
                isTruncated = row.isTruncated,
                isValid = row.isValid
            )
        }.getOrNull()
    }

    @Serializable
    private data class EntryRow(
        @SerialName("v") val version: Int,
        @SerialName("w") val workspace: String,
        @SerialName("r") val repoSlug: String,
        @SerialName("n") val prNum: Long,
        @SerialName("f") val lastFetchedAt: String,
        @SerialName("a") val author: PersonRow? = null,
        @SerialName("t") val title: String,
        @SerialName("s") val state: String,
        @SerialName("d") val isDraft: Boolean = false,
        @SerialName("c") val createdOn: String,
        @SerialName("u") val updatedOn: String,
        @SerialName("x") val closedOn: String? = null,
        @SerialName("sb") val sourceBranch: String,
        @SerialName("db") val destinationBranch: String,
        @SerialName("cc") val commentCount: Int = 0,
        @SerialName("url") val webUrl: String,
        @SerialName("rv") val reviewers: List<ReviewerRow> = emptyList(),
        @SerialName("ap") val approverUuids: List<String> = emptyList()
    )

    @Serializable
    private data class PersonRow(
        @SerialName("nm") val name: String,
        @SerialName("uid") val uuid: String? = null,
        @SerialName("acc") val accountId: String? = null,
        @SerialName("av") val avatarUrl: String? = null
    ) {
        fun toPerson(): CachedPerson {
            return CachedPerson(name = name, uuid = uuid, accountId = accountId, avatarUrl = avatarUrl)
        }

        companion object {
            fun of(person: CachedPerson): PersonRow {
                return PersonRow(
                    name = person.name,
                    uuid = person.uuid,
                    accountId = person.accountId,
                    avatarUrl = person.avatarUrl
                )
            }
        }
    }

    @Serializable
    private data class ReviewerRow(
        @SerialName("nm") val name: String,
        @SerialName("uid") val uuid: String,
        @SerialName("acc") val accountId: String? = null,
        @SerialName("av") val avatarUrl: String? = null,
        @SerialName("ok") val approved: Boolean = false,
        @SerialName("nw") val needsWork: Boolean = false,
        @SerialName("p") val participatedOn: String? = null
    ) {
        fun toReviewer(): CachedReviewer {
            return CachedReviewer(
                name = name,
                uuid = uuid,
                accountId = accountId,
                avatarUrl = avatarUrl,
                approved = approved,
                needsWork = needsWork,
                participatedOn = participatedOn?.let { value -> Instant.parse(value) }
            )
        }

        companion object {
            fun of(reviewer: CachedReviewer): ReviewerRow {
                return ReviewerRow(
                    name = reviewer.name,
                    uuid = reviewer.uuid,
                    accountId = reviewer.accountId,
                    avatarUrl = reviewer.avatarUrl,
                    approved = reviewer.approved,
                    needsWork = reviewer.needsWork,
                    participatedOn = reviewer.participatedOn?.toString()
                )
            }
        }
    }

    @Serializable
    private data class ListingRow(
        @SerialName("v") val version: Int,
        @SerialName("id") val listingId: String,
        @SerialName("f") val fetchedAt: String,
        @SerialName("t") val isTruncated: Boolean = false,
        @SerialName("ok") val isValid: Boolean = true,
        @SerialName("k") val keys: List<String> = emptyList()
    )
}