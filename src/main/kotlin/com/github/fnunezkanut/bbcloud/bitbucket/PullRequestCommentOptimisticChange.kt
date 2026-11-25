package com.github.fnunezkanut.bbcloud.bitbucket

/**
 * Applies a resolve or unresolve to comments before Bitbucket confirmed it, so the UI can show the result at once. Only
 * the root comment of a thread carries the resolved state. A reload replaces the optimistic state with the server truth.
 */
object PullRequestCommentOptimisticChange {
    fun isOptimistic(change: CommentChange): Boolean {
        return change is CommentChange.Resolve || change is CommentChange.Unresolve
    }

    /** Returns [comments] itself when the change is not optimistic or no comment has the id. */
    fun apply(comments: List<PullRequestComment>, change: CommentChange): List<PullRequestComment> {
        val commentId = change.commentId
        if (!isOptimistic(change = change) || comments.none { comment -> comment.id == commentId }) {
            return comments
        }
        return comments.map { comment ->
            if (comment.id == commentId) applyToComment(comment = comment, change = change) else comment
        }
    }

    /** Returns [threads] itself when the change is not optimistic or no thread has the root id. */
    fun applyToThreads(threads: List<TimelineEntry.CommentThread>, change: CommentChange): List<TimelineEntry.CommentThread> {
        val commentId = change.commentId
        if (!isOptimistic(change = change) || threads.none { thread -> thread.root.id == commentId }) {
            return threads
        }
        return threads.map { thread ->
            if (thread.root.id == commentId) thread.copy(root = applyToComment(comment = thread.root, change = change)) else thread
        }
    }

    private fun applyToComment(comment: PullRequestComment, change: CommentChange): PullRequestComment {
        val resolves = change is CommentChange.Resolve
        return comment.copy(
            isResolved = resolves,
            isResolutionPending = resolves,
            resolvedBy = null,
            resolvedOn = null
        )
    }
}