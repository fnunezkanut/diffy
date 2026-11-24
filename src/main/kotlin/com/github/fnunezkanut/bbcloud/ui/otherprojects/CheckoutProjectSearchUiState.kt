package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository

/** One search match. [isCheckedOut] rows are drawn greyed out with a label and can never be selected. */
internal data class CheckoutProjectSearchRow(val repository: BitbucketWorkspaceRepository, val isCheckedOut: Boolean)

internal data class CheckoutProjectSearchUiState(
    val query: String = "",
    val status: CheckoutProjectSearchStatus = CheckoutProjectSearchStatus.Idle,
    val results: List<CheckoutProjectSearchRow> = emptyList(),
    val checkedOutCount: Int = 0,
    val hasMore: Boolean = false,
    val selectedIndex: Int = -1
) {
    /** The selected repository. It is never a checked-out row. */
    val selected: BitbucketWorkspaceRepository?
        get() = results.getOrNull(selectedIndex)?.takeUnless { row -> row.isCheckedOut }?.repository
}

internal enum class CheckoutProjectSearchStatus {
    /** The query is shorter than the minimum search length; no request is made. */
    Idle,
    Searching,
    Results,

    /** Bitbucket returned no match at all. */
    NoMatches,

    /** Bitbucket returned matches, but every one is already in the Projects Directory. The greyed rows are still listed. */
    AllCheckedOut,
    Unauthorized,
    Failed
}

/** Every repository, flagged when it already has an entry in the projects directory, sorted by lower-cased slug. */
internal fun checkoutSearchRows(repositories: List<BitbucketWorkspaceRepository>, existingFolderNames: Set<String>): List<CheckoutProjectSearchRow> {
    return repositories
        .map { repository ->
            CheckoutProjectSearchRow(
                repository = repository,
                isCheckedOut = repository.slug.lowercase() in existingFolderNames
            )
        }
        .sortedBy { row -> row.repository.slug.lowercase() }
}