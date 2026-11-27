package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the "Auto-update main branches" loop when an IDE window opens. The call is idempotent, so several open projects
 * start only one loop.
 */
class DiffyMainBranchAutoUpdateStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        DiffyMainBranchAutoUpdateService.getInstance().ensureStarted()
    }
}