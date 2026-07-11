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

import androidx.compose.runtime.internal.logError
import androidx.compose.runtime.tooling.DiagnosticComposeException
import kotlin.concurrent.Volatile
import kotlin.jvm.JvmField

/**
 * Receiver scope of an [ErrorBoundary]'s `fallback` content.
 *
 * Exposes the [error] that caused the boundary to render its fallback and a [reset] operation that
 * discards the error state and re-attempts the boundary's protected content.
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
 * abandonment callbacks and any previously committed content that is replaced by [fallback] is torn
 * down with its normal `onForgotten` / `DisposableEffect` teardown.
 *
 * The two content parameters mirror the split React established for its error boundaries:
 * [fallback] is the pure render-phase branch composed in place of the failed content, and [onError]
 * is a commit-phase side effect intended for logging and reporting, invoked with the contained
 * error and a [CompositionErrorInfo].
 *
 * Phase coverage is deliberate and explicit:
 * - **Composition and recomposition** errors are contained by the boundary.
 * - **Event handlers, [LaunchedEffect] and other coroutine bodies** never pass through composition;
 *   forward errors from them explicitly through [LocalErrorBoundary]'s
 *   [ErrorBoundaryHandle.throwToBoundary].
 * - **Layout and draw** failures happen in a separate subsystem and are not contained by this
 *   boundary.
 *
 * An error thrown while composing [fallback] itself is not contained by this boundary; it escalates
 * to the next enclosing [ErrorBoundary], if any, exactly like React's fallback semantics. For the
 * same reason, [LocalErrorBoundary] read from inside [fallback] resolves to the next *enclosing*
 * boundary (or `null`), never to this boundary.
 *
 * Recovery re-attempts [content], it does not merely re-render the fallback: an explicit
 * [ErrorBoundaryScope.reset] and a change to any element of [resetKeys] both discard the error
 * state and re-run [content] on the next composition — never re-entrantly inside the failing pass.
 * A bounded re-attempt guard keeps content that keeps throwing from spinning: automatic re-attempts
 * (from [resetKeys] changes or composition-time resets) are limited while the boundary keeps
 * failing with no successful composition in between, after which the boundary holds the fallback
 * and reports the loop through [onError]; a reset invoked outside composition (a user gesture) is
 * always honored. Errors forwarded through [ErrorBoundaryHandle.throwToBoundary] do not count
 * toward this guard: they are raised by work that ran after a successful composition, so their
 * recurrence is bounded by their own triggers.
 *
 * When non-null, [onError] is invoked once per contained error — including an error whose boundary
 * immediately recovered through [resetKeys] or a reset in the same pass, including re-containment
 * of the same [Throwable] instance on a later failure, and including multiple errors accepted
 * before the boundary's next commit. Errors accepted while [onError] is null are not retained and
 * are not replayed if a callback is supplied by a later recomposition. When more than one error is
 * accepted before the boundary commits (for example a contained throw and a concurrently forwarded
 * error), the latest error is the one displayed by [fallback]. If [onError] itself throws, the
 * runtime logs that callback failure and keeps the boundary/recomposer state usable; `onError` is a
 * reporting hook and its own failure is not re-contained by the boundary.
 *
 * Same-call-site sibling boundaries created by ordinary repeated composition receive distinct
 * effective composite key hashes and keep their contained error states independent. As with other
 * position-keyed runtime state, use [key] when list items can reorder and need stable identity.
 *
 * Known v1 limitations: a boundary inserted inside newly-created [movableContentOf] content cannot
 * contain a failure raised during that deferred insertion pass; wrap the movable content invocation
 * in an already-committed enclosing boundary to contain that failure. When live edit / hot reload
 * is enabled, an error raised in a subcomposition without its own boundary is captured by the
 * hot-reload recovery before any boundary in the parent composition can contain it.
 *
 * @param fallback Composed in place of [content] while the boundary contains an error. Receives an
 *   [ErrorBoundaryScope] exposing the contained error and a reset operation.
 * @param onError When non-null, invoked after the boundary contains an error, with the error and a
 *   [CompositionErrorInfo]. Intended for logging; invoked as a commit-phase side effect. Errors
 *   accepted while this is null are discarded for reporting and are not replayed to a callback
 *   supplied by a later recomposition.
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
    state.depth = composer.errorBoundaryNestingDepth()
    state.recomposeScope = currentRecomposeScope
    state.updateOnErrorAvailability(onError != null)

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
        // boundary instead of being contained here. Note that this also means LocalErrorBoundary
        // read from inside the fallback resolves to the next ENCLOSING boundary (or null), never
        // to this boundary.
        composer.startMovableGroup(errorBoundaryContentKey, state.marker)
        CompositionLocalProvider(LocalErrorBoundary provides state.handle, content = content)
        composer.endMovableGroup()
        if (state.hasFailureBookkeeping) {
            SideEffect { state.noteContentCommitted() }
        }
    } else {
        val scope = remember(state.errorGeneration) { ErrorBoundaryScopeView(error, state) }
        scope.fallback()
    }
    // Dispatched from BOTH branches: an error that was contained and then immediately auto-reset
    // (for example a resetKeys change arriving in the same pass that consumes the trip) is still
    // a contained error and must still be reported. Runs as a commit-phase side effect after the
    // pass that observed the error applies.
    if (onError != null && state.hasPendingNotifications) {
        SideEffect { state.dispatchPendingNotifications(onError) }
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
 * Two distinct backstops share this bound:
 * - [Recomposer]'s initial-composition re-attempt loop gives up after this many contained failures
 *   in a single `composeInitial` call. Each contained failure trips a strictly higher enclosing
 *   boundary, so reaching the cap indicates a containment bug, and the failure is surfaced as a
 *   runtime error.
 * - A single boundary position stops containing after this many recorded failures with no
 *   successful composition and no out-of-composition reset in between (see
 *   [ErrorBoundaryMarker.trip]) — only reachable if the auto-reset guard is bypassed
 *   pathologically, in which case the error propagates as if the boundary were absent.
 */
