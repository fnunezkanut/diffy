package com.github.fnunezkanut.bbcloud.settings

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyOAuthLoginService
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.ui.DiffyToolWindowPanel
import com.github.fnunezkanut.bbcloud.ui.setup.DiffySettingsScreen
import com.github.fnunezkanut.bbcloud.ui.setup.DiffySettingsViewModel
import com.github.fnunezkanut.bbcloud.ui.setup.chooseWorkspaceWithDialog
import com.github.fnunezkanut.bbcloud.ui.setup.confirmResetWithDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.jewel.bridge.compose
import java.awt.Component
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * The Settings > Tools > Diffy - Bitbucket PR Review page. It hosts the plugin settings screen: projects directory,
 * authentication, a link to the Watched Projects tab, and reset.
 *
 * Every control persists its change immediately (the API token Save, the OAuth login, the directory chooser, and
 * Reset), so the page never reports itself modified and [apply] has nothing to do. Each change, and closing the page,
 * refreshes the tool window tab that is currently shown.
 *
 * The page implements [Configurable.NoScroll] because the Settings dialog otherwise puts it in a scroll pane, where the
 * Compose panel is measured with an unbounded height and the scrollable settings screen collapses to an empty page; the
 * screen scrolls by itself.
 */
class DiffyToolsConfigurable(private val project: Project) :
    Configurable,
    Configurable.NoScroll {
    private val configurationService: DiffyConfigurationService = DiffyConfigurationService.getInstance()

    /** Created with the component and cancelled when the platform disposes the page. */
    private var coroutineScope: CoroutineScope? = null
    private var viewModel: DiffySettingsViewModel? = null
    private var rootComponent: JComponent? = null

    override fun getDisplayName(): String {
        return DiffyBundle.message("configurable.tools.displayName")
    }

    override fun createComponent(): JComponent {
        LOG.debug("Creating the Diffy - Bitbucket PR Review settings page for project ${project.name}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settingsViewModel = createViewModel(coroutineScope = scope)
        coroutineScope = scope
        viewModel = settingsViewModel
        val component = compose {
            DiffySettingsScreen(
                viewModel = settingsViewModel,
                onOpenWatchedProjects = { hostComponent -> onOpenWatchedProjects(hostComponent = hostComponent) }
            )
        }
        rootComponent = component
        return component
    }

    override fun isModified(): Boolean {
        return false
    }

    override fun apply() {
        // Every control on this page persists its change immediately.
    }

    override fun reset() {
        bindSettingsState()
    }

    override fun disposeUIResources() {
        LOG.debug("Disposing the Diffy - Bitbucket PR Review settings page for project ${project.name}")
        coroutineScope?.cancel()
        coroutineScope = null
        viewModel = null
        rootComponent = null
        refreshToolWindow()
    }

    /** Reloads the page from the stored state: the token outline, OAuth status, and projects-directory status. */
    private fun bindSettingsState() {
        val scope = coroutineScope ?: return
        val settingsViewModel = viewModel ?: return
        scope.launch {
            val hasStoredToken = configurationService.hasStoredToken()
            val hasOAuthTokens = configurationService.hasStoredOAuthTokens()
            val workingMethod = configurationService.authenticationMethod()
            ApplicationManager.getApplication().invokeLater {
                settingsViewModel.bindSettingsState(
                    hasStoredToken = hasStoredToken,
                    hasOAuthTokens = hasOAuthTokens,
                    workingMethod = workingMethod
                )
            }
        }
    }

    private fun createViewModel(coroutineScope: CoroutineScope): DiffySettingsViewModel {
        return DiffySettingsViewModel(
            configurationService = configurationService,
            coroutineScope = coroutineScope,
            oauthLoginService = ApplicationManager.getApplication().service<DiffyOAuthLoginService>(),
            onSaveSuccess = { onConfigurationChanged() },
            onTokenReset = { onPluginReset() },
            // An OAuth login that finished or was cancelled from another project's settings page refreshes this page's
            // status so the working-method label stays correct.
            onExternalAuthChanged = { onConfigurationChanged() },
            onProjectsDirectoryChosenCallback = { absolutePath -> onProjectsDirectoryChosen(absolutePath = absolutePath) },
            chooseWorkspace = ::chooseWorkspaceWithDialog,
            confirmReset = ::confirmResetWithDialog
        )
    }

    private fun onConfigurationChanged() {
        bindSettingsState()
        refreshToolWindow()
    }

    private fun onPluginReset() {
        bindSettingsState()
        toolWindowPanelOrNull()?.onPluginReset()
    }

    private fun onProjectsDirectoryChosen(absolutePath: String) {
        LOG.debug("Settings projects directory chosen; persisting and rescanning watched projects")
        // saveProjectsDirectory empties the watched map; the rescan rebuilds it clear-first so rediscovered
        // repositories come in unwatched rather than carrying over selections from the old directory.
        configurationService.saveProjectsDirectory(absolutePath = absolutePath)
        toolWindowPanelOrNull()?.onProjectsDirectoryChanged()
        refreshToolWindow()
    }

    /**
     * Closes the Settings window, then shows the tool window on the Watched Projects tab. The tab switch is deferred
     * until no modal dialog is open, so it runs after [disposeUIResources] refreshed the previously shown tab. Without
     * a project (Settings opened from the Welcome screen) there is no tool window, so the click only closes Settings.
     */
    private fun onOpenWatchedProjects(hostComponent: Component) {
        LOG.info("Opening the Watched Projects tab from the settings page")
        closeSettingsWindow(hostComponent = hostComponent)
        ApplicationManager.getApplication().invokeLater(
            { openWatchedProjectsTab() },
            ModalityState.nonModal()
        )
    }

    /**
     * Closes whichever window hosts this page. The platform shows Settings either as a modal [DialogWrapper] or, in
     * 2026.3, as a non-modal settings window (a JDialog or JFrame that is not a DialogWrapper) or inside the Settings
     * tool window of the IDE frame. The modal dialog is closed through its wrapper. The non-modal window is sent a
     * WINDOW_CLOSING event, as if the user clicked its close button, so the platform still asks about unapplied
     * changes. The IDE frame itself is never closed; in that mode the page is left as it is.
     */
    private fun closeSettingsWindow(hostComponent: Component) {
        val component = rootComponent ?: hostComponent
        val dialog = DialogWrapper.findInstance(component) ?: DialogWrapper.findInstance(hostComponent)
        if (dialog != null) {
            LOG.info("Closing the modal Settings dialog")
            dialog.close(DialogWrapper.OK_EXIT_CODE)
            return
        }
        val window = SwingUtilities.getWindowAncestor(component) ?: SwingUtilities.getWindowAncestor(hostComponent)
        if (window == null) {
            LOG.info("Not closing Settings: the page has no window")
            return
        }
        if (window is IdeFrame || window == WindowManager.getInstance().getFrame(project)) {
            LOG.info("Not closing Settings: the page is shown inside the IDE frame")
            return
        }
        LOG.info("Closing the non-modal Settings window ${window.javaClass.simpleName}")
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
    }

    private fun openWatchedProjectsTab() {
        if (project.isDefault || project.isDisposed) {
            LOG.info("Not opening the Watched Projects tab: no project tool window is available")
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            LOG.info("Not opening the Watched Projects tab: the tool window is unavailable")
            return
        }
        val panel = project.service<DiffyToolWindowPanel>()
        toolWindow.show {
            if (!panel.showWatchedProjectsTab()) {
                LOG.info("Cannot open the Watched Projects tab: its content is unavailable")
            }
        }
    }

    private fun refreshToolWindow() {
        toolWindowPanelOrNull()?.refreshSelectedTab()
    }

    /** The default project (Settings opened from the Welcome screen) has no tool window to refresh. */
    private fun toolWindowPanelOrNull(): DiffyToolWindowPanel? {
        if (project.isDefault || project.isDisposed) {
            return null
        }
        return project.service<DiffyToolWindowPanel>()
    }

    companion object {
        private val LOG = logger<DiffyToolsConfigurable>()
        private const val TOOL_WINDOW_ID = "Diffy - Bitbucket PR Review"

        /** Opens Settings on the Tools > Diffy - Bitbucket PR Review page. Must be called on the EDT. */
        fun open(project: Project) {
            LOG.info("Opening the Diffy - Bitbucket PR Review settings page")
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DiffyToolsConfigurable::class.java)
        }
    }
}