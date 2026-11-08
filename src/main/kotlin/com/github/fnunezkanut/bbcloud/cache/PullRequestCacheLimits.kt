package com.github.fnunezkanut.bbcloud.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Retention and freshness settings of the pull request cache. The defaults are the production values; tests pass
 * smaller ones.
 */
data class PullRequestCacheLimits(
    /** How long a merged or declined pull request stays cached after it closed. */
    val closedRetention: Duration = 30.days,
    /** How long an open pull request stays cached after Bitbucket last reported it. */
    val staleOpenRetention: Duration = 30.days,
    /** The most entries kept. Eviction removes the oldest unpinned entries first. */
    val maxEntries: Int = 2000,
    /** How long an automatic load may use a listing snapshot without asking Bitbucket. */
    val listingFreshTtl: Duration = 60.seconds
)