@file:Suppress("ktlint:standard:function-naming")

package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import com.github.fnunezkanut.bbcloud.bitbucket.WorkspaceRepositorySearch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.jewel.bridge.compose
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

internal sealed interface CheckoutProjectDialogChoice {
    data class Selected(val repository: BitbucketWorkspaceRepository) : CheckoutProjectDialogChoice

    data object Cancelled : CheckoutProjectDialogChoice
}

/**
 * Modal with a search box. It opens at once, asks Bitbucket for the repositories that match the typed text, and lists
 * them. Repositories that are already in the Projects Directory are greyed out and cannot be chosen.
 */
internal class CheckoutProjectDialog(
    project: Project,
    private val workspace: String,
    private val projectsDirectory: String,
    existingFolderNames: Set<String>,
    search: suspend (query: String) -> WorkspaceRepositorySearch
) : DialogWrapper(project) {
    private val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: JComponent? = null

    private val viewModel = CheckoutProjectSearchViewModel(
        search = search,
        existingFolderNames = existingFolderNames,
        coroutineScope = dialogScope,
        // ModalityState.any() is required: without it the results would only arrive after this modal dialog closes.
        runOnEdt = { block -> ApplicationManager.getApplication().invokeLater(block, ModalityState.any()) }
    )

    init {
        title = DiffyBundle.message("workspace.watchedProjects.checkout.dialog.title")
        init()
        setOKButtonText(DiffyBundle.message("workspace.watchedProjects.checkout.dialog.ok"))
        isOKActionEnabled = false
    }

    fun showAndGetChoice(): CheckoutProjectDialogChoice {
        show()
        if (exitCode != OK_EXIT_CODE) {
            return CheckoutProjectDialogChoice.Cancelled
        }
        val repository = viewModel.state.value.selected ?: return CheckoutProjectDialogChoice.Cancelled
        return CheckoutProjectDialogChoice.Selected(repository = repository)
    }

    override fun doOKAction() {
        val repository = viewModel.state.value.selected ?: return
        LOG.info("User clicked Checkout in the Checkout Project dialog for ${repository.slug}")
        super.doOKAction()
    }

    override fun createCenterPanel(): JComponent {
        val actions = CheckoutProjectSearchActions(
            onQueryChange = viewModel::onQueryChanged,
            onClear = viewModel::onSearchCleared,
            onSelect = viewModel::onSelect,
            onMoveSelection = viewModel::moveSelection,
            onRetry = viewModel::onRetry,
            onConfirm = ::doOKAction,
            onSelectionAvailable = { available -> isOKActionEnabled = available }
        )
        val composeHost = compose {
            CheckoutProjectSearchScreen(
                state = viewModel.state.value,
                workspace = workspace,
                projectsDirectory = projectsDirectory,
                actions = actions
            )
        }
        composeHost.preferredSize = Dimension(JBUI.scale(DIALOG_WIDTH), JBUI.scale(DIALOG_HEIGHT))
        host = composeHost
        return composeHost
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return host
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }

    override fun dispose() {
        LOG.debug("Disposing the Checkout Project dialog")
        viewModel.dispose()
        dialogScope.cancel()
        super.dispose()
    }

    companion object {
        private val LOG = logger<CheckoutProjectDialog>()
        private const val DIALOG_WIDTH = 560
        private const val DIALOG_HEIGHT = 380
    }
}