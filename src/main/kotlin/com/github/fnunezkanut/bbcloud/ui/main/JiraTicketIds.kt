package com.github.fnunezkanut.bbcloud.ui.main

/**
 * Finds Jira ticket ids such as `ABC-123` in pull request text.
 *
 * A key is 2 to 10 characters, starts with a letter and continues with letters or digits, followed by `-` and digits.
 * The title is matched on uppercase keys only. The branch name is also matched case-insensitively and normalized to
 * uppercase, because branches are often lowercase (`abc-123-fix`); a few well known non-Jira prefixes are ignored there.
 */
object JiraTicketIds {
    private const val KEY_PATTERN = "[A-Za-z][A-Za-z0-9]{1,9}-\\d+"
    private const val BOUNDARY_BEFORE = "(?<![A-Za-z0-9])"
    private const val BOUNDARY_AFTER = "(?![A-Za-z0-9])"

    private val UPPERCASE_REGEX = Regex("$BOUNDARY_BEFORE([A-Z][A-Z0-9]{1,9}-\\d+)$BOUNDARY_AFTER")
    private val ANY_CASE_REGEX = Regex("$BOUNDARY_BEFORE($KEY_PATTERN)$BOUNDARY_AFTER")
    private val IGNORED_PREFIXES = setOf("UTF", "SHA", "ISO", "MD", "RFC", "V")

    /** Returns the distinct ids found in [title] first, then in [branch], each in order of appearance. */
    fun parse(title: String?, branch: String?): List<String> {
        val ids = LinkedHashSet<String>()
        title?.let { text ->
            UPPERCASE_REGEX.findAll(text).forEach { match -> ids.add(match.groupValues[1]) }
        }
        branch?.let { text ->
            ANY_CASE_REGEX.findAll(text).forEach { match ->
                val id = match.groupValues[1].uppercase()
                if (id.substringBefore('-') !in IGNORED_PREFIXES) {
                    ids.add(id)
                }
            }
        }
        return ids.toList()
    }
}

/** Builds Jira Cloud browse links. */
object JiraTicketLinks {
    fun browseUrl(workspace: String, ticketId: String): String {
        return "https://$workspace.atlassian.net/browse/$ticketId"
    }
}