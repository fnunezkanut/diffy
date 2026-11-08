package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.ui.state.ToggleableState
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryUrls
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserEventCategory
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserNotificationEvent
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserPullRequest
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserTimestamps
import com.github.fnunezkanut.bbcloud.external.DiffyStoredNotification
import com.github.fnunezkanut.bbcloud.external.pullRequestState
import com.github.fnunezkanut.bbcloud.ui.main.PullRequestRelativeTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * One notification event, with display ready values. [occurredAt] is null when the extension sent a timestamp that
 * does not parse; such a row keeps the raw text in [timeText] and sorts last.
 */
data class NotificationRow(
    val id: String,
    val occurredAt: Instant?,
    val timeText: String,
    val category: DiffyBrowserEventCategory,
    val typeLabel: String,
    /** The event message, or the type label when the event has none, so the title link is never empty. */
    val eventTitle: String,
    /** The title of the pull request, or [eventTitle] when no title is known, so the title link is never empty. */
    val pullRequestTitle: String,
    val actorText: String,
    val actorAvatarUrl: String?,
    /** The configured workspace; the pull request of the event can still live in another one, see [isForeignWorkspace]. */
    val workspace: String?,
    /** The workspace named by the event's own pull request link, or null when the event has no usable link. */
    val urlWorkspace: String?,
    val repoSlug: String?,
    val pullRequestId: Long?,
    /** `#772`, or empty when the event has no pull request. */
    val pullRequestNumberText: String,
    /** The https Bitbucket page of the pull request, or null when it cannot be derived. */
    val pullRequestWebUrl: String?,
    /** True when the event's own link names another workspace than the configured one. */
    val isForeignWorkspace: Boolean,
    /** The state the latest snapshot reports for the pull request, or null when it does not list it. */
    val knownPullRequestState: PullRequestState?,
    /** The read flag the plugin keeps; unread by default. The `readState` Bitbucket reports is ignored. */
    val isUnread: Boolean,
    /** The Bitbucket link of the event. The UI only uses [pullRequestWebUrl], which is derived from validated parts. */
    val url: String?
)

data class NotificationsUiState(
    val rows: List<NotificationRow>,
    /** True once there is something to show: stored notifications or a last sync time. */
    val hasData: Boolean,
    val lastUpdatedText: String,
    val selectedIds: Set<String> = emptySet(),
    val avatarRevision: Int = 0,
    /** When true, [visibleRows] holds only the unread rows. */
    val showOnlyUnread: Boolean = false
) {
    /** True when there are no stored rows at all, whatever the unread filter says. */
    val isEmpty: Boolean
        get() = rows.isEmpty()

    /** The rows the table shows: all of [rows], or only the unread ones when [showOnlyUnread] is on. */
    val visibleRows: List<NotificationRow>
        get() = if (showOnlyUnread) rows.filter { row -> row.isUnread } else rows

    val unreadCount: Int
        get() = rows.count { row -> row.isUnread }

    /** True when at least one selected row is shown, so the mark actions can run. */
    val hasSelection: Boolean
        get() = visibleRows.any { row -> row.id in selectedIds }

    /** The state of the select all checkbox in the table header. */
    val selectAllState: ToggleableState
        get() = deriveSelectAllState(rows = visibleRows, selectedIds = selectedIds)

    companion object {
        val INITIAL = NotificationsUiState(
            rows = emptyList(),
            hasData = false,
            lastUpdatedText = ""
        )
    }
}

