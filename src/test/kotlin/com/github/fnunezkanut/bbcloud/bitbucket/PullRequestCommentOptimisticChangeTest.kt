package com.github.fnunezkanut.bbcloud.bitbucket

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@Tag("unit")
class PullRequestCommentOptimisticChangeTest {
    private val resolver = ActivityUser(uuid = "{resolver}", displayName = "Resolver", avatarUrl = null)

    @Nested
    inner class IsOptimisticTests {
        @Test
        fun `isOptimistic - should be true only for resolve and unresolve`() {
            // GIVEN
            val changes = listOf(
                CommentChange.Resolve(commentId = 1),
                CommentChange.Unresolve(commentId = 1),
                CommentChange.Reply(commentId = 1, content = "x"),
                CommentChange.Edit(commentId = 1, content = "x"),
                CommentChange.Delete(commentId = 1),
                CommentChange.Create(content = "x")
            )

            // WHEN
            val results = changes.map { change -> PullRequestCommentOptimisticChange.isOptimistic(change = change) }

            // THEN
            results shouldBe listOf(true, true, false, false, false, false)
        }
    }

    @Nested
    inner class ApplyTests {
        @Test
        fun `apply - should mark the root resolved and pending without a resolver`() {
            // GIVEN
            val root = comment(id = 1)
            val reply = comment(id = 2, parentId = 1)

            // WHEN
            val result = PullRequestCommentOptimisticChange.apply(comments = listOf(root, reply), change = CommentChange.Resolve(commentId = 1))

            // THEN
            assertSoftly {
                result[0].isResolved shouldBe true
                result[0].isResolutionPending shouldBe true
                result[0].resolvedBy shouldBe null
                result[0].resolvedOn shouldBe null
                result[1] shouldBe reply
            }
        }

        @Test
        fun `apply - should reopen a resolved root and clear the resolver`() {
            // GIVEN
            val resolved = comment(id = 1).copy(isResolved = true, resolvedBy = resolver, resolvedOn = Instant.parse("2026-11-01T00:00:00Z"))

            // WHEN
            val result = PullRequestCommentOptimisticChange.apply(comments = listOf(resolved), change = CommentChange.Unresolve(commentId = 1))

            // THEN
            assertSoftly(result.single()) {
                isResolved shouldBe false
                isResolutionPending shouldBe false
                resolvedBy shouldBe null
                resolvedOn shouldBe null
            }
        }

        @Test
        fun `apply - should return the same list for an unknown comment id`() {
            // GIVEN
            val comments = listOf(comment(id = 1))

            // WHEN
            val result = PullRequestCommentOptimisticChange.apply(comments = comments, change = CommentChange.Resolve(commentId = 99))

            // THEN
            (result === comments) shouldBe true
        }

        @Test
        fun `apply - should return the same list for a change that is not optimistic`() {
            // GIVEN
            val comments = listOf(comment(id = 1))

            // WHEN
            val result = PullRequestCommentOptimisticChange.apply(comments = comments, change = CommentChange.Delete(commentId = 1))

            // THEN
            (result === comments) shouldBe true
        }
    }

    @Nested
    inner class ApplyToThreadsTests {
        @Test
        fun `applyToThreads - should change only the matching thread root`() {
            // GIVEN
            val first = TimelineEntry.CommentThread(root = comment(id = 1), replies = listOf(comment(id = 2, parentId = 1)))
            val second = TimelineEntry.CommentThread(root = comment(id = 3), replies = emptyList())

            // WHEN
            val result = PullRequestCommentOptimisticChange.applyToThreads(threads = listOf(first, second), change = CommentChange.Resolve(commentId = 1))

            // THEN
            assertSoftly {
                result[0].root.isResolved shouldBe true
                result[0].replies shouldBe first.replies
                result[1] shouldBe second
            }
        }

        @Test
        fun `applyToThreads - should return the same list when no thread matches`() {
            // GIVEN
            val threads = listOf(TimelineEntry.CommentThread(root = comment(id = 1), replies = emptyList()))

            // WHEN
            val result = PullRequestCommentOptimisticChange.applyToThreads(threads = threads, change = CommentChange.Resolve(commentId = 5))

            // THEN
            (result === threads) shouldBe true
        }
    }

    private fun comment(id: Long, parentId: Long? = null): PullRequestComment {
        return PullRequestComment(
            id = id,
            parentId = parentId,
            createdOn = Instant.parse("2026-10-31T00:00:00Z"),
            author = null,
            html = "<p>text</p>",
            inlinePath = null,
            inlineLine = null,
            inlineSide = null,
            isResolved = false,
            isDeleted = false,
            isPending = false
        )
    }
}