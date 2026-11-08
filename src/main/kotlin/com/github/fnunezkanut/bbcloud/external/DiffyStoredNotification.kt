package com.github.fnunezkanut.bbcloud.external

import kotlin.time.Instant

/**
 * One persisted notification: the event as the Chrome extension sent it, plus the read flag owned by the plugin.
 *
 * [unread] is true by default. The `readState` Bitbucket reports is ignored. [changedAt] is when the user last changed
 * the flag; it decides which side wins when two IDE processes changed the same notification.
 */
data class DiffyStoredNotification(val event: DiffyBrowserNotificationEvent, val unread: Boolean = true, val changedAt: Instant? = null)