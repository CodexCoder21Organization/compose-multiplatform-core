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
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlin.math.abs
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

    // Information about the previously processed wheel event, used to disambiguate
    // trackpad gestures from mouse wheel ticks (see [isTrackpadEvent]).
    private var lastWheelEvent: LastWheelEvent? = null
    private var lastWheelEventWasTrackpad = false

    override fun isPreciseWheelScroll(event: PointerEvent): Boolean {
        val wheelEvent = event.nativeEvent as? WheelEvent ?: return false
        val isTrackpad = isTrackpadEvent(wheelEvent)
        lastWheelEvent =
            LastWheelEvent(
                deltaX = wheelEvent.deltaX,
                deltaY = wheelEvent.deltaY,
                timeStamp = wheelEvent.timeStamp.toDouble(),
            )
        lastWheelEventWasTrackpad = isTrackpad
        return isTrackpad
    }

    /**
     * Heuristically detects whether a wheel event comes from a high-resolution input device
     * (a trackpad or a freely rotating, notch-less wheel) rather than a regular stepping
     * mouse wheel. High-resolution input should be applied immediately, while a stepping
     * wheel animates between ticks.
     *
     * This mirrors Flutter web's `_isTrackpadEvent` (flutter/engine:
     * lib/web_ui/lib/src/engine/pointer_binding.dart). It relies on non-standard, deprecated
     * properties (`wheelDeltaX`/`wheelDeltaY`); see that file for reference material.
     */
    private fun isTrackpadEvent(event: WheelEvent): Boolean {
        // Firefox restricts the legacy wheelDelta properties, so they don't provide enough
        // information to reliably disambiguate trackpad events from mouse wheel events.
        if (isFirefox) {
            return false
        }
        val wheelDeltaX = legacyWheelDeltaX(event).takeUnless { it.isNaN() }
        val wheelDeltaY = legacyWheelDeltaY(event).takeUnless { it.isNaN() }
        if (
            isAcceleratedMouseWheelDelta(event.deltaX, wheelDeltaX) ||
                isAcceleratedMouseWheelDelta(event.deltaY, wheelDeltaY)
        ) {
            return false
        }
        // While not in any formal web standard, Blink and WebKit browsers use a delta of 120
        // to represent one mouse wheel turn. If both axes of the delta (or of wheelDelta) are
        // divisible by 120, this event is probably from a mouse.
        val looksLikeMouseTick =
            (event.deltaX % 120.0 == 0.0 && event.deltaY % 120.0 == 0.0) ||
                ((wheelDeltaX ?: 1.0) % 120.0 == 0.0 && (wheelDeltaY ?: 1.0) % 120.0 == 0.0)
        if (looksLikeMouseTick) {
            val last = lastWheelEvent
            val deltaXChange = abs(event.deltaX - (last?.deltaX ?: 0.0))
            val deltaYChange = abs(event.deltaY - (last?.deltaY ?: 0.0))
            // A trackpad event might by chance have a delta of exactly 120, so make sure this
            // event doesn't have a similar delta to the previous one before treating it as a
            // mouse wheel.
            if (
                last == null ||
                    (deltaXChange == 0.0 && deltaYChange == 0.0) ||
                    !(deltaXChange < 20.0 && deltaYChange < 20.0)
            ) {
                // If a large-delta event was preceded within 50ms by a trackpad event, it is
                // likely an unlucky 120-delta trackpad event during rapid movement.
                if (
                    last != null &&
                        event.timeStamp.toDouble() - last.timeStamp < 50.0 &&
                        lastWheelEventWasTrackpad
                ) {
                    return true
                }
                return false
            }
        }
        return true
    }

    private fun isAcceleratedMouseWheelDelta(delta: Double, wheelDelta: Double?): Boolean {
        // On macOS, scrolling with a mouse wheel applies an acceleration curve, so delta
        // values ramp up and are not fixed multiples of 120, but the wheelDelta property
        // keeps its original value: by convention three times the delta with the opposite
        // sign. Allow +-1px error to account for integer truncation.
        if (wheelDelta == null) return false
        return abs(wheelDelta - (-3.0 * delta)) > 1.0
    }
}

private class LastWheelEvent(val deltaX: Double, val deltaY: Double, val timeStamp: Double)

private val PointerEvent.totalScrollDelta
    get() = this.changes.fastFold(Offset.Zero) { acc, c -> acc + c.scrollDelta }

/** Whether the current browser is Firefox, detected once from the user agent. */
private val isFirefox: Boolean by lazy { detectFirefox() }

@OptIn(ExperimentalWasmJsInterop::class)
private fun detectFirefox(): Boolean = js("/firefox/i.test(window.navigator.userAgent)")

// The legacy wheelDeltaX/wheelDeltaY properties are non-standard and may be absent (e.g. in
// Firefox), in which case these helpers return NaN to represent an unavailable value.
@OptIn(ExperimentalWasmJsInterop::class)
private fun legacyWheelDeltaX(event: WheelEvent): Double =
    js("(event.wheelDeltaX == null) ? NaN : event.wheelDeltaX")

@OptIn(ExperimentalWasmJsInterop::class)
private fun legacyWheelDeltaY(event: WheelEvent): Double =
    js("(event.wheelDeltaY == null) ? NaN : event.wheelDeltaY")

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
