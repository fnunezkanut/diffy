package com.github.fnunezkanut.bbcloud.ui.pullrequest.help

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

/**
 * A dedicated file type for the `help-notifications.pr` Notifications help tab. The platform routes a [DiffyNotificationsHelpVirtualFile] to
 * [DiffyNotificationsHelpFileEditorProvider] through this type. Without it the default text editor opens an empty tab.
 *
 * The default extension is distinct from the review and create-form types, and no file name association uses it.
 */
object DiffyNotificationsHelpFileType : FileType {
    override fun getName(): String = "Bitbucket Cloud Notifications Help"

    override fun getDescription(): String = DiffyBundle.message("fileType.notificationsHelp.description")

    override fun getDefaultExtension(): String = "helpnotif"

    override fun getIcon(): Icon {
        return DiffyPluginIcons.prIcon
    }

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = true
}