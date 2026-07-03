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

import androidx.compose.runtime.mock.Text
import androidx.compose.runtime.mock.compositionTest
import androidx.compose.runtime.mock.validate
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * JVM-specific ErrorBoundary diagnostics coverage: composition stack trace capture depends on the
 * platform's diagnostic stack trace support, which is exercised here on the JVM.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class ErrorBoundaryDiagnosticsTests {

    @Test
    fun diagnosticStackTraces_populateCompositionErrorInfo() = compositionTest {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        try {
            val reported = mutableListOf<CompositionErrorInfo>()
            compose {
                ErrorBoundary(
                    fallback = { Text("fallback") },
                    onError = { _, info -> reported.add(info) },
                ) {
                    error("boom")
                }
            }

            validate { Text("fallback") }
            assertEquals(1, reported.size)
            val trace = reported.single().composeStackTrace
            assertNotNull(
                trace,
                "with diagnostic stack traces enabled, CompositionErrorInfo must carry the " +
                    "composition stack",
            )
        } finally {
            Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        }
    }
}
