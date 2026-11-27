package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class OtherSettingsTextTest {
    @Test
    fun `heading - should read Other Settings`() {
        // GIVEN
        val key = "setup.section.otherSettings.title"

        // WHEN
        val result = DiffyBundle.message(key)

        // THEN
        result shouldBe "Other Settings"
    }

    @Test
    fun `editable diffs label and comment - should resolve to plain text without jargon`() {
        // GIVEN
        val labelKey = "setup.otherSettings.editableDiffs.label"
        val commentKey = "setup.otherSettings.editableDiffs.comment"

        // WHEN
        val label = DiffyBundle.message(labelKey)
        val comment = DiffyBundle.message(commentKey)

        // THEN
        assertSoftly {
            label shouldBe "Make pull request diffs editable"
            comment.shouldNotBeBlank()
            comment shouldNotContain "source side"
        }
    }

    @Test
    fun `auto-update main branches label and comment - should resolve to the agreed text`() {
        // WHEN
        val label = DiffyBundle.message("setup.otherSettings.autoUpdateMainBranches.label")
        val comment = DiffyBundle.message("setup.otherSettings.autoUpdateMainBranches.comment")

        // THEN
        assertSoftly {
            label shouldBe "Auto-update main branches"
            comment.shouldNotBeBlank()
            comment shouldContain "5 minutes"
        }
    }
}