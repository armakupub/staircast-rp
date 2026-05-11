# Upstream issue draft for copiumsawsed/pz-Staircast

Filed 2026-04-30 as <https://github.com/copiumsawsed/pz-Staircast/issues/1>.

---

## Status (2026-05-11): hypothesis withdrawn

After re-reading the 42.18 decompile in response to the upstream reply on issue #1:

- `scriptnz` is not consumed by `doStairs` (write-only side effect of `setZ`).
- `lastZ` (backing field `lz`) is dead state: the only call site of `getLastZ()` writes into `IsoGameCharacter.llz`, and `getLlz()` has no readers in the decompile.

The threading and state-corruption mechanism described below does not hold.

StaircastRP remains as an alternative implementation: per-thread isolation on the read path against potential concurrent reads from background threads (`LightingThread`, async sound, AI workers), traded against a measurable cost on the JIT-hot `IsoMovingObject.getX/Y/Z` getters. This is a defensive design choice, not a fix for a verified bug.

The body below is retained as the historical record of what was filed.

---

## Title

```
Possible stair-climb death: render-pass setX/Y/Z writes nx/scriptnx that the game thread reads next tick
```

## Body

Hi! Thanks for Staircast. The FakeFrameState approach is the cleanest cutaway-perspective trick I've seen for B42, and I've been studying it as the basis for a private fork.

**Up front:** this is a code-path hypothesis, not a confirmed bug. I personally hit a stair-climb death once (sprint + diagonal climb) and couldn't reproduce it deterministically. Reading the source afterwards I found a structural race that fits the "happens sometimes, no clean repro" pattern, but I haven't proven causation. Take everything below as a thing to evaluate, not a verified report.

### Symptom

A small handful of Workshop reports describe the same shape: brief black screen on a stair climb, then "you are dead". At least one of them was posted after the v1.1.0 sprite-NPE patch and after the `recoverFromError` safety net, so it isn't (only) the texture-null path that v1.1.0 addressed. My one personal occurrence matches.

### Suspected mechanism

The mod mutates the `IsoPlayer` instance during the render pass:

```java
// FakeFrameState.java
public static void apply(IsoGameCharacter c, Vector3 pos) {
    c.setX(pos.x);
    c.setY(pos.y);
    c.setZ(pos.z);
}
```

And `IsoMovingObject.setX/setY/setZ` (vanilla):

```java
public float setX(float x) {
    this.x = x;
    this.setNextX(x);          // writes this.nx
    this.setScriptNextX(x);    // writes this.scriptnx
    return this.x;
}
```

So `apply(camChar, fakePos)` doesn't just write three fields, it writes nine: `x, nx, scriptnx, y, ny, scriptny, z, nz, scriptnz`. `nx/scriptnx` are next-tick prediction values that the stair-climb update logic on the game thread reads on the *following* tick to compute the smooth Z transition.

Sequence on a fast climb:

1. Game thread: stair tick advances real `z` from 0.5 → 0.55. `setZ(0.55)` writes `z=0.55, nz=0.55, scriptnz=0.55`.
2. Render thread: `Patch_FBORenderCell.renderInternal` OnEnter snapshots `z=0.55`, calls `apply(camChar, fakePos)` → writes `z=fakeZ (e.g. 1.0), nz=1.0, scriptnz=1.0`.
3. Render runs.
4. Render OnExit restores via the same `setZ()` setter, calling `setZ(0.55)`. There's no `nz/scriptnz` snapshot from step 1 to restore from, and `setZ(0.55)` writes `nz=0.55, scriptnz=0.55`, overwriting the prediction with the position that was current at OnEnter, *not* the prediction that was current at OnEnter. Net effect after the render pass: `z` is correct, but `nz/scriptnz` are one tick behind the prediction the game thread set in step 1.
5. Game thread: next stair tick reads `scriptnz` to compute the next z. Sees stale value → wrong delta → smooth Z desyncs from where the player should be. A few ticks later, logical Z drifts below floor → fall logic kicks in → `setZ(realFloorZ)` clamps. Effectively a feedback loop.
6. If the loop drives Z out of bounds, fall-damage / death triggers.

Whether this actually fires depends on the render pass interleaving with a stair-tick boundary in just the wrong way, which is timing the mod doesn't control. That's consistent with the intermittence anyone hitting it would see.

### Why the current design can't fully restore through `setX/Y/Z`

Both write and restore go through `setX/Y/Z`, which has the side effect of also writing `nx/scriptnx`. There's no setter that touches *only* `this.x`. Even a "snapshot nx/scriptnx and restore them too" approach has a window during the render pass where the game thread can read fake values if the threading happens to overlap (`LightingThread`, `PerformanceSettings.threadSound` etc. schedule work concurrently).

### Proposed fix

Bypass `setX/Y/Z` and reflectively write only the `x/y/z` fields:

```java
// simplified, error handling and field-resolution-failure path elided
private static final Field FIELD_X = IsoMovingObject.class.getDeclaredField("x");
private static final Field FIELD_Y = IsoMovingObject.class.getDeclaredField("y");
private static final Field FIELD_Z = IsoMovingObject.class.getDeclaredField("z");
static { FIELD_X.trySetAccessible(); FIELD_Y.trySetAccessible(); FIELD_Z.trySetAccessible(); }

public static void writeFakePos(IsoGameCharacter c, float x, float y, float z) {
    FIELD_X.setFloat(c, x);
    FIELD_Y.setFloat(c, y);
    FIELD_Z.setFloat(c, z);
}
```

Replaces `apply(IsoGameCharacter, Vector3)`. `nx, scriptnx, ny, scriptny, nz, scriptnz` are never touched, so stair-climb prediction state stays consistent with what the game thread expects.

For the secondary concern about non-render threads reading `getX()` during the render window, a ThreadLocal-gated read-path patch on `IsoMovingObject.getX/Y/Z/getCurrentSquare` works:

```java
// simplified
@Patch.OnEnter(skipOn = true)
public static boolean enter(@Patch.This IsoMovingObject self, @Patch.Local("v") float v) {
    // render thread (ThreadLocal set):       return fake
    // non-render thread + field-mutated:     return saved real
    // otherwise:                              fall through to vanilla
    ...
}
```

The field-write fix alone resolves the death case because `nx/scriptnx` are no longer affected. The ThreadLocal-shadow is icing for visual edge cases. It also fully eliminates the frame-to-frame cutaway flicker I was getting with field-write alone, so worth doing if visual stability matters.

### Working prototype

I built this as a private fork (`StaircastRP`, not for Workshop) to test the proposed fix end-to-end. Tested with sprint-diagonal stair climbs, stairs going up *and* down, cellar↔ground transitions, multiple house types: no deaths in my sessions, no visible regressions I noticed. That's "no regression observed" rather than "race confirmed and fixed", since the original repro is so flaky. At minimum the no-`nx/scriptnx` write path removes that one specific code path from consideration.

Happy to push the source somewhere public if you want to look at the diff, or send a PR.

### Two unrelated minor things noticed while reading

- `Patch.OnExit` advices in this codebase don't set `onThrowable = Throwable.class`, so an exception inside the patched method body skips the restore advice. `recoverFromError` covers it via `try/catch` in OnEnter, but only catches throwables originating *inside* the advice itself, not the patched method body. Setting `onThrowable = Throwable.class` plus a `try/finally` for the restore makes recovery exception-safe without needing the disable-mod cascade.
- `Game.setLastPos` calls `setLastX(x); setLastX(y); setLastX(z)`, three `setLastX`s in a row, looks like a typo for `setLastX/setLastY/setLastZ`.

Thanks again for the mod and for sharing the source.
