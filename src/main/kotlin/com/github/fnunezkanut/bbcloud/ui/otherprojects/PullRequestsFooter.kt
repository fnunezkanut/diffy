@file:Suppress("ktlint:standard:function-naming")

package com.github.fnunezkanut.bbcloud.ui.otherprojects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import com.github.fnunezkanut.bbcloud.ui.components.DiffyOutlinedActionButton
import com.github.fnunezkanut.bbcloud.ui.main.ActionButtonOutlines
import com.github.fnunezkanut.bbcloud.ui.main.MainPanelStatusSupport
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListingSource
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsUiState
import com.intellij.openapi.diagnostic.logger
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Log category for the top-level composables of this file; `thisLogger()` would bind to the wrong Compose receiver. */
private object PullRequestsFooterLogCategory

private val LOG = logger<PullRequestsFooterLogCategory>()

private val PROJECT_SOURCES = setOf(PullRequestListingSource.ProjectOpen, PullRequestListingSource.ProjectResolved)

private val WATCHED_SOURCES = setOf(PullRequestListingSource.WatchedOpen, PullRequestListingSource.WatchedResolved)

/**
 * The footer notes of the Pull Requests, in the warning color: the status of the current project when it is not a verified Bitbucket repository, a per-source failure note for
 * the project, watched, and authored sources, the truncated notes of the watched and authored sources, and, once a
 * watched source has loaded, a scope note whose link opens the Watched Projects tab, and at the far right the Create Pull Request button when the
 * current project is a verified Bitbucket repository. The whole footer is skipped when
 * there is nothing to say.
 */
@Composable
internal fun PullRequestsFooter(
    state: PullRequestsUiState,
    currentProject: CurrentProjectLookup?,
    isCreatePullRequestVisible: Boolean,
    onCreatePullRequest: () -> Unit,
    onGoToWatchedProjects: () -> Unit
) {
    val unavailableMessage = (currentProject as? CurrentProjectLookup.Unavailable)?.let { unavailable ->
        unavailableMessageOf(status = unavailable.status)
    }
    val watchedLoaded = state.loadedSources.any { source -> source in WATCHED_SOURCES }
    val projectFailed = state.failedSources.any { source -> source in PROJECT_SOURCES }
    val watchedFailed = state.failedSources.any { source -> source in WATCHED_SOURCES }
    val authoredFailed = PullRequestListingSource.WorkspaceAuthored in state.failedSources
    val watchedTruncated = state.truncatedSources.any { source -> source in WATCHED_SOURCES }
    val authoredTruncated = PullRequestListingSource.WorkspaceAuthored in state.truncatedSources
    val hasNote = unavailableMessage != null ||
        projectFailed ||
        watchedFailed ||
        authoredFailed ||
        watchedTruncated ||
        authoredTruncated ||
        watchedLoaded ||
        isCreatePullRequestVisible
    if (!hasNote) {
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (projectFailed) {
            WarningLine(messageKey = "pullRequests.sourceFailed.project")
        }
        if (watchedFailed) {
            WarningLine(messageKey = "pullRequests.sourceFailed.watched")
        }
        if (authoredFailed) {
            WarningLine(messageKey = "pullRequests.sourceFailed.authored")
        }
        if (watchedTruncated) {
            WarningLine(messageKey = "pullRequests.watched.truncated")
        }
        if (authoredTruncated) {
            WarningLine(messageKey = "otherProjects.openedByMe.truncated")
        }
        // The current project status sits immediately to the right of the scope note; it stands alone when no watched source loaded.
        if (watchedLoaded || unavailableMessage != null || isCreatePullRequestVisible) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (watchedLoaded) {
                    ScopeNoteLink(onGoToWatchedProjects = onGoToWatchedProjects)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (unavailableMessage != null) {
                    Text(text = unavailableMessage, color = MainPanelStatusSupport.mutedForeground())
                }
                if (isCreatePullRequestVisible) {
                    DiffyOutlinedActionButton(
                        text = DiffyBundle.message("main.pullRequests.create"),
                        icon = AllIconsKeys.General.Add,
                        outlineColor = ActionButtonOutlines.green(),
                        enabled = true,
                        onClick = onCreatePullRequest
                    )
                }
            }
        }
    }
}

/** The footer text that says why the IDE project is not a verified Bitbucket repository, or null when no note is needed. */
internal fun unavailableMessageOf(status: ProjectRepositoryStatus): String? {
    return when (status) {
        is ProjectRepositoryStatus.Found -> {
            null
        }
        is ProjectRepositoryStatus.NotFound -> {
            // A project without a Bitbucket repository is expected, so the footer says nothing about it.
            null
        }
        ProjectRepositoryStatus.NoLocalProjectPath -> {
            DiffyBundle.message("main.project.noBasePath")
        }
        is ProjectRepositoryStatus.CheckFailed -> {
            if (status.repositoryWebUrl != null) {
                DiffyBundle.message("main.project.checkFailed", status.repositoryWebUrl)
            } else {
                DiffyBundle.message("main.project.checkFailedGeneric")
            }
        }
    }
}

@Composable
private fun WarningLine(messageKey: String) {
    Text(
        text = DiffyBundle.message(messageKey),
        color = MainPanelStatusSupport.warningForeground()
    )
}

/**
 * The scope note: plain text followed by a "Watched Projects" link that opens the Watched Projects tab.
 */
@Composable
private fun ScopeNoteLink(onGoToWatchedProjects: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = DiffyBundle.message("otherProjects.attention.scopeNote.prefix"),
            color = MainPanelStatusSupport.warningForeground()
        )
        Link(
            text = DiffyBundle.message("otherProjects.attention.scopeNote.watchedProjectsLink"),
            onClick = {
                LOG.info("User opened the Watched Projects tab from the Pull Requests scope note")
                onGoToWatchedProjects()
            }
        )
    }
}