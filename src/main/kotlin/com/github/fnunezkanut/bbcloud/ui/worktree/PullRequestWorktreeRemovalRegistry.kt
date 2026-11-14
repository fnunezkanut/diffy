package com.github.fnunezkanut.bbcloud.ui.worktree

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-wide guards for PR work tree removal. Being application wide, no two tool windows (two IDE windows) can
 * run the bulk removal at the same time or remove the same work tree twice.
 */
internal object PullRequestWorktreeRemovalRegistry {
    private val worktreesBeingRemoved: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val bulkRemovalInProgress = AtomicBoolean(false)

    /** Marks [path] as being removed. False when it was already marked. */
    fun tryMarkRemoving(path: Path): Boolean {
        return worktreesBeingRemoved.add(path.toAbsolutePath().normalize())
    }

    fun unmarkRemoving(path: Path) {
        worktreesBeingRemoved.remove(path.toAbsolutePath().normalize())
    }

    fun isRemoving(path: Path): Boolean {
        return path.toAbsolutePath().normalize() in worktreesBeingRemoved
    }

    /** Claims the bulk removal. False when one is already running. */
    fun tryStartBulkRemoval(): Boolean {
        return bulkRemovalInProgress.compareAndSet(false, true)
    }

    fun finishBulkRemoval() {
        bulkRemovalInProgress.set(false)
    }

    fun isBulkRemovalInProgress(): Boolean {
        return bulkRemovalInProgress.get()
    }

    internal fun resetForTests() {
        worktreesBeingRemoved.clear()
        bulkRemovalInProgress.set(false)
    }
}