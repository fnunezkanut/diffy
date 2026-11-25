@file:OptIn(
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@file:Suppress("ktlint:standard:function-naming")

package com.github.fnunezkanut.bbcloud.ui.otherprojects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryListResponseParser
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Path

/** Callbacks of [CheckoutProjectSearchScreen]. */
internal class CheckoutProjectSearchActions(
    val onQueryChange: (String) -> Unit,
    val onClear: () -> Unit,
    val onSelect: (Int) -> Unit,
    val onMoveSelection: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onConfirm: () -> Unit,
    val onSelectionAvailable: (Boolean) -> Unit
)

/** The search box and the result list of the Checkout Project dialog. Stateless apart from the text field value. */
@Composable
internal fun CheckoutProjectSearchScreen(
    state: CheckoutProjectSearchUiState,
    workspace: String,
    projectsDirectory: String,
    actions: CheckoutProjectSearchActions
) {
    val hasSelection = state.selected != null
    LaunchedEffect(hasSelection) {
        actions.onSelectionAvailable(hasSelection)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.searchPrompt", workspace))
        SearchRow(state = state, actions = actions)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CheckoutProjectResults(state = state, workspace = workspace, projectsDirectory = projectsDirectory, actions = actions)
        }
        CheckoutProjectFooter(state = state, projectsDirectory = projectsDirectory)
    }
}

@Composable
private fun SearchRow(state: CheckoutProjectSearchUiState, actions: CheckoutProjectSearchActions) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextField(
            value = fieldValue,
            onValueChange = { newValue ->
                val textChanged = newValue.text != fieldValue.text
                fieldValue = newValue
                if (textChanged) {
                    actions.onQueryChange(newValue.text)
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event -> handleKeyEvent(event = event, hasSelection = state.selected != null, actions = actions) },
            placeholder = {
                Text(
                    text = DiffyBundle.message(
                        "workspace.watchedProjects.checkout.dialog.search.placeholder",
                        BitbucketRepositoryListResponseParser.MIN_SEARCH_QUERY_LENGTH
                    )
                )
            },
            leadingIcon = {
                Icon(
                    key = AllIconsKeys.Actions.Search,
                    contentDescription = DiffyBundle.message("workspace.watchedProjects.search.icon.description")
                )
            }
        )
        if (state.status == CheckoutProjectSearchStatus.Searching) {
            CircularProgressIndicator()
        } else if (fieldValue.text.isNotEmpty()) {
            ClearIcon(
                onClear = {
                    fieldValue = TextFieldValue("")
                    actions.onClear()
                }
            )
        }
    }
}

@Composable
private fun ClearIcon(onClear: () -> Unit) {
    Tooltip(tooltip = { Text(text = DiffyBundle.message("workspace.watchedProjects.search.clear.tooltip")) }) {
        Icon(
            key = AllIconsKeys.Actions.Close,
            contentDescription = DiffyBundle.message("workspace.watchedProjects.search.clear.tooltip"),
            modifier = Modifier.clickable(onClick = onClear).pointerHoverIcon(PointerIcon.Hand)
        )
    }
}

/** Up and Down move the selection, Enter confirms it. Returns true when the event was handled. */
private fun handleKeyEvent(event: KeyEvent, hasSelection: Boolean, actions: CheckoutProjectSearchActions): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    return when (event.key) {
        Key.DirectionDown -> {
            actions.onMoveSelection(1)
            true
        }
        Key.DirectionUp -> {
            actions.onMoveSelection(-1)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (hasSelection) {
                actions.onConfirm()
            }
            hasSelection
        }
        else -> {
            false
        }
    }
}

