package com.github.fnunezkanut.bbcloud.git

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("unit")
class DiffyProjectCloneServiceTest {
    private var runnerCalls = 0

    private fun service(runner: GitCloneRunner): DiffyProjectCloneService {
        return DiffyProjectCloneService(cloneRunner = runner, runWithIndicator = { block -> block(null) })
    }

    @Test
    fun `cloneProject - an existing target should return TargetExists without calling the runner`(
        @TempDir directory: Path
    ): Unit =
        runBlocking {
            // GIVEN
            Files.createDirectory(directory.resolve("repo-a"))
            val service = service { _, _, _, _ ->
                runnerCalls++
                GitCommandResult(success = true, combinedOutput = "")
            }

            // WHEN
            val result = service.cloneProject(projectsDirectory = directory, repoSlug = "repo-a", cloneUrl = "git@bitbucket.org:acme/repo-a")

            // THEN
            assertSoftly {
                result.shouldBeInstanceOf<ProjectCloneResult.TargetExists>()
                runnerCalls shouldBe 0
            }
        }

    @Test
    fun `cloneProject - an invalid slug should return InvalidTarget`(
        @TempDir directory: Path
    ): Unit =
        runBlocking {
            // GIVEN
            val service = service { _, _, _, _ -> GitCommandResult(success = true, combinedOutput = "") }

            // WHEN
            val result = service.cloneProject(projectsDirectory = directory, repoSlug = "../x", cloneUrl = "git@bitbucket.org:acme/x")

            // THEN
            result shouldBe ProjectCloneResult.InvalidTarget
        }

    @Test
    fun `cloneProject - a failing clone should delete the partial folder`(
        @TempDir directory: Path
    ): Unit =
        runBlocking {
            // GIVEN
            val service = service { parent, name, _, _ ->
                Files.createDirectories(parent.resolve(name).resolve("partial"))
                GitCommandResult(success = false, combinedOutput = "Permission denied (publickey)")
            }

            // WHEN
            val result = service.cloneProject(projectsDirectory = directory, repoSlug = "repo-a", cloneUrl = "git@bitbucket.org:acme/repo-a")

            // THEN
            assertSoftly {
                result.shouldBeInstanceOf<ProjectCloneResult.Failed>().output shouldBe "Permission denied (publickey)"
                Files.exists(directory.resolve("repo-a")).shouldBeFalse()
            }
        }

    @Test
    fun `cloneProject - a successful clone should report the checked out branch`(
        @TempDir directory: Path
    ): Unit =
        runBlocking {
            // GIVEN
            val service = service { parent, name, _, _ ->
                val gitDirectory = parent.resolve(name).resolve(".git")
                Files.createDirectories(gitDirectory)
                Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/main\n")
                GitCommandResult(success = true, combinedOutput = "")
            }

            // WHEN
            val result = service.cloneProject(projectsDirectory = directory, repoSlug = "repo-a", cloneUrl = "git@bitbucket.org:acme/repo-a")

            // THEN
            assertSoftly {
                result.shouldBeInstanceOf<ProjectCloneResult.Success>().branch shouldBe "main"
                result.shouldBeInstanceOf<ProjectCloneResult.Success>().checkoutPath shouldBe directory.toAbsolutePath().normalize().resolve("repo-a")
            }
        }
}