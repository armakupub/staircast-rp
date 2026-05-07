package pzmod.staircastrp;

import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoGridSquare;
import zombie.iso.Vector3;

public final class FakeFrameState {
    public final Vector3 realPos = new Vector3();
    public final Vector3 fakePos = new Vector3();
    public IsoGameCharacter camChar;
    public IsoGridSquare realSquare;
    public IsoGridSquare fakeSquare;
    public IsoGridSquare floorSquare;
    public int frameCounter = -1;
    /** Last frame where ALL activation checks (cone, head-Z, fastfloor) passed.
     *  Used for hysteresis: if a single frame's boundary check fails but we
     *  were active recently, keep the fake-window open with current-frame data
     *  instead of toggling off. Eliminates the per-frame on/off "ghost jumping"
     *  flicker caused by camera/animation wobble at half-Z points. */
    public int lastStrictActivationFrame = -1;
    public float lastViewpointZ;
    public boolean renderLighting;
}
