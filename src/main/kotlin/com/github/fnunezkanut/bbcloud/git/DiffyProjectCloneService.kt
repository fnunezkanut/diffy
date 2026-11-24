package com.github.fnunezkanut.bbcloud.git

import com.github.fnunezkanut.bbcloud.validation.BitbucketRepositorySlugValidator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.util.io.NioFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Clones a workspace repository into `<projectsDirectory>/<slug>`. A folder left behind by a failed or cancelled clone
 * is deleted; only a folder this service proved absent before the clone started is ever deleted.
 */
class DiffyProjectCloneService(
    private val cloneRunner: GitCloneRunner = PlatformGitCloneRunner(),
    private val runWithIndicator: suspend ((ProgressIndicator?) -> GitCommandResult) -> GitCommandResult = { block ->
        coroutineToIndicator { indicator -> block(indicator) }
    }
) {
    suspend fun cloneProject(projectsDirectory: Path, repoSlug: String, cloneUrl: String): ProjectCloneResult {
        val normalizedDirectory = projectsDirectory.toAbsolutePath().normalize()
        val target = normalizedDirectory.resolve(repoSlug).normalize()
        if (!BitbucketRepositorySlugValidator.validate(repoSlug).isSuccess || target.parent != normalizedDirectory) {
            LOG.info("Refusing to clone: $repoSlug is not a valid folder name in the projects directory")
            return ProjectCloneResult.InvalidTarget
        }
        return withContext(Dispatchers.IO) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                LOG.info("Not cloning $repoSlug: a folder with that name already exists in the projects directory")
                return@withContext ProjectCloneResult.TargetExists(checkoutPath = target)
            }
            val result = try {
                runWithIndicator { indicator ->
                    cloneRunner.clone(
                        parentDirectory = normalizedDirectory,
                        directoryName = repoSlug,
                        url = cloneUrl,
                        indicator = indicator
                    )
                }
            } catch (exception: CancellationException) {
                withContext(NonCancellable) {
                    deletePartialClone(target = target)
                }
                throw exception
            }
            if (!result.success) {
                deletePartialClone(target = target)
                return@withContext ProjectCloneResult.Failed(output = result.combinedOutput)
            }
            if (!Files.exists(target.resolve(".git"))) {
                deletePartialClone(target = target)
                return@withContext ProjectCloneResult.Failed(output = result.combinedOutput)
            }
            ProjectCloneResult.Success(checkoutPath = target, branch = GitHeadBranchReader.readBranch(projectPath = target))
        }
    }

    private fun deletePartialClone(target: Path) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        try {
            NioFiles.deleteRecursively(target)
        } catch (exception: IOException) {
            LOG.warn("Could not delete the partial clone at $target", exception)
        }
    }

    companion object {
        private val LOG = logger<DiffyProjectCloneService>()
    }
}