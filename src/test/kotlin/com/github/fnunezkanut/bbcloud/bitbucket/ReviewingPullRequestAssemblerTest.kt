package com.github.fnunezkanut.bbcloud.bitbucket

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ReviewingPullRequestAssemblerTest {
    @Nested
    inner class RepositoryTargets {
        @Test
        fun `repositoryTargets - should drop the current project repository`() {
            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = listOf("examplerepo", "another-repo", "third-repo"),
                excludedRepoSlug = "another-repo",
                maxRepositories = 50
            )

            // THEN
            targets shouldContainExactly listOf("examplerepo", "third-repo")
        }

        @Test
        fun `repositoryTargets - should match the excluded repository without case`() {
            // GIVEN the local directory name is not lowercase the way Bitbucket slugs are
            val repoSlugs = listOf("MyRepo", "other-repo")

            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = repoSlugs,
                excludedRepoSlug = "MyRepo",
                maxRepositories = 50
            )

            // THEN
            targets shouldContainExactly listOf("other-repo")
        }

        @Test
        fun `repositoryTargets - should keep every repository when nothing is excluded`() {
            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = listOf("examplerepo", "other-repo"),
                excludedRepoSlug = null,
                maxRepositories = 50
            )

            // THEN
            targets shouldContainExactly listOf("examplerepo", "other-repo")
        }

        @Test
        fun `repositoryTargets - should not let a blank exclusion drop everything`() {
            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = listOf("examplerepo", "other-repo"),
                excludedRepoSlug = "   ",
                maxRepositories = 50
            )

            // THEN
            targets shouldContainExactly listOf("examplerepo", "other-repo")
        }

        @Test
        fun `repositoryTargets - should collapse duplicates and blank slugs`() {
            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = listOf("examplerepo", " ", "examplerepo", "other-repo"),
                excludedRepoSlug = null,
                maxRepositories = 50
            )

            // THEN
            targets shouldContainExactly listOf("examplerepo", "other-repo")
        }

        @Test
        fun `repositoryTargets - should cap the number of repositories`() {
            // GIVEN more repositories than one load may ask
            val repoSlugs = (1..10).map { index -> "repo-$index" }

            // WHEN
            val targets = ReviewingPullRequestAssembler.repositoryTargets(
                repoSlugs = repoSlugs,
                excludedRepoSlug = null,
                maxRepositories = 3
            )

            // THEN
            targets shouldContainExactly listOf("repo-1", "repo-2", "repo-3")
        }
    }
}