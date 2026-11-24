package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor

/** Theme-aware colors for the orange "not in the pull request yet" bar under the blue bar. */
internal object WorktreeUnpushedChangesBarColors {
    fun border(): Color {
        return JBColor(0xF5A623, 0xB0701E).toComposeColor()
    }

    fun background(): Color {
        return JBColor(0xFFF4E5, 0x3A2C1A).toComposeColor()
    }
}