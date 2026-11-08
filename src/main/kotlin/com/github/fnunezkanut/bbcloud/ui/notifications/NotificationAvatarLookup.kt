package com.github.fnunezkanut.bbcloud.ui.notifications

import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore
import com.github.fnunezkanut.bbcloud.cache.avatarUrlOfPerson
import com.github.fnunezkanut.bbcloud.external.DiffyBrowserNotificationEvent

/**
 * The avatar URL of the actor of [event], read from the cached pull request of the event: the actor is looked up among
 * the author and the reviewers. Null when the event has no pull request, the pull request or the actor is not cached,
 * or the cache knows no avatar for the actor. Call it from a background thread, like every pull request cache read.
 */
internal fun cachedActorAvatarUrl(cache: PullRequestCacheStore, event: DiffyBrowserNotificationEvent, workspace: String?): String? {
    val repoSlug = event.pullRequestRepoSlug
    val pullRequestId = event.pullRequestId
    if (workspace.isNullOrBlank() || repoSlug.isNullOrBlank() || pullRequestId == null) {
        return null
    }
    val key = PullRequestCacheKey.of(workspace = workspace, repoSlug = repoSlug, prNum = pullRequestId)
    val pullRequest = cache.get(key) ?: return null
    return pullRequest.avatarUrlOfPerson(accountId = event.actorAccountId, displayName = event.actorDisplayName)
}