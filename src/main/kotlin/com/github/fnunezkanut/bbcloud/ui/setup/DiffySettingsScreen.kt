@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(
    ExperimentalJewelApi::class,
    ExperimentalFoundationApi::class
)

package com.github.fnunezkanut.bbcloud.ui.setup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod
import com.github.fnunezkanut.bbcloud.ui.otherprojects.WatchedProjectsViewModel
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(
    "com.github.fnunezkanut.bbcloud.ui.setup.DiffySettingsScreen"
)

/**
 * Compose/Jewel implementation of the settings screen hosted by the Settings > Tools > Diffy - Bitbucket PR Review page.
 * [onOpenWatchedProjects] receives the host component and opens the Watched Projects tab of the tool window.
 */
@Composable
fun DiffySettingsScreen(viewModel: DiffySettingsViewModel, onOpenWatchedProjects: (Component) -> Unit) {
    val state by viewModel.state
    val hostComponent = LocalComponent.current

    VerticallyScrollableContainer(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProjectsDirectorySection(
                state = state,
                onSelectDirectory = {
                    LOG.info("User clicked Select Projects Directory on Settings")
                    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle(DiffyBundle.message("setup.projectsDirectory.browse.title"))
                        .withDescription(DiffyBundle.message("setup.projectsDirectory.browse.description"))
                    val selectedFolder = FileChooser.chooseFile(descriptor, hostComponent, null, null)
                    if (selectedFolder != null) {
                        val path = selectedFolder.toNioPath().toString()
                        LOG.info("Select Projects Directory chosen: $path")
                        viewModel.onProjectsDirectoryChosen(path)
                    }
                }
            )
            AuthenticationSection(
                state = state,
                onOAuthLogin = viewModel::onOAuthLoginClicked,
                onOAuthCancel = viewModel::onOAuthLoginCancelClicked
            )
            WatchedProjectsSection(onOpenWatchedProjects = { onOpenWatchedProjects(hostComponent) })
            PullRequestDiffsSection(viewModel = viewModel)
            ResetSection(
                isBusy = state.isBusy,
                onReset = viewModel::onResetClicked
            )
            Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
            FooterContent(footer = state.pluginVersionFooter)
        }
    }
}

/**
 * Renders the Settings footer on two lines. The first has the plugin-name label as a clickable link to the project on
 * GitHub followed by the version as plain text; the stored [footer] is "<label><version suffix>", so the known label
 * prefix is split off to keep a single source of truth in [SetupFooterText]. The second line is the disclaimer.
 */
@Composable
private fun FooterContent(footer: String) {
    val label = SetupFooterText.label()
    val suffix = footer.removePrefix(label)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Link(
                text = label,
                onClick = {
                    LOG.info("User opened the plugin project link ${SetupFooterText.PROJECT_URL}")
                    BrowserUtil.browse(SetupFooterText.PROJECT_URL)
                }
            )
            if (suffix.isNotEmpty()) {
                Text(text = suffix, color = JewelTheme.globalColors.text.info)
            }
        }
        Text(
            text = SetupFooterText.disclaimer(),
            modifier = Modifier.fillMaxWidth(),
            color = JewelTheme.globalColors.text.info
        )
    }
}

/** The "3. Select Watched Projects" section: a link that opens the Watched Projects tab of the tool window. */
@Composable
private fun WatchedProjectsSection(onOpenWatchedProjects: () -> Unit) {
    GroupHeader(text = DiffyBundle.message("setup.section.watchedProjects.title"))
    Link(
        text = DiffyBundle.message("setup.watchedProjects.link", WatchedProjectsViewModel.MAX_WATCHED_PROJECTS),
        onClick = {
            LOG.info("User clicked the Select Watched Projects link on Settings")
            onOpenWatchedProjects()
        }
    )
}

