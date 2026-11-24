package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@Tag("unit")
class DiffyWorkspaceRepositoryCacheTest {
    private val start = Instant.parse("2026-11-24T10:00:00Z")
    private var now = start
    private val state = DiffyWorkspaceRepositoryCacheState.State()
    private val cache = DiffyWorkspaceRepositoryCache(store = { state }, clock = { now })

    private fun repo(slug: String, branch: String = "main"): BitbucketWorkspaceRepository {
        return BitbucketWorkspaceRepository(slug = slug, name = slug, mainBranch = branch, httpsCloneUrl = null, sshCloneUrl = null, avatarUrl = null)
    }

    @Test
    fun `replace then read - should return the rows and the clock time`() {
        // WHEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a"), repo("b")), isTruncated = true)

        // THEN
        val snapshot = cache.read(workspace = "acme")
        assertSoftly {
            snapshot?.repositories?.map { repository -> repository.slug } shouldBe listOf("a", "b")
            snapshot?.isTruncated shouldBe true
            snapshot?.fetchedAt shouldBe start
        }
    }

    @Test
    fun `read - should return null for another workspace`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a")), isTruncated = false)

        // THEN
        cache.read(workspace = "other") shouldBe null
    }

    @Test
    fun `upsert - should replace an existing slug and keep fetchedAt`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a"), repo("b")), isTruncated = false)
        now = start + 5.hours

        // WHEN
        cache.upsert(workspace = "acme", repository = repo("a", branch = "develop"))

        // THEN
        val snapshot = cache.read(workspace = "acme")
        assertSoftly {
            snapshot?.repositories?.map { repository -> repository.slug to repository.mainBranch } shouldBe listOf("a" to "develop", "b" to "main")
            snapshot?.fetchedAt shouldBe start
        }
    }

    @Test
    fun `remove - should drop the slug`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a"), repo("b")), isTruncated = false)

        // WHEN
        cache.remove(workspace = "acme", repoSlug = "a")

        // THEN
        cache.read(workspace = "acme")?.repositories?.map { repository -> repository.slug } shouldBe listOf("b")
    }

    @Test
    fun `upsert and remove - should do nothing for another workspace`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a")), isTruncated = false)

        // WHEN
        cache.upsert(workspace = "other", repository = repo("x"))
        cache.remove(workspace = "other", repoSlug = "a")

        // THEN
        cache.read(workspace = "acme")?.repositories?.map { repository -> repository.slug } shouldBe listOf("a")
    }

    @Test
    fun `clear - should empty the snapshot`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a")), isTruncated = false)

        // WHEN
        cache.clear()

        // THEN
        cache.read(workspace = "acme") shouldBe null
    }

    @Test
    fun `read - should skip a corrupt row`() {
        // GIVEN
        cache.replace(workspace = "acme", repositories = listOf(repo("a")), isTruncated = false)
        state.repositories = (state.repositories + "garbage").toMutableList()

        // THEN
        cache.read(workspace = "acme")?.repositories?.map { repository -> repository.slug } shouldBe listOf("a")
    }
}