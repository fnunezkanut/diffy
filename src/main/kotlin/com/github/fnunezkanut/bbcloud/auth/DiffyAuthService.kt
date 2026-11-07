package com.github.fnunezkanut.bbcloud.auth

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.credentials.DiffyCredentialStore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlin.time.Clock

/**
 * Application service that is the single entry point for Bitbucket access tokens. It implements
 * [DiffyAccessTokenProvider] by delegating to a [DiffyAccessTokenResolver], and it also owns the shared
 * [DiffyCredentialValidator] so every project reuses one online-check cache. Its constructor takes no parameters, so
 * it passes the service-constructor rule.
 */
@Service(Service.Level.APP)
class DiffyAuthService : DiffyAccessTokenProvider {
    private val credentialStore: DiffyCredentialStore
        get() = ApplicationManager.getApplication().service<DiffyCredentialStore>()

    /** The online credential validator, shared across projects so one check result is cached per IDE. */
    val credentialValidator: DiffyCredentialValidator by lazy {
        DiffyCredentialValidator(
            credentialStore = credentialStore,
            accessTokenProvider = this,
            checkClient = OkHttpBitbucketCredentialCheckClient(),
            clock = Clock.System
        )
    }

    private val resolver: DiffyAccessTokenResolver by lazy {
        DiffyAccessTokenResolver(
            credentialStore = credentialStore,
            tokenClient = OkHttpBitbucketOAuthTokenClient(),
            clock = Clock.System,
            onOAuthSessionExpired = { notifyOAuthSessionExpired() },
            onCredentialsChanged = { credentialValidator.invalidate() }
        )
    }

    override suspend fun bearerTokenOrNull(): String? = resolver.bearerTokenOrNull()

    override suspend fun oauthAccessTokenOrNull(): String? = resolver.oauthAccessTokenOrNull()

    override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? = resolver.refreshAfterUnauthorized(rejectedToken = rejectedToken)

    override fun markOAuthNotWorking() = resolver.markOAuthNotWorking()

    override fun onOAuthTokensChanged() = resolver.onOAuthTokensChanged()

    private fun notifyOAuthSessionExpired() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Diffy")
            .createNotification(
                DiffyBundle.message("oauth.notification.sessionExpired"),
                NotificationType.WARNING
            )
            .notify(null)
    }

    companion object {
        /** The singleton, cast from the registered [DiffyAccessTokenProvider] implementation. */
        fun getInstance(): DiffyAuthService {
            return ApplicationManager.getApplication().service<DiffyAccessTokenProvider>() as DiffyAuthService
        }
    }
}