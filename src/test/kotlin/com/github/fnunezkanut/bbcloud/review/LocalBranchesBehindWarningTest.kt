package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.BranchSyncStatus
import com.github.fnunezkanut.bbcloud.git.PrBranchRole
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class LocalBranchesBehindWarningTest {
    private val prWorktree: Path = Path.of("/tmp/.worktrees/repo--pr-9")
    private val explanation = "The diff, line counts and files in this window may not match the pull request on Bitbucket."
    private val upToDate = BranchSyncStatus.UpToDate(commitsAhead = 0)

    private fun behind(commits: Int = 2, ahead: Int = 0, checkedOutAt: Path? = null, dirty: Boolean? = null): BranchSyncStatus.Behind {
        return BranchSyncStatus.Behind(
            commitsBehind = commits,
            commitsAhead = ahead,
            localRevision = "old",
            remoteRevision = "new",
            checkedOutAt = checkedOutAt,
            checkedOutWorktreeDirty = dirty
        )
    }

    private fun snapshot(source: BranchSyncStatus, destination: BranchSyncStatus): PrBranchSyncSnapshot {
        return PrBranchSyncSnapshot(
            remoteName = "origin",
            sourceBranch = "feature/foo",
            destinationBranch = "main",
            prWorktreePath = prWorktree,
            source = source,
            destination = destination
        )
    }

    @Test
    fun `from - should return null for a null snapshot`() {
        LocalBranchesBehindWarning.from(null).shouldBeNull()
    }

    @Test
    fun `from - should return null when neither branch is behind`() {
        // GIVEN
        val snapshot = snapshot(source = upToDate, destination = BranchSyncStatus.MissingLocal(remoteRevision = "r"))

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot)

        // THEN
        state.shouldBeNull()
    }

    @Test
    fun `from - should describe only the source branch`() {
        // GIVEN
        val snapshot = snapshot(source = behind(), destination = upToDate)

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        assertSoftly(state) {
            message shouldBe "Local branch \"feature/foo\" is 2 commits behind origin/feature/foo. Sync it so you review what is on Bitbucket."
            linkText shouldBe "Sync local branch"
            roles shouldBe listOf(PrBranchRole.Source)
            updateInProgress shouldBe false
        }
    }

    @Test
    fun `from - should use the singular for one commit behind`() {
        // GIVEN
        val snapshot = snapshot(source = upToDate, destination = behind(commits = 1))

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        assertSoftly(state) {
            message shouldBe "Local branch \"main\" is 1 commit behind origin/main. Sync it so you review what is on Bitbucket."
            roles shouldBe listOf(PrBranchRole.Destination)
        }
    }

    @Test
    fun `from - should list both branches with the source first`() {
        // GIVEN
        val snapshot = snapshot(source = behind(), destination = behind(commits = 1))

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        assertSoftly(state) {
            message shouldBe
                "Local branches are behind origin: \"feature/foo\" by 2 commits, \"main\" by 1 commit. " +
                "Sync them so you review what is on Bitbucket."
            linkText shouldBe "Sync local branches"
            roles shouldBe listOf(PrBranchRole.Source, PrBranchRole.Destination)
        }
    }

    @Test
    fun `from - should explain uncommitted changes in the work tree`() {
        // GIVEN
        val snapshot = snapshot(source = behind(checkedOutAt = prWorktree, dirty = true), destination = upToDate)

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        state.tooltip shouldBe "$explanation\n\"feature/foo\" was not updated automatically: $prWorktree has uncommitted changes."
    }

    @Test
    fun `from - should explain local commits that are not on Bitbucket`() {
        // GIVEN
        val single = snapshot(source = behind(ahead = 1), destination = upToDate)
        val several = snapshot(source = behind(ahead = 3), destination = upToDate)

        // WHEN
        val singleState = LocalBranchesBehindWarning.from(single).shouldNotBeNull()
        val severalState = LocalBranchesBehindWarning.from(several).shouldNotBeNull()

        // THEN
        assertSoftly {
            singleState.tooltip shouldBe "$explanation\n\"feature/foo\" was not updated automatically: 1 local commit is not on Bitbucket."
            severalState.tooltip shouldBe "$explanation\n\"feature/foo\" was not updated automatically: 3 local commits are not on Bitbucket."
        }
    }

    @Test
    fun `from - should explain a branch checked out in another work tree`() {
        // GIVEN
        val other = Path.of("/tmp/projects/repo")
        val snapshot = snapshot(source = upToDate, destination = behind(checkedOutAt = other, dirty = false))

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        state.tooltip shouldBe "$explanation\n\"main\" was not updated automatically: it is checked out in $other."
    }

    @Test
    fun `from - should add no reason line when the branch would be updated automatically`() {
        // GIVEN
        val snapshot = snapshot(source = behind(), destination = upToDate)

        // WHEN
        val state = LocalBranchesBehindWarning.from(snapshot).shouldNotBeNull()

        // THEN
        assertSoftly(state) {
            tooltip shouldBe explanation
            tooltip shouldStartWith "The diff, line counts"
        }
    }
}