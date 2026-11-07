package com.github.fnunezkanut.bbcloud.ui.pullrequest.create

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

/**
 * A dedicated file type for the `new.pr` create-pull-request tab, kept separate from the `<id>.pr` review file type.
 *
 * Giving the create form its own file type removes any ambiguity about which editor opens it: the platform routes a
 * [DiffyNewPullRequestVirtualFile] to [DiffyNewPullRequestFileEditorProvider] via this type, rather than falling
 * back to the default text editor and showing an empty tab. It reuses the shared pull-request icon.
 */
object DiffyNewPullRequestFileType : FileType {
    override fun getName(): String = "Bitbucket Cloud New PR"

    override fun getDescription(): String = DiffyBundle.message("fileType.newPullRequest.description")

    override fun getDefaultExtension(): String = "newpr"

    override fun getIcon(): Icon {
        return DiffyPluginIcons.prIcon
    }

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = true
}