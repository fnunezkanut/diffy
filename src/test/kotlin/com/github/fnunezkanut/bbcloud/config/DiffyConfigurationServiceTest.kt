package com.github.fnunezkanut.bbcloud.config

import com.github.fnunezkanut.bbcloud.auth.BitbucketCredentialCheckClient
import com.github.fnunezkanut.bbcloud.auth.CredentialCheckResult
import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.auth.DiffyCredentialValidator
import com.github.fnunezkanut.bbcloud.auth.OAuthTokens
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspace
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceClient
import com.github.fnunezkanut.bbcloud.bitbucket.WorkspaceListResult
import com.github.fnunezkanut.bbcloud.credentials.DiffyCredentialStore
import com.github.fnunezkanut.bbcloud.project.DiffyWatchedProjectsDiscoveryService
import com.github.fnunezkanut.bbcloud.project.WatchedProjectMapping
import com.github.fnunezkanut.bbcloud.project.WatchedProjectsDiscoveryResult
import com.github.fnunezkanut.bbcloud.settings.DiffySettings
import com.github.fnunezkanut.bbcloud.ui.otherprojects.WatchedProjectsViewModel
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Clock

@Tag("unit")
class DiffyConfigurationServiceTest {
    /** A real existing directory, injected per test, so the directory-validity checks in isConfigured pass. */
    @TempDir
    lateinit var projectsDir: Path

    @Test
    fun `isConfigured - should be false when slug or login missing`(): Unit =
        runBlocking {
            val settings = settings()
            val store = FakeCredentialStore(hasLogin = false)
            val service = service(settings, store)

            service.isConfigured() shouldBe false

            // Slug + a valid projects directory, but still no OAuth login, is not configured.
            settings.workspaceSlug = "my-workspace"
            settings.projectsDirectory = projectsDir.toString()
            service.isConfigured() shouldBe false

            // OAuth login stored but a blank slug is not configured.
            store.saveOAuthTokens(oauthTokens())
            settings.workspaceSlug = "  "
            service.isConfigured() shouldBe false
        }

    @Test
    fun `isConfigured - should be true when slug login and projects directory are set and valid`(): Unit =
        runBlocking {
            // GIVEN slug, OAuth login, and a valid projects directory (the work tree root is derived, not required to exist).
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.toString()
            val store = FakeCredentialStore(hasLogin = true)
            val service = service(settings, store)

            // WHEN / THEN
            service.isConfigured() shouldBe true
        }

    @Test
    fun `isConfigured - should be false when projects directory not set`(): Unit =
        runBlocking {
            // GIVEN slug and token, but no projects directory
            val settings = settings(workspaceSlug = "my-workspace")
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN / THEN
            service.isConfigured() shouldBe false
        }

    @Test
    fun `isConfigured - should be false when projects directory does not exist`(): Unit =
        runBlocking {
            // GIVEN a projects directory pointing at a path that is not a directory on disk
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.resolve("missing").toString()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN / THEN
            service.isConfigured() shouldBe false
        }

    @Test
    fun `isConfigured - should be false when no OAuth login is stored`(): Unit =
        runBlocking {
            // GIVEN slug and a valid projects directory, but no stored token
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.toString()
            val service = service(settings, FakeCredentialStore(hasLogin = false))

            // WHEN / THEN
            service.isConfigured() shouldBe false
        }

    @Test
    fun `isConfigured - should be false when workspace slug is empty`(): Unit =
        runBlocking {
            // GIVEN token and a valid projects directory, but no workspace slug
            val settings = settings()
            settings.projectsDirectory = projectsDir.toString()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN / THEN
            service.isConfigured() shouldBe false
        }

    @Test
    fun `isConfigured - should be true with working OAuth and a valid directory`(): Unit =
        runBlocking {
            // GIVEN slug, a valid directory, and OAuth tokens
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.toString()
            val store = FakeCredentialStore(hasLogin = false)
            store.oauthTokens = oauthTokens()
            val service = service(settings, store)

            // WHEN / THEN
            service.isConfigured() shouldBe true
        }

    @Test
    fun `isConfigured - should not call the validator when the slug is missing`(): Unit =
        runBlocking {
            // GIVEN no slug, a valid directory, and a check client that would fail the test if called
            val settings = settings()
            settings.projectsDirectory = projectsDir.toString()
            val store = FakeCredentialStore(hasLogin = true)
            var checkCalls = 0
            val countingCheckClient = object : BitbucketCredentialCheckClient {
                override suspend fun check(bearerToken: String): CredentialCheckResult {
                    checkCalls++
                    return CredentialCheckResult.Valid
                }
            }
            val provider = FakeAccessTokenProvider(store = store)
            val validator = DiffyCredentialValidator(
                credentialStore = store,
                accessTokenProvider = provider,
                checkClient = countingCheckClient,
                clock = Clock.System,
                ttl = kotlin.time.Duration.ZERO
            )
            val service = service(settings, store, provider = provider, validator = validator)

            // WHEN / THEN the cheap slug check short-circuits before any online check
            service.isConfigured() shouldBe false
            checkCalls shouldBe 0
        }