internal const val ErrorBoundaryHardContainmentCap = 64

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
    fun trip(error: Throwable, depth: Int, invalidateScope: Boolean): Boolean {
        val composer = state.composer ?: return false
        val record =
            composer.errorBoundaryTripRecord(state.keyHash, depth, create = true) ?: return false
        if (record.failedAttempts >= ErrorBoundaryHardContainmentCap) return false
        record.failedAttempts++
        record.error = error
        // Only a recomposition containment needs to schedule the boundary's fallback pass; the
        // initial-composition retry loop re-attempts synchronously. A scope created by the
        // abandoned pass itself (a boundary INSERTED by the failed pass — initial composition,
        // or a boundary newly revealed by a recomposition) must not be invalidated: its anchor
        // points into the discarded insert table and can alias a committed slot-table location.
        // For an abandoned-scope recomposition containment the composition is re-scheduled as a
        // whole instead — its restored invalidations re-run the pass, which re-creates the
        // boundary and consumes this record.
        if (invalidateScope) {
            if (!state.abandoned) {
                state.recomposeScope?.invalidate()
            } else {
                (composer.composition as? CompositionImpl)?.let { it.parent.invalidate(it) }
            }
        }
        if (state.abandoned) {
            state.clearComposerReferences()
        }
        return true
    }
}

/**
 * The per-position record of contained errors, kept on the composer keyed by the boundary's
 * composite key hash plus its marker-nesting [depth]. Keeping it on the composer rather than in the
 * boundary's remembered state is what makes containment of *initial* composition failures work: the
 * failed pass's remembered state is abandoned, but this record survives to be consumed by the fresh
 * boundary state of the re-attempted pass. Records sharing a hash chain through [next].
 */
internal class ErrorBoundaryTripRecord(@JvmField val depth: Int) {
    @JvmField var error: Throwable? = null
    @JvmField var failedAttempts: Int = 0
    @JvmField var next: ErrorBoundaryTripRecord? = null
}

