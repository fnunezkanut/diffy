package com.github.fnunezkanut.bbcloud.git

import kotlin.time.Instant

/**
 * Names and pruning rules of the backup refs that "Rebase onto latest with AI" writes before the prompt is sent.
 *
 * Layout, per pull request:
 * - `refs/diffy/backups/pr-<id>/<epochMillis>`: the `HEAD` commit when the link was clicked.
 * - `refs/diffy/backups/pr-<id>/<epochMillis>-wip`: a `git stash create` commit of the uncommitted tracked changes,
 *   written only when there were some. Untracked files are not included.
 *
 * The refs live outside `refs/heads`, `refs/bisect`, `refs/worktree` and `refs/rewritten`, so git stores them in the
 * common git directory: every work tree of the clone shares them, and removing the pull request work tree keeps them.
 * `git push`, `--tags` and `--all` never push them, the IDE branch popup does not list them, and they keep the commit
 * reachable, so `git gc` does not delete it the way it expires reflog entries.
 */
internal object RebaseBackupRefs {
    const val NAMESPACE = "refs/diffy/backups"
    const val WIP_SUFFIX = "-wip"
    const val MAX_BACKUPS_PER_PULL_REQUEST = 5

    /** The ref prefix of one pull request. The trailing slash keeps `pr-1/` from matching `pr-12/`. */
    fun pullRequestPrefix(pullRequestId: Long): String {
        return "$NAMESPACE/pr-$pullRequestId/"
    }

    fun headRef(pullRequestId: Long, createdAt: Instant): String {
        return pullRequestPrefix(pullRequestId = pullRequestId) + createdAt.toEpochMilliseconds()
    }

    fun wipRef(headRef: String): String {
        return headRef + WIP_SUFFIX
    }

    /**
     * The refs of [pullRequestId] to delete so that only the [keep] newest snapshots remain. A snapshot is a head ref and
     * its `-wip` ref. Refs whose key is not a number were not written by Diffy and are never returned.
     */
    fun refsToPrune(pullRequestId: Long, existingRefs: List<String>, keep: Int = MAX_BACKUPS_PER_PULL_REQUEST): List<String> {
        val prefix = pullRequestPrefix(pullRequestId = pullRequestId)
        val refsBySnapshot = existingRefs
            .filter { ref -> ref.startsWith(prefix) }
            .mapNotNull { ref -> snapshotKey(ref = ref, prefix = prefix)?.let { key -> key to ref } }
            .groupBy(keySelector = { (key, _) -> key }, valueTransform = { (_, ref) -> ref })
        return refsBySnapshot.keys
            .sortedDescending()
            .drop(keep.coerceAtLeast(0))
            .flatMap { key -> refsBySnapshot.getValue(key) }
    }

    /**
     * The command that puts the work tree back to the backup. Without [wipRef] it is `git reset --hard <backupRef>`;
     * with it, `git reset --hard <backupRef> && git stash apply <wipRef>`, because `git stash apply` accepts the commit
     * written by `git stash create`. The ref names contain only `[a-z0-9/-]`, so they need no shell quoting.
     */
    fun restoreCommand(backupRef: String, wipRef: String? = null): String {
        val reset = "git reset --hard $backupRef"
        if (wipRef == null) {
            return reset
        }
        return "$reset && git stash apply $wipRef"
    }

    /** An all-zeros object id as long as [sha]. As the old value of `update-ref` it means "the ref must not exist yet". */
    fun zeroObjectId(sha: String): String {
        return "0".repeat(sha.length)
    }

    private fun snapshotKey(ref: String, prefix: String): Long? {
        return ref.removePrefix(prefix).removeSuffix(WIP_SUFFIX).toLongOrNull()
    }
}