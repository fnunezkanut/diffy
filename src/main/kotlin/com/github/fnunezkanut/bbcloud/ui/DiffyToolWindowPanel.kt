package com.github.fnunezkanut.bbcloud.ui

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.auth.DiffyOAuthLoginService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyAuthoredPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyProjectRepositoryLinkService
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyReviewQueueService
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketPullRequestClient
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketRepositoryClient
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.project.LocalProjectRepositorySlug
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTab
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTabResolver
import com.github.fnunezkanut.bbcloud.ui.main.DiffyMainPanel
import com.github.fnunezkanut.bbcloud.ui.notifications.NotificationsPanel
import com.github.fnunezkanut.bbcloud.ui.otherprojects.DiffyOtherProjectsPanel
import com.github.fnunezkanut.bbcloud.ui.recentprojects.RecentProjectsPanel
import com.github.fnunezkanut.bbcloud.ui.setup.DiffySettingsScreen
import com.github.fnunezkanut.bbcloud.ui.setup.DiffySettingsViewModel
import com.github.fnunezkanut.bbcloud.ui.setup.chooseWorkspaceWithDialog
import com.github.fnunezkanut.bbcloud.ui.setup.confirmResetWithDialog
import com.intellij.icons.AllIcons
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
import org.jetbrains.jewel.bridge.compose
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Owns the six tool window tabs and the panels behind them. The Current Project tab opens by default once a
 * Bitbucket workspace and API token exist, otherwise the Settings tab opens. Tabs are never redirected: while the plugin
 * is unconfigured, the other tabs offer a link to the Settings tab instead.
 */
@Service(Service.Level.PROJECT)
class DiffyToolWindowPanel(private val project: Project) {
    private val configurationService: DiffyConfigurationService = DiffyConfigurationService.getInstance()
    private val projectRepositoryLinkService: DiffyProjectRepositoryLinkService = createDefaultProjectRepositoryLinkService(
        configurationService = configurationService
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsViewModel = createSettingsViewModel()
    private val settingsComposeComponent: JComponent = compose {
        DiffySettingsScreen(viewModel = settingsViewModel)
    }
    private val mainPanel = DiffyMainPanel(
        configurationService = configurationService,
        projectRepositoryLinkService = projectRepositoryLinkService,
        openPullRequestService = createDefaultOpenPullRequestService(configurationService = configurationService),
        onGoToSettings = { selectTab(tab = DiffyToolWindowTab.Settings) }
    )
    private val otherProjectsPanel = DiffyOtherProjectsPanel(
        project = project,
        configurationService = configurationService,
        authoredPullRequestService = createDefaultAuthoredPullRequestService(),
        reviewQueueService = createDefaultReviewQueueService(),
        coroutineScope = coroutineScope,
        onGoToSettings = { selectTab(tab = DiffyToolWindowTab.Settings) },
        onGoToWatchedProjects = { showWatchedProjectsTab() },
        onGoToWorkspace = { showWorkspacePrsTab() },
        onRefreshRequested = { refreshWorkspace() }
    )

    private val recentProjectsPanel = RecentProjectsPanel(
        project = project,
        coroutineScope = coroutineScope,
        isPruneInProgress = { otherProjectsPanel.isPruneInProgress() }
    )

    private val notificationsPanel = NotificationsPanel(
        project = project,
        coroutineScope = coroutineScope,
        onGoToSettings = { selectTab(tab = DiffyToolWindowTab.Settings) }
    )

    /** Set by the async configuration check; null while it is still in flight. */
    private var isConfigured: Boolean? = null
    private var contentManager: ContentManager? = null
    private var isProgrammaticSelection = false
    private var contentByTab: Map<DiffyToolWindowTab, Content> = emptyMap()

    /** Non-closeable components for each of the six tabs; wired from coordinators. */
    val projectPrsComponent: JComponent = mainPanel.projectPrsTabComponent
    val workspacePrsComponent: JComponent = otherProjectsPanel.workspacePrsTabComponent
    val workspaceWatchedProjectsComponent: JComponent = otherProjectsPanel.watchedProjectsTabComponent
    val recentProjectsComponent: JComponent = recentProjectsPanel.component
    val notificationsComponent: JComponent = notificationsPanel.component
    val settingsComponent: JComponent = settingsComposeComponent

    /** Builds the six non-closeable tabs, in header order. */
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
        return contents
    }

