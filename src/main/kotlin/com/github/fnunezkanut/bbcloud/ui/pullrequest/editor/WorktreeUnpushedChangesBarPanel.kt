package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.github.fnunezkanut.bbcloud.review.DiffyLocalBranchStateService
import com.github.fnunezkanut.bbcloud.review.DiffyWorktreePullRequestContext
import com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Weighted
import org.jetbrains.jewel.bridge.compose
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The orange "not in the pull request yet" bar stacked above each editor or diff of a pull request work tree.
 *
 * Its weight places it between the blue bar and the review bar. Every panel reads the shared
 * [DiffyLocalBranchStateService], so all open editors agree.
 */
internal class WorktreeUnpushedChangesBarPanel private constructor(private val project: Project) :
    JPanel(BorderLayout()),
    Disposable,
    Weighted {
        private var composePanel: ComposePanel? = null
        private var disposed = false

        init {
            isOpaque = false
        }

        override fun getWeight(): Double {
            return WorktreeBarWeights.UNPUSHED
        }

        @OptIn(ExperimentalComposeUiApi::class)
        override fun dispose() {
            if (disposed) {
                return
            }
            disposed = true
            composePanel?.dispose()
        }

        private fun runAction(actionId: String, control: String) {
            val pullRequestId = DiffyWorktreePullRequestContext.getInstance(project).binding?.pullRequestId
            LOG.info("User clicked '$control' in the orange bar of pull request #$pullRequestId")
            val actionManager = ActionManager.getInstance()
            val action = actionManager.getAction(actionId)
            if (action == null) {
                LOG.warn("The action '$actionId' is not available")
                return
            }
            actionManager.tryToExecute(action, null, this, ACTION_PLACE, true)
        }

        companion object {
            private val LOG = logger<WorktreeUnpushedChangesBarPanel>()

            private const val ACTION_PLACE = "DiffyUnpushedChangesBar"
            private const val COMMIT_ACTION_ID = "CheckinProject"
            private const val PUSH_ACTION_ID = "Vcs.Push"

            fun create(project: Project, relativePath: String?): WorktreeUnpushedChangesBarPanel {
                val bar = WorktreeUnpushedChangesBarPanel(project = project)
                val branchStateService = DiffyLocalBranchStateService.getInstance(project)
                branchStateService.ensureStarted()
                val bannerViewModel = WorktreeTodoBannerViewModel.getInstance(project)
                bannerViewModel.ensureInitialized()
                val actionManager = ActionManager.getInstance()
                val showCommit = actionManager.getAction(COMMIT_ACTION_ID) != null
                val showPush = actionManager.getAction(PUSH_ACTION_ID) != null
                if (!showCommit || !showPush) {
                    LOG.warn("A platform action of the orange bar is missing: commit=$showCommit, push=$showPush")
                }
                var panel: ComposePanel? = null
                val content = compose(config = { panel = this }) {
                    val state by branchStateService.state.collectAsState()
                    val bannerState by bannerViewModel.state
                    WorktreeUnpushedChangesBar(
                        state = state,
                        relativePath = relativePath,
                        isPullRequestOpen = bannerState.isWorktree,
                        showCommit = showCommit,
                        showPush = showPush,
                        onCommit = { bar.runAction(actionId = COMMIT_ACTION_ID, control = "Commit") },
                        onPush = { bar.runAction(actionId = PUSH_ACTION_ID, control = "Push") }
                    )
                }
                bar.composePanel = panel
                bar.add(content, BorderLayout.CENTER)
                return bar
            }
        }
    }