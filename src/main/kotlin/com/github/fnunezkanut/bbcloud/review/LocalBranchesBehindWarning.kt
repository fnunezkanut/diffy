package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.git.BranchSyncStatus
import com.github.fnunezkanut.bbcloud.git.PrBranchFastForwardPolicy
import com.github.fnunezkanut.bbcloud.git.PrBranchRole
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncAction
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncMode
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSkipReason
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot

/**
 * Builds the state of the red bar from a [PrBranchSyncSnapshot]. Pure apart from the message bundle, so it is
 * unit-testable. Returns null when there is nothing to warn about.
 */
object LocalBranchesBehindWarning {
    fun from(snapshot: PrBranchSyncSnapshot?): LocalBehindRemoteBarUiState? {
        if (snapshot == null) {
            return null
        }
        val behindRoles = PrBranchRole.entries.filter { role -> snapshot.statusFor(role) is BranchSyncStatus.Behind }
        if (behindRoles.isEmpty()) {
            return null
        }
        val reasons = behindRoles.mapNotNull { role -> reasonFor(snapshot = snapshot, role = role) }
        val tooltip = (listOf(DiffyBundle.message("worktree.localBehindBar.explanation")) + reasons).joinToString(separator = "\n")
        return LocalBehindRemoteBarUiState(
            message = messageFor(snapshot = snapshot, roles = behindRoles),
            linkText = linkTextFor(roles = behindRoles),
            tooltip = tooltip,
            roles = behindRoles
        )
    }

    private fun linkTextFor(roles: List<PrBranchRole>): String {
        val key = if (roles.size == 1) "worktree.localBehindBar.syncOne" else "worktree.localBehindBar.syncBoth"
        return DiffyBundle.message(key)
    }

    private fun messageFor(snapshot: PrBranchSyncSnapshot, roles: List<PrBranchRole>): String {
        if (roles.size == 1) {
            val role = roles.single()
            val branch = snapshot.branchFor(role)
            return DiffyBundle.message(
                "worktree.localBehindBar.single",
                branch,
                commitsBehind(snapshot = snapshot, role = role),
                "${snapshot.remoteName}/$branch"
            )
        }
        return DiffyBundle.message(
            "worktree.localBehindBar.both",
            snapshot.remoteName,
            snapshot.branchFor(PrBranchRole.Source),
            commitsBehind(snapshot = snapshot, role = PrBranchRole.Source),
            snapshot.branchFor(PrBranchRole.Destination),
            commitsBehind(snapshot = snapshot, role = PrBranchRole.Destination)
        )
    }

    private fun commitsBehind(snapshot: PrBranchSyncSnapshot, role: PrBranchRole): Int {
        return (snapshot.statusFor(role) as BranchSyncStatus.Behind).commitsBehind
    }

    private fun reasonFor(snapshot: PrBranchSyncSnapshot, role: PrBranchRole): String? {
        val status = snapshot.statusFor(role) as? BranchSyncStatus.Behind ?: return null
        val action = PrBranchFastForwardPolicy.decide(
            role = role,
            status = status,
            prWorktreePath = snapshot.prWorktreePath,
            mode = PrBranchSyncMode.Automatic
        )
        val skip = action as? PrBranchSyncAction.Skip ?: return null
        val branch = snapshot.branchFor(role)
        val path = status.checkedOutAt?.toString().orEmpty()
        return when (skip.reason) {
            PrBranchSyncSkipReason.UncommittedChanges -> {
                DiffyBundle.message("worktree.localBehindBar.reason.uncommitted", branch, path)
            }
            PrBranchSyncSkipReason.LocalCommitsNotOnRemote -> {
                DiffyBundle.message("worktree.localBehindBar.reason.diverged", branch, status.commitsAhead)
            }
            PrBranchSyncSkipReason.CheckedOutInOtherWorktree -> {
                DiffyBundle.message("worktree.localBehindBar.reason.otherWorktree", branch, path)
            }
            PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown -> {
                null
            }
        }
    }
}