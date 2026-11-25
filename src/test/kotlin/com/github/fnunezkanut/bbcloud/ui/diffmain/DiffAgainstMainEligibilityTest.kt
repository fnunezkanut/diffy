package com.github.fnunezkanut.bbcloud.ui.diffmain

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffAgainstMainEligibilityTest {
    @Nested
    inner class IsOnFeatureBranch {
        @Test
        fun `isOnFeatureBranch - should be false for main, master, and a detached head`() {
            assertSoftly {
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = "main") shouldBe false
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = "master") shouldBe false
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = null) shouldBe false
            }
        }

        @Test
        fun `isOnFeatureBranch - should be true for any other branch`() {
            assertSoftly {
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = "feature/x") shouldBe true
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = "feature/main") shouldBe true
                DiffAgainstMainEligibility.isOnFeatureBranch(currentBranchName = "develop") shouldBe true
            }
        }
    }

    @Nested
    inner class IsAtMainCommit {
        @Test
        fun `isAtMainCommit - should be true for equal hashes ignoring letter case`() {
            assertSoftly {
                DiffAgainstMainEligibility.isAtMainCommit(currentRevision = "abc123", mainCommitHash = "abc123") shouldBe true
                DiffAgainstMainEligibility.isAtMainCommit(currentRevision = "ABC123", mainCommitHash = "abc123") shouldBe true
            }
        }

        @Test
        fun `isAtMainCommit - should be false for different hashes or a missing hash`() {
            assertSoftly {
                DiffAgainstMainEligibility.isAtMainCommit(currentRevision = "abc123", mainCommitHash = "def456") shouldBe false
                DiffAgainstMainEligibility.isAtMainCommit(currentRevision = null, mainCommitHash = "abc123") shouldBe false
                DiffAgainstMainEligibility.isAtMainCommit(currentRevision = "abc123", mainCommitHash = null) shouldBe false
            }
        }
    }

    @Nested
    inner class IsEligibleFileKind {
        @Test
        fun `isEligibleFileKind - should accept only a local, non-directory, non-binary file`() {
            assertSoftly {
                DiffAgainstMainEligibility.isEligibleFileKind(isInLocalFileSystem = true, isDirectory = false, isBinary = false) shouldBe true
                DiffAgainstMainEligibility.isEligibleFileKind(isInLocalFileSystem = false, isDirectory = false, isBinary = false) shouldBe false
                DiffAgainstMainEligibility.isEligibleFileKind(isInLocalFileSystem = true, isDirectory = true, isBinary = false) shouldBe false
                DiffAgainstMainEligibility.isEligibleFileKind(isInLocalFileSystem = true, isDirectory = false, isBinary = true) shouldBe false
            }
        }
    }
}