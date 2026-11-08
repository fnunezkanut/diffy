package com.github.fnunezkanut.bbcloud.external

fun browserReviewer(uuid: String? = "{ben}", approved: Boolean = false, state: String? = null, displayName: String = "Ben"): DiffyBrowserReviewer {
    return DiffyBrowserReviewer(
        displayName = displayName,
        accountId = "712020:ben",
        uuid = uuid,
        state = state,
        approved = approved,
        participatedOn = null
    )
}

fun browserPullRequest(
    id: Long = 1L,
    workspace: String = "workspace",
    repoSlug: String = "repo",
    title: String = "Add the cache",
    state: String = "OPEN",
    draft: Boolean = false,
    createdOn: String? = "2026-11-01T10:00:00+00:00",
    updatedOn: String? = "2026-11-01T10:00:00+00:00",
    commentCount: Int = 0,
    authorDisplayName: String? = "Anna",
    sourceBranch: String? = "feature/1",
    destinationBranch: String? = "main",
    reviewers: List<DiffyBrowserReviewer> = emptyList(),
    otherParticipants: List<DiffyBrowserReviewer> = emptyList()
): DiffyBrowserPullRequest {
    return DiffyBrowserPullRequest(
        id = id,
        workspace = workspace,
        repoSlug = repoSlug,
        repoFullName = "$workspace/$repoSlug",
        title = title,
        titleTags = emptyList(),
        jiraKeys = emptyList(),
        jiraKeySource = null,
        url = "https://bitbucket.org/$workspace/$repoSlug/pull-requests/$id",
        state = state,
        draft = draft,
        authorDisplayName = authorDisplayName,
        sourceBranch = sourceBranch,
        destinationBranch = destinationBranch,
        createdOn = createdOn,
        updatedOn = updatedOn,
        commentCount = commentCount,
        taskCount = 0,
        approvalCount = 0,
        changesRequestedCount = 0,
        myReviewState = null,
        reviewers = reviewers,
        otherParticipants = otherParticipants
    )
}

fun browserEvent(
    id: String = "event-1",
    occurredAt: String = "2026-11-06T17:30:44.112Z",
    category: DiffyBrowserEventCategory = DiffyBrowserEventCategory.HUMAN_COMMENT,
    readState: String? = "unread",
    message: String? = "A message",
    actorDisplayName: String? = "Filip",
    actorIsBot: Boolean = false,
    actorAccountId: String? = null,
    pullRequestUrl: String? = null,
    pullRequestId: Long? = 772L,
    pullRequestRepoSlug: String? = "my-repo",
    url: String? = "https://bitbucket.org/ws/my-repo/pull-requests/772"
): DiffyBrowserNotificationEvent {
    return DiffyBrowserNotificationEvent(
        id = id,
        occurredAt = occurredAt,
        category = category,
        templateId = null,
        readState = readState,
        message = message,
        actorDisplayName = actorDisplayName,
        actorAccountId = actorAccountId,
        actorIsBot = actorIsBot,
        actorBotMatchedOn = null,
        pullRequestId = pullRequestId,
        pullRequestRepoSlug = pullRequestRepoSlug,
        pullRequestTitle = "Title of the pull request",
        pullRequestUrl = pullRequestUrl,
        commentId = null,
        onDiff = false,
        url = url,
        groupId = null,
        groupSize = 1
    )
}

fun browserSnapshot(
    pullRequests: List<DiffyBrowserPullRequest> = emptyList(),
    events: List<DiffyBrowserNotificationEvent> = emptyList(),
    capturedAt: String = "2026-11-08T10:00:00.000Z",
    truncatedPullRequests: Boolean = false
): DiffyBrowserSnapshot {
    return DiffyBrowserSnapshot(
        schemaVersion = 1,
        meta = DiffyBrowserSnapshotMeta(
            capturedAt = capturedAt,
            workspaces = listOf("workspace"),
            skippedNonBitbucket = 0,
            truncatedPullRequests = truncatedPullRequests
        ),
        pullRequests = pullRequests,
        notificationEvents = events,
        skippedInvalidEntries = 0
    )
}