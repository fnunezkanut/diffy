package com.github.fnunezkanut.bbcloud.ui.main

import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor
import org.jetbrains.jewel.bridge.toComposeColor

/**
 * The status colours the plugin uses for its headers, its centered status messages, and its error rows.
 *
 * The colours are resolved through [JBColor.namedColor] so they follow the IDE theme: the same named colour is used
 * in light and dark themes, and the value is read on every call, so the next recomposition picks up a theme switch.
 */
object MainPanelStatusSupport {
    fun projectFoundForeground(): Color {
        return JBColor.namedColor(
            "Label.successForeground",
            JBColor(0x4CAF50, 0x9CCC9C)
        ).toComposeColor()
    }

    fun warningForeground(): Color {
        return JBColor.namedColor(
            "ValidationWarning.foreground",
            JBColor(0xE08855, 0xE08855)
        ).toComposeColor()
    }

    /** A light gray for de-emphasized notes. */
    fun mutedForeground(): Color {
        return JBColor(0xA0A0A0, 0x8C8C8C).toComposeColor()
    }

    fun errorForeground(): Color {
        return JBColor.namedColor(
            "Label.errorForeground",
            JBColor(0xC7222D, 0xFF5261)
        ).toComposeColor()
    }
}