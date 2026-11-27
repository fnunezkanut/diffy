package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Instant

/** Drives [RebaseBackupWriter] through a scripted [GitCommandRunner] that records every call. */
@Tag("unit")
class RebaseBackupWriterTest {
    private val workTree: Path = Path.of("/tmp/.worktrees/repo--pr-7")
    private val headSha = "a".repeat(40)
    private val stashSha = "b".repeat(40)
    private val zeros = "0".repeat(40)
    private val prefix = "refs/diffy/backups/pr-7/"
    private val headRef = "${prefix}1795694861000"
    private val wipRef = "$headRef-wip"
    private val headMessage = "diffy: backup before AI rebase of PR #7"
    private val wipMessage = "diffy: uncommitted changes before AI rebase of PR #7"

    private class RecordingGitRunner(private val script: (GitCommand, List<String>) -> GitCommandResult) : GitCommandRunner {
        val calls = mutableListOf<Pair<GitCommand, List<String>>>()

        override fun run(
            project: Project?,
            workingDir: Path,
            command: GitCommand,
            parameters: List<String>,
            remoteUrl: String?,
            background: Boolean
        ): GitCommandResult {
            calls.add(command to parameters)
            return script(command, parameters)
        }
    }

    private fun success(output: String = ""): GitCommandResult {
        return GitCommandResult(success = true, combinedOutput = output)
    }

    private fun failure(output: String): GitCommandResult {
        return GitCommandResult(success = false, combinedOutput = output)
    }

    private fun runner(
        revParse: GitCommandResult = success(output = "$ git rev-parse --verify HEAD\n$headSha"),
        headUpdate: GitCommandResult = success(),
        stash: GitCommandResult = success(),
        wipUpdate: GitCommandResult = success(),
        forEachRef: GitCommandResult = success(output = headRef),
        deletion: GitCommandResult = success()
    ): RecordingGitRunner {
        return RecordingGitRunner { command, parameters ->
            when {
                command == GitCommand.REV_PARSE -> revParse
                command == GitCommand.STASH -> stash
                command == GitCommand.FOR_EACH_REF -> forEachRef
                command == GitCommand.UPDATE_REF && parameters.firstOrNull() == "-d" -> deletion
                command == GitCommand.UPDATE_REF && parameters.getOrNull(2) == wipRef -> wipUpdate
                command == GitCommand.UPDATE_REF -> headUpdate
                else -> failure(output = "unexpected command $command $parameters")
            }
        }
    }

    private fun create(runner: RecordingGitRunner): RebaseBackupResult {
        val writer = RebaseBackupWriter(gitRunner = runner, clock = { Instant.fromEpochMilliseconds(1_795_694_861_000) })
        return writer.create(project = null, workTree = workTree, pullRequestId = 7)
    }

    private fun RecordingGitRunner.updateRefCalls(): List<List<String>> {
        return calls.filter { (command, _) -> command == GitCommand.UPDATE_REF }.map { (_, parameters) -> parameters }
    }

    private fun RecordingGitRunner.deletedRefs(): List<String> {
        return updateRefCalls().filter { parameters -> parameters.firstOrNull() == "-d" }.map { parameters -> parameters[1] }
    }

    @Test
    fun `create - should save only HEAD when the tracked tree is clean`() {
        // GIVEN
        val runner = runner()

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = null)
            runner.updateRefCalls() shouldContainExactly listOf(listOf("-m", headMessage, headRef, headSha, zeros))
        }
    }

    @Test
    fun `create - should also save the uncommitted tracked changes when stash create prints a commit`() {
        // GIVEN
        val runner = runner(stash = success(output = "$ git stash create\n$stashSha"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = wipRef)
            runner.updateRefCalls() shouldContainExactly listOf(
                listOf("-m", headMessage, headRef, headSha, zeros),
                listOf("-m", wipMessage, wipRef, stashSha, zeros)
            )
        }
    }

    @Test
    fun `create - should fail without writing a ref when rev-parse fails`() {
        // GIVEN
        val runner = runner(revParse = failure(output = "$ git rev-parse --verify HEAD\nfatal: Needed a single revision"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result shouldBe RebaseBackupResult.Failed(reason = "fatal: Needed a single revision")
            runner.updateRefCalls().shouldBeEmpty()
        }
    }

    @Test
    fun `create - should fail when rev-parse prints something that is not an object id`() {
        // GIVEN
        val runner = runner(revParse = success(output = "$ git rev-parse --verify HEAD\nHEAD"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<RebaseBackupResult.Failed>()
            runner.updateRefCalls().shouldBeEmpty()
        }
    }

    @Test
    fun `create - should fail without stashing when the head ref cannot be written`() {
        // GIVEN
        val runner = runner(headUpdate = failure(output = "fatal: update_ref failed: reference already exists"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result shouldBe RebaseBackupResult.Failed(reason = "fatal: update_ref failed: reference already exists")
            runner.calls.map { (command, _) -> command }.contains(GitCommand.STASH) shouldBe false
        }
    }

    @Test
    fun `create - should still succeed without a wip ref when stash create fails`() {
        // GIVEN
        val runner = runner(stash = failure(output = "fatal: you are in the middle of a merge"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = null)
    }

    @Test
    fun `create - should still succeed without a wip ref when the wip ref cannot be written`() {
        // GIVEN
        val runner = runner(stash = success(output = stashSha), wipUpdate = failure(output = "fatal: cannot lock ref"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = null)
    }

    @Test
    fun `create - should prune the oldest snapshots beyond five`() {
        // GIVEN
        val listed = (1L..6L).map { key -> "$prefix$key" } + "${prefix}1-wip" + headRef
        val runner = runner(forEachRef = success(output = "$ git for-each-ref\n" + listed.joinToString(separator = "\n")))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<RebaseBackupResult.Created>()
            runner.deletedRefs() shouldContainExactlyInAnyOrder listOf("${prefix}1", "${prefix}1-wip", "${prefix}2")
            runner.calls.first { (command, _) -> command == GitCommand.FOR_EACH_REF }.second shouldBe
                listOf("--format=%(refname)", prefix)
        }
    }

    @Test
    fun `create - should still succeed without pruning when for-each-ref fails`() {
        // GIVEN
        val runner = runner(forEachRef = failure(output = "fatal: bad object"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = null)
            runner.deletedRefs().shouldBeEmpty()
        }
    }

    @Test
    fun `create - should still succeed when a pruned ref cannot be deleted`() {
        // GIVEN
        val listed = (1L..6L).map { key -> "$prefix$key" } + headRef
        val runner = runner(forEachRef = success(output = listed.joinToString(separator = "\n")), deletion = failure(output = "error"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        result shouldBe RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = null)
    }

    @Test
    fun `create - should ignore the command line echo when reading the SHA`() {
        // GIVEN
        val runner = runner(revParse = success(output = "$ git rev-parse --verify HEAD\n$headSha"))

        // WHEN
        val result = create(runner = runner)

        // THEN
        result.shouldBeInstanceOf<RebaseBackupResult.Created>().headSha shouldBe headSha
    }

    @Test
    fun `create - should accept a SHA-256 object id`() {
        // GIVEN
        val sha256 = "c".repeat(64)
        val runner = runner(revParse = success(output = sha256))

        // WHEN
        val result = create(runner = runner)

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<RebaseBackupResult.Created>().headSha shouldBe sha256
            runner.updateRefCalls().first() shouldContainExactly listOf("-m", headMessage, headRef, sha256, "0".repeat(64))
        }
    }
}