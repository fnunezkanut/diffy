package com.github.fnunezkanut.bbcloud.bitbucket

/** Why the mergeability checks could not be read. */
enum class MergeChecksFailure {
    /** HTTP 500: custom check results or queue configuration are unavailable; a real merge would fail the same way. */
    CustomChecksUnavailable,

    /** HTTP 429: the mergeability-specific rate limit was hit. */
    RateLimited,

    /** Any other status, a network failure, or an unparseable body. */
    Other
}

/** Which mergeability checks a request asks for; see [BitbucketPullRequestResponseParser.buildMergeabilityChecksUrl]. */
enum class MergeChecksFilter {
    /** `q=type!="git_mergeability_check"`: every check except the slow Git check. */
    WithoutGit,

    /** `q=check.kind!="maximum_commits_behind"`: includes the Git check; the caller keeps only the [PullRequestMergeCheck.GitCheck]. */
    GitOnly
}

sealed interface MergeChecksResult {
    data class Loaded(val checks: List<PullRequestMergeCheck>) : MergeChecksResult

    data class Unavailable(val reason: MergeChecksFailure) : MergeChecksResult
}