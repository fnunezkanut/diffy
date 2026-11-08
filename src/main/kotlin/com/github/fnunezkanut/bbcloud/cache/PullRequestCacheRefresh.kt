package com.github.fnunezkanut.bbcloud.cache

/**
 * How a load should treat the cache.
 *
 * [IfStale] is an automatic load (a tab opened or bound). It uses the cache while the listing is fresh and falls back to
 * the cache when a request fails. [Force] is a user's Refresh click. It always asks Bitbucket and updates the cache.
 */
enum class PullRequestCacheRefresh {
    IfStale,
    Force
}