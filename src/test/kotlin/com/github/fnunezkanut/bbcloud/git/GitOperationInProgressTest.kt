package com.github.fnunezkanut.bbcloud.git

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("unit")
class GitOperationInProgressTest {
    @Test
    fun `isGitOperationInProgress - should be false for a plain git directory`(
        @TempDir gitDir: Path
    ) {
        isGitOperationInProgress(gitDir = gitDir) shouldBe false
    }

    @Test
    fun `isGitOperationInProgress - should be true for each marker`(
        @TempDir gitDir: Path
    ) {
        val markers = listOf("rebase-merge", "rebase-apply", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "index.lock")

        val results = markers.map { marker ->
            val dir = Files.createTempDirectory(gitDir, "git")
            Files.createFile(dir.resolve(marker))
            marker to isGitOperationInProgress(gitDir = dir)
        }

        results shouldBe markers.map { marker -> marker to true }
    }
}