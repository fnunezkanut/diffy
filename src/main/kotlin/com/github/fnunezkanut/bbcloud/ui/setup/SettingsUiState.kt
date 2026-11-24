package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod

/** Immutable state rendered by the Compose Settings screen. */
data class SettingsUiState(
    // Whether an OAuth login is stored.
    val hasOAuthTokens: Boolean = false,
    // The working authentication method as determined by the online credential check; None when nothing verifies.
    val workingMethod: DiffyAuthenticationMethod = DiffyAuthenticationMethod.None,
    val projectsDirectory: String? = null,
    // Stored Bitbucket workspace slug shown in the authentication status label; null when none is stored.
    val workspaceSlug: String? = null,
    val isBusy: Boolean = false,
    // Whether an OAuth login is currently running (possibly started from another project's settings page).
    val isOAuthLoginInProgress: Boolean = false,
    // When the browser could not be opened, the authorize URL to show for manual paste; null otherwise.
    val oauthBrowserFallbackUrl: String? = null,
    // The OAuth status message (success or error) shown under the OAuth button; null hides it.
    val oauthMessage: String? = null,
    val oauthMessageIsError: Boolean = false,
    val pluginVersionFooter: String
) {
    /**
     * The active authentication method. It mirrors [workingMethod], the result of the online credential check, so the
     * status label reflects what actually works rather than merely what is stored.
     */
    val authenticationMethod: DiffyAuthenticationMethod
        get() = workingMethod

    /**
     * Whether an OAuth login is stored but not verified working, so the status label can warn the user to
     * authenticate again (for example after a revoked OAuth session).
     */
    val hasStoredButNotWorkingCredentials: Boolean
        get() = hasOAuthTokens && workingMethod == DiffyAuthenticationMethod.None
}