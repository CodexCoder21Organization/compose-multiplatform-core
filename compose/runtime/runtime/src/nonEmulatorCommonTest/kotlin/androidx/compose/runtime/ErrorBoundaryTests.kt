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

import androidx.compose.runtime.mock.Linear
import androidx.compose.runtime.mock.MockViewListValidator
import androidx.compose.runtime.mock.MockViewValidator
import androidx.compose.runtime.mock.TestMonotonicFrameClock
import androidx.compose.runtime.mock.Text
import androidx.compose.runtime.mock.View
import androidx.compose.runtime.mock.ViewApplier
import androidx.compose.runtime.mock.compositionTest
import androidx.compose.runtime.mock.expectNoChanges
import androidx.compose.runtime.mock.validate
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(
    ExperimentalComposeRuntimeApi::class,
    ExperimentalCoroutinesApi::class,
    InternalComposeApi::class,
)
@Suppress("UNUSED_EXPRESSION")
class ErrorBoundaryTests {

    private fun MockViewValidator.FallbackText(error: Throwable) {
        Text("fallback: ${error.message}")
    }

    @Composable
    private fun ErrorBoundaryScope.FallbackContent() {
        Text("fallback: ${error.message}")
    }

    private fun View.flattenedText(): List<String> =
        listOfNotNull(text) + children.flatMap { it.flattenedText() }

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
    fun rootBoundary_initialCompositionThrow_composesFallback() = compositionTest {
        compose { ErrorBoundary(fallback = { FallbackContent() }) { error("root initial boom") } }

        validate { FallbackText(IllegalStateException("root initial boom")) }
        verifyConsistent()
    }

