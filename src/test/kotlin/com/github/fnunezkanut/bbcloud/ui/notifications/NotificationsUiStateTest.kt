package com.github.fnunezkanut.bbcloud.ui.notifications

import androidx.compose.ui.state.ToggleableState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserEventCategory
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserNotificationEvent
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserPullRequest
import com.github.fnunezkanut.bbcloud.external.DiffyStoredNotification
import com.github.fnunezkanut.bbcloud.external.browserEvent
import com.github.fnunezkanut.bbcloud.external.browserPullRequest
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@Tag("unit")
class NotificationsUiStateTest {
    private val labels = NotificationLabels(
        typeLabel = { category -> category.wireName },
        botSuffix = " (bot)",
        timeText = { instant, zone -> formatTime(instant = instant, timeZone = zone) },
        lastUpdatedText = { instant, zone -> formatTime(instant = instant, timeZone = zone) }
    )

    private fun rowsOf(
        vararg events: DiffyBrowserNotificationEvent,
        timeZone: TimeZone = TimeZone.UTC,
        pullRequests: List<DiffyBrowserPullRequest> = emptyList(),
        context: NotificationRowContext = NotificationRowContext()
    ): List<NotificationRow> {
        return buildNotificationRows(
            notifications = events.map { event -> DiffyStoredNotification(event = event) },
            pullRequests = pullRequests,
            timeZone = timeZone,
            labels = labels,
            context = context
        )
    }

    @Nested
    inner class Ordering {
        @Test
        fun `buildNotificationRows - should put the newest event first whatever the input order`() {
            // GIVEN events in a random order
            val events = arrayOf(
                browserEvent(id = "b", occurredAt = "2026-11-06T10:00:00Z"),
                browserEvent(id = "c", occurredAt = "2026-11-07T10:00:00Z"),
                browserEvent(id = "a", occurredAt = "2026-11-05T10:00:00Z")
            )

            // WHEN
            val rows = rowsOf(*events)

            // THEN
            rows.map { row -> row.id } shouldBe listOf("c", "b", "a")
        }

        @Test
        fun `buildNotificationRows - should keep the original order for equal timestamps`() {
            // GIVEN three events at the same time
            val events = arrayOf(
                browserEvent(id = "first", occurredAt = "2026-11-06T10:00:00Z"),
                browserEvent(id = "second", occurredAt = "2026-11-06T10:00:00Z"),
                browserEvent(id = "third", occurredAt = "2026-11-06T10:00:00Z")
            )

            // WHEN
            val rows = rowsOf(*events)

            // THEN
            rows.map { row -> row.id } shouldBe listOf("first", "second", "third")
        }

        @Test
        fun `buildNotificationRows - should keep an event with an unparseable timestamp and sort it last`() {
            // GIVEN one event whose timestamp is not ISO
            val events = arrayOf(
                browserEvent(id = "bad", occurredAt = "yesterday-ish"),
                browserEvent(id = "good", occurredAt = "2026-11-06T10:00:00Z")
            )

            // WHEN
            val rows = rowsOf(*events)

            // THEN
            assertSoftly {
                rows.map { row -> row.id } shouldBe listOf("good", "bad")
                rows.last().timeText shouldBe "yesterday-ish"
                rows.last().occurredAt shouldBe null
            }
        }

        @Test
        fun `buildNotificationRows - should collapse a repeated event id to the first one`() {
            // GIVEN the same id twice
            val events = arrayOf(
                browserEvent(id = "dup", occurredAt = "2026-11-06T10:00:00Z", message = "first"),
                browserEvent(id = "dup", occurredAt = "2026-11-07T10:00:00Z", message = "second")
            )

            // WHEN
            val rows = rowsOf(*events)

            // THEN
            assertSoftly {
                rows.size shouldBe 1
                rows.single().eventTitle shouldBe "first"
            }
        }
    }

