package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.git.MainBranchSkipReason
import com.github.fnunezkanut.bbcloud.git.MainBranchSyncStatus
import com.github.fnunezkanut.bbcloud.project.WatchedProjectMapping
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Tag("unit")
class WatchedProjectsMainSyncMergeTest {
    private val now = Instant.parse("2030-01-01T12:00:00Z")

    private fun row(slug: String): WatchedProjectRow {
        return buildWatchedProjectRow(
            mapping = WatchedProjectMapping(localFolderPath = "", remoteRepoSlug = slug, workspace = "acme", watched = true),
            organizationUrl = null,
            avatarUrl = null
        )
    }

    @Test
    fun `withMainBranchSync - should set the status of the matching row and keep null for the others`() {
        // GIVEN
        val status = MainBranchSyncStatus.UpToDate(branch = "main", checkedAt = now)

        // WHEN
        val rows = withMainBranchSync(rows = listOf(row("a"), row("b")), statuses = mapOf("a" to status))

        // THEN
        assertSoftly {
            rows[0].mainBranchSync shouldBe status
            rows[1].mainBranchSync shouldBe null
        }
    }

    @Test
    fun `withMainBranchSync - should clear a status that is gone`() {
        // GIVEN
        val withStatus = withMainBranchSync(
            rows = listOf(row("a")),
            statuses = mapOf("a" to MainBranchSyncStatus.UpToDate(branch = "main", checkedAt = now))
        )

        // WHEN
        val cleared = withMainBranchSync(rows = withStatus, statuses = emptyMap())

        // THEN
        cleared[0].mainBranchSync shouldBe null
    }

    @Test
    fun `formatRelative - should give a short age`() {
        // WHEN
        val ages = listOf(10.seconds, 2.minutes, 3.hours, 50.hours).map { age ->
            MainBranchSyncText.formatRelative(now = now, then = now - age)
        }

        // THEN
        ages shouldBe listOf("just now", "2 min ago", "3 h ago", "2 d ago")
    }

    @Test
    fun `formatRelative - should read just now for a time in the future`() {
        MainBranchSyncText.formatRelative(now = now, then = now + 5.minutes) shouldBe "just now"
    }

    @Test
    fun `warning and summary - should describe each result`() {
        // GIVEN
        val skipped = MainBranchSyncStatus.Skipped(
            branch = "main",
            reason = MainBranchSkipReason.UncommittedChanges,
            path = null,
            checkedAt = now
        )
        val failed = MainBranchSyncStatus.FetchFailed(branch = "main", checkedAt = now)

        // THEN
        assertSoftly {
            MainBranchSyncText.warning(skipped) shouldBe "main was not updated: the clone has uncommitted changes."
            MainBranchSyncText.warning(
                failed
            ).endsWith("Check your SSH access to Bitbucket and that your SSH key is loaded without a passphrase prompt.") shouldBe
                true
            MainBranchSyncText.summary(MainBranchSyncStatus.Updated(branch = "main", commits = 2, checkedAt = now), now) shouldBe "main updated just now"
            MainBranchSyncText.isWarning(skipped) shouldBe true
            MainBranchSyncText.isWarning(MainBranchSyncStatus.UpToDate(branch = "main", checkedAt = now)) shouldBe false
        }
    }
}