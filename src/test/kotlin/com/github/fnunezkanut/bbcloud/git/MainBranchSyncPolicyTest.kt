package com.github.fnunezkanut.bbcloud.git

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class MainBranchSyncPolicyTest {
    private val root: Path = Path.of("/projects/repo")

    private fun behind(ahead: Int = 0, checkedOutAt: Path? = null, dirty: Boolean? = null): BranchSyncStatus.Behind {
        return BranchSyncStatus.Behind(
            commitsBehind = 3,
            commitsAhead = ahead,
            localRevision = "old",
            remoteRevision = "new",
            checkedOutAt = checkedOutAt,
            checkedOutWorktreeDirty = dirty
        )
    }

    private fun decide(status: BranchSyncStatus, gitOperationInProgress: Boolean = false): PrBranchSyncAction {
        return MainBranchSyncPolicy.decide(status = status, cloneRoot = root, gitOperationInProgress = gitOperationInProgress)
    }

    @Test
    fun `decide - git operation in progress should skip even a missing local branch`() {
        decide(status = BranchSyncStatus.MissingLocal(remoteRevision = "new"), gitOperationInProgress = true) shouldBe
            PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown)
    }

    @Test
    fun `decide - missing local branch should create a tracking branch`() {
        decide(status = BranchSyncStatus.MissingLocal(remoteRevision = "new")) shouldBe PrBranchSyncAction.CreateLocalBranch
    }

    @Test
    fun `decide - up to date, no remote branch and unknown should do nothing`() {
        listOf(BranchSyncStatus.UpToDate(commitsAhead = 2), BranchSyncStatus.NoRemoteBranch, BranchSyncStatus.Unknown)
            .map { status -> decide(status = status) } shouldBe List(3) { PrBranchSyncAction.None }
    }

    @Test
    fun `decide - diverged branch should be left alone`() {
        decide(status = behind(ahead = 1)) shouldBe PrBranchSyncAction.Skip(PrBranchSyncSkipReason.LocalCommitsNotOnRemote)
    }

    @Test
    fun `decide - behind and not checked out should update the ref with a compare and swap`() {
        decide(status = behind()) shouldBe PrBranchSyncAction.UpdateRef(newRevision = "new", oldRevision = "old")
    }

    @Test
    fun `decide - checked out in the clone root with unknown state should skip`() {
        decide(status = behind(checkedOutAt = root, dirty = null)) shouldBe
            PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown)
    }

    @Test
    fun `decide - checked out in the clone root with changes should skip`() {
        decide(status = behind(checkedOutAt = root, dirty = true)) shouldBe
            PrBranchSyncAction.Skip(PrBranchSyncSkipReason.UncommittedChanges)
    }

    @Test
    fun `decide - checked out in the clone root and clean should fast-forward there`() {
        decide(status = behind(checkedOutAt = root, dirty = false)) shouldBe
            PrBranchSyncAction.MergeFastForwardInWorktree(worktreePath = root)
    }

    @Test
    fun `decide - checked out in another work tree should skip even when it is clean`() {
        decide(status = behind(checkedOutAt = Path.of("/work/repo-pr-7"), dirty = false)) shouldBe
            PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutInOtherWorktree)
    }
}