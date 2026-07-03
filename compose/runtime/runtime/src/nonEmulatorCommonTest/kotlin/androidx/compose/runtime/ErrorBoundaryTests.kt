/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.runtime

import androidx.compose.runtime.mock.EmptyApplier
import androidx.compose.runtime.mock.Linear
import androidx.compose.runtime.mock.MockViewValidator
import androidx.compose.runtime.mock.Text
import androidx.compose.runtime.mock.View
import androidx.compose.runtime.mock.ViewApplier
import androidx.compose.runtime.mock.compositionTest
import androidx.compose.runtime.mock.expectNoChanges
import androidx.compose.runtime.mock.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeRuntimeApi::class, InternalComposeApi::class)
@Suppress("UNUSED_EXPRESSION")
class ErrorBoundaryTests {

    private fun MockViewValidator.FallbackText(error: Throwable) {
        Text("fallback: ${error.message}")
    }

    @Composable
    private fun ErrorBoundaryScope.FallbackContent() {
        Text("fallback: ${error.message}")
    }

    @Test
    fun initialCompositionThrow_composesFallback() = compositionTest {
        compose {
            Linear {
                ErrorBoundary(fallback = { FallbackContent() }) {
                    Text("before throw")
                    error("boom")
                }
                Text("after boundary")
            }
        }

        validate {
            Linear {
                FallbackText(IllegalStateException("boom"))
                Text("after boundary")
            }
        }
        verifyConsistent()
    }

    @Test
    fun recompositionThrow_composesFallbackAndKeepsSiblings() = compositionTest {
        val fail = mutableStateOf(false)
        compose {
            Linear {
                ErrorBoundary(fallback = { FallbackContent() }) {
                    Text("content")
                    if (fail.value) error("boom")
                }
                Text("sibling")
            }
        }

        validate {
            Linear {
                Text("content")
                Text("sibling")
            }
        }

        fail.value = true
        advance()

        validate {
            Linear {
                FallbackText(IllegalStateException("boom"))
                Text("sibling")
            }
        }
        verifyConsistent()
    }