/**
 * The remembered state of one [ErrorBoundary] instance.
 *
 * Implements [RememberObserver] so that the boundary's composer-kept trip record is removed when
 * the boundary leaves the composition ([onForgotten]) or when the pass that created this state is
 * abandoned ([onAbandoned]) — otherwise a tripped-then-removed boundary would leak its record (and
 * the recorded [Throwable]) for the lifetime of the composition, and a later boundary reusing the
 * same call site (same composite key hash) would inherit the stale error. On the abandoned-pass
 * path the record is (re)created by the trip that happens *after* abandonment is dispatched, so
 * clearing here never loses a live containment.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
internal class ErrorBoundaryState : RememberObserver {
    // Written on the composer thread; read from arbitrary threads by requestReset/throwToBoundary,
    // hence volatile.
    @Volatile @JvmField var composer: InternalComposer? = null
    @JvmField var keyHash: CompositeKeyHashCode = EmptyCompositeKeyHashCode
    @JvmField var depth: Int = 0
    @Volatile @JvmField var recomposeScope: RecomposeScope? = null

    val marker: ErrorBoundaryMarker = ErrorBoundaryMarker(this)

    val handle: ErrorBoundaryHandle = ErrorBoundaryHandle { error ->
        // Latest-wins by design: concurrent forwards race to a single pending slot; the boundary
        // shows (and reports) the most recent forwarded error.
        pendingImperativeError = error
        recomposeScope?.invalidate()
    }

    /** The error the boundary is currently showing its fallback for. */
    @JvmField var error: Throwable? = null

    /**
     * Consecutive contained composition failures with no successful content composition in between.
     * Mirrors the composer-kept [ErrorBoundaryTripRecord.failedAttempts]. Errors forwarded through
     * [ErrorBoundaryHandle.throwToBoundary] do NOT count: they are raised by work that ran after a
     * successful composition, so their recurrence is bounded by their own triggers rather than by
     * the composition re-attempt guard.
     */
    @JvmField var failedAttempts: Int = 0

    /**
     * Monotonic count of errors this boundary has accepted (contained trips and forwarded errors).
     * This drives the fallback scope identity via `remember(state.errorGeneration)`; `onError`
     * delivery itself is queued in [pendingErrorNotifications] so re-containing the *same*
     * [Throwable] instance is a new failure and is reported again.
     */
    @JvmField var errorGeneration: Int = 0

    /** Accepted errors not yet reported through `onError`, in acceptance order. */
    @JvmField
    val pendingErrorNotifications: MutableList<ErrorBoundaryNotification> = mutableListOf()

    /** Set when the auto-reset guard suppressed a re-attempt; reported through `onError`. */
    @JvmField var pendingLoopNotification: Throwable? = null

    @JvmField var lastResetKeys: Array<Any?>? = null

    /** Error forwarded from outside composition through [ErrorBoundaryHandle.throwToBoundary]. */
    @Volatile @JvmField var pendingImperativeError: Throwable? = null

    /**
     * Reset requests are split into two independent flags rather than a single max-merged value so
     * that a forced (out-of-composition) reset arriving concurrently with the body consuming a
     * guarded one can never be lost: consuming one flag does not clear the other, and the forced
     * request's invalidate() schedules another pass that will observe it.
     */
    @Volatile @JvmField var pendingGuardedReset: Boolean = false

    @Volatile @JvmField var pendingForcedReset: Boolean = false

    val hasFailureBookkeeping: Boolean
        get() = failedAttempts > 0 || pendingLoopNotification != null

    val hasPendingNotifications: Boolean
        get() = pendingErrorNotifications.isNotEmpty() || pendingLoopNotification != null

    /**
     * Updates whether reporting is active and discards reports when no callback can consume them.
     */
    fun updateOnErrorAvailability(available: Boolean) {
        if (!available) {
            pendingErrorNotifications.clear()
            pendingLoopNotification = null
        }
        isOnErrorAvailable = available
    }

    @JvmField var isOnErrorAvailable: Boolean = false

    private fun acceptError(accepted: Throwable) {
        error = accepted
        errorGeneration++
        if (isOnErrorAvailable) {
            pendingErrorNotifications +=
                ErrorBoundaryNotification(
                    accepted,
                    CompositionErrorInfo(composeStackTraceOf(accepted)),
                )
        }
    }

    /**
     * Consumes the error, if any, that the runtime contained for this boundary position since the
     * boundary last composed. Runs on the composer thread at the start of the boundary's body.
     */
    fun consumePendingTrip() {
        val record = composer?.errorBoundaryTripRecord(keyHash, depth, create = false) ?: return
        val tripped = record.error
        if (tripped != null) {
            record.error = null
            acceptError(tripped)
        }
        failedAttempts = record.failedAttempts
    }

    /** Applies signals raised from outside composition: forwarded errors and reset requests. */
    fun mergePendingSignals() {
        val imperative = pendingImperativeError
        if (imperative != null) {
            pendingImperativeError = null
            acceptError(imperative)
        }
        if (pendingForcedReset) {
            pendingForcedReset = false
            pendingGuardedReset = false
            if (error != null) {
                clearErrorForRetry(clearFailures = true)
            }
        } else if (pendingGuardedReset) {
            pendingGuardedReset = false
            if (error != null) {
                autoReset()
            }
        }
    }

    /** Applies resetKeys-driven recovery: a key change while showing the fallback re-attempts. */
    fun applyResetKeys(resetKeys: Array<Any?>) {
        val previous = lastResetKeys
        lastResetKeys = resetKeys.copyOf()
        if (error != null && previous != null && !resetKeys.contentEquals(previous)) {
            autoReset()
        }
    }

    private fun autoReset() {
        if (failedAttempts <= MaxConsecutiveAutoResetAttempts) {
            clearErrorForRetry(clearFailures = false)
        } else if (isOnErrorAvailable && pendingLoopNotification == null) {
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
            composer.clearErrorBoundaryTripRecord(keyHash, depth)
        } else {
            composer.errorBoundaryTripRecord(keyHash, depth, create = true)?.failedAttempts =
                failedAttempts
        }
    }

    /** Called as a side effect after the protected content composes and commits successfully. */
    fun noteContentCommitted() {
        failedAttempts = 0
        pendingLoopNotification = null
        composer?.clearErrorBoundaryTripRecord(keyHash, depth)
    }

    /** Delivers not-yet-reported errors to `onError`; runs as a commit-phase side effect. */
    fun dispatchPendingNotifications(onError: (Throwable, CompositionErrorInfo) -> Unit) {
        if (pendingErrorNotifications.isNotEmpty()) {
            val notifications = pendingErrorNotifications.toList()
            pendingErrorNotifications.clear()
            for (notification in notifications) {
                dispatchOnError(onError, notification.error, notification.info)
            }
        }
        val loop = pendingLoopNotification
        if (loop != null) {
            pendingLoopNotification = null
            dispatchOnError(
                onError,
                ErrorBoundaryResetLoopException(loop),
                CompositionErrorInfo(null),
            )
        }
    }

    fun requestReset() {
        if (composer?.isComposing == true) {
            pendingGuardedReset = true
        } else {
            pendingForcedReset = true
        }
        recomposeScope?.invalidate()
    }

    /**
     * Set when the pass that created this state was abandoned: the state (and its recompose scope)
     * belong to a discarded insert table and must not be used for scheduling.
     */
    @JvmField var abandoned: Boolean = false

    override fun onRemembered() {}

    override fun onForgotten() {
        composer?.clearErrorBoundaryTripRecord(keyHash, depth)
        clearComposerReferences()
    }

    override fun onAbandoned() {
        abandoned = true
        composer?.clearErrorBoundaryTripRecord(keyHash, depth)
    }

    fun clearComposerReferences() {
        composer = null
        recomposeScope = null
    }
}

@OptIn(ExperimentalComposeRuntimeApi::class)
private fun dispatchOnError(
    onError: (Throwable, CompositionErrorInfo) -> Unit,
    error: Throwable,
    info: CompositionErrorInfo,
) {
    try {
        onError(error, info)
    } catch (callbackFailure: Throwable) {
        logError(
            "ErrorBoundary onError callback threw while reporting a contained error.",
            callbackFailure,
        )
    }
}

@OptIn(ExperimentalComposeRuntimeApi::class)
internal class ErrorBoundaryNotification(
    @JvmField val error: Throwable,
    @JvmField val info: CompositionErrorInfo,
)

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
    error.suppressedExceptions
        .firstOrNull { it is DiagnosticComposeException }
        ?.let { it.stackTraceToString() }
