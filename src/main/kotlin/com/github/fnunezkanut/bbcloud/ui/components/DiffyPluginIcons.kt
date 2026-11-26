package com.github.fnunezkanut.bbcloud.ui.components

import com.intellij.openapi.util.IconLoader
import org.jetbrains.jewel.ui.icon.IntelliJIconKey
import javax.swing.Icon

/**
 * The plugin's green and purple pull-request merge icon. [prIcon] is the shared 16×16 Swing icon for the `.pr` file type
 * and classic tool-window registration; [pr] is the Jewel [IntelliJIconKey] for Compose bars (`icons/prToolWindow.svg` on
 * the classic UI and `icons/expui/toolwindow/prToolWindow.svg` on the new UI).
 */
internal object DiffyPluginIcons {
    val prIcon: Icon
        get() = IconLoader.getIcon("/icons/prToolWindow.svg", DiffyPluginIcons::class.java)

    val pr: IntelliJIconKey = IntelliJIconKey(
        oldUiPath = "icons/prToolWindow.svg",
        newUiPath = "icons/expui/toolwindow/prToolWindow.svg",
        iconClass = DiffyPluginIcons::class.java
    )

    /** The Send to Air glyph. It is drawn for this plugin and is not copied from the JetBrains Air plugin. */
    val sendToAir: IntelliJIconKey = IntelliJIconKey(
        oldUiPath = "icons/sendToAir.svg",
        newUiPath = "icons/sendToAir.svg",
        iconClass = DiffyPluginIcons::class.java
    )

    /**
     * The "local branch is behind Bitbucket" glyph of the red bar: a local branch (grey) and a red arrow that points
     * down onto a bar. It is drawn for this plugin and is not copied from the JetBrains icons.
     */
    val localBehindRemote: IntelliJIconKey = IntelliJIconKey(
        oldUiPath = "icons/localBehindRemote.svg",
        newUiPath = "icons/localBehindRemote.svg",
        iconClass = DiffyPluginIcons::class.java
    )

    /**
     * The "pull request is behind its destination branch" glyph of the yellow bar: a branch (grey) that is missing two
     * commits (amber) of the destination branch. It is drawn for this plugin and is not copied from the JetBrains icons.
     */
    val behindDestination: IntelliJIconKey = IntelliJIconKey(
        oldUiPath = "icons/behindDestination.svg",
        newUiPath = "icons/behindDestination.svg",
        iconClass = DiffyPluginIcons::class.java
    )
}