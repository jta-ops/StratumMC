package mc.stratum.tweaks;

import mc.stratum.audit.AuditLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Discovers, verifies, and loads Stratum tweak JARs from the {@code tweaks/} directory.
 *
 * <p>Each JAR must be accompanied by a {@code .sig} file (detached Ed25519 signature).
 * JARs without a valid signature are rejected and never loaded.
 */
public final class TweakLoader {

    private static final Logger LOGGER = Logger.getLogger("TweakLoader");

    private final List<TweakManifest.TweakEntry> loadedTweaks = new ArrayList<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Scans {@code tweaksDir} for {@code .jar} files, verifies each one's Ed25519
     * signature (detached {@code .sig} file), and loads verified JARs with an
     * isolated {@link URLClassLoader}.
     *
     * @param tweaksDir       directory to scan
     * @param verificationKey public key used for signature verification
     */
    public void loadAll(final File tweaksDir, final PublicKey verificationKey) {
        if (!tweaksDir.exists()) {
            tweaksDir.mkdirs();
            LOGGER.info("[Stratum] No tweaks/ directory found; created empty one.");
            return;
        }

        final File[] jars = tweaksDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOGGER.info("[Stratum] No tweaks found in " + tweaksDir.getPath());
            return;
        }

        for (final File jar : jars) {
            loadSingle(jar, verificationKey);
        }
    }

    /**
     * Fetches the remote tweak manifest from {@code apiUrl/manifest}.
     *
     * @param apiUrl base URL of the Stratum API (no trailing slash)
     * @return parsed {@link TweakManifest}, or {@code null} on failure
     */
    public TweakManifest fetchManifest(final String apiUrl) {
        final String url = apiUrl.endsWith("/") ? apiUrl + "manifest" : apiUrl + "/manifest";
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            final HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warning("[Stratum] Manifest fetch returned HTTP " + response.statusCode());
                return null;
            }
            return TweakManifest.fromJson(response.body());
        } catch (IOException | InterruptedException ex) {
            LOGGER.warning("[Stratum] Could not fetch tweak manifest from " + url + ": " + ex.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Downloads the JAR and signature for a {@link TweakManifest.TweakEntry} into
     * {@code tweaksDir}, verifying the signature before persisting.
     *
     * @param entry     the tweak entry to download
     * @param tweaksDir destination directory
     * @param key       public key for verification
     * @return {@code true} if the tweak was successfully downloaded and verified
     */
    public boolean downloadTweak(final TweakManifest.TweakEntry entry,
                                  final File tweaksDir,
                                  final PublicKey key) {
        tweaksDir.mkdirs();

        final Path jarPath = tweaksDir.toPath().resolve(entry.id() + ".jar");
        final Path sigPath = tweaksDir.toPath().resolve(entry.id() + ".jar.sig");

        // Download JAR
        if (!downloadFile(entry.downloadUrl(), jarPath)) {
            LOGGER.warning("[Stratum] Failed to download JAR for tweak: " + entry.name());
            return false;
        }

        // Write detached signature from manifest field
        try {
            Files.writeString(sigPath, entry.signature());
        } catch (IOException ex) {
            LOGGER.warning("[Stratum] Could not write sig file for " + entry.name() + ": " + ex.getMessage());
            return false;
        }

        // Verify before accepting
        if (!TweakSignatureVerifier.verify(jarPath.toFile(), sigPath.toFile(), key)) {
            LOGGER.warning("[Stratum] REJECTED tweak " + entry.name() + ": signature verification failed");
            AuditLog.getInstance().log("TWEAK_REJECTED id=" + entry.id() + " name=" + entry.name()
                    + " reason=signature_mismatch");
            silentDelete(jarPath);
            silentDelete(sigPath);
            return false;
        }

        LOGGER.info("[Stratum] Downloaded and verified tweak: " + entry.name());
        return true;
    }

    /** Returns an unmodifiable view of the currently loaded tweak entries. */
    public List<TweakManifest.TweakEntry> getLoadedTweaks() {
        return Collections.unmodifiableList(loadedTweaks);
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private void loadSingle(final File jar, final PublicKey key) {
        final String baseName = jar.getName().replace(".jar", "");
        final File sigFile = new File(jar.getParent(), jar.getName() + ".sig");

        if (!TweakSignatureVerifier.verify(jar, sigFile, key)) {
            LOGGER.warning("[Stratum] REJECTED tweak " + baseName + ": signature verification failed");
            AuditLog.getInstance().log("TWEAK_REJECTED jar=" + jar.getName()
                    + " reason=signature_verification_failed");
            return;
        }

        try {
            // Isolated class loader — tweaks run in their own namespace
            final URLClassLoader cl = new URLClassLoader(
                    new URL[]{jar.toURI().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );

            // Build a synthetic TweakEntry from the jar name (real metadata lives in manifest)
            final TweakManifest.TweakEntry entry = new TweakManifest.TweakEntry(
                    baseName, baseName, "Stratum1.x", "", ""
            );
            loadedTweaks.add(entry);

            LOGGER.info("[Stratum] Loaded tweak " + entry.stratumVersion() + ": " + entry.name());
            AuditLog.getInstance().log("TWEAK_LOADED jar=" + jar.getName());

        } catch (Exception ex) {
            LOGGER.severe("[Stratum] Failed to load tweak JAR " + jar.getName() + ": " + ex.getMessage());
        }
    }

    private static boolean downloadFile(final String urlStr, final Path destination) {
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            final HttpResponse<InputStream> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return false;
            try (InputStream in = resp.body()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void silentDelete(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
