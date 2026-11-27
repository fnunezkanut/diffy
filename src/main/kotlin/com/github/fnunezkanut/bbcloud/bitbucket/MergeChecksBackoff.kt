package com.github.fnunezkanut.bbcloud.bitbucket

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Spaces out the mergeability requests after Bitbucket answers HTTP 429 (its mergeability-specific rate limit). The wait starts
 * at [initialDelay], doubles on every further 429, stops growing at [maxDelay], and resets on the next success.
 *
 * The caller asks [isAllowed] before a request and reports the outcome through [onRateLimited] or [onSuccess]. Its methods are
 * synchronized, because the editor session reads it from the EDT and from its poller.
 */
class MergeChecksBackoff(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val initialDelay: Duration = INITIAL_DELAY,
    private val maxDelay: Duration = MAX_DELAY
) {
    private var nextAllowed: ComparableTimeMark? = null
    private var nextDelay: Duration = initialDelay

    /** True when no wait is pending, so a request may be sent now. */
    @Synchronized
    fun isAllowed(): Boolean {
        return remaining() == Duration.ZERO
    }

    /** The time left before the next request may be sent; zero when none is pending. */
    @Synchronized
    fun remaining(): Duration {
        val allowedAt = nextAllowed ?: return Duration.ZERO
        val left = -allowedAt.elapsedNow()
        return if (left.isPositive()) left else Duration.ZERO
    }

    /** Records an HTTP 429 and returns how long to wait before the next request. */
    @Synchronized
    fun onRateLimited(): Duration {
        val delay = nextDelay
        nextAllowed = timeSource.markNow() + delay
        nextDelay = minOf(delay * 2, maxDelay)
        return delay
    }

    /** Forgets earlier 429s: the next one waits [initialDelay] again. */
    @Synchronized
    fun onSuccess() {
        nextAllowed = null
        nextDelay = initialDelay
    }

    companion object {
        val INITIAL_DELAY: Duration = 30.seconds
        val MAX_DELAY: Duration = 5.minutes
    }
}