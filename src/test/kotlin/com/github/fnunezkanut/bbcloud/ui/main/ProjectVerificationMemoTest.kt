package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Tag("unit")
class ProjectVerificationMemoTest {
    private val verified = VerifiedRepository(
        projectBasePath = "/Users/me/Projects/payments",
        organizationUrl = "https://bitbucket.org/acme/",
        status = ProjectRepositoryStatus.Found(repositoryWebUrl = "https://bitbucket.org/acme/payments", avatarUrl = null),
        verifiedAt = TimeSource.Monotonic.markNow()
    )

    @Test
    fun `canReuseVerification - should reuse a Found verification for the same project within five minutes`() {
        canReuseVerification(elapsed = 4.minutes + 59.seconds) shouldBe true
    }

    @Test
    fun `canReuseVerification - should verify again at the five minute boundary`() {
        canReuseVerification(elapsed = 5.minutes) shouldBe false
    }

    @Test
    fun `canReuseVerification - should not reuse for another project, another organization, or an unknown age`() {
        canReuseVerification(projectBasePath = "/Users/me/Projects/other", elapsed = 1.seconds) shouldBe false
        canReuseVerification(organizationUrl = "https://bitbucket.org/other/", elapsed = 1.seconds) shouldBe false
        canReuseVerification(elapsed = null) shouldBe false
        canReuseVerification(elapsed = (-1).seconds) shouldBe false
    }

    @Test
    fun `canReuseVerification - should not reuse when nothing was remembered`() {
        canReuseVerification(verified = null, elapsed = 1.seconds) shouldBe false
    }

    private fun canReuseVerification(
        verified: VerifiedRepository? = this.verified,
        projectBasePath: String? = "/Users/me/Projects/payments",
        organizationUrl: String = "https://bitbucket.org/acme/",
        elapsed: kotlin.time.Duration?
    ): Boolean {
        return com.github.fnunezkanut.bbcloud.ui.main.canReuseVerification(
            verified = verified,
            projectBasePath = projectBasePath,
            organizationUrl = organizationUrl,
            elapsed = elapsed
        )
    }
}