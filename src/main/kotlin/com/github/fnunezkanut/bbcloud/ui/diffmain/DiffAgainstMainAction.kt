package com.github.fnunezkanut.bbcloud.ui.diffmain

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.project.DumbAwareAction

/**
 * "Diff this file against main" at the top of the editor text popup and the editor tab popup. It is offered on a named
 * branch other than `main` and `master`, for a file in a Git repository that has a `main` or `master` branch, and it
 * opens a diff of the file against that branch ([DiffAgainstMainOpener]). The text names the branch that was found.
 */
internal class DiffAgainstMainAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (project == null || file == null || !isNormalEditorContext(event = event)) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        val mainRef = DiffAgainstMainEligibility.resolve(project = project, file = file)
        event.presentation.isEnabledAndVisible = mainRef != null
        if (mainRef != null) {
            event.presentation.text = DiffyBundle.message("diffMain.action.text", mainRef.name)
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            LOG.info("Diff against main skipped: no file in the action context")
            return
        }
        LOG.info("User opened the diff against the main branch for a file from ${event.place}")
        DiffAgainstMainOpener.open(project = project, file = file)
    }

    /**
     * The text popup is shared by every editor (diff viewers, consoles, the commit message box), so it needs the main
     * editor kind. The tab popup has no editor in its context and only needs the file.
     */
    private fun isNormalEditorContext(event: AnActionEvent): Boolean {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return true
        return editor.editorKind == EditorKind.MAIN_EDITOR
    }

    companion object {
        private val LOG = logger<DiffAgainstMainAction>()
    }
}