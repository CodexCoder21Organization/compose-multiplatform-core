/*
 * Copyright 2023 The Android Open Source Project
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

@file:Suppress("DEPRECATION")

package androidx.compose.foundation.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFold
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.WheelEvent

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig = JsConfig

private object JsConfig : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        // Browsers report wheel deltas in one of three units depending on the input
        // device and its configuration (see WheelEvent.deltaMode). Compose scrolling
        // operates in pixels, so line- and page-mode deltas have to be normalized first.
        // This mirrors the way Flutter's web engine handles wheel events in
        // `_convertWheelEventToPointerData` (flutter/engine: lib/web_ui/lib/src/engine/
        // pointer_binding.dart).
        return when ((event.nativeEvent as? WheelEvent)?.deltaMode) {
            // Some browsers (most notably Firefox) report wheel deltas in lines rather
            // than pixels. Convert lines to pixels using the browser's default line
            // height so that scrolling respects the user's font settings.
            WheelEvent.DOM_DELTA_LINE -> event.totalScrollDelta * -defaultLineScrollHeight.dp.toPx()

            // Page-mode deltas are expressed in viewport pages, so scale them by the
            // size of the scrollable bounds (same approach as the desktop config).
            WheelEvent.DOM_DELTA_PAGE ->
                Offset(
                    x = event.totalScrollDelta.x * bounds.width,
                    y = event.totalScrollDelta.y * bounds.height,
                ) * -1f

            // Pixel-mode deltas (the default, used by trackpads and high-resolution
            // wheels). The resulting offset is not strictly accurate but provides
            // satisfactory UI behavior; keep in mind that changing this value would
            // also require adjusting the corresponding tests.
            else -> event.totalScrollDelta * -1.dp.toPx()
        }
    }
}

private val PointerEvent.totalScrollDelta
    get() = this.changes.fastFold(Offset.Zero) { acc, c -> acc + c.scrollDelta }

/** Fallback line height (in dp) used when the browser default font size can't be read. */
private const val FallbackLineScrollHeight = 16f

/**
 * The default line height (in dp) used to convert line-mode wheel deltas to pixels.
 *
 * Derived once from the browser's default font size, mirroring Flutter web's
 * `_computeDefaultScrollLineHeight`, so that line-mode scrolling respects the user's font
 * settings. Falls back to [FallbackLineScrollHeight] when the value can't be determined.
 */
private val defaultLineScrollHeight: Float by lazy { computeDefaultLineScrollHeight() }

private fun computeDefaultLineScrollHeight(): Float {
    val body = document.body ?: return FallbackLineScrollHeight
    val probe = document.createElement("div") as HTMLElement
    probe.style.fontSize = "initial"
    probe.style.display = "none"
    body.appendChild(probe)
    val fontSize = window.getComputedStyle(probe).fontSize
    body.removeChild(probe)
    return fontSize.removeSuffix("px").toFloatOrNull() ?: FallbackLineScrollHeight
}


/*
import androidx.compose.ui.input.mouse.MouseScrollOrientation
import androidx.compose.ui.input.mouse.MouseScrollUnit
import androidx.compose.ui.input.mouse.mouseScrollFilter
import androidx.compose.ui.platform.DesktopPlatform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalDesktopPlatform
*/
/*
composed {
    val density = LocalDensity.current
    val desktopPlatform = LocalDesktopPlatform.current
    val config = PlatformScrollConfig(density, desktopPlatform)

    mouseScrollFilter { event, bounds ->
        if (isOrientationMatches(orientation, event.orientation)) {
            val scrollBounds = when (orientation) {
                Orientation.Vertical -> bounds.height
                Orientation.Horizontal -> bounds.width
            }
            onScroll(-config.toScrollOffset(event.delta, scrollBounds))
            true
        } else {
            false
        }
    }
}

fun isOrientationMatches(
    orientation: Orientation,
    mouseOrientation: MouseScrollOrientation
): Boolean {
    return if (mouseOrientation == MouseScrollOrientation.Horizontal) {
        orientation == Orientation.Horizontal
    } else {
        orientation == Orientation.Vertical
    }
}

private class PlatformScrollConfig(
    private val density: Density,
    private val desktopPlatform: DesktopPlatform
) {
    fun toScrollOffset(
        unit: MouseScrollUnit,
        bounds: Int
    ): Float = when (unit) {
        is MouseScrollUnit.Line -> unit.value * platformLineScrollOffset(bounds)

        // TODO(demin): Chrome/Firefox on Windows scroll differently: value * 0.90f * bounds
        // the formula was determined experimentally based on Windows Start behaviour
        is MouseScrollUnit.Page -> unit.value * bounds.toFloat()
    }

    // TODO(demin): Chrome on Windows/Linux uses different scroll strategy
    //  (always the same scroll offset, bounds-independent).
    //  Figure out why and decide if we can use this strategy instead of current one.
    private fun platformLineScrollOffset(bounds: Int): Float {
        return when (desktopPlatform) {
            // TODO(demin): is this formula actually correct? some experimental values don't fit
            //  the formula
            // the formula was determined experimentally based on Ubuntu Nautilus behaviour
            DesktopPlatform.Linux -> sqrt(bounds.toFloat())

            // the formula was determined experimentally based on Windows Start behaviour
            DesktopPlatform.Windows -> bounds / 20f

            // the formula was determined experimentally based on MacOS Finder behaviour
            // MacOS driver will send events with accelerating delta
            DesktopPlatform.MacOS -> with(density) { 10.dp.toPx() }
        }
    }
}
*/
