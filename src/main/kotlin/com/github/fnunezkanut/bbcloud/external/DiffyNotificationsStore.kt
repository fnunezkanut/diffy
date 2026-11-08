package com.github.fnunezkanut.bbcloud.external

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The application service in front of the persisted notifications. It wires [DiffyNotificationsRepository] to
 * [DiffyNotificationsState], so a change another IDE process wrote shows up in [content] while a Notifications tab is
 * open. The platform injects [coroutineScope] and cancels it on shutdown.
 */
@Service(Service.Level.APP)
class DiffyNotificationsStore(private val coroutineScope: CoroutineScope) {
    private val repository = DiffyNotificationsRepository(
        storage = DiffyNotificationsState.getInstance(),
        ioDispatcher = Dispatchers.IO
    )

    /** The persisted notifications. [DiffyNotificationsContent.loaded] is false until the first read finished. */
    val content: StateFlow<DiffyNotificationsContent> = repository.content

    init {
        DiffyNotificationsState.getInstance().addReloadListener {
            LOG.debug("The notification state was reloaded from disk")
            launchGuarded(action = "merge a reloaded state") { repository.onExternalReload() }
        }
        launchGuarded(action = "load the persisted notifications") { repository.ensureLoaded() }
    }

    suspend fun recordSnapshot(snapshot: DiffyBrowserSnapshot) {
        repository.recordSnapshot(snapshot = snapshot)
    }

    suspend fun setUnread(ids: Set<String>, unread: Boolean) {
        repository.setUnread(ids = ids, unread = unread)
    }

    suspend fun clear() {
        repository.clear()
    }

    private fun launchGuarded(action: String, block: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not $action", exception)
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyNotificationsStore>()

        fun getInstance(): DiffyNotificationsStore {
            return ApplicationManager.getApplication().service()
        }

        /** Forgets the stored notifications in the background. Does nothing without an application (unit tests). */
        fun clearInBackground() {
            val application = ApplicationManager.getApplication() ?: return
            val store = application.service<DiffyNotificationsStore>()
            store.launchGuarded(action = "clear the stored notifications") { store.clear() }
        }
    }
}