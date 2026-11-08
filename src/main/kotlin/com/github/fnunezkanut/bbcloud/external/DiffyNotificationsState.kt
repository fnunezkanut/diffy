package com.github.fnunezkanut.bbcloud.external

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The persisted notifications. Its XML lives in its own file in the IDE config directory and is never synced by
 * settings sync (roaming is disabled). Registered as an `applicationService` in `plugin.xml`.
 *
 * The component is reloadable, so when another IDE process of the same product rewrites the file the platform calls
 * [loadState] on the running instance; [addReloadListener] listeners then merge the new state with the one in memory.
 *
 * [State.rows] holds one JSON row per notification, see [DiffyStoredNotificationRow]. Rows of a newer plugin version
 * are kept as they are, so they survive a downgrade and a later upgrade.
 */
@State(
    name = "BbCloudNotifications",
    storages = [Storage(value = "bbcloud-notifications.xml", roamingType = RoamingType.DISABLED)]
)
class DiffyNotificationsState :
    SimplePersistentStateComponent<DiffyNotificationsState.State>(State()),
    DiffyNotificationsStorage {
    class State : BaseState() {
        var lastSyncedAt by string("")

        var rows by list<String>()
    }

    private val reloadListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addReloadListener(listener: () -> Unit) {
        reloadListeners.add(listener)
    }

    override fun loadState(state: State) {
        super.loadState(state)
        LOG.debug("Loaded the notification state: rows=${state.rows.size}")
        reloadListeners.forEach { listener ->
            try {
                listener()
            } catch (exception: Exception) {
                LOG.warn("A notification state reload listener failed", exception)
            }
        }
    }

    override fun read(): DiffyStoredContent {
        val current = state
        val notifications = ArrayList<DiffyStoredNotification>()
        val passthrough = ArrayList<String>()
        var unreadable = 0
        for (row in current.rows.toList()) {
            val notification = DiffyStoredNotificationRow.decode(encoded = row)
            when {
                notification != null -> notifications.add(notification)
                DiffyStoredNotificationRow.isNewerVersion(encoded = row) -> passthrough.add(row)
                else -> unreadable++
            }
        }
        if (passthrough.isNotEmpty()) {
            LOG.warn("Keeping ${passthrough.size} notification row(s) written by a newer plugin version untouched")
        }
        if (unreadable > 0) {
            LOG.debug("Skipped $unreadable unreadable notification row(s)")
        }
        return DiffyStoredContent(
            lastSyncedAt = DiffyBrowserTimestamps.parseOrNull(value = current.lastSyncedAt),
            notifications = notifications,
            passthroughRows = passthrough
        )
    }

    override fun write(content: DiffyStoredContent) {
        val current = state
        val encoded = content.notifications.map { notification -> DiffyStoredNotificationRow.encode(notification = notification) }
        current.rows.clear()
        current.rows.addAll(encoded + content.passthroughRows)
        current.lastSyncedAt = content.lastSyncedAt?.toString().orEmpty()
    }

    override fun scheduleSave() {
        SaveAndSyncHandler.getInstance().scheduleSave(task = SaveAndSyncHandler.SaveTask())
    }

    companion object {
        private val LOG = logger<DiffyNotificationsState>()

        fun getInstance(): DiffyNotificationsState {
            return ApplicationManager.getApplication().service()
        }
    }
}