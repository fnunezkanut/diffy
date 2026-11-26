package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor

/** Theme-aware colors for the yellow "pull request is behind its destination branch" bar under the red bar. */
internal object WorktreeBehindDestinationBarColors {
    fun border(): Color {
        return JBColor(0xE6C000, 0x9C8A2A).toComposeColor()
    }

    fun background(): Color {
        return JBColor(0xFFFBE0, 0x35321C).toComposeColor()
    }
}