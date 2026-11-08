package com.github.fnunezkanut.bbcloud.toolwindow

/** The six permanent tabs of the Diffy - Bitbucket PR Review tool window, in header order. */
enum class DiffyToolWindowTab { ProjectPrs, WorkspacePrs, WorkspaceWatchedProjects, RecentProjects, Notifications, Settings }

object DiffyToolWindowTabResolver {
    /**
     * The tab the tool window opens on. Settings opens first while the plugin is unconfigured, so first-time configuration
     * is one click away; the other tabs are never redirected and offer a link to Settings while unconfigured.
     *
     * [isConfigured] is null until the initial async configuration check resolves.
     */
    fun initialTab(isConfigured: Boolean?): DiffyToolWindowTab {
        return if (isConfigured == false) {
            DiffyToolWindowTab.Settings
        } else {
            DiffyToolWindowTab.ProjectPrs
        }
    }
}