    @Nested
    inner class PullRequestLinks {
        private val configured = NotificationRowContext(configuredWorkspace = "acme")

        @Test
        fun `pullRequestUrlWorkspace - should read the workspace of the pull request link and ignore a diff suffix`() {
            // GIVEN events with a pull request link and with a comment link
            val fromPullRequestUrl = browserEvent(pullRequestUrl = "https://bitbucket.org/acme/my-repo/pull-requests/772")
            val fromEventUrl = browserEvent(url = "https://bitbucket.org/acme/my-repo/pull-requests/772/diff#comment-1")

            // WHEN / THEN both name the workspace
            assertSoftly {
                pullRequestUrlWorkspace(fromPullRequestUrl) shouldBe "acme"
                pullRequestUrlWorkspace(fromEventUrl) shouldBe "acme"
            }
        }

        @Test
        fun `pullRequestUrlWorkspace - should reject other schemes, hosts, repositories and ids`() {
            // GIVEN links that do not agree with the event or are not Bitbucket Cloud https links
            val http = browserEvent(url = "http://bitbucket.org/acme/my-repo/pull-requests/772")
            val otherHost = browserEvent(url = "https://evil.example/acme/my-repo/pull-requests/772")
            val otherRepo = browserEvent(url = "https://bitbucket.org/acme/other/pull-requests/772")
            val otherId = browserEvent(url = "https://bitbucket.org/acme/my-repo/pull-requests/1")
            val none = browserEvent(url = null)

            // WHEN / THEN none yields a workspace
            assertSoftly {
                pullRequestUrlWorkspace(http) shouldBe null
                pullRequestUrlWorkspace(otherHost) shouldBe null
                pullRequestUrlWorkspace(otherRepo) shouldBe null
                pullRequestUrlWorkspace(otherId) shouldBe null
                pullRequestUrlWorkspace(none) shouldBe null
            }
        }

        @Test
        fun `buildNotificationRows - should build the pull request page from the configured workspace when the event has no link`() {
            // GIVEN an event without links and a configured workspace
            val event = browserEvent(url = null)

            // WHEN
            val row = rowsOf(event, context = configured).single()

            // THEN
            assertSoftly {
                row.workspace shouldBe "acme"
                row.pullRequestWebUrl shouldBe "https://bitbucket.org/acme/my-repo/pull-requests/772"
                row.isForeignWorkspace shouldBe false
            }
        }

        @Test
        fun `buildNotificationRows - should mark an event of another workspace as foreign and keep its own page`() {
            // GIVEN an event whose link names another workspace than the configured one
            val event = browserEvent(url = "https://bitbucket.org/other-ws/my-repo/pull-requests/772/diff")

            // WHEN
            val row = rowsOf(event, context = configured).single()

            // THEN
            assertSoftly {
                row.isForeignWorkspace shouldBe true
                row.urlWorkspace shouldBe "other-ws"
                row.pullRequestWebUrl shouldBe "https://bitbucket.org/other-ws/my-repo/pull-requests/772"
            }
        }

        @Test
        fun `buildNotificationRows - should have no page and no foreign flag without any workspace`() {
            // GIVEN an event without links and no configured workspace
            val event = browserEvent(url = null)

            // WHEN
            val row = rowsOf(event).single()

            // THEN
            assertSoftly {
                row.workspace shouldBe null
                row.pullRequestWebUrl shouldBe null
                row.isForeignWorkspace shouldBe false
            }
        }

        @Test
        fun `buildNotificationRows - should take the known state from the snapshot pull request`() {
            // GIVEN a merged, a declined, an open, and an unlisted pull request
            val events = arrayOf(
                browserEvent(id = "m", pullRequestId = 1L),
                browserEvent(id = "d", pullRequestId = 2L),
                browserEvent(id = "o", pullRequestId = 3L),
                browserEvent(id = "u", pullRequestId = 4L)
            )
            val pullRequests = listOf(
                browserPullRequest(id = 1L, workspace = "acme", repoSlug = "my-repo", state = "MERGED"),
                browserPullRequest(id = 2L, workspace = "acme", repoSlug = "my-repo", state = "declined"),
                browserPullRequest(id = 3L, workspace = "acme", repoSlug = "my-repo", state = "OPEN")
            )

            // WHEN
            val rows = rowsOf(*events, pullRequests = pullRequests, context = configured).associateBy { row -> row.id }

            // THEN
            assertSoftly {
                rows.getValue("m").knownPullRequestState shouldBe PullRequestState.Merged
                rows.getValue("d").knownPullRequestState shouldBe PullRequestState.Declined
                rows.getValue("o").knownPullRequestState shouldBe PullRequestState.Open
                rows.getValue("u").knownPullRequestState shouldBe null
            }
        }

        @Test
        fun `buildNotificationRows - should not take the state of a pull request in another workspace`() {
            // GIVEN a merged pull request with the same number in another workspace
            val pullRequests = listOf(browserPullRequest(id = 772L, workspace = "someone-else", repoSlug = "my-repo", state = "MERGED"))

            // WHEN
            val row = rowsOf(browserEvent(url = null), pullRequests = pullRequests, context = configured).single()

            // THEN
            row.knownPullRequestState shouldBe null
        }
    }

