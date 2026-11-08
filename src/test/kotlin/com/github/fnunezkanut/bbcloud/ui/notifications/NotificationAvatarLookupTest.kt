package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.cache.CACHE_TEST_START
import com.github.fnunezkanut.bbcloud.cache.CachedPerson
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequest
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore
import com.github.fnunezkanut.bbcloud.external.browserEvent
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class NotificationAvatarLookupTest {
    private val key = PullRequestCacheKey.of(workspace = "acme", repoSlug = "my-repo", prNum = 772L)
    private val cached = CachedPullRequest(
        key = key,
        workspace = "acme",
        repoSlug = "my-repo",
        lastFetchedAt = CACHE_TEST_START,
        author = CachedPerson(name = "Filip", uuid = null, accountId = "712020:filip", avatarUrl = "https://avatars.example/filip.png"),
        title = "Title",
        state = PullRequestState.Open,
        isDraft = false,
        createdOn = CACHE_TEST_START,
        updatedOn = CACHE_TEST_START,
        closedOn = null,
        sourceBranch = "feature",
        destinationBranch = "main",
        commentCount = 0,
        webUrl = "https://bitbucket.org/acme/my-repo/pull-requests/772",
        reviewers = emptyList(),
        approverUuids = emptySet()
    )
    private val cache = mockk<PullRequestCacheStore>().also { store ->
        every { store.get(key) } returns cached
        every { store.get(match { other -> other != key }) } returns null
    }

    @Test
    fun `cachedActorAvatarUrl - should find the actor among the people of the cached pull request`() {
        // GIVEN an event of the cached pull request by its author
        val event = browserEvent(actorAccountId = "712020:filip")

        // WHEN
        val url = cachedActorAvatarUrl(cache = cache, event = event, workspace = "ACME")

        // THEN the lookup ignores the case of the workspace
        url shouldBe "https://avatars.example/filip.png"
    }

    @Test
    fun `cachedActorAvatarUrl - should be null without a workspace, a pull request, or a cached entry`() {
        // GIVEN events that cannot be looked up
        val unknownPullRequest = browserEvent(pullRequestId = 1L, actorAccountId = "712020:filip")
        val noPullRequest = browserEvent(pullRequestId = null, pullRequestRepoSlug = null, actorAccountId = "712020:filip")
        val known = browserEvent(actorAccountId = "712020:filip")

        // WHEN / THEN
        assertSoftly {
            cachedActorAvatarUrl(cache = cache, event = unknownPullRequest, workspace = "acme") shouldBe null
            cachedActorAvatarUrl(cache = cache, event = noPullRequest, workspace = "acme") shouldBe null
            cachedActorAvatarUrl(cache = cache, event = known, workspace = null) shouldBe null
            cachedActorAvatarUrl(cache = cache, event = known, workspace = " ") shouldBe null
        }
    }
}