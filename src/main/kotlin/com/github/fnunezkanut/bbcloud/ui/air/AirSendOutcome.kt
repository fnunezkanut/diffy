package com.github.fnunezkanut.bbcloud.ui.air

/** What happened to a prompt sent to the Air chat. */
internal enum class AirSendOutcome {
    /** The prompt is in the Air chat input, not submitted. */
    Inserted,

    /** The Air chat input could not be reached, so the prompt is on the clipboard. */
    CopiedToClipboard
}