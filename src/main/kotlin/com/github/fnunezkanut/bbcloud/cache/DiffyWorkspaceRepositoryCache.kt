package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Application-level cache of the repository list of the configured workspace, persisted through
 * [DiffyWorkspaceRepositoryCacheState]. It holds one snapshot; a snapshot of another workspace is ignored and replaced.
 *
 * Calls decode up to 5,000 rows, so callers run them on `Dispatchers.IO`. This class never calls the pull request
 * cache, so the clear hook of that cache cannot deadlock with it.
 */
@Service(Service.Level.APP)
class DiffyWorkspaceRepositoryCache(
    private val store: () -> DiffyWorkspaceRepositoryCacheState.State = { DiffyWorkspaceRepositoryCacheState.getInstance().state },
    private val clock: () -> Instant = { Clock.System.now() }
) : WorkspaceRepositoryCacheStore {
    private val lock = Any()

    override fun read(workspace: String): CachedWorkspaceRepositories? {
        synchronized(lock) {
            val state = store()
            if (state.workspace != workspace) {
                return null
            }
            val fetchedAt = runCatching { Instant.parse(state.fetchedAt.orEmpty()) }.getOrNull() ?: return null
            val repositories = state.repositories.mapNotNull { row -> CachedWorkspaceRepositoryRow.decode(row = row) }
            LOG.debug("Read ${repositories.size} cached repositories of $workspace")
            return CachedWorkspaceRepositories(
                repositories = repositories,
                isTruncated = state.isTruncated,
                fetchedAt = fetchedAt
            )
        }
    }

    override fun replace(workspace: String, repositories: List<BitbucketWorkspaceRepository>, isTruncated: Boolean) {
        synchronized(lock) {
            val state = store()
            state.workspace = workspace
            state.fetchedAt = clock().toString()
            state.isTruncated = isTruncated
            state.repositories = repositories.map { repository -> CachedWorkspaceRepositoryRow.encode(repository = repository) }.toMutableList()
            LOG.debug("Cached ${repositories.size} repositories of $workspace")
        }
    }

    override fun upsert(workspace: String, repository: BitbucketWorkspaceRepository) {
        synchronized(lock) {
            val state = store()
            if (!hasSnapshot(state = state, workspace = workspace)) {
                return
            }
            val others = decodedRepositories(state = state).filterNot { existing -> existing.slug == repository.slug }
            val updated = (others + repository).sortedBy { entry -> entry.slug.lowercase() }
            state.repositories = updated.map { entry -> CachedWorkspaceRepositoryRow.encode(repository = entry) }.toMutableList()
            LOG.debug("Updated cached repository ${repository.slug} of $workspace")
        }
    }

    override fun remove(workspace: String, repoSlug: String) {
        synchronized(lock) {
            val state = store()
            if (!hasSnapshot(state = state, workspace = workspace)) {
                return
            }
            val remaining = decodedRepositories(state = state).filterNot { existing -> existing.slug == repoSlug }
            state.repositories = remaining.map { entry -> CachedWorkspaceRepositoryRow.encode(repository = entry) }.toMutableList()
            LOG.debug("Removed cached repository $repoSlug of $workspace")
        }
    }

    override fun clear() {
        synchronized(lock) {
            val state = store()
            state.workspace = ""
            state.fetchedAt = ""
            state.isTruncated = false
            state.repositories = mutableListOf()
            LOG.debug("Cleared the workspace repository cache")
        }
    }

    private fun hasSnapshot(state: DiffyWorkspaceRepositoryCacheState.State, workspace: String): Boolean {
        return state.workspace == workspace && !state.fetchedAt.isNullOrBlank()
    }

    private fun decodedRepositories(state: DiffyWorkspaceRepositoryCacheState.State): List<BitbucketWorkspaceRepository> {
        return state.repositories.mapNotNull { row -> CachedWorkspaceRepositoryRow.decode(row = row) }
    }

    companion object {
        private val LOG = logger<DiffyWorkspaceRepositoryCache>()

        fun getInstance(): DiffyWorkspaceRepositoryCache {
            return ApplicationManager.getApplication().service()
        }

        /** Clears the saved list. Does nothing without an application (unit tests). */
        fun clearIfAvailable() {
            if (ApplicationManager.getApplication() == null) {
                return
            }
            try {
                getInstance().clear()
            } catch (exception: Exception) {
                LOG.debug("Could not clear the workspace repository cache", exception)
            }
        }
    }
}