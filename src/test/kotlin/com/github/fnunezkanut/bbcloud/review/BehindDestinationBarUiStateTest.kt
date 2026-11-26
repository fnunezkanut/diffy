package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.bitbucket.ItemCount
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class BehindDestinationBarUiStateTest {
    private fun stateOf(state: PullRequestState = PullRequestState.Open, commitsBehind: ItemCount?): BehindDestinationBarUiState? {
        return behindDestinationBarStateOf(
            pullRequestId = 42L,
            state = state,
            commitsBehind = commitsBehind,
            sourceBranch = "feature/foo",
            destinationBranch = "main"
        )
    }

    @Test
    fun `behindDestinationBarStateOf - should return null for a pull request that is not open`() {
        // GIVEN
        val commitsBehind = ItemCount(value = 3, isTruncated = false)

        // WHEN
        val merged = stateOf(state = PullRequestState.Merged, commitsBehind = commitsBehind)
        val declined = stateOf(state = PullRequestState.Declined, commitsBehind = commitsBehind)

        // THEN
        assertSoftly {
            merged.shouldBeNull()
            declined.shouldBeNull()
        }
    }

    @Test
    fun `behindDestinationBarStateOf - should return null when the count is unknown`() {
        // GIVEN
        val commitsBehind: ItemCount? = null

        // WHEN
        val state = stateOf(commitsBehind = commitsBehind)

        // THEN
        state.shouldBeNull()
    }

    @Test
    fun `behindDestinationBarStateOf - should return null when the pull request is up to date`() {
        // GIVEN
        val commitsBehind = ItemCount(value = 0, isTruncated = false)

        // WHEN
        val state = stateOf(commitsBehind = commitsBehind)

        // THEN
        state.shouldBeNull()
    }

    @Test
    fun `behindDestinationBarStateOf - should build the singular message for one commit`() {
        // GIVEN
        val commitsBehind = ItemCount(value = 1, isTruncated = false)

        // WHEN
        val state = stateOf(commitsBehind = commitsBehind).shouldNotBeNull()

        // THEN
        assertSoftly(state) {
            pullRequestId shouldBe 42L
            message shouldBe """This pull request is 1 commit behind "main"."""
            tooltip shouldBe
                """Bitbucket reports that "main" has commits that are not in this pull request. """ +
                """Rebase the source branch "feature/foo" onto it to review and merge against the latest code."""
            sourceBranch shouldBe "feature/foo"
            destinationBranch shouldBe "main"
        }
    }

    @Test
    fun `behindDestinationBarStateOf - should build the plural message for two commits`() {
        // GIVEN
        val commitsBehind = ItemCount(value = 2, isTruncated = false)

        // WHEN
        val state = stateOf(commitsBehind = commitsBehind).shouldNotBeNull()

        // THEN
        state.message shouldBe """This pull request is 2 commits behind "main"."""
    }

    @Test
    fun `behindDestinationBarStateOf - should show the truncated count when Bitbucket capped it`() {
        // GIVEN
        val commitsBehind = ItemCount(value = 100, isTruncated = true)

        // WHEN
        val state = stateOf(commitsBehind = commitsBehind).shouldNotBeNull()

        // THEN
        state.message shouldBe """This pull request is 100+ commits behind "main"."""
    }
}