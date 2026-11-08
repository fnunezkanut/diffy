package com.github.fnunezkanut.bbcloud.external

import com.intellij.util.io.origin
import io.netty.handler.codec.http.HttpRequest

/** True when the request carries a Chrome extension `Origin` header, whichever extension it is. */
fun isChromeExtensionOrigin(request: HttpRequest): Boolean {
    return isChromeExtensionOrigin(origin = request.origin)
}

/** True when [origin] starts with the Chrome extension scheme. */
fun isChromeExtensionOrigin(origin: String?): Boolean {
    return origin != null && origin.startsWith(DiffyExternalOrigins.CHROME_EXTENSION_PREFIX)
}