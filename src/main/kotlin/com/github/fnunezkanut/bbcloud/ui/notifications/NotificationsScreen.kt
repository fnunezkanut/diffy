@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserEventCategory
import com.github.fnunezkanut.bbcloud.ui.otherprojects.toComposeImageBitmap
import com.github.fnunezkanut.bbcloud.ui.pullrequests.LISTING_SUBTITLE_SEPARATOR
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.TriStateCheckbox
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Extra space between the select all checkbox and the Mark as read button, on top of the row spacing. */
private val SELECT_ALL_BUTTON_GAP = 7.dp
private val CHECKBOX_COLUMN_WIDTH = 32.dp
private val TYPE_COLUMN_WIDTH = 32.dp
private val TYPE_ICON_SIZE = 16.dp
private val AVATAR_SIZE = 32.dp
private const val STRIPE_ALPHA = 0.12f

/** The bump, in sp, of the event title over the default body text, like the pull request listing titles. */
private const val TITLE_FONT_SIZE_BUMP = 1f

/** What the rows of the table can do; keeps the composables free of the view model. */
private data class NotificationRowActions(
    val onSelectionChanged: (rowId: String, selected: Boolean) -> Unit,
    val onTitleClicked: (NotificationRow) -> Unit,
    val onMessageClicked: (NotificationRow) -> Unit,
    val onPullRequestNumberClicked: (NotificationRow) -> Unit,
    val onRepositoryClicked: (NotificationRow) -> Unit,
    val onSelectAllClicked: () -> Unit,
    val onMarkReadClicked: () -> Unit,
    val onMarkUnreadClicked: () -> Unit,
    val onShowOnlyUnreadChanged: (Boolean) -> Unit,
    val avatarIconFor: (String?) -> javax.swing.Icon
)

@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel) {
    val state by viewModel.state
    val actions = remember(viewModel) {
        NotificationRowActions(
            onSelectionChanged = { rowId, selected -> viewModel.onRowSelectionChanged(rowId = rowId, selected = selected) },
            onTitleClicked = viewModel::onTitleClicked,
            onMessageClicked = viewModel::onMessageClicked,
            onPullRequestNumberClicked = viewModel::onPullRequestNumberClicked,
            onRepositoryClicked = viewModel::onRepositoryClicked,
            onSelectAllClicked = viewModel::onSelectAllClicked,
            onMarkReadClicked = viewModel::onMarkReadClicked,
            onMarkUnreadClicked = viewModel::onMarkUnreadClicked,
            onShowOnlyUnreadChanged = viewModel::onShowOnlyUnreadChanged,
            avatarIconFor = viewModel::avatarIconFor
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.hasData -> CenteredMessage(text = DiffyBundle.message("notifications.empty"))
                state.isEmpty -> CenteredMessage(text = DiffyBundle.message("notifications.emptyAfterCapture"))
                else -> NotificationsTable(state = state, actions = actions)
            }
        }
        NotificationsFooter(
            state = state,
            actions = actions,
            onSetupHelp = viewModel::onSetupHelpClicked
        )
    }
}

/**
 * The always visible footer row: the select all checkbox, the mark as read and unread buttons, and the setup help link
 * on the right.
 */
