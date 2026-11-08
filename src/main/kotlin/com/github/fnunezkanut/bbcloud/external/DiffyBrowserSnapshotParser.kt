package com.github.fnunezkanut.bbcloud.external

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** The result of parsing a `POST /api/diffy/snapshot` body. */
sealed interface DiffyBrowserSnapshotParseResult {
    data class Valid(val snapshot: DiffyBrowserSnapshot) : DiffyBrowserSnapshotParseResult

    /** [message] is a short English reason. It never contains input text, because it is logged and sent back. */
    data class Rejected(val message: String) : DiffyBrowserSnapshotParseResult
}

/**
 * Validates and parses the snapshot document of the Chrome extension. The check is pure: no settings, no I/O. Only
 * known fields are read. A single bad array entry is skipped and counted instead of rejecting the whole document.
 */
object DiffyBrowserSnapshotParser {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_PULL_REQUESTS = 500
    const val MAX_EVENTS = 5000
    const val MAX_TITLE_LENGTH = 500
    const val MAX_STRING_LENGTH = 2000

    const val INVALID_BODY = "The request body is not a valid JSON object."
    const val UNSUPPORTED_VERSION = "Unsupported snapshot version."
    const val MISSING_LISTS = "The snapshot must contain pullRequests and notificationEvents arrays."
    const val TOO_MANY_PULL_REQUESTS = "The snapshot contains too many pull requests."
    const val TOO_MANY_EVENTS = "The snapshot contains too many notification events."
    const val VALUE_TOO_LONG = "The snapshot contains a value that is too long."

    private class LimitExceededException : RuntimeException()

    fun parse(body: String): DiffyBrowserSnapshotParseResult {
        val root = parseObject(body = body)
            ?: return DiffyBrowserSnapshotParseResult.Rejected(message = INVALID_BODY)

        val version = root.get("schemaVersion")
        val versionValue = if (version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber) {
            version.asInt
        } else {
            null
        }
        if (versionValue != SUPPORTED_SCHEMA_VERSION) {
            return DiffyBrowserSnapshotParseResult.Rejected(message = UNSUPPORTED_VERSION)
        }

        val pullRequestArray = arrayField(root = root, name = "pullRequests")
        val eventArray = arrayField(root = root, name = "notificationEvents")
        if (pullRequestArray == null || eventArray == null) {
            return DiffyBrowserSnapshotParseResult.Rejected(message = MISSING_LISTS)
        }
        if (pullRequestArray.size() > MAX_PULL_REQUESTS) {
            return DiffyBrowserSnapshotParseResult.Rejected(message = TOO_MANY_PULL_REQUESTS)
        }
        if (eventArray.size() > MAX_EVENTS) {
            return DiffyBrowserSnapshotParseResult.Rejected(message = TOO_MANY_EVENTS)
        }

        return try {
            DiffyBrowserSnapshotParseResult.Valid(
                snapshot = buildSnapshot(root = root, pullRequestArray = pullRequestArray, eventArray = eventArray)
            )
        } catch (exception: LimitExceededException) {
            DiffyBrowserSnapshotParseResult.Rejected(message = VALUE_TOO_LONG)
        }
    }

    private fun buildSnapshot(root: JsonObject, pullRequestArray: JsonArray, eventArray: JsonArray): DiffyBrowserSnapshot {
        var skipped = 0
        val pullRequests = mutableListOf<DiffyBrowserPullRequest>()
        pullRequestArray.forEach { element ->
            val parsed = parsePullRequest(element = element)
            if (parsed == null) {
                skipped++
            } else {
                pullRequests.add(parsed)
            }
        }
        val events = mutableListOf<DiffyBrowserNotificationEvent>()
        eventArray.forEach { element ->
            val parsed = parseEvent(element = element)
            if (parsed == null) {
                skipped++
            } else {
                events.add(parsed)
            }
        }
        return DiffyBrowserSnapshot(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            meta = parseMeta(root = root.get("meta") as? JsonObject),
            pullRequests = pullRequests,
            notificationEvents = events,
            skippedInvalidEntries = skipped
        )
    }

    private fun parseMeta(root: JsonObject?): DiffyBrowserSnapshotMeta {
        return DiffyBrowserSnapshotMeta(
            capturedAt = root?.let { stringField(root = it, name = "capturedAt") }.orEmpty(),
            workspaces = root?.let { stringList(root = it, name = "workspaces") }.orEmpty(),
            skippedNonBitbucket = root?.let { intField(root = it, name = "skippedNonBitbucket") } ?: 0,
            truncatedPullRequests = (root?.get("truncated") as? JsonObject)
                ?.let { truncated -> booleanField(root = truncated, name = "pullRequests") } ?: false
        )
    }