    /**
     * Hands a freshly created pull request to the main panel, which switches the current checkout to the main branch,
     * reloads the listings, and opens the pull request in its own work tree. Returns false when the main panel is not
     * bound to a project.
     */
    fun openCreatedPullRequest(summary: PullRequestSummary, localMainBranch: String?): Boolean {
        LOG.debug("Handing created pull request #${summary.id} to the main panel")
        return mainPanel.openCreatedPullRequest(summary = summary, localMainBranch = localMainBranch)
    }

    /** Selects Current Project and restores its listing card without starting a reload. */
    fun showCurrentProjectPullRequestsListing(): Boolean {
        val contentManager = contentManager ?: return false
        val content = contentByTab[DiffyToolWindowTab.ProjectPrs] ?: return false
        isProgrammaticSelection = true
        try {
            contentManager.setSelectedContent(content)
        } finally {
            isProgrammaticSelection = false
        }
        mainPanel.showCurrentProjectPullRequestsListing()
        return true
    }

    /** Selects the Settings tab and rebinds its state. Returns false if the tab or its content is unavailable. */
    fun showSettings(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.Settings] ?: return false
        LOG.info("User opened Diffy - Bitbucket PR Review settings from the Tools settings page")
        selectTab(tab = DiffyToolWindowTab.Settings)
        return true
    }

    /** Selects the Workspace - Watched Projects tab. Returns false if the tab or its content is unavailable. */
    fun showWatchedProjectsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.WorkspaceWatchedProjects] ?: return false
        selectTab(tab = DiffyToolWindowTab.WorkspaceWatchedProjects)
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

    /** Selects the Workspace PRs tab. Returns false if the tab or its content is unavailable. */
    fun showWorkspacePrsTab(): Boolean {
        contentManager ?: return false
        contentByTab[DiffyToolWindowTab.WorkspacePrs] ?: return false
        selectTab(tab = DiffyToolWindowTab.WorkspacePrs)
        return true
    }

    /** Starts listening for tab selection and resolves the tab that opens with the tool window. */
    fun bind(contentManager: ContentManager, parentDisposable: Disposable) {
        this.contentManager = contentManager
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
                applyTabSelection(tab = tab)
            }
        }
        contentManager.addContentManagerListener(listener)
        Disposer.register(parentDisposable) {
            contentManager.removeContentManagerListener(listener)
        }
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            LOG.debug("Initial tool window state: configured=$configured")
            ApplicationManager.getApplication().invokeLater {
                applyInitialTabState(configured = configured)
            }
        }
    }

    private fun applyInitialTabState(configured: Boolean) {
        isConfigured = configured
        selectTab(tab = DiffyToolWindowTabResolver.initialTab(isConfigured = configured))
    }

    private fun selectTab(tab: DiffyToolWindowTab) {
        val contentManager = contentManager ?: return
        val content = contentByTab[tab] ?: return
        isProgrammaticSelection = true
        try {
            contentManager.setSelectedContent(content)
        } finally {
            isProgrammaticSelection = false
        }
        applyTabSelection(tab = tab)
    }

    private fun applyTabSelection(tab: DiffyToolWindowTab) {
        when (tab) {
            DiffyToolWindowTab.ProjectPrs -> refreshProjectPrs()
            DiffyToolWindowTab.WorkspacePrs -> refreshWorkspacePrs()
            DiffyToolWindowTab.WorkspaceWatchedProjects -> refreshWorkspaceWatchedProjects()
            DiffyToolWindowTab.RecentProjects -> refreshRecentProjects()
            DiffyToolWindowTab.Notifications -> refreshNotifications()
            DiffyToolWindowTab.Settings -> bindSettingsState()
        }
    }

    private fun refreshProjectPrs() {
        LOG.debug("Refreshing the Current Project tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                if (isSelected(tab = DiffyToolWindowTab.ProjectPrs)) {
                    if (configured) {
                        mainPanel.refresh(project = project, coroutineScope = coroutineScope)
                    } else {
                        mainPanel.showNotConfigured()
                    }
                }
            }
        }
    }

    private fun refreshWorkspacePrs() {
        LOG.debug("Refreshing the Workspace tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                if (isSelected(tab = DiffyToolWindowTab.WorkspacePrs)) {
                    if (configured) {
                        otherProjectsPanel.loadWorkspacePullRequests(
                            excludedRepoSlug = LocalProjectRepositorySlug.fromBasePath(project.basePath),
                            coroutineScope = coroutineScope
                        )
                    } else {
                        otherProjectsPanel.showNotConfigured()
                    }
                }
            }
        }
    }

    private fun refreshWorkspaceWatchedProjects() {
        LOG.debug("Refreshing the Workspace - Watched Projects tab")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                if (isSelected(tab = DiffyToolWindowTab.WorkspaceWatchedProjects)) {
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
                isConfigured = configured
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

    /** Reloads the Settings tab from the stored state: the token outline, OAuth status, and projects-directory status. */
    private fun bindSettingsState() {
        coroutineScope.launch {
            val hasStoredToken = configurationService.hasStoredToken()
            val hasOAuthTokens = configurationService.hasStoredOAuthTokens()
            val workingMethod = configurationService.authenticationMethod()
            val configured = configurationService.isConfigured()
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                settingsViewModel.bindSettingsState(
                    hasStoredToken = hasStoredToken,
                    hasOAuthTokens = hasOAuthTokens,
                    workingMethod = workingMethod
                )
            }
        }
    }

    private fun refreshAfterReset() {
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            LOG.debug("Refreshing tool window after token reset: configured=$configured")
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                // resetAll already wiped the persisted avatar-URL map; also drop the in-memory decoded repository
                // avatars so the Watched Projects tab shows placeholders until the next Sync repopulates them.
                otherProjectsPanel.resetWatchedProjectsAvatars()
                recentProjectsPanel.resetAvatars()
                selectTab(tab = DiffyToolWindowTab.Settings)
            }
        }
    }

    private fun refreshAfterSave() {
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            LOG.debug("Refreshing tool window after save: configured=$configured")
            ApplicationManager.getApplication().invokeLater {
                isConfigured = configured
                // Stay on Settings after a save (R1): a token-only save is not fully configured under the tightened
                // contract, and the user remains on Settings to finish configuring the directories. Re-binding the
                // Settings tab refreshes the saved token outline and directory status rows.
                selectTab(tab = DiffyToolWindowTab.Settings)
            }
        }
    }

    private fun componentFor(tab: DiffyToolWindowTab): JComponent {
        return when (tab) {
            DiffyToolWindowTab.ProjectPrs -> projectPrsComponent
            DiffyToolWindowTab.WorkspacePrs -> workspacePrsComponent
            DiffyToolWindowTab.WorkspaceWatchedProjects -> workspaceWatchedProjectsComponent
            DiffyToolWindowTab.RecentProjects -> recentProjectsComponent
            DiffyToolWindowTab.Notifications -> notificationsComponent
            DiffyToolWindowTab.Settings -> settingsComposeComponent
        }
    }

    private fun titleKeyFor(tab: DiffyToolWindowTab): String {
        return when (tab) {
            DiffyToolWindowTab.ProjectPrs -> "toolwindow.tab.projectPrs"
            DiffyToolWindowTab.WorkspacePrs -> "toolwindow.tab.workspacePrs"
            DiffyToolWindowTab.WorkspaceWatchedProjects -> "toolwindow.tab.workspaceWatchedProjects"
            DiffyToolWindowTab.RecentProjects -> "toolwindow.tab.recentProjects"
            DiffyToolWindowTab.Notifications -> "toolwindow.tab.notifications"
            DiffyToolWindowTab.Settings -> "toolwindow.tab.settings"
        }
    }

    private fun iconFor(tab: DiffyToolWindowTab): Icon {
        return when (tab) {
            DiffyToolWindowTab.ProjectPrs -> AllIcons.Nodes.IdeaProject
            DiffyToolWindowTab.WorkspacePrs -> AllIcons.Nodes.ModuleGroup
            DiffyToolWindowTab.WorkspaceWatchedProjects -> AllIcons.Actions.Show
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
        // Refresh all workspace tabs when the refresh button is clicked, regardless of the selected tab
        LOG.debug("User requested a reload of the Workspace tabs")
        coroutineScope.launch {
            val configured = configurationService.isConfigured()
            if (!configured) {
                otherProjectsPanel.showNotConfigured()
                // Route the Watched Projects tab to its centered not-configured card too, so both Workspace tabs
                // show the generic link rather than leaving Watched Projects on stale content.
                otherProjectsPanel.showWatchedProjectsNotConfigured()
                return@launch
            }
            val excludedRepoSlug = LocalProjectRepositorySlug.fromBasePath(project.basePath)
            ApplicationManager.getApplication().invokeLater {
                // The Refresh button re-checks the watched projects, then loads both tabs from Bitbucket.
                otherProjectsPanel.loadWorkspacePullRequests(
                    excludedRepoSlug = excludedRepoSlug,
                    coroutineScope = coroutineScope,
                    refresh = PullRequestCacheRefresh.Force
                )
                otherProjectsPanel.bindWatchedProjects()
            }
        }
    }

    private fun createSettingsViewModel(): DiffySettingsViewModel {
        return DiffySettingsViewModel(
            configurationService = configurationService,
            coroutineScope = coroutineScope,
            oauthLoginService = ApplicationManager.getApplication().service<DiffyOAuthLoginService>(),
            onSaveSuccess = { refreshAfterSave() },
            onTokenReset = { refreshAfterReset() },
            // An OAuth login that finished or was cancelled from another project's Settings tab refreshes this tab's
            // status so the working-method label stays correct (decision 13b).
            onExternalAuthChanged = { bindSettingsState() },
            // Persists the chosen projects directory and triggers the exact scan that used to run from the Watched
            // Projects green button. otherProjectsPanel is read lazily here because this panel is built before it.
            onProjectsDirectoryChosenCallback = { absolutePath ->
                LOG.debug("Settings projects directory chosen; persisting and rescanning watched projects")
                // saveProjectsDirectory empties the watched map; the rescan rebuilds it clear-first so rediscovered
                // repositories come in unwatched rather than carrying over selections from the old directory.
                configurationService.saveProjectsDirectory(absolutePath = absolutePath)
                otherProjectsPanel.rescanWatchedProjects(clearFirst = true)
            },
            chooseWorkspace = ::chooseWorkspaceWithDialog,
            confirmReset = ::confirmResetWithDialog
        )
    }

    /**
     * Cancels only this project's coroutine scope. The OAuth login runs in the application-level
     * [DiffyOAuthLoginService], so closing one project does not cancel a login started from another project's
     * Settings tab.
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

        private fun createDefaultReviewQueueService(): DiffyReviewQueueService {
            val application = ApplicationManager.getApplication()
            return DiffyReviewQueueService(
                accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
                pullRequestClient = OkHttpBitbucketPullRequestClient(),
                pullRequestCache = DiffyPullRequestCache.getInstance()
            )
        }

        /** Feeds the top section of the Other Projects tab; the review queue above is not wired to a section yet. */
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