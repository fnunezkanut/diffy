package com.github.fnunezkanut.bbcloud.ui.pullrequest.create

import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.intellij.diff.util.FileEditorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JComponent

/** The editor behind the `new.pr` create-pull-request tab; it owns and disposes a [DiffyNewPullRequestSession]. */
class DiffyNewPullRequestFileEditor(project: Project, private val file: DiffyNewPullRequestVirtualFile, openPullRequestService: DiffyOpenPullRequestService) :
    FileEditorBase() {
    private val session = DiffyNewPullRequestSession(
        project = project,
        file = file,
        openPullRequestService = openPullRequestService,
        parentDisposable = this
    )

    override fun getComponent(): JComponent {
        return session.component
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return session.component
    }

    override fun getName(): String {
        return file.name
    }

    override fun getFile(): VirtualFile {
        return file
    }

    override fun isValid(): Boolean {
        return file.isValid
    }

    override fun selectNotify() {
        session.onEditorSelected()
    }

    override fun dispose() {
        session.dispose()
        super.dispose()
    }
}