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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
internal fun runErrorBoundaryDemoMain(target: String) {
    runTest { withContext(TestMonotonicFrameClock(this)) { runErrorBoundaryDemo(target) } }
}

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
private suspend fun TestScope.runErrorBoundaryDemo(target: String) {
    coroutineScope { runErrorBoundaryDemo(target, this@runErrorBoundaryDemo) }
}

@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalCoroutinesApi::class)
private suspend fun CoroutineScope.runErrorBoundaryDemo(target: String, testScope: TestScope) {
    val recomposer = Recomposer(coroutineContext)
    val recomposerJob = launch { recomposer.runRecomposeAndApplyChanges() }
    val root = View().also { it.name = "root" }
    val composition = Composition(ViewApplier(root), recomposer)

    var failContent by mutableStateOf(true)
    var fallbackScope: ErrorBoundaryScope? = null
    var boundaryHandle: ErrorBoundaryHandle? = null

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

        root.requireText("fallback: demo composition boom")
        printTree("composition throw", root)

        failContent = false
        checkNotNull(fallbackScope) { "ErrorBoundary fallback scope was not captured" }.reset()
        testScope.advanceFrame(recomposer)

        root.requireText("recovered content")
        root.requireAbsent("fallback: demo composition boom")
        printTree("reset recovery", root)

        checkNotNull(boundaryHandle) { "LocalErrorBoundary handle was not captured" }
            .throwToBoundary(IllegalStateException("demo forwarded boom"))
        testScope.advanceFrame(recomposer)

        root.requireText("fallback: demo forwarded boom")
        root.requireAbsent("recovered content")
        printTree("throwToBoundary", root)

        println("DEMO OK: $target")
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

private fun printTree(label: String, root: View) {
    println("[$label]")
    print(root.toFmtString())
}

private fun View.requireText(expected: String) {
    val actual = textValues()
    check(expected in actual) { "Expected text '$expected' in $actual\n${toFmtString()}" }
}

private fun View.requireAbsent(unexpected: String) {
    val actual = textValues()
    check(unexpected !in actual) { "Unexpected text '$unexpected' in $actual\n${toFmtString()}" }
}

private fun View.textValues(): List<String> =
    listOfNotNull(text) + children.flatMap { it.textValues() }
