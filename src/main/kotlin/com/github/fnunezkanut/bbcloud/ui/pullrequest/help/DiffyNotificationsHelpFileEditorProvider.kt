package com.github.fnunezkanut.bbcloud.ui.pullrequest.help

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DiffyNotificationsHelpFileEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is DiffyNotificationsHelpVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        LOG.debug("Creating the Notifications help editor")
        return DiffyHelpFileEditor(file = file, markdown = WorktreesHelpMarkdown.loadNotifications())
    }

    override fun getEditorTypeId(): String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }

    companion object {
        private val LOG = logger<DiffyNotificationsHelpFileEditorProvider>()
        const val EDITOR_TYPE_ID = "DiffyNotificationsHelp"
    }
}