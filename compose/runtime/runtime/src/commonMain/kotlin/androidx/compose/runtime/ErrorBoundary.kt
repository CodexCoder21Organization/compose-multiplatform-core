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

import androidx.compose.runtime.tooling.DiagnosticComposeException
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField

/**
 * Receiver scope of an [ErrorBoundary]'s `fallback` content.
 *
 * Exposes the [error] that caused the boundary to render its fallback and a [reset] operation
 * that discards the error state and re-attempts the boundary's protected content.
 */
@ExperimentalComposeRuntimeApi
public interface ErrorBoundaryScope {
    /** The error currently contained by the boundary. */
    public val error: Throwable

    /**
     * Discards the boundary's error state and re-attempts composing the protected content on the
     * next composition. The re-attempt never happens re-entrantly inside the current composition
     * pass.
     *
     * A reset invoked from outside composition (for example an event handler responding to a user
     * gesture) is always honored. A reset invoked while composition is in progress (for example
     * directly from the fallback content) is subject to the boundary's bounded re-attempt guard:
     * once the protected content has failed repeatedly with no successful composition in between,
     * such resets are ignored and the boundary holds the fallback instead of spinning.
     */
    public fun reset()
}

/**
 * A handle to the nearest enclosing [ErrorBoundary], if any, exposed through [LocalErrorBoundary].
 *
 * Errors raised outside of composition — event handlers, [LaunchedEffect] and other coroutine
 * bodies — never pass through the composition machinery, so an [ErrorBoundary] cannot observe them
 * directly. Such errors can be forwarded to the boundary explicitly with [throwToBoundary], which
 * transitions the boundary to its fallback content as if the error had been thrown during
 * composition of the protected content.
 */
@ExperimentalComposeRuntimeApi
public fun interface ErrorBoundaryHandle {
    /** Forward [error] to the boundary, transitioning it to its fallback content. */
    public fun throwToBoundary(error: Throwable)
}

/**
 * Additional information about an error contained by an [ErrorBoundary], passed to the boundary's
 * `onError` callback.
 */
@ExperimentalComposeRuntimeApi
public class CompositionErrorInfo
internal constructor(
    /**
     * A string representation of the composition stack — the chain of composable groups that were
     * being composed when the error was raised — or `null` when the runtime was not collecting
     * diagnostic composition stack traces. Enable collection with
     * [Composer.setDiagnosticStackTraceMode].
     */
    public val composeStackTrace: String?
)

/**
 * The [ErrorBoundaryHandle] of the nearest enclosing [ErrorBoundary], or `null` when there is no
 * enclosing boundary. See [ErrorBoundaryHandle.throwToBoundary] for forwarding out-of-composition
 * errors to the boundary.
 */
@ExperimentalComposeRuntimeApi
public val LocalErrorBoundary: ProvidableCompositionLocal<ErrorBoundaryHandle?> =
    staticCompositionLocalOf {
        null
    }

/**
 * Contains errors thrown while composing [content] to this subtree instead of letting them abort
 * the entire composition.
 *
 * When [content] — or anything it composes — throws during composition or recomposition, the
 * runtime abandons the failed composition pass (its state writes are rolled back by disposing the
 * pass's mutable snapshot and its node tree changes are discarded unapplied), attributes the error
 * to the nearest enclosing boundary, and re-attempts composition with this boundary rendering
 * [fallback] in place of [content]. Values remembered in the abandoned pass receive their normal
 * abandonment callbacks and any previously committed content that is replaced by [fallback] is
 * torn down with its normal `onForgotten` / `DisposableEffect` teardown.
 *
 * The two content parameters mirror the split React established for its error boundaries:
 * [fallback] is the pure render-phase branch composed in place of the failed content, and
 * [onError] is a commit-phase side effect intended for logging and reporting, invoked with the
 * contained error and a [CompositionErrorInfo].
 *
 * Phase coverage is deliberate and explicit:
 * - **Composition and recomposition** errors are contained by the boundary.
 * - **Event handlers, [LaunchedEffect] and other coroutine bodies** never pass through
 *   composition; forward errors from them explicitly through [LocalErrorBoundary]'s
 *   [ErrorBoundaryHandle.throwToBoundary].
 * - **Layout and draw** failures happen in a separate subsystem and are not contained by this
 *   boundary.
 *
 * An error thrown while composing [fallback] itself is not contained by this boundary; it
 * escalates to the next enclosing [ErrorBoundary], if any, exactly like React's fallback
 * semantics.
 *
 * Recovery re-attempts [content], it does not merely re-render the fallback: an explicit
 * [ErrorBoundaryScope.reset] and a change to any element of [resetKeys] both discard the error
 * state and re-run [content] on the next composition — never re-entrantly inside the failing
 * pass. A bounded re-attempt guard keeps content that keeps throwing from spinning: automatic
 * re-attempts (from [resetKeys] changes or composition-time resets) are limited while the boundary
 * keeps failing with no successful composition in between, after which the boundary holds the
 * fallback and reports the loop through [onError]; a reset invoked outside composition (a user
 * gesture) is always honored.
 *
 * Two sibling boundaries created from the same call site (for example, in a loop) share the same
 * composite key hash unless distinguished with [key]; wrap such boundaries in [key] to keep their
 * contained error states distinct.
 *
 * @param fallback Composed in place of [content] while the boundary contains an error. Receives
 *   an [ErrorBoundaryScope] exposing the contained error and a reset operation.
 * @param onError Invoked after the boundary contains an error, with the error and a
 *   [CompositionErrorInfo]. Intended for logging; invoked as a commit-phase side effect.
 * @param resetKeys When any element changes (by [Array.contentEquals]) while the boundary is
 *   showing its fallback, the boundary automatically discards its error state and re-attempts
 *   [content], subject to the bounded re-attempt guard. Key these to the data whose change makes a
 *   re-attempt worthwhile.
 * @param content The protected content of the boundary.
 */
