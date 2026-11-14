package com.github.fnunezkanut.bbcloud.ui.pullrequests

/**
 * Where a row of the Pull Requests came from; decides how its title link behaves. Declaration order is the de-duplication
 * precedence: the current project wins over a watched copy of the same repository, and both win over the authored copy
 * (the repository listing carries participants and resolved state).
 */
enum class PullRequestListingSource { ProjectOpen, ProjectResolved, WatchedOpen, WatchedResolved, WorkspaceAuthored }