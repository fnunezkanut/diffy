package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@Tag("unit")
class DiffyStoredNotificationRowTest {
    @Test
    fun `encode and decode - should round trip every field and the read flag`() {
        // GIVEN a read notification with a change time
        val original = DiffyStoredNotification(
            event = browserEvent(id = "e1", category = DiffyBrowserEventCategory.MENTION, readState = null),
            unread = false,
            changedAt = Instant.parse("2026-11-08T10:00:00Z")
        )

        // WHEN
        val decoded = DiffyStoredNotificationRow.decode(encoded = DiffyStoredNotificationRow.encode(notification = original))

        // THEN
        decoded shouldBe original
    }

    @Test
    fun `encode - should not write the read state of Bitbucket and decode should return null for it`() {
        // GIVEN an event Bitbucket reports as read
        val notification = DiffyStoredNotification(event = browserEvent(readState = "read"))

        // WHEN
        val encoded = DiffyStoredNotificationRow.encode(notification = notification)
        val decoded = DiffyStoredNotificationRow.decode(encoded = encoded)

        // THEN the notification is still unread and carries no read state
        assertSoftly {
            encoded.contains("read") shouldBe false
            decoded?.unread shouldBe true
            decoded?.event?.readState shouldBe null
        }
    }

    @Test
    fun `decode - should default to unread when the flag is missing`() {
        // GIVEN a minimal row
        val encoded = """{"v":1,"id":"e1","at":"2026-11-06T17:30:44.112Z"}"""

        // WHEN / THEN
        DiffyStoredNotificationRow.decode(encoded = encoded)?.unread shouldBe true
    }

    @Test
    fun `decode - should return null for malformed rows, blank ids and other versions`() {
        assertSoftly {
            DiffyStoredNotificationRow.decode(encoded = "not json") shouldBe null
            DiffyStoredNotificationRow.decode(encoded = """{"v":1,"id":"","at":"x"}""") shouldBe null
            DiffyStoredNotificationRow.decode(encoded = """{"v":2,"id":"e1","at":"x"}""") shouldBe null
        }
    }

    @Test
    fun `decode - should ignore an unparseable change time`() {
        // GIVEN a row with a bad change time
        val encoded = """{"v":1,"id":"e1","at":"x","ch":"yesterday"}"""

        // WHEN / THEN
        DiffyStoredNotificationRow.decode(encoded = encoded)?.changedAt shouldBe null
    }

    @Test
    fun `isNewerVersion - should only be true for a well formed row of a newer version`() {
        assertSoftly {
            DiffyStoredNotificationRow.isNewerVersion(encoded = """{"v":2,"id":"e1","future":true}""") shouldBe true
            DiffyStoredNotificationRow.isNewerVersion(encoded = """{"v":1,"id":"e1","at":"x"}""") shouldBe false
            DiffyStoredNotificationRow.isNewerVersion(encoded = "not json") shouldBe false
        }
    }
}