    @Nested
    inner class Presentation {
        @Test
        fun `buildNotificationRows - should use the message as title and fall back to the type label`() {
            // GIVEN one event with a message and one with a blank message
            val withMessage = browserEvent(id = "m", message = "John approved your pull request")
            val blank = browserEvent(id = "b", message = " ", category = DiffyBrowserEventCategory.APPROVAL)

            // WHEN
            val rows = rowsOf(withMessage, blank).associateBy { row -> row.id }

            // THEN
            assertSoftly {
                rows.getValue("m").eventTitle shouldBe "John approved your pull request"
                rows.getValue("b").eventTitle shouldBe "approval"
            }
        }

        @Test
        fun `buildNotificationRows - should ask the context for the actor avatar with the effective workspace`() {
            // GIVEN a context that records the workspace it is asked about
            val asked = mutableListOf<String?>()
            val context = NotificationRowContext(
                configuredWorkspace = "acme",
                actorAvatarUrlFor = { _, workspace ->
                    asked.add(workspace)
                    "https://avatar.example/a.png"
                }
            )

            // WHEN one event has a link in another workspace and one has none
            val rows = rowsOf(
                browserEvent(id = "link", url = "https://bitbucket.org/other-ws/my-repo/pull-requests/772"),
                browserEvent(id = "none", url = null),
                context = context
            )

            // THEN the link wins over the configured workspace and the URL lands on the rows
            assertSoftly {
                asked.toSet() shouldBe setOf("other-ws", "acme")
                rows.map { row -> row.actorAvatarUrl }.toSet() shouldBe setOf("https://avatar.example/a.png")
            }
        }
    }

    @Nested
    inner class Selection {
        private val rows = rowsOf(browserEvent(id = "a"), browserEvent(id = "b"))

        @Test
        fun `deriveSelectAllState - should be off, indeterminate or on by the selected rows`() {
            // GIVEN two rows
            // WHEN / THEN
            assertSoftly {
                deriveSelectAllState(rows = rows, selectedIds = emptySet()) shouldBe ToggleableState.Off
                deriveSelectAllState(rows = rows, selectedIds = setOf("a")) shouldBe ToggleableState.Indeterminate
                deriveSelectAllState(rows = rows, selectedIds = setOf("a", "b")) shouldBe ToggleableState.On
                deriveSelectAllState(rows = emptyList(), selectedIds = setOf("a")) shouldBe ToggleableState.Off
            }
        }
    }

