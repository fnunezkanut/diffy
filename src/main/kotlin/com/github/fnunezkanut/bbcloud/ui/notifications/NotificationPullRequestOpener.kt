package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryUrls
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.project.PullRequestOpenCheckResult
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a click on a notification row into an action. The title opens the pull request for review, or on Bitbucket
 * when it is closed or lives in another workspace; the pull request number opens the pull request on the web; the
 * repository opens the local checkout, or the Bitbucket page when there is none.
 *
 * Everything the platform provides is injected, so the routing is unit tested without the IDE.
 */
internal class NotificationPullRequestOpener(
    private val coroutineScope: CoroutineScope,
    private val checkOpen: suspend (workspace: String, repoSlug: String, prNumber: Long) -> PullRequestOpenCheckResult?,
    private val startReview: (workspace: String, repoSlug: String, prNumber: Long) -> Unit,
    private val localCheckoutPathFor: (repoSlug: String) -> String,
    private val openLocalProject: suspend (repoSlug: String, checkoutPath: Path) -> Boolean,
    private val browse: (url: String) -> Unit,
    private val runOnEdt: (() -> Unit) -> Unit
) {
    /** The title link: open the pull request for review, or on the web when it is not open. */
    fun openForReview(row: NotificationRow) {
        LOG.info("User opened the pull request of notification ${row.id}")
        val workspace = row.workspace
        val repoSlug = row.repoSlug
        val pullRequestId = row.pullRequestId
        if (workspace == null || repoSlug == null || pullRequestId == null || row.isForeignWorkspace) {
            LOG.info("Opening notification ${row.id} on the web: the review flow does not apply")
            browseIfSafe(url = row.pullRequestWebUrl)
            return
        }
        val knownState = row.knownPullRequestState
        if (knownState != null && knownState != PullRequestState.Open) {
            LOG.info("Opening notification ${row.id} on the web: the pull request is $knownState")
            browseIfSafe(url = row.pullRequestWebUrl)
            return
        }
        coroutineScope.launch {
            routeByLiveState(row = row, workspace = workspace, repoSlug = repoSlug, pullRequestId = pullRequestId)
        }
    }

    /** The `#772` link: open the pull request page on Bitbucket. */
    fun openOnWeb(row: NotificationRow) {
        LOG.info("User opened the pull request page of notification ${row.id} on the web")
        browseIfSafe(url = row.pullRequestWebUrl)
    }

    /** The repository link: open the local checkout when there is one, otherwise the Bitbucket repository page. */
    fun openRepository(row: NotificationRow) {
        val repoSlug = row.repoSlug
        val webWorkspace = row.urlWorkspace ?: row.workspace
        val webUrl = webWorkspace?.let { workspace ->
            BitbucketRepositoryUrls.repositoryWebUrlForWorkspace(workspace = workspace, repoSlug = repoSlug.orEmpty())
        }
        if (repoSlug == null) {
            LOG.info("Cannot open the repository of notification ${row.id}: no repository")
            return
        }
        val localPath = if (row.isForeignWorkspace) "" else localCheckoutPathFor(repoSlug)
        if (localPath.isBlank()) {
            LOG.info("User opened the repository of notification ${row.id} on the web")
            browseIfSafe(url = webUrl)
            return
        }
        LOG.info("User opened the local project of notification ${row.id}")
        coroutineScope.launch {
            val opened = try {
                openLocalProject(repoSlug, Path.of(localPath))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not open the local project for notification ${row.id}", exception)
                false
            }
            if (!opened) {
                LOG.info("Falling back to the repository web page for notification ${row.id}")
                runOnEdt { browseIfSafe(url = webUrl) }
            }
        }
    }

    private suspend fun routeByLiveState(row: NotificationRow, workspace: String, repoSlug: String, pullRequestId: Long) {
        when (val check = checkOpen(workspace, repoSlug, pullRequestId)) {
            is PullRequestOpenCheckResult.NotOpen -> {
                LOG.info("Opening notification ${row.id} on the web: the pull request is ${check.state}")
                runOnEdt { browseIfSafe(url = row.pullRequestWebUrl) }
            }
            PullRequestOpenCheckResult.NotFound -> {
                LOG.info("Opening notification ${row.id} on the web: Bitbucket does not return the pull request")
                runOnEdt { browseIfSafe(url = row.pullRequestWebUrl) }
            }
            is PullRequestOpenCheckResult.StillOpen, is PullRequestOpenCheckResult.Failed, null -> {
                // A failed check falls through to the review flow, which reports its own problems with a Bitbucket link.
                startReview(workspace, repoSlug, pullRequestId)
            }
        }
    }

    private fun browseIfSafe(url: String?) {
        if (url == null || !isBrowsableUrl(url = url)) {
            LOG.info("Not opening a missing or unsafe link")
            return
        }
        browse(url)
    }

    companion object {
        private val LOG = logger<NotificationPullRequestOpener>()

        /** Only http and https links are ever handed to the browser. */
        internal fun isBrowsableUrl(url: String): Boolean {
            val scheme = try {
                URI(url.trim()).scheme
            } catch (_: Exception) {
                return false
            }
            return scheme.equals(other = "https", ignoreCase = true) || scheme.equals(other = "http", ignoreCase = true)
        }
    }
}