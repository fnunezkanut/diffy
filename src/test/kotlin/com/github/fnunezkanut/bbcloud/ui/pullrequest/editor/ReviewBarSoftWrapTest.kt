package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.diff.EditorDiffViewer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorSettings
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ReviewBarSoftWrapTest {
    private fun editorWith(wrapped: Boolean): Editor {
        val settings = mockk<EditorSettings>()
        every { settings.isUseSoftWraps } returns wrapped
        val editor = mockk<Editor>()
        every { editor.settings } returns settings
        return editor
    }

    private fun viewerOf(vararg editors: Editor): EditorDiffViewer {
        val viewer = mockk<EditorDiffViewer>()
        every { viewer.editors } returns editors.toList()
        return viewer
    }

    @Test
    fun `editorsOf - should return both editors of a two-side viewer`() {
        // GIVEN
        val viewer = viewerOf(editorWith(wrapped = false), editorWith(wrapped = false))

        // WHEN
        val result = ReviewBarSoftWrap.editorsOf(viewer = viewer)

        // THEN
        result shouldHaveSize 2
    }

    @Test
    fun `editorsOf - should return empty for an unknown viewer`() {
        // GIVEN
        val viewer = Any()

        // WHEN
        val result = ReviewBarSoftWrap.editorsOf(viewer = viewer)

        // THEN
        result.shouldBeEmpty()
    }

    @Test
    fun `isWrapped - should be false when one editor is not wrapped`() {
        // GIVEN
        val viewer = viewerOf(editorWith(wrapped = true), editorWith(wrapped = false))

        // WHEN
        val result = ReviewBarSoftWrap.isWrapped(viewer = viewer)

        // THEN
        result shouldBe false
    }

    @Test
    fun `isWrapped - should be true when every editor is wrapped`() {
        // GIVEN
        val viewer = viewerOf(editorWith(wrapped = true), editorWith(wrapped = true))

        // WHEN
        val result = ReviewBarSoftWrap.isWrapped(viewer = viewer)

        // THEN
        result shouldBe true
    }

    @Test
    fun `isWrapped - should be false when the viewer has no editor`() {
        // GIVEN
        val viewer = viewerOf()

        // WHEN
        val result = ReviewBarSoftWrap.isWrapped(viewer = viewer)

        // THEN
        assertSoftly {
            result shouldBe false
            ReviewBarSoftWrap.editorsOf(viewer = viewer).shouldBeEmpty()
        }
    }

    @Test
    fun `setWrapped - should leave editors alone that already have the state`() {
        // GIVEN
        val viewer = viewerOf(editorWith(wrapped = true), editorWith(wrapped = true))

        // WHEN
        ReviewBarSoftWrap.setWrapped(viewer = viewer, wrapped = true)

        // THEN
        ReviewBarSoftWrap.isWrapped(viewer = viewer) shouldBe true
    }
}