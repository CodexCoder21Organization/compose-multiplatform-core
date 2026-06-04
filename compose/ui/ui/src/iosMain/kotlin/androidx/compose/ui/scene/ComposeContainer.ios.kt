/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.ui.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.navigationevent.UIKitNavigationEventInput
import androidx.compose.ui.node.OnLayoutCompletedListenerHandle
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.MotionDurationScaleImpl
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformWindowContext
import androidx.compose.ui.uikit.ComposeContainerConfiguration
import androidx.compose.ui.uikit.InterfaceOrientation
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.uikit.PlistSanityCheck
import androidx.compose.ui.uikit.density
import androidx.compose.ui.uikit.embedSubview
import androidx.compose.ui.uikit.utils.CMPKeyValueObserver
import androidx.compose.ui.uikit.utils.CMPUIWindowSceneUtils
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.fastForEachReversed
import androidx.compose.ui.viewinterop.UIKitInteropAction
import androidx.compose.ui.viewinterop.UIKitInteropTransaction
import androidx.compose.ui.window.ComposeContainerLifecycleDelegate
import androidx.compose.ui.window.ComposeContainerView
import androidx.compose.ui.window.FocusedViewsList
import androidx.compose.ui.window.MetalView
import androidx.compose.ui.window.SceneActiveStateListener
import androidx.lifecycle.enableSavedStateHandles
import androidx.savedstate.SavedState
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import platform.CoreGraphics.CGFloat
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIResponder
import platform.UIKit.UIUserInterfaceLayoutDirection
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIViewNoIntrinsicMetric
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * The class represents a common part of Compose integration for all iOS containers.
 */
