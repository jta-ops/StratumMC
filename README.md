# Stratum MC

[![Build](https://img.shields.io/github/actions/workflow/status/jta-ops/StratumMC/build.yml?branch=main&label=build)](https://github.com/jta-ops/StratumMC/actions)
[![License](https://img.shields.io/github/license/jta-ops/StratumMC)](LICENSE.md)

**Stratum** is a high-performance Minecraft server fork built on [Paper](https://github.com/PaperMC/Paper), designed for public server networks. It ships with a signed plugin/tweak system, a safe self-update mechanism via a separate launcher process, and first-class operator tooling.

# **[STRATUM SITE](https://stratumserver.net)**

---

## What makes Stratum different

| Feature | Description |
|---|---|
| **Tweaks** | Always-on signed code modules (`Stratum1.N`) pulled from the Stratum API. Patch-like, never disableable, folded into core on a major release. |
| **Addons** | Opt-in, owner-toggleable features signed and distributed via the Stratum API. |
| **Safe updates** | `/ST update` — passive, immediate, or scheduled. The server never overwrites its own running JAR; all swapping is done by `stratum-launcher`. |
| **Signed everything** | All tweaks, addons, and release JARs are Ed25519-signed. Nothing loads without verification. |
| **Metrics** | Optional Prometheus-format `/metrics` endpoint. |

---

## Version matrix

Each Minecraft protocol version is a separate build. Client-version bridging is done via [ViaVersion](https://github.com/ViaVersion/ViaVersion) plugins — the JAR itself is locked to one version.

| Stratum branch | Minecraft version |
|---|---|
| `main` | 26.1.2 |
| `1.21.5` | 1.21.5 |
| `1.21.6` | 1.21.6 |

---

## Three components

```
stratum-server      — the Minecraft server JAR (this repo, paper-server/)
stratum-launcher    — supervisor that launches the server and swaps JARs
stratum-api-server  — REST backend: version metadata, signed tweaks & addons
```

---

## Building

> **Prerequisites:** JDK 21+, Git

```bash
git clone https://github.com/jta-ops/StratumMC
cd StratumMC
./gradlew applyPatches
./gradlew build
# Output: paper-server/build/libs/stratum-*-paperclip-*.jar
```

Releases are built and signed automatically by GitHub Actions on each tagged push.

---

## Running

```bash
# Recommended: use the launcher so updates work safely
java -jar stratum-launcher.jar stratum-server.jar

# Or directly (no self-update support):
java @aikar-flags.txt -jar stratum-server.jar --nogui
```

See `aikar-flags.txt` for recommended JVM flags.

---

## Commands

| Command | Description |
|---|---|
| `/ST version` | Show version, build, and git commit |
| `/ST tweaks` | List active tweaks (read-only) |
| `/ST addons list` | List installed addons |
| `/ST addons enable/disable <id>` | Toggle an addon |
| `/ST update` | Passive update (waits for empty server) |
| `/ST update now` | Immediate update (kicks all players) |
| `/ST update <time>` | Scheduled update (e.g. `5m`, `30s`) |
| `/ST diag` | Live health report |
| `/ST preset <type>` | Apply config preset (`survival`, `skyblock`, `minigame`) |
| `/ST migrate` | Detect and port Paper/Purpur config |

Permission node: `stratum.admin` for update/diag/addon management, `stratum.preset` for presets.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: all server changes go through patches.

```bash
# Make your change in paper-server/src/
./gradlew rebuildPatches
git commit -m "feat: my change"
```

---

## Security & signing

See [SECURITY.md](SECURITY.md) for the full trust model, key management, and how to verify a release.
