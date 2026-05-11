package pzmod.staircastrp;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicIntegerArray;

import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCamera;
import zombie.iso.IsoMovingObject;

/**
 * Per-player fake-render window registry.
 *
 * <p><b>Three-layer state:</b>
 * <ul>
 *   <li>{@link #data}: per-player FakeFrameState filled by Patch_IsoWorld.</li>
 *   <li>{@link #renderingFake}: ThreadLocal pointer set by render-pass patches
 *       on enter, cleared on exit. Read-path patches consult it to decide
 *       whether the calling thread is mid-render.</li>
 *   <li>{@link #fieldMutated}: per-player flag set when the outer render-pass
 *       has reflectively written fake x/y/z onto the camChar's private fields.
 *       Read-path patches use this combined with thread context to decide:
 *       <ul>
 *         <li>render thread: ThreadLocal set → return fake (the upper-floor Z).</li>
 *         <li>non-render thread: ThreadLocal null but fieldMutated.get(idx)==1 →
 *             return saved real value (so concurrent reads from background
 *             threads such as LightingThread, async sound, AI workers don't
 *             observe fake values during the render window).</li>
 *         <li>otherwise: skip, vanilla getter returns this.x as usual.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>The reason for the field write at all: PZ's render code mixes getter
 * calls with direct-field reads on x/y/z (e.g. IsoCell.IsCutawaySquare).
 * Direct-field reads bypass the getter patch and would see real values for
 * an entire frame while render code via getter sees fake, producing visible
 * cutaway flicker on stairs. Writing the field directly makes all reads see
 * fake DURING the render window; the read-path patch then re-isolates
 * background threads (LightingThread, async sound, AI workers) by returning
 * the saved real value when fieldMutated.get(idx) == 1 and the caller is not
 * the render thread.
 */
public final class FakeWindow {
    public static final int MAX_PLAYERS = 4;

    public static final FakeFrameState[] data = new FakeFrameState[MAX_PLAYERS];

    public static final ThreadLocal<FakeFrameState> renderingFake = new ThreadLocal<>();

    /**
     * Per-player flag indicating that the camChar's x/y/z private fields have
     * been reflectively mutated to fake values. Cleared on render-pass exit.
     *
     * <p>AtomicIntegerArray (not boolean[]) for cross-thread happens-before.
     * Array elements are never volatile even if the reference is. Without a
     * release/acquire edge a non-render thread could read the flag still == 0
     * after the render thread set it to 1, miss the read-path shadow, and
     * observe the fake field via the vanilla getter, defeating the per-thread
     * isolation that the shadow provides.
     *
     * <p>{@code set(idx, 1)} / {@code set(idx, 0)} provide volatile-write
     * semantics; {@code get(idx)} is volatile-read. The release on
     * {@code set(idx, 1)} also publishes the FakeFrameState mutations done by
     * Patch_IsoWorld earlier in the frame.
     */
    public static final AtomicIntegerArray fieldMutated = new AtomicIntegerArray(MAX_PLAYERS);

    private static Field FIELD_X;
    private static Field FIELD_Y;
    private static Field FIELD_Z;

    static {
        try {
            FIELD_X = IsoMovingObject.class.getDeclaredField("x");
            FIELD_Y = IsoMovingObject.class.getDeclaredField("y");
            FIELD_Z = IsoMovingObject.class.getDeclaredField("z");
            FIELD_X.trySetAccessible();
            FIELD_Y.trySetAccessible();
            FIELD_Z.trySetAccessible();
        } catch (NoSuchFieldException e) {
            FIELD_X = null;
            FIELD_Y = null;
            FIELD_Z = null;
        }
    }

    public static FakeFrameState getOrAllocate(int playerIndex) {
        FakeFrameState ffs = data[playerIndex];
        if (ffs == null) {
            ffs = new FakeFrameState();
            data[playerIndex] = ffs;
        }
        return ffs;
    }

    public static FakeFrameState get(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= MAX_PLAYERS) return null;
        return data[playerIndex];
    }

    public static boolean isReady(int playerIndex) {
        FakeFrameState ffs = get(playerIndex);
        return ffs != null && ffs.frameCounter == IsoCamera.frameState.frameCount;
    }

    /** Reflectively writes x/y/z fields on the camChar, skipping setX/Y/Z's
     *  side effects on nx, scriptnx, lx, ly, lz. Returns true if the write
     *  succeeded. */
    public static boolean writeFakePos(IsoGameCharacter camChar, float x, float y, float z) {
        if (FIELD_X == null) return false;
        try {
            FIELD_X.setFloat(camChar, x);
            FIELD_Y.setFloat(camChar, y);
            FIELD_Z.setFloat(camChar, z);
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    /** Restore real x/y/z to the camChar fields. */
    public static void writeRealPos(IsoGameCharacter camChar, float x, float y, float z) {
        if (FIELD_X == null) return;
        try {
            FIELD_X.setFloat(camChar, x);
            FIELD_Y.setFloat(camChar, y);
            FIELD_Z.setFloat(camChar, z);
        } catch (IllegalAccessException ignored) {
        }
    }

    /**
     * Returns the FakeFrameState whose fields are currently mutated and whose
     * camChar matches {@code self}. Used by read-path patches on the
     * non-render thread to return the saved-real value so update logic doesn't
     * see fake. Returns null if no field mutation is active for self.
     */
    public static FakeFrameState findMutatedFor(IsoMovingObject self) {
        if (self == null) return null;
        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (fieldMutated.get(i) == 0) continue;
            FakeFrameState ffs = data[i];
            if (ffs != null && ffs.camChar == self) return ffs;
        }
        return null;
    }
}
