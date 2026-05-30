package mc.stratum.bootstrap;

import org.bukkit.event.server.ServerListPingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

public class StratumBootstrap extends JavaPlugin implements Listener {

    private static final String API_BASE  = "https://stratumserver.net/api/license";
    private static final String DASH_BASE = "https://stratumserver.net/api/dash";
    private static final String SITE_BASE = "https://stratumserver.net";
    private static final String SERVER_IP   = "139.99.209.145";
    private static final int    SERVER_PORT = 27022;
    private static final long   HEARTBEAT_INTERVAL_TICKS = 5 * 60 * 20;
    private static final String SUSPENDED_MOTD = "§c⚠ License suspended §7| §estratumserver.net";
    private static final String ACTIVE_MOTD    = "§aStratum §7| §eLicensed §7| §bstratumserver.net";
    private static final PotionEffect INFINITE_BLINDNESS =
            new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, true);

    private String fingerprint;
    private String licenseKey;
    private volatile boolean licenseBlocked  = false;
    private volatile boolean licenseVerified = false;
    private final Set<UUID> restrictedPlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable heartbeatTask;
    private long serverStartTime;
    private volatile boolean updateReady = false;
    private String currentBuild = "unknown";

    // ── Dashboard ────────────────────────────────────────────────────────────
    private final List<String> consoleBatch = Collections.synchronizedList(new ArrayList<>());
    private DashLog4jAppender dashAppender;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try { configFile.createNewFile(); } catch (Exception e) {
                getLogger().warning("Could not create config.yml: " + e.getMessage());
            }
        }
        generateFingerprint();
    }

    @Override
    public void onEnable() {
        serverStartTime = System.currentTimeMillis();
        getServer().getPluginManager().registerEvents(this, this);

        // Attach Log4j2 appender to capture all console output
        dashAppender = new DashLog4jAppender();
        dashAppender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(dashAppender);

        CompletableFuture.runAsync(() -> {
            try {
                licenseKey = readLicenseFromLocations();
                if (licenseKey == null || licenseKey.isEmpty()) {
                    getLogger().info("No license key found — registering with API...");
                    licenseKey = registerWithApi();
                    if (licenseKey != null) {
                        saveLicenseToLocations(licenseKey);
                        getLogger().info("License registered: " + licenseKey.substring(0, Math.min(20, licenseKey.length())) + "...");
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

                licenseVerified = true;
                printStartupBanner();
                startHeartbeat();
                startTabListTask();
                scheduleBackups();
                startDashTasks();
                checkForServerUpdate();
            } catch (Exception e) {
                getLogger().severe("License check failed: " + e.getMessage());
                enterRestrictedMode();
            }
        });
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) heartbeatTask.cancel();
        if (licenseKey != null && fingerprint != null) {
            sendApiRequest("POST", "/shutdown",
                    "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\"}");
        }
        if (dashAppender != null) {
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(dashAppender);
            dashAppender.stop();
        }
    }

    // ── Dashboard background tasks ────────────────────────────────────────────

    private void startDashTasks() {
        // Poll pending commands every 5 seconds
        new BukkitRunnable() {
            @Override public void run() {
                if (!licenseVerified || licenseBlocked) return;
                CompletableFuture.runAsync(() -> pollAndRunCommands());
            }
        }.runTaskTimer(this, 100L, 100L);

        // Push console + players every 30 seconds
        new BukkitRunnable() {
            @Override public void run() {
                if (!licenseVerified || licenseBlocked) return;
                CompletableFuture.runAsync(() -> {
                    pushConsoleBatch();
                    pushPlayerList();
                });
            }
        }.runTaskTimer(this, 600L, 600L);

        // Poll scheduled commands every 5 minutes
        new BukkitRunnable() {
            @Override public void run() {
                if (!licenseVerified || licenseBlocked) return;
                CompletableFuture.runAsync(() -> runScheduledCommands());
            }
        }.runTaskTimer(this, 6000L, 6000L);
    }

    private void pollAndRunCommands() {
        try {
            String response = sendDashApiRequest("GET", "/commands/pending", null);
            if (response == null) return;
            List<Map<String, Object>> commands = parseJsonObjectArray(response, "commands");
            for (Map<String, Object> cmd : commands) {
                long id = toLong(cmd.get("id"));
                String command = asString(cmd.get("command"));
                if (command == null || command.isEmpty()) continue;

                // Mark pre-dispatch batch size so we can capture output
                final int batchSizeBefore = consoleBatch.size();

                // Dispatch on main thread, block until done
                try {
                    Bukkit.getScheduler().callSyncMethod(this, () -> {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                        return null;
                    }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {}

                // Collect lines logged after dispatch
                List<String> captured;
                synchronized (consoleBatch) {
                    captured = new ArrayList<>(consoleBatch.subList(
                            Math.min(batchSizeBefore, consoleBatch.size()), consoleBatch.size()));
                }
                String output = captured.isEmpty() ? "(no output)" : String.join("\n", captured);

                final long fid = id;
                final String fout = output;
                sendDashApiRequest("POST", "/commands/" + fid + "/complete",
                        "{\"output\":" + jsonString(fout) + "}");
            }
        } catch (Exception e) {
            getLogger().warning("[Dash] Command poll error: " + e.getMessage());
        }
    }

    private void pushConsoleBatch() {
        if (consoleBatch.isEmpty()) return;
        List<String> toSend;
        synchronized (consoleBatch) {
            toSend = new ArrayList<>(consoleBatch);
            consoleBatch.clear();
        }
        if (toSend.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < toSend.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(toSend.get(i)));
        }
        sb.append("]}");
        sendDashApiRequest("POST", "/console/push", sb.toString());
    }

    private void pushPlayerList() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        StringBuilder sb = new StringBuilder("{\"players\":[");
        boolean first = true;
        for (Player p : online) {
            if (!first) sb.append(',');
            sb.append("{\"name\":").append(jsonString(p.getName()))
              .append(",\"uuid\":").append(jsonString(p.getUniqueId().toString()))
              .append(",\"gamemode\":").append(jsonString(p.getGameMode().name()))
              .append(",\"health\":").append(String.format("%.1f", p.getHealth()))
              .append(",\"ping\":").append(p.getPing())
              .append('}');
            first = false;
        }
        sb.append("]}");
        sendDashApiRequest("POST", "/players/push", sb.toString());
    }

    private void runScheduledCommands() {
        try {
            String response = sendDashApiRequest("GET", "/schedule/due", null);
            if (response == null) return;
            List<Map<String, Object>> schedules = parseJsonObjectArray(response, "schedules");
            if (schedules.isEmpty()) return;
            for (Map<String, Object> sched : schedules) {
                String command = asString(sched.get("command"));
                long intervalMins = toLong(sched.get("interval_mins"));
                if (command == null || intervalMins <= 0) continue;
                // Simple time-based check: run if interval divides current minute
                long minutesSinceEpoch = System.currentTimeMillis() / 60000;
                if (minutesSinceEpoch % intervalMins != 0) continue;
                final String cmd = command;
                Bukkit.getScheduler().runTask(this, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
        } catch (Exception e) {
            getLogger().warning("[Dash] Schedule poll error: " + e.getMessage());
        }
    }

    // ── Dashboard commands ────────────────────────────────────────────────────

    private void cmdDash(CommandSender sender, String[] args) {
        if (!sender.isOp() && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("You must be OP to use dashboard commands.").color(NamedTextColor.RED));
            return;
        }
        if (licenseKey == null) {
            sender.sendMessage(Component.text("No license key — server not registered yet.").color(NamedTextColor.RED));
            return;
        }

        // /stb dash link         → step 1: get code from API
        // /stb dash link <code>  → step 2: confirm with code from dashboard
        if (args.length < 2 || !args[1].equalsIgnoreCase("link")) {
            sender.sendMessage(Component.text("Usage: /stb dash link [confirm-code]").color(NamedTextColor.GRAY));
            return;
        }

        if (args.length == 2) {
            // Step 1
            sender.sendMessage(Component.text("Contacting dashboard...").color(NamedTextColor.GRAY));
            CompletableFuture.runAsync(() -> {
                String response = sendDashApiRequest("POST", "/link/init", "{}");
                if (response == null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage(Component.text("Failed to contact dashboard API.").color(NamedTextColor.RED)));
                    return;
                }
                Map<String, Object> map = parseJson(response);
                String code = asString(map.get("code"));
                if (code == null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage(Component.text("Unexpected response: " + response).color(NamedTextColor.RED)));
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").color(NamedTextColor.DARK_GRAY));
                    sender.sendMessage(Component.text(" Dashboard Link Code").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                    sender.sendMessage(Component.text(""));
                    sender.sendMessage(Component.text("  " + code).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
                    sender.sendMessage(Component.text(""));
                    sender.sendMessage(Component.text(" Enter this code at:").color(NamedTextColor.GRAY));
                    sender.sendMessage(Component.text(" stratumserver.net/dashboard").color(NamedTextColor.AQUA));
                    sender.sendMessage(Component.text(""));
                    sender.sendMessage(Component.text(" Code expires in 10 minutes.").color(NamedTextColor.DARK_GRAY));
                    sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").color(NamedTextColor.DARK_GRAY));
                });
            });
        } else {
            // Step 2
            String confirmCode = args[2];
            sender.sendMessage(Component.text("Confirming link...").color(NamedTextColor.GRAY));
            CompletableFuture.runAsync(() -> {
                String response = sendDashApiRequest("POST", "/link/confirm",
                        "{\"code\":" + jsonString(confirmCode) + "}");
                if (response == null) {
                    Bukkit.getScheduler().runTask(this, () ->
                            sender.sendMessage(Component.text("Failed to contact dashboard API.").color(NamedTextColor.RED)));
                    return;
                }
                Map<String, Object> map = parseJson(response);
                Object ok = map.get("ok");
                Bukkit.getScheduler().runTask(this, () -> {
                    if (Boolean.TRUE.equals(ok)) {
                        sender.sendMessage(Component.text("✔ Server linked to your dashboard account!").color(NamedTextColor.GREEN));
                        sender.sendMessage(Component.text("  Visit stratumserver.net/dashboard to manage it.").color(NamedTextColor.GRAY));
                    } else {
                        String error = asString(map.get("error"));
                        sender.sendMessage(Component.text("✘ Link failed: " + (error != null ? error : "invalid code")).color(NamedTextColor.RED));
                    }
                });
            });
        }
    }

    // ── Console log appender (Log4j2) ─────────────────────────────────────────

    private class DashLog4jAppender extends AbstractAppender {
        DashLog4jAppender() {
            super("StratumDash", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            try {
                String ts = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                String level = event.getLevel().name();
                String msg = event.getMessage().getFormattedMessage();
                // Strip ANSI escape codes and Minecraft colour codes
                msg = msg.replaceAll("\\[[;\\d]*[A-Za-z]", "").replaceAll("§.", "");
                if (msg.isBlank()) return;
                String line = "[" + ts + " " + level + "]: " + msg;
                if (consoleBatch.size() < 2000) consoleBatch.add(line);
            } catch (Exception ignored) {}
        }
    }

    // ── Dashboard API helper ──────────────────────────────────────────────────

    private String sendDashApiRequest(String method, String path, String body) {
        try {
            URL url = new URL(DASH_BASE + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + licenseKey);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (body != null && !method.equals("GET")) {
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            getLogger().warning("[Dash] API error [" + method + " " + path + "]: " + e.getMessage());
            return null;
        }
    }

    // ── Existing code (unchanged) ─────────────────────────────────────────────

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
        }
    }

    private String registerWithApi() {
        try {
            String body = "{\"fingerprint\":\"" + fingerprint + "\",\"server_ip\":\"" + SERVER_IP + "\",\"server_port\":" + SERVER_PORT + "}";
            String response = sendApiRequest("POST", "/register", body);
            if (response == null) return null;
            Map<String, Object> map = parseJson(response);
            if (map.containsKey("key")) return asString(map.get("key"));
            return null;
        } catch (Exception e) { getLogger().severe("Registration error: " + e.getMessage()); return null; }
    }

    private boolean verifyWithApi() {
        try {
            String body = "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\"}";
            String response = sendApiRequest("POST", "/verify", body);
            if (response == null) return false;
            Map<String, Object> map = parseJson(response);
            String status = asString(map.get("status"));
            if ("active".equals(status)) { licenseBlocked = false; return true; }
            if ("blocked".equals(status)) { licenseBlocked = true; return false; }
            return false;
        } catch (Exception e) { getLogger().severe("Verification error: " + e.getMessage()); return false; }
    }

    private void startHeartbeat() {
        heartbeatTask = new BukkitRunnable() {
            @Override public void run() {
                CompletableFuture.runAsync(() -> {
                    try {
                        double tps = getTps(); double mspt = getMspt();
                        Runtime rt = Runtime.getRuntime();
                        long ramUsed = (rt.totalMemory() - rt.freeMemory()) / 1048576;
                        long ramMax = rt.maxMemory() / 1048576;
                        int online = Bukkit.getOnlinePlayers().size();
                        int max = Bukkit.getMaxPlayers();
                        String body = "{\"key\":\"" + licenseKey + "\",\"fingerprint\":\"" + fingerprint + "\","
                                + "\"tps\":" + tps + ",\"mspt\":" + mspt + ","
                                + "\"ram_used_mb\":" + ramUsed + ",\"ram_max_mb\":" + ramMax + ","
                                + "\"players_online\":" + online + ",\"players_max\":" + max + "}";
                        String response = sendApiRequest("POST", "/heartbeat", body);
                        if (response != null) {
                            Map<String, Object> map = parseJson(response);
                            if (map.containsKey("status")) {
                                String status = asString(map.get("status"));
                                if ("blocked".equals(status) && !licenseBlocked) { licenseBlocked = true; enterRestrictedMode(); }
                                else if ("active".equals(status) && licenseBlocked) { licenseBlocked = false; exitRestrictedMode(); }
                            }
                        }
                    } catch (Exception e) { getLogger().warning("Heartbeat error: " + e.getMessage()); }
                });
            }
        };
        heartbeatTask.runTaskTimer(this, HEARTBEAT_INTERVAL_TICKS, HEARTBEAT_INTERVAL_TICKS);
    }

    private void startTabListTask() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (licenseBlocked) {
                        p.sendPlayerListHeader(Component.text("⚠ License Suspended").color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
                        p.sendPlayerListFooter(Component.text("stratumserver.net §7| §cRestricted Mode").color(NamedTextColor.GRAY));
                    } else {
                        p.sendPlayerListHeader(Component.text("Stratum").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                                .append(Component.text(" v" + getDescription().getVersion()).color(NamedTextColor.GRAY)));
                        double tps = getTps();
                        NamedTextColor tpsColor = tps > 18 ? NamedTextColor.GREEN : tps > 14 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                        p.sendPlayerListFooter(Component.text("TPS: ").color(NamedTextColor.GRAY)
                                .append(Component.text(String.format("%.1f", tps)).color(tpsColor))
                                .append(Component.text(" | ").color(NamedTextColor.DARK_GRAY))
                                .append(Component.text(Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()).color(NamedTextColor.GRAY))
                                .append(Component.text(" | ").color(NamedTextColor.DARK_GRAY))
                                .append(Component.text("stratumserver.net").color(NamedTextColor.DARK_AQUA)));
                    }
                }
            }
        }.runTaskTimer(this, 100L, 100L);
    }

    private void scheduleBackups() {
        long interval = 4 * 60 * 60 * 20L;
        new BukkitRunnable() {
            @Override public void run() {
                if (!licenseVerified || licenseBlocked) return;
                Bukkit.getScheduler().runTaskAsynchronously(StratumBootstrap.this, () -> {
                    try { uploadBackup(); } catch (Exception e) { getLogger().warning("Backup upload failed: " + e.getMessage()); }
                });
            }
        }.runTaskTimer(this, interval, interval);
    }

    private void uploadBackup() {
        try {
            File worldDir = new File(getDataFolder().getParentFile().getParentFile(), "world");
            if (!worldDir.exists()) return;
            ProcessBuilder pb = new ProcessBuilder("tar", "-czf", "-", "-C", worldDir.getParent(), worldDir.getName());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            URL url = new URL(SITE_BASE + "/api/backups/upload?fingerprint=" + fingerprint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/gzip");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);
            OutputStream os = conn.getOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = proc.getInputStream().read(buf)) != -1) os.write(buf, 0, len);
            os.flush(); os.close(); proc.waitFor();
            int code = conn.getResponseCode();
            if (code == 200) getLogger().info("World backup uploaded successfully.");
            else getLogger().warning("Backup upload returned HTTP " + code);
            conn.disconnect();
        } catch (Exception e) { getLogger().warning("Backup error: " + e.getMessage()); }
    }

    private void enterRestrictedMode() {
        licenseBlocked = true;
        getLogger().warning("ENTERING RESTRICTED MODE — License is not active!");
        for (Player player : Bukkit.getOnlinePlayers()) restrictPlayer(player);
    }

    private void exitRestrictedMode() {
        licenseBlocked = false;
        getLogger().info("License restored — exiting restricted mode.");
        for (Player player : Bukkit.getOnlinePlayers()) unrestrictPlayer(player);
    }

    private void printStartupBanner() {
        String[] fallback = { "", "  STRATUM", "", "  Stratum Bootstrap v" + getDescription().getVersion(),
                "  License: " + licenseKey.substring(0, Math.min(24, licenseKey.length())) + "...",
                "  Fingerprint: " + fingerprint.substring(0, 16) + "...",
                "  Status: ACTIVE | Node: " + SERVER_IP + ":" + SERVER_PORT,
                "  https://stratumserver.net", "" };
        try {
            URL url = new URL(SITE_BASE + "/api/banner");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
            String bannerText = null;
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) { sb.append(line).append('\n'); }
                reader.close(); bannerText = sb.toString().trim();
            }
            conn.disconnect();
            if (bannerText != null && !bannerText.isEmpty()) {
                getLogger().info("");
                for (String line : bannerText.split("\n")) getLogger().info("  " + line);
                getLogger().info(""); getLogger().info("  Stratum Bootstrap v" + getDescription().getVersion());
                getLogger().info("  License: " + licenseKey.substring(0, Math.min(24, licenseKey.length())) + "...");
                getLogger().info("  Fingerprint: " + fingerprint.substring(0, 16) + "...");
                getLogger().info("  Status: ACTIVE | Node: " + SERVER_IP + ":" + SERVER_PORT);
                getLogger().info("  https://stratumserver.net"); getLogger().info("");
            } else { for (String line : fallback) getLogger().info(line); }
        } catch (Exception e) { for (String line : fallback) getLogger().info(line); }
    }

    private void restrictPlayer(Player player) {
        restrictedPlayers.add(player.getUniqueId());
        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        player.addPotionEffect(INFINITE_BLINDNESS);
        player.sendMessage(Component.text("Server license is suspended. Contact admin.").color(NamedTextColor.RED));
        player.showTitle(Title.title(
                Component.text("⚠ License Suspended").color(NamedTextColor.RED),
                Component.text("Contact admin to resolve").color(NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(Integer.MAX_VALUE), Duration.ofMillis(500))));
    }

    private void unrestrictPlayer(Player player) {
        restrictedPlayers.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.clearTitle();
        player.sendMessage(Component.text("License restored — welcome back!").color(NamedTextColor.GREEN));
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (licenseBlocked) {
            restrictPlayer(event.getPlayer());
            event.joinMessage(Component.text("⚠ ").color(NamedTextColor.RED)
                    .append(Component.text(event.getPlayer().getName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" joined — license suspended").color(NamedTextColor.RED)));
        } else {
            event.joinMessage(Component.text("▸ ").color(NamedTextColor.GREEN)
                    .append(Component.text(event.getPlayer().getName()).color(NamedTextColor.WHITE))
                    .append(Component.text(" joined").color(NamedTextColor.GRAY)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        restrictedPlayers.remove(event.getPlayer().getUniqueId());
        if (licenseBlocked) {
            event.quitMessage(Component.text("▸ ").color(NamedTextColor.RED)
                    .append(Component.text(event.getPlayer().getName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" left — license suspended").color(NamedTextColor.RED)));
        } else {
            event.quitMessage(Component.text("▸ ").color(NamedTextColor.GRAY)
                    .append(Component.text(event.getPlayer().getName()).color(NamedTextColor.WHITE))
                    .append(Component.text(" left").color(NamedTextColor.DARK_GRAY)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerPing(ServerListPingEvent event) {
        event.setMotd(licenseBlocked ? SUSPENDED_MOTD : ACTIVE_MOTD);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!licenseBlocked) return;
        String cmd = event.getMessage().toLowerCase().split(" ")[0];
        if (!cmd.equals("/stb") && !cmd.startsWith("/stb ")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Commands disabled — license suspended. Use /stb license").color(NamedTextColor.RED));
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendUsage(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "license":  cmdLicense(sender); break;
            case "version":  cmdVersion(sender); break;
            case "stats":    cmdStats(sender); break;
            case "restart":  cmdRestart(sender, args); break;
            case "update":   cmdUpdate(sender); break;
            case "dash":     cmdDash(sender, args); break;
            default:         sendUsage(sender); break;
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("=== Stratum Bootstrap ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/stb license").color(NamedTextColor.WHITE).append(Component.text(" - License info").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb version").color(NamedTextColor.WHITE).append(Component.text(" - Version banner").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb stats").color(NamedTextColor.WHITE).append(Component.text(" - Server stats").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb restart [seconds]").color(NamedTextColor.WHITE).append(Component.text(" - Restart server").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb update").color(NamedTextColor.WHITE).append(Component.text(" - Apply server update").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb dash link").color(NamedTextColor.WHITE).append(Component.text(" - Link to dashboard (step 1)").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb dash link <code>").color(NamedTextColor.WHITE).append(Component.text(" - Confirm link (step 2)").color(NamedTextColor.GRAY)));
    }

    private void cmdLicense(CommandSender sender) {
        sender.sendMessage(Component.text("=== Stratum License ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Key: ").color(NamedTextColor.WHITE).append(Component.text(licenseKey != null ? licenseKey.substring(0, Math.min(30, licenseKey.length())) + "..." : "None").color(NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Fingerprint: ").color(NamedTextColor.WHITE).append(Component.text(fingerprint != null ? fingerprint.substring(0, 16) + "..." : "None").color(NamedTextColor.YELLOW)));
        String status = licenseBlocked ? "§cBLOCKED" : (licenseVerified ? "§aACTIVE" : "§ePENDING");
        sender.sendMessage(Component.text("Status: " + status));
        sender.sendMessage(Component.text("Server: " + SERVER_IP + ":" + SERVER_PORT).color(NamedTextColor.GRAY));
    }

    private void cmdVersion(CommandSender sender) {
        String[] banner = {
                "███████╗████████╗██████╗  █████╗ ████████╗██╗   ██╗███╗   ███╗",
                "██╔════╝██╔══██╗██╔══██╗██╔══════██╗██║   ██║████╗ ███║",
                "███████╗╔══██╗   ██████╔███████╗╔══██╗╔══██╗████╔██╗",
                "██╔══██╗   ██║   ██╔══██╗██╔══██╗╔══██╗██╔██╔══██╗",
                "███████╗   ██║   ██╔══██╗██╔══██╗███████╔██╔══██╗",
                "╚══════╝╔══╝   ╚════╝╚══════╝╚═══════╝ ╚══════╝ ╚══════╝"
        };
        for (String line : banner) sender.sendMessage(Component.text(line).color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Stratum Bootstrap v" + getDescription().getVersion()).color(NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  https://stratumserver.net").color(NamedTextColor.DARK_AQUA));
    }

    private void cmdStats(CommandSender sender) {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        long maxMB = rt.maxMemory() / 1048576;
        double tps = getTps(); double mspt = getMspt();
        long uptimeSec = (System.currentTimeMillis() - serverStartTime) / 1000;
        long hours = uptimeSec / 3600; long mins = (uptimeSec % 3600) / 60; long secs = uptimeSec % 60;
        NamedTextColor tpsColor = tps > 18 ? NamedTextColor.GREEN : tps > 14 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        sender.sendMessage(Component.text("=== Server Stats ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("TPS: ").color(NamedTextColor.WHITE).append(Component.text(String.format("%.1f", tps)).color(tpsColor)));
        sender.sendMessage(Component.text("MSPT: ").color(NamedTextColor.WHITE).append(Component.text(String.format("%.1fms", mspt)).color(NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("RAM: ").color(NamedTextColor.WHITE).append(Component.text(usedMB + "mb / " + maxMB + "mb").color(NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Players: ").color(NamedTextColor.WHITE).append(Component.text(Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()).color(NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Uptime: ").color(NamedTextColor.WHITE).append(Component.text(String.format("%dh %dm %ds", hours, mins, secs)).color(NamedTextColor.YELLOW)));
        String status = licenseBlocked ? "§cBLOCKED" : (licenseVerified ? "§aACTIVE" : "§ePENDING");
        sender.sendMessage(Component.text("License: " + status));
    }

    private void cmdRestart(CommandSender sender, String[] args) {
        int seconds = 30;
        if (args.length > 1) { try { seconds = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {} }
        if (seconds < 5) seconds = 5;
        final int finalSeconds = seconds;
        sender.sendMessage(Component.text("Restarting server in " + finalSeconds + " seconds...").color(NamedTextColor.YELLOW));
        new BukkitRunnable() {
            int remaining = finalSeconds;
            @Override public void run() {
                if (remaining <= 0) { Bukkit.getScheduler().runTask(StratumBootstrap.this, Bukkit::shutdown); cancel(); return; }
                if (remaining <= 5 || remaining % 10 == 0)
                    Bukkit.broadcast(Component.text("⚠ Server restarting in " + remaining + "s...").color(NamedTextColor.YELLOW));
                remaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void cmdUpdate(CommandSender sender) {
        if (!updateReady) {
            File updateFile = new File(getDataFolder().getParentFile().getParentFile(), "stratum-updates/server.jar.update");
            if (updateFile.exists()) {
                updateReady = true;
            } else {
                sender.sendMessage(Component.text("No update available. The server is up to date.").color(NamedTextColor.GREEN));
                return;
            }
        }
        sender.sendMessage(Component.text("Applying server update and restarting...").color(NamedTextColor.YELLOW));
        applyServerUpdate();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private double getTps() {
        try { double[] tps = Bukkit.getServer().getTPS(); return tps.length > 0 ? tps[0] : 20.0; }
        catch (Exception e) { return 20.0; }
    }

    private double getMspt() {
        try { return Bukkit.getServer().getAverageTickTime(); } catch (Exception e) { return 0.0; }
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private long toLong(Object val) {
        if (val == null) return 0;
        try { return val instanceof Number ? ((Number) val).longValue() : Long.parseLong(val.toString()); }
        catch (Exception e) { return 0; }
    }

    /** Parse a top-level array field from JSON like {"key":[{...},{...}]} */
    private List<Map<String, Object>> parseJsonObjectArray(String json, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null) return result;
        String marker = "\"" + key + "\"";
        int start = json.indexOf(marker);
        if (start < 0) return result;
        start = json.indexOf('[', start + marker.length());
        if (start < 0) return result;
        int end = json.lastIndexOf(']');
        if (end <= start) return result;
        String arr = json.substring(start + 1, end).trim();
        if (arr.isEmpty()) return result;
        // Split by },{ boundaries
        int depth = 0; int objStart = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') { if (depth == 0) objStart = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && objStart >= 0) { result.add(parseJson(arr.substring(objStart, i + 1))); objStart = -1; } }
        }
        return result;
    }

    private String asString(Object val) { return val == null ? null : String.valueOf(val); }

    // ── Server auto-update ──────────────────────────────────────────────

    private void checkForServerUpdate() {
        try {
            io.papermc.paper.ServerBuildInfo build = io.papermc.paper.ServerBuildInfo.buildInfo();
            currentBuild = build.buildNumber().isPresent() ? String.valueOf(build.buildNumber().getAsInt()) : "unknown";
        } catch (Exception e) {
            currentBuild = "unknown";
        }

        CompletableFuture.runAsync(() -> {
            try {
                String response = sendApiRequest("GET", "/../builds/latest", null);
                if (response == null) return;
                Map<String, Object> map = parseJson(response);
                String remoteVersion = asString(map.get("version"));
                if (remoteVersion == null) return;
                String remoteBuild = remoteVersion.contains("-") ? remoteVersion.substring(remoteVersion.lastIndexOf('-') + 1) : remoteVersion;
                if (currentBuild.equals("unknown") || Integer.parseInt(remoteBuild) > Integer.parseInt(currentBuild)) {
                    getLogger().info("Update available: build " + remoteBuild + " (current: " + currentBuild + ")");
                    getLogger().info("Downloading update...");
                    boolean downloaded = downloadServerUpdate(asString(map.get("filename")));
                    if (downloaded) {
                        updateReady = true;
                        getLogger().warning("============================================================");
                        getLogger().warning("  Server update ready! Run /stb update to apply.");
                        getLogger().warning("  Current: build #" + currentBuild + " | New: build #" + remoteBuild);
                        getLogger().warning("============================================================");
                    }
                } else {
                    getLogger().info("Server is up to date (build #" + currentBuild + ").");
                }
            } catch (Exception e) {
                getLogger().warning("Update check failed: " + e.getMessage());
            }
        });
    }

    private boolean downloadServerUpdate(String filename) {
        try {
            File updateDir = new File(getDataFolder().getParentFile().getParentFile(), "stratum-updates");
            if (!updateDir.exists()) updateDir.mkdirs();
            File updateFile = new File(updateDir, "server.jar.update");

            String downloadUrl = SITE_BASE + "/api/builds/latest/download";
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);

            if (conn.getResponseCode() != 200) {
                getLogger().warning("Download failed: HTTP " + conn.getResponseCode());
                conn.disconnect();
                return false;
            }

            java.io.InputStream in = conn.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(updateFile);
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                total += len;
            }
            out.flush();
            out.close();
            in.close();
            conn.disconnect();

            getLogger().info("Downloaded " + (total / 1024 / 1024) + "MB to " + updateFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to download update: " + e.getMessage());
            return false;
        }
    }

    private void applyServerUpdate() {
        File updateFile = new File(getDataFolder().getParentFile().getParentFile(), "stratum-updates/server.jar.update");
        File serverJar = new File(getDataFolder().getParentFile().getParentFile(), "server.jar");

        if (!updateFile.exists()) {
            getLogger().severe("No update file found at " + updateFile.getAbsolutePath());
            return;
        }

        try {
            getLogger().info("Applying server update...");
            getLogger().info("  Backup: " + serverJar.getAbsolutePath() + ".bak");

            // Backup current jar
            if (serverJar.exists()) {
                java.nio.file.Files.copy(serverJar.toPath(), new File(serverJar.getAbsolutePath() + ".bak").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Copy update
            java.nio.file.Files.copy(updateFile.toPath(), serverJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            updateFile.delete();

            getLogger().info("Update applied! Restarting server in 5 seconds...");

            // Schedule restart
            new BukkitRunnable() {
                int countdown = 5;
                @Override
                public void run() {
                    if (countdown <= 0) {
                        Bukkit.broadcast(net.kyori.adventure.text.Component.text("Server restarting for update...").color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                        Bukkit.shutdown();
                        cancel();
                        return;
                    }
                    if (countdown <= 3) {
                        Bukkit.broadcast(net.kyori.adventure.text.Component.text("Restarting in " + countdown + "...").color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    }
                    countdown--;
                }
            }.runTaskTimer(this, 0L, 20L);

        } catch (Exception e) {
            getLogger().severe("Failed to apply update: " + e.getMessage());
        }
    }

    private String sendApiRequest(String method, String path, String body) {
        try {
            URL url = new URL(API_BASE + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            if (body != null) { conn.setDoOutput(true); conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) { getLogger().warning("API request failed [" + method + " " + path + "]: " + e.getMessage()); return null; }
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
            if (i >= json.length() || json.charAt(i) != '"') break;
            i++;
            int keyStart = i;
            while (i < json.length() && json.charAt(i) != '"') { if (json.charAt(i) == '\\') i++; i++; }
            String k = json.substring(keyStart, i); i++;
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;
            while (i < json.length() && json.charAt(i) == ' ') i++;
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < json.length()) {
                    if (json.charAt(i) == '\\' && i + 1 < json.length()) { sb.append(json.charAt(i + 1)); i += 2; }
                    else if (json.charAt(i) == '"') break;
                    else { sb.append(json.charAt(i)); i++; }
                }
                map.put(k, sb.toString()); i++;
            } else if (c == 't' || c == 'f') {
                boolean val = json.startsWith("true", i); map.put(k, val); i += val ? 4 : 5;
            } else if (c == 'n') { map.put(k, null); i += 4;
            } else if (c == '[') {
                int depth = 1; int arrStart = i; i++;
                while (i < json.length() && depth > 0) { char ch = json.charAt(i); if (ch == '[') depth++; else if (ch == ']') depth--; i++; }
                map.put(k, json.substring(arrStart, i));
            } else if (c == '{') {
                int depth = 1; int objStart = i; i++;
                while (i < json.length() && depth > 0) { char ch = json.charAt(i); if (ch == '{') depth++; else if (ch == '}') depth--; i++; }
                map.put(k, json.substring(objStart, i));
            } else if (c == '-' || Character.isDigit(c)) {
                int valStart = i;
                while (i < json.length() && (Character.isDigit(json.charAt(i)) || ".\\-eE+".indexOf(json.charAt(i)) >= 0)) i++;
                String numStr = json.substring(valStart, i);
                try { if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) map.put(k, Double.parseDouble(numStr)); else map.put(k, Long.parseLong(numStr)); }
                catch (NumberFormatException nfe) { map.put(k, numStr); }
            }
            while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
            if (i < json.length() && json.charAt(i) == ',') i++;
        }
        return map;
    }

    // ── License file helpers (unchanged) ──────────────────────────────────────

    private String readLicenseFromLocations() {
        Map<String, Integer> keyVotes = new HashMap<>();
        String key;
        key = readLicenseFile(); if (key != null) keyVotes.merge(key, 1, Integer::sum);
        key = readLicenseFromEula(); if (key != null) keyVotes.merge(key, 1, Integer::sum);
        key = readLicenseFromServerProperties(); if (key != null) keyVotes.merge(key, 1, Integer::sum);
        key = readLicenseFromConfig(); if (key != null) keyVotes.merge(key, 1, Integer::sum);
        if (keyVotes.isEmpty()) return null;
        return keyVotes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private void saveLicenseToLocations(String key) {
        writeLicenseFile(key); writeLicenseToEula(key); writeLicenseToServerProperties(key); writeLicenseToConfig(key);
    }

    private String readLicenseFile() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), ".stratum-license");
            if (!file.exists()) return null;
            return asString(parseJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim()).get("key"));
        } catch (Exception e) { return null; }
    }

    private void writeLicenseFile(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), ".stratum-license");
            try (FileWriter fw = new FileWriter(file)) { fw.write("{\"key\":\"" + key + "\",\"fingerprint\":\"" + fingerprint + "\"}\n"); }
        } catch (Exception e) { getLogger().warning("Failed to write .stratum-license: " + e.getMessage()); }
    }

    private String readLicenseFromEula() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "eula.txt");
            if (!file.exists()) return null;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                if (line.startsWith("# Stratum-License:")) return line.substring("# Stratum-License:".length()).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void writeLicenseToEula(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "eula.txt");
            List<String> lines = file.exists() ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>(Collections.singletonList("eula=true"));
            lines.removeIf(l -> l.startsWith("# Stratum-License:")); lines.add("# Stratum-License: " + key);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) { getLogger().warning("Failed to write eula.txt: " + e.getMessage()); }
    }

    private String readLicenseFromServerProperties() {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "server.properties");
            if (!file.exists()) return null;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                if (line.startsWith("#stratum-license=")) return line.substring("#stratum-license=".length()).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private void writeLicenseToServerProperties(String key) {
        try {
            File file = new File(getDataFolder().getParentFile().getParentFile(), "server.properties");
            List<String> lines = file.exists() ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
            lines.removeIf(l -> l.startsWith("#stratum-license=")); lines.add("#stratum-license=" + key);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) { getLogger().warning("Failed to write server.properties: " + e.getMessage()); }
    }

    private String readLicenseFromConfig() { return getConfig().getString("license-key", null); }
    private void writeLicenseToConfig(String key) { getConfig().set("license-key", key); saveConfig(); }
}
