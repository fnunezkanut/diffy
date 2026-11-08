package com.github.fnunezkanut.bbcloud.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyOAuthConfigTest {
    @Test
    fun `REDIRECT_URI - should match the callback host, port and path`() {
        // GIVEN the callback constants
        val expected = "http://${DiffyOAuthConfig.CALLBACK_HOST}:${DiffyOAuthConfig.CALLBACK_PORT}${DiffyOAuthConfig.CALLBACK_PATH}"

        // WHEN / THEN
        DiffyOAuthConfig.REDIRECT_URI shouldBe expected
    }
}