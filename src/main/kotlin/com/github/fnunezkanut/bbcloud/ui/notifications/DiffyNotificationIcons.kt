package com.github.fnunezkanut.bbcloud.ui.notifications

import org.jetbrains.jewel.ui.icon.IntelliJIconKey

/**
 * Icons of the Notifications tab that the platform does not offer. The robot marks a comment by a bot. Like the comment
 * toolbar icons, the key points at the same expui SVG for the old and the new UI, and the light and `_dark` pair follows
 * the IDE theme on its own.
 */
internal object DiffyNotificationIcons {
    private const val ROBOT_PATH = "icons/expui/notifications/robot.svg"

    val robot: IntelliJIconKey = IntelliJIconKey(
        oldUiPath = ROBOT_PATH,
        newUiPath = ROBOT_PATH,
        iconClass = DiffyNotificationIcons::class.java
    )
}