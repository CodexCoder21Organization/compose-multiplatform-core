/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.graphics.layer

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.SkiaBackedCanvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaColorFilter
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.materializeSkiaPath
import androidx.compose.ui.graphics.platform.PlatformGraphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toSkia
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import org.jetbrains.skia.Paint as SkPaint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Rect as SkRect
import org.jetbrains.skiko.node.RenderNode

@OptIn(InternalComposeUiApi::class)
internal class SkikoGraphicsLayer(
    renderNode: RenderNode,
) : PlatformGraphicsLayer {
    private var renderNode: RenderNode? = renderNode
    private val pictureDrawScope = CanvasDrawScope()

    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto
        set(value) {
            field = value
            updateLayerProperties()
        }

    override var pivotOffset: Offset = Offset.Unspecified
        set(value) {
            field = value
            renderNode?.pivot = Point(value.x, value.y)
        }

    override var alpha: Float = 1f
        set(value) {
            field = value
            renderNode?.alpha = value
            updateLayerProperties()
        }

    override var scaleX: Float = 1f
        set(value) {
            field = value
            renderNode?.scaleX = value
        }

    override var scaleY: Float = 1f
        set(value) {
            field = value
            renderNode?.scaleY = value
        }

    override var translationX: Float = 0f
        set(value) {
            field = value
            renderNode?.translationX = value
        }

    override var translationY: Float = 0f
        set(value) {
            field = value
            renderNode?.translationY = value
        }

    override var shadowElevation: Float = 0f
        set(value) {
            field = value
            renderNode?.shadowElevation = value
        }

    override var ambientShadowColor: Color = Color.Black
        set(value) {
            field = value
            renderNode?.ambientShadowColor = value.toArgb()
        }

    override var spotShadowColor: Color = Color.Black
        set(value) {
            field = value
            renderNode?.spotShadowColor = value.toArgb()
        }

    override var blendMode: BlendMode = BlendMode.SrcOver
        set(value) {
            field = value
            updateLayerProperties()
        }

    override var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            updateLayerProperties()
        }

    override var rotationX: Float = 0f
        set(value) {
            field = value
            renderNode?.rotationX = value
        }

    override var rotationY: Float = 0f
        set(value) {
            field = value
            renderNode?.rotationY = value
        }

    override var rotationZ: Float = 0f
        set(value) {
            field = value
            renderNode?.rotationZ = value
        }

    override var cameraDistance: Float = DefaultCameraDistance
        set(value) {
            field = value
            renderNode?.cameraDistance = value
        }

    override var renderEffect: RenderEffect? = null
        set(value) {
            field = value
            updateLayerProperties()
        }

    override fun setBounds(topLeft: IntOffset, size: IntSize) {
        renderNode?.bounds = SkRect.makeXYWH(
            topLeft.x.toFloat(),
            topLeft.y.toFloat(),
            size.width.toFloat(),
            size.height.toFloat()
        )
    }

    override fun setOutline(outline: Outline?, clip: Boolean) {
        val renderNode = renderNode ?: return
        if (outline == null) {
            renderNode.clip = false
            renderNode.setClipPath(null)
        } else {
            renderNode.clip = clip
            when (outline) {
                is Outline.Rectangle -> renderNode.setClipRect(
                    outline.rect.left,
                    outline.rect.top,
                    outline.rect.right,
                    outline.rect.bottom,
                    antiAlias = true
                )
                is Outline.Rounded -> renderNode.setClipRRect(
                    outline.roundRect.left,
                    outline.roundRect.top,
                    outline.roundRect.right,
                    outline.roundRect.bottom,
                    floatArrayOf(
                        outline.roundRect.topLeftCornerRadius.x,
                        outline.roundRect.topLeftCornerRadius.y,
                        outline.roundRect.topRightCornerRadius.x,
                        outline.roundRect.topRightCornerRadius.y,
                        outline.roundRect.bottomRightCornerRadius.x,
                        outline.roundRect.bottomRightCornerRadius.y,
                        outline.roundRect.bottomLeftCornerRadius.x,
                        outline.roundRect.bottomLeftCornerRadius.y
                    ),
                    antiAlias = true
                )
                is Outline.Generic -> renderNode.setClipPath(
                    outline.path.materializeSkiaPath(),
                    antiAlias = true
                )
            }
        }
    }

    override fun record(
        density: Density,
        layoutDirection: LayoutDirection,
        layer: GraphicsLayer,
        block: DrawScope.() -> Unit,
    ) {
        val renderNode = renderNode ?: return
        val recordingCanvas = renderNode.beginRecording()
        try {
            val composeCanvas = recordingCanvas.asComposeCanvas() as SkiaBackedCanvas
            composeCanvas.alphaMultiplier =
                if (compositingStrategy == CompositingStrategy.ModulateAlpha) {
                    alpha
                } else {
                    1.0f
                }
            pictureDrawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = composeCanvas,
                size = layer.size.toSize(),
                graphicsLayer = layer,
                block = block,
            )
        } finally {
            renderNode.endRecording()
        }
    }

    override fun draw(canvas: Canvas) {
        renderNode?.drawInto(canvas.skiaCanvas)
    }

    override fun discardDisplayList() {
        renderNode?.close()
        renderNode = null
    }

    override fun setOutsets(left: Int, top: Int, right: Int, bottom: Int) {
        // TODO: https://youtrack.jetbrains.com/issue/CMP-10054/Implement-GraphicsLayer.setOutsets-method
    }

    private fun updateLayerProperties() {
        renderNode?.layerPaint = if (requiresLayer()) {
            SkPaint().also {
                it.setAlphaf(alpha)
                it.imageFilter = renderEffect?.skiaImageFilter
                it.colorFilter = colorFilter?.asSkiaColorFilter()
                it.blendMode = blendMode.toSkia()
            }
        } else {
            null
        }
    }

    private fun requiresLayer(): Boolean {
        val alphaNeedsLayer = alpha < 1f && compositingStrategy != CompositingStrategy.ModulateAlpha
        val hasColorFilter = colorFilter != null
        val hasBlendMode = blendMode != BlendMode.SrcOver
        val hasRenderEffect = renderEffect != null
        val offscreenBufferRequested = compositingStrategy == CompositingStrategy.Offscreen
        return alphaNeedsLayer || hasColorFilter || hasBlendMode || hasRenderEffect ||
            offscreenBufferRequested
    }
}
