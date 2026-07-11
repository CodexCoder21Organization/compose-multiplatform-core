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

package androidx.compose.runtime.mock

import androidx.compose.runtime.Composition
import androidx.compose.runtime.ErrorBoundary
import androidx.compose.runtime.ErrorBoundaryHandle
import androidx.compose.runtime.ErrorBoundaryScope
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.LocalErrorBoundary
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
internal fun assertErrorBoundaryDemoTranscript(target: String, expected: String) {
    runTest {
        val actual = withContext(TestMonotonicFrameClock(this)) { runErrorBoundaryDemo(target) }
        assertEquals(expected, actual)
    }
}

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
private suspend fun TestScope.runErrorBoundaryDemo(target: String): String = coroutineScope {
    runErrorBoundaryDemo(target, this@runErrorBoundaryDemo)
}

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
private suspend fun CoroutineScope.runErrorBoundaryDemo(
    target: String,
    testScope: TestScope,
): String {
    val recomposer = Recomposer(coroutineContext)
    val recomposerJob = launch { recomposer.runRecomposeAndApplyChanges() }
    val root = View().also { it.name = "root" }
    val composition = Composition(ViewApplier(root), recomposer)

    var failContent by mutableStateOf(true)
    var fallbackScope: ErrorBoundaryScope? = null
    var boundaryHandle: ErrorBoundaryHandle? = null
    val transcript = StringBuilder()

    try {
        composition.setContent {
            ErrorBoundary(
                fallback = {
                    fallbackScope = this
                    Text("fallback: ${error.message}")
                }
            ) {
                boundaryHandle = LocalErrorBoundary.current
                if (failContent) error("demo composition boom")
                Text("recovered content")
            }
        }

        transcript.appendTree("composition throw", root)

        failContent = false
        checkNotNull(fallbackScope) { "ErrorBoundary fallback scope was not captured" }.reset()
        testScope.advanceFrame(recomposer)

        transcript.appendTree("reset recovery", root)

        checkNotNull(boundaryHandle) { "LocalErrorBoundary handle was not captured" }
            .throwToBoundary(IllegalStateException("demo forwarded boom"))
        testScope.advanceFrame(recomposer)

        transcript.appendTree("throwToBoundary", root)

        transcript.appendLine("DEMO OK: $target")
        return transcript.toString()
    } finally {
        composition.dispose()
        recomposer.cancel()
        recomposerJob.join()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.advanceFrame(recomposer: Recomposer) {
    testScheduler.runCurrent()
    Snapshot.sendApplyNotifications()
    testScheduler.advanceTimeBy(5_000)
    testScheduler.runCurrent()
    check(!recomposer.hasPendingWork) { "Recomposer still has pending work after advancing" }
}

private fun StringBuilder.appendTree(label: String, root: View) {
    appendLine("[$label]")
    append(root.toFmtString())
}
