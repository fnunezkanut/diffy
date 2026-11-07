package com.github.fnunezkanut.bbcloud.auth

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Builds the Bitbucket Cloud authorize URL for an [OAuthLoginRequest], with the fixed [DiffyOAuthConfig.REDIRECT_URI]
 * and the S256 PKCE parameters. The scopes are configured on the OAuth consumer, not in this URL.
 */
internal object BitbucketAuthorizeUrl {
    fun build(request: OAuthLoginRequest): String {
        return DiffyOAuthConfig.AUTHORIZE_URL.toHttpUrl()
            .newBuilder()
            .addQueryParameter(DiffyConsumerMaterial.primaryField(), DiffyConsumerMaterial.primary())
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", DiffyOAuthConfig.REDIRECT_URI)
            .addQueryParameter("state", request.state)
            .addQueryParameter("code_challenge", request.pkce.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }
}