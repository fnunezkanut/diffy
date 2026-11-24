package com.github.fnunezkanut.bbcloud.git

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.VirtualFile
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class WorkingTreeSourceChangesTest {
    private fun filePath(path: String, directory: Boolean = false, onDisk: Boolean = true): FilePath {
        val filePath = mockk<FilePath>(relaxed = true)
        every { filePath.path } returns path
        every { filePath.isDirectory } returns directory
        every { filePath.virtualFile } returns if (onDisk) mockk<VirtualFile>(relaxed = true) else null
        return filePath
    }

    private fun revision(file: FilePath): ContentRevision {
        val revision = mockk<ContentRevision>(relaxed = true)
        every { revision.file } returns file
        return revision
    }

    @Test
    fun `withWorkingTreeSourceSide - should give a modified file a current after side with the same path`() {
        // GIVEN
        val file = filePath(path = "/repo/A.kt")
        val before = revision(file)
        val change = Change(before, revision(file))

        // WHEN
        val result = WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single()

        // THEN
        assertSoftly {
            result.afterRevision.shouldBeInstanceOf<CurrentContentRevision>()
            result.afterRevision?.file shouldBe file
            result.beforeRevision shouldBeSameInstanceAs before
        }
    }

    @Test
    fun `withWorkingTreeSourceSide - should leave a deleted file unchanged`() {
        val change = Change(revision(filePath(path = "/repo/Gone.kt")), null)

        WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single() shouldBeSameInstanceAs change
    }

    @Test
    fun `withWorkingTreeSourceSide - should give an added file a current after side`() {
        val change = Change(null, revision(filePath(path = "/repo/New.kt")))

        val result = WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single()

        result.afterRevision.shouldBeInstanceOf<CurrentContentRevision>()
    }

    @Test
    fun `withWorkingTreeSourceSide - should keep the new path of a renamed file`() {
        // GIVEN
        val oldFile = filePath(path = "/repo/Old.kt")
        val newFile = filePath(path = "/repo/New.kt")
        val change = Change(revision(oldFile), revision(newFile))

        // WHEN
        val result = WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single()

        // THEN
        assertSoftly {
            result.beforeRevision?.file shouldBe oldFile
            result.afterRevision?.file shouldBe newFile
        }
    }

    @Test
    fun `withWorkingTreeSourceSide - should leave a directory unchanged`() {
        val file = filePath(path = "/repo/sub", directory = true)
        val change = Change(revision(file), revision(file))

        WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single() shouldBeSameInstanceAs change
    }

    @Test
    fun `withWorkingTreeSourceSide - should leave a file missing on disk unchanged`() {
        val file = filePath(path = "/repo/Missing.kt", onDisk = false)
        val change = Change(revision(file), revision(file))

        WorkingTreeSourceChanges.withWorkingTreeSourceSide(listOf(change)).single() shouldBeSameInstanceAs change
    }

    @Test
    fun `withWorkingTreeSourceSide - should preserve the order`() {
        // GIVEN
        val paths = listOf("/repo/C.kt", "/repo/A.kt", "/repo/B.kt")
        val changes = paths.map { path ->
            val file = filePath(path = path)
            Change(revision(file), revision(file))
        }

        // WHEN
        val result = WorkingTreeSourceChanges.withWorkingTreeSourceSide(changes)

        // THEN
        result.map { change -> change.afterRevision?.file?.path } shouldBe paths
    }
}