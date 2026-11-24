package com.github.fnunezkanut.bbcloud.project

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("unit")
class ProjectsDirectoryEntriesTest {
    @Test
    fun `lowerCaseNames - should list files and folders lower-cased and return null for a missing directory`(
        @TempDir directory: Path
    ) {
        // GIVEN
        Files.createDirectory(directory.resolve("Repo-A"))
        Files.createFile(directory.resolve("Notes.TXT"))

        // THEN
        assertSoftly {
            ProjectsDirectoryEntries.lowerCaseNames(projectsDirectory = directory) shouldBe setOf("repo-a", "notes.txt")
            ProjectsDirectoryEntries.lowerCaseNames(projectsDirectory = directory.resolve("missing")) shouldBe null
        }
    }
}