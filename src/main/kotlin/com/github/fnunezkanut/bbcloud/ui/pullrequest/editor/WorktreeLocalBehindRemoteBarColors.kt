package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor

/** Theme-aware colors for the red "local branches are behind Bitbucket" bar under the blue bar. */
internal object WorktreeLocalBehindRemoteBarColors {
    fun border(): Color {
        return JBColor(0xE5484D, 0xC2414A).toComposeColor()
    }

    fun background(): Color {
        return JBColor(0xFDECEC, 0x3B2224).toComposeColor()
    }
}