package mc.stratum.launcher;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.*;

/**
 * Stratum Launcher — the lightweight {@code Stratum.jar}.
 *
 * <p>Distributed under the same file name as the full server jar, so the
 * startup command never changes ({@code java -jar Stratum.jar}). On first run
 * it installs everything it needs:
 *
 * <ol>
 *   <li>Downloads the latest Stratum server jar from the official build API
 *       (kept as {@code stratum-server.jar}, never overwriting itself)</li>
 *   <li>Installs the StratumBootstrap plugin into {@code plugins/}</li>
 * </ol>
 *
 * <p>It then supervises the server process: performs signed JAR swaps when the
 * server schedules an update ({@code UPDATE_SIGNAL}), and restarts the server
 * after a crash (with backoff, max 3 attempts within 10 minutes).
 *
 * The server never overwrites its own running JAR. All swapping happens here.
 *
 * Usage: java -jar Stratum.jar [server.jar] [-- jvm args...]
 */
public final class StratumLauncher {

    private static final Logger LOG = Logger.getLogger("StratumLauncher");
    private static final Path SIGNAL_FILE = Path.of("UPDATE_SIGNAL");
    private static final Path PENDING_JAR = Path.of("stratum-server-pending.jar");
    private static final Path ACTIVE_JAR  = Path.of("stratum-server.jar");
    private static final Path KEY_FILE    = Path.of("stratum_public_key.pem");
    private static final Path PLUGIN_JAR  = Path.of("plugins/StratumBootstrap.jar");

    private static final String API_BASE = System.getProperty("stratum.api", "https://stratumserver.net");
    private static final String SERVER_DOWNLOAD = API_BASE + "/api/builds/latest/download";
    private static final String PLUGIN_DOWNLOAD = API_BASE + "/downloads/StratumBootstrap.jar";

    private static final int MAX_CRASH_RESTARTS = 3;
    private static final long CRASH_WINDOW_MILLIS = 10 * 60 * 1000L;

    public static void main(String[] args) throws Exception {
        configureLogging();
        LOG.info("Stratum Launcher starting...");

        String serverJar = args.length > 0 && !args[0].equals("--") ? args[0] : ACTIVE_JAR.toString();

        installIfMissing(Path.of(serverJar));

        final List<Long> crashTimes = new ArrayList<>();
        while (true) {
            LOG.info("Starting server: " + serverJar);
            Process proc = launchServer(serverJar, args);
            int exitCode = proc.waitFor();
            LOG.info("Server exited with code " + exitCode);

            if (Files.exists(SIGNAL_FILE)) {
                String mode = Files.readString(SIGNAL_FILE).split("\n")[0].trim();
                LOG.info("UPDATE_SIGNAL detected (mode=" + mode + ")");
                Files.delete(SIGNAL_FILE);

                if (Files.exists(PENDING_JAR)) {
                    if (verifyJar(PENDING_JAR)) {
                        LOG.info("Signature verified. Swapping JAR...");
                        Files.move(ACTIVE_JAR, Path.of(ACTIVE_JAR + ".bak"),
                                StandardCopyOption.REPLACE_EXISTING);
                        Files.move(PENDING_JAR, ACTIVE_JAR,
                                StandardCopyOption.REPLACE_EXISTING);
                        LOG.info("JAR swap complete. Restarting...");
                        logAudit("JAR_SWAP_COMPLETE mode=" + mode);
                    } else {
                        LOG.severe("Pending JAR failed signature verification — aborting swap!");
                        logAudit("JAR_SWAP_REJECTED reason=bad_sig");
                        Files.delete(PENDING_JAR);
                    }
                } else {
                    LOG.warning("UPDATE_SIGNAL present but no pending JAR found. Restarting server as-is.");
                }
                // Restart the server
                continue;
            }

            // Crash recovery: non-zero exit without an update signal
            if (exitCode != 0) {
                long now = System.currentTimeMillis();
                crashTimes.removeIf(t -> now - t > CRASH_WINDOW_MILLIS);
                crashTimes.add(now);
                if (crashTimes.size() <= MAX_CRASH_RESTARTS) {
                    long backoffSeconds = 5L * crashTimes.size();
                    LOG.warning("Server crashed (exit " + exitCode + "). Restarting in " + backoffSeconds
                            + "s (attempt " + crashTimes.size() + "/" + MAX_CRASH_RESTARTS + " within 10 minutes)");
                    logAudit("CRASH_RESTART exit=" + exitCode + " attempt=" + crashTimes.size());
                    Thread.sleep(backoffSeconds * 1000L);
                    continue;
                }
                LOG.severe("Server crashed " + crashTimes.size() + " times within 10 minutes — giving up. Check logs/latest.log");
                logAudit("CRASH_GIVEUP exit=" + exitCode);
                System.exit(exitCode);
            }

            // Normal exit — don't restart
            LOG.info("Server stopped normally. Launcher exiting.");
            break;
        }
    }

