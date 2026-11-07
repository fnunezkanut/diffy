package com.github.fnunezkanut.bbcloud.ui.pullrequest.create

import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.project.Project
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyNewPullRequestVirtualFileTest {
    private fun context(): NewPullRequestContext {
        return NewPullRequestContext(
            organizationUrl = "https://bitbucket.org/acme/",
            workspace = "acme",
            repositorySlug = "sample-repository",
            repositoryWebUrl = "https://bitbucket.org/acme/sample-repository",
            currentLocalBranch = "feature/x"
        )
    }

    @Test
    fun `virtual file - should expose a read-only new-pr name and the dedicated new PR file type`() {
        // GIVEN
        val project = mockk<Project> {
            every { isOpen } returns true
            every { isDisposed } returns false
        }

        // WHEN
        val file = DiffyNewPullRequestVirtualFile(project = project, context = context())

        // THEN
        assertSoftly {
            file.name shouldBe "new.pr"
            file.isWritable shouldBe false
            file.isValid shouldBe true
            file.fileType shouldBe DiffyNewPullRequestFileType
            file.getUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT) shouldBe true
        }
    }

    @Test
    fun `virtual file - should be invalid when the project is closed or disposed`() {
        // GIVEN a disposed project
        val project = mockk<Project> {
            every { isOpen } returns true
            every { isDisposed } returns true
        }

        // WHEN
        val file = DiffyNewPullRequestVirtualFile(project = project, context = context())

        // THEN
        file.isValid shouldBe false
    }
}