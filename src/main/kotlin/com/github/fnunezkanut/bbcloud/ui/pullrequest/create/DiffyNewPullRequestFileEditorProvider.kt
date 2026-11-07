package com.github.fnunezkanut.bbcloud.ui.pullrequest.create

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketPullRequestClient
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DiffyNewPullRequestFileEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file !is DiffyNewPullRequestVirtualFile) {
            LOG.debug("Rejecting file editor for ${file.name}: not a DiffyNewPullRequestVirtualFile")
            return false
        }
        return true
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        LOG.info("Opening DiffyNewPullRequest editor for new.pr")
        val virtualFile = file as DiffyNewPullRequestVirtualFile
        return DiffyNewPullRequestFileEditor(
            project = project,
            file = virtualFile,
            openPullRequestService = createOpenPullRequestService(DiffyConfigurationService.getInstance())
        )
    }

    override fun getEditorTypeId(): String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }

    private fun createOpenPullRequestService(configurationService: DiffyConfigurationService): DiffyOpenPullRequestService {
        val application = ApplicationManager.getApplication()
        return DiffyOpenPullRequestService(
            accessTokenProvider = application.service<DiffyAccessTokenProvider>(),
            pullRequestClient = OkHttpBitbucketPullRequestClient(),
            configurationService = configurationService
        )
    }

    companion object {
        private val LOG = logger<DiffyNewPullRequestFileEditorProvider>()
        const val EDITOR_TYPE_ID = "DiffyNewPullRequest"
    }
}