package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketAccountUuid
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestReviewer
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequest
import com.github.fnunezkanut.bbcloud.cache.CachedReviewer
import kotlin.time.Instant

/**
 * Pure rules that fold a pull request of the Chrome extension snapshot into the pull request cache. The snapshot never
 * moves `lastFetchedAt`, the author, the branches, the web URL or the creation time of an existing entry.
 */
object DiffyBrowserSnapshotCacheMerge {
    private const val CHANGES_REQUESTED = "changes_requested"

    /**
     * Returns [existing] updated with [incoming], or null when nothing changes. A snapshot older than the entry changes
     * nothing. With an equal `updatedOn` the snapshot may only raise the comment count. A strictly newer snapshot wins.
     */
    fun merge(existing: CachedPullRequest, incoming: DiffyBrowserPullRequest): CachedPullRequest? {
        val incomingUpdatedOn = DiffyBrowserTimestamps.parseOrNull(value = incoming.updatedOn) ?: return null
        val merged = when {
            incomingUpdatedOn < existing.updatedOn -> existing
            incomingUpdatedOn == existing.updatedOn -> raiseCommentCount(existing = existing, incoming = incoming)
            else -> applyNewer(existing = existing, incoming = incoming, incomingUpdatedOn = incomingUpdatedOn)
        }
        return if (merged == existing) null else merged
    }

    /** Builds the summary of a pull request the cache does not know yet, or null when the snapshot lacks a required field. */
    fun toSummary(incoming: DiffyBrowserPullRequest): PullRequestSummary? {
        if (stateOf(wire = incoming.state) != PullRequestState.Open) {
            return null
        }
        val createdOn = DiffyBrowserTimestamps.parseOrNull(value = incoming.createdOn) ?: return null
        val updatedOn = DiffyBrowserTimestamps.parseOrNull(value = incoming.updatedOn) ?: return null
        val author = incoming.authorDisplayName
        val sourceBranch = incoming.sourceBranch
        val destinationBranch = incoming.destinationBranch
        if (author.isNullOrBlank() || sourceBranch.isNullOrBlank() || destinationBranch.isNullOrBlank()) {
            return null
        }
        return PullRequestSummary(
            id = incoming.id,
            title = incoming.title,
            authorDisplayName = author,
            authorAvatarUrl = null,
            createdOn = createdOn,
            updatedOn = updatedOn,
            sourceBranch = sourceBranch,
            destinationBranch = destinationBranch,
            commentCount = incoming.commentCount,
            webUrl = incoming.url,
            state = PullRequestState.Open,
            isDraft = incoming.draft,
            approverUuids = approverUuidsOf(incoming = incoming),
            reviewers = incoming.reviewers.mapNotNull { reviewer -> toReviewer(reviewer = reviewer) }
        )
    }

    private fun raiseCommentCount(existing: CachedPullRequest, incoming: DiffyBrowserPullRequest): CachedPullRequest {
        return if (incoming.commentCount > existing.commentCount) {
            existing.copy(commentCount = incoming.commentCount)
        } else {
            existing
        }
    }

    private fun applyNewer(existing: CachedPullRequest, incoming: DiffyBrowserPullRequest, incomingUpdatedOn: Instant): CachedPullRequest {
        val state = stateOf(wire = incoming.state) ?: existing.state
        return existing.copy(
            title = incoming.title,
            isDraft = incoming.draft,
            commentCount = incoming.commentCount,
            updatedOn = incomingUpdatedOn,
            state = state,
            closedOn = closedOnFor(existing = existing, state = state, incomingUpdatedOn = incomingUpdatedOn),
            reviewers = existing.reviewers.map { reviewer -> updatedReviewer(reviewer = reviewer, incoming = incoming) },
            approverUuids = mergedApprovers(existing = existing, incoming = incoming)
        )
    }

    private fun closedOnFor(existing: CachedPullRequest, state: PullRequestState, incomingUpdatedOn: Instant): Instant? {
        return when {
            state == PullRequestState.Open -> null
            existing.closedOn != null -> existing.closedOn
            else -> incomingUpdatedOn
        }
    }

    /** Replaces the approvers only when the snapshot carries participants, so a bare snapshot cannot erase them. */
    private fun mergedApprovers(existing: CachedPullRequest, incoming: DiffyBrowserPullRequest): Set<String> {
        val hasParticipants = incoming.reviewers.isNotEmpty() || incoming.otherParticipants.isNotEmpty()
        return if (hasParticipants) approverUuidsOf(incoming = incoming) else existing.approverUuids
    }

    private fun approverUuidsOf(incoming: DiffyBrowserPullRequest): Set<String> {
        return (incoming.reviewers + incoming.otherParticipants)
            .filter { participant -> participant.approved }
            .mapNotNull { participant -> participant.uuid }
            .toSet()
    }

    private fun updatedReviewer(reviewer: CachedReviewer, incoming: DiffyBrowserPullRequest): CachedReviewer {
        val match = incoming.reviewers.firstOrNull { candidate ->
            BitbucketAccountUuid.matches(first = candidate.uuid, second = reviewer.uuid)
        } ?: return reviewer
        return reviewer.copy(
            approved = match.approved,
            needsWork = match.state.equals(other = CHANGES_REQUESTED, ignoreCase = true)
        )
    }

    private fun toReviewer(reviewer: DiffyBrowserReviewer): PullRequestReviewer? {
        val uuid = reviewer.uuid ?: return null
        val status = when {
            reviewer.approved -> ReviewerStatus.Approved
            reviewer.state.equals(other = CHANGES_REQUESTED, ignoreCase = true) -> ReviewerStatus.ChangesRequested
            else -> ReviewerStatus.None
        }
        return PullRequestReviewer(
            uuid = uuid,
            displayName = reviewer.displayName,
            avatarUrl = null,
            status = status,
            participatedOn = DiffyBrowserTimestamps.parseOrNull(value = reviewer.participatedOn),
            accountId = reviewer.accountId
        )
    }

    /** Maps the wire state to the plugin state. Null for `SUPERSEDED` and unknown values. */
    private fun stateOf(wire: String): PullRequestState? {
        return when (wire.trim().uppercase()) {
            "OPEN" -> PullRequestState.Open
            "MERGED" -> PullRequestState.Merged
            "DECLINED" -> PullRequestState.Declined
            else -> null
        }
    }
}