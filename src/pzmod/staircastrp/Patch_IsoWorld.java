package pzmod.staircastrp;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.Vector3;

public class Patch_IsoWorld {

    /**
     * Hysteresis: once a frame strictly passes all activation checks,
     * subsequent frames within this window are allowed to stay activated even
     * if a soft check (cone, head-Z) briefly fails. 30 frames @ 60 FPS = 0.5 s
     * of grace, enough to ride out animation key-pose oscillations on stairs
     * (head bob, idle sway) without re-triggering the cutaway state-machine
     * per frame.
     */
    private static final int HYSTERESIS_FRAMES = 30;

    private static Field FIELD_DRAW_WORLD;

    static {
        try {
            FIELD_DRAW_WORLD = IsoWorld.class.getDeclaredField("drawWorld");
            FIELD_DRAW_WORLD.trySetAccessible();
        } catch (NoSuchFieldException e) {
            FIELD_DRAW_WORLD = null;
        }
    }

    public static boolean readDrawWorld(IsoWorld world) {
        if (FIELD_DRAW_WORLD == null) return true;
        try {
            return FIELD_DRAW_WORLD.getBoolean(world);
        } catch (Exception e) {
            return true;
        }
    }

    @Patch(className = "zombie.iso.IsoWorld", methodName = "renderInternal")
    public static class Patch_renderInternal {
        @Patch.OnEnter
        public static void enter(@Patch.This IsoWorld self) {
            try {
                computeFake(self);
            } catch (Throwable t) {
                Mod.instance.log("Patch_IsoWorld.renderInternal failed: " + t);
                t.printStackTrace();
            }
        }

        public static void computeFake(IsoWorld self) {
            // Strict checks — fail without hysteresis (mod off, char gone,
            // pause menu, etc.). These define whether fake-render is even
            // applicable, not just whether activation conditions are met.
            if (!Mod.instance.enabled) return;
            IsoGameCharacter camChar = IsoCamera.getCameraCharacter();
            if (camChar == null) return;
            if (!readDrawWorld(self)) return;

            IsoCamera.FrameState fs = IsoCamera.frameState;
            IsoGridSquare square = fs.camCharacterSquare;
            if (square == null) return;
            if (camChar.getVehicle() != null) return;
            if (!camChar.hasActiveModel()) return;

            int playerIndex = fs.playerIndex;
            FakeFrameState ffs = FakeWindow.get(playerIndex);
            boolean recentlyActive = ffs != null
                    && ffs.lastStrictActivationFrame >= 0
                    && (fs.frameCount - ffs.lastStrictActivationFrame) <= HYSTERESIS_FRAMES;

            float charX = fs.camCharacterX;
            float charY = fs.camCharacterY;
            float charZ = fs.camCharacterZ;

            // Soft checks — boundary conditions that can wobble at sub-frame.
            // Track failure but don't return yet; let hysteresis decide.
            boolean strictPass = true;
            if ((float) PZMath.fastfloor(charZ + 0.55f) < charZ) strictPass = false;
            if (strictPass && !square.HasElevatedFloor()) strictPass = false;

            boolean stairsNorth = square.HasStairsNorth();
            float heading = PZMath.wrap(
                    camChar.getLookAngleRadians() - (stairsNorth ? (float) Math.PI : 1.5707964f),
                    (float) Math.PI * 2);
            float cone = Math.max(0.5f, 1.1780972f * (1.8181818f * PZMath.frac(charZ + 0.55f)));
            if (strictPass && heading > (float) Math.PI + cone && heading < (float) Math.PI * 2 - cone) {
                strictPass = false;
            }

            if (!strictPass && !recentlyActive) return;

            // Try to compute fresh floor/target squares from the current
            // (possibly non-stair) tile. If the geometry isn't there, fall
            // back to last frame's squares — only acceptable inside the
            // hysteresis window.
            IsoGridSquare floorSquare = null;
            IsoGridSquare targetSquare = null;
            boolean stairTop = false;
            boolean stairMid = false;

            if (square.HasElevatedFloor()) {
                stairTop = square.has(IsoObjectType.stairsTN) || square.has(IsoObjectType.stairsTW);
                stairMid = square.has(IsoObjectType.stairsMN) || square.has(IsoObjectType.stairsMW);
                int topOffset = stairTop ? 1 : (stairMid ? 2 : 3);
                floorSquare = camChar.getCell().getGridSquare(
                        square.x - (stairsNorth ? 0 : topOffset),
                        square.y - (stairsNorth ? topOffset : 0),
                        square.z + 1);
                IsoGridSquare upperSquare = square.getCell().getGridSquare(square.x, square.y, square.z + 1);
                targetSquare = upperSquare != null ? upperSquare : floorSquare;
            }
            if (floorSquare == null || targetSquare == null) {
                if (!recentlyActive || ffs.floorSquare == null || ffs.fakeSquare == null) return;
                floorSquare = ffs.floorSquare;
                targetSquare = ffs.fakeSquare;
                strictPass = false;
            }

            // Viewpoint Z snap — head bone gives the actual vertical eye
            // position through the stair animation. Snap onto last-frame value
            // if the delta is sub-threshold to avoid jitter at the threshold
            // crossing.
            Vector3 headPos = new Vector3();
            zombie.CombatManager.getBoneWorldPos((IsoMovingObject) camChar, "Bip01_Head", headPos);
            headPos.z += 0.05f;
            float headZ = (ffs != null && Math.abs(headPos.z - ffs.lastViewpointZ) <= 0.02f)
                    ? ffs.lastViewpointZ
                    : headPos.z;
            if (PZMath.fastfloor(headZ) < targetSquare.z) {
                if (!recentlyActive) return;
                strictPass = false;
            }

            ffs = FakeWindow.getOrAllocate(playerIndex);

            // Stair-top lighting on, except if a zombie sits on a nearby stair.
            boolean renderLighting = stairTop;
            if (renderLighting && PZMath.fastfloor(charZ + 0.11f) < targetSquare.z) {
                outer:
                for (int y = square.y - 1; y <= square.y + 1; ++y) {
                    for (int x = square.x - 1; x <= square.x + 1; ++x) {
                        IsoGridSquare s = camChar.getCell().getGridSquare(x, y, square.z);
                        if (s != null && s.HasStairs() && s.getZombie() != null) {
                            renderLighting = false;
                            break outer;
                        }
                    }
                }
            }

            ffs.camChar = camChar;
            ffs.realPos.set(charX, charY, charZ);
            ffs.realSquare = square;
            ffs.floorSquare = floorSquare;
            ffs.fakeSquare = targetSquare;
            ffs.fakePos.set(charX, charY, (float) targetSquare.getZ());
            ffs.lastViewpointZ = headPos.z;
            ffs.renderLighting = renderLighting;
            ffs.frameCounter = fs.frameCount;
            if (strictPass) {
                ffs.lastStrictActivationFrame = fs.frameCount;
            }
        }
    }
}
