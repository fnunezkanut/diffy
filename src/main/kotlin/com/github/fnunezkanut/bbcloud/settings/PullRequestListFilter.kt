package com.github.fnunezkanut.bbcloud.settings

/**
 * The filter state of the Pull Requests: the three Exclude checkboxes and the two dropdowns. Defaults match a fresh install.
 * [userScope] restricts the list to the pull requests matching the selected [PullRequestUserScope]. [projectScope]
 * restricts it to the current project's repository when set to [PullRequestProjectScope.CurrentProject].
 */
data class PullRequestListFilter(
    val excludeResolved: Boolean = true,
    val excludeApprovedByMe: Boolean = false,
    val excludeDrafts: Boolean = true,
    val userScope: PullRequestUserScope = PullRequestUserScope.AssignedToMe,
    val projectScope: PullRequestProjectScope = PullRequestProjectScope.AnyProject
)

/**
 * The user scope dropdown selection for a pull request tab, in dropdown order. [AssignedToMe] is the default and keeps
 * the pull requests the current user authored or reviews. [Me] keeps only the pull requests the current user authored.
 * [Others] keeps the pull requests the current user did not author. The entry names are persisted in settings, so do
 * not rename them without a migration; an unknown stored name falls back to [AssignedToMe].
 */
enum class PullRequestUserScope {
    AssignedToMe,
    Me,
    Others
}

/**
 * The project scope dropdown selection of the Pull Requests. [AnyProject] (default) applies no restriction. [CurrentProject]
 * keeps the rows whose repository is the repository of the IDE project. The names are persisted; do not rename them.
 */
enum class PullRequestProjectScope {
    AnyProject,
    CurrentProject
}