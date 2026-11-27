package com.github.fnunezkanut.bbcloud.ui.air

import com.github.fnunezkanut.bbcloud.git.RebaseBackupRefs

/**
 * Builds the agent prompt put into a new JetBrains Air chat by "Rebase onto latest with AI". The prompt asks the agent
 * to stash, rebase the source branch onto the latest destination branch, resolve conflicts, verify, force push with
 * lease and restore the stash. It is an agent prompt, not UI text, so it is not localized.
 *
 * Diffy saves `HEAD` under a backup ref before the prompt is built. Step 1 makes the agent confirm that `HEAD` still
 * is that commit, a rule keeps the agent away from `refs/diffy/`, and the summary names the backup ref and the command
 * that restores it.
 *
 * Branch names come from Bitbucket and can contain backticks, so every value is rendered as a Markdown code span whose
 * fence is longer than any backtick run inside it.
 */
internal object AirRebasePullRequestPrompt {
    private const val QUOTE = '"'
    private const val NEW_LINE = '\n'
    private const val RULES_HEADER = "Rules:"
    private const val SEPARATE_COMMANDS_RULE =
        "- Run every git command as its own separate command. Do not join commands with ;, &&, || or pipes, because the " +
            "terminal tool can pass them to git as arguments and fail."
    private const val NO_PERMISSION_RULE = "- Do not ask for permission between steps or for routine conflict resolutions."
    private const val STOP_RULE =
        "- Stop and ask me only if: (a) a conflict has unclear business or domain intent where two valid interpretations " +
            "exist, (b) tests or builds fail after resolving conflicts, (c) `git push --force-with-lease` is rejected due to " +
            "remote changes, or (d) a check in steps 1, 2, or 8 tells you to stop."
    private const val ABORT_RULE =
        "- If you stop before the rebase completes, run `git rebase --abort` first unless I tell you otherwise, then " +
            "restore the stash from step 3 the same way as in step 8."
    private const val NO_SIDE_EFFECTS_RULE =
        "- Do not commit the stashed changes, do not create new branches, and do not change the pull request on Bitbucket."
    private const val BACKUP_RULE = "- Do not delete, move or update any ref under `refs/diffy/`; it holds the backup of this branch."
    private const val CONFLICT_STEP =
        "If merge conflicts occur: resolve them directly without asking for confirmation on each file or step. Stage the " +
            "resolved files and run `git rebase --continue` until all conflicts are resolved and the rebase completes."
    private const val VERIFY_STEP =
        "Once the rebase finishes cleanly, run the relevant test suite or build check to verify that nothing is broken. " +
            "Follow the build and test instructions in AGENTS.md, CLAUDE.md, or the README of the repository when present."

