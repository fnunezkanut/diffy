package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import git4idea.commands.Git
import git4idea.commands.GitLineHandlerListener
import git4idea.commands.GitStandardProgressAnalyzer
import java.nio.file.Path
import kotlin.time.TimeSource

/** The seam [DiffyProjectCloneService] uses to run `git clone`, so the service can be unit-tested without git. */
fun interface GitCloneRunner {
    fun clone(parentDirectory: Path, directoryName: String, url: String, indicator: ProgressIndicator?): GitCommandResult
}

/**
 * The production [GitCloneRunner]. It runs a full clone through git4idea, so the IDE's SSH handling applies. Submodules
 * follow the IDE advanced setting `git.clone.recurse.submodules`, which `Git.clone` applies itself.
 */
class PlatformGitCloneRunner : GitCloneRunner {
    override fun clone(parentDirectory: Path, directoryName: String, url: String, indicator: ProgressIndicator?): GitCommandResult {
        val listeners = if (indicator == null) {
            emptyArray<GitLineHandlerListener>()
        } else {
            arrayOf(GitStandardProgressAnalyzer.createListener(indicator))
        }
        LOG.debug("Cloning $url into $parentDirectory/$directoryName")
        val started = TimeSource.Monotonic.markNow()
        val result = Git.getInstance().clone(null, parentDirectory, url, directoryName, *listeners)
        LOG.debug("Cloning $url finished: success=${result.success()} in ${started.elapsedNow().inWholeMilliseconds} ms")
        return GitCommandResult(
            success = result.success(),
            combinedOutput = GitCommandOutput.format(
                commandLine = "git clone $url $directoryName",
                output = result.outputAsJoinedString,
                errorOutput = result.errorOutputAsJoinedString
            )
        )
    }

    companion object {
        private val LOG = logger<PlatformGitCloneRunner>()
    }
}