package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.CustomCheckPhase
import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
import com.intellij.openapi.util.text.StringUtil

/** What the rows of the Checks card can link to; the web URL and the rebase availability are known only to the page. */
data class CheckRowContext(val pullRequestWebUrl: String?, val isRebaseAvailable: Boolean, val gitStatus: GitRowStatus)

/** Turns the mergeability checks into the rows of the Checks card, in the order Eligibility, Standard, Custom, Other. */
object CheckRowsBuilder {
    fun build(checks: List<PullRequestMergeCheck>, context: CheckRowContext): List<CheckRowState> {
        val rows = checks.filter { check -> isShown(check) }.map { check -> toRow(check = check, context = context) }
        val withPlaceholder = if (context.gitStatus == GitRowStatus.Loading && checks.none { check -> check is PullRequestMergeCheck.GitCheck }) {
            rows + checkingForConflictsRow()
        } else {
            rows
        }
        return withPlaceholder.sortedBy { row -> row.group }
    }

    /**
     * State, permission, Git and merge queue checks are eligibility checks: they appear only when they fail or block, plus a
     * Git check Bitbucket could not evaluate and a pull request that sits in a merge queue.
     */
    private fun isShown(check: PullRequestMergeCheck): Boolean {
        if (!check.isEligibility()) {
            return true
        }
        return when (check) {
            is PullRequestMergeCheck.GitCheck -> check.status == MergeCheckStatus.Failed || check.isBlocking || check.status == MergeCheckStatus.Unknown
            is PullRequestMergeCheck.QueueCheck -> check.status == MergeCheckStatus.Failed || check.isBlocking || check.isQueued
            else -> check.status == MergeCheckStatus.Failed || check.isBlocking
        }
    }

    private fun toRow(check: PullRequestMergeCheck, context: CheckRowContext): CheckRowState {
        val isAdvisory = check is PullRequestMergeCheck.StandardCheck && !check.isRequired
        val isOnMergePending = check.isOnMergePending()
        val isMuted = check is PullRequestMergeCheck.GitCheck && check.reason == null
        return CheckRowState(
            labelHtml = MergeCheckLabels.toHtml(MergeCheckLabels.labelFor(check)),
            tooltip = tooltip(check = check, isAdvisory = isAdvisory, isOnMergePending = isOnMergePending),
            status = check.status,
            isBlocking = check.isBlocking,
            iconKind = iconKind(check = check, isOnMergePending = isOnMergePending),
            secondaryText = secondaryText(check = check, isOnMergePending = isOnMergePending),
            isAdvisory = isAdvisory,
            isMuted = isMuted,
            group = groupOf(check),
            actions = actionsOf(check = check, context = context)
        )
    }

    private fun checkingForConflictsRow(): CheckRowState {
        return CheckRowState(
            labelHtml = DiffyBundle.message("main.branch.checks.git.loading"),
            tooltip = DiffyBundle.message("main.branch.checks.git.loading"),
            status = MergeCheckStatus.Pending,
            isBlocking = false,
            iconKind = CheckRowIcon.Pending,
            isMuted = true,
            group = CheckRowGroup.Eligibility
        )
    }

    private fun PullRequestMergeCheck.isOnMergePending(): Boolean {
        return this is PullRequestMergeCheck.CustomCheck && phase == CustomCheckPhase.OnMerge && status == MergeCheckStatus.Pending
    }

    private fun groupOf(check: PullRequestMergeCheck): CheckRowGroup {
        return when (check) {
            is PullRequestMergeCheck.StandardCheck -> CheckRowGroup.Standard
            is PullRequestMergeCheck.CustomCheck -> CheckRowGroup.Custom
            is PullRequestMergeCheck.UnrecognizedCheck -> CheckRowGroup.Other
            is PullRequestMergeCheck.StateCheck,
            is PullRequestMergeCheck.PermissionCheck,
            is PullRequestMergeCheck.GitCheck,
            is PullRequestMergeCheck.QueueCheck -> CheckRowGroup.Eligibility
        }
    }

