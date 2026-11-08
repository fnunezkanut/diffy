package com.github.fnunezkanut.bbcloud.cache

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PullRequestCacheKeyTest {
    @Test
    fun `of - should lowercase and trim the workspace and repository`() {
        // WHEN a key is built from mixed-case names
        val key = PullRequestCacheKey.of(workspace = " AcmeCo ", repoSlug = "Payments-API", prNum = 12L)

        // THEN the key is normalized
        key shouldBe PullRequestCacheKey(workspace = "acmeco", repoSlug = "payments-api", prNum = 12L)
    }

    @Test
    fun `encode then decode - should round trip the key`() {
        // GIVEN a key
        val key = PullRequestCacheKey.of(workspace = "workspace", repoSlug = "repo", prNum = 99L)

        // THEN decoding its encoded form returns it
        PullRequestCacheKey.decode(encoded = key.encode()) shouldBe key
    }

    @Test
    fun `decode - should return null for malformed values`() {
        PullRequestCacheKey.decode(encoded = "no-separators").shouldBeNull()
        PullRequestCacheKey.decode(encoded = "workspace/repo#").shouldBeNull()
        PullRequestCacheKey.decode(encoded = "workspace/repo#abc").shouldBeNull()
        PullRequestCacheKey.decode(encoded = "/repo#4").shouldBeNull()
    }

    @Test
    fun `isWatchedBy - should match the repository slug case-insensitively`() {
        // GIVEN a key for a mixed-case repository
        val key = PullRequestCacheKey.of(workspace = "workspace", repoSlug = "Payments", prNum = 1L)

        // THEN the watched set matches regardless of case, and an unwatched set does not
        key.isWatchedBy(watchedRepoSlugs = setOf("PAYMENTS")).shouldBeTrue()
        key.isWatchedBy(watchedRepoSlugs = setOf("billing")).shouldBeFalse()
        key.isWatchedBy(watchedRepoSlugs = emptySet()).shouldBeFalse()
    }
}