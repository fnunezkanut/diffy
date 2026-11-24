package com.github.fnunezkanut.bbcloud.git

import java.nio.file.Path

sealed interface ProjectCloneResult {
    /** [branch] is the branch checked out after the clone, or null when HEAD could not be read. */
    data class Success(val checkoutPath: Path, val branch: String?) : ProjectCloneResult

    data class TargetExists(val checkoutPath: Path) : ProjectCloneResult

    data object InvalidTarget : ProjectCloneResult

    data class Failed(val output: String) : ProjectCloneResult
}