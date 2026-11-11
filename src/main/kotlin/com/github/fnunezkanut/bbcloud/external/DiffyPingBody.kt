package com.github.fnunezkanut.bbcloud.external

import com.google.gson.JsonObject

/** Builds the JSON answer of the port-discovery ping. Pure, so it can be tested without Netty. */
object DiffyPingBody {
    private const val PLUGIN_NAME = "diffy"

    /**
     * Always carries `plugin` and `version`. The configured [workspace] is added only for
     * the Diffy Chrome extension or a Firefox extension (see [isDiffyExtensionOrigin]), and then as JSON null when none is configured, so another
     * extension learns nothing about the user.
     */
    fun build(origin: String?, version: String, workspace: String?): String {
        val body = JsonObject()
        body.addProperty("plugin", PLUGIN_NAME)
        body.addProperty("version", version)
        if (isDiffyExtensionOrigin(origin = origin)) {
            body.addProperty("workspace", workspace)
        }
        return body.toString()
    }
}