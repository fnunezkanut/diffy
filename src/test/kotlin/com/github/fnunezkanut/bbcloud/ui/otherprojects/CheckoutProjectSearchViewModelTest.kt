package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketWorkspaceRepository
import com.github.fnunezkanut.bbcloud.bitbucket.WorkspaceRepositorySearch
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration

@Tag("unit")
class CheckoutProjectSearchViewModelTest {
    private fun repo(slug: String, name: String = slug): BitbucketWorkspaceRepository {
        return BitbucketWorkspaceRepository(slug = slug, name = name, mainBranch = "main", httpsCloneUrl = null, sshCloneUrl = null, avatarUrl = null)
    }

    private fun found(vararg slugs: String, hasMore: Boolean = false): WorkspaceRepositorySearch {
        return WorkspaceRepositorySearch.Found(repositories = slugs.map { slug -> repo(slug) }, hasMore = hasMore)
    }

    private class Harness(existing: Set<String> = emptySet(), handler: suspend (String) -> WorkspaceRepositorySearch) {
        val queries = mutableListOf<String>()

        val viewModel = CheckoutProjectSearchViewModel(
            search = { query ->
                queries.add(query)
                handler(query)
            },
            existingFolderNames = existing,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            runOnEdt = { block -> block() },
            debounce = Duration.ZERO
        )

        val state: CheckoutProjectSearchUiState
            get() = viewModel.state.value
    }

    @Nested
    inner class QueryHandlingTest {
        @Test
        fun `should stay idle and make no call for a query shorter than 2 characters`() {
            // GIVEN
            val harness = Harness { found("a") }

            // WHEN
            harness.viewModel.onQueryChanged(" a ")

            // THEN
            assertSoftly {
                harness.state.status shouldBe CheckoutProjectSearchStatus.Idle
                harness.state.query shouldBe " a "
                harness.queries shouldBe emptyList()
            }
        }

        @Test
        fun `should show Searching while the request is pending, then Results with the first row selected`() {
            // GIVEN
            val pending = CompletableDeferred<WorkspaceRepositorySearch>()
            val harness = Harness { pending.await() }

            // WHEN
            harness.viewModel.onQueryChanged("ab")
            val whileSearching = harness.state.status
            pending.complete(found("beta", "alpha"))

            // THEN
            assertSoftly {
                whileSearching shouldBe CheckoutProjectSearchStatus.Searching
                harness.state.status shouldBe CheckoutProjectSearchStatus.Results
                harness.state.results.map { row -> row.repository.slug } shouldBe listOf("alpha", "beta")
                harness.state.selectedIndex shouldBe 0
                harness.state.selected?.slug shouldBe "alpha"
            }
        }

        @Test
        fun `should send the trimmed query and skip a repeat when only whitespace changed`() {
            // GIVEN
            val harness = Harness { found("abc") }

            // WHEN
            harness.viewModel.onQueryChanged(" abc")
            harness.viewModel.onQueryChanged("abc ")

            // THEN
            assertSoftly {
                harness.queries shouldBe listOf("abc")
                harness.state.query shouldBe "abc "
                harness.state.status shouldBe CheckoutProjectSearchStatus.Results
            }
        }

        @Test
        fun `should drop a stale response that completes after a newer query`() {
            // GIVEN
            val first = CompletableDeferred<WorkspaceRepositorySearch>()
            val second = CompletableDeferred<WorkspaceRepositorySearch>()
            val answers = mapOf("ab" to first, "abc" to second)
            val harness = Harness { query -> answers.getValue(query).await() }

            // WHEN
            harness.viewModel.onQueryChanged("ab")
            harness.viewModel.onQueryChanged("abc")
            second.complete(found("abc-new"))
            first.complete(found("ab-old"))

            // THEN
            harness.state.results.map { row -> row.repository.slug } shouldBe listOf("abc-new")
        }

        @Test
        fun `should cancel the pending search and reset to Idle when the query is cleared`() {
            // GIVEN
            val pending = CompletableDeferred<WorkspaceRepositorySearch>()
            val harness = Harness { pending.await() }
            harness.viewModel.onQueryChanged("abc")

            // WHEN
            harness.viewModel.onSearchCleared()
            pending.complete(found("abc"))

            // THEN
            assertSoftly {
                harness.state.status shouldBe CheckoutProjectSearchStatus.Idle
                harness.state.results shouldBe emptyList()
                harness.state.query shouldBe ""
            }
        }

        @Test
        fun `should keep the previous results while a new search runs`() {
            // GIVEN
            val pending = CompletableDeferred<WorkspaceRepositorySearch>()
            var calls = 0
            val harness = Harness {
                calls++
                if (calls == 1) found("abc") else pending.await()
            }
            harness.viewModel.onQueryChanged("abc")

            // WHEN
            harness.viewModel.onQueryChanged("abcd")

            // THEN
            assertSoftly {
                harness.state.status shouldBe CheckoutProjectSearchStatus.Searching
                harness.state.results.map { row -> row.repository.slug } shouldBe listOf("abc")
            }
        }
    }

