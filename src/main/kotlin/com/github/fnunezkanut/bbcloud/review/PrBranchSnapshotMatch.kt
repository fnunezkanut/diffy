package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot

/**
 * Whether [snapshot] describes the pull request with the given branches. A missing or blank branch name never matches,
 * so a snapshot is ignored until the pull request branches are known. A leading `<remote>/` is ignored on both names.
 */
fun snapshotMatchesBranches(snapshot: PrBranchSyncSnapshot, sourceBranch: String?, destinationBranch: String?): Boolean {
    if (sourceBranch.isNullOrBlank() || destinationBranch.isNullOrBlank()) {
        return false
    }
    val prefix = "${snapshot.remoteName}/"
    val source = snapshot.sourceBranch == sourceBranch.trim().removePrefix(prefix)
    val destination = snapshot.destinationBranch == destinationBranch.trim().removePrefix(prefix)
    return source && destination
}