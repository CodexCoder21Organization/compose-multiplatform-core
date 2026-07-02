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
import androidx.compose.runtime.mock.MockViewValidator
import androidx.compose.runtime.mock.Text
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

@OptIn(ExperimentalComposeRuntimeApi::class)
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
        compose {
            ErrorBoundary(
                fallback = { FallbackContent() },
                onError = { error, info -> reported.add(error to info) },
            ) {
                Text("content")
                if (fail.value) throw thrown
            }
        }

        assertTrue(reported.isEmpty(), "onError should not fire while content is healthy")

        fail.value = true
        advance()

        assertEquals(1, reported.size, "expected exactly one onError report")
        assertSame(thrown, reported.single().first)
        assertNotNull(reported.single().second)

        // Further unrelated recompositions must not re-report the same contained error.
        advance()
        assertEquals(1, reported.size, "onError must not re-report the same error")
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
        assertTrue(
            attempts <= 6,
            "expected the re-attempt guard to stop the loop, but content composed " +
                "$attempts times",
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
}
