package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Writes the backup refs described in [RebaseBackupRefs] before "Rebase onto latest with AI" sends its prompt, then
 * prunes old snapshots of the same pull request. Only the head backup is required; the `-wip` backup and pruning are
 * best effort. Failures are logged at debug only, because the caller decides the user-facing outcome.
 */
internal class RebaseBackupWriter(
    private val gitRunner: GitCommandRunner = PlatformGitCommandRunner(),
    private val clock: () -> Instant = { Clock.System.now() }
) {
    /** Blocking: call it off the EDT. */
    fun create(project: Project?, workTree: Path, pullRequestId: Long): RebaseBackupResult {
        val revParse = run(project = project, workTree = workTree, command = GitCommand.REV_PARSE, parameters = listOf("--verify", "HEAD"))
        val headSha = GitCommandOutput.lastOutputLine(revParse.combinedOutput)
        if (!revParse.success || headSha == null || !isObjectId(value = headSha)) {
            LOG.debug("Could not resolve HEAD for the backup of pull request #$pullRequestId")
            return RebaseBackupResult.Failed(reason = reasonOf(result = revParse))
        }
        LOG.debug("HEAD of pull request #$pullRequestId is $headSha")
        val headRef = RebaseBackupRefs.headRef(pullRequestId = pullRequestId, createdAt = clock())
        val headUpdate = createRef(project = project, workTree = workTree, ref = headRef, sha = headSha, message = headMessage(pullRequestId = pullRequestId))
        if (!headUpdate.success) {
            LOG.debug("Could not write backup ref $headRef")
            return RebaseBackupResult.Failed(reason = reasonOf(result = headUpdate))
        }
        LOG.debug("Wrote backup ref $headRef")
        val wipRef = createWipRef(project = project, workTree = workTree, pullRequestId = pullRequestId, headRef = headRef)
        prune(project = project, workTree = workTree, pullRequestId = pullRequestId)
        return RebaseBackupResult.Created(headSha = headSha, headRef = headRef, wipRef = wipRef)
    }

    /** The `-wip` ref of the uncommitted tracked changes, or null when the tracked tree is clean or a step failed. */
    private fun createWipRef(project: Project?, workTree: Path, pullRequestId: Long, headRef: String): String? {
        val message = wipMessage(pullRequestId = pullRequestId)
        val stash = run(project = project, workTree = workTree, command = GitCommand.STASH, parameters = listOf("create", message))
        if (!stash.success) {
            LOG.debug("git stash create failed for pull request #$pullRequestId; saving HEAD only")
            return null
        }
        val stashSha = GitCommandOutput.lastOutputLine(stash.combinedOutput)
        if (stashSha == null || !isObjectId(value = stashSha)) {
            LOG.debug("No uncommitted tracked changes in pull request #$pullRequestId")
            return null
        }
        val wipRef = RebaseBackupRefs.wipRef(headRef = headRef)
        val wipUpdate = createRef(project = project, workTree = workTree, ref = wipRef, sha = stashSha, message = message)
        if (!wipUpdate.success) {
            LOG.debug("Could not write backup ref $wipRef; saving HEAD only")
            return null
        }
        LOG.debug("Wrote backup ref $wipRef")
        return wipRef
    }

    private fun prune(project: Project?, workTree: Path, pullRequestId: Long) {
        val listing = run(
            project = project,
            workTree = workTree,
            command = GitCommand.FOR_EACH_REF,
            parameters = listOf("--format=%(refname)", RebaseBackupRefs.pullRequestPrefix(pullRequestId = pullRequestId))
        )
        if (!listing.success) {
            LOG.debug("Could not list the backup refs of pull request #$pullRequestId; nothing pruned")
            return
        }
        val existingRefs = GitCommandOutput.withoutCommandLine(listing.combinedOutput)
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()
        RebaseBackupRefs.refsToPrune(pullRequestId = pullRequestId, existingRefs = existingRefs).forEach { ref ->
            val deletion = run(project = project, workTree = workTree, command = GitCommand.UPDATE_REF, parameters = listOf("-d", ref))
            if (deletion.success) {
                LOG.debug("Pruned backup ref $ref")
            } else {
                LOG.debug("Could not prune backup ref $ref")
            }
        }
    }

    /** The all-zeros old value makes git refuse to overwrite a ref that already exists. */
    private fun createRef(project: Project?, workTree: Path, ref: String, sha: String, message: String): GitCommandResult {
        return run(
            project = project,
            workTree = workTree,
            command = GitCommand.UPDATE_REF,
            parameters = listOf("-m", message, ref, sha, RebaseBackupRefs.zeroObjectId(sha = sha))
        )
    }

    private fun run(project: Project?, workTree: Path, command: GitCommand, parameters: List<String>): GitCommandResult {
        return gitRunner.run(project = project, workingDir = workTree, command = command, parameters = parameters)
    }

    private fun reasonOf(result: GitCommandResult): String {
        return GitCommandOutput.withoutCommandLine(result.combinedOutput).trim().ifEmpty { "git printed no output" }
    }

    private fun isObjectId(value: String): Boolean {
        return OBJECT_ID.matches(value)
    }

    private fun headMessage(pullRequestId: Long): String {
        return "diffy: backup before AI rebase of PR #$pullRequestId"
    }

    private fun wipMessage(pullRequestId: Long): String {
        return "diffy: uncommitted changes before AI rebase of PR #$pullRequestId"
    }

    companion object {
        private val LOG = logger<RebaseBackupWriter>()

        /** A SHA-1 (40) or SHA-256 (64) object id. */
        private val OBJECT_ID = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
    }
}