package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class SettingsUiStateTest {
    @Test
    fun `authenticationMethod - should mirror the working method None`() {
        // GIVEN
        val state = SettingsUiState(pluginVersionFooter = "x", workingMethod = DiffyAuthenticationMethod.None)

        // WHEN
        val method = state.authenticationMethod

        // THEN
        method shouldBe DiffyAuthenticationMethod.None
    }

    @Test
    fun `authenticationMethod - should mirror the working method OAuth`() {
        // GIVEN
        val state = SettingsUiState(pluginVersionFooter = "x", workingMethod = DiffyAuthenticationMethod.OAuth)

        // WHEN
        val method = state.authenticationMethod

        // THEN
        method shouldBe DiffyAuthenticationMethod.OAuth
    }

    @Test
    fun `hasStoredButNotWorkingCredentials - should reflect stored-but-not-working combinations`() {
        assertSoftly {
            // Nothing stored: not a "stored but not working" state.
            SettingsUiState(pluginVersionFooter = "x").hasStoredButNotWorkingCredentials shouldBe false

            // OAuth stored but nothing works online.
            SettingsUiState(
                pluginVersionFooter = "x",
                hasOAuthTokens = true,
                workingMethod = DiffyAuthenticationMethod.None
            ).hasStoredButNotWorkingCredentials shouldBe true

            // A working method means it is not in the warning state.
            SettingsUiState(
                pluginVersionFooter = "x",
                hasOAuthTokens = true,
                workingMethod = DiffyAuthenticationMethod.OAuth
            ).hasStoredButNotWorkingCredentials shouldBe false
        }
    }
}