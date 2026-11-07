package com.github.fnunezkanut.bbcloud

import java.util.Properties

/** Build metadata read from `diffy-build.properties`, which `processResources` fills in at build time. */
object DiffyBuildInfo {
    private const val RESOURCE = "/diffy-build.properties"
    private const val VERSION_KEY = "version"

    /** Returns the plugin version, or null when the resource is missing or blank. */
    fun version(): String? {
        val properties = Properties()
        DiffyBuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { stream -> properties.load(stream) }
        return properties.getProperty(VERSION_KEY)?.trim()?.takeIf { value -> value.isNotEmpty() }
    }
}