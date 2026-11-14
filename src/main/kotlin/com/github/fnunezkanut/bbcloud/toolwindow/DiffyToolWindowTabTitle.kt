package com.github.fnunezkanut.bbcloud.toolwindow

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil

/** Builds tool window tab titles with a counter after the title, in the HTML form the Problems tool window uses. */
object DiffyToolWindowTabTitle {
    /** Counts above this value show as "99+", so a long count does not widen the header without limit. */
    const val MAX_SHOWN_COUNT = 99

    private const val TAB_COUNTER_GAP = 8

    /**
     * The tab title with a counter after it. A null or non-positive [count] returns [title] unchanged (no HTML), so a
     * tab without a counter looks like the other tabs. [counterColorHex] is the color as `ColorUtil.toHtmlColor`
     * returns it, including the leading `#`.
     */
    fun format(title: String, count: Int?, counterColorHex: String, gapPx: Int): String {
        if (count == null || count <= 0) {
            return title
        }
        val countText = if (count > MAX_SHOWN_COUNT) "$MAX_SHOWN_COUNT+" else count.toString()
        val escapedTitle = StringUtil.escapeXmlEntities(title)
        return "<html><body><table cellpadding='0' cellspacing='0'><tr>" +
            "<td><nobr>$escapedTitle</nobr></td>" +
            "<td width='$gapPx'></td>" +
            "<td><font color='$counterColorHex'>$countText</font></td>" +
            "</tr></table></body></html>"
    }

    /** Same as [format], using the inactive text color and the gap scaled for the current UI. */
    fun formatForCurrentTheme(title: String, count: Int?): String {
        return format(
            title = title,
            count = count,
            counterColorHex = ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor()),
            gapPx = JBUI.scale(TAB_COUNTER_GAP)
        )
    }
}