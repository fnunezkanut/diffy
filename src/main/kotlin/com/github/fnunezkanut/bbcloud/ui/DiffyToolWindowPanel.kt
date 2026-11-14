package com.github.fnunezkanut.bbcloud.ui

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyAuthoredPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyProjectRepositoryLinkService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyWatchedProjectsPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketPullRequestClient
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketRepositoryClient
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.settings.DiffySettings
import com.github.fnunezkanut.bbcloud.settings.DiffyToolsConfigurable
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTab
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTabResolver
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTabTitle
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.github.fnunezkanut.bbcloud.ui.main.DiffyCurrentProjectCoordinator
import com.github.fnunezkanut.bbcloud.ui.notifications.NotificationsPanel
import com.github.fnunezkanut.bbcloud.ui.otherprojects.DiffyOtherProjectsPanel
import com.github.fnunezkanut.bbcloud.ui.recentprojects.RecentProjectsPanel
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Owns the five tool window tabs and the panels behind them. The tab selected last opens with the tool window. The
 * plugin settings live on the Settings > Tools > Diffy - Bitbucket PR Review page: selecting the Settings tab opens that
 * page and keeps the previous tab selected. While the plugin is unconfigured, the Pull Requests, Notifications, and Watched Projects tabs show a link to that page.
 * The Pull Requests and Notifications tab titles carry a counter of open pull requests and unread notifications.
 */
