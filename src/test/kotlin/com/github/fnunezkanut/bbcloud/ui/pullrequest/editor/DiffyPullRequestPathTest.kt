package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.openapi.project.Project
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyPullRequestPathTest {
    @Test
    fun `path serializer - should round trip every identity field`() {
        // GIVEN
        val path = DiffyPullRequestPath(
            sessionId = "session-1",
            projectHash = "project-1",
            workspaceUrl = "https://bitbucket.org/acme",
            repositorySlug = "sample-repository",
            pullRequestId = 42L
        )

        // WHEN
        val serialized = DiffyPullRequestPathSerializer.serialize(path)
        val restored = DiffyPullRequestPathSerializer.deserialize(serialized)

        // THEN
        restored shouldBe path
    }

    @Test
    fun `virtual file system - should reject a path belonging to another project`() {
        // GIVEN
        val project = mockProject(locationHash = "project-1")
        val path = DiffyPullRequestPath(
            sessionId = "session-1",
            projectHash = "project-2",
            workspaceUrl = "https://bitbucket.org/acme",
            repositorySlug = "sample-repository",
            pullRequestId = 42L
        )
        val fileSystem = DiffyPullRequestVirtualFileSystem()

        // WHEN
        val file = fileSystem.findOrCreateFile(project = project, path = path)

        // THEN
        file shouldBe null
    }

    @Test
    fun `path serializer - should normalize the workspace URL`() {
        // GIVEN
        val path = DiffyPullRequestPath(
            sessionId = "session-1",
            projectHash = "project-1",
            workspaceUrl = " https://bitbucket.org/acme/// ",
            repositorySlug = "sample-repository",
            pullRequestId = 42L
        )

        // WHEN
        val restored = DiffyPullRequestPathSerializer.deserialize(
            DiffyPullRequestPathSerializer.serialize(path)
        )

        // THEN
        restored.workspaceUrl shouldBe "https://bitbucket.org/acme"
        restored.repositorySlug shouldBe "sample-repository"
        restored.pullRequestId shouldBe 42L
        restored.sessionId shouldNotBe null
    }

    private fun mockProject(locationHash: String): Project {
        return mockk<Project> {
            every { this@mockk.locationHash } returns locationHash
            every { this@mockk.name } returns "Test Project"
        }
    }
}