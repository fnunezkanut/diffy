package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

object DiffyPullRequestFileType : FileType {
    override fun getName(): String = "Diffy - Bitbucket PR Review Overview"

    override fun getDescription(): String = DiffyBundle.message("fileType.pullRequest.description")

    override fun getDefaultExtension(): String = "pr"

    override fun getIcon(): Icon {
        return DiffyPluginIcons.prIcon
    }

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = true
}