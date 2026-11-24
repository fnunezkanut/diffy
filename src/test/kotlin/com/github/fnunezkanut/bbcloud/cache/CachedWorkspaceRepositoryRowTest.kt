package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CachedWorkspaceRepositoryRowTest {
    private val repository = BitbucketWorkspaceRepository(
        slug = "repo-a",
        name = "Repo A",
        mainBranch = "main",
        httpsCloneUrl = "https://bitbucket.org/acme/repo-a.git",
        sshCloneUrl = "git@bitbucket.org:acme/repo-a.git",
        avatarUrl = null
    )

    @Test
    fun `decode - should round trip an encoded repository`() {
        // WHEN
        val decoded = CachedWorkspaceRepositoryRow.decode(row = CachedWorkspaceRepositoryRow.encode(repository = repository))

        // THEN
        decoded shouldBe repository
    }

    @Test
    fun `decode - should return null for an unknown version, malformed JSON, and an invalid slug`() {
        io.kotest.assertions.assertSoftly {
            CachedWorkspaceRepositoryRow.decode(row = """{"v":2,"slug":"repo-a","name":"A"}""") shouldBe null
            CachedWorkspaceRepositoryRow.decode(row = "not-json") shouldBe null
            CachedWorkspaceRepositoryRow.decode(row = """{"v":1,"slug":"bad slug","name":"A"}""") shouldBe null
        }
    }
}