@Composable
@ExperimentalComposeRuntimeApi
public fun ErrorBoundary(
    fallback: @Composable ErrorBoundaryScope.() -> Unit,
    onError: ((Throwable, CompositionErrorInfo) -> Unit)? = null,
    resetKeys: Array<Any?> = emptyArray(),
    content: @Composable () -> Unit,
) {
    val composer = currentComposer as InternalComposer
    val keyHash = currentCompositeKeyHashCode
    val state = remember { ErrorBoundaryState() }
    state.composer = composer
    state.keyHash = keyHash
    state.recomposeScope = currentRecomposeScope

    // Pick up an error the runtime contained for this boundary position, then any signals raised
    // from outside composition (throwToBoundary / reset), then apply resetKeys-driven auto-reset.
    state.consumePendingTrip()
    state.mergePendingSignals()
    state.applyResetKeys(resetKeys)

    val error = state.error
    if (error == null) {
        // The marker group is what the runtime finds when it walks up from a throw raised while
        // composing the protected content. The fallback branch below is deliberately NOT wrapped
        // in this group so that an error thrown by the fallback escalates to the next enclosing
        // boundary instead of being contained here.
        composer.startMovableGroup(errorBoundaryContentKey, state.marker)
        CompositionLocalProvider(LocalErrorBoundary provides state.handle, content = content)
        composer.endMovableGroup()
        if (state.hasFailureBookkeeping) {
            SideEffect { state.noteContentCommitted() }
        }
    } else {
        val scope = remember(error) { ErrorBoundaryScopeView(error, state) }
        scope.fallback()
        if (onError != null) {
            SideEffect { state.dispatchPendingNotifications(onError) }
        }
    }
}

/**
 * The number of times a boundary automatically re-attempts its content (via [ErrorBoundary]'s
 * `resetKeys` or composition-time resets) while the content keeps failing with no successful
 * composition in between, before the boundary holds its fallback and reports the loop through
 * `onError`. A reset issued outside composition (a user gesture) is always honored and clears the
 * failure count.
 */
private const val MaxConsecutiveAutoResetAttempts = 3

/**
 * An absolute upper bound on consecutive contained failures of a single boundary position with no
 * successful composition and no out-of-composition reset in between. Beyond this the runtime stops
 * containing the error, which surfaces it like an unguarded composition failure. This is a
 * backstop against pathological containment loops; the auto-reset guard above keeps well-behaved
 * boundaries far below it.
 */
internal const val ErrorBoundaryHardContainmentCap = 64

/** No pending reset request. */
private const val ResetNone = 0

/** A reset requested during composition; subject to the bounded re-attempt guard. */
private const val ResetGuarded = 1

/** A reset requested outside composition (user gesture); always honored. */
private const val ResetForced = 2

/**
 * The identity the runtime uses to attribute a contained error to a boundary. Stored as the object
 * key of the boundary's content marker group so that the composer can find it by walking parent
 * groups up from the position of a throw.
 */
