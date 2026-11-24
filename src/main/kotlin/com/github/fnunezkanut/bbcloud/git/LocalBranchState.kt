package com.github.fnunezkanut.bbcloud.git

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestPathMatcher

/**
 * The state of the local source branch of a pull request work tree compared with its remote counterpart.
 *
 * Both the orange "not in the pull request yet" bar and the editable diff decision read this one state, so git runs
 * once and the two features never disagree.
 *
 * All paths are normalized with `PullRequestPathMatcher.normalize(PullRequestPathMatcher.relativeProjectPath(...))`,
 * the key the review bar and the viewed files use.
 *
 * @param remoteName the remote the pull request branch lives on.
 * @param sourceBranch the source branch of the pull request.
 * @param headRevision the commit the work tree is checked out on.
 * @param localSourceRevision the revision of the ref `loadPullRequestChanges` diffs against (local branch preferred),
 * or null when it does not resolve.
 * @param remoteSourceRevision the revision of `<remote>/<source>`.
 * @param headDescendsFromLocalSource whether [headRevision] is the local source revision or a descendant of it.
 * @param commitsAheadOfRemote how many commits [headRevision] is ahead of `<remote>/<source>`.
 * @param unpushedPaths the paths changed in `<remote>/<source>..HEAD`.
 * @param uncommittedPaths the paths that are changed, unversioned, or unsaved in the work tree.
 */
data class LocalBranchState(
    val remoteName: String,
    val sourceBranch: String,
    val headRevision: String,
    val localSourceRevision: String?,
    val remoteSourceRevision: String,
    val headDescendsFromLocalSource: Boolean,
    val commitsAheadOfRemote: Int,
    val unpushedPaths: Set<String>,
    val uncommittedPaths: Set<String>
) {
    /** The status of the file at [relativePath] (any separator style, optional leading `./`). */
    fun fileStatus(relativePath: String): LocalFileStatus {
        val path = PullRequestPathMatcher.normalize(relativePath)
        val unpushed = commitsAheadOfRemote > 0 && unpushedPaths.contains(path)
        val uncommitted = uncommittedPaths.contains(path)
        return when {
            unpushed && uncommitted -> LocalFileStatus.UnpushedAndUncommitted
            unpushed -> LocalFileStatus.Unpushed
            uncommitted -> LocalFileStatus.Uncommitted
            else -> LocalFileStatus.InPullRequest
        }
    }

    /**
     * Whether the work tree file may be shown as the right side of a diff whose source revision is
     * [diffSourceRevision].
     *
     * `HEAD` at or ahead of the source commit allows editing. `HEAD` behind the source commit, diverged from it, or on
     * an unrelated commit does not, because the local file would then miss pull request changes.
     */
    fun allowsEditableSourceSide(diffSourceRevision: String): Boolean {
        if (diffSourceRevision.isBlank() || headRevision.isBlank()) {
            return false
        }
        if (headRevision.equals(diffSourceRevision, ignoreCase = true)) {
            return true
        }
        return headDescendsFromLocalSource && diffSourceRevision.equals(localSourceRevision, ignoreCase = true)
    }
}