package com.github.fnunezkanut.bbcloud.ui.setup

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.DiffyBuildInfo
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod
import com.github.fnunezkanut.bbcloud.auth.DiffyOAuthLoginService
import com.github.fnunezkanut.bbcloud.auth.OAuthLoginResult
import com.github.fnunezkanut.bbcloud.auth.OAuthLoginState
import com.github.fnunezkanut.bbcloud.auth.OAuthTokens
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyMainBranchAutoUpdateService
import com.github.fnunezkanut.bbcloud.git.MainBranchAutoUpdateControl
import com.github.fnunezkanut.bbcloud.settings.DiffySettings
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Holds Settings state and coordinates persistence without depending on Swing or Compose UI components.
 *
 * Dialog callbacks are supplied by the hosting UI so the workspace chooser and reset confirmation remain platform
 * dialogs while all save/reset decisions stay testable and independent of the rendered screen.
 */
class DiffySettingsViewModel(
    private val configurationService: DiffyConfigurationService,
    private val coroutineScope: CoroutineScope,
    private val oauthLoginService: DiffyOAuthLoginService,
    private val onSaveSuccess: () -> Unit,
    /** Called after Reset Plugin has wiped the stored OAuth login and every other plugin setting. */
    private val onTokenReset: () -> Unit,
    private val onExternalAuthChanged: () -> Unit,
    private val onProjectsDirectoryChosenCallback: (String) -> Unit,
    private val chooseWorkspace: (slugs: List<String>, preselectedSlug: String) -> String?,
    private val confirmReset: () -> Boolean,
    /** Resolved when used, so unit tests never touch the IDE application. */
    private val mainBranchAutoUpdate: () -> MainBranchAutoUpdateControl = { DiffyMainBranchAutoUpdateService.getInstance() }
) {
    val state: MutableState<SettingsUiState> = mutableStateOf(
        SettingsUiState(pluginVersionFooter = SetupFooterText.build(resolvePluginVersion()))
    )

    // True while this view model owns the active OAuth login, so it does not react to its own finish as "external".
    private var ownsLogin = false

    init {
        // Mirror the application-level login state so every project's settings page shows the same progress and can
        // cancel it (decision 13b). A finish or cancel started elsewhere refreshes this tab's status.
        coroutineScope.launch {
            oauthLoginService.state.collect { loginState ->
                mirrorLoginState(loginState = loginState)
            }
        }
    }

    private fun mirrorLoginState(loginState: OAuthLoginState) {
        when (loginState) {
            is OAuthLoginState.InProgress -> {
                state.value = state.value.copy(
                    isOAuthLoginInProgress = true,
                    oauthBrowserFallbackUrl = loginState.browserLaunchFailedUrl
                )
            }
            is OAuthLoginState.Idle -> {
                state.value = state.value.copy(isOAuthLoginInProgress = false, oauthBrowserFallbackUrl = null)
                if (!ownsLogin) {
                    onExternalAuthChanged()
                }
            }
            is OAuthLoginState.Finished -> {
                state.value = state.value.copy(isOAuthLoginInProgress = false, oauthBrowserFallbackUrl = null)
                if (!ownsLogin) {
                    onExternalAuthChanged()
                }
            }
        }
    }

    /**
     * Re-reads persisted Settings values when the settings page opens and after each change.
     *
     * A successful OAuth login re-binds the settings page (see DiffyToolsConfigurable). The OAuth message is retained
     * across a re-bind so the user can still read it.
     */
    fun bindSettingsState(hasOAuthTokens: Boolean, workingMethod: DiffyAuthenticationMethod) {
        coroutineScope.launch {
            val projectsDirectory = configurationService.projectsDirectoryOrNull()
            val workspaceSlug = configurationService.workspaceSlugOrNull()
            state.value = state.value.copy(
                hasOAuthTokens = hasOAuthTokens,
                workingMethod = workingMethod,
                projectsDirectory = projectsDirectory,
                workspaceSlug = workspaceSlug,
                isBusy = false
            )
        }
    }

    /** Whether pull request diffs in a work tree are editable ("Make pull request diffs editable" on Settings). */
    fun isEditableDiffsEnabled(): Boolean {
        return DiffySettings.getInstance().editableSourceSideInDiffs
    }

    /** Persists the "Make pull request diffs editable" choice at once. */
    fun onEditableDiffsChanged(enabled: Boolean) {
        LOG.info("User changed 'Make pull request diffs editable' on Settings: enabled=$enabled")
        DiffySettings.getInstance().editableSourceSideInDiffs = enabled
    }

    /** Whether the main branch of each watched project is kept up to date ("Auto-update main branches" on Settings). */
    fun isAutoUpdateMainBranchesEnabled(): Boolean {
        return DiffySettings.getInstance().autoUpdateMainBranches
    }

    /** Persists the "Auto-update main branches" choice at once and starts or stops the background job. */
    fun onAutoUpdateMainBranchesChanged(enabled: Boolean) {
        LOG.info("User changed 'Auto-update main branches' on Settings: enabled=$enabled")
        DiffySettings.getInstance().autoUpdateMainBranches = enabled
        mainBranchAutoUpdate().onSettingChanged(enabled = enabled)
    }

    /**
     * Maps a resolve outcome to the matching bundle message, or delegates to the workspace chooser. [onResolved] runs
     * with the chosen workspace slug; [onError] shows a message; [unauthorizedMessage] is used for an Unauthorized
     * outcome (the missing-scopes message, since the OAuth tokens are not saved).
     */
    private suspend fun applyResolution(
        resolution: WorkspaceResolution,
        onResolved: suspend (String) -> Unit,
        onError: (String) -> Unit,
        unauthorizedMessage: String
    ) {
        when (resolution) {
            is WorkspaceResolution.Resolved -> {
                LOG.info("Setup auto-selected the only accessible workspace: workspaceSlug=${resolution.slug}")
                onResolved(resolution.slug)
            }

            is WorkspaceResolution.NeedsConfirmation -> {
                LOG.debug("Offering ${resolution.slugs.size} Bitbucket workspaces")
                val chosenSlug = chooseWorkspace(resolution.slugs, resolution.preselectedSlug)
                if (chosenSlug == null) {
                    LOG.info("Setup cancelled at the workspace chooser")
                    setBusy(false)
                    return
                }
                LOG.info("Setup confirmed: workspaceSlug=$chosenSlug")
                onResolved(chosenSlug)
            }

            WorkspaceResolution.NoneAccessible -> {
                LOG.info("Setup aborted: the login has no accessible workspaces")
                onError(DiffyBundle.message("setup.workspaces.empty"))
            }

            WorkspaceResolution.Unauthorized -> {
                LOG.info("Setup aborted: the access token was rejected by Bitbucket")
                onError(unauthorizedMessage)
            }

            is WorkspaceResolution.Failed -> {
                LOG.warn("Failed to list Bitbucket workspaces", resolution.cause)
                onError(DiffyBundle.message("setup.workspaces.error.failed"))
            }
        }
    }

    /** Starts the OAuth login flow; progress and the result are mirrored from the application login service. */
    fun onOAuthLoginClicked() {
        LOG.info("User clicked Authenticate with Bitbucket oauth login on Settings")
        if (state.value.isOAuthLoginInProgress || state.value.isBusy) {
            LOG.info("Ignoring OAuth login click: a login or save is already in progress")
            return
        }
        state.value = state.value.copy(
            isBusy = true,
            oauthMessage = DiffyBundle.message("setup.authentication.oauth.waiting"),
            oauthMessageIsError = false
        )
        ownsLogin = true
        val deferred = oauthLoginService.start()
        coroutineScope.launch {
            try {
                when (val result = deferred.await()) {
                    is OAuthLoginResult.Success -> onOAuthLoginSucceeded(tokens = result.tokens)
                    is OAuthLoginResult.PortUnavailable -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.portUnavailable", 42424)
                    )
                    is OAuthLoginResult.BrowserLaunchFailed -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.browser", result.authorizeUrl)
                    )
                    is OAuthLoginResult.TimedOut -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.timedOut")
                    )
                    is OAuthLoginResult.Denied -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.denied")
                    )
                    is OAuthLoginResult.InvalidCallback -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.invalidCallback")
                    )
                    is OAuthLoginResult.ClientRejected -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.clientRejected")
                    )
                    is OAuthLoginResult.ExchangeFailed -> showOAuthError(
                        DiffyBundle.message("setup.authentication.oauth.error.exchangeFailed")
                    )
                }
            } finally {
                oauthLoginService.acknowledge()
                ownsLogin = false
                setBusy(false)
            }
        }
    }

    private suspend fun onOAuthLoginSucceeded(tokens: OAuthTokens) {
        val resolution = WorkspaceResolutionPolicy.resolve(
            result = configurationService.listWorkspaces(apiToken = tokens.accessToken),
            preferredSlug = configurationService.workspaceSlugOrNull()
        )
        applyResolution(
            resolution = resolution,
            onResolved = { slug -> persistOAuth(workspaceSlug = slug, tokens = tokens) },
            onError = { message -> showOAuthError(message) },
            // The OAuth login succeeded but the consumer lacks the scopes to list workspaces; tokens are not saved.
            unauthorizedMessage = DiffyBundle.message("setup.authentication.oauth.error.scopes")
        )
    }

    private suspend fun persistOAuth(workspaceSlug: String, tokens: OAuthTokens) {
        val result = configurationService.saveOAuthSetup(workspaceSlug = workspaceSlug, tokens = tokens)
        if (result.isSuccess) {
            LOG.info("OAuth setup save succeeded: workspaceSlug=$workspaceSlug")
            state.value = state.value.copy(
                hasOAuthTokens = true,
                workingMethod = DiffyAuthenticationMethod.OAuth,
                workspaceSlug = workspaceSlug,
                // The authenticated state is reported by the persistent AuthenticationStatus line, so the transient
                // OAuth success message is cleared here to avoid showing a duplicate.
                oauthMessage = null,
                oauthMessageIsError = false
            )
            onSaveSuccess()
        } else {
            LOG.warn("Failed to save OAuth setup", result.exceptionOrNull())
            showOAuthError(DiffyBundle.message("setup.error.saveFailed"))
        }
    }

    /** Cancels the running OAuth login; any project may cancel the shared login (decision 13b). */
    fun onOAuthLoginCancelClicked() {
        LOG.info("User clicked Cancel on the Bitbucket oauth login")
        oauthLoginService.cancel()
    }

    fun onResetClicked() {
        LOG.info("User clicked setup Reset")
        if (!confirmReset()) {
            return
        }
        // Cancel any in-flight OAuth login first; the reset removes the stored login.
        oauthLoginService.cancel()
        setBusy(true)
        // resetAll wipes every plugin setting: the OAuth login, workspace slug, projects directory, and
        // watched projects.
        coroutineScope.launch {
            configurationService.resetAll()
            // The reset turned the setting off; stop the job and drop its results.
            mainBranchAutoUpdate().onSettingChanged(enabled = false)
            state.value = state.value.copy(
                hasOAuthTokens = false,
                workingMethod = DiffyAuthenticationMethod.None,
                projectsDirectory = null,
                workspaceSlug = null,
                oauthMessage = null,
                oauthMessageIsError = false,
                isBusy = false
            )
            LOG.info("Diffy - Bitbucket PR Review plugin settings reset")
            onTokenReset()
        }
    }

    /** Persists and displays a directory selected by the platform folder chooser. */
    fun onProjectsDirectoryChosen(path: String) {
        onProjectsDirectoryChosenCallback(path)
        state.value = state.value.copy(projectsDirectory = configurationService.projectsDirectoryOrNull())
    }

    private fun setBusy(isBusy: Boolean) {
        state.value = state.value.copy(isBusy = isBusy)
    }

    private fun showOAuthError(message: String) {
        state.value = state.value.copy(oauthMessage = message, oauthMessageIsError = true)
    }

    private fun resolvePluginVersion(): String? {
        return DiffyBuildInfo.version() ?: DiffyBundle.message("about.versionUnknown")
    }

    companion object {
        private val LOG = logger<DiffySettingsViewModel>()
    }
}