internal class ComposeContainer(
    private val configuration: ComposeContainerConfiguration,
    private val content: @Composable () -> Unit,
    private val coroutineContext: CoroutineContext,
    private val lifecycleDelegate: ComposeContainerLifecycleDelegate
) {

    val view = ComposeContainerView(
        transparentForTouches = false,
        useOpaqueConfiguration = configuration.opaque,
    ).apply {
        onSizeThatFits = { composeSceneSizeSynchronizer.onSizeThatFitsRequest(it) }
        onIntrinsicContentSize = { composeSceneSizeSynchronizer.preferredCGSize }
    }

    private var mediator: ComposeSceneMediator? = null
    private val windowContext = PlatformWindowContext()
    private var layersHolder: ComposeLayersHolder? = null
    private val layoutDirection get() = getApplicationLayoutDirection()
    private val motionDurationScale = MotionDurationScaleImpl()
    private var activeStateListener: SceneActiveStateListener? = null
    private var sceneJob: Job = Job().also {
        // The initial state of the container considered as "not active".
        // The `initializeComposeScene` must be called to set the active `sceneJob`.
        it.cancel()
    }
    private var savedState: SavedState? = null
    private var mediatorComponentsOwner: DefaultArchitectureComponentsOwner? = null
    private val architectureComponentsOwner: DefaultArchitectureComponentsOwner
        get() = mediatorComponentsOwner
            ?: error("ArchitectureComponentsOwner is not initialized yet.")

    private val interfaceOrientationObserver = SceneGeometryObserver {
        updateInterfaceOrientationState()
    }
    private val navigationEventInput = UIKitNavigationEventInput(
        density = view.density,
        getTopLeftOffsetInWindow = { IntOffset.Zero }, //full screen
        endEdgePanGestureBehavior = configuration.endEdgePanGestureBehavior
    )

    private val composeSceneSizeSynchronizer: ComposeSceneSizeSynchronizer =
        ComposeSceneSizeSynchronizer(
            view = view,
            composeSceneSize = { mediator?.measureSceneSize(it) },
            invalidateComposeSceneContainerSize = view::invalidateIntrinsicContentSize
        )

    val hasInteropViews: Boolean get() = mediator?.hasInteropViews ?: false

    /*
     * Initial value is arbitrarily chosen to avoid propagating invalid value logic
     * It's never the case in the real usage scenario to reflect that in type system
     */
    private val interfaceOrientationState: MutableState<InterfaceOrientation> = mutableStateOf(
        InterfaceOrientation.Portrait
    )
    private val systemThemeState: MutableState<SystemTheme> = mutableStateOf(SystemTheme.Unknown)

    private val focusedViewsList = FocusedViewsList()

    private var onLayoutCompletedListenerHandle: OnLayoutCompletedListenerHandle? = null

    init {
        if (configuration.enforceStrictPlistSanityCheck) {
            PlistSanityCheck.performIfNeeded()
        }
        lifecycleDelegate.runOnDeinit {
            windowContext.dispose()
        }
    }

    fun nestedCoroutineScope(
        addedContext: CoroutineContext = EmptyCoroutineContext
    ): CoroutineScope {
        return CoroutineScope(coroutineContext + addedContext + Job(parent = sceneJob))
    }

    fun prepareAndGetSizeTransitionAnimation(withProgress: suspend ((Float) -> Unit) -> Unit): suspend () -> Unit {
        return mediator?.prepareAndGetSizeTransitionAnimation(withProgress) ?: {}
    }

    fun hasInvalidations(): Boolean {
        return mediator?.hasInvalidations == true || layersHolder?.layersViewController?.hasInvalidations == true
    }

    private val currentInterfaceOrientation: InterfaceOrientation?
        get() {
            return InterfaceOrientation.getByRawValue(
                CMPUIWindowSceneUtils.interfaceOrientationForWindowScene(windowScene)
            )
        }

    private fun onLayoutSubviews() {
        windowContext.updateWindowContainerSize()
    }

    private fun onDidMoveToWindow(window: UIWindow?) {
        navigationEventInput.onDidMoveToWindow(window, view)
        interfaceOrientationObserver.windowScene = window?.windowScene

        window ?: return

        updateInterfaceOrientationState()

        layersHolder?.layersViewController?.containerWindow = view.window
        windowContext.window = window
        updateMotionSpeed()
        lifecycleDelegate.windowScene = window.windowScene
    }

    fun updateInterfaceOrientationState() {
        currentInterfaceOrientation?.let {
            interfaceOrientationState.value = it
        }
    }

    fun sceneDidAppear() {
        mediator?.sceneDidAppear()

        // Because the container view can change during the modal transition animation,
        // the gesture handlers and layers view are added back when the animation ends.
        navigationEventInput.onDidMoveToWindow(view.window, view)
    }

    fun sceneWillDisappear() {
        mediator?.sceneWillDisappear()

        navigationEventInput.onDidMoveToWindow(null, view)
    }

    fun updateUserInterfaceStyle(style: UIUserInterfaceStyle) {
        systemThemeState.value = style.asComposeSystemTheme()
    }

    fun initializeComposeScene() {
        sceneJob = Job()
        val sceneCoroutineContext = coroutineContext + motionDurationScale + sceneJob
        val metalView = MetalView(
            retrieveInteropTransaction = {
                mediator?.retrieveInteropTransaction() ?: object : UIKitInteropTransaction {
                    override val actions = emptyList<UIKitInteropAction>()
                    override val isInteropActive = false
                }
            },
            useSeparateRenderThreadWhenPossible = configuration.parallelRendering,
            render = { canvas, nanoTime ->
                mediator?.render(canvas.asComposeCanvas(), nanoTime)
            }
        )
        metalView.canBeOpaque = configuration.opaque
        val holder = ComposeLayersHolder(
            useSeparateRenderThreadWhenPossible = configuration.parallelRendering,
            coroutineContext = sceneCoroutineContext,
            view = view
        ).also {
            layersHolder = it
        }

        mediatorComponentsOwner = DefaultArchitectureComponentsOwner(savedState)
        architectureComponentsOwner.enableSavedStateHandles()
        lifecycleDelegate.onLifecycleStateUpdated = architectureComponentsOwner::setLifecycleState

        mediator = ComposeSceneMediator(
            onFocusBehavior = configuration.onFocusBehavior,
            isClearFocusOnMouseDownEnabled = configuration.isClearFocusOnMouseDownEnabled,
            focusedViewsList = focusedViewsList,
            windowContext = windowContext,
            architectureComponentsOwner = architectureComponentsOwner,
            coroutineContext = sceneCoroutineContext,
            redrawer = metalView.redrawer,
            composeSceneFactory = { invalidate, context, frameRecomposer ->
                createComposeScene(invalidate, context, holder, frameRecomposer)
            },
            navigationEventInput = navigationEventInput,
            interfaceOrientationState = interfaceOrientationState,
        ).also { mediator ->
            view.embedSubview(mediator.backgroundView)
            view.updateMetalView(
                metalView = metalView,
                onDidMoveToWindow = ::onDidMoveToWindow,
                onLayoutSubviews = ::onLayoutSubviews
            )
            view.embedSubview(mediator.overlayView)

            mediator.setContent {
                ProvideContainerCompositionLocals(content)
            }
        }

        onLayoutCompletedListenerHandle?.unregister()
        onLayoutCompletedListenerHandle = composeSceneSizeSynchronizer?.let {
            mediator?.registerOnLayoutCompletedListener(it::onComposeLayoutCompleted)
        }

        activeStateListener = SceneActiveStateListener(
            getScene = ::windowScene
        ) { isSceneActive ->
            if (isSceneActive) {
                updateMotionSpeed()
            }
        }

        interfaceOrientationObserver.isObservingEnabled = true

        architectureComponentsOwner.navigationEventDispatcher.addInput(navigationEventInput)
        lifecycleDelegate.windowScene = windowScene
        navigationEventInput.onDidMoveToWindow(view.window, view)
        onFocusConditionsChanged()
    }

    fun disposeComposeScene() {
        sceneJob.cancel()
        // Store the current state in the local savedState property. It is used to
        // provide the saved state to the next Compose scene when the container re-enters
        // the window hierarchy.
        savedState = architectureComponentsOwner.saveState()
        lifecycleDelegate.onLifecycleStateUpdated = null

        view.updateMetalView(metalView = null)
        navigationEventInput.onDidMoveToWindow(null, view)
        architectureComponentsOwner.navigationEventDispatcher.removeInput(navigationEventInput)

        onLayoutCompletedListenerHandle?.unregister()
        onLayoutCompletedListenerHandle = null

        mediator = null

        activeStateListener?.dispose()
        activeStateListener = null

        layersHolder = null

        interfaceOrientationObserver.isObservingEnabled = false
    }

    private fun createComposeSceneContext(
        platformContext: PlatformContext,
        layersHolder: ComposeLayersHolder,
        frameRecomposer: FrameRecomposer,
    ): ComposeSceneContext {
        return object : ComposeSceneContext {
            override val platformContext: PlatformContext = platformContext

            override fun createLayer(
                density: Density,
                layoutDirection: LayoutDirection,
                focusable: Boolean,
                consumePointerInputOutside: Boolean,
            ): ComposeSceneLayer {
                val layer = UIKitComposeSceneLayer(
                    onClosed = {
                        layersHolder.getLayersViewController().detach(it)
                        onFocusConditionsChanged()
                    },
                    createComposeSceneContext = {
                        createComposeSceneContext(it, layersHolder, frameRecomposer)
                    },
                    hostCompositionLocals = { ProvideContainerCompositionLocals(it) },
                    layersViewController = layersHolder.getLayersViewController(),
                    initialLayoutDirection = layoutDirection,
                    configuration = configuration,
                    onFocusConditionsChanged = ::onFocusConditionsChanged,
                    focusedViewsList = if (focusable) focusedViewsList.childFocusedViewsList() else null,
                    consumePointerInputOutside = consumePointerInputOutside,
                    parentCoroutineContext = frameRecomposer.compositionContext.effectCoroutineContext,
                    ownerProvider = architectureComponentsOwner,
                    interfaceOrientationState = interfaceOrientationState,
                )

                layersHolder.getLayersViewController().attach(layer)
                onFocusConditionsChanged()

                return layer
            }
        }
    }

    private fun createComposeScene(
        invalidate: () -> Unit,
        platformContext: PlatformContext,
        layersHolder: ComposeLayersHolder,
        frameRecomposer: FrameRecomposer,
    ): ComposeScene = PlatformLayersComposeScene(
        frameRecomposer = frameRecomposer,
        density = view.density,
        layoutDirection = layoutDirection,
        composeSceneContext = createComposeSceneContext(
            platformContext = platformContext,
            layersHolder = layersHolder,
            frameRecomposer = frameRecomposer,
        ),
        // TODO: Split these into UIKit layout vs display invalidation. `invalidateLayout`
        // should call into layout scheduling, while `invalidateDraw` should schedule display.
        invalidateLayout = invalidate,
        invalidateDraw = invalidate,
    )

    /**
     * Enables or disables accessibility for each layer, as well as the root mediator, taking into
     * account layer order and ability to overlay underlying content.
     */
    private fun onFocusConditionsChanged() {
        var isFocusEnabled = true
        layersHolder?.layersViewController?.withLayers {
            it.fastForEachReversed { layer ->
                layer.isFocusEnabled = isFocusEnabled
                isFocusEnabled = isFocusEnabled && !layer.focusable
            }
        }
        mediator?.isFocusEnabled = isFocusEnabled
    }

    private val containingViewController: UIViewController get() {
        var responder: UIResponder? = view
        while (responder != null) {
            if (responder is UIViewController) {
                return responder
            }
            responder = responder.nextResponder
        }
        error("Compose Container mut be located inside a UIViewController")
    }

    @Composable
    private fun ProvideContainerCompositionLocals(content: @Composable () -> Unit) =
        CompositionLocalProvider(
            LocalUIViewController provides containingViewController,
            LocalSystemTheme provides systemThemeState.value,
            content = content
        )

    private fun updateMotionSpeed() {
        motionDurationScale.scaleFactor = if (UIAccessibilityIsReduceMotionEnabled()) {
            // 0f would cause motion to finish in the next frame callback.
            // See [MotionDurationScale.scaleFactor] for more details.
            0f
        } else {
            1f / (view.window?.layer?.speed?.takeIf { it > 0 } ?: 1f)
        }
    }

    private val windowScene: UIWindowScene?
        get() = view.window?.windowScene
}

