# Contributing to Stratum MC

## The patch workflow

All server modifications go through Paper's patch system. **Never edit decompiled Mojang source directly** — changes must be committed as patches.

### Making a change

```bash
# 1. Apply patches to get working source
./gradlew applyPatches

# 2. Make your change inside paper-server/src/
#    (CraftBukkit/Paper code lives here)

# 3. Rebuild patches from your changes
./gradlew rebuildPatches

# 4. Commit the resulting .patch files
git add paper-server/patches/
git commit -m "feat: describe your change"
```

### Adding a new Stratum feature

New Stratum-specific classes belong in `mc.stratum.*` packages under `paper-server/patches/features/`. Number your patch file `0110-My-Feature.patch` (continuing from the last Stratum patch).

### Patch file naming

```
paper-server/patches/features/0102-Stratum-ST-command.patch
paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch
```

- `features/` — new files added to `paper-server/src/main/java/`
- `sources/`  — modifications to decompiled Minecraft/CraftBukkit source

### Building

```bash
./gradlew applyPatches && ./gradlew build
```

CI runs automatically on every push to `main` and `dev`.

## Code style

- Java 21
- Follow existing Paper conventions for NMS patches
- Stratum-specific code: `mc.stratum.*` packages
- No Mojang code in the repo — patches only

## Submitting a PR

1. Fork the repo
2. Create a branch: `git checkout -b feat/my-feature`
3. Push your patch commits
4. Open a PR against `main`

CI must pass before merge.
