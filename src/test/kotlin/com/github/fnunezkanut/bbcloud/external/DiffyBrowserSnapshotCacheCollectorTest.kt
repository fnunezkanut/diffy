package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyBrowserSnapshotCacheCollectorTest {
    private val flow = MutableStateFlow<DiffyBrowserSnapshot?>(null)
    private val applied = mutableListOf<String>()

    private fun snapshotAt(capturedAt: String): DiffyBrowserSnapshot {
        return browserSnapshot(capturedAt = capturedAt)
    }

    @Test
    fun `collect - should hand every emitted snapshot to the updater and skip the initial null`() {
        // GIVEN a running collector
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val collector = DiffyBrowserSnapshotCacheCollector(
            snapshots = flow,
            apply = { snapshot -> applied += snapshot.meta.capturedAt },
            ioDispatcher = Dispatchers.Unconfined
        )
        scope.launch { collector.collect() }

        // WHEN two snapshots arrive
        flow.value = snapshotAt(capturedAt = "one")
        flow.value = snapshotAt(capturedAt = "two")
        scope.cancel()

        // THEN both were applied in order
        applied shouldBe listOf("one", "two")
    }

    @Test
    fun `collect - should log a failure and still process the next snapshot`() {
        // GIVEN an updater that fails on the first snapshot
        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val collector = DiffyBrowserSnapshotCacheCollector(
            snapshots = flow,
            apply = { snapshot ->
                if (snapshot.meta.capturedAt == "bad") {
                    throw IllegalStateException("boom")
                }
                applied += snapshot.meta.capturedAt
            },
            ioDispatcher = Dispatchers.Unconfined
        )
        scope.launch { collector.collect() }

        // WHEN a failing snapshot is followed by a good one
        flow.value = snapshotAt(capturedAt = "bad")
        flow.value = snapshotAt(capturedAt = "good")
        val stillActive = job.isActive
        scope.cancel()

        // THEN the collector survived and applied the good one
        assertSoftly {
            applied shouldBe listOf("good")
            stillActive shouldBe true
        }
    }
}