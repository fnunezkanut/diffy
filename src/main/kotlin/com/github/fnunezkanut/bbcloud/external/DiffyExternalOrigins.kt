package com.github.fnunezkanut.bbcloud.external

/**
 * Browser origins that may call the Diffy REST endpoints `/api/diffy/open`, `/api/diffy/snapshot` and
 * `/api/diffy/workspace`.
 */
object DiffyExternalOrigins {
    /** The scheme prefix of every Chrome extension origin. */
    const val CHROME_EXTENSION_PREFIX = "chrome-extension://"

    /** The scheme prefix of every Firefox extension origin; the part after it is a random UUID per browser profile. */
    const val FIREFOX_EXTENSION_PREFIX = "moz-extension://"

    /** Origin of the extension published in the Chrome Web Store. */
    const val CHROME_WEB_STORE_EXTENSION_ORIGIN = "chrome-extension://pkeoedpgepmldoikeedkchmhkgjpelln"

    /**
     * Origin of the unpacked development copy loaded from `diffy-chrome-ext/`. The manifest has no `key`, so Chrome
     * derives this ID from the load path; it is stable only for that path.
     */
    const val CHROME_UNPACKED_EXTENSION_ORIGIN = "chrome-extension://knninefpkpeielbdhlbhdheabjmkebpb"

    /** Every Chrome origin treated as the Diffy extension. */
    val ALLOWED_CHROME_EXTENSION_ORIGINS: Set<String> =
        setOf(
            CHROME_WEB_STORE_EXTENSION_ORIGIN,
            CHROME_UNPACKED_EXTENSION_ORIGIN
        )

    /** Describes the accepted origins for log lines. */
    fun expectedOriginsDescription(): String {
        return ALLOWED_CHROME_EXTENSION_ORIGINS.joinToString(separator = " or ") + " or moz-extension://*"
    }
}