    private fun parsePullRequest(element: JsonElement): DiffyBrowserPullRequest? {
        val root = element as? JsonObject ?: return null
        val id = longField(root = root, name = "id")
        val repoSlug = stringField(root = root, name = "repoSlug")
        if (id == null || repoSlug == null) {
            return null
        }
        val author = root.get("author") as? JsonObject
        return DiffyBrowserPullRequest(
            id = id,
            workspace = stringField(root = root, name = "workspace").orEmpty(),
            repoSlug = repoSlug,
            repoFullName = stringField(root = root, name = "repoFullName").orEmpty(),
            title = stringField(root = root, name = "title", maxLength = MAX_TITLE_LENGTH).orEmpty(),
            titleTags = stringList(root = root, name = "titleTags"),
            jiraKeys = stringList(root = root, name = "jiraKeys"),
            jiraKeySource = stringField(root = root, name = "jiraKeySource"),
            url = stringField(root = root, name = "url").orEmpty(),
            state = stringField(root = root, name = "state").orEmpty(),
            draft = booleanField(root = root, name = "draft"),
            authorDisplayName = author?.let { stringField(root = it, name = "displayName") },
            sourceBranch = stringField(root = root, name = "sourceBranch"),
            destinationBranch = stringField(root = root, name = "destinationBranch"),
            createdOn = stringField(root = root, name = "createdOn"),
            updatedOn = stringField(root = root, name = "updatedOn"),
            commentCount = intField(root = root, name = "commentCount") ?: 0,
            taskCount = intField(root = root, name = "taskCount") ?: 0,
            approvalCount = intField(root = root, name = "approvalCount") ?: 0,
            changesRequestedCount = intField(root = root, name = "changesRequestedCount") ?: 0,
            myReviewState = stringField(root = root, name = "myReviewState"),
            reviewers = parseReviewers(array = arrayField(root = root, name = "reviewers")),
            otherParticipants = parseReviewers(array = arrayField(root = root, name = "otherParticipants"))
        )
    }

    private fun parseReviewers(array: JsonArray?): List<DiffyBrowserReviewer> {
        if (array == null) {
            return emptyList()
        }
        return array.mapNotNull { element ->
            val root = element as? JsonObject
            val displayName = root?.let { stringField(root = it, name = "displayName") }
            if (root == null || displayName == null) {
                null
            } else {
                DiffyBrowserReviewer(
                    displayName = displayName,
                    accountId = stringField(root = root, name = "accountId"),
                    uuid = stringField(root = root, name = "uuid"),
                    state = stringField(root = root, name = "state"),
                    approved = booleanField(root = root, name = "approved"),
                    participatedOn = stringField(root = root, name = "participatedOn")
                )
            }
        }
    }

    private fun parseEvent(element: JsonElement): DiffyBrowserNotificationEvent? {
        val root = element as? JsonObject ?: return null
        val id = stringField(root = root, name = "id")
        val occurredAt = stringField(root = root, name = "occurredAt")
        val categoryName = stringField(root = root, name = "category")
        if (id == null || occurredAt == null || categoryName == null) {
            return null
        }
        val actor = root.get("actor") as? JsonObject
        val pullRequest = root.get("pullRequest") as? JsonObject
        return DiffyBrowserNotificationEvent(
            id = id,
            occurredAt = occurredAt,
            category = DiffyBrowserEventCategory.fromWireName(name = categoryName),
            templateId = stringField(root = root, name = "templateId"),
            readState = stringField(root = root, name = "readState"),
            message = stringField(root = root, name = "message"),
            actorDisplayName = actor?.let { stringField(root = it, name = "displayName") },
            actorAccountId = actor?.let { stringField(root = it, name = "accountId") },
            actorIsBot = actor?.let { booleanField(root = it, name = "isBot") } ?: false,
            actorBotMatchedOn = actor?.let { stringField(root = it, name = "botMatchedOn") },
            pullRequestId = pullRequest?.let { longField(root = it, name = "id") },
            pullRequestRepoSlug = pullRequest?.let { stringField(root = it, name = "repoSlug") },
            pullRequestTitle = pullRequest?.let { stringField(root = it, name = "title", maxLength = MAX_TITLE_LENGTH) },
            pullRequestUrl = pullRequest?.let { stringField(root = it, name = "url") },
            commentId = longField(root = root, name = "commentId"),
            onDiff = booleanField(root = root, name = "onDiff"),
            url = stringField(root = root, name = "url"),
            groupId = stringField(root = root, name = "groupId"),
            groupSize = intField(root = root, name = "groupSize") ?: 1
        )
    }

    private fun parseObject(body: String): JsonObject? {
        return try {
            JsonParser.parseString(body) as? JsonObject
        } catch (exception: JsonParseException) {
            null
        }
    }

    private fun arrayField(root: JsonObject, name: String): JsonArray? {
        val element = root.get(name)
        return if (element != null && element.isJsonArray) element.asJsonArray else null
    }

    /** A string field, or null when absent, not a string, or JSON null. Throws when longer than [maxLength]. */
    private fun stringField(root: JsonObject, name: String, maxLength: Int = MAX_STRING_LENGTH): String? {
        val element = root.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            return null
        }
        val value = element.asString
        if (value.length > maxLength) {
            throw LimitExceededException()
        }
        return value
    }

    private fun stringList(root: JsonObject, name: String): List<String> {
        val array = arrayField(root = root, name = name) ?: return emptyList()
        return array.mapNotNull { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                val value = element.asString
                if (value.length > MAX_STRING_LENGTH) {
                    throw LimitExceededException()
                }
                value
            } else {
                null
            }
        }
    }

    private fun longField(root: JsonObject, name: String): Long? {
        val element = root.get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            return null
        }
        return try {
            element.asBigDecimal.longValueExact()
        } catch (exception: ArithmeticException) {
            null
        }
    }

    private fun intField(root: JsonObject, name: String): Int? {
        val value = longField(root = root, name = name) ?: return null
        return value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun booleanField(root: JsonObject, name: String): Boolean {
        val element = root.get(name)
        return element != null && element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
    }
}