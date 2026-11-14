@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.github.fnunezkanut.bbcloud.ui.pullrequests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.settings.PullRequestListFilter
import com.github.fnunezkanut.bbcloud.settings.PullRequestProjectScope
import com.github.fnunezkanut.bbcloud.settings.PullRequestUserScope
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.ListComboBox

/**
 * The Exclude checkboxes and the two dropdowns, emitted inline into the header row of the Pull Requests.
 *
 * Layout, left to right: the user scope dropdown, the project scope dropdown, then Exclude Resolved, Exclude Approved
 * by me, and Exclude Drafts. The project scope dropdown offers In current project only once the current project
 * lookup resolved ([isProjectScopeOffered]).
 */
@Composable
internal fun RowScope.PullRequestExcludeCheckboxes(filter: PullRequestListFilter, viewModel: PullRequestsViewModel, isProjectScopeOffered: Boolean) {
    // The 10dp start padding separates the group from the left-hand header section (the repo link and the open-count pill).
    Row(
        modifier = Modifier.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        UserScopeComboBox(
            userScope = filter.userScope,
            onUserScopeChanged = viewModel::onUserScopeChanged
        )
        ProjectScopeComboBox(
            projectScope = filter.projectScope,
            isProjectScopeOffered = isProjectScopeOffered,
            onProjectScopeChanged = viewModel::onProjectScopeChanged
        )
        CheckboxRow(
            text = DiffyBundle.message("pullRequests.exclude.resolved"),
            checked = filter.excludeResolved,
            onCheckedChange = viewModel::onExcludeResolvedChanged
        )
        CheckboxRow(
            text = DiffyBundle.message("pullRequests.exclude.approvedByMe"),
            checked = filter.excludeApprovedByMe,
            onCheckedChange = viewModel::onExcludeApprovedByMeChanged
        )
        CheckboxRow(
            text = DiffyBundle.message("pullRequests.exclude.drafts"),
            checked = filter.excludeDrafts,
            onCheckedChange = viewModel::onExcludeDraftsChanged
        )
    }
}

/**
 * The project scope dropdown. Its items are In any project, plus In current project when [isProjectScopeOffered] is
 * true, in [PullRequestProjectScope] declaration order. The Jewel combo box cannot disable a single item, so the second
 * item is left out until the current project lookup resolved. A saved [PullRequestProjectScope.CurrentProject]
 * therefore shows as In any project until then; the saved value itself is not changed.
 */
@Composable
private fun ProjectScopeComboBox(
    projectScope: PullRequestProjectScope,
    isProjectScopeOffered: Boolean,
    onProjectScopeChanged: (PullRequestProjectScope) -> Unit
) {
    val choices = if (isProjectScopeOffered) PullRequestProjectScope.entries.toList() else listOf(PullRequestProjectScope.AnyProject)
    val options = choices.map { choice -> DiffyBundle.message(labelKeyOf(projectScope = choice)) }
    val selectedIndex = choices.indexOf(projectScope).coerceAtLeast(0)
    ListComboBox(
        items = options,
        selectedIndex = selectedIndex,
        onSelectedItemChange = { index ->
            onProjectScopeChanged(choices.getOrElse(index) { PullRequestProjectScope.AnyProject })
        },
        modifier = Modifier.width(200.dp)
    )
}

/** The message key of the label shown for [projectScope] in the project scope dropdown. */
private fun labelKeyOf(projectScope: PullRequestProjectScope): String {
    return when (projectScope) {
        PullRequestProjectScope.AnyProject -> "pullRequests.projectScope.anyProject"
        PullRequestProjectScope.CurrentProject -> "pullRequests.projectScope.currentProject"
    }
}

/** The user scope dropdown. Its items are Assigned to me, Created by me and Created by others, in [PullRequestUserScope] declaration order. */
@Composable
private fun UserScopeComboBox(userScope: PullRequestUserScope, onUserScopeChanged: (PullRequestUserScope) -> Unit) {
    val choices = PullRequestUserScope.entries.toList()
    val options = choices.map { choice -> DiffyBundle.message(labelKeyOf(userScope = choice)) }
    val selectedIndex = choices.indexOf(userScope).coerceAtLeast(0)
    ListComboBox(
        items = options,
        selectedIndex = selectedIndex,
        onSelectedItemChange = { index ->
            onUserScopeChanged(choices.getOrElse(index) { PullRequestUserScope.AssignedToMe })
        },
        modifier = Modifier.width(200.dp)
    )
}

/** The message key of the label shown for [userScope] in the user scope dropdown. */
private fun labelKeyOf(userScope: PullRequestUserScope): String {
    return when (userScope) {
        PullRequestUserScope.AssignedToMe -> "pullRequests.userScope.assignedToMe"
        PullRequestUserScope.Me -> "pullRequests.userScope.me"
        PullRequestUserScope.Others -> "pullRequests.userScope.others"
    }
}