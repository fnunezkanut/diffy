package com.github.fnunezkanut.bbcloud.external

import com.google.gson.JsonObject

/** Builds the JSON answer of the port-discovery ping. Pure, so it can be tested without Netty. */
object DiffyPingBody {
    private const val PLUGIN_NAME = "diffy"

    /**
     * Always carries `plugin` and `version`. The configured [workspace] is added only for
     * [DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN], and then as JSON null when none is configured, so another
     * extension learns nothing about the user.
     */
    fun build(origin: String?, version: String, workspace: String?): String {
        val body = JsonObject()
        body.addProperty("plugin", PLUGIN_NAME)
        body.addProperty("version", version)
        if (origin == DiffyExternalOrigins.ALLOWED_EXTENSION_ORIGIN) {
            body.addProperty("workspace", workspace)
        }
        return body.toString()
    }
}