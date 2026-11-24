package com.github.fnunezkanut.bbcloud.ui.otherprojects

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryListResponseParser
import com.github.fnunezkanut.bbcloud.bitbucket.WorkspaceRepositorySearch
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder of the Checkout Project dialog. It debounces the typed text, asks [search] for matching repositories,
 * and drops answers that belong to an outdated query. [runOnEdt] must run its block on the EDT, because all state
 * writes and the [generation] counter belong there.
 */
internal class CheckoutProjectSearchViewModel(
    private val search: suspend (query: String) -> WorkspaceRepositorySearch,
    private val existingFolderNames: Set<String>,
    private val coroutineScope: CoroutineScope,
    private val runOnEdt: (() -> Unit) -> Unit,
    private val debounce: Duration = DEFAULT_DEBOUNCE
) {
    val state: MutableState<CheckoutProjectSearchUiState> = mutableStateOf(CheckoutProjectSearchUiState())

    private var searchJob: Job? = null
    private var generation: Int = 0
    private var lastCompletedQuery: String? = null

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        generation++
        val trimmed = query.trim()
        if (trimmed.length < BitbucketRepositoryListResponseParser.MIN_SEARCH_QUERY_LENGTH) {
            resetToIdle(query = query)
            return
        }
        if (isAlreadyAnswered(trimmed = trimmed)) {
            state.value = state.value.copy(query = query)
            return
        }
        state.value = state.value.copy(query = query, status = CheckoutProjectSearchStatus.Searching)
        startSearch(trimmed = trimmed)
    }

    fun onSearchCleared() {
        LOG.info("User cleared the search box in the Checkout Project dialog")
        onQueryChanged(query = "")
    }

    fun onRetry() {
        LOG.info("User clicked Retry in the Checkout Project dialog")
        lastCompletedQuery = null
        onQueryChanged(query = state.value.query)
    }

    fun onSelect(index: Int) {
        val row = state.value.results.getOrNull(index)
        if (row == null || row.isCheckedOut) {
            LOG.debug("Ignored a click on a row that cannot be selected")
            return
        }
        LOG.info("User selected repository ${row.repository.slug} in the Checkout Project dialog")
        state.value = state.value.copy(selectedIndex = index)
    }

    /** Moves the selection to the next row that is not checked out in the direction of [delta]. */
    fun moveSelection(delta: Int) {
        if (delta == 0) {
            return
        }
        val current = state.value
        val step = if (delta > 0) 1 else -1
        var index = if (current.selectedIndex < 0 && step < 0) current.results.size else current.selectedIndex
        index += step
        while (index in current.results.indices) {
            if (!current.results[index].isCheckedOut) {
                LOG.debug("Moved the selection to row $index")
                state.value = current.copy(selectedIndex = index)
                return
            }
            index += step
        }
    }

    fun dispose() {
        searchJob?.cancel()
    }

    private fun resetToIdle(query: String) {
        lastCompletedQuery = null
        state.value = CheckoutProjectSearchUiState(query = query)
    }

    private fun isAlreadyAnswered(trimmed: String): Boolean {
        val status = state.value.status
        val finished = status == CheckoutProjectSearchStatus.Results ||
            status == CheckoutProjectSearchStatus.NoMatches ||
            status == CheckoutProjectSearchStatus.AllCheckedOut
        return finished && trimmed == lastCompletedQuery
    }

    private fun startSearch(trimmed: String) {
        val requestGeneration = generation
        searchJob = coroutineScope.launch {
            delay(debounce)
            val outcome = runSearch(query = trimmed)
            runOnEdt {
                if (requestGeneration == generation) {
                    applyOutcome(query = trimmed, outcome = outcome)
                }
            }
        }
    }

    private suspend fun runSearch(query: String): WorkspaceRepositorySearch {
        return try {
            search(query)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            WorkspaceRepositorySearch.Failed(cause = exception)
        }
    }

    private fun applyOutcome(query: String, outcome: WorkspaceRepositorySearch) {
        lastCompletedQuery = query
        when (outcome) {
            is WorkspaceRepositorySearch.Found -> {
                applyFound(outcome = outcome)
            }
            WorkspaceRepositorySearch.Unauthorized -> {
                LOG.info("Checkout Project search: Bitbucket refused the request")
                state.value = failureState(status = CheckoutProjectSearchStatus.Unauthorized)
            }
            is WorkspaceRepositorySearch.Failed -> {
                LOG.warn("Checkout Project search failed", outcome.cause)
                state.value = failureState(status = CheckoutProjectSearchStatus.Failed)
            }
        }
    }

    private fun failureState(status: CheckoutProjectSearchStatus): CheckoutProjectSearchUiState {
        return CheckoutProjectSearchUiState(query = state.value.query, status = status)
    }

    private fun applyFound(outcome: WorkspaceRepositorySearch.Found) {
        val rows = checkoutSearchRows(repositories = outcome.repositories, existingFolderNames = existingFolderNames)
        val checkedOutCount = rows.count { row -> row.isCheckedOut }
        val status = when {
            rows.isEmpty() -> CheckoutProjectSearchStatus.NoMatches
            checkedOutCount == rows.size -> CheckoutProjectSearchStatus.AllCheckedOut
            else -> CheckoutProjectSearchStatus.Results
        }
        LOG.debug("Checkout Project search found ${rows.size} repositories ($checkedOutCount already checked out, hasMore=${outcome.hasMore})")
        state.value = CheckoutProjectSearchUiState(
            query = state.value.query,
            status = status,
            results = rows,
            checkedOutCount = checkedOutCount,
            hasMore = outcome.hasMore,
            selectedIndex = rows.indexOfFirst { row -> !row.isCheckedOut }
        )
    }

    companion object {
        private val LOG = logger<CheckoutProjectSearchViewModel>()

        private val DEFAULT_DEBOUNCE: Duration = 300.milliseconds
    }
}