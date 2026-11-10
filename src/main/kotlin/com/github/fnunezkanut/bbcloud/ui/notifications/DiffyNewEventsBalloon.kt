package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.ui.DiffyToolWindowPanel
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager

/**
 * Tells the user that the Diffy browser extension delivered notification events that were never seen before. One
 * balloon is shown in one open project (the one with the active window, otherwise the first), and its action opens the
 * Notifications tab. A newer balloon replaces the previous one.
 */
@Service(Service.Level.APP)
class DiffyNewEventsBalloon {
    /** Only touched on the event dispatch thread. */
    private var lastNotification: Notification? = null

    /** Shows the balloon for [count] new events. Does nothing for a count of zero or when the plugin has no workspace. */
    fun show(count: Int) {
        if (count <= 0) {
            return
        }
        if (DiffyConfigurationService.getInstance().workspaceSlugOrNull() == null) {
            LOG.debug("Not announcing $count new event notification(s): no workspace is configured")
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val project = targetProject()
            if (project == null) {
                LOG.debug("Not announcing $count new event notification(s): no open project")
                return@invokeLater
            }
            LOG.info("Showing the new event notifications balloon: count=$count")
            lastNotification?.expire()
            lastNotification = createNotification(project = project, count = count)
        }
    }

    private fun targetProject(): Project? {
        val openProjects = ProjectManager.getInstance().openProjects.filter { project -> !project.isDisposed }
        val windowManager = WindowManager.getInstance()
        return openProjects.firstOrNull { project -> windowManager.getFrame(project)?.isActive == true }
            ?: openProjects.firstOrNull()
    }

    private fun createNotification(project: Project, count: Int): Notification {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                DiffyBundle.message("notifications.newEvents.balloon", count),
                NotificationType.INFORMATION
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(DiffyBundle.message("notifications.newEvents.open")) {
                openNotificationsTab(project = project)
            }
        )
        notification.notify(project)
        return notification
    }

    private fun openNotificationsTab(project: Project) {
        LOG.info("User clicked Open Notifications in the new event notifications balloon")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            LOG.info("Cannot open the Notifications tab: Diffy - Bitbucket PR Review tool window is unavailable")
            return
        }
        val panel = project.service<DiffyToolWindowPanel>()
        toolWindow.show {
            if (!panel.showNotificationsTab()) {
                LOG.info("Cannot open the Notifications tab: its content is unavailable")
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyNewEventsBalloon>()
        private const val NOTIFICATION_GROUP_ID = "Diffy"
        private const val TOOL_WINDOW_ID = "Diffy - Bitbucket PR Review"
    }
}