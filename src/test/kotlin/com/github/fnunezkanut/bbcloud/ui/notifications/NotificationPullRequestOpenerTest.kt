package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.external.DiffyStoredNotification
import com.github.fnunezkanut.bbcloud.external.browserEvent
import com.github.fnunezkanut.bbcloud.external.browserPullRequest
import com.github.fnunezkanut.bbcloud.project.PullRequestOpenCheckResult
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class NotificationPullRequestOpenerTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val checks = mutableListOf<String>()
    private val reviews = mutableListOf<String>()
    private val browsed = mutableListOf<String>()
    private val localOpens = mutableListOf<String>()
    private var checkResult: PullRequestOpenCheckResult? = null
    private var localPath = ""
    private var localOpened = true

    private val opener = NotificationPullRequestOpener(
        coroutineScope = scope,
        checkOpen = { workspace, repoSlug, prNumber ->
            checks.add("$workspace/$repoSlug#$prNumber")
            checkResult
        },
        startReview = { workspace, repoSlug, prNumber -> reviews.add("$workspace/$repoSlug#$prNumber") },
        localCheckoutPathFor = { localPath },
        openLocalProject = { repoSlug, path ->
            localOpens.add("$repoSlug@$path")
            localOpened
        },
        browse = { url -> browsed.add(url) },
        runOnEdt = { block -> block() }
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun rowOf(
        url: String? = null,
        pullRequestState: String? = null,
        configuredWorkspace: String? = "acme",
        pullRequestId: Long? = 772L,
        repoSlug: String? = "my-repo"
    ): NotificationRow {
        val pullRequests = pullRequestState?.let { state ->
            listOf(browserPullRequest(id = 772L, workspace = "acme", repoSlug = "my-repo", state = state))
        }.orEmpty()
        return buildNotificationRows(
            notifications = listOf(
                DiffyStoredNotification(event = browserEvent(url = url, pullRequestId = pullRequestId, pullRequestRepoSlug = repoSlug))
            ),
            pullRequests = pullRequests,
            timeZone = TimeZone.UTC,
            labels = NotificationLabels(
                typeLabel = { category -> category.wireName },
                botSuffix = "",
                timeText = { _, _ -> "" },
                lastUpdatedText = { _, _ -> "" }
            ),
            context = NotificationRowContext(configuredWorkspace = configuredWorkspace)
        ).single()
    }

    @Nested
    inner class Title {
        @Test
        fun `openForReview - should open a merged or declined pull request on the web without any check`() {
            // GIVEN the snapshot says the pull request is merged
            val row = rowOf(pullRequestState = "MERGED")

            // WHEN
            opener.openForReview(row)

            // THEN
            assertSoftly {
                browsed shouldBe listOf("https://bitbucket.org/acme/my-repo/pull-requests/772")
                checks shouldBe emptyList()
                reviews shouldBe emptyList()
            }
        }

        @Test
        fun `openForReview - should check and then review a pull request the snapshot says is open`() {
            // GIVEN an open pull request that is still open on Bitbucket
            checkResult = PullRequestOpenCheckResult.Failed()
            val row = rowOf(pullRequestState = "OPEN")

            // WHEN
            opener.openForReview(row)

            // THEN the review flow starts
            assertSoftly {
                checks shouldBe listOf("acme/my-repo#772")
                reviews shouldBe listOf("acme/my-repo#772")
                browsed shouldBe emptyList()
            }
        }

        @Test
        fun `openForReview - should open on the web when the live check says not open or not found`() {
            // GIVEN a pull request the snapshot does not list, closed on Bitbucket
            val closed = rowOf()

            // WHEN both outcomes are clicked
            checkResult = PullRequestOpenCheckResult.NotOpen(state = PullRequestState.Declined)
            opener.openForReview(closed)
            checkResult = PullRequestOpenCheckResult.NotFound
            opener.openForReview(closed)

            // THEN both open the web page and never review
            assertSoftly {
                browsed.size shouldBe 2
                reviews shouldBe emptyList()
            }
        }

        @Test
        fun `openForReview - should review when the state could not be verified`() {
            // GIVEN no organization URL, so the check returns null
            checkResult = null
            val row = rowOf()

            // WHEN
            opener.openForReview(row)

            // THEN the review flow reports its own problems
            reviews shouldBe listOf("acme/my-repo#772")
        }

        @Test
        fun `openForReview - should open a pull request of another workspace on the web`() {
            // GIVEN an event that links to another workspace
            val row = rowOf(url = "https://bitbucket.org/other-ws/my-repo/pull-requests/772")

            // WHEN
            opener.openForReview(row)

            // THEN
            assertSoftly {
                browsed shouldBe listOf("https://bitbucket.org/other-ws/my-repo/pull-requests/772")
                checks shouldBe emptyList()
                reviews shouldBe emptyList()
            }
        }

        @Test
        fun `openForReview - should open the event page on the web without a configured workspace`() {
            // GIVEN an event with a link and no configured workspace
            val row = rowOf(url = "https://bitbucket.org/acme/my-repo/pull-requests/772/diff#comment-1", configuredWorkspace = null)

            // WHEN
            opener.openForReview(row)

            // THEN
            assertSoftly {
                browsed shouldBe listOf("https://bitbucket.org/acme/my-repo/pull-requests/772")
                reviews shouldBe emptyList()
            }
        }

        @Test
        fun `openForReview - should do nothing when nothing can be opened`() {
            // GIVEN no workspace and no link
            val row = rowOf(configuredWorkspace = null)

            // WHEN
            opener.openForReview(row)

            // THEN
            assertSoftly {
                browsed shouldBe emptyList()
                reviews shouldBe emptyList()
            }
        }
    }

    @Nested
    inner class Links {
        @Test
        fun `openOnWeb - should open the pull request page`() {
            // GIVEN / WHEN
            opener.openOnWeb(rowOf())

            // THEN
            browsed shouldBe listOf("https://bitbucket.org/acme/my-repo/pull-requests/772")
        }

        @Test
        fun `openRepository - should open the repository page when there is no local checkout`() {
            // GIVEN no local checkout
            localPath = ""

            // WHEN
            opener.openRepository(rowOf())

            // THEN
            assertSoftly {
                browsed shouldBe listOf("https://bitbucket.org/acme/my-repo")
                localOpens shouldBe emptyList()
            }
        }

        @Test
        fun `openRepository - should open the local project and stay off the web`() {
            // GIVEN a local checkout that opens
            localPath = "/work/my-repo"

            // WHEN
            opener.openRepository(rowOf())

            // THEN
            assertSoftly {
                localOpens shouldBe listOf("my-repo@${Path.of("/work/my-repo")}")
                browsed shouldBe emptyList()
            }
        }

        @Test
        fun `openRepository - should fall back to the repository page when the local project fails`() {
            // GIVEN a local checkout that cannot be opened
            localPath = "/work/my-repo"
            localOpened = false

            // WHEN
            opener.openRepository(rowOf())

            // THEN
            browsed shouldBe listOf("https://bitbucket.org/acme/my-repo")
        }

        @Test
        fun `openRepository - should use the workspace of the link and no local checkout for another workspace`() {
            // GIVEN a local checkout name that exists, and an event of another workspace
            localPath = "/work/my-repo"
            val row = rowOf(url = "https://bitbucket.org/other-ws/my-repo/pull-requests/772")

            // WHEN
            opener.openRepository(row)

            // THEN
            assertSoftly {
                browsed shouldBe listOf("https://bitbucket.org/other-ws/my-repo")
                localOpens shouldBe emptyList()
            }
        }
    }

    @Test
    fun `isBrowsableUrl - should accept only http and https`() {
        // GIVEN / WHEN / THEN
        assertSoftly {
            NotificationPullRequestOpener.isBrowsableUrl("https://bitbucket.org/a") shouldBe true
            NotificationPullRequestOpener.isBrowsableUrl("http://bitbucket.org/a") shouldBe true
            NotificationPullRequestOpener.isBrowsableUrl("file:///etc/passwd") shouldBe false
            NotificationPullRequestOpener.isBrowsableUrl("javascript:alert(1)") shouldBe false
            NotificationPullRequestOpener.isBrowsableUrl("not a url") shouldBe false
        }
    }
}