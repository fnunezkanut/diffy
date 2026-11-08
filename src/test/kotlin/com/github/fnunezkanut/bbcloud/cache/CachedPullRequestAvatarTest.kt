package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CachedPullRequestAvatarTest {
    private val pullRequest = CachedPullRequest(
        key = PullRequestCacheKey.of(workspace = "acme", repoSlug = "repo", prNum = 1L),
        workspace = "acme",
        repoSlug = "repo",
        lastFetchedAt = CACHE_TEST_START,
        author = CachedPerson(name = "Anna Author", uuid = "{anna}", accountId = "712020:anna", avatarUrl = "https://avatars.example/anna.png"),
        title = "Title",
        state = PullRequestState.Open,
        isDraft = false,
        createdOn = CACHE_TEST_START,
        updatedOn = CACHE_TEST_START,
        closedOn = null,
        sourceBranch = "feature",
        destinationBranch = "main",
        commentCount = 0,
        webUrl = "https://bitbucket.org/acme/repo/pull-requests/1",
        reviewers = listOf(
            CachedReviewer(
                name = "Ben Reviewer",
                uuid = "{ben}",
                accountId = "712020:ben",
                avatarUrl = "https://avatars.example/ben.png",
                approved = false,
                needsWork = false,
                participatedOn = null
            ),
            CachedReviewer(
                name = "Cleo NoAvatar",
                uuid = "{cleo}",
                accountId = "712020:cleo",
                avatarUrl = null,
                approved = false,
                needsWork = false,
                participatedOn = null
            )
        ),
        approverUuids = emptySet()
    )

    @Test
    fun `avatarUrlOfPerson - should find the author and a reviewer by account id`() {
        // GIVEN / WHEN
        val author = pullRequest.avatarUrlOfPerson(accountId = "712020:anna", displayName = "someone else")
        val reviewer = pullRequest.avatarUrlOfPerson(accountId = "712020:ben", displayName = null)

        // THEN
        assertSoftly {
            author shouldBe "https://avatars.example/anna.png"
            reviewer shouldBe "https://avatars.example/ben.png"
        }
    }

    @Test
    fun `avatarUrlOfPerson - should fall back to the display name only without an account id`() {
        // GIVEN / WHEN
        val byName = pullRequest.avatarUrlOfPerson(accountId = null, displayName = "ben reviewer")
        val wrongAccount = pullRequest.avatarUrlOfPerson(accountId = "712020:nobody", displayName = "Ben Reviewer")

        // THEN the name is used only when there is no account id
        assertSoftly {
            byName shouldBe "https://avatars.example/ben.png"
            wrongAccount shouldBe null
        }
    }

    @Test
    fun `avatarUrlOfPerson - should return null for an unknown person or a person without an avatar`() {
        // GIVEN / WHEN
        val unknown = pullRequest.avatarUrlOfPerson(accountId = "712020:zed", displayName = "Zed")
        val noAvatar = pullRequest.avatarUrlOfPerson(accountId = "712020:cleo", displayName = "Cleo NoAvatar")
        val nothing = pullRequest.avatarUrlOfPerson(accountId = null, displayName = null)

        // THEN
        assertSoftly {
            unknown shouldBe null
            noAvatar shouldBe null
            nothing shouldBe null
        }
    }
}