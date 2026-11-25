package com.github.fnunezkanut.bbcloud.external

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.concurrent.ConcurrentHashMap

/** Where a browser-triggered open of a pull request stands. */
enum class DiffyOpenProgressState {
    OPENING,
    OPENED,
    FAILED
}

/** The progress of one browser-triggered open. [message] is a short English reason, set only for [DiffyOpenProgressState.FAILED]. */
data class DiffyOpenProgress(val state: DiffyOpenProgressState, val message: String?)

/**
 * Remembers, in memory only, how each browser-triggered open of a pull request is going, so the browser extension can
 * show a loading icon until the IDE window has opened. Entries are keyed by workspace, repository slug and pull request
 * number, and expire [EXPIRY_MS] after their last update. Only ids and fixed texts are stored, never titles or tokens.
 *
 * The map operations are safe to call from a Netty I/O thread and from the coroutine that runs the open.
 */
@Service(Service.Level.APP)
class DiffyOpenProgressTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val entries: MutableMap<String, Entry> = ConcurrentHashMap<String, Entry>()

    /** Marks the open as running. A repeated call for the same pull request restarts the entry. */
    fun markOpening(workspace: String, repoSlug: String, prNumber: Long) {
        put(
            workspace = workspace,
            repoSlug = repoSlug,
            prNumber = prNumber,
            progress = DiffyOpenProgress(state = DiffyOpenProgressState.OPENING, message = null)
        )
    }

    /** Marks the open as finished: the IDE window is open and focused. */
    fun markOpened(workspace: String, repoSlug: String, prNumber: Long) {
        put(
            workspace = workspace,
            repoSlug = repoSlug,
            prNumber = prNumber,
            progress = DiffyOpenProgress(state = DiffyOpenProgressState.OPENED, message = null)
        )
    }

    /** Marks the open as failed. [message] is the same text the IDE notification shows. */
    fun markFailed(workspace: String, repoSlug: String, prNumber: Long, message: String) {
        put(
            workspace = workspace,
            repoSlug = repoSlug,
            prNumber = prNumber,
            progress = DiffyOpenProgress(state = DiffyOpenProgressState.FAILED, message = message)
        )
    }

    /** Returns the progress of the latest open of the pull request, or null when none is known or it has expired. */
    fun progressFor(workspace: String, repoSlug: String, prNumber: Long): DiffyOpenProgress? {
        val key = keyOf(workspace = workspace, repoSlug = repoSlug, prNumber = prNumber)
        val entry = entries[key] ?: return null
        if (clock() - entry.updatedAtMillis > EXPIRY_MS) {
            entries.remove(key, entry)
            return null
        }
        return entry.progress
    }

    private fun put(workspace: String, repoSlug: String, prNumber: Long, progress: DiffyOpenProgress) {
        val now = clock()
        entries.values.removeIf { entry -> now - entry.updatedAtMillis > EXPIRY_MS }
        entries[keyOf(workspace = workspace, repoSlug = repoSlug, prNumber = prNumber)] =
            Entry(progress = progress, updatedAtMillis = now)
    }

    private fun keyOf(workspace: String, repoSlug: String, prNumber: Long): String {
        return "$workspace/$repoSlug/$prNumber"
    }

    private data class Entry(val progress: DiffyOpenProgress, val updatedAtMillis: Long)

    companion object {
        /** Five minutes: long enough for a slow work tree setup, short enough to forget old opens. */
        const val EXPIRY_MS: Long = 5 * 60 * 1000L

        fun getInstance(): DiffyOpenProgressTracker {
            return ApplicationManager.getApplication().service()
        }
    }
}