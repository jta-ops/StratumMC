# Writing, Signing, and Publishing Addons

## What is an addon?

An addon is a signed, opt-in feature module that server owners can enable or disable. Unlike tweaks, addons are chosen by the operator — they don't load automatically.

## Addon JAR structure

```
my-addon.jar
├── META-INF/
│   └── addon.json           ← required metadata
└── mc/stratum/addons/impl/
    └── MyAddon.class
```

**addon.json:**
```json
{
  "id": "my-addon",
  "name": "My Addon",
  "description": "Does something cool",
  "version": "1.0.0",
  "author": "YourName",
  "mainClass": "mc.stratum.addons.impl.MyAddon",
  "minStratumVersion": "1.0",
  "maxStratumVersion": null,
  "conflicts": [],
  "requiresTier2Key": false
}
```

## Implementing StratumAddon

```java
package mc.stratum.addons.impl;

import mc.stratum.addons.AddonMeta;
import mc.stratum.addons.StratumAddon;

public class MyAddon implements StratumAddon {
    @Override public void onEnable() {
        org.bukkit.Bukkit.getLogger().info("[MyAddon] Enabled");
        // register listeners, commands, etc.
    }
    @Override public void onDisable() {
        org.bukkit.Bukkit.getLogger().info("[MyAddon] Disabled");
    }
    @Override public AddonMeta getMeta() { return null; } // read from addon.json at runtime
}
```

## Compatibility and conflicts

- `minStratumVersion` / `maxStratumVersion` — version range the addon supports
- `conflicts` — list of addon IDs this addon cannot coexist with
- `requiresTier2Key` — set `true` for addons that touch networking or file I/O (requires additional tier-2 signature)

## Signing

```bash
# Standard addon
openssl pkeyutl -sign -inkey stratum_private.pem -rawin \
    -in my-addon.jar | base64 > my-addon.jar.sig

# Tier-2 sensitive addon (requires BOTH signatures)
openssl pkeyutl -sign -inkey stratum_private.pem -rawin \
    -in my-addon.jar | base64 > my-addon.jar.sig
openssl pkeyutl -sign -inkey stratum_tier2_private.pem -rawin \
    -in my-addon.jar | base64 > my-addon.jar.tier2.sig
```

## Publishing

Place files in your API server data directory:

```
data/addons/my-addon.jar
data/addons/my-addon.jar.sig
```

Operators install addons with:
```
/ST addons enable my-addon
```