    private fun iconKind(check: PullRequestMergeCheck, isOnMergePending: Boolean): CheckRowIcon {
        if (isOnMergePending || (check is PullRequestMergeCheck.GitCheck && check.reason == null)) {
            return CheckRowIcon.Neutral
        }
        if (check is PullRequestMergeCheck.QueueCheck && check.isQueued) {
            return CheckRowIcon.Passed
        }
        return when (check.status) {
            MergeCheckStatus.Passed -> CheckRowIcon.Passed
            MergeCheckStatus.Failed -> if (check.isBlocking) CheckRowIcon.Blocking else CheckRowIcon.Warning
            MergeCheckStatus.Pending -> CheckRowIcon.Pending
            MergeCheckStatus.Skipped, MergeCheckStatus.Unknown -> CheckRowIcon.Neutral
        }
    }

    private fun tooltip(check: PullRequestMergeCheck, isAdvisory: Boolean, isOnMergePending: Boolean): String {
        val key = when {
            isOnMergePending -> "main.branch.checks.onMerge.pending"
            isAdvisory && check.status == MergeCheckStatus.Failed -> "main.branch.checks.tooltip.failedAdvisory"
            else -> statusTooltipKey(check)
        }
        return DiffyBundle.message(key)
    }

    private fun statusTooltipKey(check: PullRequestMergeCheck): String {
        return when (check.status) {
            MergeCheckStatus.Passed -> {
                "main.branch.checks.tooltip.passed"
            }
            MergeCheckStatus.Failed -> {
                if (check.isBlocking) "main.branch.checks.tooltip.failedBlocking" else "main.branch.checks.tooltip.failedNotBlocking"
            }
            MergeCheckStatus.Pending -> {
                "main.branch.checks.tooltip.pending"
            }
            MergeCheckStatus.Skipped -> {
                "main.branch.checks.tooltip.skipped"
            }
            MergeCheckStatus.Unknown -> {
                "main.branch.checks.tooltip.unknown"
            }
        }
    }

    private fun secondaryText(check: PullRequestMergeCheck, isOnMergePending: Boolean): String? {
        val text = when {
            isOnMergePending -> DiffyBundle.message("main.branch.checks.onMerge.pending")
            check is PullRequestMergeCheck.CustomCheck -> check.message
            else -> MergeCheckLabels.progressText(check)
        }
        return text?.let { value -> StringUtil.escapeXmlEntities(value) }
    }

    private fun actionsOf(check: PullRequestMergeCheck, context: CheckRowContext): List<CheckRowAction> {
        return when (check) {
            is PullRequestMergeCheck.StandardCheck -> listOfNotNull(sectionActionOf(check))
            is PullRequestMergeCheck.QueueCheck -> listOfNotNull(context.pullRequestWebUrl?.let { url -> CheckRowAction.OpenInBitbucket(url) })
            is PullRequestMergeCheck.GitCheck -> gitActions(check = check, context = context)
            is PullRequestMergeCheck.StateCheck,
            is PullRequestMergeCheck.PermissionCheck,
            is PullRequestMergeCheck.CustomCheck,
            is PullRequestMergeCheck.UnrecognizedCheck -> emptyList()
        }
    }

    /** Approvals, tasks and builds link to the section that can fix them; a passing check has nothing to fix. */
    private fun sectionActionOf(check: PullRequestMergeCheck.StandardCheck): CheckRowAction? {
        if (check.status == MergeCheckStatus.Passed) {
            return null
        }
        val section = when (check.kind) {
            "minimum_approvals", "minimum_default_reviewer_approvals" -> CheckSection.Reviewers
            "resolved_tasks" -> CheckSection.Tasks
            "minimum_successful_builds", "no_failed_builds", "no_in_progress_builds" -> CheckSection.Builds
            else -> return null
        }
        return CheckRowAction.ScrollToSection(section)
    }

    private fun gitActions(check: PullRequestMergeCheck.GitCheck, context: CheckRowContext): List<CheckRowAction> {
        val isConflicted = check.reason == GitMergeabilityReason.Conflicts
        val isImpossible = check.reason == GitMergeabilityReason.MergeImpossible
        return buildList {
            if (isConflicted && context.isRebaseAvailable) {
                add(CheckRowAction.RebaseWithAi)
            }
            val webUrl = context.pullRequestWebUrl
            if ((isConflicted || isImpossible) && webUrl != null) {
                add(CheckRowAction.OpenConflictsInBrowser(webUrl))
            }
        }
    }
}