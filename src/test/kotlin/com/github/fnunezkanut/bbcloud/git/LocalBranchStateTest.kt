package com.github.fnunezkanut.bbcloud.git

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class LocalBranchStateTest {
    private fun state(
        headRevision: String = "head1",
        localSourceRevision: String? = "src1",
        headDescendsFromLocalSource: Boolean = true,
        commitsAheadOfRemote: Int = 0,
        unpushedPaths: Set<String> = emptySet(),
        uncommittedPaths: Set<String> = emptySet()
    ): LocalBranchState {
        return LocalBranchState(
            remoteName = "origin",
            sourceBranch = "feature",
            headRevision = headRevision,
            localSourceRevision = localSourceRevision,
            remoteSourceRevision = "remote1",
            headDescendsFromLocalSource = headDescendsFromLocalSource,
            commitsAheadOfRemote = commitsAheadOfRemote,
            unpushedPaths = unpushedPaths,
            uncommittedPaths = uncommittedPaths
        )
    }

    @Nested
    inner class FileStatus {
        @Test
        fun `fileStatus - should report each of the four statuses`() {
            // GIVEN
            val state = state(
                commitsAheadOfRemote = 2,
                unpushedPaths = setOf("src/Pushed.kt", "src/Both.kt"),
                uncommittedPaths = setOf("src/Dirty.kt", "src/Both.kt")
            )

            // WHEN / THEN
            assertSoftly {
                state.fileStatus("src/Clean.kt") shouldBe LocalFileStatus.InPullRequest
                state.fileStatus("src/Pushed.kt") shouldBe LocalFileStatus.Unpushed
                state.fileStatus("src/Dirty.kt") shouldBe LocalFileStatus.Uncommitted
                state.fileStatus("src/Both.kt") shouldBe LocalFileStatus.UnpushedAndUncommitted
            }
        }

        @Test
        fun `fileStatus - should not report unpushed when no commit is ahead`() {
            val state = state(commitsAheadOfRemote = 0, unpushedPaths = setOf("src/A.kt"))

            state.fileStatus("src/A.kt") shouldBe LocalFileStatus.InPullRequest
        }

        @Test
        fun `fileStatus - should normalize separators and a leading dot slash`() {
            val state = state(uncommittedPaths = setOf("src/A.kt"))

            assertSoftly {
                state.fileStatus("src\\A.kt") shouldBe LocalFileStatus.Uncommitted
                state.fileStatus("./src/A.kt") shouldBe LocalFileStatus.Uncommitted
            }
        }
    }

    @Nested
    inner class AllowsEditableSourceSide {
        @Test
        fun `allowsEditableSourceSide - should allow when head equals the diff source in any case`() {
            val state = state(headRevision = "ABC123", headDescendsFromLocalSource = false)

            state.allowsEditableSourceSide(diffSourceRevision = "abc123") shouldBe true
        }

        @Test
        fun `allowsEditableSourceSide - should allow when head is ahead of the local source`() {
            val state = state(localSourceRevision = "src1", headDescendsFromLocalSource = true)

            state.allowsEditableSourceSide(diffSourceRevision = "src1") shouldBe true
        }

        @Test
        fun `allowsEditableSourceSide - should refuse when head is behind or diverged`() {
            val state = state(localSourceRevision = "src1", headDescendsFromLocalSource = false)

            state.allowsEditableSourceSide(diffSourceRevision = "src1") shouldBe false
        }

        @Test
        fun `allowsEditableSourceSide - should refuse a diff source other than the local source`() {
            val state = state(localSourceRevision = "src1", headDescendsFromLocalSource = true)

            state.allowsEditableSourceSide(diffSourceRevision = "other") shouldBe false
        }

        @Test
        fun `allowsEditableSourceSide - should refuse a blank revision`() {
            val state = state(headRevision = "")

            assertSoftly {
                state.allowsEditableSourceSide(diffSourceRevision = "") shouldBe false
                state().allowsEditableSourceSide(diffSourceRevision = " ") shouldBe false
            }
        }
    }
}