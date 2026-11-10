package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyNotificationsRecorderTest {
    private val announced = mutableListOf<Int>()

    @Test
    fun `collect - should announce the count when it is above zero`() {
        // GIVEN snapshots that yield 3, 0, and 1 new notifications
        val counts = ArrayDeque(listOf(3, 0, 1))
        val recorder = DiffyNotificationsRecorder(
            snapshots = flowOf(browserSnapshot(), null, browserSnapshot(), browserSnapshot()),
            record = { counts.removeFirst() },
            announce = { count -> announced.add(count) }
        )

        // WHEN
        runBlocking { recorder.collect() }

        // THEN null snapshots are skipped and zero is not announced
        announced shouldBe listOf(3, 1)
    }

    @Test
    fun `collect - should keep going after a failure`() {
        // GIVEN a first snapshot that fails and a second that yields 2
        var calls = 0
        val recorder = DiffyNotificationsRecorder(
            snapshots = flowOf(browserSnapshot(), browserSnapshot()),
            record = {
                calls++
                if (calls == 1) error("boom") else 2
            },
            announce = { count -> announced.add(count) }
        )

        // WHEN
        runBlocking { recorder.collect() }

        // THEN
        assertSoftly {
            calls shouldBe 2
            announced shouldBe listOf(2)
        }
    }
}