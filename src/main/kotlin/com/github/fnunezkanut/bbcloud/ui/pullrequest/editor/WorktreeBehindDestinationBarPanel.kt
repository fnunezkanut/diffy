package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import com.github.fnunezkanut.bbcloud.review.WorktreeBehindDestinationBarViewModel
import com.github.fnunezkanut.bbcloud.review.WorktreeLocalBehindRemoteBarViewModel
import com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel
import com.github.fnunezkanut.bbcloud.review.shouldShowRebaseLink
import com.github.fnunezkanut.bbcloud.ui.air.AirChatBridge
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Weighted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.jewel.bridge.compose
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The yellow "pull request is behind its destination branch" bar stacked above each editor or diff of a pull request
 * work tree.
 *
 * Its weight places it between the red bar and the orange bar. Every panel reads the shared
 * [WorktreeBehindDestinationBarViewModel], so all open editors and diffs agree.
 *
 * Editors add and remove the bar through their notification provider. A diff cannot collect its notifications again,
 * so a panel created with `toggleVisibility` hides itself while there is nothing to show, and then takes no height.
 */
internal class WorktreeBehindDestinationBarPanel private constructor(private val toggleScope: CoroutineScope) :
    JPanel(BorderLayout()),
    Disposable,
    Weighted {
        private var composePanel: ComposePanel? = null
        private var disposed = false

        init {
            isOpaque = false
        }

        override fun getWeight(): Double {
            return WorktreeBarWeights.BEHIND_DESTINATION
        }

        @OptIn(ExperimentalComposeUiApi::class)
        override fun dispose() {
            if (disposed) {
                return
            }
            disposed = true
            toggleScope.cancel()
            composePanel?.dispose()
        }

        private fun showOrHide(visible: Boolean) {
            if (disposed || isVisible == visible) {
                return
            }
            isVisible = visible
            revalidate()
            repaint()
        }

        companion object {
            /**
             * Creates a bar for [project]. With [toggleVisibility] the bar shows and hides itself, as a diff needs.
             */
            fun create(project: Project, toggleVisibility: Boolean): WorktreeBehindDestinationBarPanel {
                val barViewModel = WorktreeBehindDestinationBarViewModel.getInstance(project)
                barViewModel.ensureStarted()
                val redBarViewModel = WorktreeLocalBehindRemoteBarViewModel.getInstance(project)
                redBarViewModel.ensureStarted()
                val bannerViewModel = WorktreeTodoBannerViewModel.getInstance(project)
                bannerViewModel.ensureInitialized()
                val bar = WorktreeBehindDestinationBarPanel(toggleScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT))
                var panel: ComposePanel? = null
                val content = compose(config = { panel = this }) {
                    val state by barViewModel.state.collectAsState()
                    val redBarState by redBarViewModel.state.collectAsState()
                    val bannerState by bannerViewModel.state
                    val isAirAvailable = remember { AirChatBridge.isAvailable() }
                    WorktreeBehindDestinationBar(
                        state = state,
                        isPullRequestOpen = bannerState.isWorktree,
                        showRebaseLink = shouldShowRebaseLink(
                            isAirAvailable = isAirAvailable,
                            isRedBarVisible = redBarState != null && bannerState.isWorktree
                        ),
                        onRebaseWithAi = { barViewModel.rebaseWithAi() }
                    )
                }
                bar.composePanel = panel
                bar.add(content, BorderLayout.CENTER)
                if (toggleVisibility) {
                    bar.isVisible = barViewModel.isVisible()
                    barViewModel.visible.onEach { visible -> bar.showOrHide(visible) }.launchIn(bar.toggleScope)
                }
                return bar
            }
        }
    }