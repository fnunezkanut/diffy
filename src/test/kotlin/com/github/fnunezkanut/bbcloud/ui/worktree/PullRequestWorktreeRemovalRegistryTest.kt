package com.github.fnunezkanut.bbcloud.ui.worktree

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class PullRequestWorktreeRemovalRegistryTest {
    @AfterEach
    fun tearDown() {
        PullRequestWorktreeRemovalRegistry.resetForTests()
    }

    @Test
    fun `tryStartBulkRemoval - should allow only one run until it finishes`() {
        // GIVEN / WHEN
        val first = PullRequestWorktreeRemovalRegistry.tryStartBulkRemoval()
        val second = PullRequestWorktreeRemovalRegistry.tryStartBulkRemoval()
        val runningBefore = PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress()
        PullRequestWorktreeRemovalRegistry.finishBulkRemoval()

        // THEN
        assertSoftly {
            first shouldBe true
            second shouldBe false
            runningBefore shouldBe true
            PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress() shouldBe false
        }
    }

    @Test
    fun `tryMarkRemoving - should refuse the same normalized path twice until unmarked`() {
        // GIVEN
        val path = Path.of("/tmp/wt/repo--pr-1")
        val samePath = Path.of("/tmp/wt/x/../repo--pr-1")

        // WHEN
        val first = PullRequestWorktreeRemovalRegistry.tryMarkRemoving(path)
        val second = PullRequestWorktreeRemovalRegistry.tryMarkRemoving(samePath)
        val marked = PullRequestWorktreeRemovalRegistry.isRemoving(path)
        PullRequestWorktreeRemovalRegistry.unmarkRemoving(samePath)

        // THEN
        assertSoftly {
            first shouldBe true
            second shouldBe false
            marked shouldBe true
            PullRequestWorktreeRemovalRegistry.isRemoving(path) shouldBe false
        }
    }
}