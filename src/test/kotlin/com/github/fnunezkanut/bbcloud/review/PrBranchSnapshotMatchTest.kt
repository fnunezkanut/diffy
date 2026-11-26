package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.BranchSyncStatus
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class PrBranchSnapshotMatchTest {
    private val snapshot = PrBranchSyncSnapshot(
        remoteName = "origin",
        sourceBranch = "feature/foo",
        destinationBranch = "main",
        prWorktreePath = Path.of("/tmp/.worktrees/repo--pr-9"),
        source = BranchSyncStatus.UpToDate(commitsAhead = 0),
        destination = BranchSyncStatus.UpToDate(commitsAhead = 0)
    )

    @Test
    fun `snapshotMatchesBranches - should be false while a banner branch is unknown`() {
        // GIVEN branches that are null or blank
        // WHEN
        val nullSource = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = null, destinationBranch = "main")
        val blankDestination = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = "feature/foo", destinationBranch = " ")
        val bothNull = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = null, destinationBranch = null)

        // THEN
        listOf(nullSource, blankDestination, bothNull) shouldBe listOf(false, false, false)
    }

    @Test
    fun `snapshotMatchesBranches - should strip the remote prefix and trim`() {
        // GIVEN
        val source = " origin/feature/foo "
        val destination = "origin/main"

        // WHEN
        val matches = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = source, destinationBranch = destination)

        // THEN
        matches shouldBe true
    }

    @Test
    fun `snapshotMatchesBranches - should be false for other branches`() {
        // GIVEN
        val other = "feature/bar"

        // WHEN
        val matches = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = other, destinationBranch = "main")

        // THEN
        matches shouldBe false
    }

    @Test
    fun `snapshotMatchesBranches - should be true for the same branches`() {
        // GIVEN
        // WHEN
        val matches = snapshotMatchesBranches(snapshot = snapshot, sourceBranch = "feature/foo", destinationBranch = "main")

        // THEN
        matches shouldBe true
    }
}