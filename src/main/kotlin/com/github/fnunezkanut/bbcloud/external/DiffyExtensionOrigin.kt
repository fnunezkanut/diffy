package com.github.fnunezkanut.bbcloud.external

import com.intellij.util.io.origin
import io.netty.handler.codec.http.HttpRequest

/** True when the request carries a Chrome or Firefox extension `Origin` header, whichever extension it is. */
fun isBrowserExtensionOrigin(request: HttpRequest): Boolean {
    return isBrowserExtensionOrigin(origin = request.origin)
}

/** True when [origin] starts with the Chrome or the Firefox extension scheme. */
fun isBrowserExtensionOrigin(origin: String?): Boolean {
    if (origin == null) {
        return false
    }
    return origin.startsWith(DiffyExternalOrigins.CHROME_EXTENSION_PREFIX) ||
        origin.startsWith(DiffyExternalOrigins.FIREFOX_EXTENSION_PREFIX)
}

/**
 * True when [origin] is the Diffy Chrome extension (store or unpacked copy), or any Firefox extension.
 *
 * A Firefox extension origin is `moz-extension://<uuid>` with a random UUID per browser profile, so it cannot be pinned
 * like the Chrome ID. A web page cannot forge an extension `Origin`, so only an installed add-on can pass this check.
 */
fun isDiffyExtensionOrigin(origin: String?): Boolean {
    if (origin == null) {
        return false
    }
    if (origin in DiffyExternalOrigins.ALLOWED_CHROME_EXTENSION_ORIGINS) {
        return true
    }
    return origin.startsWith(DiffyExternalOrigins.FIREFOX_EXTENSION_PREFIX) &&
        origin.length > DiffyExternalOrigins.FIREFOX_EXTENSION_PREFIX.length
}