private fun UIUserInterfaceStyle.asComposeSystemTheme(): SystemTheme {
    return when (this) {
        UIUserInterfaceStyle.UIUserInterfaceStyleLight -> SystemTheme.Light
        UIUserInterfaceStyle.UIUserInterfaceStyleDark -> SystemTheme.Dark
        else -> SystemTheme.Unknown
    }
}

private fun getApplicationLayoutDirection() =
    when (UIApplication.sharedApplication().userInterfaceLayoutDirection) {
        UIUserInterfaceLayoutDirection.UIUserInterfaceLayoutDirectionRightToLeft -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }

private class ComposeLayersHolder(
    private val useSeparateRenderThreadWhenPossible: Boolean,
    private val coroutineContext: CoroutineContext,
    private val view: ComposeContainerView
) {
    var layersViewController: ComposeLayersViewController? = null
        private set

    fun getLayersViewController(): ComposeLayersViewController {
        return layersViewController ?: run {
            val layers = ComposeLayersViewController(
                useSeparateRenderThreadWhenPossible = useSeparateRenderThreadWhenPossible,
                coroutineContext = coroutineContext,
                hostingComposeView = view
            )
            layers.containerWindow = view.window
            layersViewController = layers
            layers
        }
    }
}

private class SceneGeometryObserver(
    val onGeometryChanged: () -> Unit
) : CMPKeyValueObserver() {
    private val observingKey = "effectiveGeometry"

    var windowScene: UIWindowScene? = null
        set(value) {
            if (field == value) return
            removeObserverIfNeeded()
            field = value
            addObserverIfNeeded()
        }

    var isObservingEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                addObserverIfNeeded()
            } else {
                removeObserverIfNeeded()
            }
        }

    private var isObservingAdded = false

    private fun addObserverIfNeeded() {
        if (isObservingEnabled && !isObservingAdded) {
            isObservingAdded = true
            windowScene?.addObserver(this, observingKey, NSKeyValueObservingOptionNew, null)
        }
    }

    private fun removeObserverIfNeeded() {
        windowScene?.removeObserver(this, observingKey)
        isObservingAdded = false
    }

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: CPointer<out CPointed>?
    ) {
        onGeometryChanged()
    }
}

