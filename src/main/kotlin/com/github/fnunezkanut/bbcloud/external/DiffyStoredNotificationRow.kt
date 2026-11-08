package com.github.fnunezkanut.bbcloud.external

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encodes notifications as compact JSON strings for [DiffyNotificationsState].
 *
 * Every row carries a schema version `v`. Decoding never throws: a row that fails to parse or has an older unknown
 * version returns null. A row of a newer version also returns null, but [isNewerVersion] recognizes it so the state can
 * keep it untouched. The `readState` of Bitbucket is never written and is always null after decoding.
 */
object DiffyStoredNotificationRow {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(notification: DiffyStoredNotification): String {
        val event = notification.event
        val row = Row(
            version = VERSION,
            id = event.id,
            occurredAt = event.occurredAt,
            category = event.category.wireName,
            templateId = event.templateId,
            message = event.message,
            actorDisplayName = event.actorDisplayName,
            actorAccountId = event.actorAccountId,
            actorIsBot = event.actorIsBot,
            actorBotMatchedOn = event.actorBotMatchedOn,
            pullRequestId = event.pullRequestId,
            pullRequestRepoSlug = event.pullRequestRepoSlug,
            pullRequestTitle = event.pullRequestTitle,
            pullRequestUrl = event.pullRequestUrl,
            commentId = event.commentId,
            onDiff = event.onDiff,
            url = event.url,
            groupId = event.groupId,
            groupSize = event.groupSize,
            unread = notification.unread,
            changedAt = notification.changedAt?.toString()
        )
        return json.encodeToString(Row.serializer(), row)
    }

    /** Returns null for a row that cannot be decoded or has another version than [VERSION]. */
    fun decode(encoded: String): DiffyStoredNotification? {
        return runCatching {
            val row = json.decodeFromString(Row.serializer(), encoded)
            if (row.version != VERSION || row.id.isBlank()) {
                return null
            }
            DiffyStoredNotification(
                event = DiffyBrowserNotificationEvent(
                    id = row.id,
                    occurredAt = row.occurredAt,
                    category = DiffyBrowserEventCategory.fromWireName(name = row.category),
                    templateId = row.templateId,
                    readState = null,
                    message = row.message,
                    actorDisplayName = row.actorDisplayName,
                    actorAccountId = row.actorAccountId,
                    actorIsBot = row.actorIsBot,
                    actorBotMatchedOn = row.actorBotMatchedOn,
                    pullRequestId = row.pullRequestId,
                    pullRequestRepoSlug = row.pullRequestRepoSlug,
                    pullRequestTitle = row.pullRequestTitle,
                    pullRequestUrl = row.pullRequestUrl,
                    commentId = row.commentId,
                    onDiff = row.onDiff,
                    url = row.url,
                    groupId = row.groupId,
                    groupSize = row.groupSize
                ),
                unread = row.unread,
                changedAt = row.changedAt?.let { value -> DiffyBrowserTimestamps.parseOrNull(value = value) }
            )
        }.getOrNull()
    }

    /** True when [encoded] is a well formed row written by a newer plugin version. */
    fun isNewerVersion(encoded: String): Boolean {
        return runCatching {
            json.decodeFromString(VersionOnly.serializer(), encoded).version > VERSION
        }.getOrDefault(false)
    }

    @Serializable
    private data class VersionOnly(
        @SerialName("v") val version: Int
    )

    @Serializable
    private data class Row(
        @SerialName("v") val version: Int,
        @SerialName("id") val id: String,
        @SerialName("at") val occurredAt: String,
        @SerialName("cat") val category: String? = null,
        @SerialName("tpl") val templateId: String? = null,
        @SerialName("msg") val message: String? = null,
        @SerialName("an") val actorDisplayName: String? = null,
        @SerialName("aid") val actorAccountId: String? = null,
        @SerialName("bot") val actorIsBot: Boolean = false,
        @SerialName("bm") val actorBotMatchedOn: String? = null,
        @SerialName("pr") val pullRequestId: Long? = null,
        @SerialName("rs") val pullRequestRepoSlug: String? = null,
        @SerialName("pt") val pullRequestTitle: String? = null,
        @SerialName("pu") val pullRequestUrl: String? = null,
        @SerialName("cid") val commentId: Long? = null,
        @SerialName("df") val onDiff: Boolean = false,
        @SerialName("url") val url: String? = null,
        @SerialName("gid") val groupId: String? = null,
        @SerialName("gs") val groupSize: Int = 1,
        @SerialName("un") val unread: Boolean = true,
        @SerialName("ch") val changedAt: String? = null
    )
}