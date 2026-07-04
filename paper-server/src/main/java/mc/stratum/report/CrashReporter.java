package mc.stratum.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.papermc.paper.ServerBuildInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uploads a crash/hang report (thread dump + build info) to the Stratum API
 * and prints a single shareable link to the console, instead of asking
 * server owners to paste hundreds of log lines.
 *
 * <p>Fires from the watchdog when the server stops responding. Requires a
 * {@code .stratum-license} file for authentication; without one (or when the
 * API is unreachable) the report is saved to {@code crash-reports/} locally
 * only, which is the vanilla behavior owners already know.
 *
 * <p>Opt out with {@code -Dstratum.crash-reports=false} or
 * {@code crash-reports: false} in {@code stratum.yml}.
 */
public final class CrashReporter {

    private static final Logger LOGGER = Logger.getLogger("Stratum");
    private static final String API_BASE = System.getProperty("stratum.api", "https://stratumserver.net");
    private static final Path LICENSE_FILE = Path.of(".stratum-license");
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final Gson GSON = new Gson();

    private CrashReporter() {
    }

    /**
     * Builds and uploads a report. Only the first call per JVM does anything;
     * the watchdog can fire repeatedly while the server is hung.
     */
    public static void report(final String reason) {
        if (!enabled() || !REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            final String license = readLicense();
            if (license == null) {
                return; // no license, keep local-only behavior
            }
            final JsonObject payload = new JsonObject();
            payload.addProperty("version", ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_FULL));
            payload.addProperty("reason", reason);
            payload.addProperty("dump", threadDump());

            final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            final HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + "/api/crash/push"))
                .header("Authorization", "Bearer " + license)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                final JsonObject result = GSON.fromJson(response.body(), JsonObject.class);
                LOGGER.severe("Crash report uploaded: " + API_BASE + result.get("url").getAsString());
            }
        } catch (final Exception ex) {
            // Never let reporting make a crash worse
            LOGGER.log(Level.FINE, "Crash report upload failed", ex);
        }
    }

    private static String threadDump() {
        final StringBuilder dump = new StringBuilder(64 * 1024);
        for (final ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            dump.append(thread.toString());
        }
        return dump.toString();
    }

    private static String readLicense() {
        try {
            if (Files.isRegularFile(LICENSE_FILE)) {
                for (final String line : Files.readAllLines(LICENSE_FILE)) {
                    final String trimmed = line.trim();
                    if (trimmed.startsWith("STRATUM-")) {
                        return trimmed;
                    }
                }
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    private static boolean enabled() {
        if ("false".equalsIgnoreCase(System.getProperty("stratum.crash-reports"))) {
            return false;
        }
        try {
            final Path config = Path.of("stratum.yml");
            if (Files.isRegularFile(config) && Files.readString(config).contains("crash-reports: false")) {
                return false;
            }
        } catch (final Exception ignored) {
        }
        return true;
    }
}
