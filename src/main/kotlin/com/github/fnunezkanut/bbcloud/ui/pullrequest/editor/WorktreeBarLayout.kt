package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layout constants shared by the blue pull request bar and the orange "not in the pull request yet" bar. */
internal val ROW_HORIZONTAL_PADDING = 12.dp
internal val ROW_VERTICAL_PADDING = 6.dp
internal val BORDER_WIDTH = 1.dp

/** The gap drawn below a bordered box so the bar is separated from the bar or editor below it. */
internal val BANNER_BOTTOM_GAP = 5.dp

/**
 * The weights that order the bars above an editor or diff: a lower weight renders higher. The platform sorts the
 * notification components by weight.
 */
internal object WorktreeBarWeights {
    const val BLUE = 0.0
    const val UNPUSHED = 0.5
    const val REVIEW = 1.0
}

/**
 * Draws a [width]-thick [color] line along the top and bottom edges of the element, leaving the left and right edges
 * open, so the bar reads as a horizontal band rather than a four-sided boxed card. The line is drawn centered on each
 * edge, mirroring how [androidx.compose.foundation.border] strokes a full box.
 */
internal fun Modifier.topAndBottomBorder(width: Dp, color: Color): Modifier {
    return drawBehind {
        val strokeWidth = width.toPx()
        val halfStroke = strokeWidth / 2
        drawLine(
            color = color,
            start = Offset(x = 0f, y = halfStroke),
            end = Offset(x = size.width, y = halfStroke),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = Offset(x = 0f, y = size.height - halfStroke),
            end = Offset(x = size.width, y = size.height - halfStroke),
            strokeWidth = strokeWidth
        )
    }
}