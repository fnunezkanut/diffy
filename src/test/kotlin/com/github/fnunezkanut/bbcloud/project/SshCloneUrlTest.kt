package com.github.fnunezkanut.bbcloud.project

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryRemoteUrl
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class SshCloneUrlTest {
    private fun repo(slug: String = "repo-a", ssh: String?): BitbucketWorkspaceRepository {
        return BitbucketWorkspaceRepository(slug = slug, name = slug, mainBranch = null, httpsCloneUrl = null, sshCloneUrl = ssh, avatarUrl = null)
    }

    @Test
    fun `sshCloneUrlFor - should return the SSH href when it matches the workspace and slug`() {
        // GIVEN
        val url = "git@bitbucket.org:acme/repo-a.git"

        // THEN
        sshCloneUrlFor(repository = repo(ssh = url), workspace = "acme") shouldBe url
    }

    @Test
    fun `sshCloneUrlFor - should fall back for another workspace, another slug, an HTTPS href, and a missing href`() {
        val fallback = "git@ssh.bitbucket.org:acme/repo-a"
        assertSoftly {
            sshCloneUrlFor(repository = repo(ssh = "git@bitbucket.org:other/repo-a.git"), workspace = "acme") shouldBe fallback
            sshCloneUrlFor(repository = repo(ssh = "git@bitbucket.org:acme/repo-b.git"), workspace = "acme") shouldBe fallback
            sshCloneUrlFor(repository = repo(ssh = "https://bitbucket.org/acme/repo-a.git"), workspace = "acme") shouldBe fallback
            sshCloneUrlFor(repository = repo(ssh = null), workspace = "acme") shouldBe fallback
        }
    }

    @Test
    fun `fallbackSshCloneUrl - should parse back to the workspace and slug`() {
        // WHEN
        val parsed = BitbucketRepositoryRemoteUrl.parse(fallbackSshCloneUrl(workspace = "acme", repoSlug = "repo-a"))

        // THEN
        assertSoftly {
            parsed?.workspace shouldBe "acme"
            parsed?.repoSlug shouldBe "repo-a"
        }
    }
}