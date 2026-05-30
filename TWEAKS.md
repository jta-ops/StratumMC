# Writing, Signing, and Publishing Tweaks

## What is a tweak?

A tweak is a signed JAR (`Stratum1.N`) that is automatically loaded by the server on startup. Tweaks are always-on — they cannot be disabled by server operators. They are effectively part of the core server code and get folded into core on a major version bump.

## Tweak JAR structure

```
my-tweak.jar
├── META-INF/
│   └── tweak.properties     ← required metadata
└── mc/stratum/tweaks/impl/
    └── MyTweak.class        ← your code
```

**tweak.properties:**
```properties
id=my-tweak
name=My Tweak
version=Stratum1.3
```

## Writing a tweak

Tweaks are plain Java classes. They are loaded with an isolated `URLClassLoader` with the server's classloader as parent, so they have full access to Bukkit/Paper APIs and NMS.

```java
package mc.stratum.tweaks.impl;

public class MyTweak {
    static {
        // Code here runs at load time
        org.bukkit.Bukkit.getLogger().info("[Stratum] MyTweak loaded");
    }
}
```

## Building and signing

```bash
# Compile and package
javac -cp stratum-server.jar -d out src/mc/stratum/tweaks/impl/MyTweak.java
jar cf my-tweak.jar -C out . META-INF/

# Sign
openssl pkeyutl -sign -inkey stratum_private.pem -rawin \
    -in my-tweak.jar | base64 > my-tweak.jar.sig
```

## Publishing via the API

Place the signed JAR and .sig file in your `stratum-api-server` data directory:

```
data/tweaks/my-tweak.jar
data/tweaks/my-tweak.jar.sig
```

The API manifest will automatically include it. Servers with `tweaks.auto-fetch: true` will download and load it on next restart.

## Incrementing the version

Bump `version=Stratum1.4` in tweak.properties. The previous tweak remains loaded until the server restarts with the new version.
