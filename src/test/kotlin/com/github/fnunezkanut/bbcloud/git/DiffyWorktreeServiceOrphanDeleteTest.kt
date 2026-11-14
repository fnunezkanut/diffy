package com.github.fnunezkanut.bbcloud.git

import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeRecentProjects
import com.github.fnunezkanut.bbcloud.project.RecentProjectsGateway
import com.intellij.ide.ProjectGroup
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("unit")
class DiffyWorktreeServiceOrphanDeleteTest {
    @TempDir
    lateinit var root: Path

    private class NoGit : GitCommandRunner {
        override fun run(project: Project?, workingDir: Path, command: GitCommand, parameters: List<String>, remoteUrl: String?): GitCommandResult {
            throw IllegalStateException("git must not run when deleting an orphan folder")
        }
    }

    private class RecordingGateway : RecentProjectsGateway {
        val removedPaths = mutableListOf<String>()

        override fun findGroup(name: String): ProjectGroup? {
            return null
        }

        override fun addGroup(group: ProjectGroup) = Unit

        override fun removeGroup(group: ProjectGroup) = Unit

        override fun moveProjectToGroup(path: String, group: ProjectGroup) = Unit

        override fun removePath(path: String) {
            removedPaths.add(path)
        }
    }

    private val gateway = RecordingGateway()
    private val refreshed = mutableListOf<Path>()

    private fun service(): DiffyWorktreeService {
        return DiffyWorktreeService.forTesting(
            gitRunner = NoGit(),
            openProjectLocator = OpenProjectLocator { null },
            recentProjects = PullRequestWorktreeRecentProjects(gateway = gateway),
            refreshVfs = { path -> refreshed.add(path) }
        )
    }

    private fun delete(path: Path, directory: Path = root): WorktreeRemovalResult {
        return runBlocking { service().deleteOrphanWorktreeFolder(worktreePath = path, workTreeDirectory = directory) }
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should delete a safe folder`() {
        // GIVEN
        val folder = Files.createDirectories(root.resolve("repo--pr-3/sub"))
        Files.writeString(folder.resolve("a.txt"), "x")

        // WHEN
        val result = delete(path = root.resolve("repo--pr-3"))

        // THEN
        assertSoftly {
            result shouldBe WorktreeRemovalResult.Success
            Files.exists(root.resolve("repo--pr-3")) shouldBe false
            gateway.removedPaths.size shouldBe 1
            refreshed.size shouldBe 1
        }
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should refuse a folder outside the work tree root`() {
        // GIVEN
        val outside = Files.createTempDirectory("outside")
        val folder = Files.createDirectories(outside.resolve("repo--pr-3"))

        // WHEN
        val result = delete(path = folder)

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<WorktreeRemovalResult.Failed>()
            Files.exists(folder) shouldBe true
        }
        folder.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should refuse a name that is not slug--pr-n`() {
        // GIVEN
        val folder = Files.createDirectories(root.resolve("notes"))

        // WHEN
        val result = delete(path = folder)

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<WorktreeRemovalResult.Failed>()
            Files.exists(folder) shouldBe true
        }
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should delete a symbolic link but leave its target`() {
        // GIVEN
        val target = Files.createTempDirectory("link-target")
        Files.writeString(target.resolve("keep.txt"), "keep")
        val link = root.resolve("repo--pr-4")
        Files.createSymbolicLink(link, target)

        // WHEN
        val result = delete(path = link)

        // THEN
        assertSoftly {
            result shouldBe WorktreeRemovalResult.Success
            Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(target.resolve("keep.txt")) shouldBe true
        }
        target.toFile().deleteRecursively()
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should delete a folder with a git directory`() {
        // GIVEN
        val folder = Files.createDirectories(root.resolve("repo--pr-5/.git/objects"))

        // WHEN
        val result = delete(path = root.resolve("repo--pr-5"))

        // THEN
        assertSoftly {
            result shouldBe WorktreeRemovalResult.Success
            Files.exists(folder) shouldBe false
        }
    }

    @Test
    fun `deleteOrphanWorktreeFolder - should succeed when the folder is already gone`() {
        // GIVEN / WHEN
        val result = delete(path = root.resolve("repo--pr-6"))

        // THEN
        result shouldBe WorktreeRemovalResult.Success
    }
}