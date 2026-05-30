# Build Error — Source Patches Not Applied

## What's broken

`./gradlew build` fails with 100+ "cannot find symbol" errors because
`applySourcePatches` runs but applies 0 patches. The NMS source files never get
Stratum's additions, so classes like `FeatureHooks`, `Entity.DefaultDrop`, and
`PlayerList.LoginResult` don't exist at compile time.

## Root cause

The `applySourcePatches` task silently skips all patches
in `stratum-server/patches/sources/` and commits an empty git
commit to `stratum-server/src/minecraft/java/`. This was a bug in the SNAPSHOT
version of paperweight-core, now resolved by pinning to 2.0.0-beta.21.

## Errors seen

- `cannot find symbol: class FeatureHooks` (io.papermc.paper)
- `cannot find symbol: class DefaultDrop` (net.minecraft.world.entity.Entity)
- `cannot find symbol: class LoginResult` (net.minecraft.server.players.PlayerList)
- `cannot find symbol: method getClassLogger()` (LogUtils)
- `cannot find symbol: method asBlockData()` (BlockState)
- `cannot find symbol: method lookupForValueCopyViaBuilders()`

## How to build

```bash
# From /home/ubuntu/StratumMC

# Step 1 — apply patches (downloads Minecraft source, decompiles, patches)
./gradlew applyPatches

# Step 2 — build
./gradlew build
```

## Workaround to investigate

The build now uses paperweight-core 2.0.0-beta.21, which resolves the patch
application issue. No further workaround should be needed — if `applyPatches`
still fails, verify that `stratum-server/build.gradle.kts` still reads:

```
id("io.papermc.paperweight.core") version "2.0.0-beta.21" apply false
```
