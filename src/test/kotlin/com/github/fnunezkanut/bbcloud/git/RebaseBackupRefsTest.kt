package com.github.fnunezkanut.bbcloud.git

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@Tag("unit")
class RebaseBackupRefsTest {
    private val prefix = "refs/diffy/backups/pr-1/"

    @Test
    fun `headRef - should build the ref from the pull request id and epoch milliseconds`() {
        // GIVEN
        val createdAt = Instant.fromEpochMilliseconds(1_795_694_861_000)

        // WHEN
        val ref = RebaseBackupRefs.headRef(pullRequestId = 7, createdAt = createdAt)

        // THEN
        ref shouldBe "refs/diffy/backups/pr-7/1795694861000"
    }

    @Nested
    inner class RefsToPrune {
        @Test
        fun `should keep the newest snapshots and return older head and wip refs`() {
            // GIVEN
            val refs = (1L..7L).map { key -> "$prefix$key" } + listOf("${prefix}1-wip", "${prefix}7-wip")

            // WHEN
            val pruned = RebaseBackupRefs.refsToPrune(pullRequestId = 1, existingRefs = refs)

            // THEN
            pruned shouldContainExactlyInAnyOrder listOf("${prefix}1", "${prefix}1-wip", "${prefix}2")
        }

        @Test
        fun `should return nothing when there are at most keep snapshots`() {
            // GIVEN
            val refs = (1L..5L).map { key -> "$prefix$key" } + "${prefix}3-wip"

            // WHEN
            val pruned = RebaseBackupRefs.refsToPrune(pullRequestId = 1, existingRefs = refs)

            // THEN
            pruned.shouldBeEmpty()
        }

        @Test
        fun `should ignore refs of another pull request and refs with a key that is not numeric`() {
            // GIVEN
            val refs = listOf("${prefix}1", "${prefix}2", "${prefix}manual", "${prefix}abc-wip", "refs/diffy/backups/pr-12/0")

            // WHEN
            val pruned = RebaseBackupRefs.refsToPrune(pullRequestId = 1, existingRefs = refs, keep = 1)

            // THEN
            pruned shouldContainExactlyInAnyOrder listOf("${prefix}1")
        }

        @Test
        fun `should sort snapshots numerically, not lexically`() {
            // GIVEN
            val refs = listOf("${prefix}999", "${prefix}1000")

            // WHEN
            val pruned = RebaseBackupRefs.refsToPrune(pullRequestId = 1, existingRefs = refs, keep = 1)

            // THEN
            pruned shouldContainExactlyInAnyOrder listOf("${prefix}999")
        }
    }

    @Nested
    inner class RestoreCommand {
        @Test
        fun `should reset to the backup without a wip ref`() {
            // WHEN
            val command = RebaseBackupRefs.restoreCommand(backupRef = "${prefix}5")

            // THEN
            command shouldBe "git reset --hard ${prefix}5"
        }

        @Test
        fun `should also apply the wip ref when there is one`() {
            // WHEN
            val command = RebaseBackupRefs.restoreCommand(backupRef = "${prefix}5", wipRef = "${prefix}5-wip")

            // THEN
            command shouldBe "git reset --hard ${prefix}5 && git stash apply ${prefix}5-wip"
        }
    }

    @Test
    fun `zeroObjectId - should match the length of SHA-1 and SHA-256 ids`() {
        // WHEN
        val sha1Zero = RebaseBackupRefs.zeroObjectId(sha = "a".repeat(40))
        val sha256Zero = RebaseBackupRefs.zeroObjectId(sha = "b".repeat(64))

        // THEN
        sha1Zero shouldBe "0".repeat(40)
        sha256Zero shouldBe "0".repeat(64)
    }
}