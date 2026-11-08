package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketAccountUuid
import com.github.fnunezkanut.bbcloud.bitbucket.CurrentUser
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import com.github.fnunezkanut.bbcloud.external.DiffyNotificationsStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The application-level cache of Bitbucket pull request metadata. It keeps one [CachedPullRequest] per pull request and
 * one [ListingSnapshot] per listing, and writes both through to [DiffyPullRequestCacheState] after every change.
 *
 * Every public method takes one lock, so the Current Project and Workspace tabs of several IDE windows can share one
 * instance. Call it only from a background thread (`Dispatchers.IO`). The JSON encoding of up to [PullRequestCacheLimits.maxEntries]
 * rows is too slow for the event dispatch thread. Callers read watched repository slugs before they call in, because
 * settings access must not run under this lock.
 *
 * Clearing the cache also clears the stored notifications ([onCleared]), because both belong to one account.
 *
 * Constructor parameters have defaults so that IntelliJ can create the service and unit tests can inject a fake state, a
 * fake clock, and small limits.
 */
@Service(Service.Level.APP)
class DiffyPullRequestCache(
    private val store: () -> DiffyPullRequestCacheState.State = { DiffyPullRequestCacheState.getInstance().state },
    private val clock: () -> Instant = { Clock.System.now() },
    private val limits: PullRequestCacheLimits = PullRequestCacheLimits(),
    /** Runs after the cache was emptied by [clear] or an account change; production forgets the stored notifications. */
    private val onCleared: () -> Unit = { DiffyNotificationsStore.clearInBackground() }
) : PullRequestCacheStore {
    private val lock = Any()

    /** Entries by [PullRequestCacheKey.encode]. Insertion order is not relied upon. */
    private val pullRequests = LinkedHashMap<String, CachedPullRequest>()

    /** Listing snapshots by listing id. */
    private val snapshots = LinkedHashMap<String, ListingSnapshot>()

    private var loaded = false

    /** An owner change waits for a second call with the same uuid before the cache is cleared. */
    private var pendingOwnerUuid: String? = null

    private var rememberedUser: RememberedUser? = null

    private data class RememberedUser(val tokenHash: Int, val user: CurrentUser)

    override fun upsertAll(workspace: String, repoSlug: String, summaries: List<PullRequestSummary>) {
        mutate { now ->
            summaries.forEach { summary -> upsertSummary(workspace = workspace, repoSlug = repoSlug, summary = summary, now = now) }
            LOG.debug("Cached ${summaries.size} pull request(s) for $workspace/$repoSlug")
        }
    }

    override fun upsertAll(pullRequests: List<ReviewingPullRequest>) {
        mutate { now ->
            pullRequests.forEach { pullRequest ->
                upsertSummary(
                    workspace = pullRequest.workspace,
                    repoSlug = pullRequest.repoSlug,
                    summary = pullRequest.summary,
                    now = now
                )
            }
            LOG.debug("Cached ${pullRequests.size} pull request(s) from a workspace listing")
        }
    }

    override fun upsert(workspace: String, repoSlug: String, summary: PullRequestSummary) {
        mutate { now -> upsertSummary(workspace = workspace, repoSlug = repoSlug, summary = summary, now = now) }
    }

    override fun upsertDetails(workspace: String, repoSlug: String, details: PullRequestDetails) {
        mutate { now ->
            upsertSummary(workspace = workspace, repoSlug = repoSlug, summary = details.summary, now = now)
        }
    }

    override fun get(key: PullRequestCacheKey): CachedPullRequest? {
        return read { pullRequests[key.encode()] }
    }

    override fun ageOf(key: PullRequestCacheKey): Duration? {
        return read { now ->
            pullRequests[key.encode()]?.let { entry -> ageSince(instant = entry.lastFetchedAt, now = now) }
        }
    }

    override fun lastFetchedAt(key: PullRequestCacheKey): Instant? {
        return read { pullRequests[key.encode()]?.lastFetchedAt }
    }

    override fun patch(key: PullRequestCacheKey, transform: (CachedPullRequest) -> CachedPullRequest) {
        mutate {
            val existing = pullRequests[key.encode()] ?: return@mutate
            // An optimistic patch is not a Bitbucket read, so it must not move the fetch time.
            pullRequests[key.encode()] = transform(existing).copy(lastFetchedAt = existing.lastFetchedAt)
        }
    }

    override fun recordListing(listingId: String, keys: List<PullRequestCacheKey>, isTruncated: Boolean) {
        mutate { now ->
            snapshots[listingId] = ListingSnapshot(
                listingId = listingId,
                keys = keys.distinct(),
                fetchedAt = now,
                isTruncated = isTruncated,
                isValid = true
            )
        }
    }

    override fun listing(listingId: String): CachedListing? {
        return read { _ -> snapshots[listingId]?.let { snapshot -> resolve(snapshot = snapshot) } }
    }

    override fun freshListing(listingId: String): CachedListing? {
        return read { now ->
            val listing = snapshots[listingId]?.let { snapshot -> resolve(snapshot = snapshot) } ?: return@read null
            val isFresh = listing.isValid && listing.isComplete && isWithinFreshTtl(fetchedAt = listing.fetchedAt, now = now)
            if (isFresh) listing else null
        }
    }

    override fun invalidateListing(listingId: String) {
        mutate {
            val snapshot = snapshots[listingId] ?: return@mutate
            snapshots[listingId] = snapshot.copy(isValid = false)
        }
    }

    override fun invalidateListingsContaining(key: PullRequestCacheKey) {
        mutate {
            for (listingId in snapshots.keys.toList()) {
                val snapshot = snapshots[listingId] ?: continue
                if (key in snapshot.keys) {
                    snapshots[listingId] = snapshot.copy(isValid = false)
                }
            }
        }
    }

    override fun removeFromOpenListings(key: PullRequestCacheKey) {
        mutate {
            for (listingId in snapshots.keys.toList()) {
                if (!isOpenListing(listingId = listingId)) {
                    continue
                }
                val snapshot = snapshots[listingId] ?: continue
                if (key in snapshot.keys) {
                    snapshots[listingId] = snapshot.copy(keys = snapshot.keys.filterNot { listed -> listed == key })
                }
            }
        }
    }

    override fun dropListingsForRepo(workspace: String, repoSlug: String) {
        mutate {
            snapshots.remove(PullRequestListingIds.project(workspace = workspace, repoSlug = repoSlug))
            snapshots.remove(PullRequestListingIds.workspaceReviewing(workspace = workspace, repoSlug = repoSlug))
        }
    }

    override fun entriesForRepo(workspace: String, repoSlug: String, includeClosed: Boolean): List<CachedPullRequest> {
        val normalizedWorkspace = workspace.trim().lowercase()
        val normalizedRepo = repoSlug.trim().lowercase()
        return read {
            pullRequests.values.filter { entry ->
                entry.key.workspace == normalizedWorkspace &&
                    entry.key.repoSlug == normalizedRepo &&
                    (includeClosed || entry.state == PullRequestState.Open)
            }
        }
    }

    override fun prune() {
        mutate { }
    }

    override fun clear() {
        mutate {
            clearLocked()
            store().ownerUserUuid = ""
            onCleared()
        }
    }

    override fun bindUser(userUuid: String?) {
        val incoming = userUuid?.takeIf { it.isNotBlank() } ?: return
        // Called on every current-user lookup, so it writes only when an owner change clears the cache.
        synchronized(lock) {
            ensureLoaded(now = clock())
            if (applyOwner(incoming = incoming)) {
                writeThrough()
            }
        }
    }

    override fun cachedCurrentUser(tokenHash: Int): CurrentUser? {
        synchronized(lock) {
            return rememberedUser?.takeIf { remembered -> remembered.tokenHash == tokenHash }?.user
        }
    }

    override fun rememberCurrentUser(tokenHash: Int, user: CurrentUser) {
        synchronized(lock) {
            rememberedUser = RememberedUser(tokenHash = tokenHash, user = user)
        }
    }

    /** Runs [block] with the current time and the loaded state, without writing anything back. */
    private fun <T> read(block: (Instant) -> T): T {
        synchronized(lock) {
            val now = clock()
            ensureLoaded(now = now)
            return block(now)
        }
    }

    /** Runs [block], prunes, then writes the whole cache once. */
    private fun <T> mutate(block: (Instant) -> T): T {
        synchronized(lock) {
            val now = clock()
            ensureLoaded(now = now)
            val result = block(now)
            pruneLocked(now = now)
            writeThrough()
            return result
        }
    }

    private fun ensureLoaded(now: Instant) {
        if (loaded) {
            return
        }
        loaded = true
        val state = store()
        var unreadableRows = 0
        for (row in state.entries) {
            val entry = CachedPullRequestRow.decodeEntry(encoded = row)
            if (entry == null) {
                unreadableRows++
            } else {
                pullRequests[entry.key.encode()] = entry
            }
        }
        for (row in state.listings) {
            val snapshot = CachedPullRequestRow.decodeListing(encoded = row)
            if (snapshot == null) {
                unreadableRows++
            } else {
                snapshots[snapshot.listingId] = snapshot
            }
        }
        if (unreadableRows > 0) {
            LOG.debug("Skipped $unreadableRows unreadable pull request cache row(s)")
        }
        LOG.debug("Loaded ${pullRequests.size} cached pull request(s) and ${snapshots.size} listing snapshot(s)")
        pruneLocked(now = now)
    }

    private fun writeThrough() {
        val state = store()
        val encodedEntries = pullRequests.values.map { entry -> CachedPullRequestRow.encodeEntry(entry = entry) }
        val encodedListings = snapshots.values.map { snapshot -> CachedPullRequestRow.encodeListing(snapshot = snapshot) }
        state.entries.clear()
        state.entries.addAll(encodedEntries)
        state.listings.clear()
        state.listings.addAll(encodedListings)
    }

    private fun upsertSummary(workspace: String, repoSlug: String, summary: PullRequestSummary, now: Instant) {
        val key = PullRequestCacheKey.of(workspace = workspace, repoSlug = repoSlug, prNum = summary.id)
        val existing = pullRequests[key.encode()]
        val originalWorkspace = workspace.takeIf { it.isNotBlank() } ?: existing?.workspace ?: workspace
        val originalRepoSlug = repoSlug.takeIf { it.isNotBlank() } ?: existing?.repoSlug ?: repoSlug
        var entry = CachedPullRequestMapper.fromSummary(
            workspace = originalWorkspace,
            repoSlug = originalRepoSlug,
            summary = summary,
            fetchedAt = now,
            closedOn = closedOnFor(existing = existing, summary = summary)
        )
        if (existing != null && isPartialListing(incoming = summary, existing = existing)) {
            entry = entry.copy(reviewers = existing.reviewers, approverUuids = existing.approverUuids)
        }
        pullRequests[key.encode()] = entry
    }

    /**
     * The closing time of a closed pull request. Bitbucket does not return a reliable close time in the fields the plugin
     * reads, so the first `updated_on` seen while closed is kept. Re-opening clears it.
     */
    private fun closedOnFor(existing: CachedPullRequest?, summary: PullRequestSummary): Instant? {
        if (summary.state == PullRequestState.Open) {
            return null
        }
        if (existing != null && existing.state != PullRequestState.Open && existing.closedOn != null) {
            return existing.closedOn
        }
        return summary.updatedOn
    }

    /**
     * A listing that omits participants carries no reviewers or approvers. Keeping the stored ones avoids erasing them,
     * as long as the pull request has not changed since.
     */
    private fun isPartialListing(incoming: PullRequestSummary, existing: CachedPullRequest): Boolean {
        val incomingHasNoParticipants = incoming.reviewers.isEmpty() && incoming.approverUuids.isEmpty()
        val existingHasParticipants = existing.reviewers.isNotEmpty() || existing.approverUuids.isNotEmpty()
        return incomingHasNoParticipants && existingHasParticipants && existing.updatedOn == incoming.updatedOn
    }

    /** Returns true when the owner change cleared the cache, so the caller must write the empty state. */
    private fun applyOwner(incoming: String): Boolean {
        val state = store()
        val owner = state.ownerUserUuid.orEmpty()
        if (owner.isBlank()) {
            state.ownerUserUuid = incoming
            pendingOwnerUuid = null
            return false
        }
        if (BitbucketAccountUuid.matches(first = owner, second = incoming)) {
            pendingOwnerUuid = null
            return false
        }
        val pending = pendingOwnerUuid
        if (pending != null && BitbucketAccountUuid.matches(first = pending, second = incoming)) {
            val removedEntries = pullRequests.size
            clearLocked()
            state.ownerUserUuid = incoming
            pendingOwnerUuid = null
            LOG.info("Pull request cache owner changed; cleared $removedEntries cached pull request(s)")
            onCleared()
            return true
        }
        pendingOwnerUuid = incoming
        return false
    }

    private fun clearLocked() {
        pullRequests.clear()
        snapshots.clear()
        pendingOwnerUuid = null
        rememberedUser = null
    }

    private fun resolve(snapshot: ListingSnapshot): CachedListing {
        val resolved = snapshot.keys.mapNotNull { key -> pullRequests[key.encode()] }
        return CachedListing(
            entries = resolved,
            fetchedAt = snapshot.fetchedAt,
            isTruncated = snapshot.isTruncated,
            isValid = snapshot.isValid,
            isComplete = resolved.size == snapshot.keys.size
        )
    }

    private fun isWithinFreshTtl(fetchedAt: Instant, now: Instant): Boolean {
        val age = now - fetchedAt
        return age >= Duration.ZERO && age < limits.listingFreshTtl
    }

    private fun isOpenListing(listingId: String): Boolean {
        return listingId.startsWith(PullRequestListingIds.WORKSPACE_REVIEWING_PREFIX) ||
            listingId.startsWith(PullRequestListingIds.WORKSPACE_AUTHORED_PREFIX)
    }

    /**
     * Applies the retention rules in order. Pinned entries are the ones a recent listing snapshot still shows, so they
     * are kept through retention, and evicted only after every unpinned entry when the cap is reached.
     */
    private fun pruneLocked(now: Instant): Int {
        val pinned = pinnedKeys(now = now)
        val expired = removeExpiredEntries(now = now, pinned = pinned)
        val evicted = enforceCap(pinned = pinned)
        dropOldSnapshots(now = now)
        val removed = expired + evicted
        if (removed > 0) {
            LOG.info("Pruned $removed cached pull request(s)")
        }
        return removed
    }

    /** Keys referenced by any snapshot fetched within the retention window. */
    private fun pinnedKeys(now: Instant): Set<String> {
        return snapshots.values
            .filter { snapshot -> now - snapshot.fetchedAt <= limits.staleOpenRetention }
            .flatMap { snapshot -> snapshot.keys }
            .map { key -> key.encode() }
            .toSet()
    }

    private fun removeExpiredEntries(now: Instant, pinned: Set<String>): Int {
        val before = pullRequests.size
        pullRequests.values.removeAll { entry ->
            !pinned.contains(entry.key.encode()) && isExpired(entry = entry, now = now)
        }
        return before - pullRequests.size
    }

    private fun isExpired(entry: CachedPullRequest, now: Instant): Boolean {
        return if (entry.state == PullRequestState.Open) {
            now - entry.lastFetchedAt > limits.staleOpenRetention
        } else {
            now - (entry.closedOn ?: entry.updatedOn) > limits.closedRetention
        }
    }

    /** Evicts the oldest unpinned entries first, then the oldest pinned ones, and closed entries before open ones when tied. */
    private fun enforceCap(pinned: Set<String>): Int {
        val overflow = pullRequests.size - limits.maxEntries
        if (overflow <= 0) {
            return 0
        }
        val victims = pullRequests.values
            .sortedWith(
                compareBy<CachedPullRequest>(
                    { entry -> pinned.contains(entry.key.encode()) },
                    { entry -> entry.lastFetchedAt },
                    { entry -> entry.state == PullRequestState.Open }
                )
            )
            .take(overflow)
        victims.forEach { victim -> pullRequests.remove(victim.key.encode()) }
        return victims.size
    }

    /** Drops snapshots older than the retention window. Snapshots that point at a removed entry become invalid. */
    private fun dropOldSnapshots(now: Instant) {
        snapshots.values.removeAll { snapshot -> now - snapshot.fetchedAt > limits.staleOpenRetention }
        for (listingId in snapshots.keys.toList()) {
            val snapshot = snapshots[listingId] ?: continue
            val hasMissingEntry = snapshot.keys.any { key -> !pullRequests.containsKey(key.encode()) }
            if (snapshot.isValid && hasMissingEntry) {
                snapshots[listingId] = snapshot.copy(isValid = false)
            }
        }
    }

    private fun ageSince(instant: Instant, now: Instant): Duration? {
        val age = now - instant
        return if (age.isNegative()) null else age
    }

    companion object {
        private val LOG = logger<DiffyPullRequestCache>()

        fun getInstance(): DiffyPullRequestCache {
            return ApplicationManager.getApplication().service()
        }
    }
}