package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A repository verification the Pull Requests remembers, so that re-opening the tab within
 * [VERIFICATION_REUSE_WINDOW] does not repeat the repository GET. Only a Found status is remembered. A missing or
 * broken repository is always checked again.
 */
data class VerifiedRepository(
    val projectBasePath: String?,
    val organizationUrl: String,
    val status: ProjectRepositoryStatus.Found,
    val verifiedAt: TimeSource.Monotonic.ValueTimeMark
)

/** How long a verified repository is reused by automatic tab loads. The Refresh button always verifies again. */
internal val VERIFICATION_REUSE_WINDOW: Duration = 5.minutes

/**
 * Whether [verified] still answers for this project and organization. A null or negative age never reuses, so an
 * unknown age is verified again.
 */
internal fun canReuseVerification(verified: VerifiedRepository?, projectBasePath: String?, organizationUrl: String, elapsed: Duration?): Boolean {
    if (verified == null || elapsed == null || elapsed.isNegative()) {
        return false
    }
    return verified.projectBasePath == projectBasePath &&
        verified.organizationUrl == organizationUrl &&
        elapsed < VERIFICATION_REUSE_WINDOW
}