package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.git.MainBranchSkipReason
import com.github.fnunezkanut.bbcloud.git.MainBranchSyncStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The text the Watched Projects tab shows for the result of "Auto-update main branches". */
object MainBranchSyncText {
    /** A short age such as "just now", "2 min ago", "3 h ago" or "2 d ago". A [then] after [now] reads as "just now". */
    fun formatRelative(now: Instant, then: Instant): String {
        val age = now - then
        return when {
            age < 1.minutes -> DiffyBundle.message("workspace.watchedProjects.mainSync.justNow")
            age < 1.hours -> DiffyBundle.message("workspace.watchedProjects.mainSync.minutesAgo", age.inWholeMinutes)
            age < 1.days -> DiffyBundle.message("workspace.watchedProjects.mainSync.hoursAgo", age.inWholeHours)
            else -> DiffyBundle.message("workspace.watchedProjects.mainSync.daysAgo", age.inWholeDays)
        }
    }

    /** True for results that need the user's attention (shown as a warning icon with a tooltip). */
    fun isWarning(status: MainBranchSyncStatus): Boolean {
        return status !is MainBranchSyncStatus.Updated && status !is MainBranchSyncStatus.UpToDate
    }

    /** The one-line text of an [MainBranchSyncStatus.Updated] or [MainBranchSyncStatus.UpToDate] result. */
    fun summary(status: MainBranchSyncStatus, now: Instant): String {
        val age = formatRelative(now = now, then = status.checkedAt)
        return when (status) {
            is MainBranchSyncStatus.Updated -> DiffyBundle.message("workspace.watchedProjects.mainSync.updated", status.branch, age)
            is MainBranchSyncStatus.UpToDate -> DiffyBundle.message("workspace.watchedProjects.mainSync.upToDate", status.branch, age)
            else -> warning(status = status)
        }
    }

    /** The full message of a result that needs attention, used as the tooltip of the warning icon. */
    fun warning(status: MainBranchSyncStatus): String {
        return when (status) {
            is MainBranchSyncStatus.Skipped -> {
                skipMessage(status = status)
            }
            is MainBranchSyncStatus.FetchFailed -> {
                DiffyBundle.message("workspace.watchedProjects.mainSync.fetchFailed", status.branch) +
                    " " + DiffyBundle.message("workspace.watchedProjects.mainSync.fetchFailed.hint")
            }
            is MainBranchSyncStatus.NoMainBranch -> {
                DiffyBundle.message("workspace.watchedProjects.mainSync.noMainBranch")
            }
            is MainBranchSyncStatus.Updated -> {
                DiffyBundle.message("workspace.watchedProjects.mainSync.tooltip")
            }
            is MainBranchSyncStatus.UpToDate -> {
                DiffyBundle.message("workspace.watchedProjects.mainSync.tooltip")
            }
        }
    }

    private fun skipMessage(status: MainBranchSyncStatus.Skipped): String {
        val key = when (status.reason) {
            MainBranchSkipReason.UncommittedChanges -> "workspace.watchedProjects.mainSync.skipped.uncommitted"
            MainBranchSkipReason.LocalCommitsNotOnRemote -> "workspace.watchedProjects.mainSync.skipped.localCommits"
            MainBranchSkipReason.CheckedOutInOtherWorktree -> "workspace.watchedProjects.mainSync.skipped.otherWorktree"
            MainBranchSkipReason.GitOperationInProgress -> "workspace.watchedProjects.mainSync.skipped.gitOperation"
            MainBranchSkipReason.StateUnknown -> "workspace.watchedProjects.mainSync.skipped.stateUnknown"
        }
        return DiffyBundle.message(key, status.branch, status.path?.toString().orEmpty())
    }
}