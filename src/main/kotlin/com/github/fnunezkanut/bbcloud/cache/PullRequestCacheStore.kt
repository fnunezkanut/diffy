package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.CurrentUser
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The operations the Bitbucket services use to read and write the pull request cache. Services receive it through their
 * constructors, so unit tests inject [NoOpPullRequestCacheStore] or an in-memory [DiffyPullRequestCache].
 *
 * Every method may be called from a coroutine on `Dispatchers.IO`. None may be called on the event dispatch thread.
 * Methods that take a workspace and a repository slug accept them in any case.
 */
interface PullRequestCacheStore {
    /** Caches the summaries of one repository's listing, keeping their order out of band. Does not touch the listing snapshot. */
    fun upsertAll(workspace: String, repoSlug: String, summaries: List<PullRequestSummary>)

    /** Caches listings that already pair each summary with its repository. */
    fun upsertAll(pullRequests: List<ReviewingPullRequest>)

    fun upsert(workspace: String, repoSlug: String, summary: PullRequestSummary)

    /** Caches a details response. Reviewers and approvers come from `details.summary`. */
    fun upsertDetails(workspace: String, repoSlug: String, details: PullRequestDetails)

    fun get(key: PullRequestCacheKey): CachedPullRequest?

    /** Time since Bitbucket last reported this pull request, or null when unknown or in the future. */
    fun ageOf(key: PullRequestCacheKey): Duration?

    fun lastFetchedAt(key: PullRequestCacheKey): Instant?

    /** Changes an entry in place without moving its [CachedPullRequest.lastFetchedAt]. Does nothing when the entry is absent. */
    fun patch(key: PullRequestCacheKey, transform: (CachedPullRequest) -> CachedPullRequest)

    /** Replaces the snapshot of [listingId] with [keys] in the order Bitbucket returned them. */
    fun recordListing(listingId: String, keys: List<PullRequestCacheKey>, isTruncated: Boolean)

    /** The listing as last recorded, including invalid snapshots. Used as the stale fallback. */
    fun listing(listingId: String): CachedListing?

    /** The listing when it is valid, complete, and younger than the freshness TTL. Null otherwise. */
    fun freshListing(listingId: String): CachedListing?

    fun invalidateListing(listingId: String)

    /** Marks every listing that contains [key] as invalid, so the next automatic load asks Bitbucket. */
    fun invalidateListingsContaining(key: PullRequestCacheKey)

    /** Removes [key] from the reviewing and authored snapshots. The entry itself stays cached. */
    fun removeFromOpenListings(key: PullRequestCacheKey)

    /** Drops the project and reviewing snapshots of one repository. The entries stay cached. */
    fun dropListingsForRepo(workspace: String, repoSlug: String)

    fun entriesForRepo(workspace: String, repoSlug: String, includeClosed: Boolean): List<CachedPullRequest>

    fun prune()

    /** Empties the entries, the listings, and the owner. Also forgets the in-memory current user. */
    fun clear()

    /**
     * Records which Bitbucket account the cache belongs to. A change clears the cache, but only after two calls in a row
     * report the same new account, because the OAuth and API-token paths can belong to different accounts.
     */
    fun bindUser(userUuid: String?)

    /** The current user last read with [tokenHash], or null. In memory only. */
    fun cachedCurrentUser(tokenHash: Int): CurrentUser?

    fun rememberCurrentUser(tokenHash: Int, user: CurrentUser)
}

/** Stores nothing. Reads miss and writes do nothing. Used by constructors that do not get a real cache. */
object NoOpPullRequestCacheStore : PullRequestCacheStore {
    override fun upsertAll(workspace: String, repoSlug: String, summaries: List<PullRequestSummary>) {
    }

    override fun upsertAll(pullRequests: List<ReviewingPullRequest>) {
    }

    override fun upsert(workspace: String, repoSlug: String, summary: PullRequestSummary) {
    }

    override fun upsertDetails(workspace: String, repoSlug: String, details: PullRequestDetails) {
    }

    override fun get(key: PullRequestCacheKey): CachedPullRequest? {
        return null
    }

    override fun ageOf(key: PullRequestCacheKey): Duration? {
        return null
    }

    override fun lastFetchedAt(key: PullRequestCacheKey): Instant? {
        return null
    }

    override fun patch(key: PullRequestCacheKey, transform: (CachedPullRequest) -> CachedPullRequest) {
    }

    override fun recordListing(listingId: String, keys: List<PullRequestCacheKey>, isTruncated: Boolean) {
    }

    override fun listing(listingId: String): CachedListing? {
        return null
    }

    override fun freshListing(listingId: String): CachedListing? {
        return null
    }

    override fun invalidateListing(listingId: String) {
    }

    override fun invalidateListingsContaining(key: PullRequestCacheKey) {
    }

    override fun removeFromOpenListings(key: PullRequestCacheKey) {
    }

    override fun dropListingsForRepo(workspace: String, repoSlug: String) {
    }

    override fun entriesForRepo(workspace: String, repoSlug: String, includeClosed: Boolean): List<CachedPullRequest> {
        return emptyList()
    }

    override fun prune() {
    }

    override fun clear() {
    }

    override fun bindUser(userUuid: String?) {
    }

    override fun cachedCurrentUser(tokenHash: Int): CurrentUser? {
        return null
    }

    override fun rememberCurrentUser(tokenHash: Int, user: CurrentUser) {
    }
}