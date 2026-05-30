package mc.stratum.addons;

import mc.stratum.audit.AuditLog;
import mc.stratum.tweaks.TweakSignatureVerifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Discovers, verifies, and manages Stratum addons in the {@code addons/} directory.
 *
 * <p>Each addon JAR must be accompanied by a {@code .sig} file (detached Ed25519 signature).
 * Addons declaring {@code requiresTier2Key: true} in their {@code META-INF/addon.json}
 * must pass verification against the {@code tier2Key} instead of the primary key.
 */
public final class AddonLoader {

    private static final Logger LOGGER = Logger.getLogger("AddonLoader");

    // Current Stratum version for compatibility checks
    private static final int STRATUM_MAJOR_VERSION = 1;
    private static final int STRATUM_MINOR_VERSION = 0;

    private final List<AddonMeta>        installedAddons = new ArrayList<>();
    private final List<AddonMeta>        enabledAddons   = new ArrayList<>();
    private final Map<String, StratumAddon> liveInstances = new HashMap<>();
    private final Map<String, ClassLoader>  classLoaders  = new HashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Scans {@code addonsDir}, verifies each JAR, reads {@code META-INF/addon.json},
     * checks compatibility, and loads enabled addons.
     *
     * @param addonsDir  directory to scan
     * @param primaryKey primary Ed25519 public key (for standard addons)
     * @param tier2Key   tier-2 Ed25519 public key (for premium addons)
     */
    public void loadAll(final File addonsDir,
                        final PublicKey primaryKey,
                        final PublicKey tier2Key) {
        if (!addonsDir.exists()) {
            addonsDir.mkdirs();
            LOGGER.info("[Stratum] No addons/ directory found; created empty one.");
            return;
        }

        final File[] jars = addonsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOGGER.info("[Stratum] No addons found in " + addonsDir.getPath());
            return;
        }

        for (final File jar : jars) {
            loadSingle(jar, primaryKey, tier2Key);
        }