@Composable
private fun PullRequestDiffsSection(viewModel: DiffySettingsViewModel) {
    var enabled by remember { mutableStateOf(viewModel.isEditableSourceSideEnabled()) }
    GroupHeader(text = DiffyBundle.message("setup.section.pullRequestDiffs.title"))
    CheckboxRow(
        text = DiffyBundle.message("settings.editableSourceSideInDiffs.label"),
        checked = enabled,
        onCheckedChange = { checked ->
            enabled = checked
            viewModel.onEditableSourceSideChanged(enabled = checked)
        }
    )
    Text(
        text = DiffyBundle.message("settings.editableSourceSideInDiffs.comment"),
        color = JewelTheme.globalColors.text.info
    )
}

@Composable
private fun ProjectsDirectorySection(state: SettingsUiState, onSelectDirectory: () -> Unit) {
    GroupHeader(text = DiffyBundle.message("setup.section.projectsDirectory.title"))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onSelectDirectory, modifier = handModifier()) {
            ButtonContent(
                icon = AllIconsKeys.Nodes.Folder,
                iconDescription = "Projects directory",
                text = DiffyBundle.message("setup.projectsDirectory.browse.button")
            )
        }
        if (state.projectsDirectory == null) {
            StatusContent(
                icon = AllIconsKeys.General.Warning,
                iconDescription = "Projects directory is not set",
                text = DiffyBundle.message("setup.projectsDirectory.status.notSet"),
                color = JewelTheme.globalColors.text.warning
            )
        } else {
            StatusContent(
                icon = AllIconsKeys.General.InspectionsOK,
                iconDescription = "Projects directory is set",
                text = DiffyBundle.message("setup.projectsDirectory.status.set", state.projectsDirectory),
                color = SUCCESS_COLOR
            )
        }
    }
}

/**
 * The Authentication section: the OAuth button, OAuth login progress with a Cancel link (and a browser-fallback URL
 * when the browser could not be opened), an OAuth status message, and a status label that reflects the working
 * authentication method.
 */
@Composable
private fun AuthenticationSection(state: SettingsUiState, onOAuthLogin: () -> Unit, onOAuthCancel: () -> Unit) {
    GroupHeader(text = DiffyBundle.message("setup.section.authentication.title"))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // OAuth login runs in the application login service; the button is disabled while a login or save is active.
        DefaultButton(
            onClick = onOAuthLogin,
            enabled = !state.isBusy && !state.isOAuthLoginInProgress,
            modifier = handModifier()
        ) {
            ButtonContent(
                icon = AllIconsKeys.General.User,
                iconDescription = "OAuth login",
                text = DiffyBundle.message("setup.authentication.oauth.button")
            )
        }
    }
    if (state.isOAuthLoginInProgress) {
        OAuthLoginProgress(fallbackUrl = state.oauthBrowserFallbackUrl, onCancel = onOAuthCancel)
    }
    if (state.oauthMessage != null) {
        if (state.oauthMessageIsError) {
            StatusContent(
                icon = AllIconsKeys.General.Warning,
                iconDescription = "OAuth error",
                text = state.oauthMessage,
                color = JewelTheme.globalColors.text.warning
            )
        } else {
            StatusContent(
                icon = AllIconsKeys.General.InspectionsOK,
                iconDescription = "OAuth success",
                text = state.oauthMessage,
                color = SUCCESS_COLOR
            )
        }
    }
    AuthenticationStatus(state = state)
}

