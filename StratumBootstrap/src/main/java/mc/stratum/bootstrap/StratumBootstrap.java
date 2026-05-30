package mc.stratum.bootstrap;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.event.player.PlayerPickupItemEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class StratumBootstrap extends JavaPlugin implements Listener {

    private static final String API_BASE = "https://stratumserver.net/api/license";
    private static final String SERVER_IP = "139.99.209.145";
    private static final int SERVER_PORT = 27022;
    private static final long HEARTBEAT_INTERVAL_TICKS = 5 * 60 * 20;
    private static final Duration BLINDNESS_DURATION = Duration.ofSeconds(Integer.MAX_VALUE);
    private static final Component SUSPENDED_MOTD = Component.text("⚠ License suspended").color(NamedTextColor.RED);
    private static final PotionEffect INFINITE_BLINDNESS = new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true);

    private String fingerprint;
    private String licenseKey;
    private volatile boolean licenseBlocked = false;
    private volatile boolean licenseVerified = false;
    private final Set<UUID> restrictedPlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable heartbeatTask;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        generateFingerprint();
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        CompletableFuture.runAsync(() -> {
            try {
                licenseKey = readLicenseFromLocations();
                if (licenseKey == null || licenseKey.isEmpty()) {
                    getLogger().info("No license key found — registering with API...");
                    licenseKey = registerWithApi();
                    if (licenseKey != null) {
                        saveLicenseToLocations(licenseKey);
                        getLogger().info("License registered and saved: " + licenseKey.substring(0, Math.min(20, licenseKey.length())) + "...");
                    } else {
                        getLogger().severe("Failed to register license!");
                        enterRestrictedMode();
                        return;
                    }
                }

                boolean verified = verifyWithApi();
                if (!verified) {
                    getLogger().severe("License verification failed!");
                    enterRestrictedMode();
                    return;
                }

                getLogger().info("License verified successfully.");
                licenseVerified = true;
                startHeartbeat();
            } catch (Exception e) {
                getLogger().severe("License check failed: " + e.getMessage());
                enterRestrictedMode();
            }
        });
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (licenseKey != null && fingerprint != null) {
            sendApiRequest("POST", "/shutdown", "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\"}");
        }
    }

    private void generateFingerprint() {
        String uuid = getConfig().getString("uuid", "");
        if (uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
            getConfig().set("uuid", uuid);
            saveConfig();
        }
        try {
            String input = SERVER_IP + ":" + SERVER_PORT + ":" + uuid;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            fingerprint = sb.toString();
        } catch (Exception e) {
            fingerprint = UUID.randomUUID().toString().replace("-", "");
            getLogger().warning("SHA-256 not available, using random fingerprint");
        }
    }

    private String registerWithApi() {
        try {
            String body = "{\"fingerprint\":\"" + fingerprint + "\",\"server_ip\":\"" + SERVER_IP + "\",\"server_port\":" + SERVER_PORT + "}";
            String response = sendApiRequest("POST", "/register", body);
            if (response == null) return null;
            Map<String, Object> map = parseJson(response);
            if (map.containsKey("key")) {
                return (String) map.get("key");
            }
            return null;
        } catch (Exception e) {
            getLogger().severe("Registration error: " + e.getMessage());
            return null;
        }
    }

    private boolean verifyWithApi() {
        try {
            String body = "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\"}";
            String response = sendApiRequest("POST", "/verify", body);
            if (response == null) return false;
            Map<String, Object> map = parseJson(response);
            String status = (String) map.get("status");
            if ("active".equals(status)) {
                licenseBlocked = false;
                return true;
            } else if ("blocked".equals(status)) {
                licenseBlocked = true;
                return false;
            }
            return false;
        } catch (Exception e) {
            getLogger().severe("Verification error: " + e.getMessage());
            return false;
        }
    }

    private void startHeartbeat() {
        heartbeatTask = new BukkitRunnable() {
            @Override
            public void run() {
                CompletableFuture.runAsync(() -> {
                    try {
                        String body = "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\"}";
                        String response = sendApiRequest("POST", "/heartbeat", body);
                        if (response != null) {
                            Map<String, Object> map = parseJson(response);
                            if (map.containsKey("status")) {
                                String status = (String) map.get("status");
                                if ("blocked".equals(status) && !licenseBlocked) {
                                    licenseBlocked = true;
                                    enterRestrictedMode();
                                } else if ("active".equals(status) && licenseBlocked) {
                                    licenseBlocked = false;
                                    exitRestrictedMode();
                                }
                            }
                        } else {
                            getLogger().warning("Heartbeat failed — server may be unreachable");
                        }
                    } catch (Exception e) {
                        getLogger().warning("Heartbeat error: " + e.getMessage());
                    }
                });
            }
        };
        heartbeatTask.runTaskTimer(this, HEARTBEAT_INTERVAL_TICKS, HEARTBEAT_INTERVAL_TICKS);
    }

    private void enterRestrictedMode() {
        licenseBlocked = true;
        getLogger().warning("ENTERING RESTRICTED MODE — License is not active!");

        for (Player player : Bukkit.getOnlinePlayers()) {
            restrictPlayer(player);
        }
    }

    private void exitRestrictedMode() {
        licenseBlocked = false;
        getLogger().info("License restored — exiting restricted mode.");

        for (Player player : Bukkit.getOnlinePlayers()) {
            unrestrictPlayer(player);
        }
    }

    private void restrictPlayer(Player player) {
        restrictedPlayers.add(player.getUniqueId());
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        player.addPotionEffect(INFINITE_BLINDNESS);
        player.sendMessage(Component.text("Server license is suspended. Contact admin.").color(NamedTextColor.RED));
        player.showTitle(Title.title(
            Component.text("⚠ License Suspended").color(NamedTextColor.RED),
            Component.text("Contact admin to resolve").color(NamedTextColor.YELLOW),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(Integer.MAX_VALUE), Duration.ofMillis(500))
        ));
    }

    private void unrestrictPlayer(Player player) {
        restrictedPlayers.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.clearTitle();
        player.sendMessage(Component.text("License restored — welcome back!").color(NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (licenseBlocked) {
            restrictPlayer(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        restrictedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerPing(PaperServerListPingEvent event) {
        if (licenseBlocked) {
            event.motd(SUSPENDED_MOTD);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!licenseBlocked) return;
        String cmd = event.getMessage().toLowerCase().split(" ")[0];
        if (!cmd.equals("/st") && !cmd.equals("/st") && !cmd.startsWith("/st ")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Commands disabled — license suspended. Use /st license").color(NamedTextColor.RED));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("license")) {
            sender.sendMessage(Component.text("=== Stratum License Info ===").color(NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Key: " + (licenseKey != null ? licenseKey.substring(0, Math.min(30, licenseKey.length())) + "..." : "None")).color(NamedTextColor.WHITE));
            sender.sendMessage(Component.text("Fingerprint: " + (fingerprint != null ? fingerprint.substring(0, 16) + "..." : "None")).color(NamedTextColor.WHITE));
            sender.sendMessage(Component.text("Status: " + (licenseBlocked ? "BLOCKED" : (licenseVerified ? "Active" : "Pending"))).color(licenseBlocked ? NamedTextColor.RED : NamedTextColor.GREEN));
            sender.sendMessage(Component.text("Server: " + SERVER_IP + ":" + SERVER_PORT).color(NamedTextColor.GRAY));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /st license").color(NamedTextColor.YELLOW));
        return true;
    }

    private String readLicenseFromLocations() {
        Map<String, Integer> keyVotes = new HashMap<>();
        String key;

        key = readLicenseFile();
        if (key != null) keyVotes.merge(key, 1, Integer::sum);

        key = readLicenseFromEula();
        if (key != null) keyVotes.merge(key, 1, Integer::sum);

        key = readLicenseFromServerProperties();
        if (key != null) keyVotes.merge(key, 1, Integer::sum);

        key = readLicenseFromConfig();
        if (key != null) keyVotes.merge(key, 1, Integer::sum);

        if (keyVotes.isEmpty()) return null;
        return keyVotes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private void saveLicenseToLocations(String key) {
        writeLicenseFile(key);
        writeLicenseToEula(key);
        writeLicenseToServerProperties(key);
        writeLicenseToConfig(key);
    }

    private String readLicenseFile() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), ".stratum-license");
            if (!file.exists()) return null;
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            Map<String, Object> map = parseJson(content);
            return (String) map.get("key");
        } catch (Exception e) { return null; }
    }

    private void writeLicenseFile(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), ".stratum-license");
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("{\"key\":\"" + key + "\",\"fingerprint\":\"" + fingerprint + "\"}\n");
            }
        } catch (Exception e) { getLogger().warning("Failed to write .stratum-license: " + e.getMessage()); }
    }

    private String readLicenseFromEula() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "eula.txt");
            if (!file.exists()) return null;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("# Stratum-License:")) {
                    return line.substring("# Stratum-License:".length()).trim();
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private void writeLicenseToEula(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "eula.txt");
            List<String> lines = file.exists()
                ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>(Collections.singletonList("eula=true"));
            lines.removeIf(l -> l.startsWith("# Stratum-License:"));
            lines.add("# Stratum-License: " + key);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) { getLogger().warning("Failed to write eula.txt: " + e.getMessage()); }
    }

    private String readLicenseFromServerProperties() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "server.properties");
            if (!file.exists()) return null;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith("#stratum-license=")) {
                    return line.substring("#stratum-license=".length()).trim();
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private void writeLicenseToServerProperties(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "server.properties");
            List<String> lines = file.exists()
                ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>();
            lines.removeIf(l -> l.startsWith("#stratum-license="));
            lines.add("#stratum-license=" + key);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) { getLogger().warning("Failed to write server.properties: " + e.getMessage()); }
    }

    private String readLicenseFromConfig() {
        return getConfig().getString("license-key", null);
    }

    private void writeLicenseToConfig(String key) {
        getConfig().set("license-key", key);
        saveConfig();
    }

    private String sendApiRequest(String method, String path, String body) {
        try {
            URL url = new URL(API_BASE + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (body != null) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            getLogger().warning("API request failed [" + method + " " + path + "]: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return map;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return map;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return map;
        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) == ' ') i++;
            if (i >= json.length()) break;
            if (json.charAt(i) != '"') break;
            i++;
            int keyStart = i;
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++;
                i++;
            }
            String key = json.substring(keyStart, i);
            i++;
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;
            while (i < json.length() && json.charAt(i) == ' ') i++;
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '"') {
                i++;
                int valStart = i;
                StringBuilder sb = new StringBuilder();
                while (i < json.length()) {
                    if (json.charAt(i) == '\\' && i + 1 < json.length()) {
                        sb.append(json.charAt(i + 1));
                        i += 2;
                    } else if (json.charAt(i) == '"') {
                        break;
                    } else {
                        sb.append(json.charAt(i));
                        i++;
                    }
                }
                map.put(key, sb.toString());
                i++;
            } else if (c == 't' || c == 'f') {
                boolean val = json.startsWith("true", i);
                map.put(key, val);
                i += val ? 4 : 5;
            } else if (c == 'n') {
                map.put(key, null);
                i += 4;
            } else if (c == '-' || Character.isDigit(c)) {
                int valStart = i;
                while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' || json.charAt(i) == '-' || json.charAt(i) == 'e' || json.charAt(i) == 'E' || json.charAt(i) == '+')) i++;
                String numStr = json.substring(valStart, i);
                try {
                    if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                        map.put(key, Double.parseDouble(numStr));
                    } else {
                        map.put(key, Long.parseLong(numStr));
                    }
                } catch (NumberFormatException nfe) {
                    map.put(key, numStr);
                }
            }
            while (i < json.length() && json.charAt(i) != ',') i++;
            i++;
        }
        return map;
    }
}