package com.github.fnunezkanut.bbcloud.external

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What is persisted: the last sync time, the notifications, and the raw rows of a newer plugin version that this
 * version cannot read but must write back unchanged.
 */
data class DiffyStoredContent(val lastSyncedAt: Instant?, val notifications: List<DiffyStoredNotification>, val passthroughRows: List<String> = emptyList())

/** Where the repository keeps its data. [DiffyNotificationsState] is the real one; tests use an in-memory fake. */
interface DiffyNotificationsStorage {
    fun read(): DiffyStoredContent

    fun write(content: DiffyStoredContent)

    /** Asks the platform to save soon, so other IDE processes see the change quickly. Must not block. */
    fun scheduleSave()
}

/** What the Notifications tab shows. [loaded] is false until the persisted state was read the first time. */
data class DiffyNotificationsContent(val lastSyncedAt: Instant?, val notifications: List<DiffyStoredNotification>, val loaded: Boolean) {
    companion object {
        val NOT_LOADED = DiffyNotificationsContent(lastSyncedAt = null, notifications = emptyList(), loaded = false)
    }
}

/**
 * Owns the persisted notifications: merges snapshots into them, applies the cap, changes read flags, merges the state
 * another IDE process wrote, and publishes every result through [content].
 *
 * Every public method blocks on one lock and is meant for [ioDispatcher]; the suspend ones switch to it themselves. The
 * class holds no platform state, so unit tests inject a fake [storage].
 */
class DiffyNotificationsRepository(
    private val storage: DiffyNotificationsStorage,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Instant = { Clock.System.now() }
) {
    private val lock = Any()
    private val state = MutableStateFlow(DiffyNotificationsContent.NOT_LOADED)
    private var passthroughRows: List<String> = emptyList()

    val content: StateFlow<DiffyNotificationsContent> = state.asStateFlow()

    /** Reads the persisted state the first time. Safe to call again; later calls do nothing. */
    suspend fun ensureLoaded() {
        withContext(ioDispatcher) { loadIfNeeded() }
    }

    /**
     * Merges the events of [snapshot] in, caps the list, and records the sync time. Returns how many notifications the
     * snapshot added that were never stored before. Returns 0 for the first sync (nothing was ever synced, which is
     * also the state after [clear]), so a backlog is not announced as new.
     */
    suspend fun recordSnapshot(snapshot: DiffyBrowserSnapshot): Int {
        return withContext(ioDispatcher) {
            synchronized(lock) {
                val current = loadIfNeeded()
                val merged = applyCap(notifications = mergeSnapshot(existing = current.notifications, incoming = snapshot.notificationEvents))
                val syncedAt = DiffyBrowserTimestamps.parseOrNull(value = snapshot.meta.capturedAt) ?: clock()
                commit(lastSyncedAt = syncedAt, notifications = merged)
                val newCount = if (current.lastSyncedAt == null) {
                    0
                } else {
                    countNewNotifications(before = current.notifications, after = merged, incoming = snapshot.notificationEvents)
                }
                LOG.debug("Recorded a snapshot: events=${snapshot.notificationEvents.size}, stored=${merged.size}, new=$newCount")
                newCount
            }
        }
    }

    /** Marks the notifications of [ids] as unread or read. Unknown ids are ignored. */
    suspend fun setUnread(ids: Set<String>, unread: Boolean) {
        withContext(ioDispatcher) {
            synchronized(lock) {
                val current = loadIfNeeded()
                val changed = withUnread(notifications = current.notifications, ids = ids, unread = unread, now = clock())
                if (changed != current.notifications) {
                    commit(lastSyncedAt = current.lastSyncedAt, notifications = changed)
                }
                LOG.debug("Marked ${ids.size} notification(s) as ${if (unread) "unread" else "read"}")
            }
        }
    }

    /** Forgets every notification and the sync time. The next snapshot fills the list again. */
    suspend fun clear() {
        withContext(ioDispatcher) {
            synchronized(lock) {
                loadIfNeeded()
                passthroughRows = emptyList()
                commit(lastSyncedAt = null, notifications = emptyList())
                LOG.debug("Cleared the stored notifications")
            }
        }
    }

    /**
     * Called after the platform reloaded the persisted state because another IDE process wrote it. Merges the incoming
     * state with the one in memory. It writes only when the merge added something to what the other process wrote, so
     * two IDEs cannot keep rewriting each other's file.
     */
    suspend fun onExternalReload() {
        withContext(ioDispatcher) {
            synchronized(lock) {
                if (!state.value.loaded) {
                    loadIfNeeded()
                    return@synchronized
                }
                val local = state.value
                val incoming = storage.read()
                val merged = applyCap(notifications = mergeExternal(local = local.notifications, incoming = incoming.notifications))
                val syncedAt = laterOf(first = local.lastSyncedAt, second = incoming.lastSyncedAt)
                passthroughRows = (passthroughRows + incoming.passthroughRows).distinct()
                val needsWrite = merged.toSet() != incoming.notifications.toSet() || syncedAt != incoming.lastSyncedAt
                if (needsWrite) {
                    commit(lastSyncedAt = syncedAt, notifications = merged)
                } else {
                    publish(lastSyncedAt = syncedAt, notifications = merged)
                }
                LOG.debug("Merged an external notification reload: stored=${merged.size}, wrote=$needsWrite")
            }
        }
    }

    private fun loadIfNeeded(): DiffyNotificationsContent {
        synchronized(lock) {
            val current = state.value
            if (current.loaded) {
                return current
            }
            val stored = storage.read()
            passthroughRows = stored.passthroughRows
            val capped = applyCap(notifications = stored.notifications)
            publish(lastSyncedAt = stored.lastSyncedAt, notifications = capped)
            LOG.debug("Loaded ${capped.size} stored notification(s)")
            return state.value
        }
    }

    private fun commit(lastSyncedAt: Instant?, notifications: List<DiffyStoredNotification>) {
        storage.write(
            DiffyStoredContent(
                lastSyncedAt = lastSyncedAt,
                notifications = notifications,
                passthroughRows = passthroughRows
            )
        )
        storage.scheduleSave()
        publish(lastSyncedAt = lastSyncedAt, notifications = notifications)
    }

    private fun publish(lastSyncedAt: Instant?, notifications: List<DiffyStoredNotification>) {
        state.value = DiffyNotificationsContent(lastSyncedAt = lastSyncedAt, notifications = notifications, loaded = true)
    }

    companion object {
        private val LOG = logger<DiffyNotificationsRepository>()
    }
}