/** The waiting line and Cancel link while an OAuth login runs, plus the browser-fallback URL and a Copy URL link. */
@Composable
private fun OAuthLoginProgress(fallbackUrl: String?, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = DiffyBundle.message("setup.authentication.oauth.waiting"))
            Link(text = DiffyBundle.message("setup.authentication.oauth.cancel"), onClick = onCancel)
        }
        if (fallbackUrl != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = DiffyBundle.message("setup.authentication.oauth.error.browser", fallbackUrl),
                    color = JewelTheme.globalColors.text.warning,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Link(
                    text = DiffyBundle.message("setup.authentication.oauth.copyUrl"),
                    onClick = {
                        LOG.info("User copied the OAuth authorize URL")
                        CopyPasteManager.getInstance().setContents(StringSelection(fallbackUrl))
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthenticationStatus(state: SettingsUiState) {
    if (state.hasStoredButNotWorkingCredentials) {
        StatusContent(
            icon = AllIconsKeys.General.Warning,
            iconDescription = "Credentials not working",
            text = DiffyBundle.message("setup.authentication.status.notWorking"),
            color = JewelTheme.globalColors.text.warning
        )
        return
    }
    val method = state.authenticationMethod
    val text = AuthenticationStatusText.build(method = method, workspaceSlug = state.workspaceSlug)
    when (method) {
        DiffyAuthenticationMethod.None -> StatusContent(
            icon = AllIconsKeys.General.Warning,
            iconDescription = "Not authenticated",
            text = text,
            color = JewelTheme.globalColors.text.warning
        )

        DiffyAuthenticationMethod.OAuth -> StatusContent(
            icon = AllIconsKeys.General.InspectionsOK,
            iconDescription = "Authenticated with OAuth",
            text = text,
            color = OAUTH_COLOR
        )
    }
}

@Composable
private fun ResetSection(isBusy: Boolean, onReset: () -> Unit) {
    GroupHeader(text = DiffyBundle.message("setup.section.resetPlugin.title"))
    OutlinedButton(onClick = onReset, enabled = !isBusy, modifier = handModifier()) {
        ButtonContent(
            icon = AllIconsKeys.Actions.GC,
            iconDescription = "Reset plugin",
            text = DiffyBundle.message("setup.reset.plugin"),
            color = JewelTheme.globalColors.text.error
        )
    }
}

@Composable
internal fun ButtonContent(icon: IconKey, iconDescription: String, text: String, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(key = icon, contentDescription = iconDescription, tint = color)
        Text(text = text, color = color)
    }
}

@Composable
internal fun StatusContent(icon: IconKey, iconDescription: String, text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(key = icon, contentDescription = iconDescription, tint = color)
        Text(text = text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun chooseWorkspaceWithDialog(slugs: List<String>, preselectedSlug: String): String? {
    return runOnEdtAndWait {
        Messages.showEditableChooseDialog(
            DiffyBundle.message("setup.workspace.resolve.message"),
            DiffyBundle.message("setup.workspace.resolve.title"),
            Messages.getQuestionIcon(),
            slugs.toTypedArray(),
            preselectedSlug,
            WorkspaceSlugValidator(slugs = slugs)
        )
    }
}

internal fun confirmResetWithDialog(): Boolean {
    return runOnEdtAndWait {
        Messages.showYesNoDialog(
            DiffyBundle.message("setup.reset.confirm.message"),
            DiffyBundle.message("setup.reset.confirm.title"),
            Messages.getWarningIcon()
        ) == Messages.YES
    }
}

private fun <T> runOnEdtAndWait(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return action()
    }
    var result: T? = null
    // ModalityState.any() lets the workspace chooser run on the EDT even while another modal dialog is showing;
    // the default modality would queue the runnable until that modal closes.
    ApplicationManager.getApplication().invokeAndWait({ result = action() }, ModalityState.any())
    @Suppress("UNCHECKED_CAST")
    return result as T
}

private class WorkspaceSlugValidator(private val slugs: List<String>) : InputValidator {
    override fun checkInput(inputString: String?): Boolean {
        return slugs.contains(inputString?.trim())
    }

    override fun canClose(inputString: String?): Boolean {
        return checkInput(inputString)
    }
}

/** The clickable-hand hover cursor used on every actionable Settings button, matching the rest of the plugin UI. */
private fun handModifier(): Modifier {
    return Modifier.pointerHoverIcon(PointerIcon.Hand)
}

internal val SUCCESS_COLOR = Color(0xFF4CAF50)

// IntelliJ New UI link blue, used for the OAuth authentication status label when OAuth is the working method.
private val OAUTH_COLOR = Color(0xFF3574F0)