package com.github.fnunezkanut.bbcloud.settings

import com.intellij.openapi.options.Configurable
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Guards the scroll behavior of the settings page: without [Configurable.NoScroll] the Settings dialog wraps the page
 * in a scroll pane and the Compose settings screen renders as an empty page.
 */
@Tag("unit")
class DiffyToolsConfigurableTest {
    @Test
    fun `DiffyToolsConfigurable - should opt out of the Settings dialog scroll pane`() {
        // GIVEN
        val configurableClass = DiffyToolsConfigurable::class.java

        // WHEN
        val optsOutOfScrollPane = Configurable.NoScroll::class.java.isAssignableFrom(configurableClass)

        // THEN
        optsOutOfScrollPane shouldBe true
    }
}