    @Test
    fun rootBoundary_recompositionThrow_composesFallback() = compositionTest {
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { FallbackContent() }) {
                Text("root content")
                if (fail.value) error("root recomposition boom")
            }
        }

        validate { Text("root content") }

        fail.value = true
        advance()

        validate { FallbackText(IllegalStateException("root recomposition boom")) }
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
                fallback = { Text("fallback ${fallbackRevision.value}") },
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
    fun onErrorThrowing_doesNotCorruptBoundaryState() = compositionTest {
        var shouldFail = true
        var throwFromOnError = true
        var capturedReset: (() -> Unit)? = null
        val siblingText = mutableStateOf("sibling")
        compose {
            Linear {
                ErrorBoundary(
                    fallback = {
                        capturedReset = ::reset
                        FallbackContent()
                    },
                    onError = { _, _ -> if (throwFromOnError) error("onError boom") },
                ) {
                    if (shouldFail) error("content boom") else Text("content")
                }
                Text(siblingText.value)
            }
        }

        validate {
            Linear {
                FallbackText(IllegalStateException("content boom"))
                Text("sibling")
            }
        }

        throwFromOnError = false
        shouldFail = false
        siblingText.value = "updated sibling"
        assertNotNull(capturedReset, "fallback should have captured reset")()
        advance()

        validate {
            Linear {
                Text("content")
                Text("updated sibling")
            }
        }
        verifyConsistent()
    }

    @Test
    fun sideEffectThrow_underBoundary_isNotContained() = compositionTest {
        val e =
            assertFailsWith<IllegalStateException> {
                compose {
                    ErrorBoundary(fallback = { Text("fallback") }) {
                        Text("content")
                        SideEffect { error("side effect boom") }
                    }
                }
            }

        assertEquals("side effect boom", e.message)
    }

    @Test
    fun disposableEffectInitThrow_underBoundary_isNotContained() = compositionTest {
        val e =
            assertFailsWith<IllegalStateException> {
                compose {
                    ErrorBoundary(fallback = { Text("fallback") }) {
                        Text("content")
                        DisposableEffect(Unit) {
                            error("disposable init boom")
                            onDispose {}
                        }
                    }
                }
            }

        assertEquals("disposable init boom", e.message)
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
    fun resetKeysAutoResetGuard_allowsExactlyThreeAutomaticReattempts() = compositionTest {
        var attempts = 0
        val dataRevision = mutableStateOf(0)
        val reported = mutableListOf<String>()
        compose {
            ErrorBoundary(
                fallback = { FallbackContent() },
                onError = { error, _ -> reported += error.message ?: "" },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                dataRevision.value
                attempts++
                Text("attempt $attempts")
                error("boom attempt $attempts")
            }
        }

        validate { FallbackText(IllegalStateException("boom attempt 1")) }
        assertEquals(1, attempts)

        repeat(3) { index ->
            dataRevision.value = index + 1
            advance()
            validate { FallbackText(IllegalStateException("boom attempt ${index + 2}")) }
            assertEquals(index + 2, attempts)
        }

        dataRevision.value = 4
        advance()

        validate { FallbackText(IllegalStateException("boom attempt 4")) }
        assertEquals(4, attempts, "the guard must suppress a fifth content attempt")
        assertEquals(
            listOf(
                "boom attempt 1",
                "boom attempt 2",
                "boom attempt 3",
                "boom attempt 4",
                "ErrorBoundary content kept failing across 3 consecutive automatic re-attempts " +
                    "with no successful composition in between; holding the fallback. An " +
                    "explicit reset() from outside composition re-attempts and clears this guard.",
            ),
            reported,
        )
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
    fun rememberThrow_underCompositionLocalAndKey_isAttributedWithoutRerunningContent() =
        compositionTest {
            val local = compositionLocalOf { "missing" }
            val revision = mutableStateOf(0)
            var contentRuns = 0

            @Composable
            fun DeepChild() {
                CompositionLocalProvider(local provides "provided") {
                    key("deep-key") {
                        val remembered =
                            remember(revision.value) {
                                if (revision.value == 1) error("boom in remember")
                                "remembered ${revision.value}"
                            }
                        Text("${local.current}: $remembered")
                    }
                }
            }

            compose {
                ErrorBoundary(fallback = { FallbackContent() }) {
                    contentRuns++
                    DeepChild()
                }
            }

            validate { Text("provided: remembered 0") }
            assertEquals(1, contentRuns)

            revision.value = 1
            advance()

            validate { FallbackText(IllegalStateException("boom in remember")) }
            assertEquals(
                1,
                contentRuns,
                "the protected content lambda must not re-run when only the deep child scope " +
                    "throws from remember",
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
    fun recursiveSameCallSiteBoundaries_containSelectedDepthsIndependently() = compositionTest {
        val failingDepth = mutableStateOf<Int?>(null)
        val reported = mutableListOf<String>()

        @Composable
        fun Nested(depth: Int) {
            ErrorBoundary(
                fallback = { Text("fallback $depth: ${error.message}") },
                onError = { error, _ -> reported += "depth $depth: ${error.message}" },
            ) {
                Text("content $depth")
                if (failingDepth.value == depth) error("boom $depth")
                if (depth > 0) Nested(depth - 1)
            }
        }

        compose { Nested(24) }

        assertEquals((24 downTo 0).map { "content $it" }, root.flattenedText())

        failingDepth.value = 7
        advance()

        assertEquals(
            (24 downTo 8).map { "content $it" } + "fallback 7: boom 7",
            root.flattenedText(),
        )
        assertEquals(listOf("depth 7: boom 7"), reported)
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
                ErrorBoundary(fallback = { error("fallback is broken too") }) { error("boom") }
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
    fun forcedReset_afterAutoResetGuardTrips_isHonoredAndClearsGuard() = compositionTest {
        val dataRevision = mutableStateOf(0)
        var shouldFail = true
        var attempts = 0
        var capturedReset: (() -> Unit)? = null
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    Text("fallback")
                },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                attempts++
                Text("content")
                if (shouldFail) error("boom $attempts")
            }
        }

        repeat(10) {
            dataRevision.value = it + 1
            advance(ignorePendingWork = false)
        }

        validate { Text("fallback") }
        assertEquals(
            4,
            attempts,
            "expected the guard to stop after the initial attempt plus three auto-resets",
        )

        shouldFail = false
        assertNotNull(capturedReset, "fallback should have captured reset")()
        advance()

        validate { Text("content") }
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
    fun throwToBoundary_afterBoundaryLeavesComposition_isNoOp() = compositionTest {
        val showBoundary = mutableStateOf(true)
        var handle: ErrorBoundaryHandle? = null
        compose {
            Linear {
                if (showBoundary.value) {
                    ErrorBoundary(fallback = { FallbackContent() }) {
                        handle = LocalErrorBoundary.current
                        Text("content")
                    }
                }
                Text("sibling")
            }
        }

        val capturedHandle = assertNotNull(handle, "content should see an enclosing boundary")
        showBoundary.value = false
        advance()
        validate { Linear { Text("sibling") } }

        capturedHandle.throwToBoundary(IllegalStateException("late event"))
        advance()

        validate { Linear { Text("sibling") } }
        verifyConsistent()
    }

    @Test
    fun throwToBoundary_duringFailedComposition_reportsBothErrorsAndShowsLatest() =
        compositionTest {
            val fail = mutableStateOf(false)
            val reported = mutableListOf<String>()
            var handle: ErrorBoundaryHandle? = null
            compose {
                ErrorBoundary(
                    fallback = { FallbackContent() },
                    onError = { error, _ -> reported.add(error.message ?: "") },
                ) {
                    handle = LocalErrorBoundary.current
                    Text("content")
                    if (fail.value) {
                        assertNotNull(handle)
                            .throwToBoundary(IllegalStateException("forwarded during failed pass"))
                        error("composition failure")
                    }
                }
            }

            fail.value = true
            advance()

            validate { FallbackText(IllegalStateException("forwarded during failed pass")) }
            assertEquals(
                listOf("composition failure", "forwarded during failed pass"),
                reported,
                "the boundary should report every accepted error exactly once while showing the " +
                    "latest accepted error",
            )
            verifyConsistent()
        }

    @Test
    fun forwardedErrors_doNotTripTheAutoResetGuard() = compositionTest {
        var handle: ErrorBoundaryHandle? = null
        val dataRevision = mutableStateOf(0)
        compose {
            ErrorBoundary(
                fallback = { Text("fallback") },
                resetKeys = arrayOf(dataRevision.value),
            ) {
                handle = LocalErrorBoundary.current
                Text("content ${dataRevision.value}")
            }
        }
        validate { Text("content 0") }
        val capturedHandle = assertNotNull(handle)

        // Forward well over MaxConsecutiveAutoResetAttempts errors, each recovered by a data
        // revision change: forwarded errors follow successful compositions, so they must never
        // exhaust the composition re-attempt guard.
        repeat(6) { round ->
            capturedHandle.throwToBoundary(IllegalStateException("effect failure $round"))
            advance()
            validate { Text("fallback") }
            dataRevision.value = round + 1
            advance()
            validate { Text("content ${round + 1}") }
        }
        verifyConsistent()
    }

    @Test
    fun throwToBoundary_onStaleHandleAfterBoundaryLeavesComposition_isIgnored() =
        compositionTest {
            val showBoundary = mutableStateOf(true)
            var capturedHandle: ErrorBoundaryHandle? = null
            var compositionCount = 0
            compose {
                compositionCount++
                Linear {
                    if (showBoundary.value) {
                        ErrorBoundary(fallback = { FallbackContent() }) {
                            capturedHandle = LocalErrorBoundary.current
                            Text("content")
                        }
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
            val staleHandle = assertNotNull(capturedHandle)

            showBoundary.value = false
            advance()
            validate { Linear { Text("sibling") } }
            val compositionCountAfterRemoval = compositionCount

            staleHandle.throwToBoundary(IllegalStateException("stale"))
            val changes = advanceCount()
            assertEquals(
                0,
                changes,
                "throwToBoundary on a forgotten boundary handle must not schedule changes",
            )
            assertEquals(
                compositionCountAfterRemoval,
                compositionCount,
                "throwToBoundary on a forgotten boundary handle must not recompose the parent",
            )
            validate { Linear { Text("sibling") } }

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
    fun sideEffectThrow_underBoundaryEscapesWithoutTrippingBoundary() = compositionTest {
        var fallbackComposed = false
        val thrown =
            assertFailsWith<IllegalStateException> {
                compose {
                    ErrorBoundary(
                        fallback = {
                            fallbackComposed = true
                            FallbackContent()
                        }
                    ) {
                        Text("content")
                        SideEffect { error("side effect boom") }
                    }
                }
            }

        assertEquals("side effect boom", thrown.message)
        assertFalse(fallbackComposed, "apply-phase SideEffect failures are not boundary-contained")
    }

    @Test
    fun localErrorBoundary_isNullWithoutBoundary() = compositionTest {
        var handle: ErrorBoundaryHandle? = ErrorBoundaryHandle {}
        compose {
            handle = LocalErrorBoundary.current
            Text("content")
        }
        assertNull(handle, "LocalErrorBoundary must default to null outside any boundary")
    }

    @Test
    fun fallbackSeesOuterCompositionLocalsAndOuterErrorBoundaryHandle() = compositionTest {
        val localToken = compositionLocalOf { "default" }
        var outerHandleFromContent: ErrorBoundaryHandle? = null
        var innerHandleFromContent: ErrorBoundaryHandle? = null
        var handleSeenInInnerFallback: ErrorBoundaryHandle? = null
        compose {
            ErrorBoundary(fallback = { Text("outer fallback") }) {
                outerHandleFromContent = LocalErrorBoundary.current
                CompositionLocalProvider(localToken provides "provided") {
                    ErrorBoundary(
                        fallback = {
                            handleSeenInInnerFallback = LocalErrorBoundary.current
                            Text("${localToken.current}: ${error.message}")
                        }
                    ) {
                        innerHandleFromContent = LocalErrorBoundary.current
                        error("boom")
                    }
                }
            }
        }

        validate { Text("provided: boom") }
        val outerHandle = assertNotNull(outerHandleFromContent)
        val innerHandle = assertNotNull(innerHandleFromContent)
        assertSame(
            outerHandle,
            handleSeenInInnerFallback,
            "a fallback composes outside its own boundary marker and must see the outer handle",
        )
        assertTrue(
            outerHandle !== innerHandle,
            "content inside a nested boundary must see the inner handle, not the outer one",
        )
        verifyConsistent()
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
    fun disposableEffectFromFailedPass_neverStarts() = compositionTest {
        val events = mutableListOf<String>()
        compose {
            ErrorBoundary(fallback = { Text("fallback") }) {
                DisposableEffect(Unit) {
                    events.add("effect started")
                    onDispose { events.add("effect disposed") }
                }
                error("boom")
            }
        }

        validate { Text("fallback") }
        assertTrue(
            events.isEmpty(),
            "DisposableEffect registered in an abandoned composition pass must never start",
        )
        verifyConsistent()
    }

    @Test
    fun fallbackDisposableEffect_disposesExactlyOnceWhenBoundaryRecovers() = compositionTest {
        var shouldFail = true
        var capturedReset: (() -> Unit)? = null
        var fallbackStarts = 0
        var fallbackDisposes = 0
        var contentStarts = 0
        var contentDisposes = 0
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    DisposableEffect(Unit) {
                        fallbackStarts++
                        onDispose { fallbackDisposes++ }
                    }
                    Text("fallback")
                }
            ) {
                DisposableEffect(Unit) {
                    contentStarts++
                    onDispose { contentDisposes++ }
                }
                Text("content")
                if (shouldFail) error("boom")
            }
        }

        validate { Text("fallback") }
        assertEquals(1, fallbackStarts, "fallback effect should start once")
        assertEquals(0, fallbackDisposes, "fallback effect should still be active")
        assertEquals(0, contentStarts, "failed content effect must not start")
        assertEquals(0, contentDisposes, "failed content effect must not dispose")

        shouldFail = false
        assertNotNull(capturedReset, "fallback should have captured reset")()
        advance()

        validate { Text("content") }
        assertEquals(1, fallbackStarts, "fallback effect should not restart during recovery")
        assertEquals(1, fallbackDisposes, "fallback effect should dispose exactly once")
        assertEquals(1, contentStarts, "healthy content effect should start once")
        assertEquals(0, contentDisposes, "recovered content effect should still be active")
        verifyConsistent()
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
                key(2) { ErrorBoundary(fallback = { Text("fallback 2") }) { Text("content 2") } }
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
    fun userKey208_insideBoundaryDoesNotCollideWithReservedMarkerKey() = compositionTest {
        val fail = mutableStateOf(false)
        compose {
            ErrorBoundary(fallback = { FallbackContent() }) {
                key(208) {
                    Text("content")
                    if (fail.value) error("boom")
                }
            }
        }

        validate { Text("content") }

        fail.value = true
        advance()

        validate { FallbackText(IllegalStateException("boom")) }
        verifyConsistent()
    }

    @Test
    fun sameCallSiteSiblingBoundaries_withoutKey_containIndependently() = compositionTest {
        val failSecond = mutableStateOf(false)
        val hashes = arrayOfNulls<CompositeKeyHashCode>(2)
        compose {
            Linear {
                repeat(2) { index ->
                    ErrorBoundary(fallback = { Text("fallback $index: ${error.message}") }) {
                        hashes[index] = currentCompositeKeyHashCode
                        Text("content $index")
                        if (failSecond.value && index == 1) error("boom $index")
                    }
                }
            }
        }

        validate {
            Linear {
                Text("content 0")
                Text("content 1")
            }
        }
        assertTrue(
            hashes[0] != hashes[1],
            "same-call-site sibling boundary content should receive distinct composite hashes",
        )

        failSecond.value = true
        advance()

        validate {
            Linear {
                Text("content 0")
                Text("fallback 1: boom 1")
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
        compose { ErrorBoundary(fallback = { Text("fallback") }) { Text(text.value) } }

        validate { Text("first") }

        text.value = "second"
        advance()
        validate { Text("second") }

        expectNoChanges()
        verifyConsistent()
    }

    @Test
    fun boundaryInsertedDuringRecomposition_containsItsFirstCompositionFailure() = compositionTest {
        val show = mutableStateOf(false)
        var shouldFail = true
        var capturedReset: (() -> Unit)? = null
        compose {
            Linear {
                if (show.value) {
                    ErrorBoundary(
                        fallback = {
                            capturedReset = ::reset
                            Text("fallback")
                        }
                    ) {
                        Text("content")
                        if (shouldFail) error("boom on first appearance")
                    }
                }
                Text("sibling")
            }
        }

        validate { Linear { Text("sibling") } }

        // The boundary is INSERTED by a recomposition and its content throws in that same
        // pass: the containment must still schedule the fallback (the boundary's own scope
        // belongs to the abandoned insert and cannot be used for rescheduling).
        show.value = true
        advance(ignorePendingWork = true)
        advance(ignorePendingWork = true)

        validate {
            Linear {
                Text("fallback")
                Text("sibling")
            }
        }

        // And the freshly-inserted boundary recovers normally.
        shouldFail = false
        assertNotNull(capturedReset, "fallback should have composed and captured reset")()
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
    fun subcomposition_boundaryContainsInitialCompositionThrow() = compositionTest {
        var fallbackComposed = false
        compose {
            Text("parent")
            TestSubcomposition {
                ErrorBoundary(fallback = { fallbackComposed = true }) {
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
                    ErrorBoundary(fallback = { fallbackComposed = true }) {
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
    fun movableContentChild_insertedUnderBoundary_isContained() = compositionTest {
        compose {
            val child = remember {
                movableContentOf {
                    Text("movable before throw")
                    error("boom in movable child")
                }
            }
            ErrorBoundary(fallback = { FallbackContent() }) { child() }
        }

        validate { FallbackText(IllegalStateException("boom in movable child")) }
        verifyConsistent()
    }

    @Test
    fun movableContentChildren_insertedInSameDeferredBatch_keepSuccessfulSibling(): TestResult =
        runTest {
            val testClock = TestMonotonicFrameClock(this)
            withContext(testClock) {
                val moveToChildren = mutableStateOf(false)
                val reported = mutableListOf<String>()
                val rememberEvents = mutableListOf<String>()
                val observed =
                    object : RememberObserver {
                        override fun onRemembered() {
                            rememberEvents += "remembered"
                        }

                        override fun onForgotten() {
                            rememberEvents += "forgotten"
                        }

                        override fun onAbandoned() {
                            rememberEvents += "abandoned"
                        }
                    }
                val successfulRoot = View().apply { name = "successful root" }
                val throwingRoot = View().apply { name = "throwing root" }
                val recomposer = Recomposer(coroutineContext)
                val runner = launch { recomposer.runRecomposeAndApplyChanges() }
                testScheduler.runCurrent()
                val successfulComposition = Composition(ViewApplier(successfulRoot), recomposer)
                val throwingComposition = Composition(ViewApplier(throwingRoot), recomposer)

                val successfulChild = movableContentOf {
                    remember { observed }
                    Text("successful movable")
                }
                val throwingChild = movableContentOf {
                    Text("throwing movable before")
                    if (moveToChildren.value) {
                        error("boom in deferred sibling")
                    }
                }

                try {
                    successfulComposition.setContent {
                        if (moveToChildren.value) {
                            successfulChild()
                        } else {
                            Text("successful waiting")
                        }
                    }
                    throwingComposition.setContent {
                        ErrorBoundary(
                            fallback = { FallbackContent() },
                            onError = { error, _ -> reported += error.message ?: "" },
                        ) {
                            if (moveToChildren.value) {
                                throwingChild()
                            } else {
                                Text("throwing waiting")
                            }
                        }
                    }

                    MockViewListValidator(successfulRoot.children).validate {
                        Text("successful waiting")
                    }
                    MockViewListValidator(throwingRoot.children).validate {
                        Text("throwing waiting")
                    }
                    assertEquals(emptyList(), rememberEvents)

                    Snapshot.withMutableSnapshot { moveToChildren.value = true }
                    Snapshot.sendApplyNotifications()
                    assertNotNull(
                        withTimeoutOrNull(3_000) { recomposer.awaitIdle() },
                        "timed out waiting for deferred movable insert recomposition",
                    )
                    testScheduler.advanceTimeBy(5_000)
                    assertNotNull(
                        withTimeoutOrNull(3_000) { recomposer.awaitIdle() },
                        "timed out waiting for contained boundary fallback recomposition",
                    )

                    MockViewListValidator(successfulRoot.children).validate {
                        Text("successful movable")
                    }
                    MockViewListValidator(throwingRoot.children).validate {
                        FallbackText(IllegalStateException("boom in deferred sibling"))
                    }
                    assertEquals(listOf("boom in deferred sibling"), reported)
                    assertEquals(listOf("remembered"), rememberEvents)
                    (successfulComposition as ControlledComposition).verifyConsistent()
                    (throwingComposition as ControlledComposition).verifyConsistent()
                } finally {
                    successfulComposition.dispose()
                    throwingComposition.dispose()
                    recomposer.close()
                    assertNotNull(
                        withTimeoutOrNull(3_000) { runner.join() },
                        "timed out waiting for recomposer runner to finish",
                    )
                }
            }
        }

    @Test
    fun nestedMovableContent_containedAfterSuccessfulInsert_keepsSibling() = compositionTest {
        val move = mutableStateOf(false)
        val reported = mutableListOf<String>()

        val successfulChild = movableContentOf { Text("successful movable") }
        val nestedThrowingChild = movableContentOf {
            Text("nested movable before")
            if (move.value) error("boom in nested movable")
        }
        val throwingHost = movableContentOf {
            Text("throwing host")
            nestedThrowingChild()
        }

        compose {
            if (move.value) {
                successfulChild()
            } else {
                Text("successful waiting")
            }
            ErrorBoundary(
                fallback = { FallbackContent() },
                onError = { error, _ -> reported += error.message ?: "" },
            ) {
                if (move.value) {
                    throwingHost()
                } else {
                    Text("throwing waiting")
                }
            }
        }

        validate {
            Text("successful waiting")
            Text("throwing waiting")
        }

        move.value = true
        advance()

        validate {
            Text("successful movable")
            FallbackText(IllegalStateException("boom in nested movable"))
        }
        assertEquals(listOf("boom in nested movable"), reported)
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

    @Test
    fun equivalentThrowableInstance_updatesFallbackScopeError() = compositionTest {
        class EquivalentThrowable(message: String) : RuntimeException(message) {
            override fun equals(other: Any?): Boolean = other is EquivalentThrowable

            override fun hashCode(): Int = 7
        }

        val first = EquivalentThrowable("first")
        val second = EquivalentThrowable("second")
        var thrown = first
        var capturedReset: (() -> Unit)? = null
        compose {
            ErrorBoundary(
                fallback = {
                    capturedReset = ::reset
                    FallbackContent()
                }
            ) {
                throw thrown
            }
        }

        validate { Text("fallback: first") }

        thrown = second
        assertNotNull(capturedReset)()
        advance()

        validate { Text("fallback: second") }
        verifyConsistent()
    }

    @Test
    fun resetKeysInPlaceElementChange_reattemptsContent() = compositionTest {
        var shouldFail = true
        val resetKeys = arrayOf<Any?>(0)
        val revision = mutableStateOf(0)
        compose {
            val observedRevision = revision.value
            resetKeys[0] = observedRevision
            ErrorBoundary(
                fallback = { FallbackContent() },
                onError = { _, _ -> observedRevision.hashCode() },
                resetKeys = resetKeys,
            ) {
                Text("content")
                if (shouldFail) error("boom")
            }
        }

        validate { FallbackText(IllegalStateException("boom")) }

        // The resetKeys contract is element-wise content equality, not array identity. Reusing a
        // stable array instance is therefore still a key change when one of its elements changes.
        shouldFail = false
        revision.value = 1
        advance()

        validate { Text("content") }
        verifyConsistent()
    }
}
// Note: subcomposition tests reuse the internal TestSubcomposition helper defined alongside
// CompositionTests in this package.
