package com.github.fnunezkanut.bbcloud.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The persisted backing store of the recently-seen mention users, separate from `DiffySettings` so its XML is its
 * own file. Registered as an `applicationService` in `plugin.xml`. The `@State` name stays "BbCloudKnownUsers" so
 * existing user settings keep loading.
 */
@State(name = "BbCloudKnownUsers", storages = [Storage("bbcloud-known-users.xml")])
class DiffyKnownUsersState : SimplePersistentStateComponent<DiffyKnownUsersState.State>(State()) {
    class State : BaseState() {
        /** One encoded row per remembered user; see [DiffyKnownUserRow]. Capped, oldest first out. */
        var rows by list<String>()
    }

    companion object {
        fun getInstance(): DiffyKnownUsersState {
            return ApplicationManager.getApplication().service()
        }
    }
}