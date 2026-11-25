package com.github.fnunezkanut.bbcloud.git

/**
 * Chooses which of `main` and `master` is the main branch of a repository. Pure logic, free of the IntelliJ platform.
 *
 * "Deduce the main branch from the last few commits" means this: when only one of the two names exists as a ref, that
 * one wins. When both exist, the ref names that decorate the last [RECENT_COMMIT_SCAN_COUNT] commits reachable from
 * `HEAD` (the `%D` output of `git log`, newest commit first) decide: the first commit that is decorated with `main`,
 * `master`, or a remote tracking ref of either, names the winner. When no recent commit mentions either, `main` wins.
 */
internal object MainBranchChooser {
    /** The branch names that count as the main branch, in order of preference. */
    val CANDIDATE_NAMES: List<String> = listOf("main", "master")

    /** How many commits of the history of `HEAD` the decoration scan looks at. */
    const val RECENT_COMMIT_SCAN_COUNT = 50

    private const val HEAD_ARROW_PREFIX = "HEAD -> "
    private const val TAG_PREFIX = "tag: "
    private const val DECORATION_SEPARATOR = ", "
    private const val REMOTE_SEPARATOR = '/'
    private const val DEFAULT_CANDIDATE = "main"

    /** True for the exact (trimmed, case-sensitive) names `main` and `master`. */
    fun isMainBranchName(branchName: String?): Boolean {
        return branchName != null && branchName.trim() in CANDIDATE_NAMES
    }

    /**
     * Returns the chosen name (`main` or `master`), or null when [existing] is empty.
     *
     * [existing] holds the candidate names that resolve to a ref. [recentDecorationLines] is only called when both
     * names exist, because it costs a git process. [remoteNames] are the repository remotes, used to recognize
     * `<remote>/main` decorations without mistaking a branch such as `feature/main` for the main branch.
     */
    fun choose(existing: List<String>, remoteNames: Set<String>, recentDecorationLines: () -> List<String>): String? {
        val candidates = CANDIDATE_NAMES.filter { name -> name in existing }
        if (candidates.size < 2) {
            return candidates.firstOrNull()
        }
        for (line in recentDecorationLines()) {
            val mentioned = parseDecorations(line = line, remoteNames = remoteNames)
            val winner = candidates.firstOrNull { name -> name in mentioned }
            if (winner != null) {
                return winner
            }
        }
        return DEFAULT_CANDIDATE
    }

    /**
     * Returns the candidate names (`main`, `master`) that one `%D` line mentions: the local branch, or the remote
     * tracking branch of one of [remoteNames]. Tags, the bare `HEAD`, and every other branch are ignored.
     */
    fun parseDecorations(line: String, remoteNames: Set<String>): Set<String> {
        val mentioned = mutableSetOf<String>()
        for (rawEntry in line.split(DECORATION_SEPARATOR)) {
            val entry = rawEntry.trim().removePrefix(HEAD_ARROW_PREFIX).trim()
            val name = candidateNameOf(entry = entry, remoteNames = remoteNames)
            if (name != null) {
                mentioned.add(name)
            }
        }
        return mentioned
    }

    private fun candidateNameOf(entry: String, remoteNames: Set<String>): String? {
        if (entry.isEmpty() || entry.startsWith(TAG_PREFIX) || entry == "HEAD") {
            return null
        }
        if (entry in CANDIDATE_NAMES) {
            return entry
        }
        val remote = entry.substringBefore(REMOTE_SEPARATOR, missingDelimiterValue = "")
        val branch = entry.substringAfter(REMOTE_SEPARATOR, missingDelimiterValue = "")
        if (remote in remoteNames && branch in CANDIDATE_NAMES) {
            return branch
        }
        return null
    }
}