    fun build(request: RebasePullRequestRequest): String {
        val stashMessage = "diffy: before rebase of PR #${request.pullRequestId}"
        val quotedStashMessage = "$QUOTE$stashMessage$QUOTE"
        val source = codeSpan(text = request.sourceBranch)
        val remote = request.remoteName
        return buildString {
            append("Rebase the branch of Bitbucket pull request #${request.pullRequestId} ($source) onto the latest ")
            append(codeSpan(text = "$remote/${request.destinationBranch}"))
            append(location(repositoryPath = request.repositoryPath))
            append(", using this exact sequence:").append(NEW_LINE).append(NEW_LINE)
            append("1. Run ").append(codeSpan(text = "git fetch $remote")).append(". Then run ")
            append(codeSpan(text = "git branch --show-current")).append(" as a separate command and confirm that the current branch is ")
            append(source).append(". This directory is a git work tree created for this pull request, so do not switch ")
            append("branches: if the current branch is not ").append(source)
            append(" (including a detached HEAD), stop and ask me. ")
            append("Then run ").append(codeSpan(text = "git rev-parse HEAD")).append(" as a separate command and confirm that it prints ")
            append(codeSpan(text = request.headSha)).append(". Diffy saved this commit as ").append(codeSpan(text = request.backupRef))
            append(" before this prompt was created. If it prints anything else, the branch changed after the backup was taken: ")
            append("stop and ask me.").append(NEW_LINE)
            append("2. Run ").append(codeSpan(text = "git rev-list --count HEAD..$remote/${request.sourceBranch}"))
            append(". If the result is not 0, the remote branch has commits that the local branch does not have: stop and ")
            append("ask me, because a force push would discard them.").append(NEW_LINE)
            append("3. Run ").append(codeSpan(text = "git status --porcelain")).append(". If the output is not empty, run ")
            append(codeSpan(text = "git stash push --include-untracked --message $quotedStashMessage"))
            append(" and remember that you created this stash. If the output is empty, do not create a stash.").append(NEW_LINE)
            append("4. Run ").append(codeSpan(text = "git rebase $remote/${request.destinationBranch}")).append('.').append(NEW_LINE)
            append("5. ").append(CONFLICT_STEP).append(NEW_LINE)
            append("6. ").append(VERIFY_STEP).append(NEW_LINE)
            append("7. Run ").append(codeSpan(text = "git push --force-with-lease $remote ${request.sourceBranch}"))
            append(" to update the remote branch.").append(NEW_LINE)
            append("8. If you created a stash in step 3, restore that exact stash. The stash list is shared by every work ")
            append("tree of this repository, so do not assume it is ").append(codeSpan(text = "stash@{0}")).append(": run ")
            append(codeSpan(text = "git stash list")).append(", find the entry whose message is $quotedStashMessage, and run ")
            append(codeSpan(text = "git stash pop stash@{<n>}")).append(" for that entry. If the pop has conflicts, resolve ")
            append("them so the uncommitted changes are kept, and do not drop the stash until they are; if the intent is ")
            append("unclear, stop and ask me.").append(NEW_LINE).append(NEW_LINE)
            append(RULES_HEADER).append(NEW_LINE)
            append(SEPARATE_COMMANDS_RULE).append(NEW_LINE)
            append(NO_PERMISSION_RULE).append(NEW_LINE)
            append(STOP_RULE).append(NEW_LINE)
            append(ABORT_RULE).append(NEW_LINE)
            append(NO_SIDE_EFFECTS_RULE).append(NEW_LINE)
            append(BACKUP_RULE).append(NEW_LINE)
            append(summaryRule(backupRef = request.backupRef, wipBackupRef = request.wipBackupRef))
        }
    }

    private fun summaryRule(backupRef: String, wipBackupRef: String?): String {
        val wipPart = if (wipBackupRef == null) "" else " and the uncommitted changes backup ${codeSpan(text = wipBackupRef)}"
        val restoreCommand = RebaseBackupRefs.restoreCommand(backupRef = backupRef, wipRef = wipBackupRef)
        return "- At the end, summarize all conflicted files and how each was resolved, say whether a stash was created and " +
            "restored, and name the backup ref ${codeSpan(text = backupRef)}$wipPart with the command that restores the " +
            "branch to its state before the rebase: ${codeSpan(text = restoreCommand)}."
    }

    private fun location(repositoryPath: String?): String {
        if (repositoryPath == null) {
            return ", in the current working directory"
        }
        return ", in the local repository at ${codeSpan(text = repositoryPath)}"
    }

    /**
     * Wraps [text] in a Markdown code span whose fence is one backtick longer than the longest backtick run in [text].
     * Line breaks become spaces, and a space pads text that starts or ends with a backtick, as Markdown requires.
     */
    internal fun codeSpan(text: String): String {
        val single = text.replace('\n', ' ').replace('\r', ' ')
        val longestRun = Regex("`+").findAll(single).maxOfOrNull { match -> match.value.length } ?: 0
        val fence = "`".repeat(longestRun + 1)
        val padded = if (single.startsWith('`') || single.endsWith('`')) " $single " else single
        return fence + padded + fence
    }
}