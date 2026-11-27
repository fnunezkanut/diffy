package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckPullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
import com.intellij.openapi.util.text.StringUtil

sealed interface MergeCheckLabel {
    /** A bundle message whose optional [count] is the configured threshold of the check. */
    data class Message(val key: String, val count: Int? = null) : MergeCheckLabel

    /** A bundle message with one user-authored [name] argument, such as a merge queue name; the name is escaped before it is shown as HTML. */
    data class Named(val key: String, val name: String) : MergeCheckLabel

    /** Plain text that is escaped before it is shown as HTML. */
    data class Text(val text: String) : MergeCheckLabel
}

/**
 * The "X of Y checks passed" summary. Only checks that are required and not skipped are counted (the eligibility checks
 * for state, permission, Git and merge queue are excluded), while [isBlocking] is true when any check blocks merging.
 */
data class MergeCheckSummary(val passed: Int, val total: Int, val isBlocking: Boolean) {
    companion object {
        fun of(checks: List<PullRequestMergeCheck>): MergeCheckSummary {
            val counted = checks.filter { check -> !check.isEligibility() && check.isRequired && check.status != MergeCheckStatus.Skipped }
            return MergeCheckSummary(
                passed = counted.count { check -> check.status == MergeCheckStatus.Passed },
                total = counted.size,
                isBlocking = checks.any { check -> check.isBlocking }
            )
        }
    }
}

/** State, permission, Git and merge queue checks say whether merging is possible at all; they are not shown in the count. */
fun PullRequestMergeCheck.isEligibility(): Boolean {
    return when (this) {
        is PullRequestMergeCheck.StateCheck,
        is PullRequestMergeCheck.PermissionCheck,
        is PullRequestMergeCheck.GitCheck,
        is PullRequestMergeCheck.QueueCheck -> true
        is PullRequestMergeCheck.StandardCheck,
        is PullRequestMergeCheck.CustomCheck,
        is PullRequestMergeCheck.UnrecognizedCheck -> false
    }
}

object MergeCheckLabels {
    fun labelFor(check: PullRequestMergeCheck): MergeCheckLabel {
        return when (check) {
            is PullRequestMergeCheck.StandardCheck -> {
                standardLabel(check)
            }
            is PullRequestMergeCheck.StateCheck -> {
                stateLabel(check)
            }
            is PullRequestMergeCheck.PermissionCheck -> {
                MergeCheckLabel.Message(key = "main.branch.checks.permission")
            }
            is PullRequestMergeCheck.GitCheck -> {
                gitLabel(check)
            }
            is PullRequestMergeCheck.QueueCheck -> {
                queueLabel(check)
            }
            is PullRequestMergeCheck.CustomCheck -> {
                check.name?.let { name -> MergeCheckLabel.Text(name) } ?: MergeCheckLabel.Message(key = "main.branch.checks.kind.custom")
            }
            is PullRequestMergeCheck.UnrecognizedCheck -> {
                MergeCheckLabel.Text(humanize(check.type))
            }
        }
    }

    /**
     * The observed value of a counted standard check, such as "1 / 2" for approvals or "3 commits behind (max 1)";
     * null when Bitbucket sent no observed value or the kind has no count.
     */
    fun progressText(check: PullRequestMergeCheck): String? {
        if (check !is PullRequestMergeCheck.StandardCheck) {
            return null
        }
        val observed = check.observedValue ?: return null
        return when (check.kind) {
            "minimum_approvals", "minimum_default_reviewer_approvals", "minimum_successful_builds" -> {
                DiffyBundle.message("main.branch.checks.progress.observed", observed, check.requiredValue ?: 1)
            }
            "maximum_commits_behind" -> {
                DiffyBundle.message("main.branch.checks.progress.commitsBehind", observed, check.requiredValue ?: 0)
            }
            else -> {
                null
            }
        }
    }

