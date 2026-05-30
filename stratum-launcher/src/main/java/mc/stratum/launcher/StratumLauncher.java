package mc.stratum.launcher;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.*;

/**
 * Stratum Launcher — supervises the server process and performs signed JAR swaps.
 *
 * The server never overwrites its own running JAR. All swapping happens here.
 *
 * Usage: java -jar stratum-launcher.jar [server.jar] [-- server args...]
 */
public final class StratumLauncher {

    private static final Logger LOG = Logger.getLogger("StratumLauncher");
    private static final Path SIGNAL_FILE = Path.of("UPDATE_SIGNAL");
    private static final Path PENDING_JAR = Path.of("stratum-server-pending.jar");
    private static final Path ACTIVE_JAR  = Path.of("stratum-server.jar");
    private static final Path KEY_FILE    = Path.of("stratum_public_key.pem");

    public static void main(String[] args) throws Exception {
        configureLogging();
        LOG.info("Stratum Launcher starting...");

        String serverJar = args.length > 0 ? args[0] : ACTIVE_JAR.toString();

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

            // Normal exit — don't restart
            LOG.info("Server stopped normally. Launcher exiting.");
            break;
        }
    }

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
