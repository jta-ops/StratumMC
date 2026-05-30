package mc.stratum;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Downloads and updates the StratumBootstrap plugin from stratumserver.net on every startup.
 */
public final class StratumPluginInstaller {

    private static final Logger LOGGER = LogManager.getLogger("StratumPluginInstaller");
    private static final String DOWNLOAD_URL = "https://stratumserver.net/downloads/StratumBootstrap.jar";
    private static final String PLUGIN_FILENAME = "StratumBootstrap.jar";

    private StratumPluginInstaller() {}

    public static void installOrUpdate() {
        final File pluginsDir = new File("plugins");
        pluginsDir.mkdirs();

        final Path dest = pluginsDir.toPath().resolve(PLUGIN_FILENAME);

        try {
            final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOAD_URL))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Stratum] StratumBootstrap plugin installed/updated from {}", DOWNLOAD_URL);
            } else {
                LOGGER.warn("[Stratum] Failed to download StratumBootstrap (HTTP {}), skipping update.", response.statusCode());
            }
        } catch (Exception ex) {
            LOGGER.warn("[Stratum] Could not reach {} to update plugin: {}", DOWNLOAD_URL, ex.getMessage());
        }
    }
}
