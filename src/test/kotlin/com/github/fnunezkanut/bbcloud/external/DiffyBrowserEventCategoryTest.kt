package com.github.fnunezkanut.bbcloud.external

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyBrowserEventCategoryTest {
    @Test
    fun `fromWireName - should map every wire name to its entry`() {
        assertSoftly {
            DiffyBrowserEventCategory.fromWireName("reviewer_added") shouldBe DiffyBrowserEventCategory.REVIEWER_ADDED
            DiffyBrowserEventCategory.fromWireName("approval") shouldBe DiffyBrowserEventCategory.APPROVAL
            DiffyBrowserEventCategory.fromWireName("bot_review_comment") shouldBe DiffyBrowserEventCategory.BOT_REVIEW_COMMENT
            DiffyBrowserEventCategory.fromWireName("human_comment") shouldBe DiffyBrowserEventCategory.HUMAN_COMMENT
            DiffyBrowserEventCategory.fromWireName("mention") shouldBe DiffyBrowserEventCategory.MENTION
            DiffyBrowserEventCategory.fromWireName("pr_updated") shouldBe DiffyBrowserEventCategory.PR_UPDATED
            DiffyBrowserEventCategory.fromWireName("merged") shouldBe DiffyBrowserEventCategory.MERGED
            DiffyBrowserEventCategory.fromWireName("other_bitbucket") shouldBe DiffyBrowserEventCategory.OTHER_BITBUCKET
        }
    }

    @Test
    fun `fromWireName - should map unknown and missing names to other bitbucket`() {
        assertSoftly {
            DiffyBrowserEventCategory.fromWireName("brand_new") shouldBe DiffyBrowserEventCategory.OTHER_BITBUCKET
            DiffyBrowserEventCategory.fromWireName(null) shouldBe DiffyBrowserEventCategory.OTHER_BITBUCKET
        }
    }
}