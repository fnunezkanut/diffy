package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread

/**
 * Replaces the commit-bound after-revision of pull request changes with the work tree file.
 *
 * The platform builds the right side of a diff from the after-revision. A [CurrentContentRevision] is backed by the
 * real file, so that side is editable and fully navigable. Any other revision is an in-memory snapshot, which is
 * read-only. No flag can unlock a snapshot, so the revision itself has to be the local file.
 */
internal object WorkingTreeSourceChanges {
    private const val SUBMODULE_REVISION_CLASS_NAME = "GitSubmoduleContentRevision"

    /**
     * Returns [changes] in the same order, with the after-revision of each change replaced by the work tree file of
     * the same path. Deleted files, submodules, files missing on disk, and changes that already use the work tree file
     * stay as they are.
     */
    @RequiresBackgroundThread
    fun withWorkingTreeSourceSide(changes: Collection<Change>): List<Change> {
        return changes.map { change -> withWorkingTreeSourceSide(change = change) }
    }

    private fun withWorkingTreeSourceSide(change: Change): Change {
        val afterRevision = change.afterRevision ?: return change
        if (afterRevision is CurrentContentRevision) {
            return change
        }
        if (afterRevision.file.isDirectory || afterRevision.javaClass.simpleName == SUBMODULE_REVISION_CLASS_NAME) {
            return change
        }
        if (afterRevision.file.virtualFile == null) {
            return change
        }
        return Change(change.beforeRevision, CurrentContentRevision.create(afterRevision.file), change.fileStatus)
    }
}