package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * The main branch of a repository: [name] is `main` or `master`, [ref] is the ref to read files from (for example
 * `origin/main`), and [commitHash] is the commit that [ref] points to, when the repository knows it.
 */
internal data class MainBranchRef(val name: String, val ref: String, val commitHash: String?)

/**
 * Finds the main branch (`main` or `master`) of a [GitRepository] without fetching. The remote tracking ref wins over
 * the local branch (see [GitBranchRefResolver]). The choice between the two names is made by [MainBranchChooser].
 */
internal object GitMainBranchLocator {
    private val LOG = logger<GitMainBranchLocator>()

    private val cache = ConcurrentHashMap<String, CachedMainBranch>()

    private data class ResolvedCandidate(val name: String, val ref: String, val commitHash: String?)

    private class CachedMainBranch(val key: String, val value: MainBranchRef?)

    /** True when a `main` or `master` ref exists. Needs no git process, so it is safe in `AnAction.update`. */
    fun hasMainBranchRef(repository: GitRepository): Boolean {
        return MainBranchChooser.CANDIDATE_NAMES.any { name ->
            GitBranchRefResolver.resolve(repository = repository, branchName = name, preferRemote = true) != null
        }
    }

    /**
     * Finds the main branch of [repository], running `git log` when both `main` and `master` exist. Callers run it off
     * the EDT.
     */
    fun locate(project: Project, repository: GitRepository): MainBranchRef? {
        return choose(project = project, repository = repository, candidates = resolveCandidates(repository = repository))
    }

    /**
     * Like [locate], but remembers the answer per repository root until `HEAD` or one of the candidate refs moves, so
     * the `git log` scan of the both-names case runs once per movement instead of once per popup. Callers run it off
     * the EDT.
     */
    fun locateCached(project: Project, repository: GitRepository): MainBranchRef? {
        val candidates = resolveCandidates(repository = repository)
        if (candidates.isEmpty()) {
            return null
        }
        val key = cacheKey(repository = repository, candidates = candidates)
        val rootPath = repository.root.path
        val cached = cache[rootPath]
        if (cached != null && cached.key == key) {
            return cached.value
        }
        val value = choose(project = project, repository = repository, candidates = candidates)
        cache[rootPath] = CachedMainBranch(key = key, value = value)
        return value
    }

    private fun choose(project: Project, repository: GitRepository, candidates: List<ResolvedCandidate>): MainBranchRef? {
        val remoteNames = repository.remotes.map { remote -> remote.name }.toSet()
        val chosenName = MainBranchChooser.choose(
            existing = candidates.map { candidate -> candidate.name },
            remoteNames = remoteNames,
            recentDecorationLines = { readRecentDecorationLines(project = project, repository = repository) }
        ) ?: return null
        val chosen = candidates.first { candidate -> candidate.name == chosenName }
        LOG.debug("Main branch of the repository resolved to ${chosen.ref} among ${candidates.size} candidate(s)")
        return MainBranchRef(name = chosen.name, ref = chosen.ref, commitHash = chosen.commitHash)
    }

    private fun resolveCandidates(repository: GitRepository): List<ResolvedCandidate> {
        return MainBranchChooser.CANDIDATE_NAMES.mapNotNull { name ->
            val ref = GitBranchRefResolver.resolve(repository = repository, branchName = name, preferRemote = true)
            if (ref == null) {
                null
            } else {
                ResolvedCandidate(name = name, ref = ref, commitHash = commitHashOf(repository = repository, ref = ref))
            }
        }
    }

    private fun commitHashOf(repository: GitRepository, ref: String): String? {
        val branch = repository.branches.findBranchByName(ref) ?: return null
        return repository.branches.getHash(branch)?.asString()
    }

    private fun cacheKey(repository: GitRepository, candidates: List<ResolvedCandidate>): String {
        val refs = candidates.joinToString(separator = ";") { candidate -> "${candidate.ref}=${candidate.commitHash}" }
        return "${repository.currentRevision}|$refs"
    }

    /** The `%D` decorations of the last commits of `HEAD`, newest first. Empty when git fails. */
    private fun readRecentDecorationLines(project: Project, repository: GitRepository): List<String> {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters("-n", MainBranchChooser.RECENT_COMMIT_SCAN_COUNT.toString(), "--format=%D", "HEAD", "--")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            LOG.debug("Could not read the recent commit decorations: exitCode=${result.exitCode}")
            return emptyList()
        }
        LOG.debug("Read ${result.output.size} recent commit decoration line(s)")
        return result.output
    }
}