    @Test
    fun containedError_isReportedThroughOnErrorExactlyOnce() = compositionTest {
        val reported = mutableListOf<Pair<Throwable, CompositionErrorInfo>>()
        val thrown = IllegalStateException("boom")
        val fail = mutableStateOf(false)
        val fallbackRevision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = {
                    Text("fallback ${fallbackRevision.value}")
                },
                onError = { error, info -> reported.add(error to info) },
            ) {
                Text("content")
                if (fail.value) throw thrown
            }
        }

        assertTrue(reported.isEmpty(), "onError should not fire while content is healthy")

        fail.value = true
        advance()

        validate { Text("fallback 0") }
        assertEquals(1, reported.size, "expected exactly one onError report")
        assertSame(thrown, reported.single().first)
        assertNotNull(reported.single().second)

        // Force the fallback (and the boundary body) to actually recompose while the same
        // contained error is still showing: the report must not repeat.
        fallbackRevision.value = 1
        advance()
        validate { Text("fallback 1") }
        assertEquals(1, reported.size, "onError must not re-report the same containment")
        verifyConsistent()
    }

    @Test
    fun onErrorReportedPerContainment_evenForTheSameThrowableInstance() = compositionTest {
        val reported = mutableListOf<Throwable>()
        val thrown = IllegalStateException("recurring boom")
        var shouldFail = true
        var capturedReset: (() -> Unit)? = null
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    Text("fallback")
                },
                onError = { error, _ -> reported.add(error) },
            ) {
                Text("content")
                if (shouldFail) throw thrown
            }
        }

        validate { Text("fallback") }
        assertEquals(1, reported.size)

        // A user-gesture reset re-attempts; the content fails again with the SAME Throwable
        // instance. That is a new containment and must be reported again.
        assertNotNull(capturedReset)()
        advance()

        validate { Text("fallback") }
        assertEquals(2, reported.size, "re-containing the same instance is a new failure")
        assertSame(thrown, reported[0])
        assertSame(thrown, reported[1])
        verifyConsistent()
    }

    @Test
    fun onErrorReported_whenResetKeysRecoverInTheSamePass() = compositionTest {
        val reported = mutableListOf<Throwable>()
        var shouldFail = true
        val dataRevision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = { Text("fallback") },
                onError = { error, _ -> reported.add(error) },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                Text("content ${dataRevision.value}")
                if (shouldFail) error("boom")
            }
        }

        validate { Text("fallback") }
        assertEquals(1, reported.size)

        // The cause clears and the data revision changes before the boundary composes its
        // fallback pass: the boundary recovers straight back to content — but the containment
        // that happened must still have been reported (exactly once).
        shouldFail = false
        dataRevision.value = 1
        advance()

        validate { Text("content 1") }
        assertEquals(1, reported.size, "recovered containment must still be reported")
        verifyConsistent()
    }

    @Test
    fun guardedReset_fromInsideFallbackComposition_reattempts() = compositionTest {
        var failuresToGo = 1
        var fallbackRuns = 0
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(
                fallback = {
                    fallbackRuns++
                    // Pathological but permitted: resetting from inside the fallback's own
                    // composition. Guarded — honored while the re-attempt guard is not exceeded.
                    if (fallbackRuns == 1) reset()
                    Text("fallback")
                }
            ) {
                Text("content")
                if (fail.value && failuresToGo > 0) {
                    failuresToGo--
                    error("boom")
                }
            }
        }

        validate { Text("content") }

        // Trip the committed boundary; the fallback pass issues a guarded reset during its own
        // composition, which re-attempts the (now healthy) content on a subsequent pass.
        fail.value = true
        advance(ignorePendingWork = true)
        advance(ignorePendingWork = true)
        advance()

        validate { Text("content") }
        verifyConsistent()
    }

    @Test
    fun resetKeysRecovery_clearsGuardAfterPartialFailures() = compositionTest {
        var failuresToGo = 2
        val dataRevision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = { Text("fallback") },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                dataRevision.value // observe
                Text("content")
                if (failuresToGo > 0) {
                    failuresToGo--
                    error("boom")
                }
            }
        }

        validate { Text("fallback") }

        // Two data-driven re-attempts fail before the third succeeds — all within the guard.
        dataRevision.value = 1
        advance()
        dataRevision.value = 2
        advance()
        validate { Text("content") }

        // The successful commit cleared the failure count: a fresh failure is contained again
        // and a fresh data change recovers again.
        failuresToGo = 1
        dataRevision.value = 3
        advance()
        validate { Text("fallback") }
        dataRevision.value = 4
        advance()
        validate { Text("content") }
        verifyConsistent()
    }

    @Test
    fun deepThrow_isAttributedWithoutRerunningTheProtectedContent() = compositionTest {
        var contentRuns = 0
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) {
                contentRuns++
                Linear {
                    // The state read lives in a deep child scope: the failing pass recomposes
                    // ONLY that scope, never the boundary's content lambda — attribution must
                    // come from the slot-table walk, not from anything re-running.
                    Text(if (fail.value) error("boom") else "content")
                }
            }
        }

        assertEquals(1, contentRuns)

        fail.value = true
        advance()

        validate { Text("fallback") }
        assertEquals(
            1,
            contentRuns,
            "the protected content lambda must not re-run: the failing pass recomposed only the " +
                "deep child scope, and the fallback pass composed the fallback instead",
        )
        verifyConsistent()
    }

    @Test
    fun siblingStateChange_inTheContainedPass_isStillApplied() = compositionTest {
        val fail = mutableStateOf(false)
        val siblingText = mutableStateOf("old")
        compose {
            Linear {
                ErrorBoundary(fallback = { Text("fallback") }) {
                    Text("content")
                    if (fail.value) error("boom")
                }
                Text(siblingText.value)
            }
        }

        // Both scopes invalidate in the same frame; the pass containing the boundary failure is
        // rolled back, but the sibling's change must still land on the re-attempted pass.
        fail.value = true
        siblingText.value = "new"
        advance()

        validate {
            Linear {
                Text("fallback")
                Text("new")
            }
        }
        verifyConsistent()
    }

    @Test
    fun boundaryRemovedWhileTripped_thenReadded_startsFresh() = compositionTest {
        val showBoundary = mutableStateOf(true)
        var shouldFail = true
        compose {
            Linear {
                if (showBoundary.value) {
                    ErrorBoundary(fallback = { Text("fallback") }) {
                        Text("content")
                        if (shouldFail) error("boom")
                    }
                }
                Text("sibling")
            }
        }

        validate {
            Linear {
                Text("fallback")
                Text("sibling")
            }
        }

        // Remove the tripped boundary entirely, then re-add it at the same call site with the
        // cause cleared: the new boundary must start fresh (no inherited error or guard state
        // from the removed instance).
        showBoundary.value = false
        advance()
        validate { Linear { Text("sibling") } }

        shouldFail = false
        showBoundary.value = true
        advance()
        validate {
            Linear {
                Text("content")
                Text("sibling")
            }
        }
        verifyConsistent()
    }

    @Test
    fun throwToBoundary_whileFallbackShowing_replacesErrorAndReports() = compositionTest {
        val reported = mutableListOf<Throwable>()
        var handle: ErrorBoundaryHandle? = null
        compose {
            ErrorBoundary(
                fallback = { FallbackContent() },
                onError = { error, _ -> reported.add(error) },
            ) {
                handle = LocalErrorBoundary.current
                Text("content")
            }
        }

        val capturedHandle = assertNotNull(handle)
        capturedHandle.throwToBoundary(IllegalStateException("first"))
        advance()
        validate { FallbackText(IllegalStateException("first")) }
        assertEquals(1, reported.size)

        // Forwarding again while the fallback is already showing replaces the displayed error
        // and reports the new containment.
        capturedHandle.throwToBoundary(IllegalStateException("second"))
        advance()
        validate { FallbackText(IllegalStateException("second")) }
        assertEquals(2, reported.size)
        assertEquals("second", reported[1].message)
        verifyConsistent()
    }

    @Test
    fun uncontainedInitialThrow_propagatesUnchanged() = compositionTest {
        val thrown = IllegalStateException("boom")
        val e =
            assertFailsWith<IllegalStateException> {
                compose {
                    Text("before throw")
                    throw thrown
                }
            }
        assertSame(thrown, e)
    }

    @Test
    fun throwInFallback_escalatesToOuterBoundary() = compositionTest {
        compose {
            ErrorBoundary(fallback = { Text("outer fallback: ${error.message}") }) {
                Text("outer content")
                ErrorBoundary(
                    fallback = { error("fallback is broken too") }
                ) {
                    error("boom")
                }
            }
        }

        validate { Text("outer fallback: fallback is broken too") }
        verifyConsistent()
    }

    @Test
    fun nestedBoundaries_innerBoundaryContainsFirst() = compositionTest {
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { Text("outer fallback") }) {
                Linear {
                    Text("outer content")
                    ErrorBoundary(fallback = { FallbackContent() }) {
                        Text("inner content")
                        if (fail.value) error("boom")
                    }
                }
            }
        }

        fail.value = true
        advance()

        validate {
            Linear {
                Text("outer content")
                FallbackText(IllegalStateException("boom"))
            }
        }
        verifyConsistent()
    }

    @Test
    fun reset_reattemptsContentAfterCauseClears() = compositionTest {
        var shouldFail = true
        var capturedReset: (() -> Unit)? = null
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    FallbackContent()
                }
            ) {
                Text("content")
                if (shouldFail) error("boom")
            }
        }

        validate { FallbackText(IllegalStateException("boom")) }

        // Clear the underlying cause, then reset from outside composition (a "user gesture").
        shouldFail = false
        assertNotNull(capturedReset, "fallback should have captured reset")()
        advance()

        validate { Text("content") }
        verifyConsistent()
    }

    @Test
    fun resetKeysChange_reattemptsContent() = compositionTest {
        var shouldFail = true
        val dataRevision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = { FallbackContent() },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                Text("content")
                if (shouldFail) error("boom")
            }
        }

        validate { FallbackText(IllegalStateException("boom")) }

        // Data-driven recovery: the cause clears and the data's revision changes.
        shouldFail = false
        dataRevision.value = 1
        advance()

        validate { Text("content") }
        verifyConsistent()
    }

    @Test
    fun resetKeysLoop_isBoundedAndHoldsFallback() = compositionTest {
        val reported = mutableListOf<Throwable>()
        val dataRevision = mutableStateOf(0)
        var attempts = 0
        compose {
            ErrorBoundary(
                fallback = { Text("fallback") },
                onError = { error, _ -> reported.add(error) },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                attempts++
                error("always broken")
            }
        }

        validate { Text("fallback") }

        // Content that keeps failing while its reset keys keep changing must not spin: the
        // guard stops re-attempting after a bounded number of consecutive failures and reports
        // the loop through onError, holding the fallback.
        repeat(10) {
            dataRevision.value = it + 1
            advance(ignorePendingWork = false)
        }

        validate { Text("fallback") }
        assertEquals(
            4,
            attempts,
            "expected exactly the initial attempt plus the guard's bounded automatic " +
                "re-attempts, but content composed $attempts times",
        )
        assertTrue(
            reported.any { it is ErrorBoundaryResetLoopException },
            "expected the guard to report the re-attempt loop through onError",
        )
        verifyConsistent()
    }

    @Test
    fun throwToBoundary_forwardsOutOfCompositionErrors() = compositionTest {
        var handle: ErrorBoundaryHandle? = null
        compose {
            ErrorBoundary(fallback = { FallbackContent() }) {
                handle = LocalErrorBoundary.current
                Text("content")
            }
        }

        validate { Text("content") }
        val capturedHandle = assertNotNull(handle, "content should see an enclosing boundary")

        // An event handler or coroutine forwards its error explicitly; composition never saw it.
        capturedHandle.throwToBoundary(IllegalStateException("boom from event handler"))
        advance()

        validate { FallbackText(IllegalStateException("boom from event handler")) }
        verifyConsistent()
    }

    @Test
    fun localErrorBoundary_isNullWithoutBoundary() = compositionTest {
        var handle: ErrorBoundaryHandle? = ErrorBoundaryHandle { }
        compose {
            handle = LocalErrorBoundary.current
            Text("content")
        }
        assertNull(handle, "LocalErrorBoundary must default to null outside any boundary")
    }

    @Test
    fun committedContentIsTornDownWhenFallbackReplacesIt() = compositionTest {
        val events = mutableListOf<String>()
        val observed =
            object : RememberObserver {
                override fun onRemembered() {
                    events.add("remembered")
                }

                override fun onForgotten() {
                    events.add("forgotten")
                }

                override fun onAbandoned() {
                    events.add("abandoned")
                }
            }
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) {
                remember { observed }
                Text("content")
                if (fail.value) error("boom")
            }
        }

        assertEquals(listOf("remembered"), events)

        fail.value = true
        advance()

        validate { Text("fallback") }
        assertEquals(
            listOf("remembered", "forgotten"),
            events,
            "committed content replaced by the fallback must receive onForgotten",
        )
    }

    @Test
    fun valuesRememberedInFailedPass_areAbandoned() = compositionTest {
        val events = mutableListOf<String>()
        val observed =
            object : RememberObserver {
                override fun onRemembered() {
                    events.add("remembered")
                }

                override fun onForgotten() {
                    events.add("forgotten")
                }

                override fun onAbandoned() {
                    events.add("abandoned")
                }
            }
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) {
                remember { observed }
                error("boom")
            }
        }

        validate { Text("fallback") }
        assertEquals(
            listOf("abandoned"),
            events,
            "values remembered in the abandoned pass must be abandoned, never remembered",
        )
    }

    @Test
    fun stateWritesInFailedPass_areRolledBack() = compositionTest {
        val counter = mutableStateOf(0)
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) {
                Text("content")
                if (fail.value) {
                    counter.value = 42
                    error("boom")
                }
            }
        }

        fail.value = true
        advance()

        validate { Text("fallback") }
        assertEquals(
            0,
            counter.value,
            "state writes made during the failed pass must be rolled back with the pass",
        )
    }

    @Test
    fun siblingBoundaries_containIndependently() = compositionTest {
        val failFirst = mutableStateOf(false)
        compose {
            Linear {
                key(1) {
                    ErrorBoundary(fallback = { Text("fallback 1") }) {
                        Text("content 1")
                        if (failFirst.value) error("boom")
                    }
                }
                key(2) {
                    ErrorBoundary(fallback = { Text("fallback 2") }) { Text("content 2") }
                }
            }
        }

        failFirst.value = true
        advance()

        validate {
            Linear {
                Text("fallback 1")
                Text("content 2")
            }
        }
        verifyConsistent()
    }

    @Test
    fun recoveredBoundary_containsAgainOnLaterFailure() = compositionTest {
        var shouldFail = true
        var capturedReset: (() -> Unit)? = null
        val revision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    FallbackContent()
                }
            ) {
                revision.value // observe
                Text("content ${revision.value}")
                if (shouldFail) error("boom ${revision.value}")
            }
        }

        validate { FallbackText(IllegalStateException("boom 0")) }

        shouldFail = false
        assertNotNull(capturedReset)()
        advance()
        validate { Text("content 0") }

        // Healthy recompositions succeed...
        revision.value = 1
        advance()
        validate { Text("content 1") }

        // ...and a later failure is contained again (the guard was cleared by success).
        shouldFail = true
        revision.value = 2
        advance()
        validate { FallbackText(IllegalStateException("boom 2")) }
        verifyConsistent()
    }

    @Test
    fun healthyBoundary_composesContentTransparently() = compositionTest {
        val text = mutableStateOf("first")
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) { Text(text.value) }
        }

        validate { Text("first") }

        text.value = "second"
        advance()
        validate { Text("second") }

        expectNoChanges()
        verifyConsistent()
    }

    @Test
    fun subcomposition_boundaryContainsInitialCompositionThrow() = compositionTest {
        var fallbackComposed = false
        compose {
            Text("parent")
            TestSubcomposition {
                ErrorBoundary(
                    fallback = {
                        fallbackComposed = true
                    }
                ) {
                    error("boom in subcomposition")
                }
            }
        }

        validate { Text("parent") }
        assertTrue(
            fallbackComposed,
            "a boundary inside a subcomposition must contain the subcomposition's own " +
                "initial-composition failure",
        )
        verifyConsistent()
    }

    @Test
    fun subcomposition_boundaryContainsRecompositionThrow_andRecomposerStaysAlive() =
        compositionTest {
            val fail = mutableStateOf(false)
            val parentText = mutableStateOf("parent")
            var fallbackComposed = false
            compose {
                Text(parentText.value)
                TestSubcomposition {
                    ErrorBoundary(
                        fallback = {
                            fallbackComposed = true
                        }
                    ) {
                        if (fail.value) error("boom in subcomposition")
                    }
                }
            }

            validate { Text("parent") }

            fail.value = true
            advance()
            assertTrue(fallbackComposed, "recomposition throw in subcomposition must be contained")

            // The recomposer must not be wedged by the contained subcomposition failure: the
            // parent composition still recomposes normally.
            parentText.value = "parent again"
            advance()
            validate { Text("parent again") }
            verifyConsistent()
        }

    @Test
    fun movableContent_trippedBoundaryMovesWithItsFallback_andRecovers() = compositionTest {
        val fail = mutableStateOf(false)
        var capturedReset: (() -> Unit)? = null
        val moved = mutableStateOf(false)
        compose {
            val boundary = remember {
                movableContentOf {
                    ErrorBoundary(
                        fallback = {
                            capturedReset = ::reset
                            Text("fallback")
                        }
                    ) {
                        Text("content")
                        if (fail.value) error("boom")
                    }
                }
            }
            if (moved.value) {
                Linear { boundary() }
            } else {
                boundary()
            }
        }

        // The boundary composes healthily inside movable content, then a recomposition failure
        // is contained normally.
        validate { Text("content") }
        fail.value = true
        advance()
        validate { Text("fallback") }

        // Move the tripped boundary: its remembered error state moves with it, so the fallback
        // persists at the new location.
        moved.value = true
        advance()
        validate { Linear { Text("fallback") } }

        // And it recovers normally at the new location.
        fail.value = false
        assertNotNull(capturedReset)()
        advance()
        validate { Linear { Text("content") } }
        verifyConsistent()
    }

    @Test
    fun pausableComposition_initialContainedFailure_composesFallback() = compositionTest {
        val pausedRoot = View().apply { name = "pausedRoot" }
        compose {
            val parent = rememberCompositionContext()
            DisposableEffect(Unit) {
                val pausableComposition = PausableComposition(ViewApplier(pausedRoot), parent)
                val handle =
                    pausableComposition.setPausableContent {
                        ErrorBoundary(fallback = { Text("fallback") }) {
                            Text("content")
                            error("boom in pausable composition")
                        }
                    }
                while (!handle.isComplete) {
                    handle.resume { false }
                }
                handle.apply()
                onDispose { pausableComposition.dispose() }
            }
        }

        assertEquals(
            listOf("fallback"),
            pausedRoot.children.map { it.text },
            "a contained failure during a pausable initial composition must compose the fallback",
        )
        verifyConsistent()
    }

    @Test
    fun runawayNestedEscalation_hitsTheHardCapInsteadOfLoopingForever() = compositionTest {
        @Composable
        fun Nested(depth: Int) {
            if (depth == 0) {
                error("bottom")
            } else {
                ErrorBoundary(fallback = { error("fallback $depth is broken") }) {
                    Nested(depth - 1)
                }
            }
        }

        // Every boundary's fallback throws too, so each contained failure escalates one level
        // up; with more levels than the hard cap the runtime must give up with its own error
        // instead of looping forever.
        val e =
            assertFailsWith<IllegalStateException> {
                compose { Nested(ErrorBoundaryHardContainmentCap + 8) }
            }
        assertTrue(
            e.message.orEmpty().contains("giving up on the re-attempt loop"),
            "expected the hard-cap runtime error, but was: ${e.message}",
        )
    }
}
// Note: subcomposition tests reuse the internal TestSubcomposition helper defined alongside
// CompositionTests in this package.
