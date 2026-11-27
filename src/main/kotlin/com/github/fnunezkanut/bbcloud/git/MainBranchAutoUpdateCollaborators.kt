package com.github.fnunezkanut.bbcloud.git

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketPullRequestClient
import com.github.fnunezkanut.bbcloud.bitbucket.OkHttpBitbucketRepositoryClient
import com.github.fnunezkanut.bbcloud.cache.DiffyWorkspaceRepositoryCache
import com.github.fnunezkanut.bbcloud.project.DiffyWatchedProjectsDiscoveryService
import com.github.fnunezkanut.bbcloud.settings.DiffySettings
import com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestReviewBarRefresh
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import git4idea.repo.GitRepositoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What the background job needs to know about the configuration, read once per pass. */
data class MainBranchSyncConfig(val enabled: Boolean, val workspace: String, val projectsDirectory: String, val watchedSlugs: List<String>)

/** Reads the [MainBranchSyncConfig] of a pass. */
fun interface MainBranchSyncSettings {
    fun read(): MainBranchSyncConfig
}

/** Finds the local clone of a watched repository, or null when there is none. */
fun interface WatchedCloneResolver {
    fun resolve(slug: String): Path?
}

/** Finds the Bitbucket main branch name of a repository without running git, or null when it is unknown. */
fun interface MainBranchNameResolver {
    suspend fun resolve(workspace: String, slug: String): String?
}

/** Tells whether an editor holds unsaved changes of a file under a clone. */
fun interface UnsavedDocumentChecker {
    fun hasUnsavedDocumentUnder(root: Path): Boolean
}

/** Tells the IDE that a clone changed after the main branch moved. */
fun interface MainBranchRefresher {
    /** [filesChanged] is true when work tree files changed, false when only a ref moved. */
    fun refresh(project: Project, root: Path, filesChanged: Boolean)
}

/** Tells the user that the fetch of a repository keeps failing. */
fun interface FetchFailureNotifier {
    fun notifyFetchFailed(slug: String, failures: Int)
}

/** The production [MainBranchSyncSettings], backed by [DiffySettings]. */
class DiffySettingsMainBranchSyncSettings : MainBranchSyncSettings {
    override fun read(): MainBranchSyncConfig {
        val settings = DiffySettings.getInstance()
        return MainBranchSyncConfig(
            enabled = settings.autoUpdateMainBranches,
            workspace = settings.workspaceSlug,
            projectsDirectory = settings.projectsDirectory,
            watchedSlugs = settings.watchedProjects.filter { mapping -> mapping.watched }.map { mapping -> mapping.remoteRepoSlug }
        )
    }
}

/**
 * The production [WatchedCloneResolver]. It recomputes the clone path from the projects directory and requires a `.git`
 * entry. A pull request work tree is never a source clone, so it is rejected.
 */
class SettingsWatchedCloneResolver(
    private val discoveryService: DiffyWatchedProjectsDiscoveryService = DiffyWatchedProjectsDiscoveryService(OkHttpBitbucketRepositoryClient())
) : WatchedCloneResolver {
    override fun resolve(slug: String): Path? {
        val settings = DiffySettings.getInstance()
        val folder = discoveryService.resolveLocalFolderPath(
            projectsDirectory = settings.projectsDirectory,
            configuredWorkspaceSlug = settings.workspaceSlug,
            repoSlug = slug
        )
        if (folder.isBlank()) {
            return null
        }
        val root = Path.of(folder)
        if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git"))) {
            return null
        }
        if (DiffyPullRequestReviewBarRefresh.isPullRequestWorkTree(folder)) {
            return null
        }
        return root
    }
}

/**
 * The production [MainBranchNameResolver]: the cached repository list first (no request), then one Bitbucket request per
 * repository and 24 hours. A missing answer is remembered for the same time, so a repository without an answer does not
 * cause a request every pass.
 */
class BitbucketMainBranchNameResolver(private val clock: () -> Instant = { Clock.System.now() }) : MainBranchNameResolver {
    private data class Remembered(val name: String?, val at: Instant)

    private val remembered = ConcurrentHashMap<String, Remembered>()

    override suspend fun resolve(workspace: String, slug: String): String? {
        val cached = DiffyWorkspaceRepositoryCache.getInstance().read(workspace)
            ?.repositories
            ?.firstOrNull { repository -> repository.slug == slug }
            ?.mainBranch
        if (cached != null) {
            return cached
        }
        val key = "$workspace/$slug"
        val now = clock()
        val previous = remembered[key]
        if (previous != null && now - previous.at < REMEMBER_FOR) {
            return previous.name
        }
        val name = readFromBitbucket(workspace = workspace, slug = slug)
        remembered[key] = Remembered(name = name, at = now)
        return name
    }

    private suspend fun readFromBitbucket(workspace: String, slug: String): String? {
        val token = ApplicationManager.getApplication().service<DiffyAccessTokenProvider>().bearerTokenOrNull()
        if (token == null) {
            LOG.info("Not reading the main branch of $slug from Bitbucket: no stored login")
            return null
        }
        return try {
            OkHttpBitbucketPullRequestClient().getMainBranchName(workspace = workspace, repoSlug = slug, apiToken = token)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.debug("Reading the main branch of $slug from Bitbucket failed", exception)
            null
        }
    }

    companion object {
        private val LOG = logger<BitbucketMainBranchNameResolver>()
        private val REMEMBER_FOR = 24.hours
    }
}

/** The production [UnsavedDocumentChecker], backed by [FileDocumentManager]. */
class PlatformUnsavedDocumentChecker : UnsavedDocumentChecker {
    override fun hasUnsavedDocumentUnder(root: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return ReadAction.compute<Boolean, RuntimeException> {
            val manager = FileDocumentManager.getInstance()
            manager.unsavedDocuments.any { document ->
                val file = manager.getFile(document)
                file != null && file.isInLocalFileSystem && Path.of(file.path).toAbsolutePath().normalize().startsWith(normalizedRoot)
            }
        }
    }
}

/** The production [MainBranchRefresher]: refreshes the Git repository and, when files changed, the virtual file system. */
class PlatformMainBranchRefresher : MainBranchRefresher {
    override fun refresh(project: Project, root: Path, filesChanged: Boolean) {
        if (project.isDisposed) {
            return
        }
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return
        GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(rootFile)?.update()
        if (filesChanged) {
            VfsUtil.markDirtyAndRefresh(true, true, false, rootFile)
        }
    }
}

/** The production [FetchFailureNotifier]: one balloon in the "Diffy" notification group. */
class BalloonFetchFailureNotifier : FetchFailureNotifier {
    override fun notifyFetchFailed(slug: String, failures: Int) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    DiffyBundle.message("mainSync.notification.fetchFailed.title", slug),
                    DiffyBundle.message("mainSync.notification.fetchFailed.content", slug, failures),
                    NotificationType.WARNING
                )
                .notify(null)
        }
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "Diffy"
    }
}