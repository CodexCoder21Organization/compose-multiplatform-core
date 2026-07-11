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

import androidx.compose.runtime.mock.ComposerToUse
import androidx.compose.runtime.mock.Text
import androidx.compose.runtime.mock.compositionTest
import androidx.compose.runtime.mock.validate
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
class ErrorBoundaryJvmTests {
    @Test
    fun forgottenBoundaryHandleDoesNotRetainDisposedComposition(): Unit = runTest {
        var retainedHandle: ErrorBoundaryHandle? = null
        localRecomposerTest { recomposer ->
            fun createAndDisposeComposition(): WeakReference<Composition> {
                val composition = Composition(UnitApplier(), recomposer)
                composition.setContent {
                    ErrorBoundary(fallback = {}) { retainedHandle = LocalErrorBoundary.current }
                }
                val weakComposition = WeakReference(composition)
                composition.dispose()
                return weakComposition
            }

            val weakComposition = createAndDisposeComposition()
            forceGcUntilCleared(weakComposition)

            assertNull(
                weakComposition.get(),
                "a retained ErrorBoundaryHandle must not keep its forgotten composition alive",
            )

            retainedHandle?.throwToBoundary(IllegalStateException("stale"))
        }
    }

    @Test
    fun forgottenBoundaryHandleDoesNotRetainLastErrorGraph() = compositionTest {
        var retainedHandle: ErrorBoundaryHandle? = null
        val showBoundary = mutableStateOf(true)
        compose {
            if (showBoundary.value) {
                ErrorBoundary(fallback = { Text("fallback") }) {
                    retainedHandle = LocalErrorBoundary.current
                    Text("content")
                }
            } else {
                Text("removed")
            }
        }

        validate { Text("content") }
        fun forwardRetainingError(): WeakReference<Any> {
            val payload = Any()
            retainedHandle?.throwToBoundary(
                object : IllegalStateException("retaining error") {
                    @Suppress("unused") val retainedPayload = payload
                }
            )
            return WeakReference(payload)
        }
        val weakPayload = forwardRetainingError()
        advance()
        validate { Text("fallback") }

        showBoundary.value = false
        advance()
        validate { Text("removed") }
        forceGcUntilCleared(weakPayload)

        assertNull(
            weakPayload.get(),
            "a retained forgotten ErrorBoundaryHandle must not keep the last error graph alive",
        )

        val stalePayload = forwardRetainingError()
        forceGcUntilCleared(stalePayload)
        assertNull(
            stalePayload.get(),
            "a retained forgotten ErrorBoundaryHandle must not retain newly forwarded errors",
        )
    }

    @Test
    fun throwToBoundaryAndResetFromBackgroundThreads() = compositionTest {
        val handleRef = AtomicReference<ErrorBoundaryHandle?>()
        val resetRef = AtomicReference<(() -> Unit)?>()
        compose {
            ErrorBoundary(
                fallback = {
                    resetRef.set(::reset)
                    Text("fallback: ${error.message}")
                }
            ) {
                resetRef.set(null)
                handleRef.set(LocalErrorBoundary.current)
                Text("content")
            }
        }

        validate { Text("content") }
        val handle = assertNotNull(handleRef.get(), "content should capture a boundary handle")

        repeat(32) { round ->
            runOnBackgroundThread {
                handle.throwToBoundary(IllegalStateException("background $round"))
            }
            advance()
            validate { Text("fallback: background $round") }

            val reset = assertNotNull(resetRef.get(), "fallback should expose reset")
            runOnBackgroundThread { reset() }
            advance()
            validate { Text("content") }
        }
    }

    @Test
    fun launchedEffectThrow_underBoundaryEscapesWithoutTrippingBoundary() {
        for (composerToUse in listOf(ComposerToUse.Gap, ComposerToUse.Link)) {
            var fallbackComposed = false
            val thrown =
                assertFailsWith<IllegalStateException> {
                    compositionTest(composerToUse) {
                        compose {
                            ErrorBoundary(
                                fallback = {
                                    fallbackComposed = true
                                    Text("fallback: ${error.message}")
                                }
                            ) {
                                Text("content")
                                LaunchedEffect(Unit) { error("launched effect boom") }
                            }
                        }

                        validate { Text("content") }
                        testCoroutineScheduler.runCurrent()
                    }
                }

            assertEquals("launched effect boom", thrown.message)
            assertFalse(
                fallbackComposed,
                "LaunchedEffect failures must escape without composing the boundary fallback",
            )
        }
    }

    private fun runOnBackgroundThread(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                block()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.join()
        failure.get()?.let { throw it }
    }

    private fun forceGcUntilCleared(reference: WeakReference<*>) {
        repeat(10) {
            if (reference.get() == null) return
            Runtime.getRuntime().gc()
            Thread.sleep(10)
        }
    }
}
