package com.github.fnunezkanut.bbcloud.settings

import com.github.fnunezkanut.bbcloud.project.WatchedProjectMapping
import com.github.fnunezkanut.bbcloud.toolwindow.DiffyToolWindowTab
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffySettingsTest {
    @Test
    fun `watchedProjects - should round-trip repository slugs to watched booleans`() {
        // GIVEN
        val settings = DiffySettings()
        val mappings = listOf(
            WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true),
            WatchedProjectMapping(localFolderPath = "/local/b", remoteRepoSlug = "repo-b", workspace = "my-workspace", watched = false)
        )

        // WHEN
        settings.replaceWatchedProjects(mappings)

        // THEN the flat map keys by repository slug only, so the workspace and local folder path are not persisted and
        // reload empty; the watched booleans survive, sorted by slug.
        settings.watchedProjects shouldBe listOf(
            WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = true),
            WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-b", workspace = "", watched = false)
        )
    }

    @Test
    fun `replaceWatchedProjects - should let the last entry win when a repository slug repeats`() {
        // GIVEN two mappings that share a repository slug (the workspace is no longer a persistence key, so they
        // collide on the single flat key)
        val settings = DiffySettings()
        val mappings = listOf(
            WatchedProjectMapping(localFolderPath = "/local/one", remoteRepoSlug = "shared-repo", workspace = "workspace-b", watched = true),
            WatchedProjectMapping(localFolderPath = "/local/two", remoteRepoSlug = "shared-repo", workspace = "workspace-a", watched = false)
        )

        // WHEN
        settings.replaceWatchedProjects(mappings)

        // THEN a single entry remains, carrying the last-written watched flag.
        settings.watchedProjects shouldBe listOf(
            WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "shared-repo", workspace = "", watched = false)
        )
    }

    @Test
    fun `clearWatchedProjects - should empty the persisted map`() {
        // GIVEN
        val settings = DiffySettings()
        settings.replaceWatchedProjects(
            listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
        )

        // WHEN
        settings.clearWatchedProjects()

        // THEN
        settings.watchedProjects shouldBe emptyList()
    }

    @Test
    fun `replaceWatchedProjects - should default a mapping with no explicit flag to unwatched`() {
        // GIVEN a mapping that does not pass watched, so it takes the unchecked default
        val settings = DiffySettings()
        settings.replaceWatchedProjects(
            listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
        )

        // WHEN
        val stored = settings.watchedProjects.single()

        // THEN
        assertSoftly {
            stored.remoteRepoSlug shouldBe "repo-a"
            stored.watched shouldBe false
        }
    }

    @Test
    fun `replaceRepoAvatarUrls - should store only non-null non-blank hrefs`() {
        // GIVEN
        val settings = DiffySettings()

        // WHEN a mix of a real href, a null, and a blank is replaced in
        settings.replaceRepoAvatarUrls(
            mapOf(
                "repo-a" to "https://cdn.example.com/a.png",
                "repo-b" to null,
                "repo-c" to "   "
            )
        )

        // THEN only the real href survives
        settings.repoAvatarUrls shouldBe mapOf("repo-a" to "https://cdn.example.com/a.png")
    }

    @Test
    fun `replaceRepoAvatarUrls - should replace the whole set, dropping entries not present in the new map`() {
        // GIVEN a stored set
        val settings = DiffySettings()
        settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png", "repo-b" to "https://cdn.example.com/b.png"))

        // WHEN replaced with a smaller set
        settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a-new.png"))

        // THEN repo-b is gone and repo-a is updated
        settings.repoAvatarUrls shouldBe mapOf("repo-a" to "https://cdn.example.com/a-new.png")
    }

    @Test
    fun `mergeRepoAvatarUrls - should upsert hrefs without dropping other entries`() {
        // GIVEN a stored set
        val settings = DiffySettings()
        settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png", "repo-b" to "https://cdn.example.com/b.png"))

        // WHEN merging an update for repo-a, a new repo-c, and a null for repo-d
        settings.mergeRepoAvatarUrls(
            mapOf(
                "repo-a" to "https://cdn.example.com/a-new.png",
                "repo-c" to "https://cdn.example.com/c.png",
                "repo-d" to null
            )
        )

        // THEN repo-b is untouched, repo-a is updated, repo-c is added, repo-d is skipped
        settings.repoAvatarUrls shouldBe mapOf(
            "repo-a" to "https://cdn.example.com/a-new.png",
            "repo-b" to "https://cdn.example.com/b.png",
            "repo-c" to "https://cdn.example.com/c.png"
        )
    }

    @Test
    fun `clearWatchedProjects - should empty both the watched map and the avatar URL map`() {
        // GIVEN both maps populated
        val settings = DiffySettings()
        settings.replaceWatchedProjects(
            listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
        )
        settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png"))

        // WHEN
        settings.clearWatchedProjects()

        // THEN
        assertSoftly {
            settings.watchedProjects shouldBe emptyList()
            settings.repoAvatarUrls shouldBe emptyMap()
        }
    }

    @Test
    fun `loadState - should keep workspaceSlug and projectsDirectory when a legacy two-level watchedProjects element is present`() {
        // GIVEN a persisted XML written by an earlier build, where watchedProjects is the old two-level shape keyed by
        // workspace. Deserializing that nested WorkspaceWatchedProjectsState into the new flat Boolean map used to
        // fault and corrupt the whole component load, silently dropping workspaceSlug and projectsDirectory with it.
        // The option is now named watchedRepos, so the stale watchedProjects element must simply be ignored.
        val legacyXml =
            """
            <component name="DiffySettings">
              <option name="organizationUrl" />
              <option name="projectsDirectory" value="/home/dev/Projects" />
              <option name="watchedProjects">
                <map>
                  <entry key="sample-workspace">
                    <value>
                      <WorkspaceWatchedProjectsState />
                    </value>
                  </entry>
                </map>
              </option>
              <option name="workspaceSlug" value="sample-workspace" />
            </component>
            """.trimIndent()
        val element = JDOMUtil.load(legacyXml)

        // WHEN the persisted element is deserialized into the current State shape
        val state = XmlSerializer.deserialize(element, DiffySettings.State::class.java)

        // THEN the sibling settings survive the legacy element, and the renamed flat map starts empty (rebuilt on the
        // next rescan rather than migrated).
        assertSoftly {
            state.workspaceSlug shouldBe "sample-workspace"
            state.projectsDirectory shouldBe "/home/dev/Projects"
            state.watchedRepos.isEmpty() shouldBe true
        }
    }

    @Test
    fun `pullRequestsListFilter - should round-trip through XmlSerializer`() {
        // GIVEN a settings instance with non-default filter values saved
        val settings = DiffySettings()
        val saved = PullRequestListFilter(
            excludeResolved = false,
            excludeApprovedByMe = true,
            excludeDrafts = false,
            userScope = PullRequestUserScope.Me,
            projectScope = PullRequestProjectScope.CurrentProject
        )
        settings.savePullRequestsListFilter(filter = saved)

        // WHEN the state is serialized to XML and deserialized back
        val serialized = XmlSerializer.serialize(settings.state)
        val roundTripped = XmlSerializer.deserialize(serialized, DiffySettings.State::class.java)
        val reloaded = DiffySettings()
        reloaded.loadState(roundTripped)

        // THEN the filter comes back with the saved values
        reloaded.pullRequestsListFilter() shouldBe saved
    }

    @Test
    fun `pullRequestsListFilter - should fall back to AssignedToMe for an unknown stored user scope`() {
        // GIVEN a stored scope name that this build does not know, as a newer or hand-edited settings file might hold
        val settings = DiffySettings()
        settings.state.pullRequestsAuthorScope = "SomethingNew"

        // WHEN the filter is read
        val filter = settings.pullRequestsListFilter()

        // THEN the unknown name falls back to AssignedToMe
        filter.userScope shouldBe PullRequestUserScope.AssignedToMe
    }

    @Test
    fun `pullRequestsListFilter - should ignore options stored under the legacy inbox names`() {
        // GIVEN persisted XML that only holds the former inbox option names
        val legacyXml =
            """
            <State>
              <option name="inboxExcludeResolvedPullRequests" value="false" />
              <option name="inboxExcludeApprovedByMePullRequests" value="true" />
              <option name="inboxExcludeDraftPullRequests" value="false" />
              <option name="inboxPullRequestAuthorScope" value="Me" />
              <option name="inboxPullRequestProjectScope" value="CurrentProject" />
            </State>
            """.trimIndent()
        val state = XmlSerializer.deserialize(JDOMUtil.load(legacyXml), DiffySettings.State::class.java)
        val settings = DiffySettings()
        settings.loadState(state)

        // WHEN the filter is read
        val filter = settings.pullRequestsListFilter()

        // THEN the legacy values are not picked up and the defaults apply
        filter shouldBe PullRequestListFilter(
            excludeResolved = true,
            excludeApprovedByMe = false,
            excludeDrafts = true,
            userScope = PullRequestUserScope.AssignedToMe,
            projectScope = PullRequestProjectScope.AnyProject
        )
    }

    @Test
    fun `pullRequestsListFilter - should default on a fresh state`() {
        // GIVEN
        val settings = DiffySettings()

        // WHEN
        val filter = settings.pullRequestsListFilter()

        // THEN
        filter shouldBe PullRequestListFilter(
            excludeResolved = true,
            excludeApprovedByMe = false,
            excludeDrafts = true,
            userScope = PullRequestUserScope.AssignedToMe,
            projectScope = PullRequestProjectScope.AnyProject
        )
    }

    @Test
    fun `savePullRequestsListFilter - should round-trip all five values`() {
        // GIVEN
        val settings = DiffySettings()
        val saved = PullRequestListFilter(
            excludeResolved = false,
            excludeApprovedByMe = true,
            excludeDrafts = false,
            userScope = PullRequestUserScope.AssignedToMe,
            projectScope = PullRequestProjectScope.CurrentProject
        )

        // WHEN
        settings.savePullRequestsListFilter(filter = saved)

        // THEN
        settings.pullRequestsListFilter() shouldBe saved
    }

    @Test
    fun `pullRequestsListFilter - should fall back to AnyProject for an unknown stored project scope`() {
        // GIVEN
        val settings = DiffySettings()
        settings.state.pullRequestsProjectScope = "SomethingElse"

        // WHEN
        val filter = settings.pullRequestsListFilter()

        // THEN
        filter.projectScope shouldBe PullRequestProjectScope.AnyProject
    }

    @Test
    fun `lastSelectedTab - should default to Pull Requests`() {
        // GIVEN
        val settings = DiffySettings()

        // WHEN
        val result = settings.lastSelectedTab

        // THEN
        result shouldBe DiffyToolWindowTab.PullRequests
    }

    @Test
    fun `lastSelectedTab - should round-trip every tab`() {
        // GIVEN
        val settings = DiffySettings()

        for (tab in DiffyToolWindowTab.entries) {
            // WHEN
            settings.lastSelectedTab = tab

            // THEN
            settings.lastSelectedTab shouldBe tab
        }
    }

    @Test
    fun `lastSelectedTab - should fall back to Pull Requests when the stored name is unknown or null`() {
        // GIVEN
        val settings = DiffySettings()

        // WHEN
        settings.state.lastSelectedTab = "Bogus"
        val unknown = settings.lastSelectedTab
        settings.state.lastSelectedTab = null
        val missing = settings.lastSelectedTab

        // THEN
        assertSoftly {
            unknown shouldBe DiffyToolWindowTab.PullRequests
            missing shouldBe DiffyToolWindowTab.PullRequests
        }
    }
}