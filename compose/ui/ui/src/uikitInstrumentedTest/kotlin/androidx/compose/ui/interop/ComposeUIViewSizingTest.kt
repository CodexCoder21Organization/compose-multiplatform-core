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

package androidx.compose.ui.interop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.scene.ComposeHostingView
import androidx.compose.ui.test.UIKitInstrumentedTest
import androidx.compose.ui.test.runUIKitInstrumentedTestInHostingView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dpSize
import androidx.compose.ui.unit.size
import androidx.compose.ui.unit.toDpRect
import kotlin.test.Test
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIViewNoIntrinsicMetric

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
class ComposeUIViewSizingTest {
    private val contentSize = DpSize(200.dp, 100.dp)

    @Test
    fun testBothAxesBounded() = testComposeUIViewSizing(
        content = { Box(Modifier.size(contentSize)) },
        proposal = CGSizeMake(150.0, 60.0),
        expected = DpSize(150.dp, 60.dp)
    )

    @Test
    fun testBothAxesBoundedProposedHeightLargerThanContentSize() = testComposeUIViewSizing(
        content = { Box(Modifier.size(contentSize)) },
        proposal = CGSizeMake(150.0, (contentSize.height + 10.dp).value.toDouble()),
        expected = DpSize(150.dp, contentSize.height)
    )

    @Test
    fun testBothAxesBoundedProposedWidthLargerThanContentSize() = testComposeUIViewSizing(
        content = { Box(Modifier.size(contentSize)) },
        proposal = CGSizeMake((contentSize.width + 10.dp).value.toDouble(), 60.0),
        expected = DpSize(contentSize.width, 60.dp)
    )

    @Test
    fun testBothAxesBoundedLargerThanContentSize() = testComposeUIViewSizing(
        content = { Box(Modifier.size(contentSize)) },
        proposal = CGSizeMake(
            (contentSize.width + 10.dp).value.toDouble(),
            (contentSize.height + 10.dp).value.toDouble()
        ),
        expected = DpSize(contentSize.width, contentSize.height)
    )

    @Test
    fun testBoundedWidthAndUnboundedHeight() = testComposeUIViewSizing(
            content = { Box(Modifier.size(contentSize)) },
            proposal = CGSizeMake(150.0, UIViewNoIntrinsicMetric),
            expected = DpSize(150.dp, 100.dp)
        )

    @Test
    fun testUnboundedWidthAndBoundedHeight() = testComposeUIViewSizing(
            content = { Box(Modifier.size(contentSize)) },
            proposal = CGSizeMake(UIViewNoIntrinsicMetric, 60.0),
            expected = DpSize(200.dp, 60.dp)
        )

    @Test
    fun testBothAxesUnbounded() = testComposeUIViewSizing(
        content = { Box(Modifier.size(contentSize)) },
        proposal = CGSizeMake(UIViewNoIntrinsicMetric, UIViewNoIntrinsicMetric),
        expected = DpSize(200.dp, 100.dp)
    )

