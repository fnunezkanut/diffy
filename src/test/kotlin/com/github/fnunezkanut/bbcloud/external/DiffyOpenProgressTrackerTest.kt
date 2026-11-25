package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyOpenProgressTrackerTest {
    private var now = 1_000L
    private val tracker = DiffyOpenProgressTracker(clock = { now })

    @Test
    fun `progressFor - should report opening after markOpening`() {
        // GIVEN
        tracker.markOpening(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // WHEN
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // THEN
        progress shouldBe DiffyOpenProgress(state = DiffyOpenProgressState.OPENING, message = null)
    }

    @Test
    fun `progressFor - should report opened after markOpened replaces opening`() {
        // GIVEN
        tracker.markOpening(workspace = "acme", repoSlug = "repo", prNumber = 7)
        tracker.markOpened(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // WHEN
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // THEN
        progress shouldBe DiffyOpenProgress(state = DiffyOpenProgressState.OPENED, message = null)
    }

    @Test
    fun `progressFor - should keep the failure message`() {
        // GIVEN
        tracker.markOpening(workspace = "acme", repoSlug = "repo", prNumber = 7)
        tracker.markFailed(workspace = "acme", repoSlug = "repo", prNumber = 7, message = "No local clone")

        // WHEN
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // THEN
        progress shouldBe DiffyOpenProgress(state = DiffyOpenProgressState.FAILED, message = "No local clone")
    }

    @Test
    fun `progressFor - should return null for an unknown pull request`() {
        // GIVEN
        tracker.markOpening(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // WHEN
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 8)

        // THEN
        progress shouldBe null
    }

    @Test
    fun `progressFor - should return null once the entry is older than the expiry`() {
        // GIVEN
        tracker.markOpened(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // WHEN
        now += DiffyOpenProgressTracker.EXPIRY_MS + 1
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // THEN
        progress shouldBe null
    }

    @Test
    fun `progressFor - should still answer exactly at the expiry`() {
        // GIVEN
        tracker.markOpened(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // WHEN
        now += DiffyOpenProgressTracker.EXPIRY_MS
        val progress = tracker.progressFor(workspace = "acme", repoSlug = "repo", prNumber = 7)

        // THEN
        progress shouldBe DiffyOpenProgress(state = DiffyOpenProgressState.OPENED, message = null)
    }

    @Test
    fun `progressFor - should keep pull requests of different repositories apart`() {
        // GIVEN
        tracker.markOpening(workspace = "acme", repoSlug = "repo-a", prNumber = 7)
        tracker.markFailed(workspace = "acme", repoSlug = "repo-b", prNumber = 7, message = "boom")

        // WHEN
        val first = tracker.progressFor(workspace = "acme", repoSlug = "repo-a", prNumber = 7)
        val second = tracker.progressFor(workspace = "acme", repoSlug = "repo-b", prNumber = 7)

        // THEN
        assertSoftly {
            first?.state shouldBe DiffyOpenProgressState.OPENING
            second?.state shouldBe DiffyOpenProgressState.FAILED
        }
    }
}