package com.github.fnunezkanut.bbcloud.plugin

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the plugin descriptor against the duplicate service registration that made the IDE log
 * `Conflicting component name 'DiffySettings'` and fail to build the tool window at startup. Two
 * `<applicationService>` entries pointing at the same implementation register two light services with
 * the same `@State` name, which the platform rejects at runtime only, so it is checked here instead.
 */
@Tag("unit")
class DiffyPluginDescriptorTest {
    @Test
    fun `plugin xml - should declare each service implementation only once`() {
        // GIVEN
        val descriptor = pluginDescriptor()

        // WHEN
        val implementations = serviceRegistrations(descriptor).map { it.implementation }

        // THEN
        assertSoftly {
            val duplicates = implementations.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            duplicates shouldBe emptySet()
            implementations shouldContainExactlyInAnyOrder listOf(
                "com.github.fnunezkanut.bbcloud.settings.DiffySettings",
                "com.github.fnunezkanut.bbcloud.review.DiffyKnownUsersState",
                "com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState",
                "com.github.fnunezkanut.bbcloud.external.DiffyNotificationsState",
                "com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.DiffyPullRequestDeclineCoordinator",
                "com.github.fnunezkanut.bbcloud.credentials.PasswordSafeDiffyCredentialStore",
                "com.github.fnunezkanut.bbcloud.auth.DiffyAuthService",
                "com.github.fnunezkanut.bbcloud.auth.DiffyOAuthLoginService",
                "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotStore",
                "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotCacheService",
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestReviewState",
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestViewedFiles",
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestViewedThreads",
                "com.github.fnunezkanut.bbcloud.review.DiffyWorktreePullRequestContext",
                "com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel",
                "com.github.fnunezkanut.bbcloud.review.WorktreePullRequestPageReloader",
                "com.github.fnunezkanut.bbcloud.git.DiffyPrBranchSyncService",
                "com.github.fnunezkanut.bbcloud.ui.diff.DiffyPullRequestDiffCommentService",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyWorktreesHelpEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyNotificationsHelpEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.markdowndiff.DiffyMarkdownDiffEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.DiffyToolWindowPanel"
            )
        }
    }

    @Test
    fun `plugin xml - should declare an interface only when the registration names one`() {
        // GIVEN
        val descriptor = pluginDescriptor()

        // WHEN
        val registrations = serviceRegistrations(descriptor)

        // THEN
        assertSoftly {
            val interfaces = registrations.mapNotNull { it.serviceInterface }
            interfaces shouldContainExactlyInAnyOrder listOf(
                "com.github.fnunezkanut.bbcloud.credentials.DiffyCredentialStore",
                "com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider"
            )
            registrations.filter { it.serviceInterface == null }.map { it.implementation } shouldContainExactly listOf(
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestViewedThreads",
                "com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.DiffyPullRequestDeclineCoordinator",
                "com.github.fnunezkanut.bbcloud.settings.DiffySettings",
                "com.github.fnunezkanut.bbcloud.review.DiffyKnownUsersState",
                "com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState",
                "com.github.fnunezkanut.bbcloud.external.DiffyNotificationsState",
                "com.github.fnunezkanut.bbcloud.auth.DiffyOAuthLoginService",
                "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotStore",
                "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotCacheService",
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestReviewState",
                "com.github.fnunezkanut.bbcloud.review.DiffyPullRequestViewedFiles",
                "com.github.fnunezkanut.bbcloud.review.DiffyWorktreePullRequestContext",
                "com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel",
                "com.github.fnunezkanut.bbcloud.review.WorktreePullRequestPageReloader",
                "com.github.fnunezkanut.bbcloud.git.DiffyPrBranchSyncService",
                "com.github.fnunezkanut.bbcloud.ui.diff.DiffyPullRequestDiffCommentService",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyWorktreesHelpEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyNotificationsHelpEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.markdowndiff.DiffyMarkdownDiffEditorOpener",
                "com.github.fnunezkanut.bbcloud.ui.DiffyToolWindowPanel"
            )
        }
    }

    private data class ServiceRegistration(val serviceInterface: String?, val implementation: String)

