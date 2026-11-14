package com.github.fnunezkanut.bbcloud.bitbucket

/** Chooses the repositories the Pull Requests asks for its watched projects. */
object ReviewingPullRequestAssembler {
    /**
     * The repositories to ask: [excludedRepoSlug] dropped, because the Pull Requests lists the current project from its own source, and
     * capped, so a workspace with many watched repositories cannot fan out without bound.
     *
     * The exclusion is matched without case: it is derived from the local directory name, while Bitbucket slugs
     * are lowercase, so `MyRepo` on disk has to match `myrepo` remotely.
     */
    fun repositoryTargets(repoSlugs: List<String>, excludedRepoSlug: String?, maxRepositories: Int): List<String> {
        val excluded = excludedRepoSlug?.trim()?.lowercase()?.takeIf { slug -> slug.isNotEmpty() }
        return repoSlugs
            .filter { slug -> slug.isNotBlank() }
            .filterNot { slug -> excluded != null && slug.lowercase() == excluded }
            .distinct()
            .take(maxRepositories)
    }
}