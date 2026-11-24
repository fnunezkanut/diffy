package com.github.fnunezkanut.bbcloud.credentials

import com.github.fnunezkanut.bbcloud.auth.OAuthTokens

/**
 * Stores the plugin's Bitbucket OAuth credentials in the IDE's secure storage. Older plugin versions also stored a
 * user-created API token; that entry is no longer read, and [clearLegacyApiToken] only deletes it.
 */
interface DiffyCredentialStore {
    /** Deletes the API token entry that older plugin versions stored. Safe to call when no such entry exists. */
    suspend fun clearLegacyApiToken()

    /** The stored OAuth token bundle, or null when none is stored or the stored entry is corrupt. */
    suspend fun getOAuthTokens(): OAuthTokens?

    /** Writes the OAuth token bundle atomically as a single JSON entry. */
    suspend fun saveOAuthTokens(tokens: OAuthTokens)

    /** Removes the stored OAuth token bundle. */
    suspend fun clearOAuthTokens()
}