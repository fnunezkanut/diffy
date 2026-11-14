@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.github.fnunezkanut.bbcloud.ui.pullrequests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.main.CenteredError
import com.github.fnunezkanut.bbcloud.ui.main.CenteredMessage
import com.github.fnunezkanut.bbcloud.ui.main.MainPanelStatusSupport
import com.github.fnunezkanut.bbcloud.ui.main.PullRequestCardActions
import com.github.fnunezkanut.bbcloud.ui.main.PullRequestCardListing
import org.jetbrains.jewel.ui.component.Text

/**
 * The scrollable body of a Pull Requests, below the tab header that holds the Exclude: checkboxes. Shows the
 * listing, the loading/empty/filtered-empty/error cards, then the tab-specific [footer] and the shared
 * user-filter-unavailable note.
 */
@Composable
internal fun PullRequestsListingBody(
    viewModel: PullRequestsViewModel,
    emptyMessageKey: String,
    errorMessageKey: String,
    footer: @Composable (PullRequestsUiState) -> Unit
) {
    val state by viewModel.state
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (state.card) {
                PullRequestsCard.Hidden -> Unit
                PullRequestsCard.Loading -> CenteredMessage(
                    message = DiffyBundle.message("main.pullRequests.loading")
                )

                PullRequestsCard.Empty -> CenteredMessage(
                    message = DiffyBundle.message(emptyMessageKey)
                )

                PullRequestsCard.FilteredEmpty -> CenteredMessage(
                    message = DiffyBundle.message("pullRequests.noneMatchFilters")
                )

                PullRequestsCard.CurrentProjectUnavailable -> CenteredMessage(
                    message = DiffyBundle.message("pullRequests.overview.currentProjectUnavailable")
                )

                PullRequestsCard.Error -> CenteredError(
                    message = DiffyBundle.message(errorMessageKey)
                )

                PullRequestsCard.Listing -> PullRequestCardListing(
                    rows = state.rows,
                    avatarRevision = state.avatarRevision,
                    // Merged and declined rows now appear next to open rows; the badge is None for open rows.
                    showStateBadge = true,
                    avatarIconFor = viewModel::avatarIconFor,
                    actions = PullRequestCardActions(
                        onOpenTitle = viewModel::onOpenTitle,
                        onOpenPrOnWeb = viewModel::onOpenPrOnWeb,
                        onOpenRepository = viewModel::onOpenRepository
                    )
                )
            }
        }
        footer(state)
        if (state.isUserFilterUnavailable) {
            Text(
                text = DiffyBundle.message("pullRequests.userFilterUnavailable"),
                color = MainPanelStatusSupport.warningForeground()
            )
        }
    }
}