    @Nested
    inner class OutcomeTest {
        @Test
        fun `should keep checked-out slugs as flagged rows, ignoring case, and count them`() {
            // GIVEN
            val harness = Harness(existing = setOf("alpha")) { found("Beta", "Alpha", "gamma") }

            // WHEN
            harness.viewModel.onQueryChanged("a1")

            // THEN
            assertSoftly {
                harness.state.results.map { row -> row.repository.slug to row.isCheckedOut } shouldBe
                    listOf("Alpha" to true, "Beta" to false, "gamma" to false)
                harness.state.checkedOutCount shouldBe 1
                harness.state.selectedIndex shouldBe 1
                harness.state.selected?.slug shouldBe "Beta"
            }
        }

        @Test
        fun `should report AllCheckedOut with no selection when every match exists locally`() {
            // GIVEN
            val harness = Harness(existing = setOf("a1", "a2")) { found("a1", "a2", hasMore = true) }

            // WHEN
            harness.viewModel.onQueryChanged("a")
            harness.viewModel.onQueryChanged("a1")

            // THEN
            assertSoftly {
                harness.state.status shouldBe CheckoutProjectSearchStatus.AllCheckedOut
                harness.state.results.size shouldBe 2
                harness.state.selectedIndex shouldBe -1
                harness.state.selected shouldBe null
                harness.state.hasMore shouldBe true
            }
        }

        @Test
        fun `should report NoMatches for an empty response`() {
            // GIVEN
            val harness = Harness { found() }

            // WHEN
            harness.viewModel.onQueryChanged("zzz")

            // THEN
            harness.state.status shouldBe CheckoutProjectSearchStatus.NoMatches
        }

        @Test
        fun `should map Unauthorized and Failed to their statuses`() {
            // GIVEN
            val unauthorized = Harness { WorkspaceRepositorySearch.Unauthorized }
            val failed = Harness { WorkspaceRepositorySearch.Failed() }

            // WHEN
            unauthorized.viewModel.onQueryChanged("abc")
            failed.viewModel.onQueryChanged("abc")

            // THEN
            assertSoftly {
                unauthorized.state.status shouldBe CheckoutProjectSearchStatus.Unauthorized
                failed.state.status shouldBe CheckoutProjectSearchStatus.Failed
                failed.state.results shouldBe emptyList()
            }
        }

        @Test
        fun `should map an exception thrown by search to Failed`() {
            // GIVEN
            val harness = Harness { throw IllegalStateException("boom") }

            // WHEN
            harness.viewModel.onQueryChanged("abc")

            // THEN
            harness.state.status shouldBe CheckoutProjectSearchStatus.Failed
        }

        @Test
        fun `should re-issue the same query on retry`() {
            // GIVEN
            var calls = 0
            val harness = Harness {
                calls++
                if (calls == 1) WorkspaceRepositorySearch.Failed() else found("abc")
            }
            harness.viewModel.onQueryChanged("abc")

            // WHEN
            harness.viewModel.onRetry()

            // THEN
            assertSoftly {
                harness.queries shouldBe listOf("abc", "abc")
                harness.state.status shouldBe CheckoutProjectSearchStatus.Results
            }
        }
    }

    @Nested
    inner class SelectionTest {
        private fun harnessWithMixedRows(): Harness {
            val harness = Harness(existing = setOf("b", "d")) { found("a", "b", "c", "d", "e") }
            harness.viewModel.onQueryChanged("xx")
            return harness
        }

        @Test
        fun `should start on the first selectable row even when checked-out rows come first`() {
            // GIVEN
            val harness = Harness(existing = setOf("a", "b")) { found("a", "b", "c") }

            // WHEN
            harness.viewModel.onQueryChanged("xx")

            // THEN
            harness.state.selected?.slug shouldBe "c"
        }

        @Test
        fun `should ignore a click on a checked-out row`() {
            // GIVEN
            val harness = harnessWithMixedRows()

            // WHEN
            harness.viewModel.onSelect(1)
            harness.viewModel.onSelect(99)

            // THEN
            harness.state.selected?.slug shouldBe "a"
        }

        @Test
        fun `should select a selectable row on click`() {
            // GIVEN
            val harness = harnessWithMixedRows()

            // WHEN
            harness.viewModel.onSelect(4)

            // THEN
            harness.state.selected?.slug shouldBe "e"
        }

        @Test
        fun `should skip checked-out rows when moving in both directions`() {
            // GIVEN
            val harness = harnessWithMixedRows()

            // WHEN
            harness.viewModel.moveSelection(1)
            val afterDown = harness.state.selected?.slug
            harness.viewModel.moveSelection(1)
            val afterSecondDown = harness.state.selected?.slug
            harness.viewModel.moveSelection(-1)

            // THEN
            assertSoftly {
                afterDown shouldBe "c"
                afterSecondDown shouldBe "e"
                harness.state.selected?.slug shouldBe "c"
            }
        }

        @Test
        fun `should stay put at both ends`() {
            // GIVEN
            val harness = harnessWithMixedRows()

            // WHEN
            harness.viewModel.moveSelection(-1)
            val atTop = harness.state.selected?.slug
            harness.viewModel.onSelect(4)
            harness.viewModel.moveSelection(1)

            // THEN
            assertSoftly {
                atTop shouldBe "a"
                harness.state.selected?.slug shouldBe "e"
            }
        }

        @Test
        fun `should do nothing when no row is selectable`() {
            // GIVEN
            val harness = Harness(existing = setOf("a", "b")) { found("a", "b") }
            harness.viewModel.onQueryChanged("xx")

            // WHEN
            harness.viewModel.moveSelection(1)
            harness.viewModel.moveSelection(-1)

            // THEN
            harness.state.selectedIndex shouldBe -1
        }
    }

    @Nested
    inner class CheckoutSearchRowsTest {
        @Test
        fun `should flag existing folders ignoring case and sort by lower-cased slug`() {
            // WHEN
            val rows = checkoutSearchRows(
                repositories = listOf(repo("zeta"), repo("Alpha"), repo("Beta")),
                existingFolderNames = setOf("beta")
            )

            // THEN
            rows.map { row -> row.repository.slug to row.isCheckedOut } shouldBe
                listOf("Alpha" to false, "Beta" to true, "zeta" to false)
        }
    }
}