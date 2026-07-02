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

package androidx.compose.ui.interaction.swipeback

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.UIKitInstrumentedTest
import androidx.compose.ui.test.findNodeWithTag
import androidx.compose.ui.test.findNodeWithTagOrNull
import androidx.compose.ui.test.runUIKitInstrumentedTest
import androidx.compose.ui.test.utils.hold
import androidx.compose.ui.test.utils.leftCenter
import androidx.compose.ui.test.utils.offsetBy
import androidx.compose.ui.test.utils.rightCenter
import androidx.compose.ui.test.utils.up
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

internal class SwipeBackInHostingViewTest : SwipeBackTest(
    runUIKitInstrumentedTest = { runUIKitInstrumentedTest(useHostingView = true, it) }
)

internal class SwipeBackInHostingViewControllerTest : SwipeBackTest(
    runUIKitInstrumentedTest = { runUIKitInstrumentedTest(useHostingView = false, it) }
)

internal abstract class SwipeBackTest(
    private val runUIKitInstrumentedTest: (UIKitInstrumentedTest.() -> Unit) -> Unit
) {
    @Test
    fun testEdgeBackSwipeInFullscreenContainer() = runUIKitInstrumentedTest {
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        setContent {
            BackGestureContent(
                tag = ROOT_SURFACE,
                onTransitionStateChanged = { transitionState = it },
                onBackCompletedCountChanged = { backCompletedCount = it }
            )
        }

        waitUntilReady(ROOT_SURFACE) { backCompletedCount == 0 }

        val backSwipe = swipeRightFromEdge().hold()
        waitUntil("Back swipe should start in fullscreen container") {
            transitionState is InProgress
        }

        assertEquals(
            expected = NavigationEvent.EDGE_LEFT,
            actual = (transitionState as InProgress).latestEvent.swipeEdge
        )
        assertEquals(
            expected = 0,
            actual = backCompletedCount,
            message = "Back gesture should not complete before release"
        )

        backSwipe.up()

        waitUntil("Back swipe should complete in fullscreen container") {
            backCompletedCount == 1
        }
    }

    @Test
    fun testEdgeBackSwipeInNonFullscreenContainer() = runUIKitInstrumentedTest {
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        setupWindow {
            val composeViewController = createViewControllerHostingCompose {
                BackGestureContent(
                    tag = ROOT_SURFACE,
                    onTransitionStateChanged = { transitionState = it },
                    onBackCompletedCountChanged = { backCompletedCount = it }
                )
            }

            UIViewController().also { hostViewController ->
                hostViewController.addChildViewController(composeViewController)
                hostViewController.view.addSubview(composeViewController.view)
                composeViewController.view.setFrame(CGRectMake(80.0, 160.0, 220.0, 260.0))
                composeViewController.didMoveToParentViewController(hostViewController)
            }
        }

        waitUntilReady(ROOT_SURFACE) { backCompletedCount == 0 }

        val backSwipe = swipeRightFromEdge().hold()
        waitUntil("Back swipe should start in non-fullscreen container") {
            transitionState is InProgress
        }

        assertEquals(
            expected = NavigationEvent.EDGE_LEFT,
            actual = (transitionState as InProgress).latestEvent.swipeEdge
        )

        backSwipe.up()

        waitUntil("Back swipe should complete in non-fullscreen container") {
            backCompletedCount == 1
        }
    }

    @Test
    fun testEdgeBackSwipeInModalContainer() = runUIKitInstrumentedTest {
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        val rootViewController = UIViewController()
        setupWindow { rootViewController }

        waitUntil("Root view controller should be attached before presenting modal") {
            rootViewController.view.window != null
        }

        var presented = false
        rootViewController.presentViewController(
            viewControllerToPresent = createViewControllerHostingCompose {
                BackGestureContent(
                    tag = ROOT_SURFACE,
                    onTransitionStateChanged = { transitionState = it },
                    onBackCompletedCountChanged = { backCompletedCount = it }
                )
            }.apply {
                modalPresentationStyle = UIModalPresentationFullScreen
            },
            animated = false
        ) {
            presented = true
        }

        waitUntil("Modal container should be presented") { presented }
        waitUntilReady(ROOT_SURFACE) { backCompletedCount == 0 }

        val backSwipe = swipeRightFromEdge().hold()
        waitUntil("Back swipe should start in modal container") {
            transitionState is InProgress
        }

        assertEquals(
            expected = NavigationEvent.EDGE_LEFT,
            actual = (transitionState as InProgress).latestEvent.swipeEdge
        )

        backSwipe.up()

        waitUntil("Back swipe should complete in modal container") {
            backCompletedCount == 1
        }
    }

    @Test
    fun testRtlBackSwipeFromRightEdge() = runUIKitInstrumentedTest {
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        setupWindow {
            createViewControllerHostingCompose {
                BackGestureContent(
                    tag = ROOT_SURFACE,
                    onTransitionStateChanged = { transitionState = it },
                    onBackCompletedCountChanged = { backCompletedCount = it }
                )
            }.apply {
                view.semanticContentAttribute = UISemanticContentAttributeForceRightToLeft
            }
        }

        waitUntilReady(ROOT_SURFACE) { backCompletedCount == 0 }

        val backSwipe = swipeLeftFromEdge().hold()
        waitUntil("RTL back swipe should start from the right edge") {
            transitionState is InProgress
        }

        assertEquals(
            expected = NavigationEvent.EDGE_RIGHT,
            actual = (transitionState as InProgress).latestEvent.swipeEdge
        )

        backSwipe.up()

        waitUntil("RTL back swipe should complete after release") {
            backCompletedCount == 1
        }
    }

    @Test
    fun testEdgeBackSwipeDoesNotDispatchHorizontalDragToCompose() = runUIKitInstrumentedTest {
        var dragDistance = Float.NaN
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        setContent {
            DragTestContent(
                onDragDistanceChanged = { dragDistance = it },
                onTransitionStateChanged = { transitionState = it },
                onBackCompletedCountChanged = { backCompletedCount = it }
            )
        }

        waitUntil("drag surface should be ready") {
            findNodeWithTagOrNull(DRAG_SURFACE) != null &&
                !dragDistance.isNaN() &&
                backCompletedCount == 0
        }

        val backSwipe = swipeRightFromEdge().hold()

        waitUntil("back swipe should be in progress") {
            transitionState is InProgress
        }

        assertEquals(
            expected = 0f,
            actual = dragDistance,
            absoluteTolerance = 0.01f,
            message = "Edge back swipe should not dispatch horizontal drag deltas to Compose"
        )
        assertEquals(
            expected = 0,
            actual = backCompletedCount,
            message = "Back gesture should not complete before release"
        )

        backSwipe.up()

        waitUntil("back swipe should complete after release") {
            backCompletedCount == 1
        }
    }

    @Test
    fun testInnerSwipeDispatchesHorizontalDragWithoutStartingBack() = runUIKitInstrumentedTest {
        var dragDistance = Float.NaN
        var transitionState: NavigationEventTransitionState = NavigationEventTransitionState.Idle
        var backCompletedCount = -1

        setContent {
            DragTestContent(
                onDragDistanceChanged = { dragDistance = it },
                onTransitionStateChanged = { transitionState = it },
                onBackCompletedCountChanged = { backCompletedCount = it }
            )
        }

        waitUntil("drag surface should be ready") {
            findNodeWithTagOrNull(DRAG_SURFACE) != null &&
                !dragDistance.isNaN() &&
                backCompletedCount == 0
        }

        findNodeWithTag(DRAG_SURFACE).swipe(
            fromPosition = { leftCenter().offsetBy(dx = 16.dp) },
            toPosition = { rightCenter().offsetBy(dx = (-16).dp) }
        )

        waitUntil("inner swipe should dispatch drag deltas to Compose") {
            dragDistance > 0f
        }
        assertFalse(
            transitionState is InProgress,
            "Inner swipe should not start back navigation"
        )
        assertEquals(
            expected = 0,
            actual = backCompletedCount,
            message = "Inner swipe should not complete back navigation"
        )
    }
}

