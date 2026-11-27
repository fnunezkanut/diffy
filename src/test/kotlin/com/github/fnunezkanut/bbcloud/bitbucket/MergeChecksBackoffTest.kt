package com.github.fnunezkanut.bbcloud.bitbucket

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@Tag("unit")
class MergeChecksBackoffTest {
    private val timeSource = TestTimeSource()
    private val backoff = MergeChecksBackoff(timeSource = timeSource)

    @Test
    fun `isAllowed - should allow a request before any rate limit`() {
        // THEN
        assertSoftly {
            backoff.isAllowed() shouldBe true
            backoff.remaining() shouldBe Duration.ZERO
        }
    }

    @Test
    fun `onRateLimited - should wait thirty seconds after the first 429`() {
        // WHEN
        val delay = backoff.onRateLimited()

        // THEN
        assertSoftly {
            delay shouldBe 30.seconds
            backoff.isAllowed() shouldBe false
            backoff.remaining() shouldBe 30.seconds
        }
    }

    @Test
    fun `remaining - should count down with time and allow requests once the wait has passed`() {
        // GIVEN
        backoff.onRateLimited()

        // WHEN
        timeSource += 10.seconds

        // THEN
        backoff.remaining() shouldBe 20.seconds
        backoff.isAllowed() shouldBe false

        // WHEN
        timeSource += 20.seconds

        // THEN
        assertSoftly {
            backoff.remaining() shouldBe Duration.ZERO
            backoff.isAllowed() shouldBe true
        }
    }

    @Test
    fun `onRateLimited - should double the wait on every further 429`() {
        // WHEN
        val delays = List(4) { backoff.onRateLimited() }

        // THEN
        delays shouldBe listOf(30.seconds, 60.seconds, 120.seconds, 240.seconds)
    }

    @Test
    fun `onRateLimited - should cap the wait at five minutes`() {
        // WHEN
        val delays = List(7) { backoff.onRateLimited() }

        // THEN
        delays.takeLast(3) shouldBe listOf(5.minutes, 5.minutes, 5.minutes)
    }

    @Test
    fun `onSuccess - should end the wait and start again at thirty seconds`() {
        // GIVEN
        backoff.onRateLimited()
        backoff.onRateLimited()

        // WHEN
        backoff.onSuccess()
        val delay = backoff.onRateLimited()

        // THEN
        assertSoftly {
            delay shouldBe 30.seconds
            backoff.remaining() shouldBe 30.seconds
        }
    }

    @Test
    fun `onSuccess - should allow requests again at once`() {
        // GIVEN
        backoff.onRateLimited()

        // WHEN
        backoff.onSuccess()

        // THEN
        backoff.isAllowed() shouldBe true
    }
}