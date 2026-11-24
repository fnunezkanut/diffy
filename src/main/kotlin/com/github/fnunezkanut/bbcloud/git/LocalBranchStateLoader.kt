package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlin.time.TimeSource

/** Reads the [LocalBranchState] of a pull request work tree from the local repository. It never fetches. */
internal class LocalBranchStateLoader(
    private val diffService: DiffyPullRequestBranchDiffService = DiffyPullRequestBranchDiffService(),
    private val changedPathsReader: ChangedPathsReader = ChangedPathsReader { project, root, from, to ->
        readChangedPaths(project = project, repositoryRoot = root, remoteSource = from, head = to, git = Git.getInstance())
    }
) {
    /** Reads the paths that changed between two revisions, or null when the git command fails. */
    fun interface ChangedPathsReader {
        fun read(project: Project, repositoryRoot: VirtualFile, fromRevision: String, toRevision: String): Set<String>?
    }

    /**
     * Loads the state for [sourceBranch], or null when it cannot be determined (no repository, no `<remote>/<source>`
     * ref, or a failing git command).
     */
    @RequiresBackgroundThread
    fun load(project: Project, sourceBranch: String, uncommittedPaths: Set<String>): LocalBranchState? {
        val repository = ProjectGitRepositoryLookup.findRepository(project) ?: run {
            LOG.debug("Local branch state skipped: no Git repository for project ${project.name}")
            return null
        }
        val head = repository.currentRevision
        if (head.isNullOrBlank()) {
            LOG.debug("Local branch state skipped: the work tree has no current revision")
            return null
        }
        val remoteName = PrRemoteResolver.remoteName(repository = repository)
        val remoteSource = diffService.resolveRevision(project = project, refName = "$remoteName/$sourceBranch")
        if (remoteSource == null) {
            LOG.info("Local branch state skipped: the remote ref of the pull request source branch does not exist")
            return null
        }
        val localSourceRef = GitBranchRefResolver.resolve(
            repository = repository,
            branchName = sourceBranch,
            preferRemote = false
        )
        val localSource = localSourceRef?.let { ref -> diffService.resolveRevision(project = project, refName = ref) }
        val descends = descendsFromLocalSource(project = project, localSource = localSource, head = head)
        val ahead = diffService.countCommitsBetween(
            project = project,
            fromRevision = remoteSource,
            toRevision = head
        ) ?: run {
            LOG.debug("Local branch state skipped: counting the unpushed commits failed")
            return null
        }
        val unpushedPaths = if (ahead > 0) {
            changedPathsReader.read(
                project = project,
                repositoryRoot = repository.root,
                fromRevision = remoteSource,
                toRevision = head
            ) ?: return null
        } else {
            emptySet()
        }
        return LocalBranchState(
            remoteName = remoteName,
            sourceBranch = sourceBranch,
            headRevision = head,
            localSourceRevision = localSource,
            remoteSourceRevision = remoteSource,
            headDescendsFromLocalSource = descends,
            commitsAheadOfRemote = ahead,
            unpushedPaths = unpushedPaths,
            uncommittedPaths = uncommittedPaths
        )
    }

    private fun descendsFromLocalSource(project: Project, localSource: String?, head: String): Boolean {
        if (localSource == null) {
            return false
        }
        if (localSource.equals(head, ignoreCase = true)) {
            return true
        }
        return diffService.isAncestor(project = project, ancestorRevision = localSource, descendantRevision = head)
    }

    companion object {
        private val LOG = logger<LocalBranchStateLoader>()

        fun readChangedPaths(project: Project, repositoryRoot: VirtualFile, remoteSource: String, head: String, git: Git): Set<String>? {
            return try {
                val handler = GitLineHandler(project, repositoryRoot, GitCommand.DIFF).apply {
                    addParameters("--name-only", "-z", remoteSource, head)
                }
                val commandLine = handler.printableCommandLine()
                LOG.debug("Running $commandLine")
                val started = TimeSource.Monotonic.markNow()
                val result = git.runCommand(handler)
                LOG.debug("Finished $commandLine: exitCode=${result.exitCode} in ${started.elapsedNow().inWholeMilliseconds} ms")
                if (!result.success()) {
                    LOG.debug("git diff --name-only failed: exitCode=${result.exitCode}")
                    return null
                }
                GitNameOnlyOutput.parse(output = result.outputAsJoinedString)
            } catch (exception: Exception) {
                LOG.debug("git diff --name-only failed between $remoteSource and $head", exception)
                null
            }
        }
    }
}