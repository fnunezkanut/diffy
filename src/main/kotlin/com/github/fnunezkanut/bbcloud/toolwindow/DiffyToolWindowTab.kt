package com.github.fnunezkanut.bbcloud.toolwindow

/** The five permanent tabs of the Diffy - Bitbucket PR Review tool window, in header order. */
enum class DiffyToolWindowTab { PullRequests, Notifications, RecentProjects, WatchedProjects, Settings }

object DiffyToolWindowTabResolver {
    /**
     * The tab the tool window opens on: the tab that was selected last ([lastSelectedTab]). The Settings tab only opens
     * the Settings > Tools page, so a stored Settings selection (written by versions before 1.13.0) opens the Pull
     * Requests tab instead. While the plugin is unconfigured, every tab shows a link to the settings page.
     */
    fun initialTab(lastSelectedTab: DiffyToolWindowTab): DiffyToolWindowTab {
        return if (lastSelectedTab == DiffyToolWindowTab.Settings) {
            DiffyToolWindowTab.PullRequests
        } else {
            lastSelectedTab
        }
    }
}