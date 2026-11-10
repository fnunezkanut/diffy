package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@Tag("unit")
class DiffyNotificationsMergeTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    private fun stored(
        id: String,
        occurredAt: String = "2026-11-06T17:30:44.112Z",
        unread: Boolean = true,
        changedAt: Instant? = null,
        message: String? = "m"
    ): DiffyStoredNotification {
        return DiffyStoredNotification(
            event = browserEvent(id = id, occurredAt = occurredAt, message = message, readState = null),
            unread = unread,
            changedAt = changedAt
        )
    }

    @Test
    fun `mergeSnapshot - should add unknown events as unread whatever Bitbucket says`() {
        // GIVEN an event Bitbucket reports as read
        val incoming = listOf(browserEvent(id = "a", readState = "read"))

        // WHEN
        val merged = mergeSnapshot(existing = emptyList(), incoming = incoming)

        // THEN
        assertSoftly {
            merged.single().unread shouldBe true
            merged.single().event.readState shouldBe null
        }
    }

    @Test
    fun `mergeSnapshot - should keep the read flag of a known event and refresh its data`() {
        // GIVEN a read notification
        val existing = listOf(stored(id = "a", unread = false, changedAt = now, message = "old"))

        // WHEN the snapshot carries a new message for the same id
        val merged = mergeSnapshot(existing = existing, incoming = listOf(browserEvent(id = "a", message = "new")))

        // THEN
        assertSoftly {
            merged.single().event.message shouldBe "new"
            merged.single().unread shouldBe false
            merged.single().changedAt shouldBe now
        }
    }

    @Test
    fun `mergeSnapshot - should keep events the snapshot no longer lists and collapse repeated ids`() {
        // GIVEN one stored notification and a snapshot that repeats another id
        val existing = listOf(stored(id = "old"))
        val incoming = listOf(browserEvent(id = "x", message = "first"), browserEvent(id = "x", message = "second"))

        // WHEN
        val merged = mergeSnapshot(existing = existing, incoming = incoming)

        // THEN
        assertSoftly {
            merged.map { it.event.id } shouldBe listOf("old", "x")
            merged.last().event.message shouldBe "first"
        }
    }

    @Test
    fun `countNewNotifications - should count only unseen ids once`() {
        // GIVEN one known notification and an incoming list with a known, two repeated, and one new id
        val before = listOf(stored(id = "known"))
        val incoming = listOf(browserEvent(id = "known"), browserEvent(id = "new"), browserEvent(id = "new"))
        val after = mergeSnapshot(existing = before, incoming = incoming)

        // WHEN
        val count = countNewNotifications(before = before, after = after, incoming = incoming)

        // THEN
        count shouldBe 1
    }

    @Test
    fun `countNewNotifications - should not count an event the cap evicted`() {
        // GIVEN a new event that is not in the capped result
        val incoming = listOf(browserEvent(id = "evicted"))

        // WHEN
        val count = countNewNotifications(before = emptyList(), after = emptyList(), incoming = incoming)

        // THEN
        count shouldBe 0
    }

    @Test
    fun `countNewNotifications - should be zero without incoming events`() {
        // GIVEN / WHEN
        val count = countNewNotifications(before = listOf(stored(id = "a")), after = listOf(stored(id = "a")), incoming = emptyList())

        // THEN
        count shouldBe 0
    }

    @Test
    fun `applyCap - should evict the oldest notifications beyond the maximum`() {
        // GIVEN 2001 notifications, one minute apart
        val base = Instant.parse("2026-11-01T00:00:00Z")
        val all = (0..MAX_STORED_NOTIFICATIONS).map { index ->
            stored(id = "n$index", occurredAt = (base + kotlin.time.Duration.parse("${index}m")).toString())
        }

        // WHEN
        val capped = applyCap(notifications = all)

        // THEN the oldest one is gone and the newest comes first
        assertSoftly {
            capped.size shouldBe MAX_STORED_NOTIFICATIONS
            capped.first().event.id shouldBe "n$MAX_STORED_NOTIFICATIONS"
            capped.any { it.event.id == "n0" } shouldBe false
        }
    }

    @Test
    fun `applyCap - should keep exactly the maximum and evict unparseable timestamps first`() {
        // GIVEN a full list plus one notification with a broken timestamp
        val base = Instant.parse("2026-11-01T00:00:00Z")
        val full = (0 until MAX_STORED_NOTIFICATIONS).map { index ->
            stored(id = "n$index", occurredAt = (base + kotlin.time.Duration.parse("${index}m")).toString())
        }

        // WHEN
        val capped = applyCap(notifications = full + stored(id = "broken", occurredAt = "never"))

        // THEN
        assertSoftly {
            capped.size shouldBe MAX_STORED_NOTIFICATIONS
            capped.any { it.event.id == "broken" } shouldBe false
        }
    }

    @Test
    fun `withUnread - should stamp only the notifications whose flag changes`() {
        // GIVEN one unread and one already read notification
        val list = listOf(stored(id = "a"), stored(id = "b", unread = false))

        // WHEN both are marked read
        val result = withUnread(notifications = list, ids = setOf("a", "b", "missing"), unread = false, now = now)

        // THEN only a changed
        assertSoftly {
            result[0].unread shouldBe false
            result[0].changedAt shouldBe now
            result[1].changedAt shouldBe null
        }
    }

    @Test
    fun `mergeExternal - should union both sides and keep the flag changed last`() {
        // GIVEN this IDE marked a read at 10:00 and the other IDE marked it unread at 11:00, plus one row each
        val local = listOf(stored(id = "a", unread = false, changedAt = Instant.parse("2026-11-08T10:00:00Z")), stored(id = "mine"))
        val incoming = listOf(stored(id = "a", unread = true, changedAt = Instant.parse("2026-11-08T11:00:00Z")), stored(id = "theirs"))

        // WHEN
        val merged = mergeExternal(local = local, incoming = incoming).associateBy { it.event.id }

        // THEN
        assertSoftly {
            merged.keys shouldBe setOf("a", "mine", "theirs")
            merged.getValue("a").unread shouldBe true
        }
    }

    @Test
    fun `mergeExternal - should keep a local change that is newer than the incoming one`() {
        // GIVEN a local change after the incoming one, and an incoming flag without a change time
        val local =
            listOf(stored(id = "a", unread = false, changedAt = Instant.parse("2026-11-08T12:00:00Z")), stored(id = "b", unread = false, changedAt = now))
        val incoming = listOf(stored(id = "a", unread = true, changedAt = Instant.parse("2026-11-08T11:00:00Z")), stored(id = "b"))

        // WHEN
        val merged = mergeExternal(local = local, incoming = incoming).associateBy { it.event.id }

        // THEN
        assertSoftly {
            merged.getValue("a").unread shouldBe false
            merged.getValue("b").unread shouldBe false
        }
    }

    @Test
    fun `laterOf - should pick the later time and tolerate nulls`() {
        val early = Instant.parse("2026-11-08T10:00:00Z")
        assertSoftly {
            laterOf(first = early, second = now) shouldBe now
            laterOf(first = now, second = early) shouldBe now
            laterOf(first = null, second = early) shouldBe early
            laterOf(first = null, second = null) shouldBe null
        }
    }
}