package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotStore
import com.github.fnunezkanut.bbcloud.external.DiffyNotificationsStore
import com.github.fnunezkanut.bbcloud.project.LocalRepositoryProjectOpener
import com.github.fnunezkanut.bbcloud.ui.components.NotConfiguredContent
import com.github.fnunezkanut.bbcloud.ui.main.AuthorAvatarCache
import com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyNotificationsHelpEditorOpener
import com.github.fnunezkanut.bbcloud.ui.worktree.PullRequestWorktreeOpener
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.TimeZone
import org.jetbrains.jewel.bridge.compose
import javax.swing.JComponent

/** The two cards of the Notifications tab. */
private enum class NotificationsCard {
    Content,
    NotConfigured
}

/**
 * Hosts the Notifications tab: builds the Compose screen and view model and switches between the table and the
 * not-configured link. The view model collects the persisted notifications from [start] on, so the table is current when the
 * tab is first shown.
 */
internal class NotificationsPanel(project: Project, coroutineScope: CoroutineScope, onGoToSettings: () -> Unit) {
    private val card: MutableState<NotificationsCard> = mutableStateOf(NotificationsCard.NotConfigured)

    private val avatarCache = AuthorAvatarCache(avatarSize = NOTIFICATION_AVATAR_SIZE)

    private val pullRequestOpener = createPullRequestOpener(coroutineScope = coroutineScope)

    private val viewModel = NotificationsViewModel(
        stored = DiffyNotificationsStore.getInstance().content,
        latestPullRequests = { DiffyBrowserSnapshotStore.getInstance().latest()?.pullRequests.orEmpty() },
        setUnread = { ids, unread -> DiffyNotificationsStore.getInstance().setUnread(ids = ids, unread = unread) },
        coroutineScope = coroutineScope,
        runOnEdt = { block -> ApplicationManager.getApplication().invokeLater(block) },
        openSetupHelp = { project.service<DiffyNotificationsHelpEditorOpener>().open() },
        openForReview = pullRequestOpener::openForReview,
        openOnWeb = pullRequestOpener::openOnWeb,
        openRepository = pullRequestOpener::openRepository,
        avatarIcon = avatarCache::iconFor,
        timeZone = { TimeZone.currentSystemDefault() },
        configuredWorkspace = { DiffyConfigurationService.getInstance().workspaceSlugOrNull() },
        actorAvatarUrlFor = { event, workspace ->
            cachedActorAvatarUrl(cache = DiffyPullRequestCache.getInstance(), event = event, workspace = workspace)
        },
        cachedPullRequestState = { workspace, repoSlug, pullRequestId ->
            DiffyPullRequestCache.getInstance()
                .get(PullRequestCacheKey.of(workspace = workspace, repoSlug = repoSlug, prNum = pullRequestId))?.state
        },
        prefetchAvatars = { urls, onLoaded ->
            avatarCache.prefetch(urls = urls, coroutineScope = coroutineScope, onLoaded = onLoaded)
        },
        openMessage = { row ->
            val url = row.url
            if (url != null && url.startsWith("https://")) {
                BrowserUtil.browse(url)
            } else {
                LOG.info("Notification ${row.id} has no https link to open")
            }
        }
    )

    val component: JComponent = compose {
        val current by card
        when (current) {
            NotificationsCard.NotConfigured -> NotConfiguredContent(onGoToSettings = onGoToSettings)
            NotificationsCard.Content -> NotificationsScreen(viewModel = viewModel)
        }
    }

    /** Starts collecting snapshots. Safe to call more than once. */
    fun start() {
        viewModel.start()
    }

    /** Shows the table and re-renders its time texts, so a changed system time zone applies. */
    fun showContent() {
        LOG.debug("Showing the Notifications table")
        card.value = NotificationsCard.Content
        viewModel.refreshTimes()
    }

    fun showNotConfigured() {
        LOG.debug("Showing the not configured message on the Notifications tab")
        card.value = NotificationsCard.NotConfigured
    }

    private fun createPullRequestOpener(coroutineScope: CoroutineScope): NotificationPullRequestOpener {
        val worktreeOpener = PullRequestWorktreeOpener.getInstance()
        val configurationService = DiffyConfigurationService.getInstance()
        return NotificationPullRequestOpener(
            coroutineScope = coroutineScope,
            checkOpen = { workspace, repoSlug, prNumber ->
                worktreeOpener.checkPullRequestOpen(workspace = workspace, repoSlug = repoSlug, prNumber = prNumber)
            },
            startReview = { workspace, repoSlug, prNumber ->
                worktreeOpener.openInBackground(workspace = workspace, repoSlug = repoSlug, prNumber = prNumber)
            },
            localCheckoutPathFor = { repoSlug -> configurationService.localCheckoutPathForRepo(repoSlug) },
            openLocalProject = { repoSlug, checkoutPath ->
                LocalRepositoryProjectOpener.openOrFocus(repoSlug = repoSlug, checkoutPath = checkoutPath) != null
            },
            browse = { url -> BrowserUtil.browse(url) },
            runOnEdt = { block -> ApplicationManager.getApplication().invokeLater(block) }
        )
    }

    companion object {
        private val LOG = logger<NotificationsPanel>()
        private const val NOTIFICATION_AVATAR_SIZE = 32
    }
}