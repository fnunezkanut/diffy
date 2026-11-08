package com.github.fnunezkanut.bbcloud.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The persisted backing store of the pull request cache. Its XML lives in its own file and is never synced by settings
 * sync (roaming is disabled). Registered as an `applicationService` in `plugin.xml`.
 *
 * [State.entries] and [State.listings] hold one JSON row per cache entry and listing snapshot; see [CachedPullRequestRow].
 * [State.ownerUserUuid] is the Bitbucket account uuid that owns the cached data.
 */
@State(
    name = "BbCloudPullRequestCache",
    storages = [Storage(value = "bbcloud-pull-request-cache.xml", roamingType = RoamingType.DISABLED)]
)
class DiffyPullRequestCacheState : SimplePersistentStateComponent<DiffyPullRequestCacheState.State>(State()) {
    class State : BaseState() {
        var ownerUserUuid by string("")

        var entries by list<String>()

        var listings by list<String>()
    }

    companion object {
        fun getInstance(): DiffyPullRequestCacheState {
            return ApplicationManager.getApplication().service()
        }
    }
}