    // ── First-run install ──────────────────────────────────────────────────────

    private static void installIfMissing(Path serverJar) {
        if (Files.notExists(serverJar)) {
            LOG.info("Server jar not found — downloading the latest Stratum build...");
            if (download(SERVER_DOWNLOAD, serverJar)) {
                LOG.info("Downloaded " + serverJar + " (" + fileSize(serverJar) + ")");
                logAudit("INSTALL_SERVER_JAR file=" + serverJar);
            } else {
                LOG.severe("Could not download the Stratum server jar from " + SERVER_DOWNLOAD);
                LOG.severe("Download it manually from " + API_BASE + "/downloads and place it next to the launcher.");
                System.exit(1);
            }
        }
        if (Files.notExists(PLUGIN_JAR)) {
            LOG.info("StratumBootstrap plugin not found — installing...");
            if (download(PLUGIN_DOWNLOAD, PLUGIN_JAR)) {
                LOG.info("Installed " + PLUGIN_JAR + " (" + fileSize(PLUGIN_JAR) + ")");
                logAudit("INSTALL_BOOTSTRAP_PLUGIN file=" + PLUGIN_JAR);
            } else {
                // Not fatal: the server keeps its bootstrap plugin up to date on startup too
                LOG.warning("Could not download StratumBootstrap — the server will retry on startup.");
            }
        }
    }

    private static boolean download(String url, Path target) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "StratumLauncher")
                    .timeout(Duration.ofMinutes(5))
                    .build();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path temp = target.resolveSibling(target.getFileName() + ".download");
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() != 200 || Files.size(temp) < 1024) {
                Files.deleteIfExists(temp);
                return false;
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOG.warning("Download failed (" + url + "): " + e.getMessage());
            return false;
        }
    }

    private static String fileSize(Path path) {
        try {
            long bytes = Files.size(path);
            return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : (bytes / 1024) + " KB";
        } catch (IOException e) {
            return "unknown size";
        }
    }

    // ── Server process ─────────────────────────────────────────────────────────

    private static Process launchServer(String jar, String[] outerArgs) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        // Pass through JVM args after "--"
        boolean passThrough = false;
        for (String a : outerArgs) {
            if (a.equals("--")) { passThrough = true; continue; }
            if (passThrough) cmd.add(a);
        }
        cmd.add("-jar");
        cmd.add(jar);
        cmd.add("--nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.directory(Path.of(".").toFile());
        return pb.start();
    }

    private static boolean verifyJar(Path jarPath) {
        if (!Files.exists(KEY_FILE)) {
            LOG.warning("Public key not found at " + KEY_FILE + " — skipping verification");
            return true; // permissive if key not configured
        }
        Path sigPath = Path.of(jarPath.toString().replace(".jar", ".jar.sig"));
        if (!Files.exists(sigPath)) {
            LOG.severe("No signature file found for pending JAR: " + sigPath);
            return false;
        }
        try {
            byte[] jarBytes = Files.readAllBytes(jarPath);
            String pem = Files.readString(KEY_FILE)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));

            String sigB64 = Files.readString(sigPath).trim();
            byte[] sigBytes = Base64.getDecoder().decode(sigB64);

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(jarBytes);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            LOG.severe("Signature verification error: " + e.getMessage());
            return false;
        }
    }

    private static void logAudit(String event) {
        try {
            Path auditLog = Path.of("logs/stratum-audit.log");
            Files.createDirectories(auditLog.getParent());
            String line = java.time.Instant.now() + " [LAUNCHER] " + event + "\n";
            Files.writeString(auditLog, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler existing : root.getHandlers()) root.removeHandler(existing);
        ConsoleHandler h = new ConsoleHandler();
        h.setFormatter(new SimpleFormatter() {
            @Override public String format(LogRecord r) {
                return String.format("[%s] [Launcher/%s] %s%n",
                        java.time.LocalTime.now().toString().substring(0, 8),
                        r.getLevel().getName(), r.getMessage());
            }
        });
        root.addHandler(h);
    }
}