    @Test
    fun `saveOAuthSetup - should reject an invalid workspace slug`(): Unit =
        runBlocking {
            val settings = settings()
            val store = FakeCredentialStore(hasLogin = false)
            val service = service(settings, store)

            val result = service.saveOAuthSetup(workspaceSlug = "https://bitbucket.org/x/", tokens = oauthTokens())

            assertSoftly {
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<DiffyConfigurationService.SaveException.WorkspaceRequired>()
                store.oauthTokens shouldBe null
            }
        }

    @Test
    fun `saveOAuthSetup - should store the tokens and slug and delete the legacy API token`(): Unit =
        runBlocking {
            val settings = settings()
            val store = FakeCredentialStore(hasLogin = false)
            store.legacyApiTokenPresent = true
            val service = service(settings, store)
            val tokens = oauthTokens()

            val result = service.saveOAuthSetup(workspaceSlug = "my-workspace", tokens = tokens)

            assertSoftly {
                result.isSuccess shouldBe true
                settings.workspaceSlug shouldBe "my-workspace"
                store.oauthTokens shouldBe tokens
                // A token stored by an older plugin version is no longer used, so it is removed.
                store.legacyApiTokenPresent shouldBe false
            }
        }

    @Test
    fun `saveOAuthSetup - a slug change should clear and rescan watched projects`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "old")
            settings.projectsDirectory = projectsDir.toString()
            val store = FakeCredentialStore(hasLogin = false)
            val discoveryService = FakeDiscoveryService(
                result = WatchedProjectsDiscoveryResult.Success(mappings = emptyList(), avatarUrlBySlug = emptyMap())
            )
            val service = service(settings, store, discoveryService = discoveryService)

            service.saveOAuthSetup(workspaceSlug = "new", tokens = oauthTokens())

            assertSoftly {
                settings.workspaceSlug shouldBe "new"
                discoveryService.discoverInvocations shouldBe 1
            }
        }

    @Test
    fun `saveOAuthSetup - the same slug should not clear watched projects`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "same")
            settings.projectsDirectory = projectsDir.toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(workspace = "same", remoteRepoSlug = "repo", watched = true, localFolderPath = ""))
            )
            val store = FakeCredentialStore(hasLogin = false)
            val discoveryService = FakeDiscoveryService()
            val service = service(settings, store, discoveryService = discoveryService)

            service.saveOAuthSetup(workspaceSlug = "same", tokens = oauthTokens())

            assertSoftly {
                // No rescan, and the watched map is preserved on a same-workspace credential change.
                discoveryService.discoverInvocations shouldBe 0
                settings.watchedProjects.map { it.remoteRepoSlug } shouldBe listOf("repo")
            }
        }

    @Test
    fun `resetAll - should clear the OAuth tokens`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            val store = FakeCredentialStore(hasLogin = true)
            store.legacyApiTokenPresent = true
            val service = service(settings, store)

            service.resetAll()

            assertSoftly {
                store.oauthTokens shouldBe null
                store.clearOAuthInvocations shouldBe 1
                store.legacyApiTokenPresent shouldBe false
                settings.workspaceSlug shouldBe ""
            }
        }

    @Test
    fun `resetAll - should turn off the main branch auto-update`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            settings.autoUpdateMainBranches = true
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            service.resetAll()

            // THEN
            settings.autoUpdateMainBranches shouldBe false
        }

    private fun oauthTokens(): OAuthTokens {
        return OAuthTokens(
            accessToken = "oauth-access",
            refreshToken = "oauth-refresh",
            expiresAt = Clock.System.now() + kotlin.time.Duration.parse("2h")
        )
    }

    @Test
    fun `organizationUrlOrNull - should derive the URL from the slug`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            service.organizationUrlOrNull() shouldBe "https://bitbucket.org/my-workspace/"
        }

    @Test
    fun `organizationUrlOrNull - should be null without a slug`(): Unit =
        runBlocking {
            service(settings(), FakeCredentialStore(hasLogin = true)).organizationUrlOrNull() shouldBe null
        }

    @Test
    fun `workspaceSlugOrNull - should migrate a legacy organization url once`(): Unit =
        runBlocking {
            val settings = settings(organizationUrl = "https://bitbucket.org/legacy-workspace/")
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            assertSoftly {
                service.workspaceSlugOrNull() shouldBe "legacy-workspace"
                service.organizationUrlOrNull() shouldBe "https://bitbucket.org/legacy-workspace/"
                settings.workspaceSlug shouldBe "legacy-workspace"
            }
        }

    @Test
    fun `workspaceSlugOrNull - should prefer the stored slug over the legacy url`(): Unit =
        runBlocking {
            val settings = settings(
                workspaceSlug = "current",
                organizationUrl = "https://bitbucket.org/legacy/"
            )

            service(settings, FakeCredentialStore(hasLogin = true)).workspaceSlugOrNull() shouldBe "current"
        }

    @Test
    fun `saveProjectsDirectory - should store the normalized absolute path`(): Unit =
        runBlocking {
            val settings = settings()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            service.saveProjectsDirectory(absolutePath = "  ${RELATIVE_ROOT}/bbcloud/../bbcloud  ")

            val expected = Paths.get(RELATIVE_ROOT, "bbcloud").toAbsolutePath().normalize().toString()
            assertSoftly {
                settings.projectsDirectory shouldBe expected
                service.projectsDirectoryOrNull() shouldBe expected
            }
        }

    @Test
    fun `saveProjectsDirectory - should empty the watched projects before a rebuild`(): Unit =
        runBlocking {
            // GIVEN watched entries from a previous directory
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true))
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN a new projects directory is chosen
            service.saveProjectsDirectory(absolutePath = projectsDir.toString())

            // THEN the watched map is emptied; a clear-first rescan (driven by the UI) rebuilds it afterwards.
            settings.watchedProjects shouldBe emptyList()
        }

    @Test
    fun `projectsDirectoryOrNull - should be null when unset or blank`(): Unit =
        runBlocking {
            val settings = settings()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            service.projectsDirectoryOrNull() shouldBe null

            settings.projectsDirectory = "   "
            service.projectsDirectoryOrNull() shouldBe null
        }

    @Test
    fun `resetAll - should clear the login slug organization url projects directory and watched projects`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(
                workspaceSlug = "my-workspace",
                organizationUrl = "https://bitbucket.org/my-workspace/"
            )
            settings.projectsDirectory = Paths.get(RELATIVE_ROOT, "bbcloud").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val store = FakeCredentialStore(hasLogin = true)
            val service = service(settings, store)

            // WHEN
            service.resetAll()

            // THEN
            assertSoftly {
                store.getOAuthTokens() shouldBe null
                store.clearOAuthInvocations shouldBe 1
                settings.workspaceSlug shouldBe ""
                settings.organizationUrl shouldBe ""
                service.workspaceSlugOrNull() shouldBe null
                service.projectsDirectoryOrNull() shouldBe null
                service.workTreeDirectoryOrNull() shouldBe null
                settings.watchedProjects shouldBe emptyList()
                settings.repoAvatarUrls shouldBe emptyMap()
                service.organizationUrlOrNull() shouldBe null
                service.isConfigured() shouldBe false
            }
        }

    @Test
    fun `rescanWatchedProjects - clearFirst should replace the persisted avatar URL map`(): Unit =
        runBlocking {
            // GIVEN a stored avatar URL from a previous config and a scan that reports a fresh set
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceRepoAvatarUrls(mapOf("old-repo" to "https://cdn.example.com/old.png"))
            val scanned = listOf(
                WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            )
            val discoveryService = FakeDiscoveryService(
                result = WatchedProjectsDiscoveryResult.Success(
                    mappings = scanned,
                    avatarUrlBySlug = mapOf("repo-a" to "https://cdn.example.com/a.png")
                )
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a clear-first rescan runs
            service.rescanWatchedProjects(clearFirst = true)

            // THEN the old URL is gone and only the fresh set remains
            settings.repoAvatarUrls shouldBe mapOf("repo-a" to "https://cdn.example.com/a.png")
        }

    @Test
    fun `rescanWatchedProjects - Sync should merge the persisted avatar URL map`(): Unit =
        runBlocking {
            // GIVEN a stored avatar URL for repo-b that the scan does not touch
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceRepoAvatarUrls(mapOf("repo-b" to "https://cdn.example.com/b.png"))
            val scanned = listOf(
                WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            )
            val discoveryService = FakeDiscoveryService(
                result = WatchedProjectsDiscoveryResult.Success(
                    mappings = scanned,
                    avatarUrlBySlug = mapOf("repo-a" to "https://cdn.example.com/a.png")
                )
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a manual Sync (clearFirst = false) runs
            service.rescanWatchedProjects()

            // THEN repo-a is added and repo-b is preserved
            settings.repoAvatarUrls shouldBe mapOf(
                "repo-a" to "https://cdn.example.com/a.png",
                "repo-b" to "https://cdn.example.com/b.png"
            )
        }

    @Test
    fun `rescanWatchedProjects - Sync VerifyFailed should preserve the persisted avatar URL map`(): Unit =
        runBlocking {
            // GIVEN a stored avatar URL and a scan that fails to verify
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png"))
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.VerifyFailed)
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a manual Sync (clearFirst = false) hits a transient verify failure
            service.rescanWatchedProjects().shouldBeInstanceOf<WatchedProjectsDiscoveryResult.VerifyFailed>()

            // THEN the previous avatar URL is kept, mirroring the watched-flag carry-over
            settings.repoAvatarUrls shouldBe mapOf("repo-a" to "https://cdn.example.com/a.png")
        }

    @Test
    fun `repoAvatarUrlsForCurrentWorkspace - should return the persisted map without a Bitbucket call`(): Unit =
        runBlocking {
            // GIVEN a stored avatar URL map and a discovery service that must never be invoked by this read
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png"))
            val discoveryService = FakeDiscoveryService()
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val urls = service.repoAvatarUrlsForCurrentWorkspace()

            // THEN the persisted map is returned and no discovery (which drives the repo-existence call) happened
            assertSoftly {
                urls shouldBe mapOf("repo-a" to "https://cdn.example.com/a.png")
                discoveryService.discoverInvocations shouldBe 0
            }
        }

    @Test
    fun `localCheckoutPathForRepo - should return the resolver path without a Bitbucket call`(): Unit =
        runBlocking {
            // GIVEN a configured workspace and projects directory, and a resolver that reports a checkout path
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.toString()
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/repo-a")
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val path = service.localCheckoutPathForRepo(repoSlug = "repo-a")

            // THEN the resolver's path is returned, it was asked about repo-a, and no discovery (the repo-existence
            // call) ran
            assertSoftly {
                path shouldBe "/projects/repo-a"
                discoveryService.resolveInvocations shouldBe 1
                discoveryService.resolvedRepoSlug shouldBe "repo-a"
                discoveryService.requestedWorkspaceSlug shouldBe "my-workspace"
                discoveryService.discoverInvocations shouldBe 0
            }
        }

    @Test
    fun `localCheckoutPathForRepo - should return empty when the workspace slug or projects directory is unset`(): Unit =
        runBlocking {
            // GIVEN no workspace slug and no projects directory
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/repo-a")
            val service = service(settings(), FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val withoutSlug = service.localCheckoutPathForRepo(repoSlug = "repo-a")

            // THEN nothing resolves and the resolver is never consulted
            assertSoftly {
                withoutSlug shouldBe ""
                discoveryService.resolveInvocations shouldBe 0
            }

            // AND with a slug but still no projects directory it also returns empty without consulting the resolver
            val settingsWithSlug = settings(workspaceSlug = "my-workspace")
            val resolverGuard = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/repo-a")
            val serviceWithSlug = service(settingsWithSlug, FakeCredentialStore(hasLogin = true), discoveryService = resolverGuard)

            assertSoftly {
                serviceWithSlug.localCheckoutPathForRepo(repoSlug = "repo-a") shouldBe ""
                resolverGuard.resolveInvocations shouldBe 0
            }
        }

    @Test
    fun `localCheckoutPathForRepo - should return empty when the resolver finds no checkout`(): Unit =
        runBlocking {
            // GIVEN a configured workspace and projects directory, but the resolver reports no checkout
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = projectsDir.toString()
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "")
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val path = service.localCheckoutPathForRepo(repoSlug = "repo-a")

            // THEN the empty result is passed through (the click handler browses the web page instead)
            assertSoftly {
                path shouldBe ""
                discoveryService.resolveInvocations shouldBe 1
            }
        }

    @Test
    fun `clearProjectsDirectory - should leave the persisted avatar URL map empty`(): Unit =
        runBlocking {
            // GIVEN a stored avatar URL map and a projects directory
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceRepoAvatarUrls(mapOf("repo-a" to "https://cdn.example.com/a.png"))
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            service.clearProjectsDirectory()

            // THEN clearWatchedProjects wiped the avatar URLs too
            settings.repoAvatarUrls shouldBe emptyMap()
        }

    @Test
    fun `workTreeDirectoryOrNull - should derive worktrees under the projects directory`(): Unit =
        runBlocking {
            // GIVEN a stored projects directory
            val settings = settings()
            val projectsDirectory = Paths.get(RELATIVE_ROOT, "bbcloud").toAbsolutePath().normalize().toString()
            settings.projectsDirectory = projectsDirectory
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN / THEN the work tree root is derived as $projectsDirectory/.worktrees, normalized.
            val expected = Paths.get(projectsDirectory).resolve(".worktrees").toAbsolutePath().normalize().toString()
            service.workTreeDirectoryOrNull() shouldBe expected
        }

    @Test
    fun `workTreeDirectoryOrNull - should be null when no projects directory is set`(): Unit =
        runBlocking {
            // GIVEN no projects directory
            val settings = settings()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN / THEN there is nothing to derive from.
            service.workTreeDirectoryOrNull() shouldBe null

            settings.projectsDirectory = "   "
            service.workTreeDirectoryOrNull() shouldBe null
        }

    @Test
    fun `saveProjectsDirectory - should derive a non-null work tree root`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings()
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN a projects directory is saved
            service.saveProjectsDirectory(absolutePath = projectsDir.toString())

            // THEN the derived work tree root is $projectsDirectory/.worktrees
            val expected = Paths.get(projectsDir.toString()).resolve(".worktrees").toAbsolutePath().normalize().toString()
            assertSoftly {
                service.workTreeDirectoryOrNull() shouldBe expected
                // best-effort eager creation leaves the folder on disk
                java.nio.file.Files.isDirectory(Paths.get(expected)) shouldBe true
            }
        }

    @Test
    fun `listWorkspaces - should delegate to the workspace client`(): Unit =
        runBlocking {
            val workspaces = listOf(BitbucketWorkspace(slug = "my-workspace", uuid = null))
            val client = FakeWorkspaceClient(WorkspaceListResult.Success(workspaces))
            val service = service(settings(), FakeCredentialStore(hasLogin = true), client)

            service.listWorkspaces(apiToken = "token") shouldBe WorkspaceListResult.Success(workspaces)
            client.requestedToken shouldBe "token"
        }

    @Test
    fun `watchedProjectsForCurrentWorkspace - should return every stored mapping with the configured slug attached`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                listOf(
                    WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"),
                    WatchedProjectMapping(localFolderPath = "/local/b", remoteRepoSlug = "repo-b", workspace = "my-workspace")
                )
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // THEN the flat map holds only the current configuration's repositories; the configured slug is attached.
            assertSoftly {
                service.watchedProjectsForCurrentWorkspace().map { it.remoteRepoSlug } shouldBe listOf("repo-a", "repo-b")
                service.watchedProjectsForCurrentWorkspace().map { it.workspace }.toSet() shouldBe setOf("my-workspace")
            }
        }

    @Test
    fun `watchedProjectsForCurrentWorkspace - should return empty when no slug is configured`(): Unit =
        runBlocking {
            val settings = settings()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            service.watchedProjectsForCurrentWorkspace() shouldBe emptyList()
        }

    @Test
    fun `watchedProjectsForCurrentWorkspace - should trim more than 20 watched projects to the first 20 by slug and persist it`(): Unit =
        runBlocking {
            // GIVEN 25 watched projects, as an install from before the limit existed can hold
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                (1..25).map { index ->
                    WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-%02d".format(index), workspace = "my-workspace", watched = true)
                }
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN the watched projects are read
            val watchedSlugs = service.watchedProjectsForCurrentWorkspace().filter { it.watched }.map { it.remoteRepoSlug }

            // THEN the first 20 by slug keep their flag, and the trim is written back to the settings
            assertSoftly {
                watchedSlugs shouldBe (1..20).map { index -> "repo-%02d".format(index) }
                settings.watchedProjects.count { it.watched } shouldBe 20
            }
        }

    @Test
    fun `watchedProjectsForCurrentWorkspace - should leave exactly 20 watched projects untouched`(): Unit =
        runBlocking {
            // GIVEN exactly the limit of watched projects
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                (1..20).map { index ->
                    WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-%02d".format(index), workspace = "my-workspace", watched = true)
                }
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN the watched projects are read
            // THEN all 20 stay watched
            assertSoftly {
                service.watchedProjectsForCurrentWorkspace().count { it.watched } shouldBe 20
                settings.watchedProjects.count { it.watched } shouldBe 20
            }
        }

    @Test
    fun `replaceWatchedProjects - should overwrite previous mappings`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/old", remoteRepoSlug = "old-repo", workspace = "my-workspace"))
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))
            val newMappings = listOf(
                WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            )

            service.replaceWatchedProjects(newMappings)

            // The flat map keys by slug only, so the workspace and local folder path are not persisted and reload empty.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "")
            )
        }

    @Test
    fun `clearWatchedProjects - should remove all mappings`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            service.clearWatchedProjects()

            settings.watchedProjects shouldBe emptyList()
        }

    @Test
    fun `clearProjectsDirectory - should clear the directory and the watched projects`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get(RELATIVE_ROOT, "bbcloud").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            service.clearProjectsDirectory()

            // THEN
            assertSoftly {
                service.projectsDirectoryOrNull() shouldBe null
                settings.watchedProjects shouldBe emptyList()
                service.workspaceSlugOrNull() shouldBe "my-workspace"
            }
        }

    @Test
    fun `rescanWatchedProjects - should return NotConfigured when not configured`(): Unit =
        runBlocking {
            val service = service(settings(), FakeCredentialStore(hasLogin = false))

            val result = service.rescanWatchedProjects()

            result.shouldBeInstanceOf<WatchedProjectsDiscoveryResult.NotConfigured>()
        }

    @Test
    fun `rescanWatchedProjects - should delegate to the discovery service and persist on success`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val mappings = listOf(
                WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            )
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(mappings))
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            val result = service.rescanWatchedProjects()

            assertSoftly {
                result.shouldBeInstanceOf<WatchedProjectsDiscoveryResult.Success>()
                discoveryService.discoverInvocations shouldBe 1
                discoveryService.requestedWorkspaceSlug shouldBe "my-workspace"
                // The flat map keys by slug only, so the workspace and local folder path are not persisted.
                settings.watchedProjects shouldBe listOf(
                    WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "")
                )
            }
        }

    @Test
    fun `rescanWatchedProjects - should keep previous mappings on VerifyFailed in the Sync mode`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.VerifyFailed)
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a manual Sync (clearFirst = false) hits a transient verify failure
            service.rescanWatchedProjects().shouldBeInstanceOf<WatchedProjectsDiscoveryResult.VerifyFailed>()

            // THEN the previous entry is kept; the slug-keyed map reloads with an empty workspace and path.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "")
            )
        }

    @Test
    fun `rescanWatchedProjects - should keep previous mappings on ProjectsDirectoryUnreadable in the Sync mode`(): Unit =
        runBlocking {
            // GIVEN a stored mapping and a scan that reports the directory as unreadable (a transient read failure)
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace"))
            )
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.ProjectsDirectoryUnreadable)
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a manual Sync (clearFirst = false) hits the unreadable directory
            service.rescanWatchedProjects().shouldBeInstanceOf<WatchedProjectsDiscoveryResult.ProjectsDirectoryUnreadable>()

            // THEN the previous mapping is kept, like VerifyFailed; the slug-keyed map reloads empty workspace and path.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "")
            )
        }

    @Test
    fun `rescanWatchedProjects - clearFirst should empty the map before discovery and leave it empty on VerifyFailed`(): Unit =
        runBlocking {
            // GIVEN a previously watched entry and a scan that fails to verify
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true))
            )
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.VerifyFailed)
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a clear-first rescan (a config change) hits a verify failure
            service.rescanWatchedProjects(clearFirst = true).shouldBeInstanceOf<WatchedProjectsDiscoveryResult.VerifyFailed>()

            // THEN the map was emptied first and stays empty; there are no stale entries to restore.
            settings.watchedProjects shouldBe emptyList()
        }

    @Test
    fun `rescanWatchedProjects - clearFirst should rebuild rediscovered repositories as unwatched`(): Unit =
        runBlocking {
            // GIVEN a previously watched repo-a that the scan finds again
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true))
            )
            val scanned = listOf(
                WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            )
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(scanned))
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a clear-first rescan runs
            service.rescanWatchedProjects(clearFirst = true)

            // THEN the old watched flag is NOT carried over; repo-a comes back unwatched.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = false)
            )
        }

    @Test
    fun `rescanWatchedProjects - should serialize overlapping scans with the mutex`(): Unit =
        runBlocking {
            // GIVEN a discovery service that asserts it is never entered re-entrantly, so an interleaved rescan would fail
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val discoveryService = OverlapDetectingDiscoveryService()
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN two rescans run concurrently
            val first = async { service.rescanWatchedProjects() }
            val second = async { service.rescanWatchedProjects() }
            first.await()
            second.await()

            // THEN the mutex kept them from overlapping and both completed
            assertSoftly {
                discoveryService.observedOverlap shouldBe false
                discoveryService.discoverInvocations shouldBe 2
            }
        }

    @Test
    fun `saveOAuthSetup - should empty and rebuild watched projects when workspace slug changes and directory is set`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "old-slug")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "old-slug", watched = true))
            )
            val store = FakeCredentialStore(hasLogin = true)
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(emptyList()))
            val service = service(settings, store, discoveryService = discoveryService)

            service.saveOAuthSetup(workspaceSlug = "new-slug", tokens = oauthTokens())

            assertSoftly {
                settings.workspaceSlug shouldBe "new-slug"
                // A clear-first rescan ran for the new slug and the empty scan left the map empty.
                discoveryService.discoverInvocations shouldBe 1
                discoveryService.requestedWorkspaceSlug shouldBe "new-slug"
                settings.watchedProjects shouldBe emptyList()
            }
        }

    @Test
    fun `saveOAuthSetup - should not touch watched projects when the slug is unchanged`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true))
            )
            val store = FakeCredentialStore(hasLogin = true)
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(emptyList()))
            val service = service(settings, store, discoveryService = discoveryService)

            // The slug is unchanged, so signing in again keeps the watched choices.
            service.saveOAuthSetup(workspaceSlug = "my-workspace", tokens = oauthTokens())

            assertSoftly {
                discoveryService.discoverInvocations shouldBe 0
                // The watched map is left intact (reloads slug-keyed with empty workspace and path).
                settings.watchedProjects shouldBe listOf(
                    WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = true)
                )
            }
        }

    @Test
    fun `saveOAuthSetup - should empty watched projects on a slug change even when no projects directory is set`(): Unit =
        runBlocking {
            val settings = settings(workspaceSlug = "old-slug")
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "old-slug", watched = true))
            )
            val store = FakeCredentialStore(hasLogin = true)
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(emptyList()))
            val service = service(settings, store, discoveryService = discoveryService)

            service.saveOAuthSetup(workspaceSlug = "new-slug", tokens = oauthTokens())

            assertSoftly {
                // With no directory there is nothing to rebuild, so no discovery runs, but the map is still emptied.
                discoveryService.discoverInvocations shouldBe 0
                settings.watchedProjects shouldBe emptyList()
            }
        }

    @Test
    fun `rescanWatchedProjects - should store a newly scanned mapping as unwatched by default`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val scanned = WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(listOf(scanned)))
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            service.rescanWatchedProjects()

            // THEN
            val stored = settings.watchedProjects.single()
            assertSoftly {
                stored.remoteRepoSlug shouldBe "repo-a"
                stored.watched shouldBe false
            }
        }

    @Test
    fun `watchedRepoSlugsForCurrentWorkspace - should return only the watched slugs`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            settings.replaceWatchedProjects(
                listOf(
                    WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true),
                    WatchedProjectMapping(localFolderPath = "/local/b", remoteRepoSlug = "repo-b", workspace = "my-workspace", watched = false),
                    WatchedProjectMapping(localFolderPath = "/local/c", remoteRepoSlug = "repo-c", workspace = "my-workspace", watched = true)
                )
            )
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            val slugs = service.watchedRepoSlugsForCurrentWorkspace()

            // THEN the watched slugs come back sorted by slug.
            slugs shouldBe listOf("repo-a", "repo-c")
        }

    @Test
    fun `setWatched - should change only the matching entry`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            val repoA = WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true)
            val repoB = WatchedProjectMapping(localFolderPath = "/local/b", remoteRepoSlug = "repo-b", workspace = "my-workspace", watched = true)
            settings.replaceWatchedProjects(listOf(repoA, repoB))
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            service.setWatched(mapping = repoA, watched = false)

            // THEN the matching entry is unwatched, the other stays watched; the slug-keyed map reloads empty workspace.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = false),
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-b", workspace = "", watched = true)
            )
        }

    @Test
    fun `setAllWatched - should change every stored entry`(): Unit =
        runBlocking {
            // GIVEN
            val settings = settings(workspaceSlug = "my-workspace")
            val repoA = WatchedProjectMapping(localFolderPath = "/local/a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true)
            val repoB = WatchedProjectMapping(localFolderPath = "/local/b", remoteRepoSlug = "repo-b", workspace = "my-workspace", watched = false)
            settings.replaceWatchedProjects(listOf(repoA, repoB))
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            service.setAllWatched(watched = false)

            // THEN every entry flips to unwatched; the slug-keyed map reloads sorted by slug with empty workspace.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = false),
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-b", workspace = "", watched = false)
            )
        }

    @Test
    fun `rescanWatchedProjects - should keep the watched flag of a repository that is still found in the Sync mode`(): Unit =
        runBlocking {
            // GIVEN a stored repo-a that the user has watched, and a rescan that finds it again plus a new repo-b
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val storedRepoA =
                WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = true)
            settings.replaceWatchedProjects(listOf(storedRepoA))
            val scannedRepoA = WatchedProjectMapping(localFolderPath = "/projects/repo-a", remoteRepoSlug = "repo-a", workspace = "my-workspace")
            val scannedRepoB = WatchedProjectMapping(localFolderPath = "/projects/repo-b", remoteRepoSlug = "repo-b", workspace = "my-workspace")
            val discoveryService = FakeDiscoveryService(result = WatchedProjectsDiscoveryResult.Success(listOf(scannedRepoA, scannedRepoB)))
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN a manual Sync (clearFirst = false) runs
            service.rescanWatchedProjects()

            // THEN repo-a keeps its watched flag, repo-b comes in unwatched; the slug-keyed map reloads empty workspace.
            settings.watchedProjects shouldBe listOf(
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "", watched = true),
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-b", workspace = "", watched = false)
            )
        }

    @Test
    fun `registerCheckedOutProject - should store a resolvable folder as watched with its avatar`(): Unit =
        runBlocking {
            // GIVEN a resolvable checkout below the watched limit
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/repo-a")
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val result = service.registerCheckedOutProject(repoSlug = "repo-a", avatarUrl = "https://cdn.example.com/a.png")

            // THEN
            assertSoftly {
                result shouldBe CheckedOutProjectRegistration.Watched
                settings.watchedProjects.map { mapping -> mapping.remoteRepoSlug to mapping.watched } shouldBe listOf("repo-a" to true)
                settings.repoAvatarUrls["repo-a"] shouldBe "https://cdn.example.com/a.png"
            }
        }

    @Test
    fun `registerCheckedOutProject - should store the entry unwatched when the watched limit is reached`(): Unit =
        runBlocking {
            // GIVEN twenty watched projects
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val watched = (1..WatchedProjectsViewModel.MAX_WATCHED_PROJECTS).map { index ->
                WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "watched-%02d".format(index), workspace = "my-workspace", watched = true)
            }
            settings.replaceWatchedProjects(watched)
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/new-repo")
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            val result = service.registerCheckedOutProject(repoSlug = "new-repo", avatarUrl = null)

            // THEN
            assertSoftly {
                result shouldBe CheckedOutProjectRegistration.AddedUnwatchedLimitReached
                settings.watchedProjects.single { mapping -> mapping.remoteRepoSlug == "new-repo" }.watched shouldBe false
            }
        }

    @Test
    fun `registerCheckedOutProject - should replace an existing unwatched entry instead of duplicating it`(): Unit =
        runBlocking {
            // GIVEN an unwatched entry for the slug
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            settings.replaceWatchedProjects(
                listOf(WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = "repo-a", workspace = "my-workspace", watched = false))
            )
            val discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = "/projects/repo-a")
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = discoveryService)

            // WHEN
            service.registerCheckedOutProject(repoSlug = "repo-a", avatarUrl = null)

            // THEN
            settings.watchedProjects.map { mapping -> mapping.remoteRepoSlug to mapping.watched } shouldBe listOf("repo-a" to true)
        }

    @Test
    fun `registerCheckedOutProject - should store nothing when the folder cannot be resolved`(): Unit =
        runBlocking {
            // GIVEN a folder that is not a Bitbucket checkout
            val settings = settings(workspaceSlug = "my-workspace")
            settings.projectsDirectory = Paths.get("target/test-projects").toAbsolutePath().normalize().toString()
            val service = service(settings, FakeCredentialStore(hasLogin = true), discoveryService = FakeDiscoveryService(resolvedLocalFolderPath = ""))

            // WHEN
            val result = service.registerCheckedOutProject(repoSlug = "repo-a", avatarUrl = null)

            // THEN
            assertSoftly {
                result shouldBe CheckedOutProjectRegistration.NotResolvable
                settings.watchedProjects shouldBe emptyList()
            }
        }

    @Test
    fun `registerCheckedOutProject - should report NotConfigured without a projects directory`(): Unit =
        runBlocking {
            // GIVEN no projects directory
            val settings = settings(workspaceSlug = "my-workspace")
            val service = service(settings, FakeCredentialStore(hasLogin = true))

            // WHEN
            val result = service.registerCheckedOutProject(repoSlug = "repo-a", avatarUrl = null)

            // THEN
            result shouldBe CheckedOutProjectRegistration.NotConfigured
        }

    private fun service(
        settings: DiffySettings,
        store: FakeCredentialStore,
        client: FakeWorkspaceClient = FakeWorkspaceClient(WorkspaceListResult.Success(emptyList())),
        discoveryService: DiffyWatchedProjectsDiscoveryService = FakeDiscoveryService(),
        provider: FakeAccessTokenProvider = FakeAccessTokenProvider(store = store),
        validator: DiffyCredentialValidator = DiffyCredentialValidator(
            credentialStore = store,
            accessTokenProvider = provider,
            checkClient = AlwaysValidCheckClient,
            clock = Clock.System,
            // No caching in the unit test, so each isConfigured call re-reads the current stored credentials.
            ttl = kotlin.time.Duration.ZERO
        )
    ): DiffyConfigurationService {
        return DiffyConfigurationService(
            settings = settings,
            credentialStore = store,
            workspaceClient = client,
            discoveryService = discoveryService,
            accessTokenProvider = provider,
            credentialValidator = validator
        )
    }

    /** A provider that mirrors the fake store: the stored OAuth access token, or null. */
    private class FakeAccessTokenProvider(private val store: FakeCredentialStore) : DiffyAccessTokenProvider {
        override suspend fun bearerTokenOrNull(): String? {
            return store.getOAuthTokens()?.accessToken
        }

        override suspend fun oauthAccessTokenOrNull(): String? {
            return store.getOAuthTokens()?.accessToken
        }

        override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? {
            return null
        }

        override fun markOAuthNotWorking() {
            // No-op in the config-service test; credential state is driven by the store directly.
        }

        override fun onOAuthTokensChanged() {
            // No-op.
        }
    }

    /** Treats any non-blank bearer token as valid, so "credential present" maps to a working method. */
    private object AlwaysValidCheckClient : BitbucketCredentialCheckClient {
        override suspend fun check(bearerToken: String): CredentialCheckResult {
            return if (bearerToken.isNotBlank()) CredentialCheckResult.Valid else CredentialCheckResult.Unauthorized
        }
    }

    private fun settings(workspaceSlug: String = "", organizationUrl: String = ""): DiffySettings {
        return DiffySettings().apply {
            this.workspaceSlug = workspaceSlug
            this.organizationUrl = organizationUrl
        }
    }

    private class FakeWorkspaceClient(private val result: WorkspaceListResult) : BitbucketWorkspaceClient {
        var requestedToken: String? = null

        override suspend fun listWorkspaces(apiToken: String): WorkspaceListResult {
            requestedToken = apiToken
            return result
        }
    }

    private class FakeCredentialStore(hasLogin: Boolean) : DiffyCredentialStore {
        var oauthTokens: OAuthTokens? = if (hasLogin) defaultOAuthTokens() else null
        var saveOAuthInvocations: Int = 0
        var clearOAuthInvocations: Int = 0

        /** Whether an API token left behind by an older plugin version is still in the store. */
        var legacyApiTokenPresent: Boolean = false

        override suspend fun clearLegacyApiToken() {
            legacyApiTokenPresent = false
        }

        override suspend fun getOAuthTokens(): OAuthTokens? {
            return oauthTokens
        }

        override suspend fun saveOAuthTokens(tokens: OAuthTokens) {
            saveOAuthInvocations++
            oauthTokens = tokens
        }

        override suspend fun clearOAuthTokens() {
            clearOAuthInvocations++
            oauthTokens = null
        }

        private fun defaultOAuthTokens(): OAuthTokens {
            return OAuthTokens(
                accessToken = "oauth-access",
                refreshToken = "oauth-refresh",
                expiresAt = Clock.System.now() + kotlin.time.Duration.parse("2h")
            )
        }
    }

    private class FakeDiscoveryService(
        var result: WatchedProjectsDiscoveryResult = WatchedProjectsDiscoveryResult.NotConfigured,
        private val resolvedLocalFolderPath: String = ""
    ) : DiffyWatchedProjectsDiscoveryService(repositoryClient = mockk(relaxed = true)) {
        var requestedProjectsDirectory: String? = null
        var requestedWorkspaceSlug: String? = null
        var requestedApiToken: String? = null
        var discoverInvocations: Int = 0
        var resolveInvocations: Int = 0
        var resolvedRepoSlug: String? = null

        override suspend fun discover(projectsDirectory: String, configuredWorkspaceSlug: String, apiToken: String): WatchedProjectsDiscoveryResult {
            discoverInvocations++
            requestedProjectsDirectory = projectsDirectory
            requestedWorkspaceSlug = configuredWorkspaceSlug
            requestedApiToken = apiToken
            return result
        }

        override fun resolveLocalFolderPath(projectsDirectory: String, configuredWorkspaceSlug: String, repoSlug: String): String {
            resolveInvocations++
            requestedProjectsDirectory = projectsDirectory
            requestedWorkspaceSlug = configuredWorkspaceSlug
            resolvedRepoSlug = repoSlug
            return resolvedLocalFolderPath
        }
    }

    /**
     * A discovery fake that records whether [discover] was ever entered while a previous call was still inside it. The
     * service mutex should keep two concurrent rescans from overlapping, so [observedOverlap] must stay false. The short
     * yield widens the window an interleaving scan could slip through without introducing a timing assertion.
     */
    private class OverlapDetectingDiscoveryService : DiffyWatchedProjectsDiscoveryService(repositoryClient = mockk(relaxed = true)) {
        var discoverInvocations: Int = 0
        var observedOverlap: Boolean = false
        private var inside: Boolean = false

        override suspend fun discover(projectsDirectory: String, configuredWorkspaceSlug: String, apiToken: String): WatchedProjectsDiscoveryResult {
            if (inside) {
                observedOverlap = true
            }
            inside = true
            discoverInvocations++
            yield()
            inside = false
            return WatchedProjectsDiscoveryResult.Success(emptyList())
        }
    }

    companion object {
        /** Relative, so the assertions stay independent of the working directory the tests happen to run in. */
        private const val RELATIVE_ROOT = "target/test-projects"
    }
}