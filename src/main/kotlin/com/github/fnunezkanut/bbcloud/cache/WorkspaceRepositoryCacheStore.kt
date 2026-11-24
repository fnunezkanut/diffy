package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import kotlin.time.Instant

/** The cached repository list of one workspace. */
data class CachedWorkspaceRepositories(val repositories: List<BitbucketWorkspaceRepository>, val isTruncated: Boolean, val fetchedAt: Instant)

interface WorkspaceRepositoryCacheStore {
    /** The snapshot of [workspace], or null when nothing is cached for it. */
    fun read(workspace: String): CachedWorkspaceRepositories?

    /** Replaces the snapshot with [repositories] of [workspace], stamped now. */
    fun replace(workspace: String, repositories: List<BitbucketWorkspaceRepository>, isTruncated: Boolean)

    /** Replaces the row of [repository] in the snapshot of [workspace]; no-op when that workspace is not cached. Keeps fetchedAt. */
    fun upsert(workspace: String, repository: BitbucketWorkspaceRepository)

    /** Removes [repoSlug] from the snapshot of [workspace]; no-op when not cached. Keeps fetchedAt. */
    fun remove(workspace: String, repoSlug: String)

    fun clear()
}

object NoOpWorkspaceRepositoryCacheStore : WorkspaceRepositoryCacheStore {
    override fun read(workspace: String): CachedWorkspaceRepositories? {
        return null
    }

    override fun replace(workspace: String, repositories: List<BitbucketWorkspaceRepository>, isTruncated: Boolean) {
    }

    override fun upsert(workspace: String, repository: BitbucketWorkspaceRepository) {
    }

    override fun remove(workspace: String, repoSlug: String) {
    }

    override fun clear() {
    }
}