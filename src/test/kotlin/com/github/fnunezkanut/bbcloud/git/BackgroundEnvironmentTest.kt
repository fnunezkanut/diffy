package com.github.fnunezkanut.bbcloud.git

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class BackgroundEnvironmentTest {
    @Test
    fun `backgroundEnvironment - should disable prompts and set batch mode ssh`() {
        // WHEN
        val environment = backgroundEnvironment(parent = emptyMap())

        // THEN
        assertSoftly {
            environment["GIT_TERMINAL_PROMPT"] shouldBe "0"
            environment["GCM_INTERACTIVE"] shouldBe "never"
            environment["GIT_SSH_COMMAND"] shouldBe "ssh -o BatchMode=yes -o ConnectTimeout=15"
        }
    }

    @Test
    fun `backgroundEnvironment - should leave the users ssh setup alone`() {
        // WHEN
        val withCommand = backgroundEnvironment(parent = mapOf("GIT_SSH_COMMAND" to "ssh -i key"))
        val withSsh = backgroundEnvironment(parent = mapOf("GIT_SSH" to "/usr/bin/ssh"))

        // THEN
        assertSoftly {
            withCommand.containsKey("GIT_SSH_COMMAND") shouldBe false
            withSsh.containsKey("GIT_SSH_COMMAND") shouldBe false
            withCommand["GIT_TERMINAL_PROMPT"] shouldBe "0"
        }
    }
}