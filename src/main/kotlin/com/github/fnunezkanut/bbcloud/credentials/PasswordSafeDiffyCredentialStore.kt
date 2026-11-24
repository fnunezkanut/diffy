package com.github.fnunezkanut.bbcloud.credentials

import com.github.fnunezkanut.bbcloud.auth.OAuthTokens
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class PasswordSafeDiffyCredentialStore : DiffyCredentialStore {
    private val legacyApiTokenAttributes = CredentialAttributes(generateServiceName(SERVICE_NAMESPACE, CREDENTIAL_KEY))
    private val oauthAttributes = CredentialAttributes(generateServiceName(SERVICE_NAMESPACE, OAUTH_CREDENTIAL_KEY))

    override suspend fun clearLegacyApiToken() {
        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(legacyApiTokenAttributes, null)
            LOG.debug("Password safe legacy API token cleared")
        }
    }

    override suspend fun getOAuthTokens(): OAuthTokens? {
        return withContext(Dispatchers.IO) {
            val json = PasswordSafe.instance.getPassword(oauthAttributes)?.takeIf { it.isNotBlank() }
            if (json == null) {
                LOG.debug("Password safe OAuth tokens read: found=false")
                return@withContext null
            }
            val decoded = runCatching { JSON.decodeFromString<OAuthTokens>(json) }.getOrNull()
            if (decoded == null) {
                // A corrupt entry is unusable; clear it without logging its content.
                LOG.warn("Stored OAuth tokens could not be decoded; clearing the corrupt entry")
                PasswordSafe.instance.set(oauthAttributes, null)
                return@withContext null
            }
            LOG.debug("Password safe OAuth tokens read: found=true")
            decoded
        }
    }

    override suspend fun saveOAuthTokens(tokens: OAuthTokens) {
        withContext(Dispatchers.IO) {
            val json = JSON.encodeToString(tokens)
            PasswordSafe.instance.set(oauthAttributes, Credentials("bitbucket-oauth", json))
            LOG.debug("Password safe OAuth tokens saved")
        }
    }

    override suspend fun clearOAuthTokens() {
        withContext(Dispatchers.IO) {
            PasswordSafe.instance.set(oauthAttributes, null)
            LOG.debug("Password safe OAuth tokens cleared")
        }
    }

    companion object {
        private val LOG = logger<PasswordSafeDiffyCredentialStore>()

        private val JSON = Json { ignoreUnknownKeys = true }

        private const val SERVICE_NAMESPACE = "Diffy - Bitbucket PR Review"
        private const val CREDENTIAL_KEY = "ApiToken" // legacy key, kept so the old entry can be deleted
        private const val OAUTH_CREDENTIAL_KEY = "OAuthTokens"
    }
}