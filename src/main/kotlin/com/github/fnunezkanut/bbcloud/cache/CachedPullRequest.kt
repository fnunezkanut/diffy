package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketAccountUuid
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import kotlin.time.Instant

/** The author of a cached pull request, or a reviewer's name and account fields. Every field is optional except the name. */
data class CachedPerson(val name: String, val uuid: String?, val accountId: String?, val avatarUrl: String?)

/** A reviewer as stored in the cache. [approved] and [needsWork] come from `ReviewerStatus`, which has three mutually exclusive values. */
data class CachedReviewer(
    val name: String,
    val uuid: String,
    val accountId: String?,
    val avatarUrl: String?,
    val approved: Boolean,
    val needsWork: Boolean,
    val participatedOn: Instant?
)

/**
 * One pull request as last seen from Bitbucket Cloud. It holds every field the plugin shows in a listing, so the Current
 * Project and Workspace tabs can render without a request. [lastFetchedAt] is the last time Bitbucket reported on this
 * pull request. Optimistic patches never move it.
 *
 * [workspace] and [repoSlug] keep their original case because the Workspace tab and the worktree folder names use
 * them. The [key] is only for lookup.
 */
data class CachedPullRequest(
    val key: PullRequestCacheKey,
    val workspace: String,
    val repoSlug: String,
    val lastFetchedAt: Instant,
    val author: CachedPerson?,
    val title: String,
    val state: PullRequestState,
    val isDraft: Boolean,
    val createdOn: Instant,
    val updatedOn: Instant,
    val closedOn: Instant?,
    val sourceBranch: String,
    val destinationBranch: String,
    val commentCount: Int,
    val webUrl: String,
    val reviewers: List<CachedReviewer>,
    val approverUuids: Set<String>
) {
    /** Whether [userUuid] approves this pull request. Computed on read from [approverUuids], so it is never persisted. */
    fun isApprovedBy(userUuid: String?): Boolean {
        return approverUuids.any { approver -> BitbucketAccountUuid.matches(first = approver, second = userUuid) }
    }
}

/**
 * The avatar URL of the author or a reviewer of [this] pull request who matches [accountId], or, when [accountId] is
 * null, [displayName] ignoring case. Null when nobody matches or the match has no avatar.
 */
fun CachedPullRequest.avatarUrlOfPerson(accountId: String?, displayName: String?): String? {
    val authorMatches =
        author?.let { person -> personMatches(personAccountId = person.accountId, personName = person.name, accountId = accountId, displayName = displayName) }
            ?: false
    if (authorMatches) {
        return author.avatarUrl
    }
    return reviewers
        .firstOrNull { reviewer ->
            personMatches(personAccountId = reviewer.accountId, personName = reviewer.name, accountId = accountId, displayName = displayName)
        }
        ?.avatarUrl
}

private fun personMatches(personAccountId: String?, personName: String, accountId: String?, displayName: String?): Boolean {
    if (!accountId.isNullOrBlank()) {
        return personAccountId == accountId
    }
    return !displayName.isNullOrBlank() && personName.equals(other = displayName, ignoreCase = true)
}

/**
 * A listing snapshot: the ordered pull requests one listing showed, and when. [isValid] is false after an invalidation.
 * A snapshot that is not valid still serves as the stale fallback when a request fails.
 */
data class ListingSnapshot(val listingId: String, val keys: List<PullRequestCacheKey>, val fetchedAt: Instant, val isTruncated: Boolean, val isValid: Boolean)

/**
 * A listing resolved from its snapshot. [isComplete] is false when a referenced entry is missing, for example after a
 * prune. Entries that are missing are left out of [entries].
 */
data class CachedListing(val entries: List<CachedPullRequest>, val fetchedAt: Instant, val isTruncated: Boolean, val isValid: Boolean, val isComplete: Boolean)