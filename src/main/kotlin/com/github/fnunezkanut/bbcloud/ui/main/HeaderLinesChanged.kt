package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.git.BranchDiffLineTotals

data class HeaderLinesChanged(val emoji: String, val addedText: String, val removedText: String, val tooltip: String) {
    companion object {
        /**
         * Builds the emoji, the "+N"/"-N" texts, and the tooltip from the git [totals]. A pure mapping free of any UI
         * state so the banner and any other caller can share it; [BranchDiffLineTotals.hasBinaryFiles] switches to the
         * approximate messages that mark the counts as a lower bound.
         */
        fun fromTotals(totals: BranchDiffLineTotals): HeaderLinesChanged {
            val emoji = DiffstatLinesEmoji.forLinesAdded(totals.linesAdded)
            return if (totals.hasBinaryFiles) {
                HeaderLinesChanged(
                    emoji = emoji,
                    addedText = DiffyBundle.message("main.branch.header.linesAdded.approx", totals.linesAdded),
                    removedText = DiffyBundle.message("main.branch.header.linesRemoved.approx", totals.linesRemoved),
                    tooltip = DiffyBundle.message(
                        "main.branch.header.linesChanged.tooltip.approx",
                        totals.linesAdded,
                        totals.linesRemoved
                    )
                )
            } else {
                HeaderLinesChanged(
                    emoji = emoji,
                    addedText = DiffyBundle.message("main.branch.header.linesAdded", totals.linesAdded),
                    removedText = DiffyBundle.message("main.branch.header.linesRemoved", totals.linesRemoved),
                    tooltip = DiffyBundle.message(
                        "main.branch.header.linesChanged.tooltip",
                        totals.linesAdded,
                        totals.linesRemoved
                    )
                )
            }
        }
    }
}