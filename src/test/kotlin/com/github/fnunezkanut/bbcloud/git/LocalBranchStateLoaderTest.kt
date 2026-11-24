package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class LocalBranchStateLoaderTest {
    private val project = mockk<Project>(relaxed = true)
    private val repository = mockk<GitRepository>(relaxed = true)
    private val diffService = mockk<DiffyPullRequestBranchDiffService>()
    private val reader = mockk<LocalBranchStateLoader.ChangedPathsReader>()
    private val loader = LocalBranchStateLoader(diffService = diffService, changedPathsReader = reader)

    @BeforeEach
    fun setUp() {
        every { ProjectGitRepositoryLookup.findRepository(project) } returns repository
        every { PrRemoteResolver.remoteName(repository) } returns "origin"
        every { GitBranchRefResolver.resolve(repository, "feature", false) } returns "feature"
        every { repository.root } returns mockk<VirtualFile>(relaxed = true)
        every { repository.currentRevision } returns "head1"
        every { diffService.resolveRevision(project, "origin/feature") } returns "remote1"
        every { diffService.resolveRevision(project, "feature") } returns "head1"
        every { diffService.countCommitsBetween(project, "remote1", "head1") } returns 0
    }

    @Test
    fun `load - should return null when the remote ref is missing`() {
        // GIVEN
        every { diffService.resolveRevision(project, "origin/feature") } returns null

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldBeNull()
    }

    @Test
    fun `load - should report no unpushed files when head equals the source`() {
        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = setOf("a.kt"))

        // THEN
        state.shouldNotBeNull()
        assertSoftly {
            state.commitsAheadOfRemote shouldBe 0
            state.unpushedPaths shouldBe emptySet()
            state.uncommittedPaths shouldBe setOf("a.kt")
            state.headDescendsFromLocalSource shouldBe true
        }
    }

    @Test
    fun `load - should fill the unpushed paths when head is ahead`() {
        // GIVEN
        every { diffService.countCommitsBetween(project, "remote1", "head1") } returns 2
        every { reader.read(project, any(), "remote1", "head1") } returns setOf("src/A.kt", "src/B.kt")

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldNotBeNull()
        assertSoftly {
            state.commitsAheadOfRemote shouldBe 2
            state.unpushedPaths shouldBe setOf("src/A.kt", "src/B.kt")
        }
    }

    @Test
    fun `load - should flag a diverged branch`() {
        // GIVEN
        every { diffService.resolveRevision(project, "feature") } returns "src1"
        every { diffService.isAncestor(project, "src1", "head1") } returns false

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldNotBeNull()
        state.headDescendsFromLocalSource shouldBe false
    }

    @Test
    fun `load - should return null for a detached head without a revision`() {
        // GIVEN
        every { repository.currentRevision } returns null

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldBeNull()
    }

    @Test
    fun `load - should work on a detached head that has a revision`() {
        // GIVEN
        every { repository.currentBranchName } returns null

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldNotBeNull()
    }

    @Test
    fun `load - should return null when the git diff command fails`() {
        // GIVEN
        every { diffService.countCommitsBetween(project, "remote1", "head1") } returns 1
        every { reader.read(project, any(), "remote1", "head1") } returns null

        // WHEN
        val state = loader.load(project = project, sourceBranch = "feature", uncommittedPaths = emptySet())

        // THEN
        state.shouldBeNull()
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun mockObjects() {
            mockkObject(ProjectGitRepositoryLookup)
            mockkObject(PrRemoteResolver)
            mockkObject(GitBranchRefResolver)
        }

        @AfterAll
        @JvmStatic
        fun unmockObjects() {
            unmockkObject(ProjectGitRepositoryLookup)
            unmockkObject(PrRemoteResolver)
            unmockkObject(GitBranchRefResolver)
        }
    }
}