/**
 * Synchronizes UIKit/SwiftUI sizing proposals (`sizeThatFits`) with the preferred size produced by
 * the Compose scene under the corresponding constraints.
 *
 * - UIKit/SwiftUI proposes constraints via `sizeThatFits`
 * - Compose produces a preferred size under those constraints after its layout completes
 * - if the preferred size changes, we invalidate the *hosting* view intrinsic size so
 *   UIKit/SwiftUI can re-run layout with the updated information
 */
internal class ComposeSceneSizeSynchronizer(
    private val view: ComposeContainerView,
    private val composeSceneSize: (Constraints) -> IntSize?,
    private var invalidateComposeSceneContainerSize: () -> Unit = {},
) {

    private var latestSizeThatFitsConstraints: Constraints? = null
    /**
     * Latest constraints proposed by UIKit/SwiftUI through `sizeThatFits`.
     */
    private var lastMeasuredConstraints: Constraints? = null
    /**
     * Preferred size measured by Compose for [lastMeasuredConstraints].
     */
    private var preferredSize: IntSize? = null
    /**
     * Last size returned from `sizeThatFits`.
     *
     * Used as a temporary intrinsic size fallback until Compose measures and caches a real
     * preferred size for the latest external proposal.
     */
    private var lastSizeThatFitsResult: CValue<CGSize>? = null

    private val hasPreferredSize: Boolean get() = preferredSize != null

    val preferredCGSize: CValue<CGSize>?
        get() = preferredSize?.toCGSize(view.density) ?: lastSizeThatFitsResult

    fun onSizeThatFitsRequest(size: CValue<CGSize>): CValue<CGSize> {
        val constraints = size.useContents {
            Constraints(
                maxWidth = width.toConstraintValue(view.density),
                maxHeight = height.toConstraintValue(view.density)
            )
        }

        // `sizeThatFits` requests come from UIKit/SwiftUI and should win over stale internal state.
        if (latestSizeThatFitsConstraints != constraints) {
            latestSizeThatFitsConstraints = constraints
        }

        val result = preferredSizeForConstraints(constraints) ?: fallbackSizeThatFits(size)
        lastSizeThatFitsResult = result

        return result
    }

    private fun preferredSizeForConstraints(constraints: Constraints): CValue<CGSize>? {
        if (!hasPreferredSize) return null

        // Fast path: if Compose already measured preferred size for the exact same constraints,
        // return it directly.
        if (lastMeasuredConstraints == constraints) {
            return preferredCGSize
        }

        val didUpdatePreferredSize = measureAndCachePreferredSize(constraints) ?: return null

        if (didUpdatePreferredSize) {
            invalidateComposeSceneContainerSize()
        }

        return preferredCGSize
    }

    fun onComposeLayoutCompleted() {
        val constraints = latestSizeThatFitsConstraints ?: return
        val didUpdatePreferredSize = measureAndCachePreferredSize(constraints) ?: return

        if (didUpdatePreferredSize) {
            invalidateComposeSceneContainerSize()
        }
    }

    private fun measureAndCachePreferredSize(constraints: Constraints): Boolean? {
        val preferredSize = composeSceneSize(constraints) ?: return null

        lastMeasuredConstraints = constraints
        val preferredSizeUpdated = preferredSize != this.preferredSize
        if (preferredSizeUpdated) {
            this.preferredSize = preferredSize
        }
        return preferredSizeUpdated
    }

    private fun fallbackSizeThatFits(size: CValue<CGSize>): CValue<CGSize> {
        val viewSizeThatFits by lazy { view.superSizeThatFits(size) }

        val width = if (size.useContents { width } == UIViewNoIntrinsicMetric) {
            viewSizeThatFits.useContents { width }
        } else {
            size.useContents { width }
        }
        val height = if (size.useContents { height } == UIViewNoIntrinsicMetric) {
            viewSizeThatFits.useContents { height }
        } else {
            size.useContents { height }
        }

        return with(view.density) {
            DpSize(width.dp, height.dp).toSize().toIntSize().toCGSize(this)
        }
    }

    private fun CGFloat.toConstraintValue(density: Density): Int {
        if (this == UIViewNoIntrinsicMetric) return Constraints.Infinity
        val px = with(density) { dp.roundToPx() }
        if (px >= 0 || px == Constraints.Infinity) return px
        throw IllegalArgumentException("Invalid constraint size: $this")
    }
}

private fun IntSize.toCGSize(density: Density) = with(density) {
    CGSizeMake(width.toDp().value.toDouble(), height.toDp().value.toDouble())
}
