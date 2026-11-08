package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import kotlin.time.Instant

/** What kind of Bitbucket notification a browser event is. Wire names are snake_case. */
enum class DiffyBrowserEventCategory(val wireName: String) {
    REVIEWER_ADDED("reviewer_added"),
    APPROVAL("approval"),
    BOT_REVIEW_COMMENT("bot_review_comment"),
    HUMAN_COMMENT("human_comment"),
    MENTION("mention"),
    PR_UPDATED("pr_updated"),
    MERGED("merged"),
    OTHER_BITBUCKET("other_bitbucket");

    companion object {
        /** Maps a snake_case wire name to its entry. Unknown or missing names map to [OTHER_BITBUCKET]. */
        fun fromWireName(name: String?): DiffyBrowserEventCategory {
            return entries.firstOrNull { category -> category.wireName == name } ?: OTHER_BITBUCKET
        }
    }
}

/** One person on a pull request, as seen by the browser extension. */
data class DiffyBrowserReviewer(
    val displayName: String,
    val accountId: String?,
    val uuid: String?,
    val state: String?,
    val approved: Boolean,
    val participatedOn: String?
)

/** One pull request the current user is reviewing. Timestamps stay ISO strings; see [DiffyBrowserTimestamps]. */
data class DiffyBrowserPullRequest(
    val id: Long,
    val workspace: String,
    val repoSlug: String,
    val repoFullName: String,
    val title: String,
    val titleTags: List<String>,
    val jiraKeys: List<String>,
    val jiraKeySource: String?,
    val url: String,
    val state: String,
    val draft: Boolean,
    val authorDisplayName: String?,
    val sourceBranch: String?,
    val destinationBranch: String?,
    val createdOn: String?,
    val updatedOn: String?,
    val commentCount: Int,
    val taskCount: Int,
    val approvalCount: Int,
    val changesRequestedCount: Int,
    val myReviewState: String?,
    val reviewers: List<DiffyBrowserReviewer>,
    val otherParticipants: List<DiffyBrowserReviewer>
)

/** The plugin state of this pull request. Null for `SUPERSEDED` and for values the extension may add later. */
fun DiffyBrowserPullRequest.pullRequestState(): PullRequestState? {
    return when (state.trim().uppercase()) {
        "OPEN" -> PullRequestState.Open
        "MERGED" -> PullRequestState.Merged
        "DECLINED" -> PullRequestState.Declined
        else -> null
    }
}

/** One classified Bitbucket notification. Comment text is never part of it. */
data class DiffyBrowserNotificationEvent(
    val id: String,
    val occurredAt: String,
    val category: DiffyBrowserEventCategory,
    val templateId: String?,
    val readState: String?,
    val message: String?,
    val actorDisplayName: String?,
    val actorAccountId: String?,
    val actorIsBot: Boolean,
    val actorBotMatchedOn: String?,
    val pullRequestId: Long?,
    val pullRequestRepoSlug: String?,
    val pullRequestTitle: String?,
    val pullRequestUrl: String?,
    val commentId: Long?,
    val onDiff: Boolean,
    val url: String?,
    val groupId: String?,
    val groupSize: Int
)

/**
 * The diagnostic header of a snapshot. [truncatedPullRequests] is true when the extension stopped paging before it saw
 * every pull request, so a pull request missing from [DiffyBrowserSnapshot.pullRequests] may still exist.
 */
data class DiffyBrowserSnapshotMeta(
    val capturedAt: String,
    val workspaces: List<String>,
    val skippedNonBitbucket: Int,
    val truncatedPullRequests: Boolean = false
)

/**
 * The latest capture sent by the Chrome extension. [skippedInvalidEntries] counts array entries that were dropped
 * because required fields were missing.
 */
data class DiffyBrowserSnapshot(
    val schemaVersion: Int,
    val meta: DiffyBrowserSnapshotMeta,
    val pullRequests: List<DiffyBrowserPullRequest>,
    val notificationEvents: List<DiffyBrowserNotificationEvent>,
    val skippedInvalidEntries: Int
)

/** Parses the two timestamp shapes the extension sends. */
object DiffyBrowserTimestamps {
    /**
     * Parses `2026-11-07T19:06:10.612576+00:00` (pull requests) and `2026-11-06T17:30:44.112Z` (events). Returns null
     * when [value] is missing or not ISO-8601.
     */
    fun parseOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) {
            return null
        }
        return try {
            Instant.parse(value)
        } catch (exception: IllegalArgumentException) {
            null
        }
    }
}