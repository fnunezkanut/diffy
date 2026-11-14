package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle

/**
 * Builds the footer under the Compose Settings form. It lives outside the screen so it can be unit tested without
 * touching Compose or the hosting tool window.
 *
 * The footer has two lines. The first line is a [label] (the plugin name, rendered as a clickable link to the project
 * on GitHub) followed by the [buildVersionSuffix] remainder. The second line is the [disclaimer], rendered on its own.
 * [build] joins the label and the version suffix so the first line stays verifiable in one place.
 */
object SetupFooterText {
    /** The GitHub project the footer label links to. */
    const val PROJECT_URL = "https://github.com/fnunezkanut/diffy"

    /** The plugin-name label; the screen renders this as the clickable link. */
    fun label(): String {
        return DiffyBundle.message("about.footer.label")
    }

    /** The disclaimer shown on its own line below the label and version. */
    fun disclaimer(): String {
        return DiffyBundle.message("setup.disclaimer")
    }

    /** ": <version>" for a resolved [version], or an empty string when it is null or blank. */
    fun buildVersionSuffix(version: String?): String {
        val normalizedVersion = version?.trim().orEmpty()
        if (normalizedVersion.isEmpty()) {
            return ""
        }
        return DiffyBundle.message("about.footer.versionSuffix", normalizedVersion)
    }

    /** The first footer line: the label followed by the version suffix, if any. */
    fun build(version: String?): String {
        return label() + buildVersionSuffix(version)
    }
}