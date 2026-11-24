package com.github.fnunezkanut.bbcloud.git

/**
 * Whether the right side of pull request diffs may be the editable work tree file.
 *
 * The caller decides [allowed] (work tree and setting checks) and passes the [localBranchState], so the `git` package
 * stays free of UI and settings code.
 *
 * @param allowed false keeps the committed content on the right side.
 * @param localBranchState the state used to decide whether `HEAD` is at or ahead of the source commit; null when the
 * remote source ref is unknown.
 */
data class EditableSourceSide(val allowed: Boolean, val localBranchState: LocalBranchState?) {
    companion object {
        val DISABLED = EditableSourceSide(allowed = false, localBranchState = null)
    }
}