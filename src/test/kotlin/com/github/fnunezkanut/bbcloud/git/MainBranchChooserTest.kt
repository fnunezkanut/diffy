package com.github.fnunezkanut.bbcloud.git

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MainBranchChooserTest {
    private val remotes = setOf("origin")

    @Nested
    inner class IsMainBranchName {
        @Test
        fun `isMainBranchName - should accept main and master`() {
            assertSoftly {
                MainBranchChooser.isMainBranchName(branchName = "main") shouldBe true
                MainBranchChooser.isMainBranchName(branchName = "master") shouldBe true
                MainBranchChooser.isMainBranchName(branchName = " main ") shouldBe true
            }
        }

        @Test
        fun `isMainBranchName - should reject other names`() {
            assertSoftly {
                MainBranchChooser.isMainBranchName(branchName = "develop") shouldBe false
                MainBranchChooser.isMainBranchName(branchName = "feature/main") shouldBe false
                MainBranchChooser.isMainBranchName(branchName = "Main") shouldBe false
                MainBranchChooser.isMainBranchName(branchName = null) shouldBe false
            }
        }
    }

    @Nested
    inner class Choose {
        @Test
        fun `choose - should return null and skip the scan when no candidate exists`() {
            // GIVEN
            var scanned = false

            // WHEN
            val chosen = MainBranchChooser.choose(existing = emptyList(), remoteNames = remotes) {
                scanned = true
                emptyList()
            }

            // THEN
            assertSoftly {
                chosen shouldBe null
                scanned shouldBe false
            }
        }

        @Test
        fun `choose - should return the only existing candidate without scanning`() {
            // GIVEN
            var scanned = false

            // WHEN
            val chosen = MainBranchChooser.choose(existing = listOf("master"), remoteNames = remotes) {
                scanned = true
                emptyList()
            }

            // THEN
            assertSoftly {
                chosen shouldBe "master"
                scanned shouldBe false
            }
        }

        @Test
        fun `choose - should pick master when the newest decorated commit names origin master`() {
            // GIVEN
            val lines = listOf("", "HEAD -> feature/x", "origin/master, master", "origin/main")

            // WHEN
            val chosen = MainBranchChooser.choose(existing = listOf("main", "master"), remoteNames = remotes) { lines }

            // THEN
            chosen shouldBe "master"
        }

        @Test
        fun `choose - should pick main when the newest decorated commit names origin main`() {
            // GIVEN
            val lines = listOf("HEAD -> feature, origin/main", "origin/master")

            // WHEN
            val chosen = MainBranchChooser.choose(existing = listOf("main", "master"), remoteNames = remotes) { lines }

            // THEN
            chosen shouldBe "main"
        }

        @Test
        fun `choose - should prefer main when one commit names both`() {
            // GIVEN
            val lines = listOf("origin/master, origin/main")

            // WHEN
            val chosen = MainBranchChooser.choose(existing = listOf("main", "master"), remoteNames = remotes) { lines }

            // THEN
            chosen shouldBe "main"
        }

        @Test
        fun `choose - should fall back to main when no recent commit names either`() {
            // GIVEN
            val lines = listOf("HEAD -> feature/x", "", "tag: v1")

            // WHEN
            val chosen = MainBranchChooser.choose(existing = listOf("main", "master"), remoteNames = remotes) { lines }

            // THEN
            chosen shouldBe "main"
        }
    }

    @Nested
    inner class ParseDecorations {
        @Test
        fun `parseDecorations - should ignore feature branches and their remote refs`() {
            val mentioned = MainBranchChooser.parseDecorations(line = "HEAD -> feature/x, origin/feature/x", remoteNames = remotes)

            mentioned shouldBe emptySet()
        }

        @Test
        fun `parseDecorations - should skip tags and read the remote tracking ref`() {
            val mentioned = MainBranchChooser.parseDecorations(line = "tag: v1, origin/main", remoteNames = remotes)

            mentioned shouldContainExactly setOf("main")
        }

        @Test
        fun `parseDecorations - should read the branch behind the HEAD arrow`() {
            val mentioned = MainBranchChooser.parseDecorations(line = "HEAD -> master, origin/master", remoteNames = remotes)

            mentioned shouldContainExactly setOf("master")
        }

        @Test
        fun `parseDecorations - should return nothing for an empty line`() {
            MainBranchChooser.parseDecorations(line = "", remoteNames = remotes) shouldBe emptySet()
        }

        @Test
        fun `parseDecorations - should not map a feature branch named main to the main branch`() {
            val mentioned = MainBranchChooser.parseDecorations(line = "feature/main, upstream/main", remoteNames = remotes)

            mentioned shouldBe emptySet()
        }
    }
}