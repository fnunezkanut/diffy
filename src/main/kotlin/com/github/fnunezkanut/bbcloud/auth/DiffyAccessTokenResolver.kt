package com.github.fnunezkanut.bbcloud.auth

import com.github.fnunezkanut.bbcloud.credentials.DiffyCredentialStore
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The testable core of the access-token provider. It supplies the OAuth access token (refreshing it proactively and
 * under a single-flight [Mutex]). [onOAuthSessionExpired] fires once when a
 * refresh permanently fails; [onCredentialsChanged] invalidates downstream caches after any credential change here.
 */
class DiffyAccessTokenResolver(
    private val credentialStore: DiffyCredentialStore,
    private val tokenClient: BitbucketOAuthTokenClient,
    private val clock: Clock,
    private val onOAuthSessionExpired: () -> Unit,
    private val onCredentialsChanged: () -> Unit
) : DiffyAccessTokenProvider {
    private val refreshMutex = Mutex()

    @Volatile
    private var oauthNotWorking = false

    @Volatile
    private var nextRefreshAllowedAt: Instant? = null

    override suspend fun bearerTokenOrNull(): String? {
        return oauthAccessTokenOrNull()
    }

    override suspend fun oauthAccessTokenOrNull(): String? {
        if (oauthNotWorking) {
            return null
        }
        val tokens = credentialStore.getOAuthTokens() ?: return null
        val now = clock.now()
        if (tokens.isAccessTokenUsable(now = now, margin = DiffyOAuthConfig.ACCESS_TOKEN_REFRESH_MARGIN)) {
            return tokens.accessToken
        }
        return refreshMutex.withLock {
            // Another caller may have refreshed while we waited for the lock.
            val current = credentialStore.getOAuthTokens() ?: return@withLock null
            val lockNow = clock.now()
            if (current.isAccessTokenUsable(now = lockNow, margin = DiffyOAuthConfig.ACCESS_TOKEN_REFRESH_MARGIN)) {
                return@withLock current.accessToken
            }
            val backoffUntil = nextRefreshAllowedAt
            if (backoffUntil != null && lockNow < backoffUntil) {
                // Within the backoff window: skip the refresh and use the old token if it is not yet expired.
                return@withLock current.accessToken.takeIf { lockNow < current.expiresAt }
            }
            performRefresh(tokens = current, now = lockNow)
        }
    }

    /** Must be called while holding [refreshMutex]. */
    private suspend fun performRefresh(tokens: OAuthTokens, now: Instant): String? {
        return when (val result = tokenClient.refresh(refreshToken = tokens.refreshToken)) {
            is OAuthTokenResult.Success -> {
                credentialStore.saveOAuthTokens(tokens = result.tokens)
                nextRefreshAllowedAt = null
                LOG.info("OAuth access token refreshed")
                result.tokens.accessToken
            }
            is OAuthTokenResult.InvalidGrant, is OAuthTokenResult.InvalidClient -> {
                LOG.info("OAuth refresh permanently failed; clearing the OAuth session")
                credentialStore.clearOAuthTokens()
                oauthNotWorking = true
                onOAuthSessionExpired()
                onCredentialsChanged()
                null
            }
            is OAuthTokenResult.Failed -> {
                nextRefreshAllowedAt = now + DiffyOAuthConfig.REFRESH_BACKOFF
                LOG.warn("OAuth refresh transiently failed; keeping the tokens", result.cause)
                tokens.accessToken.takeIf { now < tokens.expiresAt }
            }
        }
    }

    override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? {
        return refreshMutex.withLock {
            val tokens = credentialStore.getOAuthTokens()
            if (tokens != null && tokens.accessToken != rejectedToken) {
                // Another caller already refreshed; use the fresh stored token.
                return@withLock tokens.accessToken
            }
            if (tokens != null && tokens.accessToken == rejectedToken) {
                val now = clock.now()
                val backoffUntil = nextRefreshAllowedAt
                if (backoffUntil != null && now < backoffUntil) {
                    return@withLock null
                }
                return@withLock when (val result = tokenClient.refresh(refreshToken = tokens.refreshToken)) {
                    is OAuthTokenResult.Success -> {
                        credentialStore.saveOAuthTokens(tokens = result.tokens)
                        nextRefreshAllowedAt = null
                        result.tokens.accessToken
                    }
                    is OAuthTokenResult.InvalidGrant, is OAuthTokenResult.InvalidClient -> {
                        credentialStore.clearOAuthTokens()
                        oauthNotWorking = true
                        onOAuthSessionExpired()
                        onCredentialsChanged()
                        null
                    }
                    is OAuthTokenResult.Failed -> {
                        nextRefreshAllowedAt = now + DiffyOAuthConfig.REFRESH_BACKOFF
                        null
                    }
                }
            }
            // There is no OAuth session; nothing to refresh.
            null
        }
    }

    override fun markOAuthNotWorking() {
        oauthNotWorking = true
        onCredentialsChanged()
        LOG.info("OAuth marked not working; requests are unauthenticated until the next login")
    }

    override fun onOAuthTokensChanged() {
        oauthNotWorking = false
        nextRefreshAllowedAt = null
    }

    companion object {
        private val LOG = logger<DiffyAccessTokenResolver>()
    }
}