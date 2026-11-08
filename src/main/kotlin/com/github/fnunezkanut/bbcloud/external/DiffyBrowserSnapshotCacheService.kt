package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies every snapshot of [snapshots] with [apply] on [ioDispatcher]. A failure is logged and the next snapshot is
 * still processed. The class holds no platform state, so unit tests inject a fake flow and a fake [apply].
 */
class DiffyBrowserSnapshotCacheCollector(
    private val snapshots: Flow<DiffyBrowserSnapshot?>,
    private val apply: (DiffyBrowserSnapshot) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun collect() {
        snapshots.filterNotNull().collect { snapshot ->
            try {
                LOG.debug(
                    "Applying a browser snapshot to the pull request cache: pullRequests=${snapshot.pullRequests.size}, " +
                        "events=${snapshot.notificationEvents.size}"
                )
                val startedAt = System.nanoTime()
                withContext(ioDispatcher) { apply(snapshot) }
                LOG.debug("Applied the browser snapshot in ${(System.nanoTime() - startedAt) / NANOS_PER_MILLI} ms")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not apply the browser snapshot to the pull request cache", exception)
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyBrowserSnapshotCacheCollector>()
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Keeps the pull request cache and the persisted notifications in step with the snapshots of the Chrome extension, even
 * when the Notifications tab was never opened. The platform injects [coroutineScope] and cancels it on shutdown. Registered as an `applicationService`
 * in `plugin.xml` and created by [DiffyBrowserSnapshotCacheStartupActivity].
 */
class DiffyBrowserSnapshotCacheService(coroutineScope: CoroutineScope) {
    init {
        val updater = DiffyBrowserSnapshotCacheUpdater(
            cache = DiffyPullRequestCache.getInstance(),
            configuredWorkspace = { DiffyConfigurationService.getInstance().workspaceSlugOrNull() }
        )
        val collector = DiffyBrowserSnapshotCacheCollector(
            snapshots = DiffyBrowserSnapshotStore.getInstance().snapshot,
            apply = { snapshot -> updater.apply(snapshot = snapshot) }
        )
        LOG.debug("Started collecting browser snapshots for the pull request cache")
        coroutineScope.launch { collector.collect() }
        coroutineScope.launch { recordNotifications(snapshots = DiffyBrowserSnapshotStore.getInstance().snapshot) }
    }

    /** Persists the notification events of every snapshot. A failure is logged and the next snapshot is still processed. */
    private suspend fun recordNotifications(snapshots: Flow<DiffyBrowserSnapshot?>) {
        val notificationsStore = DiffyNotificationsStore.getInstance()
        snapshots.filterNotNull().collect { snapshot ->
            try {
                notificationsStore.recordSnapshot(snapshot = snapshot)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not persist the notifications of the browser snapshot", exception)
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyBrowserSnapshotCacheService>()
    }
}

/** Creates [DiffyBrowserSnapshotCacheService] once an IDE window has opened. */
class DiffyBrowserSnapshotCacheStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<DiffyBrowserSnapshotCacheService>()
    }
}