/** On when every row is selected, Off when none is (or there are no rows), Indeterminate otherwise. */
fun deriveSelectAllState(rows: List<NotificationRow>, selectedIds: Set<String>): ToggleableState {
    if (rows.isEmpty()) {
        return ToggleableState.Off
    }
    val selectedCount = rows.count { row -> row.id in selectedIds }
    return when (selectedCount) {
        0 -> ToggleableState.Off
        rows.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/**
 * The texts the pure row builder needs. The defaults read the message bundle and format times relative to now, like the
 * pull request "updated" time; tests pass fixed texts.
 */
data class NotificationLabels(
    val typeLabel: (DiffyBrowserEventCategory) -> String = { category -> DiffyBundle.message(categoryBundleKey(category = category)) },
    val botSuffix: String = DiffyBundle.message("notifications.actor.bot.suffix"),
    val timeText: (Instant, TimeZone) -> String = { instant, _ -> PullRequestRelativeTime.formatRelativeThenAbsolute(date = instant) },
    val lastUpdatedText: (Instant, TimeZone) -> String = { instant, _ -> PullRequestRelativeTime.formatRelativeThenAbsolute(date = instant) }
)

/**
 * What the row builder needs from the plugin settings and caches, kept out of the pure builder: the configured
 * workspace, and a lookup of the avatar URL of an event's actor given the event and the workspace to look in.
 */
data class NotificationRowContext(
    val configuredWorkspace: String? = null,
    val actorAvatarUrlFor: (DiffyBrowserNotificationEvent, String?) -> String? = { _, _ -> null },
    /**
     * The state of a pull request when no snapshot lists it, for example the persisted pull request cache after an IDE
     * restart. Arguments are the workspace, the repository slug and the pull request number.
     */
    val cachedPullRequestState: (workspace: String, repoSlug: String, pullRequestId: Long) -> PullRequestState? = { _, _, _ -> null }
)

/** The bundle key of the type column text for [category]. The exhaustive `when` flags every new category at compile time. */
fun categoryBundleKey(category: DiffyBrowserEventCategory): String {
    return when (category) {
        DiffyBrowserEventCategory.REVIEWER_ADDED -> "notifications.category.reviewerAdded"
        DiffyBrowserEventCategory.APPROVAL -> "notifications.category.approval"
        DiffyBrowserEventCategory.BOT_REVIEW_COMMENT -> "notifications.category.botReviewComment"
        DiffyBrowserEventCategory.HUMAN_COMMENT -> "notifications.category.humanComment"
        DiffyBrowserEventCategory.MENTION -> "notifications.category.mention"
        DiffyBrowserEventCategory.PR_UPDATED -> "notifications.category.prUpdated"
        DiffyBrowserEventCategory.MERGED -> "notifications.category.merged"
        DiffyBrowserEventCategory.OTHER_BITBUCKET -> "notifications.category.otherBitbucket"
    }
}

/**
 * Builds the table rows of [notifications], newest first. Events with the same timestamp keep their original order, an
 * event whose timestamp does not parse is kept and sorted last, and a repeated event id keeps only its first occurrence.
 * [pullRequests] are the pull requests of the latest snapshot, empty when none arrived since the IDE started.
 * [timeZone] makes the time text deterministic for tests.
 */
fun buildNotificationRows(
    notifications: List<DiffyStoredNotification>,
    pullRequests: List<DiffyBrowserPullRequest>,
    timeZone: TimeZone,
    labels: NotificationLabels = NotificationLabels(),
    context: NotificationRowContext = NotificationRowContext()
): List<NotificationRow> {
    val seenIds = HashSet<String>()
    val indexed = notifications
        .filter { notification -> seenIds.add(notification.event.id) }
        .mapIndexed { index, notification ->
            IndexedRow(
                index = index,
                row = toRow(
                    notification = notification,
                    pullRequests = pullRequests,
                    timeZone = timeZone,
                    labels = labels,
                    context = context
                )
            )
        }
    val newestFirst = compareByDescending<IndexedRow> { indexed -> indexed.row.occurredAt != null }
        .thenByDescending { indexed -> indexed.row.occurredAt }
        .thenBy { indexed -> indexed.index }
    return indexed.sortedWith(newestFirst).map { indexed -> indexed.row }
}

private data class IndexedRow(val index: Int, val row: NotificationRow)

private fun toRow(
    notification: DiffyStoredNotification,
    pullRequests: List<DiffyBrowserPullRequest>,
    timeZone: TimeZone,
    labels: NotificationLabels,
    context: NotificationRowContext
): NotificationRow {
    val event = notification.event
    val occurredAt = DiffyBrowserTimestamps.parseOrNull(value = event.occurredAt)
    val typeLabel = labels.typeLabel(event.category)
    val eventTitle = event.message?.takeIf { message -> message.isNotBlank() } ?: typeLabel
    val urlParts = pullRequestUrlParts(event = event)
    val urlWorkspace = urlParts?.workspace
    val configuredWorkspace = context.configuredWorkspace?.takeIf { workspace -> workspace.isNotBlank() }
    val pullRequestId = event.pullRequestId
    val repoSlug = event.pullRequestRepoSlug?.takeIf { slug -> slug.isNotBlank() }
    val effectiveWorkspace = urlWorkspace ?: configuredWorkspace
    return NotificationRow(
        id = event.id,
        occurredAt = occurredAt,
        timeText = occurredAt?.let { instant -> labels.timeText(instant, timeZone) } ?: event.occurredAt,
        category = event.category,
        typeLabel = typeLabel,
        eventTitle = eventTitle,
        pullRequestTitle = pullRequestTitleOf(
            event = event,
            pullRequests = pullRequests,
            repoSlug = repoSlug,
            workspace = effectiveWorkspace
        ) ?: eventTitle,
        actorText = actorTextOf(event = event, botSuffix = labels.botSuffix),
        actorAvatarUrl = context.actorAvatarUrlFor(event, effectiveWorkspace),
        workspace = configuredWorkspace,
        urlWorkspace = urlWorkspace,
        repoSlug = repoSlug,
        pullRequestId = pullRequestId,
        pullRequestNumberText = if (pullRequestId == null) "" else "#$pullRequestId",
        pullRequestWebUrl = pullRequestWebUrlOf(urlParts = urlParts, workspace = configuredWorkspace, repoSlug = repoSlug, pullRequestId = pullRequestId),
        isForeignWorkspace = configuredWorkspace != null && urlWorkspace != null && !urlWorkspace.equals(other = configuredWorkspace, ignoreCase = true),
        knownPullRequestState = knownStateOf(
            pullRequests = pullRequests,
            repoSlug = repoSlug,
            pullRequestId = pullRequestId,
            workspace = effectiveWorkspace,
            cachedState = context.cachedPullRequestState
        ),
        isUnread = notification.unread,
        url = event.url
    )
}

/** The pull request title: the event's own, else the one the latest snapshot lists for the pull request; null when neither is known. */
private fun pullRequestTitleOf(
    event: DiffyBrowserNotificationEvent,
    pullRequests: List<DiffyBrowserPullRequest>,
    repoSlug: String?,
    workspace: String?
): String? {
    val own = event.pullRequestTitle?.takeIf { title -> title.isNotBlank() }
    if (own != null) {
        return own
    }
    val pullRequestId = event.pullRequestId
    if (repoSlug == null || pullRequestId == null) {
        return null
    }
    return pullRequests.firstOrNull { pullRequest ->
        pullRequest.id == pullRequestId &&
            pullRequest.repoSlug.equals(other = repoSlug, ignoreCase = true) &&
            (pullRequest.workspace.isBlank() || workspace == null || pullRequest.workspace.equals(other = workspace, ignoreCase = true))
    }?.title?.takeIf { title -> title.isNotBlank() }
}

private fun pullRequestWebUrlOf(urlParts: PullRequestUrlParts?, workspace: String?, repoSlug: String?, pullRequestId: Long?): String? {
    if (urlParts != null) {
        return "https://bitbucket.org/${urlParts.workspace}/${urlParts.repoSlug}/pull-requests/${urlParts.id}"
    }
    if (workspace == null || repoSlug == null || pullRequestId == null) {
        return null
    }
    val repositoryUrl = BitbucketRepositoryUrls.repositoryWebUrlForWorkspace(workspace = workspace, repoSlug = repoSlug) ?: return null
    return "$repositoryUrl/pull-requests/$pullRequestId"
}

/**
 * The state the snapshot reports for the pull request, or null when the snapshot does not list it or reports a state
 * the plugin does not know. A snapshot entry with a workspace only matches the same workspace. When no snapshot entry
 * matches, [cachedState] is asked, which reads the persisted pull request cache.
 */
internal fun knownStateOf(
    pullRequests: List<DiffyBrowserPullRequest>,
    repoSlug: String?,
    pullRequestId: Long?,
    workspace: String?,
    cachedState: (workspace: String, repoSlug: String, pullRequestId: Long) -> PullRequestState? = { _, _, _ -> null }
): PullRequestState? {
    if (repoSlug == null || pullRequestId == null) {
        return null
    }
    val match = pullRequests.firstOrNull { pullRequest ->
        pullRequest.id == pullRequestId &&
            pullRequest.repoSlug.equals(other = repoSlug, ignoreCase = true) &&
            (pullRequest.workspace.isBlank() || workspace == null || pullRequest.workspace.equals(other = workspace, ignoreCase = true))
    }
    if (match != null) {
        return match.pullRequestState()
    }
    if (workspace.isNullOrBlank()) {
        return null
    }
    return cachedState(workspace, repoSlug, pullRequestId)
}

/** The parts of a `https://bitbucket.org/<workspace>/<repo>/pull-requests/<id>` link. */
internal data class PullRequestUrlParts(val workspace: String, val repoSlug: String, val id: Long)

private val PULL_REQUEST_URL = Regex("^https://bitbucket\\.org/([^/?#]+)/([^/?#]+)/pull-requests/(\\d+)")

/**
 * The validated pull request link parts of [event]: the event's pull request link first, then its own link. Only https
 * links on bitbucket.org are accepted, and the repository and id must agree with the event's own pull request fields.
 */
internal fun pullRequestUrlParts(event: DiffyBrowserNotificationEvent): PullRequestUrlParts? {
    val candidates = listOfNotNull(event.pullRequestUrl, event.url)
    candidates.forEach { candidate ->
        val parts = parsePullRequestUrl(url = candidate)
        if (parts != null && agreesWithEvent(parts = parts, event = event)) {
            return parts
        }
    }
    return null
}

internal fun pullRequestUrlWorkspace(event: DiffyBrowserNotificationEvent): String? {
    return pullRequestUrlParts(event = event)?.workspace
}

internal fun parsePullRequestUrl(url: String): PullRequestUrlParts? {
    val match = PULL_REQUEST_URL.find(input = url) ?: return null
    val id = match.groupValues[3].toLongOrNull() ?: return null
    return PullRequestUrlParts(
        workspace = match.groupValues[1],
        repoSlug = match.groupValues[2],
        id = id
    )
}

private fun agreesWithEvent(parts: PullRequestUrlParts, event: DiffyBrowserNotificationEvent): Boolean {
    val eventSlug = event.pullRequestRepoSlug
    val eventId = event.pullRequestId
    val slugAgrees = eventSlug == null || parts.repoSlug.equals(other = eventSlug, ignoreCase = true)
    val idAgrees = eventId == null || parts.id == eventId
    return slugAgrees && idAgrees
}

private fun actorTextOf(event: DiffyBrowserNotificationEvent, botSuffix: String): String {
    val name = event.actorDisplayName.orEmpty()
    return if (event.actorIsBot) "$name$botSuffix".trim() else name
}

/** Formats as `yyyy-MM-dd HH:mm` in [timeZone], without `java.time`. */
internal fun formatTime(instant: Instant, timeZone: TimeZone): String {
    val local = instant.toLocalDateTime(timeZone)
    val year = local.year.toString().padStart(length = 4, padChar = '0')
    val month = (local.month.ordinal + 1).toString().padStart(length = 2, padChar = '0')
    val day = local.day.toString().padStart(length = 2, padChar = '0')
    val hour = local.hour.toString().padStart(length = 2, padChar = '0')
    val minute = local.minute.toString().padStart(length = 2, padChar = '0')
    return "$year-$month-$day $hour:$minute"
}