@Composable
private fun NotificationsFooter(state: NotificationsUiState, actions: NotificationRowActions, onSetupHelp: () -> Unit) {
    Divider(
        orientation = Orientation.Horizontal,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tooltip(tooltip = { Text(text = DiffyBundle.message("notifications.column.selectAll")) }) {
            TriStateCheckbox(
                state = state.selectAllState,
                onClick = actions.onSelectAllClicked,
                enabled = state.visibleRows.isNotEmpty()
            )
        }
        Spacer(modifier = Modifier.width(SELECT_ALL_BUTTON_GAP))
        OutlinedButton(onClick = actions.onMarkReadClicked, enabled = state.hasSelection) {
            Text(text = DiffyBundle.message("notifications.action.markRead"))
        }
        OutlinedButton(onClick = actions.onMarkUnreadClicked, enabled = state.hasSelection) {
            Text(text = DiffyBundle.message("notifications.action.markUnread"))
        }
        Spacer(modifier = Modifier.weight(1f))
        Link(
            text = DiffyBundle.message("notifications.setupHelp.link"),
            onClick = onSetupHelp
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NotificationsTable(state: NotificationsUiState, actions: NotificationRowActions) {
    val listState = rememberLazyListState()
    Column(modifier = Modifier.fillMaxSize()) {
        NotificationsToolbar(state = state, actions = actions)
        Divider(
            orientation = Orientation.Horizontal,
            modifier = Modifier.fillMaxWidth()
        )
        VerticallyScrollableContainer(
            scrollState = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items = state.visibleRows, key = { _, row -> row.id }) { rowIndex, row ->
                    NotificationRowItem(
                        row = row,
                        rowIndex = rowIndex,
                        selected = row.id in state.selectedIds,
                        avatarRevision = state.avatarRevision,
                        actions = actions
                    )
                }
            }
        }
    }
}

/** The last sync time on the left, the Show only Unread checkbox on the right. */
@Composable
private fun NotificationsToolbar(state: NotificationsUiState, actions: NotificationRowActions) {
    val showOnlyUnreadText = DiffyBundle.message("notifications.filter.showOnlyUnread")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = DiffyBundle.message("notifications.capturedAt", state.lastUpdatedText),
            color = JewelTheme.globalColors.text.info,
            modifier = Modifier.weight(1f)
        )
        CheckboxRow(
            checked = state.showOnlyUnread,
            onCheckedChange = actions.onShowOnlyUnreadChanged,
            modifier = Modifier.semantics { contentDescription = showOnlyUnreadText }
        ) {
            Text(text = showOnlyUnreadText)
        }
    }
}

