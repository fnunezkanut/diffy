package com.github.fnunezkanut.bbcloud.external

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyPingBodyTest {
    private fun ping(origin: String?, workspace: String?): JsonObject {
        return JsonParser.parseString(DiffyPingBody.build(origin = origin, version = "1.0", workspace = workspace)).asJsonObject
    }

    @Test
    fun `build - should include the workspace for the diffy extension only`() {
        // GIVEN / WHEN
        val allowed = ping(origin = DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN, workspace = "acme-workspace")
        val other = ping(origin = "chrome-extension://someoneelse", workspace = "acme-workspace")
        val none = ping(origin = null, workspace = "acme-workspace")

        // THEN
        assertSoftly {
            allowed.get("workspace").asString shouldBe "acme-workspace"
            allowed.get("plugin").asString shouldBe "diffy"
            other.has("workspace") shouldBe false
            none.has("workspace") shouldBe false
        }
    }

    @Test
    fun `build - should write a missing workspace as json null`() {
        val body = ping(origin = DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN, workspace = null)

        assertSoftly {
            body.has("workspace") shouldBe true
            body.get("workspace").isJsonNull shouldBe true
        }
    }

    @Test
    fun `build - should include the workspace for a firefox extension origin`() {
        // GIVEN / WHEN
        val firefox = ping(origin = "moz-extension://0a1b2c3d-1111-2222-3333-444455556666", workspace = "acme-workspace")

        // THEN
        firefox.get("workspace").asString shouldBe "acme-workspace"
    }

    @Test
    fun `isBrowserExtensionOrigin - should accept chrome and firefox extension origins only`() {
        assertSoftly {
            isBrowserExtensionOrigin(origin = "chrome-extension://abc") shouldBe true
            isBrowserExtensionOrigin(origin = "moz-extension://abc") shouldBe true
            isBrowserExtensionOrigin(origin = "https://bitbucket.org") shouldBe false
            isBrowserExtensionOrigin(origin = null) shouldBe false
        }
    }

    @Test
    fun `isDiffyExtensionOrigin - should accept the diffy chrome id and any firefox extension`() {
        assertSoftly {
            isDiffyExtensionOrigin(origin = DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN) shouldBe true
            isDiffyExtensionOrigin(origin = "moz-extension://abc") shouldBe true
            isDiffyExtensionOrigin(origin = "moz-extension://") shouldBe false
            isDiffyExtensionOrigin(origin = "chrome-extension://someoneelse") shouldBe false
            isDiffyExtensionOrigin(origin = "https://bitbucket.org") shouldBe false
            isDiffyExtensionOrigin(origin = null) shouldBe false
        }
    }
}