internal class ErrorBoundaryMarker(@JvmField val state: ErrorBoundaryState) {
    /**
     * The marker is the data key of the boundary's content group, and a group's data key is mixed
     * into the composite key hash of everything composed inside the group. That hash is how a
     * boundary's contained-error record is found again by a re-attempted composition pass — so it
     * must be stable across passes, while a fresh marker instance is created whenever a failed
     * initial pass abandons the boundary's remembered state. A constant hash keeps the composite
     * key hash of nested content stable; equality intentionally remains identity so that group
     * matching still distinguishes marker instances.
     */
    override fun hashCode(): Int = errorBoundaryContentKey

    /**
     * Records [error] against the boundary this marker belongs to and schedules the boundary to
     * compose its fallback. Returns `false` when the boundary cannot contain the error (its
     * composer is gone or the hard containment cap was reached), in which case the error must
     * propagate as if the boundary were absent.
     *
     * Called by the [Recomposer] on the applier thread after the failed composition pass was
     * abandoned and its snapshot disposed. The error record is intentionally kept in plain
     * composer-confined storage — not snapshot state — so that it survives the disposal of the
     * failed pass's snapshot.
     */
    fun trip(error: Throwable): Boolean {
        val composer = state.composer ?: return false
        val record = composer.errorBoundaryTripRecord(state.keyHash, create = true) ?: return false
        if (record.failedAttempts >= ErrorBoundaryHardContainmentCap) return false
        record.failedAttempts++
        record.error = error
        state.recomposeScope?.invalidate()
        return true
    }
}

/**
 * The per-position record of contained errors, kept on the composer keyed by the boundary's
 * composite key hash. Keeping it on the composer rather than in the boundary's remembered state is
 * what makes containment of *initial* composition failures work: the failed pass's remembered
 * state is abandoned, but this record survives to be consumed by the fresh boundary state of the
 * re-attempted pass.
 */
internal class ErrorBoundaryTripRecord {
    @JvmField var error: Throwable? = null
    @JvmField var failedAttempts: Int = 0
}