        // Check conflicts after all metadata is collected
        resolveConflicts();
    }

    /**
     * Enables an addon by id at runtime.
     *
     * @param id the addon id
     * @throws IllegalArgumentException if no addon with that id is installed
     * @throws IllegalStateException    if the addon is already enabled
     */
    public void enableAddon(final String id) {
        final AddonMeta meta = findInstalled(id);
        if (meta == null) throw new IllegalArgumentException("No addon installed with id: " + id);
        if (enabledAddons.contains(meta)) throw new IllegalStateException("Addon already enabled: " + id);

        enabledAddons.add(meta);

        // Call onEnable() if the addon implements StratumAddon
        final StratumAddon instance = liveInstances.get(id);
        if (instance != null) {
            try {
                instance.onEnable();
            } catch (Exception ex) {
                LOGGER.severe("[Stratum] Exception in " + id + ".onEnable(): " + ex.getMessage());
            }
        }

        AuditLog.getInstance().log("ADDON_ENABLED id=" + id + " version=" + meta.version());
        LOGGER.info("[Stratum] Enabled addon: " + meta.name() + " v" + meta.version());
    }

    /**
     * Disables an addon by id at runtime.
     *
     * @param id the addon id
     * @throws IllegalArgumentException if no addon with that id is installed
     * @throws IllegalStateException    if the addon is not currently enabled
     */
    public void disableAddon(final String id) {
        final AddonMeta meta = findInstalled(id);
        if (meta == null) throw new IllegalArgumentException("No addon installed with id: " + id);
        if (!enabledAddons.contains(meta)) throw new IllegalStateException("Addon is not enabled: " + id);

        final StratumAddon instance = liveInstances.get(id);
        if (instance != null) {
            try {
                instance.onDisable();
            } catch (Exception ex) {
                LOGGER.severe("[Stratum] Exception in " + id + ".onDisable(): " + ex.getMessage());
            }
        }

        enabledAddons.remove(meta);
        AuditLog.getInstance().log("ADDON_DISABLED id=" + id);
        LOGGER.info("[Stratum] Disabled addon: " + meta.name());
    }

    /** Returns an unmodifiable view of all installed addons (verified, loaded). */
    public List<AddonMeta> getInstalledAddons() {
        return Collections.unmodifiableList(installedAddons);
    }

    /** Returns an unmodifiable view of currently enabled addons. */
    public List<AddonMeta> getEnabledAddons() {
        return Collections.unmodifiableList(enabledAddons);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private void loadSingle(final File jar, final PublicKey primaryKey, final PublicKey tier2Key) {
        final String baseName = jar.getName().replace(".jar", "");
        final File sigFile = new File(jar.getParent(), jar.getName() + ".sig");

        // 1. Read metadata first (no signature check yet) to determine which key to use
        AddonMeta meta;
        try {
            meta = readAddonMeta(jar);
        } catch (Exception ex) {
            LOGGER.warning("[Stratum] Could not read addon.json from " + jar.getName()
                    + ": " + ex.getMessage() + " — skipping.");
            return;
        }

        // 2. Select verification key
        final PublicKey verifyKey = meta.requiresTier2Key() ? tier2Key : primaryKey;

        // 3. Verify signature
        if (!TweakSignatureVerifier.verify(jar, sigFile, verifyKey)) {
            LOGGER.warning("[Stratum] REJECTED addon " + meta.name() + ": signature verification failed");
            AuditLog.getInstance().log("ADDON_REJECTED id=" + meta.id() + " reason=signature_failed");
            return;
        }

        // 4. Compatibility check
        if (!isCompatible(meta)) {
            LOGGER.warning("[Stratum] Addon " + meta.name() + " requires Stratum "
                    + meta.minStratumVersion() + " but this is Stratum"
                    + STRATUM_MAJOR_VERSION + "." + STRATUM_MINOR_VERSION + " — skipping.");
            return;
        }

        // 5. If tagged as "plugin", install into plugins/ folder and skip normal loading
        if (meta.tags() != null && meta.tags().contains("plugin")) {
            installAsPlugin(jar, meta);
            installedAddons.add(meta);
            return;
        }

        // 6. Load classloader and register
        try {
            final URLClassLoader cl = new URLClassLoader(
                    new URL[]{jar.toURI().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );
            classLoaders.put(meta.id(), cl);

            // Attempt to instantiate main class if it implements StratumAddon
            tryInstantiateAddon(meta, cl);

            installedAddons.add(meta);
            LOGGER.info("[Stratum] Loaded addon: " + meta.name() + " v" + meta.version()
                    + " by " + meta.author());
        } catch (Exception ex) {
            LOGGER.severe("[Stratum] Failed to load addon JAR " + jar.getName()
                    + ": " + ex.getMessage());
        }
    }

    private static void installAsPlugin(final File jar, final AddonMeta meta) {
        final File pluginsDir = new File("plugins");
        pluginsDir.mkdirs();
        final File dest = new File(pluginsDir, jar.getName());
        try {
            Files.copy(jar.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AuditLog.getInstance().log("ADDON_PLUGIN_INSTALLED id=" + meta.id() + " dest=" + dest.getPath());
            LOGGER.info("[Stratum] Installed addon as plugin: " + meta.name() + " → plugins/" + jar.getName());
        } catch (IOException ex) {
            LOGGER.severe("[Stratum] Failed to install addon as plugin: " + ex.getMessage());
        }
    }

    private static AddonMeta readAddonMeta(final File jar) throws IOException {
        try (JarFile jf = new JarFile(jar)) {
            final JarEntry entry = jf.getJarEntry("META-INF/addon.json");
            if (entry == null) throw new IOException("META-INF/addon.json not found in " + jar.getName());
            try (InputStream in = jf.getInputStream(entry)) {
                final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return AddonMeta.fromJson(json);
            }
        }
    }

    private static boolean isCompatible(final AddonMeta meta) {
        final String min = meta.minStratumVersion();
        if (min == null) return true;

        // Parse "Stratum1.3" → major=1, minor=3
        try {
            final String stripped = min.replace("Stratum", "");
            final String[] parts  = stripped.split("\\.");
            final int minMajor = Integer.parseInt(parts[0]);
            final int minMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (STRATUM_MAJOR_VERSION < minMajor) return false;
            if (STRATUM_MAJOR_VERSION == minMajor && STRATUM_MINOR_VERSION < minMinor) return false;
        } catch (NumberFormatException ignored) {
            // Cannot parse — assume compatible
        }

        final String max = meta.maxStratumVersion();
        if (max != null) {
            try {
                final String stripped = max.replace("Stratum", "");
                final String[] parts  = stripped.split("\\.");
                final int maxMajor = Integer.parseInt(parts[0]);
                final int maxMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : Integer.MAX_VALUE;
                if (STRATUM_MAJOR_VERSION > maxMajor) return false;
                if (STRATUM_MAJOR_VERSION == maxMajor && STRATUM_MINOR_VERSION > maxMinor) return false;
            } catch (NumberFormatException ignored) {}
        }

        return true;
    }

    private void tryInstantiateAddon(final AddonMeta meta, final ClassLoader cl) {
        // Addon JARs may declare their main class via META-INF/MANIFEST.MF Main-Class
        // or a conventional class named after the id. We try a best-effort approach.
        final String[] candidates = {
                "mc.addon." + toCamelCase(meta.id()) + "Addon",
                "addon." + toCamelCase(meta.id()),
                meta.id()
        };
        for (final String className : candidates) {
            try {
                final Class<?> cls = Class.forName(className, true, cl);
                if (StratumAddon.class.isAssignableFrom(cls)) {
                    final StratumAddon addon = (StratumAddon) cls.getDeclaredConstructor().newInstance();
                    liveInstances.put(meta.id(), addon);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    private void resolveConflicts() {
        final List<AddonMeta> toRemove = new ArrayList<>();
        for (final AddonMeta meta : installedAddons) {
            for (final String conflict : meta.conflicts()) {
                if (findInstalled(conflict) != null) {
                    LOGGER.warning("[Stratum] Addon " + meta.id()
                            + " conflicts with " + conflict + " — disabling " + meta.id());
                    toRemove.add(meta);
                    break;
                }
            }
        }
        installedAddons.removeAll(toRemove);
    }

    private AddonMeta findInstalled(final String id) {
        return installedAddons.stream()
                .filter(m -> m.id().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);
    }

    private static String toCamelCase(final String id) {
        final StringBuilder sb = new StringBuilder();
        for (final String part : id.split("[-_]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }
}
