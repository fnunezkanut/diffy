package com.github.fnunezkanut.bbcloud.cache

/**
 * Identifies one pull request in the cache. The workspace and repository slug are lowercased so that lookups ignore
 * case. The original-case values live on the cached entry itself (see [CachedPullRequest]).
 *
 * Build keys with [of]. A key is only a lookup value: never derive a checkout path or a repository from it.
 */
data class PullRequestCacheKey(val workspace: String, val repoSlug: String, val prNum: Long) {
    /** Encodes the key as `workspace/repoSlug#prNum` for persisted rows and map keys. */
    fun encode(): String {
        return "$workspace/$repoSlug#$prNum"
    }

    /** True when [watchedRepoSlugs] names this key's repository, compared case-insensitively. */
    fun isWatchedBy(watchedRepoSlugs: Set<String>): Boolean {
        return watchedRepoSlugs.any { slug -> slug.trim().equals(other = repoSlug, ignoreCase = true) }
    }

    companion object {
        /** Builds a key with the workspace and repository slug trimmed and lowercased. */
        fun of(workspace: String, repoSlug: String, prNum: Long): PullRequestCacheKey {
            return PullRequestCacheKey(
                workspace = workspace.trim().lowercase(),
                repoSlug = repoSlug.trim().lowercase(),
                prNum = prNum
            )
        }

        /** Parses a value produced by [encode]. Returns null when the shape is wrong. */
        fun decode(encoded: String): PullRequestCacheKey? {
            val slash = encoded.indexOf('/')
            val hash = encoded.lastIndexOf('#')
            if (slash <= 0 || hash <= slash + 1 || hash == encoded.length - 1) {
                return null
            }
            val prNum = encoded.substring(hash + 1).toLongOrNull() ?: return null
            return PullRequestCacheKey(
                workspace = encoded.substring(0, slash),
                repoSlug = encoded.substring(slash + 1, hash),
                prNum = prNum
            )
        }
    }
}