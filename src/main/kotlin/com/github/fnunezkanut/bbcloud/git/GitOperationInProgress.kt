package com.github.fnunezkanut.bbcloud.git

import java.nio.file.Files
import java.nio.file.Path

private val IN_PROGRESS_MARKERS = listOf(
    "rebase-merge",
    "rebase-apply",
    "MERGE_HEAD",
    "CHERRY_PICK_HEAD",
    "REVERT_HEAD",
    "BISECT_LOG",
    "index.lock"
)

/**
 * Whether [gitDir] shows a rebase, merge, cherry-pick, revert, bisect or index lock in progress. [gitDir] is the git
 * directory of the clone (`<root>/.git` for a normal clone).
 */
fun isGitOperationInProgress(gitDir: Path): Boolean {
    return IN_PROGRESS_MARKERS.any { marker -> Files.exists(gitDir.resolve(marker)) }
}