    private fun standardLabel(check: PullRequestMergeCheck.StandardCheck): MergeCheckLabel {
        return when (check.kind) {
            "minimum_approvals" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumApprovals", count = check.requiredValue ?: 1)
            }
            "minimum_default_reviewer_approvals" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumDefaultReviewerApprovals", count = check.requiredValue ?: 1)
            }
            "minimum_successful_builds" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumSuccessfulBuilds", count = check.requiredValue ?: 1)
            }
            "maximum_commits_behind" -> {
                val maximum = check.requiredValue ?: 0
                if (maximum == 0) {
                    MergeCheckLabel.Message(key = "main.branch.checks.kind.noCommitsBehind")
                } else {
                    MergeCheckLabel.Message(key = "main.branch.checks.kind.maximumCommitsBehind", count = maximum)
                }
            }
            "resolved_tasks" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.resolvedTasks")
            }
            "no_failed_builds" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.noFailedBuilds")
            }
            "no_in_progress_builds" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.noInProgressBuilds")
            }
            "no_changes_requested" -> {
                MergeCheckLabel.Message(key = "main.branch.checks.kind.noChangesRequested")
            }
            else -> {
                MergeCheckLabel.Text(humanize(check.kind))
            }
        }
    }

    private fun stateLabel(check: PullRequestMergeCheck.StateCheck): MergeCheckLabel {
        val key = when (check.state) {
            MergeCheckPullRequestState.Draft -> "main.branch.checks.state.draft"
            MergeCheckPullRequestState.Merged -> "main.branch.checks.state.merged"
            MergeCheckPullRequestState.Declined -> "main.branch.checks.state.declined"
            MergeCheckPullRequestState.Superseded -> "main.branch.checks.state.superseded"
            MergeCheckPullRequestState.Open -> "main.branch.checks.state.queued"
            MergeCheckPullRequestState.Unknown -> "main.branch.checks.state.unknown"
        }
        return MergeCheckLabel.Message(key = key)
    }

    private fun gitLabel(check: PullRequestMergeCheck.GitCheck): MergeCheckLabel {
        val key = when (check.reason) {
            GitMergeabilityReason.Clean -> "main.branch.checks.git.clean"
            GitMergeabilityReason.Conflicts -> "main.branch.checks.git.conflicts"
            GitMergeabilityReason.MergeImpossible -> "main.branch.checks.git.mergeImpossible"
            null -> "main.branch.checks.git.unknown"
        }
        return MergeCheckLabel.Message(key = key)
    }

    private fun queueLabel(check: PullRequestMergeCheck.QueueCheck): MergeCheckLabel {
        val queueName = check.queueName
        return when {
            check.isQueued && queueName != null -> MergeCheckLabel.Named(key = "main.branch.checks.queue.queuedIn", name = queueName)
            check.isQueued -> MergeCheckLabel.Message(key = "main.branch.checks.queue.queued")
            check.queueState.equals(QUEUE_STATE_DRAINING, ignoreCase = true) -> MergeCheckLabel.Message(key = "main.branch.checks.queue.draining")
            check.queueState.equals(QUEUE_STATE_SUSPENDED, ignoreCase = true) -> MergeCheckLabel.Message(key = "main.branch.checks.queue.suspended")
            else -> MergeCheckLabel.Message(key = "main.branch.checks.queue.required")
        }
    }

    /** Turns an undocumented `check.kind` such as `no_failed_builds` into "No failed builds". */
    fun humanize(kind: String): String {
        val words = kind.split('_', '-', ' ').filter { word -> word.isNotBlank() }
        return words.joinToString(separator = " ").lowercase().replaceFirstChar { first -> first.uppercase() }
    }

    fun toHtml(label: MergeCheckLabel): String {
        return when (label) {
            is MergeCheckLabel.Message -> {
                if (label.count == null) DiffyBundle.message(label.key) else DiffyBundle.message(label.key, label.count)
            }
            is MergeCheckLabel.Named -> {
                DiffyBundle.message(label.key, StringUtil.escapeXmlEntities(label.name))
            }
            is MergeCheckLabel.Text -> {
                StringUtil.escapeXmlEntities(label.text)
            }
        }
    }

    /** The label without markup, for tooltips. */
    fun toPlainText(label: MergeCheckLabel): String {
        return when (label) {
            is MergeCheckLabel.Message -> {
                val message = if (label.count == null) DiffyBundle.message(label.key) else DiffyBundle.message(label.key, label.count)
                StringUtil.removeHtmlTags(message)
            }
            is MergeCheckLabel.Named -> {
                StringUtil.removeHtmlTags(DiffyBundle.message(label.key, label.name))
            }
            is MergeCheckLabel.Text -> {
                label.text
            }
        }
    }

    private const val QUEUE_STATE_DRAINING = "DRAINING"
    private const val QUEUE_STATE_SUSPENDED = "SUSPENDED"
}