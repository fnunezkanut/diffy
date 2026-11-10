package com.github.fnunezkanut.bbcloud.external

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Persists the notification events of every snapshot of [snapshots] with [record] and hands the number of new
 * notifications to [announce] when it is above zero. A failure is logged and the next snapshot is still processed. The
 * class holds no platform state, so unit tests inject a fake flow, a fake [record], and a fake [announce].
 */
class DiffyNotificationsRecorder(
    private val snapshots: Flow<DiffyBrowserSnapshot?>,
    private val record: suspend (DiffyBrowserSnapshot) -> Int,
    private val announce: (Int) -> Unit
) {
    suspend fun collect() {
        snapshots.filterNotNull().collect { snapshot ->
            try {
                val newCount = record(snapshot)
                if (newCount > 0) {
                    announce(newCount)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not persist the notifications of the browser snapshot", exception)
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyNotificationsRecorder>()
    }
}