@Composable
private fun DragTestContent(
    onDragDistanceChanged: (Float) -> Unit,
    onTransitionStateChanged: (NavigationEventTransitionState) -> Unit,
    onBackCompletedCountChanged: (Int) -> Unit,
) {
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var backCompletedCount by remember { mutableIntStateOf(0) }
    val navigationEventState = rememberNavigationEventState<NavigationEventInfo>(
        currentInfo = NavigationEventInfo.None,
        backInfo = listOf<NavigationEventInfo>(NavigationEventInfo.None)
    )

    onDragDistanceChanged(dragDistance)
    onTransitionStateChanged(navigationEventState.transitionState)
    onBackCompletedCountChanged(backCompletedCount)

    NavigationBackHandler(
        state = navigationEventState,
        onBackCompleted = {
            backCompletedCount += 1
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(DRAG_SURFACE)
            .draggable(
                state = rememberDraggableState { delta ->
                    dragDistance += delta
                },
                orientation = Orientation.Horizontal,
            )
    )
}

@Composable
private fun BackGestureContent(
    tag: String,
    onTransitionStateChanged: (NavigationEventTransitionState) -> Unit,
    onBackCompletedCountChanged: (Int) -> Unit,
) {
    var backCompletedCount by remember { mutableIntStateOf(0) }
    val navigationEventState = rememberNavigationEventState<NavigationEventInfo>(
        currentInfo = NavigationEventInfo.None,
        backInfo = listOf(NavigationEventInfo.None)
    )

    onTransitionStateChanged(navigationEventState.transitionState)
    onBackCompletedCountChanged(backCompletedCount)

    NavigationBackHandler(
        state = navigationEventState,
        onBackCompleted = {
            backCompletedCount += 1
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag)
    )
}

private fun UIKitInstrumentedTest.waitUntilReady(
    tag: String,
    otherConditions: () -> Boolean,
) {
    waitUntil("$tag should be ready") {
        findNodeWithTagOrNull(tag) != null && otherConditions()
    }
}

private const val ROOT_SURFACE = "rootSurface"
private const val DRAG_SURFACE = "dragSurface"
