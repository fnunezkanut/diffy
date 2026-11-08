package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyBranchCheckoutService
import com.github.fnunezkanut.bbcloud.git.DiffyPullRequestBranchDiffService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class DiffyPullRequestFileEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is DiffyPullRequestVirtualFile && file.isValid
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val virtualFile = file as DiffyPullRequestVirtualFile
        val configurationService = DiffyConfigurationService.getInstance()
        return DiffyPullRequestFileEditor(
            project = project,
            file = virtualFile,
            configurationService = configurationService,
            openPullRequestService = createOpenPullRequestService(configurationService),
            branchCheckoutService = DiffyBranchCheckoutService(),
            branchDiffService = DiffyPullRequestBranchDiffService()
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
            pullRequestClient = com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketPullRequestClient(),
            configurationService = configurationService,
            pullRequestCache = DiffyPullRequestCache.getInstance()
        )
    }

    companion object {
        const val EDITOR_TYPE_ID = "DiffyPullRequest"
    }
}