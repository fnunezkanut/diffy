package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserNotificationEvent
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserPullRequest
import com.github.fnunezkanut.bbcloud.external.DiffyNotificationsContent
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/**
 * Holds the Notifications tab state: the persisted notifications, newest first. It collects [stored] once, so the table
 * updates while the tab is open, also when another IDE process changed the persisted notifications. Rows are built on
 * [ioDispatcher] because up to 2000 notifications can be stored, and the state is written through [runOnEdt].
 * [setUnread] persists the read flag; [latestPullRequests] are the pull requests of the latest snapshot, if any.
 */
class NotificationsViewModel internal constructor(
    private val stored: Flow<DiffyNotificationsContent>,
    private val latestPullRequests: () -> List<DiffyBrowserPullRequest>,
    private val setUnread: suspend (ids: Set<String>, unread: Boolean) -> Unit,
    private val coroutineScope: CoroutineScope,
    private val runOnEdt: (() -> Unit) -> Unit,
    private val openSetupHelp: () -> Unit,
    private val openForReview: (NotificationRow) -> Unit,
    private val openOnWeb: (NotificationRow) -> Unit,
    private val openRepository: (NotificationRow) -> Unit,
    private val avatarIcon: (String?) -> Icon,
    private val timeZone: () -> TimeZone,
    private val configuredWorkspace: () -> String? = { null },
    private val actorAvatarUrlFor: (DiffyBrowserNotificationEvent, String?) -> String? = { _, _ -> null },
    private val cachedPullRequestState: (workspace: String, repoSlug: String, pullRequestId: Long) -> PullRequestState? = { _, _, _ -> null },
    private val prefetchAvatars: (urls: Collection<String>, onLoaded: () -> Unit) -> Unit = { _, _ -> },
    private val labels: () -> NotificationLabels = { NotificationLabels() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val openMessage: (NotificationRow) -> Unit = {},
    initialShowOnlyUnread: Boolean = false,
    private val saveShowOnlyUnread: (Boolean) -> Unit = {}
) {
    val state: MutableState<NotificationsUiState> =
        mutableStateOf(NotificationsUiState.INITIAL.copy(showOnlyUnread = initialShowOnlyUnread))

    private val started = AtomicBoolean(false)
    private val buildGeneration = AtomicInteger(0)

    @Volatile
    private var latestContent: DiffyNotificationsContent? = null

    /** Starts collecting the snapshots. Safe to call more than once; only the first call has an effect. */
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        LOG.debug("Collecting the stored notifications for the Notifications tab")
        coroutineScope.launch {
            stored.collectLatest { content ->
                latestContent = content
                if (content.loaded) {
                    rebuild(content = content)
                } else {
                    LOG.debug("The stored notifications are not loaded yet")
                }
            }
        }
    }

    /** Rebuilds the time texts from the latest content, so a changed system time zone shows without a new capture. */
    fun refreshTimes() {
        val content = latestContent ?: return
        if (!content.loaded) {
            return
        }
        coroutineScope.launch { rebuild(content = content) }
    }

    fun onSetupHelpClicked() {
        LOG.info("User clicked the How to setup Notifications link")
        openSetupHelp()
    }

    /** The cached avatar of [url], or the default user icon while it is missing or not loaded yet. */
    fun avatarIconFor(url: String?): Icon {
        return avatarIcon(url)
    }

    fun onRowSelectionChanged(rowId: String, selected: Boolean) {
        LOG.info("User ${if (selected) "selected" else "deselected"} notification $rowId")
        val current = state.value
        val next = if (selected) current.selectedIds + rowId else current.selectedIds - rowId
        state.value = current.copy(selectedIds = next)
    }

    /** Selects every row, or clears the selection when every row is already selected. */
    fun onSelectAllClicked() {
        val current = state.value
        val visible = current.visibleRows
        val allSelected = visible.isNotEmpty() && visible.all { row -> row.id in current.selectedIds }
        val next = if (allSelected) emptySet() else visible.map { row -> row.id }.toSet()
        LOG.info("User toggled select all, ${next.size} of ${visible.size} notification(s) selected")
        state.value = current.copy(selectedIds = next)
    }

    /** Marks the selected notifications as read. The selection stays, so the user can mark them unread again. */
    fun onMarkReadClicked() {
        markSelected(unread = false)
    }

    /** Marks the selected notifications as unread. */
    fun onMarkUnreadClicked() {
        markSelected(unread = true)
    }

    private fun markSelected(unread: Boolean) {
        val current = state.value
        val ids = current.visibleRows.filter { row -> row.id in current.selectedIds }.map { row -> row.id }.toSet()
        LOG.info("User marked ${ids.size} notification(s) as ${if (unread) "unread" else "read"}")
        if (ids.isEmpty()) {
            return
        }
        state.value = current.copy(rows = current.rows.map { row -> if (row.id in ids) row.copy(isUnread = unread) else row })
        coroutineScope.launch {
            try {
                setUnread(ids, unread)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not save the read state of ${ids.size} notification(s)", exception)
            }
        }
    }

    fun onShowOnlyUnreadChanged(showOnlyUnread: Boolean) {
        LOG.info("User turned the Show only Unread filter ${if (showOnlyUnread) "on" else "off"}")
        state.value = state.value.copy(showOnlyUnread = showOnlyUnread)
        saveShowOnlyUnread(showOnlyUnread)
    }

    fun onTitleClicked(row: NotificationRow) {
        openForReview(row)
    }

    fun onMessageClicked(row: NotificationRow) {
        LOG.info("User clicked the message of notification ${row.id}")
        openMessage(row)
    }

    fun onPullRequestNumberClicked(row: NotificationRow) {
        openOnWeb(row)
    }

    fun onRepositoryClicked(row: NotificationRow) {
        openRepository(row)
    }

    private suspend fun rebuild(content: DiffyNotificationsContent) {
        val generation = buildGeneration.incrementAndGet()
        val activeLabels = labels()
        val zone = timeZone()
        val rowContext = NotificationRowContext(
            configuredWorkspace = configuredWorkspace(),
            actorAvatarUrlFor = actorAvatarUrlFor,
            cachedPullRequestState = cachedPullRequestState
        )
        val pullRequests = latestPullRequests()
        val rows = withContext(ioDispatcher) {
            buildNotificationRows(
                notifications = content.notifications,
                pullRequests = pullRequests,
                timeZone = zone,
                labels = activeLabels,
                context = rowContext
            )
        }
        val lastUpdatedText = content.lastSyncedAt?.let { instant -> activeLabels.lastUpdatedText(instant, zone) }.orEmpty()
        LOG.debug("Stored notifications received, ${rows.size} notification row(s)")
        runOnEdt {
            if (generation == buildGeneration.get()) {
                val current = state.value
                val rowIds = rows.map { row -> row.id }.toSet()
                state.value = NotificationsUiState(
                    rows = rows,
                    hasData = content.notifications.isNotEmpty() || content.lastSyncedAt != null,
                    lastUpdatedText = lastUpdatedText,
                    selectedIds = current.selectedIds.intersect(rowIds),
                    avatarRevision = current.avatarRevision,
                    showOnlyUnread = current.showOnlyUnread
                )
                prefetchAvatars(rows.mapNotNull { row -> row.actorAvatarUrl }.distinct(), ::onAvatarLoaded)
            }
        }
    }

    private fun onAvatarLoaded() {
        val current = state.value
        state.value = current.copy(avatarRevision = current.avatarRevision + 1)
    }

    companion object {
        private val LOG = logger<NotificationsViewModel>()
    }
}