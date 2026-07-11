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

import kotlin.test.Test

class ErrorBoundaryDemoTest {
    @Test
    fun runs() {
        assertErrorBoundaryDemoTranscript(
            "js",
            """
            [composition throw]
            <root>
              <text text='fallback: demo composition boom' />
            </root>
            [reset recovery]
            <root>
              <text text='recovered content' />
            </root>
            [throwToBoundary]
            <root>
              <text text='fallback: demo forwarded boom' />
            </root>
            DEMO OK: js
            """
                .trimIndent() + "\n",
        )
    }
}