@Service(Service.Level.PROJECT)
class DiffyToolWindowPanel(private val project: Project) {
    private val configurationService: DiffyConfigurationService = DiffyConfigurationService.getInstance()
    private val projectRepositoryLinkService: DiffyProjectRepositoryLinkService = createDefaultProjectRepositoryLinkService(
        configurationService = configurationService
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The Settings tab never stays selected (see redirectToSettingsPage), so its content is an empty panel. */
    private val settingsTabPlaceholder: JComponent = JPanel()
    private val openPullRequestService = createDefaultOpenPullRequestService(configurationService = configurationService)

    // otherProjectsPanel is read lazily here because this coordinator is built before it.
    private val currentProjectCoordinator: DiffyCurrentProjectCoordinator = DiffyCurrentProjectCoordinator(
        configurationService = configurationService,
        projectRepositoryLinkService = projectRepositoryLinkService,
        openPullRequestService = openPullRequestService,
        onCurrentProjectReloadRequested = { reason -> otherProjectsPanel.reloadCurrentProject(reason = reason) }
    )
    private val otherProjectsPanel: DiffyOtherProjectsPanel = DiffyOtherProjectsPanel(
        project = project,
        configurationService = configurationService,
        authoredPullRequestService = createDefaultAuthoredPullRequestService(),
        watchedPullRequestService = createDefaultWatchedPullRequestService(openPullRequestService = openPullRequestService),
        currentProjectCoordinator = currentProjectCoordinator,
        coroutineScope = coroutineScope,
        onGoToSettings = { openSettingsPage() },
        onGoToWatchedProjects = { showWatchedProjectsTab() },
        onGoToWorkspace = { showPullRequestsTab() },
        onRefreshRequested = { refreshWorkspace() },
        onOpenCountChanged = { count -> updateTabCount(tab = DiffyToolWindowTab.PullRequests, count = count) }
    )

    private val recentProjectsPanel = RecentProjectsPanel(
        project = project,
        coroutineScope = coroutineScope,
        isPruneInProgress = { otherProjectsPanel.isPruneInProgress() }
    )

    private val notificationsPanel = NotificationsPanel(
        project = project,
        coroutineScope = coroutineScope,
        onGoToSettings = { openSettingsPage() },
        onUnreadCountChanged = { count -> updateTabCount(tab = DiffyToolWindowTab.Notifications, count = count) }
    )

    private var contentManager: ContentManager? = null
    private var isProgrammaticSelection = false

    /** The tab shown in the tool window; never [DiffyToolWindowTab.Settings], which only opens the settings page. */
    private var currentTab: DiffyToolWindowTab = DiffyToolWindowTab.PullRequests
    private var contentByTab: Map<DiffyToolWindowTab, Content> = emptyMap()

    /** The raw counter of each tab; a tab without an entry has no counter. Accessed on the EDT only. */
    private val countByTab: MutableMap<DiffyToolWindowTab, Int> = mutableMapOf()

    /** Whether the plugin is configured; counters are hidden while it is not. Accessed on the EDT only. */
    private var isConfigured: Boolean = false

    /** Non-closeable components for each of the five tabs; wired from coordinators. */
    val pullRequestsComponent: JComponent = otherProjectsPanel.pullRequestsTabComponent
    val watchedProjectsComponent: JComponent = otherProjectsPanel.watchedProjectsTabComponent
    val recentProjectsComponent: JComponent = recentProjectsPanel.component
    val notificationsComponent: JComponent = notificationsPanel.component
    val settingsComponent: JComponent = settingsTabPlaceholder

    /** Builds the five non-closeable tabs, in header order. */
    fun createContents(): List<Content> {
        val contentFactory = ContentFactory.getInstance()
        val contents = DiffyToolWindowTab.entries.map { tab ->
            val component = componentFor(tab = tab)
            contentFactory.createContent(component, DiffyBundle.message(titleKeyFor(tab)), false).apply {
                // Default left-to-right orientation places the icon before the label
                isCloseable = false
                putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
                putUserData(ToolWindowContentUi.NOT_SELECTED_TAB_ICON_TRANSPARENT, false)
                icon = iconFor(tab)
            }
        }
        contentByTab = DiffyToolWindowTab.entries.zip(contents).toMap()
        DiffyToolWindowTab.entries.forEach { tab -> applyTabTitle(tab = tab) }
        return contents
    }

    /** Shows [count] after the title of [tab], or no counter when it is null or zero. Safe to call from any thread. */
    fun updateTabCount(tab: DiffyToolWindowTab, count: Int?) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            setTabCount(tab = tab, count = count)
        } else {
            application.invokeLater { setTabCount(tab = tab, count = count) }
        }
    }

    private fun setTabCount(tab: DiffyToolWindowTab, count: Int?) {
        val normalized = count?.takeIf { value -> value > 0 }
        if (normalized == countByTab[tab]) {
            return
        }
        if (normalized == null) {
            countByTab.remove(tab)
        } else {
            countByTab[tab] = normalized
        }
        LOG.debug("Tab counter of the $tab tab changed to ${normalized ?: "none"}")
        applyTabTitle(tab = tab)
    }

    private fun applyTabTitle(tab: DiffyToolWindowTab) {
        val content = contentByTab[tab] ?: return
        content.displayName = DiffyToolWindowTabTitle.formatForCurrentTheme(
            title = DiffyBundle.message(titleKeyFor(tab)),
            count = if (isConfigured) countByTab[tab] else null
        )
    }

    private fun applyCounterTitles() {
        applyTabTitle(tab = DiffyToolWindowTab.PullRequests)
        applyTabTitle(tab = DiffyToolWindowTab.Notifications)
    }

    private fun refreshConfiguredState() {
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                if (configured != isConfigured) {
                    LOG.debug("Configured state for the tab counters changed to $configured")
                    isConfigured = configured
                    applyCounterTitles()
                }
            }
        }
    }

    /** Loads the Pull Requests listing in the background so its tab counter shows while another tab is selected. */
    private fun preloadPullRequestCount() {
        LOG.debug("Preloading the Pull Requests listing for the tab counter")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            if (!configured) {
                return@launch
            }
            ApplicationManager.getApplication().invokeLater {
                otherProjectsPanel.loadOverview(coroutineScope = coroutineScope)
            }
        }
    }

    /**
     * Hands a freshly created pull request to the current project coordinator, which switches the current checkout to
     * the main branch, reloads the current project listing, and opens the pull request in its own work tree. Returns
     * false when the coordinator is not bound to a project.
     */
    fun openCreatedPullRequest(summary: PullRequestSummary, localMainBranch: String?): Boolean {
        LOG.debug("Handing created pull request #${summary.id} to the current project coordinator")
        return currentProjectCoordinator.openCreatedPullRequest(summary = summary, localMainBranch = localMainBranch)
    }

    /** Selects the Pull Requests and restores its listing card without starting a reload. */
    fun showPullRequestsListing(): Boolean {
        val contentManager = contentManager ?: return false
        val content = contentByTab[DiffyToolWindowTab.PullRequests] ?: return false
        isProgrammaticSelection = true
        try {
            contentManager.setSelectedContent(content)
        } finally {
            isProgrammaticSelection = false
        }
        currentTab = DiffyToolWindowTab.PullRequests
        rememberTab(tab = DiffyToolWindowTab.PullRequests)
        otherProjectsPanel.showOverviewListing()
        return true
    }

    /**
     * Re-checks the configuration and reloads the tab that is currently shown, so a change made on the settings page
     * shows up at once. Safe to call from any thread; does nothing before the tool window is created.
     */
    fun refreshSelectedTab() {
        ApplicationManager.getApplication().invokeLater {
            if (contentManager == null) {
                return@invokeLater
            }
            LOG.debug("Refreshing the $currentTab tab after a settings change")
            refreshConfiguredState()
            applyTabSelection(tab = currentTab)
        }
    }

    /** Rebuilds the watched projects from the projects directory that was just chosen on the settings page. */
    fun onProjectsDirectoryChanged() {
        otherProjectsPanel.rescanWatchedProjects(clearFirst = true)
    }

    /** Drops in-memory state after Reset Plugin on the settings page, then reloads the tab that is shown. */
    fun onPluginReset() {
        ApplicationManager.getApplication().invokeLater {
            LOG.debug("Refreshing tool window after a plugin reset")
            // resetAll already wiped the persisted avatar-URL map; also drop the in-memory decoded repository
            // avatars so the Watched Projects tab shows placeholders until the next Sync repopulates them.
            otherProjectsPanel.resetWatchedProjectsAvatars()
            recentProjectsPanel.resetAvatars()
            setTabCount(tab = DiffyToolWindowTab.PullRequests, count = null)
            setTabCount(tab = DiffyToolWindowTab.Notifications, count = null)
            refreshConfiguredState()
            refreshSelectedTab()
        }
    }

    /** Selects the Watched Projects tab. Returns false if the tab or its content is unavailable. */
    fun showWatchedProjectsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.WatchedProjects] ?: return false
        selectTab(tab = DiffyToolWindowTab.WatchedProjects)
        return true
    }

    /** Selects the Recent Projects tab. Returns false if the tab or its content is unavailable. */
    fun showRecentProjectsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.RecentProjects] ?: return false
        selectTab(tab = DiffyToolWindowTab.RecentProjects)
        return true
    }

    /** Selects the Notifications tab. Returns false if the tab or its content is unavailable. */
    fun showNotificationsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.Notifications] ?: return false
        selectTab(tab = DiffyToolWindowTab.Notifications)
        return true
    }

    /** Selects the Pull Requests tab. Returns false if the tab or its content is unavailable. */
    fun showPullRequestsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.PullRequests] ?: return false
        selectTab(tab = DiffyToolWindowTab.PullRequests)
        return true
    }

    /** Starts listening for tab selection and resolves the tab that opens with the tool window. */
    fun bind(contentManager: ContentManager, parentDisposable: Disposable) {
        this.contentManager = contentManager
        currentProjectCoordinator.bind(project = project, coroutineScope = coroutineScope)
        notificationsPanel.start()
        val listener = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (isProgrammaticSelection) {
                    return
                }
                if (event.operation != ContentManagerEvent.ContentOperation.add) {
                    return
                }
                val tab = tabFor(content = event.content) ?: return
                LOG.info("User selected the $tab tab in the Diffy - Bitbucket PR Review tool window")
                if (tab == DiffyToolWindowTab.Settings) {
                    redirectToSettingsPage()
                    return
                }
                currentTab = tab
                rememberTab(tab = tab)
                applyTabSelection(tab = tab)
            }
        }
        contentManager.addContentManagerListener(listener)
        ApplicationManager.getApplication().messageBus.connect(parentDisposable).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                LOG.debug("Rebuilding the tab titles after a theme change")
                DiffyToolWindowTab.entries.forEach { tab -> applyTabTitle(tab = tab) }
            }
        )
        Disposer.register(parentDisposable) {
            contentManager.removeContentManagerListener(listener)
        }
        val initialTab = DiffyToolWindowTabResolver.initialTab(lastSelectedTab = DiffySettings.getInstance().lastSelectedTab)
        LOG.debug("Initial tool window tab: $initialTab")
        refreshConfiguredState()
        selectTab(tab = initialTab, remember = false)
        if (initialTab != DiffyToolWindowTab.PullRequests) {
            preloadPullRequestCount()
        }
    }

    /**
     * Puts the previous tab back inside the same selection event, so the Settings tab content and its highlighted
     * label are never painted, then opens the settings page. The dialog is deferred because a modal dialog must not
     * open while the content manager is still dispatching the tab click.
     */
    private fun redirectToSettingsPage() {
        restoreSelection(tab = currentTab)
        ApplicationManager.getApplication().invokeLater {
            openSettingsPage()
        }
    }

    /** Selects [tab] without reloading it, because the selection only moved away for a moment. */
    private fun restoreSelection(tab: DiffyToolWindowTab) {
        val contentManager = contentManager ?: return
        val content = contentByTab[tab] ?: return
        isProgrammaticSelection = true
        try {
            contentManager.setSelectedContent(content, false)
        } finally {
            isProgrammaticSelection = false
        }
    }

    /** Opens Settings > Tools > Diffy - Bitbucket PR Review; the page refreshes the shown tab when it closes. */
    private fun openSettingsPage() {
        LOG.info("User opened the Diffy - Bitbucket PR Review settings page from the tool window")
        DiffyToolsConfigurable.open(project = project)
    }

    private fun rememberTab(tab: DiffyToolWindowTab) {
        LOG.debug("Remembering the $tab tab as the last selected tab")
        DiffySettings.getInstance().lastSelectedTab = tab
    }

    private fun selectTab(tab: DiffyToolWindowTab, remember: Boolean = true) {
        if (tab == DiffyToolWindowTab.Settings) {
            openSettingsPage()
            return
        }
        val contentManager = contentManager ?: return
        val content = contentByTab[tab] ?: return
        isProgrammaticSelection = true
        try {
            contentManager.setSelectedContent(content)
        } finally {
            isProgrammaticSelection = false
        }
        currentTab = tab
        if (remember) {
            rememberTab(tab = tab)
        }
        applyTabSelection(tab = tab)
    }

    private fun applyTabSelection(tab: DiffyToolWindowTab) {
        when (tab) {
            DiffyToolWindowTab.PullRequests -> refreshPullRequests()
            DiffyToolWindowTab.WatchedProjects -> refreshWatchedProjects()
            DiffyToolWindowTab.RecentProjects -> refreshRecentProjects()
            DiffyToolWindowTab.Notifications -> refreshNotifications()
            DiffyToolWindowTab.Settings -> Unit
        }
    }

    private fun refreshPullRequests() {
        LOG.debug("Refreshing the Pull Requests tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                if (isSelected(tab = DiffyToolWindowTab.PullRequests)) {
                    if (configured) {
                        otherProjectsPanel.loadOverview(coroutineScope = coroutineScope)
                    } else {
                        otherProjectsPanel.showNotConfigured()
                    }
                }
            }
        }
    }

    private fun refreshWatchedProjects() {
        LOG.debug("Refreshing the Watched Projects tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                if (isSelected(tab = DiffyToolWindowTab.WatchedProjects)) {
                    if (configured) {
                        otherProjectsPanel.bindWatchedProjects()
                    } else {
                        otherProjectsPanel.showWatchedProjectsNotConfigured()
                    }
                }
            }
        }
    }

    /** Recent Projects is local IDE data, so this tab never checks whether Bitbucket is configured. */
    private fun refreshRecentProjects() {
        LOG.debug("Refreshing the Recent Projects tab")
        recentProjectsPanel.reload()
    }

    private fun refreshNotifications() {
        LOG.debug("Refreshing the Notifications tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                if (isSelected(tab = DiffyToolWindowTab.Notifications)) {
                    if (configured) {
                        notificationsPanel.showContent()
                    } else {
                        notificationsPanel.showNotConfigured()
                    }
                }
            }
        }
    }

    private fun componentFor(tab: DiffyToolWindowTab): JComponent {
        return when (tab) {
            DiffyToolWindowTab.PullRequests -> pullRequestsComponent
            DiffyToolWindowTab.WatchedProjects -> watchedProjectsComponent
            DiffyToolWindowTab.RecentProjects -> recentProjectsComponent
            DiffyToolWindowTab.Notifications -> notificationsComponent
            DiffyToolWindowTab.Settings -> settingsTabPlaceholder
        }
    }

    private fun titleKeyFor(tab: DiffyToolWindowTab): String {
        return when (tab) {
            DiffyToolWindowTab.PullRequests -> "toolwindow.tab.pullRequests"
            DiffyToolWindowTab.WatchedProjects -> "toolwindow.tab.watchedProjects"
            DiffyToolWindowTab.RecentProjects -> "toolwindow.tab.recentProjects"
            DiffyToolWindowTab.Notifications -> "toolwindow.tab.notifications"
            DiffyToolWindowTab.Settings -> "toolwindow.tab.settings"
        }
    }

    private fun iconFor(tab: DiffyToolWindowTab): Icon {
        return when (tab) {
            DiffyToolWindowTab.PullRequests -> DiffyPluginIcons.prIcon
            DiffyToolWindowTab.WatchedProjects -> AllIcons.Actions.Show
            DiffyToolWindowTab.RecentProjects -> AllIcons.Vcs.History
            DiffyToolWindowTab.Notifications -> AllIcons.Toolwindows.Notifications
            DiffyToolWindowTab.Settings -> AllIcons.General.Settings
        }
    }

    private fun tabFor(content: Content): DiffyToolWindowTab? {
        return contentByTab.entries.firstOrNull { entry -> entry.value == content }?.key
    }

    /** True when [tab] is still the content the platform shows, so a slow reload cannot apply to an abandoned tab. */
    private fun isSelected(tab: DiffyToolWindowTab): Boolean {
        return contentManager?.selectedContent == contentByTab[tab]
    }

    private fun refreshWorkspace() {
        // Refresh the Pull Requests and the Watched Projects tab when the refresh button is clicked, regardless of the selected tab
        LOG.debug("User requested a reload of the Pull Requests")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            if (!configured) {
                otherProjectsPanel.showNotConfigured()
                // Route the Watched Projects tab to its centered not-configured card too, so both Workspace scope tabs
                // show the generic link rather than leaving Watched Projects on stale content.
                otherProjectsPanel.showWatchedProjectsNotConfigured()
                refreshConfiguredState()
                return@launch
            }
            ApplicationManager.getApplication().invokeLater {
                // The Refresh button re-checks the watched projects, then loads the Pull Requests from Bitbucket.
                otherProjectsPanel.loadOverview(coroutineScope = coroutineScope, refresh = PullRequestCacheRefresh.Force)
                otherProjectsPanel.bindWatchedProjects()
            }
        }
    }

    /**
     * Cancels only this project's coroutine scope. The OAuth login runs in the application-level
     * [DiffyOAuthLoginService], so closing one project does not cancel a login started from another project's
     * settings page.
     */
    fun disposePanel() {
        LOG.debug("Disposing Diffy - Bitbucket PR Review tool window panel for project ${project.name}")
        coroutineScope.cancel()
        Disposer.dispose(recentProjectsPanel)
    }

    companion object {
        private val LOG = logger<DiffyToolWindowPanel>()

        private fun createDefaultProjectRepositoryLinkService(configurationService: DiffyConfigurationService): DiffyProjectRepositoryLinkService {
            val application = ApplicationManager.getApplication()
            return DiffyProjectRepositoryLinkService(
                accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
                repositoryClient = OkHttpBitbucketRepositoryClient(),
                configurationService = configurationService
            )
        }

        private fun createDefaultOpenPullRequestService(configurationService: DiffyConfigurationService): DiffyOpenPullRequestService {
            val application = ApplicationManager.getApplication()
            return DiffyOpenPullRequestService(
                accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
                pullRequestClient = OkHttpBitbucketPullRequestClient(),
                configurationService = configurationService,
                pullRequestCache = DiffyPullRequestCache.getInstance()
            )
        }

        private fun createDefaultWatchedPullRequestService(openPullRequestService: DiffyOpenPullRequestService): DiffyWatchedProjectsPullRequestService {
            val application = ApplicationManager.getApplication()
            return DiffyWatchedProjectsPullRequestService(
                accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
                openPullRequestService = openPullRequestService,
                pullRequestClient = OkHttpBitbucketPullRequestClient(),
                pullRequestCache = DiffyPullRequestCache.getInstance()
            )
        }

        /** Feeds the Pull Requests source of the pull requests the current user opened. */
        private fun createDefaultAuthoredPullRequestService(): DiffyAuthoredPullRequestService {
            val application = ApplicationManager.getApplication()
            return DiffyAuthoredPullRequestService(
                accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
                pullRequestClient = OkHttpBitbucketPullRequestClient(),
                pullRequestCache = DiffyPullRequestCache.getInstance()
            )
        }
    }
}