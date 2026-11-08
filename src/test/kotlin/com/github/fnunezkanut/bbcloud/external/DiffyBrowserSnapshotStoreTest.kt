package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyBrowserSnapshotStoreTest {
    private fun snapshot(capturedAt: String): DiffyBrowserSnapshot {
        return DiffyBrowserSnapshot(
            schemaVersion = 1,
            meta = DiffyBrowserSnapshotMeta(capturedAt = capturedAt, workspaces = emptyList(), skippedNonBitbucket = 0),
            pullRequests = emptyList(),
            notificationEvents = emptyList(),
            skippedInvalidEntries = 0
        )
    }

    @Test
    fun `replace - should publish the new snapshot and supersede the previous one`() {
        // GIVEN an empty store
        val store = DiffyBrowserSnapshotStore()
        store.latest() shouldBe null

        // WHEN two snapshots arrive
        val first = snapshot(capturedAt = "first")
        val second = snapshot(capturedAt = "second")
        store.replace(snapshot = first)
        val afterFirst = store.snapshot.value
        store.replace(snapshot = second)

        // THEN the newest wins
        assertSoftly {
            afterFirst shouldBe first
            store.snapshot.value shouldBe second
            store.latest() shouldBe second
        }
    }
}