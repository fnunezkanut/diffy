package com.github.fnunezkanut.bbcloud.git

/** How a file of a pull request work tree relates to the pull request on Bitbucket. */
enum class LocalFileStatus {
    /** The file has no local change that is missing from the pull request. */
    InPullRequest,

    /** The file has local commits that are not pushed to the pull request branch yet. */
    Unpushed,

    /** The file has uncommitted changes (changed, unversioned, or unsaved). */
    Uncommitted,

    /** The file has both unpushed commits and uncommitted changes. */
    UnpushedAndUncommitted
}