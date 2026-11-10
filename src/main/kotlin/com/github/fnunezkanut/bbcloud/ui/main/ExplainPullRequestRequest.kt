package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState

/** The pull request facts the "Explain this PR with AI" prompt needs. No IntelliJ types, so it can be unit tested. */
data class ExplainPullRequestRequest(
    val pullRequestId: Long,
    val title: String,
    val webUrl: String,
    val sourceBranch: String,
    val destinationBranch: String,
    val authorDisplayName: String,
    val state: PullRequestState,
    val isDraft: Boolean,
    val description: String?
)