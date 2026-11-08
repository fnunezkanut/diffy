package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.external.DiffyBrowserEventCategory
import com.github.fnunezkanut.bbcloud.ui.pullrequest.help.WorktreesHelpMarkdown
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class NotificationIconKeyTest {
    @Test
    fun `notificationIconKey - should map every category to an icon`() {
        // GIVEN every category
        val categories = DiffyBrowserEventCategory.entries

        // WHEN
        val keys = categories.map { category -> notificationIconKey(category = category) }

        // THEN
        keys.size shouldBe categories.size
    }

    @Test
    fun `notificationIconKey - should use the eye for reviewer added and the robot for bot comments`() {
        // GIVEN / WHEN
        val reviewerAdded = notificationIconKey(category = DiffyBrowserEventCategory.REVIEWER_ADDED)
        val botComment = notificationIconKey(category = DiffyBrowserEventCategory.BOT_REVIEW_COMMENT)

        // THEN
        assertSoftly {
            reviewerAdded shouldBe AllIconsKeys.General.InspectionsEye
            botComment shouldBe DiffyNotificationIcons.robot
        }
    }

    @Test
    fun `robot icon - should ship the light and the dark svg`() {
        // GIVEN / WHEN
        val light = javaClass.getResource("/icons/expui/notifications/robot.svg")
        val dark = javaClass.getResource("/icons/expui/notifications/robot_dark.svg")

        // THEN
        assertSoftly {
            light shouldNotBe null
            dark shouldNotBe null
        }
    }

    @Test
    fun `loadNotifications - should load the packaged notifications help page`() {
        // GIVEN / WHEN
        val markdown = WorktreesHelpMarkdown.loadNotifications()

        // THEN
        markdown.startsWith("# Notifications") shouldBe true
    }
}