package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.vcs.editor.ComplexPathVirtualFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DiffyPullRequestPath(
    override val sessionId: String,
    override val projectHash: String,
    val workspaceUrl: String,
    val repositorySlug: String,
    val pullRequestId: Long
) : ComplexPathVirtualFileSystem.ComplexPath

object DiffyPullRequestPathSerializer : ComplexPathVirtualFileSystem.ComplexPathSerializer<DiffyPullRequestPath> {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override fun serialize(path: DiffyPullRequestPath): String {
        return json.encodeToString(DiffyPullRequestPath.serializer(), path.normalized())
    }

    override fun deserialize(rawPath: String): DiffyPullRequestPath {
        return json.decodeFromString(DiffyPullRequestPath.serializer(), rawPath).normalized()
    }

    private fun DiffyPullRequestPath.normalized(): DiffyPullRequestPath {
        return copy(workspaceUrl = normalizeWorkspaceUrl(workspaceUrl))
    }

    fun normalizeWorkspaceUrl(workspaceUrl: String): String {
        return workspaceUrl.trim().trimEnd('/')
    }
}