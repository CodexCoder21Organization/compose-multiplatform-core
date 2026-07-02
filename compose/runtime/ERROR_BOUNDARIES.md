# Compose runtime error boundaries — design and API proposal

**Status:** implemented in this fork (runtime-only v1) · proposed for upstream
**Modules:** `compose/runtime/runtime`
**Tracking:** [JetBrains/compose-multiplatform#2582](https://github.com/JetBrains/compose-multiplatform/issues/2582) (closed as "hard to implement"), [JetBrains/compose-multiplatform#1764](https://github.com/JetBrains/compose-multiplatform/issues/1764), Google issue tracker [248513437](https://issuetracker.google.com/issues/248513437), [329588687](https://issuetracker.google.com/issues/329588687)

## Problem

An exception thrown during composition or recomposition propagates up through the
`Recomposer` and takes down the whole UI. There is no supported way to contain a
composition failure to a subtree and substitute fallback content — the capability React
popularized as an *error boundary*.

Userland cannot build a real one:

- The compose compiler rewrites every `@Composable` into balanced group start/end calls
  and forbids `try`/`catch` around composable invocations, because an exception unwinding
  mid-group leaves the slot table imbalanced ("Start/end imbalance" crash class).
- Containing a failure means unwinding a *partially applied* composition — slot table
  writes, applier operations, remembered values, `CompositionLocal` propagation,
  registered effects — back to a consistent state. Only the runtime can reach those
  internals.
- The community workaround is subcomposition (`SubcomposeLayout` / a separate
  `Composition`), which changes layout semantics, leaks the composition seam
  (`CompositionLocal`s, `movableContentOf`, tooling traversal), taxes the non-error path
  with a second composition, and still misses failures raised outside the narrow seam.

## Public API

```kotlin
@Composable
@ExperimentalComposeRuntimeApi
fun ErrorBoundary(
    fallback: @Composable ErrorBoundaryScope.() -> Unit,          // render-phase, pure
    onError: ((Throwable, CompositionErrorInfo) -> Unit)? = null, // commit-phase logging
    resetKeys: Array<Any?> = emptyArray(),                        // data-driven auto-reset
    content: @Composable () -> Unit,
)

@ExperimentalComposeRuntimeApi
interface ErrorBoundaryScope {
    val error: Throwable
    fun reset()
}

// Out-of-composition errors (event handlers, LaunchedEffect / coroutine bodies) forward
// explicitly — composition cannot observe a coroutine's throw:
@ExperimentalComposeRuntimeApi
val LocalErrorBoundary: ProvidableCompositionLocal<ErrorBoundaryHandle?>

@ExperimentalComposeRuntimeApi
fun interface ErrorBoundaryHandle {
    fun throwToBoundary(error: Throwable)
}

@ExperimentalComposeRuntimeApi
class CompositionErrorInfo internal constructor(
    val composeStackTrace: String?, // populated when Composer.setDiagnosticStackTraceMode is on
)
```

The two-parameter split mirrors React's error boundary contract, made explicit:
`fallback` is the pure render-phase branch (the `getDerivedStateFromError` analog) and
`onError` is the commit-phase side effect for logging (the `componentDidCatch` analog),
receiving a composition stack when the runtime is collecting diagnostic stack traces.

## Semantics

- **Containment.** A throw during (re)composition anywhere under `content` abandons the
  failed composition pass, attributes the error to the nearest enclosing boundary, and
  re-attempts composition with that boundary composing `fallback` in `content`'s slot.
- **Rollback.** The failed pass's state writes are discarded by **disposing the pass's
  mutable snapshot instead of applying it** (the snapshot system is MVCC; writes stay
  isolated until `apply()`). Node-tree mutations are discarded by **dropping the pass's
  unapplied change list** (the composer defers all `Applier` changes to the end of the
  pass). Values remembered in the failed pass receive `onAbandoned`. Previously committed
  content replaced by the fallback receives its normal `onForgotten` /
  `DisposableEffect` teardown when the fallback pass commits.
- **Phase coverage (v1).** Composition and recomposition failures are contained. Event
  handlers, `LaunchedEffect` and other coroutine bodies never pass through composition —
  exactly as React catches neither async nor event-handler errors — and reach the
  boundary through the imperative `LocalErrorBoundary` channel instead. Layout and draw
  failures live in a separate subsystem (the layout node tree and draw scope, not the
  slot table) and are a defined v2 extension. Failures thrown while *applying* changes
  (applier errors, `SideEffect` bodies, remember observers) are not contained in v1.
- **Fallback escalation.** The fallback composes *outside* the boundary's guarded region:
  an error thrown while composing the fallback escalates to the next enclosing boundary
  (React semantics), never loops on the same boundary.
- **Reset re-attempts, never re-entrantly.** `reset()` and a change to any `resetKeys`
  element discard the error state and re-run `content` on the next composition. A
  **bounded re-throw guard** keeps a re-attempt that throws again before any successful
  composition from spinning: automatic re-attempts are capped (3 consecutive failures),
  after which the boundary holds the fallback and reports the loop through `onError`; a
  `reset()` invoked outside composition (a user gesture) is always honored and clears the
  guard. A hard containment cap (64 consecutive contained failures of one boundary with
  no success and no user reset) is the final backstop: beyond it the error propagates as
  if the boundary were absent.
- **Identity caveat.** A boundary's contained-error record is keyed by its composite key
  hash. Sibling boundaries created from the same call site (e.g. in a loop) should be
  wrapped in `key(...)` to keep their records distinct, exactly like other
  position-keyed runtime state.

## Design: why runtime-only containment works (no compiler change in v1)

The plan assumed a compiler + runtime co-design. The spike's central finding is that
**React-style whole-pass abandonment gives correct v1 semantics with no compiler change**:

1. The runtime *already* abandons a failed pass safely. `doCompose` resets the composer
   (`abortRoot()`) on any throw; `guardChanges` / `abandonChanges` discard the unapplied
   change list and dispatch `onAbandoned`; `guardInvalidationsLocked` restores pending
   invalidations. The hot-reload / live-edit recovery path
   (`Recomposer.processCompositionError`, `retryFailedCompositions`) has exercised
   re-composition after an abandoned pass in production for years.
2. What was missing is **attribution** (which boundary encloses the throw?),
   **bookkeeping that survives the abandoned pass** (the failed pass's remembered state
   is abandoned with it), and a **driver** that re-attempts instead of rethrowing.
3. No userland `try`/`catch` around composable calls is introduced anywhere — the catch
   sits in the runtime's existing root catch sites — so the compiler's group-balance
   invariants are never violated and its `try`/`catch` ban stays intact.

Subtree-scoped slot-table unwinding (re-running *only* the failed subtree rather than
re-attempting the pass) remains a compiler + runtime optimization for later: it changes
how much work a contained failure costs, not the observable semantics.

### Mechanics

- **Marker group.** `ErrorBoundary` wraps `content` in a movable group with reserved key
  `errorBoundaryContentKey` (208) whose object key is an `ErrorBoundaryMarker` pointing
  at the boundary's state. The fallback branch is deliberately outside this group.
- **Attribution at throw time.** Both composers (`GapComposer` and `LinkComposer`)
  capture the nearest enclosing marker in their `doCompose` catch — while the
  reader/writer are still positioned at the throw — by walking parent groups exactly the
  way the diagnostic `currentStackTrace()` walk does: insertion writer chain first
  (content being inserted by this pass), then reader chain (committed groups). The walk
  is defensive: it must never throw while unwinding a real failure.
- **Trip.** The `Recomposer` consumes the captured marker in `composingOrContain` (a
  variant of `composing` that **disposes** the failed pass's snapshot instead of applying
  it). Tripping records the error in a **composer-kept registry keyed by the boundary's
  composite key hash** — not in the boundary's remembered state, which the failed pass
  may have abandoned — and invalidates the boundary's recompose scope.
- **Re-attempt.** For an initial composition, `Recomposer.composeInitial` loops: each
  contained failure trips a strictly higher boundary (the fallback is outside the marker
  group), so the loop is bounded by boundary nesting depth. For recomposition,
  `performRecompose` reports "no changes" for the contained pass and the tripped
  boundary's invalidation schedules the fallback pass on the next frame — re-attempts are
  never re-entrant.
- **Consume.** On its next composition the boundary body consumes the trip record for its
  composite key hash and composes the fallback; `onError` dispatches as a `SideEffect`
  (commit phase) after the fallback pass applies.
- **Nested compositions.** A failure raised by a subcomposition composed inline during
  its parent's pass unwinds through the parent's `doCompose` too; if the subcomposition
  has no boundary of its own, a boundary in the parent composition contains it. A
  subcomposition composed outside any enclosing pass (e.g. `SubcomposeLayout` measuring
  during layout) is contained only by boundaries inside that subcomposition in v1. The
  recomposer's `errorState` is cleared when a nested failure ends up contained, so a
  contained error never wedges the recomposer.

## Relationship to Observables / RemoteObservableBoundary

The Observables workstream's `RemoteObservableBoundary` catches
`RemoteObservableDataUnavailable` at its own read (no runtime support needed) but defers
the *deep-throw* case to a subcomposition workaround. With this primitive it becomes a
thin specialization: catch `RemoteObservableDataUnavailable`, render last-known-good, and
key `resetKeys` on the projection's availability/revision so the Observable's
invalidation push *is* the re-attempt trigger — data-driven recovery with the re-throw
guard as backstop, no subcomposition.

## Upstreaming

- The runtime change is self-contained in `androidx.compose.runtime` and marked
  `@ExperimentalComposeRuntimeApi`.
- No compiler change is required for these semantics; the compiler workstream reduces to
  the later subtree-scoped-unwinding optimization and (optionally) relaxing the
  `try`/`catch` diagnostic at the boundary for userland ergonomics.
- Existing upstream demand: compose-multiplatform#2582 (closed as "hard to implement"
  with subcomposition workarounds that the requester showed still crash), #1764, and the
  runtime team's own "log exceptions captured in composition" work (Compose Runtime
  1.10.0-alpha02) touching the same seam.

## Tests

`compose/runtime/runtime/src/nonEmulatorCommonTest/kotlin/androidx/compose/runtime/ErrorBoundaryTests.kt`
runs the suite under **both** composer implementations (gap buffer and link buffer):
initial-composition containment, recomposition containment with sibling preservation,
exactly-once `onError`, uncontained propagation without a boundary, fallback escalation
to the outer boundary, nested boundaries, explicit reset, `resetKeys` recovery, the
bounded re-throw guard (loop held + reported), the imperative `throwToBoundary` channel,
`onForgotten` teardown of replaced content, `onAbandoned` for values remembered in failed
passes, snapshot rollback of failed-pass state writes, sibling independence, re-containment
after recovery, and zero-overhead transparency of a healthy boundary.