    @Nested
    inner class Formatting {
        @Test
        fun `buildNotificationRows - should format the time in the supplied zone including a date rollover`() {
            // GIVEN an event shortly before midnight UTC
            val event = browserEvent(occurredAt = "2026-11-06T23:30:00Z")

            // WHEN the same event is rendered in two zones
            val utc = rowsOf(event, timeZone = TimeZone.UTC).single()
            val tokyo = rowsOf(event, timeZone = TimeZone.of("Asia/Tokyo")).single()

            // THEN Tokyo is nine hours ahead, on the next day
            assertSoftly {
                utc.timeText shouldBe "2026-11-06 23:30"
                tokyo.timeText shouldBe "2026-11-07 08:30"
                utc.occurredAt shouldBe Instant.parse("2026-11-06T23:30:00Z")
            }
        }

        @Test
        fun `buildNotificationRows - should mark bots and build the pull request text`() {
            // GIVEN a bot event and an event without a repository
            val bot = browserEvent(id = "bot", actorDisplayName = "Reviewer Bot", actorIsBot = true)
            val noRepo = browserEvent(id = "none", pullRequestRepoSlug = null)

            // WHEN
            val rows = rowsOf(bot, noRepo).associateBy { row -> row.id }

            // THEN
            assertSoftly {
                rows.getValue("bot").actorText shouldBe "Reviewer Bot (bot)"
                rows.getValue("bot").pullRequestNumberText shouldBe "#772"
                rows.getValue("bot").repoSlug shouldBe "my-repo"
                rows.getValue("none").repoSlug shouldBe null
            }
        }

        @Test
        fun `buildNotificationRows - should take the unread flag from the stored notification and ignore readState`() {
            // GIVEN a stored read notification whose event says unread, and an unread one whose event says read
            val read = DiffyStoredNotification(event = browserEvent(id = "r", readState = "unread", url = null), unread = false)
            val unread = DiffyStoredNotification(event = browserEvent(id = "u", readState = "read"))

            // WHEN
            val rows = buildNotificationRows(
                notifications = listOf(read, unread),
                pullRequests = emptyList(),
                timeZone = TimeZone.UTC,
                labels = labels
            ).associateBy { row -> row.id }

            // THEN the stored flag wins and the link is kept
            assertSoftly {
                rows.getValue("u").isUnread shouldBe true
                rows.getValue("r").isUnread shouldBe false
                rows.getValue("r").url shouldBe null
            }
        }

        @Test
        fun `buildNotificationRows - should fall back to the cached pull request state when no snapshot lists it`() {
            // GIVEN no snapshot pull requests and a cache that knows the pull request is merged
            val context = NotificationRowContext(
                configuredWorkspace = "acme",
                cachedPullRequestState = { workspace, repoSlug, pullRequestId ->
                    if (workspace == "acme" && repoSlug == "my-repo" && pullRequestId == 772L) PullRequestState.Merged else null
                }
            )

            // WHEN
            val row = rowsOf(browserEvent(url = null), context = context).single()

            // THEN
            row.knownPullRequestState shouldBe PullRequestState.Merged
        }

        @Test
        fun `buildNotificationRows - should prefer the snapshot state over the cached one`() {
            // GIVEN a snapshot that lists the pull request as open while the cache says merged
            val context = NotificationRowContext(
                configuredWorkspace = "acme",
                cachedPullRequestState = { _, _, _ -> PullRequestState.Merged }
            )
            val pullRequests = listOf(browserPullRequest(id = 772L, workspace = "acme", repoSlug = "my-repo", state = "OPEN"))

            // WHEN
            val row = rowsOf(browserEvent(url = null), pullRequests = pullRequests, context = context).single()

            // THEN
            row.knownPullRequestState shouldBe PullRequestState.Open
        }
    }

    @Test
    fun `categoryBundleKey - should cover every category`() {
        // GIVEN every category
        // WHEN the keys are resolved
        val keys = DiffyBrowserEventCategory.entries.map { category -> categoryBundleKey(category = category) }

        // THEN each one is distinct and namespaced
        assertSoftly {
            keys.toSet().size shouldBe DiffyBrowserEventCategory.entries.size
            keys.all { key -> key.startsWith("notifications.category.") } shouldBe true
        }
    }
}