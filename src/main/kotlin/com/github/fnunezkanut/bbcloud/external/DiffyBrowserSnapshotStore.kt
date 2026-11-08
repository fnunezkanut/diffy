package com.github.fnunezkanut.bbcloud.external

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the latest snapshot sent by the Chrome extension, in memory only. A restart of the IDE loses it until the
 * extension delivers the next one. Its notification events are persisted separately by [DiffyNotificationsStore]; the
 * snapshot, and with it the pull request list, stays in memory. Registered as an `applicationService` in `plugin.xml`.
 */
class DiffyBrowserSnapshotStore {
    private val state = MutableStateFlow<DiffyBrowserSnapshot?>(null)

    /** Emits the newest accepted snapshot, or null before the first one arrives. */
    val snapshot: StateFlow<DiffyBrowserSnapshot?> = state.asStateFlow()

    /** Replaces the stored snapshot. The newest capture always supersedes the previous one. */
    fun replace(snapshot: DiffyBrowserSnapshot) {
        state.value = snapshot
        LOG.debug(
            "Replaced the stored snapshot: pullRequests=${snapshot.pullRequests.size}, " +
                "events=${snapshot.notificationEvents.size}"
        )
    }

    fun latest(): DiffyBrowserSnapshot? {
        return state.value
    }

    companion object {
        private val LOG = logger<DiffyBrowserSnapshotStore>()

        fun getInstance(): DiffyBrowserSnapshotStore {
            return ApplicationManager.getApplication().service()
        }
    }
}