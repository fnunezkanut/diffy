package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequest
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore
import com.intellij.openapi.diagnostic.logger

/** What one snapshot did to the cache. [otherWorkspace] counts pull requests outside the configured workspace. */
data class CacheUpdateResult(
    val updated: Int = 0,
    val inserted: Int = 0,
    val unchanged: Int = 0,
    val skippedIncomplete: Int = 0,
    val otherWorkspace: Int = 0,
    val becameClosed: Int = 0,
    val disappeared: Int = 0
) {
    val hasChanges: Boolean
        get() = updated > 0 || inserted > 0 || becameClosed > 0 || disappeared > 0
}

/**
 * Folds the pull requests of a snapshot into the pull request cache. Existing entries are patched, unknown open pull
 * requests of the configured workspace are inserted, and a pull request that vanished from a complete snapshot has its
 * listings invalidated so the next load asks Bitbucket. It reads and writes the cache, so call it only on a background
 * thread. One instance keeps the keys of the previous snapshot, so use a single instance per IDE.
 */
class DiffyBrowserSnapshotCacheUpdater(private val cache: PullRequestCacheStore, private val configuredWorkspace: () -> String?) {
    private var previousKeys: Set<PullRequestCacheKey> = emptySet()

    private class Counters {
        var updated = 0
        var inserted = 0
        var unchanged = 0
        var skippedIncomplete = 0
        var otherWorkspace = 0
        var becameClosed = 0
        var disappeared = 0
    }

    @Synchronized
    fun apply(snapshot: DiffyBrowserSnapshot): CacheUpdateResult {
        val workspace = configuredWorkspace()?.trim().orEmpty()
        val counters = Counters()
        if (workspace.isEmpty()) {
            counters.otherWorkspace = snapshot.pullRequests.size
            return resultOf(counters = counters)
        }
        val currentKeys = HashSet<PullRequestCacheKey>()
        for (incoming in snapshot.pullRequests) {
            if (!incoming.workspace.trim().equals(other = workspace, ignoreCase = true)) {
                counters.otherWorkspace++
                continue
            }
            val key = PullRequestCacheKey.of(workspace = incoming.workspace, repoSlug = incoming.repoSlug, prNum = incoming.id)
            currentKeys.add(key)
            if (cache.get(key = key) == null) {
                insert(incoming = incoming, counters = counters)
            } else {
                patch(key = key, incoming = incoming, counters = counters)
            }
        }
        invalidateDisappeared(snapshot = snapshot, currentKeys = currentKeys, counters = counters)
        val result = resultOf(counters = counters)
        logResult(result = result)
        return result
    }

    private fun insert(incoming: DiffyBrowserPullRequest, counters: Counters) {
        val summary = DiffyBrowserSnapshotCacheMerge.toSummary(incoming = incoming)
        if (summary == null) {
            counters.skippedIncomplete++
            return
        }
        cache.upsert(workspace = incoming.workspace, repoSlug = incoming.repoSlug, summary = summary)
        counters.inserted++
    }

    private fun patch(key: PullRequestCacheKey, incoming: DiffyBrowserPullRequest, counters: Counters) {
        var before: CachedPullRequest? = null
        var after: CachedPullRequest? = null
        // Merge only inside the patch: the entry can change between the lookup and the patch.
        cache.patch(key = key) { current ->
            before = current
            after = DiffyBrowserSnapshotCacheMerge.merge(existing = current, incoming = incoming)
            after ?: current
        }
        val previous = before
        val next = after
        if (previous == null || next == null) {
            counters.unchanged++
            return
        }
        counters.updated++
        if (previous.state == PullRequestState.Open && next.state != PullRequestState.Open) {
            cache.removeFromOpenListings(key = key)
            counters.becameClosed++
        } else if (isVisibleChange(previous = previous, next = next)) {
            cache.invalidateListingsContaining(key = key)
        }
    }

    private fun isVisibleChange(previous: CachedPullRequest, next: CachedPullRequest): Boolean {
        return previous.commentCount != next.commentCount ||
            previous.isDraft != next.isDraft ||
            previous.title != next.title ||
            previous.approverUuids != next.approverUuids
    }

    /**
     * A pull request that was in the previous snapshot and is missing now may have been merged or declined. The state is
     * not guessed: the listings are invalidated so Bitbucket answers. A truncated snapshot proves nothing, so the keys
     * of the earlier snapshots are kept.
     */
    private fun invalidateDisappeared(snapshot: DiffyBrowserSnapshot, currentKeys: Set<PullRequestCacheKey>, counters: Counters) {
        if (snapshot.meta.truncatedPullRequests) {
            previousKeys = previousKeys + currentKeys
            return
        }
        for (key in previousKeys - currentKeys) {
            val entry = cache.get(key = key)
            if (entry != null && entry.state == PullRequestState.Open) {
                cache.invalidateListingsContaining(key = key)
                counters.disappeared++
            }
        }
        previousKeys = currentKeys
    }

    private fun resultOf(counters: Counters): CacheUpdateResult {
        return CacheUpdateResult(
            updated = counters.updated,
            inserted = counters.inserted,
            unchanged = counters.unchanged,
            skippedIncomplete = counters.skippedIncomplete,
            otherWorkspace = counters.otherWorkspace,
            becameClosed = counters.becameClosed,
            disappeared = counters.disappeared
        )
    }

    private fun logResult(result: CacheUpdateResult) {
        val summary = "Applied the browser snapshot to the pull request cache: updated=${result.updated}, " +
            "inserted=${result.inserted}, unchanged=${result.unchanged}, skippedIncomplete=${result.skippedIncomplete}, " +
            "otherWorkspace=${result.otherWorkspace}, becameClosed=${result.becameClosed}, disappeared=${result.disappeared}"
        if (result.hasChanges) {
            LOG.info(summary)
        } else {
            LOG.debug(summary)
        }
    }

    companion object {
        private val LOG = logger<DiffyBrowserSnapshotCacheUpdater>()
    }
}