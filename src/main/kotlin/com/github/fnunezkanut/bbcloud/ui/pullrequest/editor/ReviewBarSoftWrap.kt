package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.diff.EditorDiffViewer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actions.AbstractToggleUseSoftWrapsAction

/**
 * Reads and changes soft wrapping of the text editors of a diff viewer, for the "Wrap Lines" button of the review bar.
 *
 * Only the editors of the viewer change. The IDE's global diff soft-wrap setting is not touched. Side-by-side, unified
 * and one-sided text viewers all expose their editors through [EditorDiffViewer], so one code path covers them.
 *
 * Like [ReviewBarDiffEditors], the helpers take [Any] because the callers hand in either a plain viewer or a
 * `DiffRequestProcessor`; the active viewer is resolved on every call because it can change.
 */
internal object ReviewBarSoftWrap {
    private val LOG = logger<ReviewBarSoftWrap>()

    /** The text editors of the active viewer of [viewer], or an empty list when it has none (for example an image diff). */
    fun editorsOf(viewer: Any?): List<Editor> {
        val activeViewer = ReviewBarDiffEditors.activeViewer(viewer = viewer)
        if (activeViewer is EditorDiffViewer) {
            return activeViewer.editors
        }
        return emptyList()
    }

    /** True when the viewer has at least one editor and every editor wraps long lines. */
    fun isWrapped(viewer: Any?): Boolean {
        val editors = editorsOf(viewer = viewer)
        return editors.isNotEmpty() && editors.all { editor -> editor.settings.isUseSoftWraps }
    }

    /** Turns soft wrapping on or off in every editor of the viewer. Must run on the EDT. */
    fun setWrapped(viewer: Any?, wrapped: Boolean) {
        val editors = editorsOf(viewer = viewer)
        LOG.debug("Setting soft wraps to $wrapped in ${editors.size} diff editors")
        editors.forEach { editor ->
            if (editor.settings.isUseSoftWraps != wrapped) {
                AbstractToggleUseSoftWrapsAction.toggleSoftWraps(editor, null, wrapped)
            }
        }
    }
}