package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyBrowserSnapshotParserTest {
    private fun sample(): String {
        val stream = javaClass.classLoader.getResourceAsStream("browser-snapshot-sample.json")
        check(stream != null) { "browser-snapshot-sample.json is missing from the test classpath" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun parseValid(body: String): DiffyBrowserSnapshot {
        val result = DiffyBrowserSnapshotParser.parse(body = body)
        result.shouldBeInstanceOf<DiffyBrowserSnapshotParseResult.Valid>()
        return result.snapshot
    }

    private fun rejectedMessage(body: String): String {
        val result = DiffyBrowserSnapshotParser.parse(body = body)
        result.shouldBeInstanceOf<DiffyBrowserSnapshotParseResult.Rejected>()
        return result.message
    }

    @Test
    fun `parse - should read a valid document`() {
        // GIVEN the sample document
        // WHEN
        val snapshot = parseValid(body = sample())

        // THEN
        assertSoftly {
            snapshot.pullRequests.size shouldBe 2
            snapshot.notificationEvents.size shouldBe 3
            snapshot.skippedInvalidEntries shouldBe 0
            snapshot.meta.workspaces shouldBe listOf("acme-workspace")
            snapshot.meta.skippedNonBitbucket shouldBe 60
            snapshot.pullRequests[0].jiraKeys shouldBe listOf("PROJ-101", "PROJ-102")
            snapshot.pullRequests[0].reviewers.size shouldBe 2
            snapshot.pullRequests[0].otherParticipants.single().displayName shouldBe "PR Ranger"
            snapshot.pullRequests[1].draft shouldBe true
            snapshot.notificationEvents[1].category shouldBe DiffyBrowserEventCategory.BOT_REVIEW_COMMENT
            snapshot.notificationEvents[1].actorIsBot shouldBe true
            snapshot.notificationEvents[1].commentId shouldBe 897973569L
        }
    }

    @Test
    fun `parse - should map an unknown category to other bitbucket`() {
        // GIVEN the sample, whose third event has an unknown category
        // WHEN
        val snapshot = parseValid(body = sample())

        // THEN
        snapshot.notificationEvents[2].category shouldBe DiffyBrowserEventCategory.OTHER_BITBUCKET
    }

    @Test
    fun `parse - should reject a wrong schema version`() {
        rejectedMessage(body = """{"schemaVersion":2,"pullRequests":[],"notificationEvents":[]}""") shouldBe
            DiffyBrowserSnapshotParser.UNSUPPORTED_VERSION
    }

    @Test
    fun `parse - should reject a missing array`() {
        rejectedMessage(body = """{"schemaVersion":1,"pullRequests":[]}""") shouldBe DiffyBrowserSnapshotParser.MISSING_LISTS
    }

    @Test
    fun `parse - should reject malformed json`() {
        rejectedMessage(body = "{not json") shouldBe DiffyBrowserSnapshotParser.INVALID_BODY
    }

    @Test
    fun `parse - should reject too many pull requests`() {
        // GIVEN 501 pull requests
        val entries = (1..501).joinToString(separator = ",") { """{"id":$it,"repoSlug":"r"}""" }
        val body = """{"schemaVersion":1,"pullRequests":[$entries],"notificationEvents":[]}"""

        // WHEN / THEN
        rejectedMessage(body = body) shouldBe DiffyBrowserSnapshotParser.TOO_MANY_PULL_REQUESTS
    }

    @Test
    fun `parse - should skip and count entries with missing required fields`() {
        // GIVEN a pull request without id and an event without id
        val body =
            """
            {"schemaVersion":1,
             "pullRequests":[{"repoSlug":"r"},{"id":1,"repoSlug":"r"}],
             "notificationEvents":[{"occurredAt":"2026-11-06T17:30:44.112Z","category":"approval"}]}
            """.trimIndent()

        // WHEN
        val snapshot = parseValid(body = body)

        // THEN
        assertSoftly {
            snapshot.pullRequests.size shouldBe 1
            snapshot.notificationEvents.size shouldBe 0
            snapshot.skippedInvalidEntries shouldBe 2
        }
    }

    @Test
    fun `parse - should treat a missing jira key list as empty`() {
        val body = """{"schemaVersion":1,"pullRequests":[{"id":1,"repoSlug":"r"}],"notificationEvents":[]}"""

        parseValid(body = body).pullRequests.single().jiraKeys shouldBe emptyList()
    }

    @Test
    fun `parse - should read the truncated pull requests flag`() {
        // GIVEN a header that marks the pull request list as truncated
        val body = """{"schemaVersion":1,"meta":{"truncated":{"pullRequests":true,"notifications":false}},"pullRequests":[],"notificationEvents":[]}"""

        // WHEN / THEN
        parseValid(body = body).meta.truncatedPullRequests shouldBe true
    }

    @Test
    fun `parse - should read a false truncated pull requests flag`() {
        val body = """{"schemaVersion":1,"meta":{"truncated":{"pullRequests":false}},"pullRequests":[],"notificationEvents":[]}"""

        parseValid(body = body).meta.truncatedPullRequests shouldBe false
    }

    @Test
    fun `parse - should treat a missing truncated flag as false`() {
        // GIVEN the sample document, which has no truncated block, and a body without any meta
        val withoutMeta = """{"schemaVersion":1,"pullRequests":[],"notificationEvents":[]}"""

        // WHEN / THEN
        assertSoftly {
            parseValid(body = sample()).meta.truncatedPullRequests shouldBe false
            parseValid(body = withoutMeta).meta.truncatedPullRequests shouldBe false
        }
    }

    @Test
    fun `parse - should never echo input text in a rejection`() {
        // GIVEN a title that is too long and contains a marker
        val title = "SECRET-MARKER" + "x".repeat(600)
        val body = """{"schemaVersion":1,"pullRequests":[{"id":1,"repoSlug":"r","title":"$title"}],"notificationEvents":[]}"""

        // WHEN
        val message = rejectedMessage(body = body)

        // THEN
        assertSoftly {
            message shouldBe DiffyBrowserSnapshotParser.VALUE_TOO_LONG
            message.contains("SECRET-MARKER") shouldBe false
        }
    }

    @Test
    fun `timestamps - should parse both formats the extension sends`() {
        assertSoftly {
            DiffyBrowserTimestamps.parseOrNull("2026-11-07T19:06:10.612576+00:00")?.toEpochMilliseconds() shouldBe 1794078370612L
            DiffyBrowserTimestamps.parseOrNull("2026-11-06T17:30:44.112Z")?.toEpochMilliseconds() shouldBe 1793986244112L
            DiffyBrowserTimestamps.parseOrNull("nonsense") shouldBe null
            DiffyBrowserTimestamps.parseOrNull(null) shouldBe null
        }
    }
}