package pzmod.staircastrp;

import zombie.debug.DebugLog;
import zombie.debug.DebugType;

public final class Mod {
    public static final Mod instance = new Mod();

    public volatile boolean enabled = true;

    public void init() {
        DebugLog.log(DebugType.Mod, "StaircastRP: init");
    }

    public void log(String msg) {
        DebugLog.log(DebugType.Mod, "StaircastRP: " + msg);
    }
}
