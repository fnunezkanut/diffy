package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore

/**
 * Returns the current user for [apiToken]. The shared cache answers first when it remembers this token. Otherwise the
 * user is read from Bitbucket and remembered. A fresh read also tells the cache which account owns its data.
 */
internal suspend fun currentUserThroughCache(
    pullRequestCache: PullRequestCacheStore,
    pullRequestClient: BitbucketPullRequestClient,
    apiToken: String
): CurrentUser? {
    val tokenHash = apiToken.hashCode()
    pullRequestCache.cachedCurrentUser(tokenHash = tokenHash)?.let { remembered -> return remembered }
    val user = pullRequestClient.getCurrentUser(apiToken = apiToken) ?: return null
    pullRequestCache.rememberCurrentUser(tokenHash = tokenHash, user = user)
    pullRequestCache.bindUser(userUuid = user.uuid)
    return user
}