package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

private class FakeStorage(var content: DiffyStoredContent = DiffyStoredContent(lastSyncedAt = null, notifications = emptyList())) : DiffyNotificationsStorage {
    var saves = 0
    var writes = 0

    override fun read(): DiffyStoredContent {
        return content
    }

    override fun write(content: DiffyStoredContent) {
        writes++
        this.content = content
    }

    override fun scheduleSave() {
        saves++
    }
}

/** Runs a suspending test body to completion; returns Unit so JUnit accepts the test method. */
private fun runTest(block: suspend () -> Unit) {
    runBlocking { block() }
}

@Tag("unit")
class DiffyNotificationsRepositoryTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val storage = FakeStorage()
    private val repository = DiffyNotificationsRepository(storage = storage, ioDispatcher = Dispatchers.Unconfined, clock = { now })

    @Test
    fun `ensureLoaded - should publish what is persisted`() =
        runTest {
            // GIVEN a persisted read notification
            storage.content = DiffyStoredContent(
                lastSyncedAt = now,
                notifications = listOf(DiffyStoredNotification(event = browserEvent(id = "a"), unread = false))
            )

            // WHEN
            repository.ensureLoaded()

            // THEN
            assertSoftly {
                repository.content.value.loaded shouldBe true
                repository.content.value.notifications.single().unread shouldBe false
                repository.content.value.lastSyncedAt shouldBe now
            }
        }

    @Test
    fun `recordSnapshot - should persist, schedule a save and use the capture time`() =
        runTest {
            // WHEN a snapshot arrives
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a")), capturedAt = "2026-11-08T09:00:00.000Z"))

            // THEN
            assertSoftly {
                storage.content.notifications.map { it.event.id } shouldBe listOf("a")
                storage.content.lastSyncedAt shouldBe Instant.parse("2026-11-08T09:00:00.000Z")
                storage.saves shouldBe 1
                repository.content.value.notifications.single().unread shouldBe true
            }
        }

    @Test
    fun `recordSnapshot - should fall back to the receive time when the capture time does not parse`() =
        runTest {
            // WHEN
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a")), capturedAt = "yesterday"))

            // THEN
            storage.content.lastSyncedAt shouldBe now
        }

    @Test
    fun `setUnread - should persist the flag and ignore unknown ids`() =
        runTest {
            // GIVEN a stored notification
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))

            // WHEN
            repository.setUnread(ids = setOf("a", "missing"), unread = false)

            // THEN
            assertSoftly {
                storage.content.notifications.single().unread shouldBe false
                storage.content.notifications.single().changedAt shouldBe now
            }
        }

    @Test
    fun `clear - should forget the notifications and the sync time`() =
        runTest {
            // GIVEN a stored notification
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))

            // WHEN
            repository.clear()

            // THEN
            assertSoftly {
                storage.content.notifications shouldBe emptyList()
                storage.content.lastSyncedAt shouldBe null
                repository.content.value.notifications shouldBe emptyList()
            }
        }

    @Test
    fun `onExternalReload - should merge what another IDE wrote with what this IDE holds`() =
        runTest {
            // GIVEN this IDE holds a, then the other IDE wrote b and nothing else
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))
            storage.content = DiffyStoredContent(
                lastSyncedAt = now,
                notifications = listOf(DiffyStoredNotification(event = browserEvent(id = "b")))
            )
            val writesBefore = storage.writes

            // WHEN
            repository.onExternalReload()

            // THEN both are shown, and the union is written back because it adds a
            assertSoftly {
                repository.content.value.notifications.map { it.event.id }.toSet() shouldBe setOf("a", "b")
                storage.writes shouldBe writesBefore + 1
            }
        }

    @Test
    fun `onExternalReload - should not write when the other IDE already holds everything`() =
        runTest {
            // GIVEN this IDE and the file agree
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))
            val writesBefore = storage.writes

            // WHEN the platform reloads the same content
            repository.onExternalReload()

            // THEN nothing is rewritten, so two IDEs cannot ping-pong
            storage.writes shouldBe writesBefore
        }

    @Test
    fun `onExternalReload - should take a newer read flag from the other IDE`() =
        runTest {
            // GIVEN a locally unread notification that the other IDE marked read later
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))
            storage.content = DiffyStoredContent(
                lastSyncedAt = now,
                notifications = listOf(DiffyStoredNotification(event = browserEvent(id = "a"), unread = false, changedAt = now))
            )

            // WHEN
            repository.onExternalReload()

            // THEN
            repository.content.value.notifications.single().unread shouldBe false
        }

    @Test
    fun `writes - should keep rows of a newer plugin version untouched`() =
        runTest {
            // GIVEN a persisted row this version cannot read
            val future = """{"v":9,"id":"f"}"""
            storage.content = DiffyStoredContent(lastSyncedAt = null, notifications = emptyList(), passthroughRows = listOf(future))

            // WHEN a snapshot arrives
            repository.recordSnapshot(snapshot = browserSnapshot(events = listOf(browserEvent(id = "a"))))

            // THEN the unknown row is written back as it was
            storage.content.passthroughRows shouldBe listOf(future)
        }
}