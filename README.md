# Staircast RP

Project Zomboid mod, alternative implementation of the [Staircast](https://github.com/copiumsawsed/pz-Staircast) cutaway technique. **RP** stands for **Read-Path**: instead of mutating live player state during the render pass (`setX/setY/setZ`, which also writes `nx/scriptnx` prediction fields read by the game-thread stair-climb logic), this mod reflectively writes only the bare `x/y/z` fields and shadows real values back to non-render threads via a ThreadLocal-gated read-path on `IsoMovingObject.getX/Y/Z/getCurrentSquare`.

## Status

Source-available. Not published on Steam Workshop.

## Requirements

- Project Zomboid Build 42.13 or newer
- [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) for Java bytecode patching

## Build

```sh
cp build.local.example build.local
# edit build.local: PZ_DIR, JDK_DIR
bash build.sh
```

`build.sh` compiles, packages, and installs to `~/Zomboid/mods/StaircastRP/`. PZ must be closed during build (the script aborts otherwise).

## Hard-incompatible with upstream Staircast

Both mods register ZombieBuddy `@Patch` advices on the same render-path classes (`IsoPlayer.render`, `IsoCell.renderInternal`, `FBORenderCell.*`, `FBORenderTrees.init`, `IsoMovingObject` getters, etc.). Running both stacks reflective field writes and ThreadLocal state and is unsafe.

`mod.info` declares `incompatible=Staircast`, so PZ's mod manager makes one unselectable when the other is active.

## Attribution

Inspired by and tested against [`copiumsawsed/pz-Staircast`](https://github.com/copiumsawsed/pz-Staircast) (MIT). The cutaway-on-stairs idea, the FakeFrameState pattern, and the patched PZ classes are from the upstream. The reflective field-write fix and the ThreadLocal-gated read-path are this mod's contribution; full reasoning in `UPSTREAM_ISSUE.md`.

## License

MIT, see `LICENSE`.