    /**
     * Bundled plugin dependencies ship their own `META-INF/plugin.xml`, and the plugin's own descriptor is
     * reachable both from the resources output directory and from the packaged jar, so every descriptor on the
     * classpath is scanned and the bbcloud one is selected by its `<id>` rather than by resource order.
     */
    private fun pluginDescriptor(): Element {
        val descriptors = javaClass.classLoader.getResources(DESCRIPTOR_RESOURCE).toList().filter { url ->
            url.openStream().use { stream -> readRoot(stream).pluginId() == PLUGIN_ID }
        }
        check(descriptors.isNotEmpty()) { "$DESCRIPTOR_RESOURCE with id '$PLUGIN_ID' is not on the test classpath" }
        val url = descriptors.first()
        return url.openStream().use { stream -> readRoot(stream) }
    }

    private fun readRoot(stream: InputStream): Element {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream).documentElement
    }

    private fun Element.pluginId(): String = getElementsByTagName("id").item(0).textContent.trim()

    @Test
    fun `plugin xml - should register the pull request editor infrastructure`() {
        // GIVEN
        val descriptor = pluginDescriptor()

        // WHEN
        val extensions = descriptor.getElementsByTagName("extensions").item(0) as Element
        val virtualFileSystems = elements(extensions, "virtualFileSystem")
        val fileEditorProviders = elements(extensions, "fileEditorProvider")
        val titleProviders = elements(extensions, "editorTabTitleProvider")

        // THEN
        assertSoftly {
            virtualFileSystems.map { it.getAttribute("key") } shouldContainExactly listOf("diffypr")
            virtualFileSystems.map { it.getAttribute("implementationClass") } shouldContainExactly listOf(
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestVirtualFileSystem"
            )
            fileEditorProviders.map { it.getAttribute("implementationClass") } shouldContainExactly listOf(
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestFileEditorProvider",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestFileEditorProvider",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyWorktreesHelpFileEditorProvider",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyNotificationsHelpFileEditorProvider",
                "com.github.fnunezkanut.bbcloud.ui.markdowndiff.DiffyMarkdownDiffFileEditorProvider"
            )
            titleProviders.map { it.getAttribute("implementationClass") } shouldContainExactly listOf(
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestEditorTabTitleProvider"
            )
        }
    }

    @Test
    fun `plugin xml - should register the post startup activities`() {
        // GIVEN
        val descriptor = pluginDescriptor()

        // WHEN
        val extensions = descriptor.getElementsByTagName("extensions").item(0) as Element
        val activities = elements(extensions, "postStartupActivity")

        // THEN
        assertSoftly {
            activities.map { it.getAttribute("implementation") } shouldContainExactly listOf(
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.editor.DiffyPullRequestEditorStartupActivity",
                "com.github.fnunezkanut.bbcloud.review.DiffyWorktreePullRequestContextStartupActivity",
                "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotCacheStartupActivity",
                "com.github.fnunezkanut.bbcloud.ui.pullrequest.projectView.DiffyPullRequestProjectViewRefreshStartupActivity"
            )
        }
    }

    @Test
    fun `plugin xml - should register the REST open endpoint as an http request handler`() {
        // GIVEN
        val descriptor = pluginDescriptor()

        // WHEN
        val extensions = descriptor.getElementsByTagName("extensions").item(0) as Element
        val handlers = elements(extensions, "httpRequestHandler")

        // THEN
        handlers.map { it.getAttribute("implementation") } shouldContainExactly listOf(
            "com.github.fnunezkanut.bbcloud.external.DiffyOpenPullRequestRestService",
            "com.github.fnunezkanut.bbcloud.external.DiffyBrowserSnapshotRestService",
            "com.github.fnunezkanut.bbcloud.external.DiffyWorkspaceRestService"
        )
    }

    private fun elements(parent: Element, tagName: String): List<Element> {
        val children = parent.childNodes
        return (0 until children.length).mapNotNull { index ->
            children.item(index).takeIf { node -> node is Element && node.tagName == tagName } as? Element
        }
    }

    private fun serviceRegistrations(root: Element): List<ServiceRegistration> {
        val services = mutableListOf<ServiceRegistration>()
        val extensions = root.getElementsByTagName("extensions").item(0) as Element
        val children = extensions.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node !is Element) {
                continue
            }
            if (node.tagName != "applicationService" && node.tagName != "projectService") {
                continue
            }
            services.add(
                ServiceRegistration(
                    serviceInterface = node.getAttribute("serviceInterface").takeIf { it.isNotBlank() },
                    implementation = node.getAttribute("serviceImplementation")
                )
            )
        }
        return services
    }

    companion object {
        private const val DESCRIPTOR_RESOURCE = "META-INF/plugin.xml"
        private const val PLUGIN_ID = "com.github.fnunezkanut.bbcloud"
    }
}