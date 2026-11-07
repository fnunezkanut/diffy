package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyBranchCheckoutService
import com.github.fnunezkanut.bbcloud.git.DiffyPullRequestBranchDiffService
import com.github.fnunezkanut.bbcloud.ui.pullrequest.session.DiffyPullRequestEditorSession
import com.intellij.diff.util.FileEditorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JComponent

class DiffyPullRequestFileEditor(
    project: Project,
    private val file: DiffyPullRequestVirtualFile,
    configurationService: DiffyConfigurationService,
    openPullRequestService: DiffyOpenPullRequestService,
    branchCheckoutService: DiffyBranchCheckoutService,
    branchDiffService: DiffyPullRequestBranchDiffService
) : FileEditorBase() {
    private val session = DiffyPullRequestEditorSession(
        project = project,
        file = file,
        configurationService = configurationService,
        openPullRequestService = openPullRequestService,
        branchCheckoutService = branchCheckoutService,
        branchDiffService = branchDiffService,
        parentDisposable = this
    )

    override fun getComponent(): JComponent {
        return session.component
    }

    override fun getPreferredFocusedComponent(): JComponent? {
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
        session.select()
    }

    override fun dispose() {
        session.dispose()
        super.dispose()
    }
}