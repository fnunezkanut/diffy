package com.github.fnunezkanut.bbcloud.external

import kotlin.time.Instant

/** The most notifications that are persisted. The oldest ones are evicted beyond it. */
const val MAX_STORED_NOTIFICATIONS = 2000

/**
 * Merges the events of a new snapshot into [existing].
 *
 * An unknown id is added as unread, whatever `readState` it carries. A known id gets its data refreshed and keeps its
 * read flag. Ids that the snapshot no longer lists are kept, which is what makes the list persistent. A repeated
 * incoming id keeps its first occurrence.
 */
fun mergeSnapshot(existing: List<DiffyStoredNotification>, incoming: List<DiffyBrowserNotificationEvent>): List<DiffyStoredNotification> {
    val byId = LinkedHashMap<String, DiffyStoredNotification>()
    existing.forEach { notification -> byId[notification.event.id] = notification }
    val seenIncoming = HashSet<String>()
    incoming.forEach { event ->
        if (!seenIncoming.add(event.id)) {
            return@forEach
        }
        val known = byId[event.id]
        byId[event.id] = if (known == null) {
            DiffyStoredNotification(event = event.withoutReadState())
        } else {
            known.copy(event = event.withoutReadState())
        }
    }
    return byId.values.toList()
}

/**
 * Counts the notifications a snapshot really added: ids of [incoming] that were not in [before] and are still in
 * [after], so an event the cap evicted right away is not counted. A repeated incoming id counts once.
 */
fun countNewNotifications(before: List<DiffyStoredNotification>, after: List<DiffyStoredNotification>, incoming: List<DiffyBrowserNotificationEvent>): Int {
    val knownBefore = before.map { notification -> notification.event.id }.toSet()
    val keptAfter = after.map { notification -> notification.event.id }.toSet()
    return incoming
        .map { event -> event.id }
        .toSet()
        .count { id -> id !in knownBefore && id in keptAfter }
}

/**
 * Keeps the newest [max] notifications by `occurredAt` and evicts the oldest. A notification whose timestamp does not
 * parse counts as the oldest. The result is sorted newest first; equal timestamps keep their order.
 */
fun applyCap(notifications: List<DiffyStoredNotification>, max: Int = MAX_STORED_NOTIFICATIONS): List<DiffyStoredNotification> {
    val newestFirst = compareByDescending<IndexedNotification> { indexed -> indexed.occurredAt != null }
        .thenByDescending { indexed -> indexed.occurredAt }
        .thenBy { indexed -> indexed.index }
    return notifications
        .mapIndexed { index, notification ->
            IndexedNotification(
                index = index,
                occurredAt = DiffyBrowserTimestamps.parseOrNull(value = notification.event.occurredAt),
                notification = notification
            )
        }
        .sortedWith(newestFirst)
        .take(max)
        .map { indexed -> indexed.notification }
}

private data class IndexedNotification(val index: Int, val occurredAt: Instant?, val notification: DiffyStoredNotification)

/** Sets the read flag of [ids]. Only notifications whose flag really changes get a new [DiffyStoredNotification.changedAt]. */
fun withUnread(notifications: List<DiffyStoredNotification>, ids: Set<String>, unread: Boolean, now: Instant): List<DiffyStoredNotification> {
    return notifications.map { notification ->
        if (notification.event.id in ids && notification.unread != unread) {
            notification.copy(unread = unread, changedAt = now)
        } else {
            notification
        }
    }
}

/**
 * Merges the state another IDE process wrote ([incoming]) with what this process holds ([local]). Union by id. For an
 * id in both, the data of [incoming] wins, and the read flag of the side that changed it last wins: a flag with a
 * change time beats one without, and a tie prefers [incoming]. Deletions do not propagate; the cap evicts old rows.
 */
fun mergeExternal(local: List<DiffyStoredNotification>, incoming: List<DiffyStoredNotification>): List<DiffyStoredNotification> {
    val byId = LinkedHashMap<String, DiffyStoredNotification>()
    local.forEach { notification -> byId[notification.event.id] = notification }
    incoming.forEach { notification ->
        val mine = byId[notification.event.id]
        byId[notification.event.id] = if (mine == null) notification else resolveFlag(mine = mine, theirs = notification)
    }
    return byId.values.toList()
}

private fun resolveFlag(mine: DiffyStoredNotification, theirs: DiffyStoredNotification): DiffyStoredNotification {
    val mineChangedAt = mine.changedAt
    val theirChangedAt = theirs.changedAt
    val mineWins = mineChangedAt != null && (theirChangedAt == null || mineChangedAt > theirChangedAt)
    return if (mineWins) {
        theirs.copy(unread = mine.unread, changedAt = mine.changedAt)
    } else {
        theirs
    }
}

/** The later of two sync times; null only when both are null. */
fun laterOf(first: Instant?, second: Instant?): Instant? {
    if (first == null) {
        return second
    }
    if (second == null) {
        return first
    }
    return if (second > first) second else first
}

private fun DiffyBrowserNotificationEvent.withoutReadState(): DiffyBrowserNotificationEvent {
    return copy(readState = null)
}