/** The remembered state of one [ErrorBoundary] instance. */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class ErrorBoundaryState {
    @JvmField var composer: InternalComposer? = null
    @JvmField var keyHash: CompositeKeyHashCode = EmptyCompositeKeyHashCode
    @JvmField var recomposeScope: RecomposeScope? = null

    val marker: ErrorBoundaryMarker = ErrorBoundaryMarker(this)

    val handle: ErrorBoundaryHandle = ErrorBoundaryHandle { error ->
        pendingImperativeError = error
        recomposeScope?.invalidate()
    }

    /** The error the boundary is currently showing its fallback for. */
    @JvmField var error: Throwable? = null

    /**
     * Consecutive contained failures with no successful content composition in between. Mirrors
     * the composer-kept [ErrorBoundaryTripRecord.failedAttempts].
     */
    @JvmField var failedAttempts: Int = 0

    /** The error not yet reported through `onError`, if any. */
    @JvmField var pendingErrorNotification: Throwable? = null

    @JvmField var pendingErrorInfo: CompositionErrorInfo? = null

    /** Set when the auto-reset guard suppressed a re-attempt; reported through `onError`. */
    @JvmField var pendingLoopNotification: Throwable? = null

    /** The last error delivered to `onError`, to deliver each contained error exactly once. */
    @JvmField var lastNotifiedError: Throwable? = null

    @JvmField var lastResetKeys: Array<Any?>? = null

    /** Error forwarded from outside composition through [ErrorBoundaryHandle.throwToBoundary]. */
    @Volatile @JvmField var pendingImperativeError: Throwable? = null

    /** Reset requested through [ErrorBoundaryScope.reset]; one of the Reset* constants. */
    @Volatile @JvmField var pendingReset: Int = ResetNone

    val hasFailureBookkeeping: Boolean
        get() = failedAttempts > 0 || lastNotifiedError != null

    /**
     * Consumes the error, if any, that the runtime contained for this boundary position since the
     * boundary last composed. Runs on the composer thread at the start of the boundary's body.
     */
    fun consumePendingTrip() {
        val record = composer?.errorBoundaryTripRecord(keyHash, create = false) ?: return
        val tripped = record.error
        if (tripped != null) {
            record.error = null
            error = tripped
            pendingErrorNotification = tripped
            pendingErrorInfo = CompositionErrorInfo(composeStackTraceOf(tripped))
        }
        failedAttempts = record.failedAttempts
    }

    /** Applies signals raised from outside composition: forwarded errors and reset requests. */
    fun mergePendingSignals() {
        val imperative = pendingImperativeError
        if (imperative != null) {
            pendingImperativeError = null
            error = imperative
            pendingErrorNotification = imperative
            pendingErrorInfo = CompositionErrorInfo(composeStackTraceOf(imperative))
            // Forwarded errors count against the guard like contained ones so that an effect that
            // keeps failing cannot spin the boundary through data-driven resets.
            failedAttempts++
            syncFailureCountToRecord()
        }
        val reset = pendingReset
        if (reset != ResetNone) {
            pendingReset = ResetNone
            if (error != null) {
                if (reset == ResetForced) {
                    clearErrorForRetry(clearFailures = true)
                } else {
                    autoReset()
                }
            }
        }
    }

    /** Applies resetKeys-driven recovery: a key change while showing the fallback re-attempts. */
    fun applyResetKeys(resetKeys: Array<Any?>) {
        val previous = lastResetKeys
        lastResetKeys = resetKeys
        if (error != null && previous != null && !resetKeys.contentEquals(previous)) {
            autoReset()
        }
    }

    private fun autoReset() {
        if (failedAttempts <= MaxConsecutiveAutoResetAttempts) {
            clearErrorForRetry(clearFailures = false)
        } else if (pendingLoopNotification == null) {
            pendingLoopNotification = error
        }
    }

    private fun clearErrorForRetry(clearFailures: Boolean) {
        error = null
        pendingLoopNotification = null
        if (clearFailures) {
            failedAttempts = 0
            syncFailureCountToRecord()
        }
    }

    private fun syncFailureCountToRecord() {
        val composer = composer ?: return
        if (failedAttempts == 0) {
            composer.clearErrorBoundaryTripRecord(keyHash)
        } else {
            composer.errorBoundaryTripRecord(keyHash, create = true)?.failedAttempts =
                failedAttempts
        }
    }

    /** Called as a side effect after the protected content composes and commits successfully. */
    fun noteContentCommitted() {
        failedAttempts = 0
        lastNotifiedError = null
        pendingLoopNotification = null
        composer?.clearErrorBoundaryTripRecord(keyHash)
    }

    /** Delivers not-yet-reported errors to `onError`; runs as a commit-phase side effect. */
    fun dispatchPendingNotifications(onError: (Throwable, CompositionErrorInfo) -> Unit) {
        val toNotify = pendingErrorNotification
        if (toNotify != null && toNotify !== lastNotifiedError) {
            lastNotifiedError = toNotify
            pendingErrorNotification = null
            val info = pendingErrorInfo ?: CompositionErrorInfo(null)
            pendingErrorInfo = null
            onError(toNotify, info)
        }
        val loop = pendingLoopNotification
        if (loop != null) {
            pendingLoopNotification = null
            onError(
                ErrorBoundaryResetLoopException(loop),
                CompositionErrorInfo(null),
            )
        }
    }

    fun requestReset() {
        val force = composer?.isComposing != true
        val requested = if (force) ResetForced else ResetGuarded
        if (requested > pendingReset) pendingReset = requested
        recomposeScope?.invalidate()
    }
}

@OptIn(ExperimentalComposeRuntimeApi::class)
private class ErrorBoundaryScopeView(
    override val error: Throwable,
    private val state: ErrorBoundaryState,
) : ErrorBoundaryScope {
    override fun reset() {
        state.requestReset()
    }
}

/**
 * Reported through an [ErrorBoundary]'s `onError` when the boundary's bounded re-attempt guard
 * suppressed further automatic re-attempts because the protected content kept failing with no
 * successful composition in between. The boundary holds its fallback; an explicit
 * [ErrorBoundaryScope.reset] from outside composition re-attempts and clears the guard.
 */
internal class ErrorBoundaryResetLoopException(cause: Throwable) :
    RuntimeException(
        "ErrorBoundary content kept failing across $MaxConsecutiveAutoResetAttempts consecutive " +
            "automatic re-attempts with no successful composition in between; holding the " +
            "fallback. An explicit reset() from outside composition re-attempts and clears " +
            "this guard.",
        cause,
    )

private fun composeStackTraceOf(error: Throwable): String? =
    error.suppressedExceptions.firstOrNull { it is DiagnosticComposeException }?.let {
        it.stackTraceToString()
    }
