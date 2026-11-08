package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestReviewer
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import kotlin.time.Duration
import kotlin.time.Instant

/** The reference instant used by the cache tests. */
val CACHE_TEST_START: Instant = Instant.parse("2026-11-01T10:00:00Z")

/** A clock the tests move by hand. */
class MutableTestClock(var now: Instant = CACHE_TEST_START) {
    fun read(): Instant {
        return now
    }

    fun advance(by: Duration) {
        now += by
    }
}

fun cacheTestSummary(
    id: Long = 1L,
    title: String = "Add the cache",
    state: PullRequestState = PullRequestState.Open,
    updatedOn: Instant = CACHE_TEST_START,
    authorUuid: String? = "{author}",
    reviewers: List<PullRequestReviewer> = emptyList(),
    approverUuids: Set<String> = emptySet(),
    commentCount: Int = 0
): PullRequestSummary {
    return PullRequestSummary(
        id = id,
        title = title,
        authorDisplayName = "Anna",
        authorAvatarUrl = "https://avatars.example/anna.png",
        createdOn = CACHE_TEST_START,
        updatedOn = updatedOn,
        sourceBranch = "feature/$id",
        destinationBranch = "main",
        commentCount = commentCount,
        webUrl = "https://bitbucket.example/workspace/repo/pull-requests/$id",
        state = state,
        isDraft = false,
        authorUuid = authorUuid,
        approverUuids = approverUuids,
        reviewers = reviewers,
        authorAccountId = "712020:anna"
    )
}

fun cacheTestReviewer(uuid: String = "{ben}", status: ReviewerStatus = ReviewerStatus.None): PullRequestReviewer {
    return PullRequestReviewer(
        uuid = uuid,
        displayName = "Ben",
        avatarUrl = "https://avatars.example/ben.png",
        status = status,
        participatedOn = CACHE_TEST_START,
        accountId = "712020:ben"
    )
}