@Composable
private fun CheckoutProjectResults(state: CheckoutProjectSearchUiState, workspace: String, projectsDirectory: String, actions: CheckoutProjectSearchActions) {
    when (state.status) {
        CheckoutProjectSearchStatus.Idle -> {
            Text(
                text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.hint"),
                color = JewelTheme.globalColors.text.info
            )
        }
        CheckoutProjectSearchStatus.Searching -> {
            if (state.results.isEmpty()) {
                SearchingPlaceholder()
            } else {
                CheckoutProjectList(state = state, projectsDirectory = projectsDirectory, actions = actions, modifier = Modifier.alpha(DIMMED_ALPHA))
            }
        }
        CheckoutProjectSearchStatus.Results -> {
            CheckoutProjectList(state = state, projectsDirectory = projectsDirectory, actions = actions, modifier = Modifier)
        }
        CheckoutProjectSearchStatus.AllCheckedOut -> {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.allCheckedOut", state.query.trim()),
                    color = JewelTheme.globalColors.text.info
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CheckoutProjectList(state = state, projectsDirectory = projectsDirectory, actions = actions, modifier = Modifier)
                }
            }
        }
        CheckoutProjectSearchStatus.NoMatches -> {
            Text(text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.noMatches", workspace, state.query.trim()))
        }
        CheckoutProjectSearchStatus.Unauthorized -> {
            Text(
                text = DiffyBundle.message("workspace.watchedProjects.checkout.failed.unauthorized"),
                color = JewelTheme.globalColors.text.warning
            )
        }
        CheckoutProjectSearchStatus.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.failed"),
                    color = JewelTheme.globalColors.text.warning
                )
                Link(
                    text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.retry"),
                    onClick = actions.onRetry
                )
            }
        }
    }
}

@Composable
private fun SearchingPlaceholder() {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Text(text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.searching"))
    }
}

@Composable
private fun CheckoutProjectList(state: CheckoutProjectSearchUiState, projectsDirectory: String, actions: CheckoutProjectSearchActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    ScrollToSelection(listState = listState, selectedIndex = state.selectedIndex)
    VerticallyScrollableContainer(scrollState = listState, modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items = state.results, key = { _, row -> row.repository.slug }) { index, row ->
                CheckoutProjectRowItem(
                    row = row,
                    selected = index == state.selectedIndex,
                    projectsDirectory = projectsDirectory,
                    onClick = { actions.onSelect(index) },
                    onDoubleClick = {
                        actions.onSelect(index)
                        actions.onConfirm()
                    }
                )
            }
        }
    }
}

@Composable
private fun ScrollToSelection(listState: LazyListState, selectedIndex: Int) {
    LaunchedEffect(selectedIndex) {
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { item -> item.index == selectedIndex }
        if (selectedIndex >= 0 && !isVisible) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
}

@Composable
private fun CheckoutProjectRowItem(
    row: CheckoutProjectSearchRow,
    selected: Boolean,
    projectsDirectory: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    if (row.isCheckedOut) {
        CheckedOutRow(row = row, projectsDirectory = projectsDirectory)
        return
    }
    val background = if (selected) {
        Modifier.background(color = JewelTheme.globalColors.outlines.focused.copy(alpha = SELECTED_ALPHA))
    } else {
        Modifier
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(background)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(row.repository.slug) {
                detectTapGestures(onTap = { onClick() }, onDoubleTap = { onDoubleClick() })
            }
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RepositoryNames(row = row, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CheckedOutRow(row: CheckoutProjectSearchRow, projectsDirectory: String) {
    val label = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.checkedOutLabel")
    val target = Path.of(projectsDirectory).resolve(row.repository.slug).toString()
    Tooltip(
        tooltip = {
            Text(text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.checkedOutTooltip", target))
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .alpha(CHECKED_OUT_ALPHA)
                .semantics {
                    contentDescription = "${row.repository.slug}, $label"
                    disabled()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RepositoryNames(row = row, modifier = Modifier.weight(1f))
            Text(text = label, color = JewelTheme.globalColors.text.info)
        }
    }
}

@Composable
private fun RepositoryNames(row: CheckoutProjectSearchRow, modifier: Modifier) {
    val repository = row.repository
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = repository.slug, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (repository.name != repository.slug) {
            Text(
                text = repository.name,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CheckoutProjectFooter(state: CheckoutProjectSearchUiState, projectsDirectory: String) {
    val selected = state.selected
    if (selected != null) {
        Text(
            text = DiffyBundle.message(
                "workspace.watchedProjects.checkout.dialog.target",
                Path.of(projectsDirectory).resolve(selected.slug).toString(),
                selected.mainBranch ?: DiffyBundle.message("workspace.watchedProjects.checkout.dialog.defaultBranch")
            ),
            color = JewelTheme.globalColors.text.info
        )
    }
    if (state.hasMore) {
        Text(
            text = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.search.more"),
            color = JewelTheme.globalColors.text.warning
        )
    }
}

private const val DIMMED_ALPHA = 0.5f
private const val CHECKED_OUT_ALPHA = 0.45f
private const val SELECTED_ALPHA = 0.25f