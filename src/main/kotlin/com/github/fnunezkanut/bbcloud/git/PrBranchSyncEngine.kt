package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches, inspects and fast-forwards the local source and destination branches of a pull request so they match their
 * `<remote>/<branch>` counterparts on Bitbucket when that is safe.
 *
 * Git runs through the [GitCommandRunner] seam, so the engine holds no platform state and is unit-testable. All
 * functions block and must be called on [kotlinx.coroutines.Dispatchers.IO]. Branch names are always used in full;
 * only a leading `<remote>/` or `refs/heads/` is stripped (see [normalizeBranchName]).
 */
class PrBranchSyncEngine(
    private val gitRunner: GitCommandRunner = PlatformGitCommandRunner(),
    private val openProjectLocator: OpenProjectLocator = OpenProjectLocatorImpl()
) {
    /** Strips only a leading `<remote>/` and a leading `refs/heads/`, never everything before the last slash. */
    fun normalizeBranchName(branchName: String, remoteName: String): String {
        return GitBranchNames.normalize(branchName = branchName, remoteName = remoteName)
    }

    /**
     * Fetches [branches] from [remoteName] into `refs/remotes/<remote>/<branch>` with explicit forced refspecs. When the
     * combined fetch fails and there is more than one branch, each refspec is retried on its own so a missing remote
     * branch does not block the other. Returns true when at least one fetch succeeded.
     */
    fun fetch(project: Project?, prWorktreePath: Path, remoteName: String, remoteUrl: String?, branches: List<String>, background: Boolean = false): Boolean {
        val distinctBranches = branches
            .map { branch -> normalizeBranchName(branchName = branch, remoteName = remoteName) }
            .filter { branch -> branch.isNotBlank() }
            .distinct()
        if (distinctBranches.isEmpty()) {
            return false
        }
        val refspecs = distinctBranches.map { branch -> refspec(remoteName = remoteName, branch = branch) }
        val combined = runFetch(
            project = project,
            workingDir = prWorktreePath,
            remoteName = remoteName,
            remoteUrl = remoteUrl,
            refspecs = refspecs,
            background = background
        )
        if (combined) {
            return true
        }
        if (refspecs.size == 1) {
            return false
        }
        var anySucceeded = false
        for (refspec in refspecs) {
            val fetched = runFetch(
                project = project,
                workingDir = prWorktreePath,
                remoteName = remoteName,
                remoteUrl = remoteUrl,
                refspecs = listOf(refspec),
                background = background
            )
            if (fetched) {
                anySucceeded = true
            }
        }
        return anySucceeded
    }

    /** Reads the state of both branches without changing anything. Never throws (other than cancellation). */
    fun inspect(project: Project?, prWorktreePath: Path, remoteName: String, sourceBranch: String, destinationBranch: String): PrBranchSyncSnapshot {
        val source = normalizeBranchName(branchName = sourceBranch, remoteName = remoteName)
        val destination = normalizeBranchName(branchName = destinationBranch, remoteName = remoteName)
        val worktreeList = readWorktreeList(project = project, prWorktreePath = prWorktreePath)
        val sourceStatus = inspectBranch(
            project = project,
            prWorktreePath = prWorktreePath,
            remoteName = remoteName,
            branch = source,
            worktreeList = worktreeList
        )
        val destinationStatus = if (destination == source) {
            sourceStatus
        } else {
            inspectBranch(
                project = project,
                prWorktreePath = prWorktreePath,
                remoteName = remoteName,
                branch = destination,
                worktreeList = worktreeList
            )
        }
        return PrBranchSyncSnapshot(
            remoteName = remoteName,
            sourceBranch = source,
            destinationBranch = destination,
            prWorktreePath = prWorktreePath,
            source = sourceStatus,
            destination = destinationStatus
        )
    }

    /**
     * Reads the state of one [branch] of the clone at [workingDir] without changing anything. [background] runs git
     * unattended (see [GitCommandRunner.run]). Never throws (other than cancellation).
     */
    fun inspectSingle(project: Project?, workingDir: Path, remoteName: String, branch: String, background: Boolean = false): BranchSyncStatus {
        val normalized = normalizeBranchName(branchName = branch, remoteName = remoteName)
        val worktreeList = readWorktreeList(project = project, prWorktreePath = workingDir, background = background)
        return inspectBranch(
            project = project,
            prWorktreePath = workingDir,
            remoteName = remoteName,
            branch = normalized,
            worktreeList = worktreeList,
            background = background
        )
    }

    /** Fast-forwards the source and then the destination branch according to [PrBranchFastForwardPolicy]. */
    fun fastForward(project: Project?, snapshot: PrBranchSyncSnapshot, remoteUrl: String?, mode: PrBranchSyncMode): PrBranchSyncOutcome {
        val results = linkedMapOf<PrBranchRole, PrBranchSyncStepResult>()
        for (role in PrBranchRole.entries) {
            val isDuplicateDestination = role == PrBranchRole.Destination && snapshot.destinationBranch == snapshot.sourceBranch
            if (isDuplicateDestination) {
                results[role] = PrBranchSyncStepResult.NotNeeded
                continue
            }
            results[role] = fastForwardBranch(project = project, snapshot = snapshot, role = role, remoteUrl = remoteUrl, mode = mode)
        }
        return PrBranchSyncOutcome(results = results)
    }

    /**
     * Runs fetch (when [fetch] is true), inspect, fast-forward and then inspect again. The second inspect runs without a
     * fetch.
     */
    fun sync(
        project: Project?,
        prWorktreePath: Path,
        remoteName: String,
        remoteUrl: String?,
        sourceBranch: String,
        destinationBranch: String,
        mode: PrBranchSyncMode,
        fetch: Boolean = true
    ): PrBranchSyncResult {
        if (fetch) {
            val fetched = fetch(
                project = project,
                prWorktreePath = prWorktreePath,
                remoteName = remoteName,
                remoteUrl = remoteUrl,
                branches = listOf(sourceBranch, destinationBranch)
            )
            if (!fetched) {
                LOG.debug("Fetch of the pull request branches failed; continuing with the existing remote tracking refs")
            }
        }
        val before = inspect(
            project = project,
            prWorktreePath = prWorktreePath,
            remoteName = remoteName,
            sourceBranch = sourceBranch,
            destinationBranch = destinationBranch
        )
        val outcome = fastForward(project = project, snapshot = before, remoteUrl = remoteUrl, mode = mode)
        val after = if (outcome.results.values.any { result -> result !is PrBranchSyncStepResult.NotNeeded && result !is PrBranchSyncStepResult.Skipped }) {
            inspect(
                project = project,
                prWorktreePath = prWorktreePath,
                remoteName = remoteName,
                sourceBranch = sourceBranch,
                destinationBranch = destinationBranch
            )
        } else {
            before
        }
        return PrBranchSyncResult(outcome = outcome, after = after)
    }

    private fun runFetch(project: Project?, workingDir: Path, remoteName: String, remoteUrl: String?, refspecs: List<String>, background: Boolean): Boolean {
        return try {
            val result = gitRunner.run(
                project = project,
                workingDir = workingDir,
                command = GitCommand.FETCH,
                parameters = listOf(remoteName) + refspecs,
                remoteUrl = remoteUrl,
                background = background
            )
            if (!result.success) {
                LOG.debug("git fetch $remoteName ${refspecs.size} refspec(s) failed")
            }
            result.success
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.debug("git fetch $remoteName failed unexpectedly", exception)
            false
        }
    }

    private fun readWorktreeList(project: Project?, prWorktreePath: Path, background: Boolean = false): String? {
        val result = runSafely(
            project = project,
            workingDir = prWorktreePath,
            command = GitCommand.WORKTREE,
            parameters = listOf("list", "--porcelain"),
            background = background
        )
        return if (result != null && result.success) result.combinedOutput else null
    }

    private fun inspectBranch(
        project: Project?,
        prWorktreePath: Path,
        remoteName: String,
        branch: String,
        worktreeList: String?,
        background: Boolean = false
    ): BranchSyncStatus {
        if (branch.isBlank()) {
            return BranchSyncStatus.Unknown
        }
        val remoteRef = "refs/remotes/$remoteName/$branch"
        val localRef = "$HEADS_PREFIX$branch"
        val remoteResult = runSafely(
            project = project,
            workingDir = prWorktreePath,
            command = GitCommand.REV_PARSE,
            parameters = listOf("--verify", "--quiet", "$remoteRef^{commit}"),
            background = background
        )
        val remoteRevision = remoteResult?.takeIf { result -> result.success }
            ?.let { result -> GitCommandOutput.lastOutputLine(result.combinedOutput) }
        if (remoteRevision == null) {
            return if (remoteResult == null) BranchSyncStatus.Unknown else BranchSyncStatus.NoRemoteBranch
        }
        val localResult = runSafely(
            project = project,
            workingDir = prWorktreePath,
            command = GitCommand.REV_PARSE,
            parameters = listOf("--verify", "--quiet", "$localRef^{commit}"),
            background = background
        )
        val localRevision = localResult?.takeIf { result -> result.success }
            ?.let { result -> GitCommandOutput.lastOutputLine(result.combinedOutput) }
        if (localRevision == null) {
            return if (localResult == null) BranchSyncStatus.Unknown else BranchSyncStatus.MissingLocal(remoteRevision = remoteRevision)
        }
        val countResult = runSafely(
            project = project,
            workingDir = prWorktreePath,
            command = GitCommand.REV_LIST,
            parameters = listOf("--left-right", "--count", "$localRef...$remoteRef"),
            background = background
        )
        val aheadBehind = countResult?.takeIf { result -> result.success }
            ?.let { result -> GitAheadBehindOutput.parse(result.combinedOutput) }
            ?: return BranchSyncStatus.Unknown
        if (aheadBehind.behind == 0) {
            return BranchSyncStatus.UpToDate(commitsAhead = aheadBehind.ahead)
        }
        if (worktreeList == null) {
            return BranchSyncStatus.Unknown
        }
        val checkedOutAt = WorktreeListOutput.worktreeForBranch(porcelainOutput = worktreeList, branchName = branch)
        val dirty = checkedOutAt?.let { path -> readDirty(project = project, prWorktreePath = prWorktreePath, worktreePath = path, background = background) }
        return BranchSyncStatus.Behind(
            commitsBehind = aheadBehind.behind,
            commitsAhead = aheadBehind.ahead,
            localRevision = localRevision,
            remoteRevision = remoteRevision,
            checkedOutAt = checkedOutAt,
            checkedOutWorktreeDirty = dirty
        )
    }

    private fun readDirty(project: Project?, prWorktreePath: Path, worktreePath: Path, background: Boolean = false): Boolean? {
        if (!Files.isDirectory(worktreePath)) {
            return null
        }
        val result = runSafely(
            project = projectFor(project = project, prWorktreePath = prWorktreePath, path = worktreePath),
            workingDir = worktreePath,
            command = GitCommand.STATUS,
            parameters = listOf("--porcelain", "--untracked-files=no"),
            background = background
        )
        if (result == null || !result.success) {
            return null
        }
        return GitStatusOutput.hasUncommittedChanges(GitCommandOutput.withoutCommandLine(result.combinedOutput))
    }

    private fun fastForwardBranch(
        project: Project?,
        snapshot: PrBranchSyncSnapshot,
        role: PrBranchRole,
        remoteUrl: String?,
        mode: PrBranchSyncMode
    ): PrBranchSyncStepResult {
        val status = snapshot.statusFor(role)
        val action = PrBranchFastForwardPolicy.decide(
            role = role,
            status = status,
            prWorktreePath = snapshot.prWorktreePath,
            mode = mode
        )
        return execute(
            project = project,
            workingDir = snapshot.prWorktreePath,
            remoteName = snapshot.remoteName,
            branch = snapshot.branchFor(role),
            status = status,
            action = action,
            remoteUrl = remoteUrl,
            label = role.toString()
        )
    }

    /**
     * Carries out an [action] that was decided elsewhere for one [branch] of the clone at [workingDir]. [label] only
     * names the branch in log lines. [background] runs git unattended (see [GitCommandRunner.run]). Never throws (other
     * than cancellation).
     */
    fun execute(
        project: Project?,
        workingDir: Path,
        remoteName: String,
        branch: String,
        status: BranchSyncStatus,
        action: PrBranchSyncAction,
        remoteUrl: String?,
        label: String = "main",
        background: Boolean = false
    ): PrBranchSyncStepResult {
        val remoteRef = "refs/remotes/$remoteName/$branch"
        return try {
            when (action) {
                PrBranchSyncAction.None -> {
                    PrBranchSyncStepResult.NotNeeded
                }
                is PrBranchSyncAction.Skip -> {
                    LOG.info("Not updating local $label branch '$branch': ${action.reason}")
                    PrBranchSyncStepResult.Skipped(reason = action.reason, path = (status as? BranchSyncStatus.Behind)?.checkedOutAt)
                }
                is PrBranchSyncAction.MergeFastForwardInWorktree -> {
                    val result = runSafely(
                        project = projectFor(project = project, prWorktreePath = workingDir, path = action.worktreePath),
                        workingDir = action.worktreePath,
                        command = GitCommand.MERGE,
                        parameters = listOf("--ff-only", remoteRef),
                        background = background
                    )
                    finish(label = label, branch = branch, status = status, result = result, path = action.worktreePath)
                }
                is PrBranchSyncAction.UpdateRef -> {
                    val result = runSafely(
                        project = project,
                        workingDir = workingDir,
                        command = GitCommand.UPDATE_REF,
                        parameters = listOf(
                            "-m",
                            "diffy: fast-forward $branch to $remoteName/$branch",
                            "$HEADS_PREFIX$branch",
                            action.newRevision,
                            action.oldRevision
                        ),
                        background = background
                    )
                    finish(label = label, branch = branch, status = status, result = result, path = null)
                }
                PrBranchSyncAction.CreateLocalBranch -> {
                    val result = runSafely(
                        project = project,
                        workingDir = workingDir,
                        command = GitCommand.BRANCH,
                        parameters = listOf("--track", branch, remoteRef),
                        background = background
                    )
                    if (result != null && result.success) {
                        LOG.info("Created local branch '$branch' from $remoteName/$branch")
                        PrBranchSyncStepResult.Updated(path = null)
                    } else {
                        failed(result = result)
                    }
                }
                PrBranchSyncAction.PullIntoPrWorktree -> {
                    val result = runSafely(
                        project = project,
                        workingDir = workingDir,
                        command = GitCommand.PULL,
                        parameters = listOf("--no-edit", remoteName, branch),
                        remoteUrl = remoteUrl,
                        background = background
                    )
                    finish(label = label, branch = branch, status = status, result = result, path = workingDir)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.debug("Updating local $label branch '$branch' failed unexpectedly", exception)
            PrBranchSyncStepResult.Failed(output = exception.message.orEmpty())
        }
    }

    private fun finish(label: String, branch: String, status: BranchSyncStatus, result: GitCommandResult?, path: Path?): PrBranchSyncStepResult {
        if (result == null || !result.success) {
            return failed(result = result)
        }
        val commits = (status as? BranchSyncStatus.Behind)?.commitsBehind ?: 0
        LOG.info("Fast-forwarded local branch '$branch' ($label) by $commits commit(s)")
        return PrBranchSyncStepResult.Updated(path = path)
    }

    private fun failed(result: GitCommandResult?): PrBranchSyncStepResult {
        val output = result?.combinedOutput.orEmpty()
        LOG.debug("Updating a local pull request branch failed")
        return PrBranchSyncStepResult.Failed(output = output)
    }

    private fun projectFor(project: Project?, prWorktreePath: Path, path: Path): Project? {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized == prWorktreePath.toAbsolutePath().normalize()) {
            return project
        }
        return openProjectLocator.findOpenProjectForPath(normalized)
    }

    private fun runSafely(
        project: Project?,
        workingDir: Path,
        command: GitCommand,
        parameters: List<String>,
        remoteUrl: String? = null,
        background: Boolean = false
    ): GitCommandResult? {
        return try {
            gitRunner.run(
                project = project,
                workingDir = workingDir,
                command = command,
                parameters = parameters,
                remoteUrl = remoteUrl,
                background = background
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.debug("git ${command.name()} failed unexpectedly", exception)
            null
        }
    }

    private fun refspec(remoteName: String, branch: String): String {
        return "+$HEADS_PREFIX$branch:refs/remotes/$remoteName/$branch"
    }

    companion object {
        private val LOG = logger<PrBranchSyncEngine>()
        private const val HEADS_PREFIX = "refs/heads/"
    }
}