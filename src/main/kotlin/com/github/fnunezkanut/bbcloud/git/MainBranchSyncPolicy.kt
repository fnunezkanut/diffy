package com.github.fnunezkanut.bbcloud.git

import java.nio.file.Path

/**
 * The pure rules that decide how the background job may bring the local main branch of a watched clone in line with
 * `<remote>/<main>`. It is stricter than [PrBranchFastForwardPolicy]: it never touches a work tree other than the clone
 * root, because the user did not ask for the update.
 */
object MainBranchSyncPolicy {
    /**
     * Decides what to do about the main branch of the clone at [cloneRoot]. When [gitOperationInProgress] is true the
     * result is always a skip; the caller reports it as [MainBranchSkipReason.GitOperationInProgress].
     */
    fun decide(status: BranchSyncStatus, cloneRoot: Path, gitOperationInProgress: Boolean): PrBranchSyncAction {
        if (gitOperationInProgress) {
            return PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown)
        }
        if (status is BranchSyncStatus.MissingLocal) {
            return PrBranchSyncAction.CreateLocalBranch
        }
        if (status !is BranchSyncStatus.Behind) {
            return PrBranchSyncAction.None
        }
        if (status.isDiverged) {
            return PrBranchSyncAction.Skip(PrBranchSyncSkipReason.LocalCommitsNotOnRemote)
        }
        val checkedOutAt = status.checkedOutAt?.toAbsolutePath()?.normalize()
            ?: return PrBranchSyncAction.UpdateRef(newRevision = status.remoteRevision, oldRevision = status.localRevision)
        if (checkedOutAt != cloneRoot.toAbsolutePath().normalize()) {
            return PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutInOtherWorktree)
        }
        return when (status.checkedOutWorktreeDirty) {
            null -> PrBranchSyncAction.Skip(PrBranchSyncSkipReason.CheckedOutWorktreeStateUnknown)
            true -> PrBranchSyncAction.Skip(PrBranchSyncSkipReason.UncommittedChanges)
            false -> PrBranchSyncAction.MergeFastForwardInWorktree(worktreePath = checkedOutAt)
        }
    }
}