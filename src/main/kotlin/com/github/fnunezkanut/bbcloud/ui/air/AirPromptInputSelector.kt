package com.github.fnunezkanut.bbcloud.ui.air

/**
 * One Air prompt input found in the UI.
 *
 * @property input the input itself; a type parameter keeps the selection rule free of Swing and Air classes.
 * @property belongsToProject true when the input is hosted by the project that triggered the request.
 * @property isEmpty true when the input holds no text.
 * @property isInChatToolWindow true when the input lives in the project's Air chat tool window.
 */
internal data class AirPromptInputCandidate<T>(val input: T, val belongsToProject: Boolean, val isEmpty: Boolean, val isInChatToolWindow: Boolean)

/**
 * Chooses the Air prompt input that receives a prompt.
 *
 * Several IDE project windows share one JVM, and `Window.getWindows()` returns the windows of all of them. An input of
 * another project is therefore never chosen, even when it is the only one found or the only empty one. Inserting text
 * into another window's chat is worse than copying it to the clipboard.
 */
internal object AirPromptInputSelector {
    /**
     * Returns the best input of the requesting project, or null when it has none.
     *
     * Order of preference: empty inputs first when [preferEmpty] is set, then inputs inside the project's Air chat tool
     * window before others such as the global prompt palette, then the original order of [candidates].
     */
    fun <T> select(candidates: List<AirPromptInputCandidate<T>>, preferEmpty: Boolean): T? {
        val ranked = candidates
            .filter { candidate -> candidate.belongsToProject }
            .sortedWith(
                compareBy<AirPromptInputCandidate<T>>(
                    { candidate -> if (preferEmpty && !candidate.isEmpty) 1 else 0 },
                    { candidate -> if (candidate.isInChatToolWindow) 0 else 1 }
                )
            )
        return ranked.firstOrNull()?.input
    }
}