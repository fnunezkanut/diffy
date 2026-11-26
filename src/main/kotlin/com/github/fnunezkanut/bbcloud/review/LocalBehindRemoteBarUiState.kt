package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.PrBranchRole

/**
 * What the red "local branches are behind Bitbucket" bar shows.
 *
 * @param message the whole sentence before the link.
 * @param linkText the text of the sync link, for example "Sync local branch".
 * @param tooltip the explanation plus one line per branch that is not updated automatically, separated by newlines.
 * @param roles the pull request branches that are behind, source first.
 * @param updateInProgress whether a user-requested sync is running, which disables the link.
 */
data class LocalBehindRemoteBarUiState(
    val message: String,
    val linkText: String,
    val tooltip: String,
    val roles: List<PrBranchRole>,
    val updateInProgress: Boolean = false
)