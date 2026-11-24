package com.github.fnunezkanut.bbcloud.project

import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object ProjectsDirectoryEntries {
    private val LOG = logger<ProjectsDirectoryEntries>()

    /**
     * The lower-cased names of the immediate children of [projectsDirectory] (files and folders, hidden entries
     * included), or null when the directory is missing or unreadable. Call it on `Dispatchers.IO`.
     */
    fun lowerCaseNames(projectsDirectory: Path): Set<String>? {
        return try {
            Files.list(projectsDirectory).use { stream ->
                stream.map { entry -> entry.fileName.toString().lowercase() }.toList().toSet()
            }
        } catch (exception: IOException) {
            LOG.debug("Could not list the projects directory", exception)
            null
        }
    }
}