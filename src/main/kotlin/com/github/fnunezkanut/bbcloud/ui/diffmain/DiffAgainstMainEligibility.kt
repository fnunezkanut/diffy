package com.github.fnunezkanut.bbcloud.ui.diffmain

import com.github.fnunezkanut.bbcloud.git.GitMainBranchLocator
import com.github.fnunezkanut.bbcloud.git.MainBranchChooser
import com.github.fnunezkanut.bbcloud.git.MainBranchRef
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager

/** Decides whether "Diff this file against main" is offered for a file, and against which branch. */
internal object DiffAgainstMainEligibility {
    /** True when [currentBranchName] is a named branch other than `main` and `master`. Detached `HEAD` is null. */
    fun isOnFeatureBranch(currentBranchName: String?): Boolean {
        return currentBranchName != null && !MainBranchChooser.isMainBranchName(branchName = currentBranchName)
    }

    /** True when the checked-out commit is the commit of the main branch, so there is nothing to compare. */
    fun isAtMainCommit(currentRevision: String?, mainCommitHash: String?): Boolean {
        if (currentRevision == null || mainCommitHash == null) {
            return false
        }
        return currentRevision.equals(mainCommitHash, ignoreCase = true)
    }

    /** The platform-free rule for the file itself: local, not a directory, and not binary. */
    fun isEligibleFileKind(isInLocalFileSystem: Boolean, isDirectory: Boolean, isBinary: Boolean): Boolean {
        return isInLocalFileSystem && !isDirectory && !isBinary
    }

    /**
     * The main branch to diff [file] against, or null when the action must be hidden. Runs on a background thread. It
     * starts a git process only when both `main` and `master` exist and `HEAD` or one of them moved since the last call.
     */
    fun resolve(project: Project, file: VirtualFile): MainBranchRef? {
        val fileKindEligible = isEligibleFileKind(
            isInLocalFileSystem = file.isInLocalFileSystem,
            isDirectory = file.isDirectory,
            isBinary = file.fileType.isBinary
        )
        if (!fileKindEligible) {
            return null
        }
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
        if (!isOnFeatureBranch(currentBranchName = repository.currentBranchName)) {
            return null
        }
        if (!GitMainBranchLocator.hasMainBranchRef(repository = repository)) {
            return null
        }
        if (ChangeListManager.getInstance(project).isIgnoredFile(file)) {
            return null
        }
        val mainRef = GitMainBranchLocator.locateCached(project = project, repository = repository) ?: return null
        if (isAtMainCommit(currentRevision = repository.currentRevision, mainCommitHash = mainRef.commitHash)) {
            return null
        }
        return mainRef
    }
}