@Composable
private fun NotificationRowItem(row: NotificationRow, rowIndex: Int, selected: Boolean, avatarRevision: Int, actions: NotificationRowActions) {
    val stripeModifier = if (rowIndex % 2 == 1) {
        Modifier.background(color = JewelTheme.globalColors.borders.normal.copy(alpha = STRIPE_ALPHA))
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(stripeModifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.width(CHECKBOX_COLUMN_WIDTH)) {
            Checkbox(
                checked = selected,
                onCheckedChange = { checked -> actions.onSelectionChanged(row.id, checked) }
            )
        }
        TypeIconCell(row = row)
        EventCell(
            row = row,
            avatarRevision = avatarRevision,
            actions = actions,
            modifier = Modifier.weight(1f)
        )
        MessageCell(
            row = row,
            onMessageClicked = actions.onMessageClicked,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The notification message in its own column, in the normal text color; clickable when the event has an https URL. */
@Composable
private fun MessageCell(row: NotificationRow, onMessageClicked: (NotificationRow) -> Unit, modifier: Modifier) {
    val hasLink = row.url?.startsWith("https://") == true
    Box(modifier = modifier.padding(top = 2.dp)) {
        Tooltip(tooltip = { Text(text = row.eventTitle) }) {
            if (hasLink) {
                Text(
                    text = row.eventTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .pointerHoverIcon(icon = PointerIcon.Hand)
                        .clickable(role = Role.Button, onClick = { onMessageClicked(row) })
                )
            } else {
                Text(
                    text = row.eventTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The 16x16 icon of the notification type; the type label shows on hover. */
@Composable
private fun TypeIconCell(row: NotificationRow) {
    Box(
        modifier = Modifier.width(TYPE_COLUMN_WIDTH).padding(top = 2.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Tooltip(tooltip = { Text(text = row.typeLabel) }) {
            Icon(
                key = notificationIconKey(category = row.category),
                contentDescription = row.typeLabel,
                modifier = Modifier.size(TYPE_ICON_SIZE)
            )
        }
    }
}

/** The platform icon of [category]. The exhaustive `when` flags every new category at compile time. */
internal fun notificationIconKey(category: DiffyBrowserEventCategory): IconKey {
    return when (category) {
        DiffyBrowserEventCategory.REVIEWER_ADDED -> AllIconsKeys.General.InspectionsEye
        DiffyBrowserEventCategory.APPROVAL -> AllIconsKeys.General.InspectionsOK
        DiffyBrowserEventCategory.BOT_REVIEW_COMMENT -> DiffyNotificationIcons.robot
        DiffyBrowserEventCategory.HUMAN_COMMENT -> AllIconsKeys.General.Balloon
        DiffyBrowserEventCategory.MENTION -> AllIconsKeys.Actions.Annotate
        DiffyBrowserEventCategory.PR_UPDATED -> AllIconsKeys.Actions.Refresh
        DiffyBrowserEventCategory.MERGED -> AllIconsKeys.Vcs.Merge
        DiffyBrowserEventCategory.OTHER_BITBUCKET -> AllIconsKeys.General.Information
    }
}

/** The actor avatar, the event title as a link, and the subtitle `actor · #id · time · repository`. */
@Composable
private fun EventCell(row: NotificationRow, avatarRevision: Int, actions: NotificationRowActions, modifier: Modifier) {
    val avatar = remember(row.actorAvatarUrl, avatarRevision) {
        actions.avatarIconFor(row.actorAvatarUrl).toComposeImageBitmap()
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            bitmap = avatar,
            contentDescription = null,
            modifier = Modifier.size(AVATAR_SIZE),
            filterQuality = FilterQuality.High
        )
        Column(modifier = Modifier.weight(1f)) {
            EventTitle(row = row, onTitleClicked = actions.onTitleClicked)
            EventSubtitle(row = row, actions = actions)
        }
    }
}

@Composable
private fun EventTitle(row: NotificationRow, onTitleClicked: (NotificationRow) -> Unit) {
    val titleStyle = JewelTheme.defaultTextStyle.copy(
        fontSize = (JewelTheme.defaultTextStyle.fontSize.value + TITLE_FONT_SIZE_BUMP).sp,
        fontWeight = if (row.isUnread) FontWeight.Bold else FontWeight.Normal
    )
    val unreadDescription = DiffyBundle.message("notifications.unread.description")
    val semanticsModifier = if (row.isUnread) {
        Modifier.semantics { contentDescription = unreadDescription }
    } else {
        Modifier
    }
    Tooltip(tooltip = { Text(text = row.pullRequestTitle) }, modifier = semanticsModifier) {
        if (row.pullRequestId == null) {
            Text(
                text = row.pullRequestTitle,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Link(
                text = row.pullRequestTitle,
                onClick = { onTitleClicked(row) },
                textStyle = titleStyle,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The subtitle parts that exist, joined by separators: the actor, the pull request number (a link when the pull request
 * page is known), the relative time, and the repository (a link when a workspace is known).
 */
@Composable
private fun EventSubtitle(row: NotificationRow, actions: NotificationRowActions) {
    val hasActor = row.actorText.isNotBlank()
    val hasNumber = row.pullRequestNumberText.isNotBlank()
    val hasTime = row.timeText.isNotBlank()
    val repoSlug = row.repoSlug
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasActor) {
            Text(
                text = row.actorText,
                color = JewelTheme.globalColors.text.info,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (hasNumber) {
            if (hasActor) {
                SubtitleSeparator()
            }
            PullRequestNumber(row = row, onClicked = actions.onPullRequestNumberClicked)
        }
        if (hasTime) {
            if (hasActor || hasNumber) {
                SubtitleSeparator()
            }
            Text(
                text = row.timeText,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (repoSlug != null) {
            if (hasActor || hasNumber || hasTime) {
                SubtitleSeparator()
            }
            RepositoryName(row = row, repoSlug = repoSlug, onClicked = actions.onRepositoryClicked)
        }
    }
}

@Composable
private fun PullRequestNumber(row: NotificationRow, onClicked: (NotificationRow) -> Unit) {
    if (row.pullRequestWebUrl == null) {
        Text(text = row.pullRequestNumberText, maxLines = 1)
    } else {
        Link(
            text = row.pullRequestNumberText,
            onClick = { onClicked(row) },
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RowScope.RepositoryName(row: NotificationRow, repoSlug: String, onClicked: (NotificationRow) -> Unit) {
    if (row.workspace == null && row.urlWorkspace == null) {
        Text(
            text = repoSlug,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    } else {
        Link(
            text = repoSlug,
            onClick = { onClicked(row) },
            modifier = Modifier.weight(1f, fill = false),
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubtitleSeparator() {
    Text(
        text = LISTING_SUBTITLE_SEPARATOR,
        color = JewelTheme.globalColors.text.info,
        maxLines = 1
    )
}