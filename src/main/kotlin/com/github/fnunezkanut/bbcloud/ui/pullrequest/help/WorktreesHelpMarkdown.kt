package com.github.fnunezkanut.bbcloud.ui.pullrequest.help

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.intellij.openapi.diagnostic.logger
import java.io.IOException

/**
 * Reads the Work Trees help page and its images from the plugin classpath. The Gradle build copies them from `docs/`
 * into the `help/` resource folder, so the README and the plugin share one copy of the content.
 */
internal object WorktreesHelpMarkdown {
    const val MARKDOWN_RESOURCE = "/help/worktrees.md"
    const val NOTIFICATIONS_MARKDOWN_RESOURCE = "/help/notifications.md"

    private val LOG = logger<WorktreesHelpMarkdown>()

    /** Returns the Work Trees help page markdown, or a localized message when it is missing or cannot be read. */
    fun load(): String {
        return load(resourcePath = MARKDOWN_RESOURCE, unavailableKey = "worktreesHelp.unavailable")
    }

    /** Returns the Notifications help page markdown, or a localized message when it is missing or cannot be read. */
    fun loadNotifications(): String {
        return load(resourcePath = NOTIFICATIONS_MARKDOWN_RESOURCE, unavailableKey = "notificationsHelp.unavailable")
    }

    private fun load(resourcePath: String, unavailableKey: String): String {
        val bytes = readResourceBytes(resourcePath = resourcePath)
        if (bytes == null) {
            LOG.warn("The help page is missing from the plugin resources: $resourcePath")
            return DiffyBundle.message(unavailableKey)
        }
        LOG.debug("Loaded the help page $resourcePath (${bytes.size} bytes)")
        return bytes.toString(Charsets.UTF_8)
    }

    /** Returns the raw bytes of a classpath resource, or null when it is missing or cannot be read. */
    fun readResourceBytes(resourcePath: String): ByteArray? {
        return try {
            WorktreesHelpMarkdown::class.java.getResourceAsStream(resourcePath)?.use { stream -> stream.readBytes() }
        } catch (exception: IOException) {
            LOG.debug("Could not read the help resource $resourcePath", exception)
            null
        }
    }
}