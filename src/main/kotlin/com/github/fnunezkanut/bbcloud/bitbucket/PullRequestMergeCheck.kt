package com.github.fnunezkanut.bbcloud.bitbucket

enum class MergeCheckStatus {
    Passed,
    Failed,
    Pending,
    Skipped,
    Unknown
}

/** The pull request state reported by a [PullRequestMergeCheck.StateCheck]. */
enum class MergeCheckPullRequestState {
    Open,
    Draft,
    Merged,
    Declined,
    Superseded,
    Unknown
}

/** The `reason` of a Git mergeability check; absent from the response when the check status is unknown. */
enum class GitMergeabilityReason {
    Clean,
    Conflicts,
    MergeImpossible
}

/** Whether a custom check runs before the merge button is enabled or when the merge is requested. */
enum class CustomCheckPhase {
    PreMerge,
    OnMerge
}

/**
 * One entry of the mergeability checks endpoint.
 *
 * [isRequired] is the response's `required` flag and [isBlocking] its `blocking` flag. [isBlocking] decides whether
 * merging is prevented, so a failed check is not necessarily blocking.
 */
sealed interface PullRequestMergeCheck {
    val status: MergeCheckStatus
    val isRequired: Boolean
    val isBlocking: Boolean

    /** The pull request state check; a queued pull request reports [MergeCheckPullRequestState.Open] and blocks. */
    data class StateCheck(
        val state: MergeCheckPullRequestState,
        override val status: MergeCheckStatus,
        override val isRequired: Boolean,
        override val isBlocking: Boolean
    ) : PullRequestMergeCheck

    /** Write access, branch restrictions, and whether the source repository still exists. */
    data class PermissionCheck(override val status: MergeCheckStatus, override val isRequired: Boolean, override val isBlocking: Boolean) :
        PullRequestMergeCheck

    /** [conflictsUrl] is the API link to the pull request's conflicts; Bitbucket sends it only while the check blocks. */
    data class GitCheck(
        val reason: GitMergeabilityReason?,
        val conflictsUrl: String?,
        override val status: MergeCheckStatus,
        override val isRequired: Boolean,
        override val isBlocking: Boolean
    ) : PullRequestMergeCheck

    /**
     * A branch restriction identified by the Bitbucket `check.kind`. [requiredValue] and [observedValue] are the first
     * integer values of Bitbucket's `requirement` and `observed` objects. For this type [isRequired] means merge check
     * enforcement is active for the repository, not that this check is mandatory.
     */
    data class StandardCheck(
        val kind: String,
        val requiredValue: Int?,
        val observedValue: Int?,
        override val status: MergeCheckStatus,
        override val isRequired: Boolean,
        override val isBlocking: Boolean
    ) : PullRequestMergeCheck

    /** A check reported by an app. On-merge checks can be known but not yet triggered. */
    data class CustomCheck(
        val phase: CustomCheckPhase,
        val id: String?,
        val name: String?,
        val message: String?,
        override val status: MergeCheckStatus,
        override val isRequired: Boolean,
        override val isBlocking: Boolean
    ) : PullRequestMergeCheck

    /** [isRequired] says the destination branch requires queueing; [queueState] is the raw merge queue state. */
    data class QueueCheck(
        val isQueued: Boolean,
        val queueName: String?,
        val queueState: String?,
        override val status: MergeCheckStatus,
        override val isRequired: Boolean,
        override val isBlocking: Boolean
    ) : PullRequestMergeCheck

    /** A check type this version does not know; its [isBlocking] flag still counts toward the Merge state. */
    data class UnrecognizedCheck(val type: String, override val status: MergeCheckStatus, override val isRequired: Boolean, override val isBlocking: Boolean) :
        PullRequestMergeCheck
}