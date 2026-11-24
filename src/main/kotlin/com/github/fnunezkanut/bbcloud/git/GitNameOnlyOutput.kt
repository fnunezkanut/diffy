package com.github.fnunezkanut.bbcloud.git

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestPathMatcher

/**
 * Parses the output of `git diff --name-only -z`, which lists NUL-separated paths. For a rename only the new path is
 * listed, which is the path the editor shows.
 *
 * The runner's combined output can start with the printable command line (`$ git ...`), so those entries are ignored.
 */
object GitNameOnlyOutput {
    fun parse(output: String): Set<String> {
        return output.split('\u0000')
            .asSequence()
            .map { entry -> withoutCommandLines(entry = entry) }
            .filter { entry -> entry.isNotBlank() }
            .map { entry -> PullRequestPathMatcher.normalize(entry) }
            .toSet()
    }

    private fun withoutCommandLines(entry: String): String {
        return entry.lineSequence()
            .filterNot { line -> line.startsWith("$ ") }
            .joinToString(separator = "\n")
            .trim('\n', '\r')
    }
}