package mc.stratum.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * Stratum API Server
 *
 * Minimal REST backend serving version metadata, signed tweaks, and signed addons.
 *
 * Endpoints:
 *   GET /manifest          — current major version + tweak + addon list + signatures
 *   GET /tweak/<id>        — download tweak JAR
 *   GET /tweak/<id>.sig    — download tweak signature
 *   GET /addon/<id>        — download addon JAR
 *   GET /addon/<id>.sig    — download addon signature
 *   GET /jar/<version>     — download server JAR for a given MC version
 *
 * Run: java -jar stratum-api-server.jar [port] [data-dir]
 *
 * Signing workflow (Ed25519):
 *   1. Generate key pair:  openssl genpkey -algorithm ed25519 -out private.pem
 *   2. Export public key:  openssl pkey -in private.pem -pubout -out stratum_public_key.pem
 *   3. Sign a JAR:         openssl pkeyutl -sign -inkey private.pem -rawin -in server.jar | base64 > server.jar.sig
 *   4. Bundle stratum_public_key.pem into the server JAR resources.
 */
public final class StratumApiServer {

    private static final Logger LOG = Logger.getLogger("StratumAPI");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final int port;
    private final Path dataDir;

    public static void main(String[] args) throws Exception {
        configureLogging();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dataDir = args.length > 1 ? Path.of(args[1]) : Path.of("data");
        new StratumApiServer(port, dataDir).start();
    }

    public StratumApiServer(int port, Path dataDir) {
        this.port = port;
        this.dataDir = dataDir;
    }

    public void start() throws IOException {
        Files.createDirectories(dataDir.resolve("tweaks"));
        Files.createDirectories(dataDir.resolve("addons"));
        Files.createDirectories(dataDir.resolve("jars"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/manifest",  this::handleManifest);
        server.createContext("/tweak/",    this::handleTweak);
        server.createContext("/addon/",    this::handleAddon);
        server.createContext("/jar/",      this::handleJar);
        server.createContext("/health",    this::handleHealth);
        server.setExecutor(null);
        server.start();
        LOG.info("Stratum API listening on :" + port);
    }

    // GET /manifest
    private void handleManifest(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { ex.sendResponseHeaders(405, -1); return; }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("majorVersion", readManifestProperty("majorVersion", "1"));
        manifest.put("tweaks", listArtifacts("tweaks"));
        manifest.put("addons", listArtifacts("addons"));
        sendJson(ex, manifest);
    }

    // GET /tweak/<id> or /tweak/<id>.sig
    private void handleTweak(HttpExchange ex) throws IOException {
        serveFile(ex, dataDir.resolve("tweaks"), ex.getRequestURI().getPath().replace("/tweak/", ""));
    }

    // GET /addon/<id> or /addon/<id>.sig
    private void handleAddon(HttpExchange ex) throws IOException {
        serveFile(ex, dataDir.resolve("addons"), ex.getRequestURI().getPath().replace("/addon/", ""));
    }

    // GET /jar/<version>
    private void handleJar(HttpExchange ex) throws IOException {
        serveFile(ex, dataDir.resolve("jars"), ex.getRequestURI().getPath().replace("/jar/", ""));
    }

    // GET /health
    private void handleHealth(HttpExchange ex) throws IOException {
        sendJson(ex, Map.of("status", "ok", "timestamp", System.currentTimeMillis()));
    }

    private void serveFile(HttpExchange ex, Path dir, String filename) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { ex.sendResponseHeaders(405, -1); return; }
        Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir) || !Files.exists(file)) {
            ex.sendResponseHeaders(404, -1); return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String ct = filename.endsWith(".sig") ? "text/plain" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendJson(HttpExchange ex, Object obj) throws IOException {
        byte[] bytes = GSON.toJson(obj).getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private List<Map<String, String>> listArtifacts(String subdir) {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            Path dir = dataDir.resolve(subdir);
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                      .forEach(p -> {
                          String name = p.getFileName().toString();
                          String id = name.replace(".jar", "");
                          Map<String, String> entry = new LinkedHashMap<>();
                          entry.put("id", id);
                          entry.put("downloadUrl", "/" + subdir + "/" + name);
                          entry.put("signatureUrl", "/" + subdir + "/" + name + ".sig");
                          list.add(entry);
                      });
            }
        } catch (IOException ignored) {}
        return list;
    }

    private String readManifestProperty(String key, String defaultValue) {
        Path props = dataDir.resolve("manifest.properties");
        if (!Files.exists(props)) return defaultValue;
        try {
            Properties p = new Properties();
            p.load(new FileReader(props.toFile()));
            return p.getProperty(key, defaultValue);
        } catch (IOException e) { return defaultValue; }
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
    }
}
