package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import io.kotest.matchers.doubles.shouldBeLessThan
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class WorktreeBarWeightTest {
    @Test
    fun `weights - should stack blue above orange above review`() {
        WorktreeBarWeights.BLUE shouldBeLessThan WorktreeBarWeights.UNPUSHED
        WorktreeBarWeights.UNPUSHED shouldBeLessThan WorktreeBarWeights.REVIEW
    }
}