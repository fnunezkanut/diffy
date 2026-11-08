package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.ui.state.ToggleableState
import com.github.fnunezkanut.bbcloud.external.DiffyNotificationsContent
import com.github.fnunezkanut.bbcloud.external.DiffyStoredNotification
import com.github.fnunezkanut.bbcloud.external.browserEvent
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ImageIcon
import kotlin.time.Instant

@Tag("unit")
class NotificationsViewModelTest {
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val stored = MutableStateFlow(DiffyNotificationsContent.NOT_LOADED)
    private val unreadChanges = mutableListOf<Pair<Set<String>, Boolean>>()
    private var helpOpened = 0
    private val reviewed = mutableListOf<String>()
    private val openedOnWeb = mutableListOf<String>()
    private val openedRepositories = mutableListOf<String>()
    private var zone: TimeZone = TimeZone.UTC
    private val labelRequests = AtomicInteger(0)

    private val viewModel = NotificationsViewModel(
        stored = stored,
        latestPullRequests = { emptyList() },
        setUnread = { ids, unread -> unreadChanges.add(ids to unread) },
        coroutineScope = scope,
        runOnEdt = { block -> block() },
        openSetupHelp = { helpOpened++ },
        openForReview = { row -> reviewed.add(row.id) },
        openOnWeb = { row -> openedOnWeb.add(row.id) },
        openRepository = { row -> openedRepositories.add(row.id) },
        avatarIcon = { ImageIcon() },
        timeZone = { zone },
        labels = {
            labelRequests.incrementAndGet()
            NotificationLabels(
                typeLabel = { category -> category.wireName },
                botSuffix = " (bot)",
                timeText = { instant, zone -> formatTime(instant = instant, timeZone = zone) },
                lastUpdatedText = { instant, zone -> formatTime(instant = instant, timeZone = zone) }
            )
        },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun content(
        vararg ids: String,
        lastSyncedAt: Instant? = Instant.parse("2026-11-08T10:00:00.000Z"),
        unread: Boolean = true
    ): DiffyNotificationsContent {
        return DiffyNotificationsContent(
            lastSyncedAt = lastSyncedAt,
            notifications = ids.map { id -> DiffyStoredNotification(event = browserEvent(id = id), unread = unread) },
            loaded = true
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start - should stay initial while the stored notifications are not loaded`() {
        // GIVEN / WHEN
        viewModel.start()

        // THEN
        viewModel.state.value shouldBe NotificationsUiState.INITIAL
    }

    @Test
    fun `start - should fill the rows from the stored notifications and follow the next ones`() {
        // GIVEN a started view model
        viewModel.start()

        // WHEN two states arrive one after the other
        stored.value = content("one")
        val first = viewModel.state.value
        stored.value = content("two", "three", lastSyncedAt = Instant.parse("2026-11-08T11:00:00.000Z"))
        val second = viewModel.state.value

        // THEN the table follows the latest state
        assertSoftly {
            first.hasData shouldBe true
            first.rows.map { row -> row.id } shouldBe listOf("one")
            second.rows.map { row -> row.id }.toSet() shouldBe setOf("two", "three")
            second.lastUpdatedText shouldBe "2026-11-08 11:00"
        }
    }

    @Test
    fun `start - should not collect twice when called twice`() {
        // GIVEN a view model started two times
        viewModel.start()
        viewModel.start()

        // WHEN one snapshot arrives
        stored.value = content("one")

        // THEN it was built once
        assertSoftly {
            labelRequests.get() shouldBe 1
            viewModel.state.value.rows.size shouldBe 1
        }
    }

    @Test
    fun `refreshTimes - should render the time again in the new zone`() {
        // GIVEN a snapshot rendered in UTC
        viewModel.start()
        stored.value = DiffyNotificationsContent(
            lastSyncedAt = null,
            notifications = listOf(DiffyStoredNotification(event = browserEvent(occurredAt = "2026-11-06T23:30:00Z"))),
            loaded = true
        )
        val before = viewModel.state.value.rows.single().timeText

        // WHEN the system zone changes and the tab asks for a refresh
        zone = TimeZone.of("Asia/Tokyo")
        viewModel.refreshTimes()
        val after = viewModel.state.value.rows.single().timeText

        // THEN only the text changed
        assertSoftly {
            before shouldBe "2026-11-06 23:30"
            after shouldBe "2026-11-07 08:30"
        }
    }

    @Test
    fun `onRowSelectionChanged - should select and deselect a single row`() {
        // GIVEN two rows
        viewModel.start()
        stored.value = content("a", "b")

        // WHEN one row is selected and then deselected
        viewModel.onRowSelectionChanged(rowId = "a", selected = true)
        val selected = viewModel.state.value
        viewModel.onRowSelectionChanged(rowId = "a", selected = false)
        val cleared = viewModel.state.value

        // THEN the header state follows
        assertSoftly {
            selected.selectedIds shouldBe setOf("a")
            selected.selectAllState shouldBe ToggleableState.Indeterminate
            cleared.selectedIds shouldBe emptySet()
            cleared.selectAllState shouldBe ToggleableState.Off
        }
    }

    @Test
    fun `onSelectAllClicked - should select every row and clear when all are selected`() {
        // GIVEN two rows
        viewModel.start()
        stored.value = content("a", "b")

        // WHEN select all is clicked twice
        viewModel.onSelectAllClicked()
        val all = viewModel.state.value
        viewModel.onSelectAllClicked()
        val none = viewModel.state.value

        // THEN the first click selects all and the second clears
        assertSoftly {
            all.selectedIds shouldBe setOf("a", "b")
            all.selectAllState shouldBe ToggleableState.On
            none.selectedIds shouldBe emptySet()
            none.selectAllState shouldBe ToggleableState.Off
        }
    }

    @Test
    fun `onSelectAllClicked - should select the rest when only some rows are selected`() {
        // GIVEN one of two rows selected
        viewModel.start()
        stored.value = content("a", "b")
        viewModel.onRowSelectionChanged(rowId = "a", selected = true)

        // WHEN select all is clicked
        viewModel.onSelectAllClicked()

        // THEN every row is selected
        viewModel.state.value.selectedIds shouldBe setOf("a", "b")
    }

    @Test
    fun `selection - should drop rows that vanish and keep the others across updates and refreshes`() {
        // GIVEN both rows selected
        viewModel.start()
        stored.value = content("a", "b")
        viewModel.onSelectAllClicked()

        // WHEN a new state drops row b, then the times refresh
        stored.value = content("a", "c")
        val afterUpdate = viewModel.state.value.selectedIds
        viewModel.refreshTimes()
        val afterRefresh = viewModel.state.value.selectedIds

        // THEN only row a stays selected
        assertSoftly {
            afterUpdate shouldBe setOf("a")
            afterRefresh shouldBe setOf("a")
        }
    }

    @Test
    fun `click handlers - should delegate to the matching callback once`() {
        // GIVEN one row
        viewModel.start()
        stored.value = content("a")
        val row = viewModel.state.value.rows.single()

        // WHEN each link is clicked
        viewModel.onTitleClicked(row)
        viewModel.onPullRequestNumberClicked(row)
        viewModel.onRepositoryClicked(row)

        // THEN each callback ran once for the row
        assertSoftly {
            reviewed shouldBe listOf("a")
            openedOnWeb shouldBe listOf("a")
            openedRepositories shouldBe listOf("a")
        }
    }

    @Test
    fun `start - should show persisted notifications before any snapshot arrived`() {
        // GIVEN persisted notifications and no sync time
        viewModel.start()

        // WHEN the store publishes them
        stored.value = content("old", lastSyncedAt = null)

        // THEN the rows show and the last updated text is empty
        assertSoftly {
            viewModel.state.value.hasData shouldBe true
            viewModel.state.value.rows.map { row -> row.id } shouldBe listOf("old")
            viewModel.state.value.lastUpdatedText shouldBe ""
        }
    }

    @Test
    fun `start - should update the rows when another IDE changes the stored notifications`() {
        // GIVEN a shown unread row
        viewModel.start()
        stored.value = content("a")

        // WHEN the store publishes the same notification as read
        stored.value = content("a", unread = false)

        // THEN the row follows
        viewModel.state.value.rows.single().isUnread shouldBe false
    }

    @Test
    fun `onMarkReadClicked - should persist and show the selected rows as read`() {
        // GIVEN two unread rows with one selected
        viewModel.start()
        stored.value = content("a", "b")
        viewModel.onRowSelectionChanged(rowId = "a", selected = true)

        // WHEN
        viewModel.onMarkReadClicked()

        // THEN only the selected row changed, the change was saved and the selection stays
        assertSoftly {
            unreadChanges shouldBe listOf(setOf("a") to false)
            viewModel.state.value.rows.associate { row -> row.id to row.isUnread } shouldBe mapOf("a" to false, "b" to true)
            viewModel.state.value.selectedIds shouldBe setOf("a")
        }
    }

    @Test
    fun `onMarkUnreadClicked - should persist and show the selected rows as unread`() {
        // GIVEN a read row that is selected
        viewModel.start()
        stored.value = content("a", unread = false)
        viewModel.onSelectAllClicked()

        // WHEN
        viewModel.onMarkUnreadClicked()

        // THEN
        assertSoftly {
            unreadChanges shouldBe listOf(setOf("a") to true)
            viewModel.state.value.rows.single().isUnread shouldBe true
        }
    }

    @Test
    fun `onMarkReadClicked - should do nothing without a selection`() {
        // GIVEN rows and no selection
        viewModel.start()
        stored.value = content("a")

        // WHEN
        viewModel.onMarkReadClicked()

        // THEN
        unreadChanges shouldBe emptyList()
    }

    @Test
    fun `onSetupHelpClicked - should open the setup help`() {
        // GIVEN / WHEN
        viewModel.onSetupHelpClicked()

        // THEN
        helpOpened shouldBe 1
    }
}