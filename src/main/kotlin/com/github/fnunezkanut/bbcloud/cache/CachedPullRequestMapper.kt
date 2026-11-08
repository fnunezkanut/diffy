package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestReviewer
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import kotlin.time.Instant

/**
 * Pure conversions between the Bitbucket summary model and the cached model. [toSummary] is the inverse of
 * [fromSummary]: a round trip returns an equal [PullRequestSummary].
 */
object CachedPullRequestMapper {
    fun fromSummary(workspace: String, repoSlug: String, summary: PullRequestSummary, fetchedAt: Instant, closedOn: Instant?): CachedPullRequest {
        return CachedPullRequest(
            key = PullRequestCacheKey.of(workspace = workspace, repoSlug = repoSlug, prNum = summary.id),
            workspace = workspace,
            repoSlug = repoSlug,
            lastFetchedAt = fetchedAt,
            author = authorOf(summary = summary),
            title = summary.title,
            state = summary.state,
            isDraft = summary.isDraft,
            createdOn = summary.createdOn,
            updatedOn = summary.updatedOn,
            closedOn = closedOn,
            sourceBranch = summary.sourceBranch,
            destinationBranch = summary.destinationBranch,
            commentCount = summary.commentCount,
            webUrl = summary.webUrl,
            reviewers = summary.reviewers.map { reviewer -> cachedReviewerOf(reviewer = reviewer) },
            approverUuids = summary.approverUuids
        )
    }

    fun toSummary(entry: CachedPullRequest): PullRequestSummary {
        return PullRequestSummary(
            id = entry.key.prNum,
            title = entry.title,
            authorDisplayName = entry.author?.name.orEmpty(),
            authorAvatarUrl = entry.author?.avatarUrl,
            createdOn = entry.createdOn,
            updatedOn = entry.updatedOn,
            sourceBranch = entry.sourceBranch,
            destinationBranch = entry.destinationBranch,
            commentCount = entry.commentCount,
            webUrl = entry.webUrl,
            state = entry.state,
            isDraft = entry.isDraft,
            authorUuid = entry.author?.uuid,
            approverUuids = entry.approverUuids,
            reviewers = entry.reviewers.map { reviewer -> pullRequestReviewerOf(reviewer = reviewer) },
            authorAccountId = entry.author?.accountId
        )
    }

    /** Pairs the entry with its original-case repository, as the Workspace tab expects. */
    fun toReviewingPullRequest(entry: CachedPullRequest): ReviewingPullRequest {
        return ReviewingPullRequest(
            summary = toSummary(entry = entry),
            workspace = entry.workspace,
            repoSlug = entry.repoSlug
        )
    }

    private fun authorOf(summary: PullRequestSummary): CachedPerson? {
        val hasAuthorData = summary.authorDisplayName.isNotEmpty() ||
            summary.authorUuid != null ||
            summary.authorAccountId != null ||
            summary.authorAvatarUrl != null
        if (!hasAuthorData) {
            return null
        }
        return CachedPerson(
            name = summary.authorDisplayName,
            uuid = summary.authorUuid,
            accountId = summary.authorAccountId,
            avatarUrl = summary.authorAvatarUrl
        )
    }

    private fun cachedReviewerOf(reviewer: PullRequestReviewer): CachedReviewer {
        return CachedReviewer(
            name = reviewer.displayName,
            uuid = reviewer.uuid,
            accountId = reviewer.accountId,
            avatarUrl = reviewer.avatarUrl,
            approved = reviewer.status == ReviewerStatus.Approved,
            needsWork = reviewer.status == ReviewerStatus.ChangesRequested,
            participatedOn = reviewer.participatedOn
        )
    }

    private fun pullRequestReviewerOf(reviewer: CachedReviewer): PullRequestReviewer {
        return PullRequestReviewer(
            uuid = reviewer.uuid,
            displayName = reviewer.name,
            avatarUrl = reviewer.avatarUrl,
            status = statusOf(reviewer = reviewer),
            participatedOn = reviewer.participatedOn,
            accountId = reviewer.accountId
        )
    }

    private fun statusOf(reviewer: CachedReviewer): ReviewerStatus {
        return when {
            reviewer.approved -> ReviewerStatus.Approved
            reviewer.needsWork -> ReviewerStatus.ChangesRequested
            else -> ReviewerStatus.None
        }
    }
}