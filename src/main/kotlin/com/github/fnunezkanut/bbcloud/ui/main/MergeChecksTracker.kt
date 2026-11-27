package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.CustomCheckPhase
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck

/**
 * What the Checks card and the Merge button are drawn from after a change: the checks including the Git check, the last
 * successful result to fall back on, and where the deferred Git request stands.
 */
data class MergeChecksSnapshot(val result: MergeChecksResult?, val previous: MergeChecksResult.Loaded?, val gitStatus: GitRowStatus)

/**
 * Keeps the mergeability checks of the one pull request the page shows: the checks read with the overview (without the Git
 * check), the last of them that loaded, and the Git check that a separate, slower request adds. Every `on*` method ignores a
 * pull request id that is not the shown one and returns null then, so a late response for another pull request changes nothing.
 */
class MergeChecksTracker {
    private var pullRequestId: Long? = null
    private var current: MergeChecksResult? = null
    private var lastLoaded: MergeChecksResult.Loaded? = null
    private var gitCheck: PullRequestMergeCheck.GitCheck? = null
    private var gitStatus = GitRowStatus.NotLoaded

    /** Starts tracking [pullRequestId]; the state of another pull request is dropped, that of the same one is kept for an in-place refresh. */
    fun bind(pullRequestId: Long) {
        if (this.pullRequestId == pullRequestId) {
            return
        }
        this.pullRequestId = pullRequestId
        current = null
        lastLoaded = null
        gitCheck = null
        gitStatus = GitRowStatus.NotLoaded
    }

    /** Takes the checks of a freshly loaded overview. It always returns a snapshot, because the overview renders anyway. */
    fun onOverview(pullRequestId: Long, result: MergeChecksResult?): MergeChecksSnapshot {
        bind(pullRequestId)
        accept(result)
        return snapshot()
    }

    /** Takes checks re-read in the background; null when [result] equals the current one, so nothing needs to render. */
    fun onRefresh(pullRequestId: Long, result: MergeChecksResult): MergeChecksSnapshot? {
        if (pullRequestId != this.pullRequestId || result == current) {
            return null
        }
        accept(result)
        return snapshot()
    }

    /** Marks the Git request as running; a Git check from an earlier request stays visible meanwhile. */
    fun onGitLoading(pullRequestId: Long): MergeChecksSnapshot? {
        if (pullRequestId != this.pullRequestId) {
            return null
        }
        gitStatus = GitRowStatus.Loading
        return snapshot()
    }

    /** A failed Git request leaves the Git check as it was, so it cannot block or unblock Merge. */
    fun onGit(pullRequestId: Long, result: MergeChecksResult): MergeChecksSnapshot? {
        if (pullRequestId != this.pullRequestId) {
            return null
        }
        when (result) {
            is MergeChecksResult.Loaded -> {
                gitCheck = result.checks.filterIsInstance<PullRequestMergeCheck.GitCheck>().firstOrNull()
                gitStatus = GitRowStatus.Loaded
            }
            is MergeChecksResult.Unavailable -> {
                gitStatus = GitRowStatus.Failed
            }
        }
        return snapshot()
    }

    fun gitStatus(): GitRowStatus {
        return gitStatus
    }

    /** True while a check can still change on its own. A pending on-merge check is excluded: it stays pending until the merge. */
    fun hasPendingCheck(): Boolean {
        val checks = lastLoaded?.checks ?: return false
        return checks.any { check -> check.status == MergeCheckStatus.Pending && !check.isOnMerge() }
    }

    private fun PullRequestMergeCheck.isOnMerge(): Boolean {
        return this is PullRequestMergeCheck.CustomCheck && phase == CustomCheckPhase.OnMerge
    }

    private fun accept(result: MergeChecksResult?) {
        current = result
        if (result is MergeChecksResult.Loaded) {
            lastLoaded = result
        }
    }

    private fun snapshot(): MergeChecksSnapshot {
        return MergeChecksSnapshot(
            result = current?.let { result -> withGit(result) },
            previous = lastLoaded?.let { loaded -> loadedWithGit(loaded) },
            gitStatus = gitStatus
        )
    }

    private fun withGit(result: MergeChecksResult): MergeChecksResult {
        return when (result) {
            is MergeChecksResult.Loaded -> loadedWithGit(result)
            is MergeChecksResult.Unavailable -> result
        }
    }

    private fun loadedWithGit(loaded: MergeChecksResult.Loaded): MergeChecksResult.Loaded {
        val others = loaded.checks.filter { check -> check !is PullRequestMergeCheck.GitCheck }
        return MergeChecksResult.Loaded(others + listOfNotNull(gitCheck))
    }
}