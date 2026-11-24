package com.github.fnunezkanut.bbcloud.auth

/**
 * How the plugin is currently authenticated against Bitbucket Cloud, as determined by an online credential check.
 *
 * The plugin authenticates only with a Bitbucket OAuth login. This value is the *working* method, not merely the
 * stored one: it is [OAuth] only when the stored login is verified working online, otherwise [None].
 */
enum class DiffyAuthenticationMethod {
    None,

    /** A Bitbucket OAuth (PKCE) login whose access token is working. */
    OAuth
}