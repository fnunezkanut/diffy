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
    fun `isChromeExtensionOrigin - should accept only chrome extension origins`() {
        assertSoftly {
            isChromeExtensionOrigin(origin = "chrome-extension://abc") shouldBe true
            isChromeExtensionOrigin(origin = "https://bitbucket.org") shouldBe false
            isChromeExtensionOrigin(origin = null) shouldBe false
        }
    }
}