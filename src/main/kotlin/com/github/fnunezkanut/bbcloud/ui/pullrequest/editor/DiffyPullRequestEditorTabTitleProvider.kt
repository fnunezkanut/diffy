package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestVirtualFile
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile

class DiffyPullRequestEditorTabTitleProvider :
    EditorTabTitleProvider,
    DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        return null
    }

    override fun getEditorTabTooltipHtml(project: Project, virtualFile: VirtualFile): HtmlChunk? {
        if (virtualFile is DiffyNewPullRequestVirtualFile) {
            return HtmlChunk.text(DiffyBundle.message("newPr.tab.tooltip", virtualFile.context.repositorySlug))
        }
        val pullRequestFile = virtualFile as? DiffyPullRequestVirtualFile ?: return null
        val title = pullRequestFile.pullRequestTitle() ?: return null
        return HtmlChunk.text(title)
    }
}