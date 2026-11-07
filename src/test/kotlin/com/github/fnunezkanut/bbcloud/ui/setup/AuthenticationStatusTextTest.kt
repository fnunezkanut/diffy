package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.auth.DiffyAuthenticationMethod
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class AuthenticationStatusTextTest {
    @Test
    fun `build - should return not authenticated text for None`() {
        // GIVEN
        val method = DiffyAuthenticationMethod.None

        // WHEN
        val result = AuthenticationStatusText.build(method = method, workspaceSlug = "acme")

        // THEN
        // The workspace slug is ignored for the None state.
        result shouldBe DiffyBundle.message("setup.authentication.status.none")
    }

    @Test
    fun `build - should include workspace for ApiToken with slug`() {
        // GIVEN
        val method = DiffyAuthenticationMethod.ApiToken

        // WHEN
        val result = AuthenticationStatusText.build(method = method, workspaceSlug = "acme")

        // THEN
        result shouldBe "Authenticated using API Token. Workspace: acme"
    }

    @Test
    fun `build - should omit workspace for ApiToken without slug`() {
        // GIVEN
        val method = DiffyAuthenticationMethod.ApiToken

        // WHEN
        val result = AuthenticationStatusText.build(method = method, workspaceSlug = null)

        // THEN
        result shouldBe "Authenticated using API Token"
    }

    @Test
    fun `build - should include workspace for OAuth with slug`() {
        // GIVEN
        val method = DiffyAuthenticationMethod.OAuth

        // WHEN
        val result = AuthenticationStatusText.build(method = method, workspaceSlug = "acme")

        // THEN
        result shouldBe "Authenticated using Bitbucket oauth login. Workspace: acme"
    }

    @Test
    fun `build - should omit workspace when slug is blank`() {
        // GIVEN
        val method = DiffyAuthenticationMethod.ApiToken

        // WHEN
        val result = AuthenticationStatusText.build(method = method, workspaceSlug = "   ")

        // THEN
        result shouldBe "Authenticated using API Token"
    }
}