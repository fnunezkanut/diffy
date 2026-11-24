package com.github.fnunezkanut.bbcloud.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The persisted snapshot of the workspace repository list. It is rebuildable from Bitbucket and never synced by
 * settings sync (roaming is disabled). Registered as an `applicationService` in `plugin.xml`.
 *
 * [State.repositories] holds one JSON row per repository; see [CachedWorkspaceRepositoryRow].
 */
@State(
    name = "BbCloudWorkspaceRepositoryCache",
    storages = [Storage(value = "bbcloud-workspace-repositories.xml", roamingType = RoamingType.DISABLED)]
)
class DiffyWorkspaceRepositoryCacheState : SimplePersistentStateComponent<DiffyWorkspaceRepositoryCacheState.State>(State()) {
    class State : BaseState() {
        var workspace by string("")

        /** ISO-8601 instant of the last successful list fetch, or "" when nothing is cached. */
        var fetchedAt by string("")

        var isTruncated by property(false)

        var repositories by list<String>()
    }

    companion object {
        fun getInstance(): DiffyWorkspaceRepositoryCacheState {
            return ApplicationManager.getApplication().service()
        }
    }
}