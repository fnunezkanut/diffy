package com.github.fnunezkanut.bbcloud.git

import java.nio.file.Path
import kotlin.time.Instant

/** Why the background job left the local main branch of a watched project alone. */
enum class MainBranchSkipReason {
    UncommittedChanges,
    LocalCommitsNotOnRemote,
    CheckedOutInOtherWorktree,
    GitOperationInProgress,
    StateUnknown
}

/**
 * The last result of the "Auto-update main branches" job for one watched project, shown on the Watched Projects tab.
 * A local main branch that is ahead of Bitbucket is reported as [UpToDate]: the reviewer only cares that nothing is
 * behind.
 */
sealed interface MainBranchSyncStatus {
    /** The main branch name, or null when it is unknown. */
    val branch: String?

    /** When this result was produced. */
    val checkedAt: Instant

    /** The local main branch was fast-forwarded by [commits] commits (0 when it was only created). */
    data class Updated(override val branch: String, val commits: Int, override val checkedAt: Instant) : MainBranchSyncStatus

    /** The local main branch had nothing to fetch. */
    data class UpToDate(override val branch: String, override val checkedAt: Instant) : MainBranchSyncStatus

    /** The local main branch is behind, but it was not safe to update it. [path] is the work tree that blocks it. */
    data class Skipped(override val branch: String, val reason: MainBranchSkipReason, val path: Path?, override val checkedAt: Instant) : MainBranchSyncStatus

    /** The fetch from Bitbucket failed. */
    data class FetchFailed(override val branch: String, override val checkedAt: Instant) : MainBranchSyncStatus

    /** The main branch of the repository could not be determined. */
    data class NoMainBranch(override val checkedAt: Instant) : MainBranchSyncStatus {
        override val branch: String?
            get() = null
    }
}