    @Test
    fun testFixedSizeProposalAndComposeContentChanges() {
        val expanded = mutableStateOf(false)
        val sizeProposal = CGSizeMake(150.0, UIViewNoIntrinsicMetric)
        val collapsedExpected = DpSize(150.dp, 60.dp)
        val expandedExpected = DpSize(150.dp, 120.dp)

        runComposeUIViewSizingTest(
            content = {
                val height = if (expanded.value) 120.dp else 60.dp
                Box(Modifier.size(width = 200.dp, height = height))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(sizeProposal)

            waitForExpectedSize(context, collapsedExpected, "initial fixed-width proposal state")

            expanded.value = true
            waitForIdle()

            waitForExpectedSize(context, expandedExpected, "expanded fixed-width proposal state")
        }
    }

    @Test
    fun testFixedHeightProposalAndComposeContentWidthChanges() {
        val expanded = mutableStateOf(false)
        val sizeProposal = CGSizeMake(UIViewNoIntrinsicMetric, 80.0)
        val collapsedExpected = DpSize(100.dp, 80.dp)
        val expandedExpected = DpSize(180.dp, 80.dp)

        runComposeUIViewSizingTest(
            content = {
                val width = if (expanded.value) 180.dp else 100.dp
                Box(Modifier.size(width = width, height = 140.dp))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(sizeProposal)
            waitForExpectedSize(context, collapsedExpected, "initial fixed-height proposal state")

            expanded.value = true
            waitForIdle()

            waitForExpectedSize(context, expandedExpected, "expanded fixed-height proposal state")
        }
    }

    @Test
    fun testUnboundedProposalAndComposeContentBothAxesChange() {
        val expanded = mutableStateOf(false)
        val sizeProposal = CGSizeMake(UIViewNoIntrinsicMetric, UIViewNoIntrinsicMetric)
        val collapsedExpected = DpSize(90.dp, 40.dp)
        val expandedExpected = DpSize(170.dp, 130.dp)

        runComposeUIViewSizingTest(
            content = {
                val width = if (expanded.value) 170.dp else 90.dp
                val height = if (expanded.value) 130.dp else 40.dp
                Box(Modifier.size(width = width, height = height))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(sizeProposal)
            waitForExpectedSize(context, collapsedExpected, "initial fully-unbounded proposal state")

            expanded.value = true
            waitForIdle()

            waitForExpectedSize(context, expandedExpected, "expanded fully-unbounded proposal state")
        }
    }

    @Test
    fun testBoundedProposalAndComposeContentChangesFromSmallerToLargerThanProposal() {
        val expanded = mutableStateOf(false)
        val sizeProposal = CGSizeMake(140.0, 75.0)
        val collapsedExpected = DpSize(80.dp, 40.dp)
        val expandedExpected = DpSize(140.dp, 75.dp)

        runComposeUIViewSizingTest(
            content = {
                val width = if (expanded.value) 220.dp else 80.dp
                val height = if (expanded.value) 180.dp else 40.dp
                Box(Modifier.size(width = width, height = height))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(sizeProposal)

            waitForExpectedSize(context, collapsedExpected, "initial bounded proposal state")

            expanded.value = true
            waitForIdle()

            waitForExpectedSize(context, expandedExpected, "expanded bounded proposal state")
        }
    }

    @Test
    fun testBoundedProposalAndComposeContentChangesFromLargerToSmallerThanProposal() {
        val expanded = mutableStateOf(true)
        val sizeProposal = CGSizeMake(140.0, 75.0)
        val collapsedExpected = DpSize(80.dp, 40.dp)
        val expandedExpected = DpSize(140.dp, 75.dp)

        runComposeUIViewSizingTest(
            content = {
                val width = if (expanded.value) 220.dp else 80.dp
                val height = if (expanded.value) 180.dp else 40.dp
                Box(Modifier.size(width = width, height = height))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(sizeProposal)

            waitForExpectedSize(context, expandedExpected, "initial bounded proposal state")

            expanded.value = false
            println("Change expanded to false")
            waitForIdle()

            waitForExpectedSize(context, collapsedExpected, "expanded bounded proposal state")
        }
    }

    @Test
    fun testComposeContentAndProposalChangeSequence() {
        val expanded = mutableStateOf(false)
        val firstProposal = CGSizeMake(150.0, UIViewNoIntrinsicMetric)
        val secondProposal = CGSizeMake(UIViewNoIntrinsicMetric, 90.0)
        val collapsedWithFirstProposal = DpSize(150.dp, 60.dp)
        val expandedWithFirstProposal = DpSize(150.dp, 120.dp)
        val expandedWithSecondProposal = DpSize(200.dp, 90.dp)

        runComposeUIViewSizingTest(
            content = {
                val height = if (expanded.value) 120.dp else 60.dp
                Box(Modifier.size(width = 200.dp, height = height))
            }
        ) { context ->
            context.proposeSwiftUIConstraints(firstProposal)
            waitForExpectedSize(context, collapsedWithFirstProposal, "collapsed with first proposal")

            expanded.value = true
            waitForIdle()
            waitForExpectedSize(context, expandedWithFirstProposal, "expanded with first proposal")

            context.proposeSwiftUIConstraints(secondProposal)
            waitForExpectedSize(context, expandedWithSecondProposal, "expanded with second proposal")
        }
    }

    private fun runComposeUIViewSizingTest(
        content: @Composable () -> Unit,
        runTest: UIKitInstrumentedTest.(SwiftUISimulationContext) -> Unit
    ) = runUIKitInstrumentedTestInHostingView {
        var composeSceneSize: DpSize? = null

        setContent(
            waitForIdle = false
        ) {
            Column(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    composeSceneSize = coordinates.boundsInWindow().toDpRect(density).size
                },
                content = { content() }
            )
        }

        this.runTest(
            SwiftUISimulationContext(
                hostingView!!,
                { composeSceneSize }
            )
        )
    }

    private class SwiftUISimulationContext(
        val composeView: ComposeHostingView,
        private val getComposeContentSize: () -> DpSize?
    ) {
        val composeContentSize: DpSize? get() = getComposeContentSize()
        val composeUIViewSize: DpSize get() = composeView.frame.dpSize()

        private var lastSwiftUIConstraints: CValue<CGSize>? = null

        init {
            composeView.onIntrinsicContentSizeInvalidated = {
                if (lastSwiftUIConstraints != null) {
                    proposeSwiftUIConstraints(lastSwiftUIConstraints!!)
                }
            }
        }

        fun proposeSwiftUIConstraints(size: CValue<CGSize>) {
            lastSwiftUIConstraints = size
            val sizeThatFits = composeView.sizeThatFits(size)
            composeView.applyFrame(sizeThatFits)
        }

        private fun ComposeHostingView.applyFrame(size: CValue<CGSize>) {
            size.useContents {
                setFrame(CGRectMake(0.0, 0.0, width, height))
            }
            layoutIfNeeded()
        }
    }

    private fun testComposeUIViewSizing(
        proposal: CValue<CGSize>,
        expected: DpSize,
        content: @Composable () -> Unit
    ) = runComposeUIViewSizingTest(content) { context ->
        context.proposeSwiftUIConstraints(proposal)

        waitForIdle()

        waitForExpectedSize(context, expected, "proposal")
    }

    private fun UIKitInstrumentedTest.waitForExpectedSize(
        context: SwiftUISimulationContext,
        expected: DpSize,
        phase: String
    ) {
        try {
            waitUntil(
                conditionDescription = "Waiting for expected size ($phase): $expected"
            ) {
                context.composeContentSize == expected &&
                    context.composeUIViewSize == expected
            }
        } catch (e: Throwable) {
            println("composeContentSize ${context.composeContentSize}, composeUIViewSize ${context.composeUIViewSize}, expected $expected")
            throw e
        }
    }
}
