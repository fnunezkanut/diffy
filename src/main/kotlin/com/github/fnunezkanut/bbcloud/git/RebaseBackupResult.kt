package com.github.fnunezkanut.bbcloud.git

/** The outcome of [RebaseBackupWriter.create]. */
sealed interface RebaseBackupResult {
    /**
     * @param headSha the `HEAD` commit that was saved.
     * @param headRef the backup ref that points at [headSha].
     * @param wipRef the ref of the uncommitted tracked changes, or null when there were none or they could not be saved.
     */
    data class Created(val headSha: String, val headRef: String, val wipRef: String?) : RebaseBackupResult

    /**
     * @param reason what git printed, without the command line echo, for the log. The commands are local and never touch
     * the network, so it holds no credentials.
     */
    data class Failed(val reason: String) : RebaseBackupResult
}