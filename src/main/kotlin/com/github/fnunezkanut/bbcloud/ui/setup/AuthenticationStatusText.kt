package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod

/**
 * Builds the Settings authentication status label text. It lives outside the screen so it can be unit tested without
 * touching Compose or the hosting tool window, mirroring [SetupFooterText].
 */
internal object AuthenticationStatusText {
    /**
     * The status line for [method]. When a workspace slug is stored it is shown as "Workspace: <slug>"; a null or
     * blank slug drops that suffix so a partially completed older setup still reads cleanly.
     */
    fun build(method: DiffyAuthenticationMethod, workspaceSlug: String?): String {
        if (method == DiffyAuthenticationMethod.None) {
            return DiffyBundle.message("setup.authentication.status.none")
        }
        return if (workspaceSlug.isNullOrBlank()) {
            DiffyBundle.message("setup.authentication.status.authenticated")
        } else {
            DiffyBundle.message("setup.authentication.status.authenticatedWithWorkspace", workspaceSlug)
        }
    }
}