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
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.meta.SkullMeta;

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
    private static final String STRATUM_VERSION = "2.0";

    private volatile boolean serverHasPro = false;
    private volatile boolean isUpdating = false;
    private volatile String updatingMotdBackup = null;

    private static final String[] MOTD_ANIMATED = {
            "§bStratumServer §7┃ §fWhere performance meets §b§lprecision",
            "§bStratumServer §7┃ §fPowered by §bStratumMC §7v" + STRATUM_VERSION,
            "§bStratumServer §7┃ §fNow with §bAI-powered §fserver management",
            "§bStratumServer §7┃ §f§l%d§7/§f%max% §bplayers online"
    };
    private int motdAnimIndex = 0;

    // ── GUI tracking ───
    private final Map<UUID, String> guiPages = new HashMap<>();

    // ── AI state ───
    private final Map<UUID, String> aiSessions = new HashMap<>();
    // ANSI blue gradient codes for console output
    private static final String[] BLUE = {"\033[38;5;21m","\033[38;5;27m","\033[38;5;33m","\033[38;5;39m","\033[38;5;45m","\033[38;5;51m"};
    private static final String BOLD  = "\033[1m";
    private static final String RESET = "\033[0m";

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
                checkProStatus();
                printStartupBanner();
                printEasterEgg();
                startHeartbeat();
                startTabListTask();
                scheduleBackups();
                startDashTasks();
                checkForServerUpdate();
                startWatchdog();
                // flush player events every 30s
                new BukkitRunnable() { @Override public void run() { flushPlayerEvents(); } }
                    .runTaskTimerAsynchronously(StratumBootstrap.this, 600L, 600L);
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
        if (licenseBlocked) event.setMotd(SUSPENDED_MOTD);
        // custom MOTD handled by onPingMotd at LOW priority
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
            case "restart-old": cmdRestart(sender, args); break;
            case "update":   cmdUpdate(sender); break;
            case "dash":      cmdDash(sender, args); break;
            case "vanish":    cmdVanish(sender, args); break;
            case "snapshot":  cmdSnapshot(sender, args); break;
            case "restart":   cmdRestartSchedule(sender, args); break;
            case "whitelist": cmdWhitelist(sender, args); break;
            case "gamemode":
            case "gm":        cmdGamemode(sender, args); break;
            case "fly":       cmdFly(sender); break;
            case "speed":     cmdSpeed(sender, args); break;
            case "heal":      cmdHeal(sender); break;
            case "feed":      cmdFeed(sender); break;
            case "ping":      cmdPing(sender); break;
            case "near":      cmdNear(sender, args); break;
            case "clear":     cmdClear(sender); break;
            case "tp":
            case "teleport":  cmdTeleport(sender, args); break;
            case "time":      cmdTime(sender, args); break;
            case "ai":        cmdAi(sender, args); break;
            case "gui":       cmdGui(sender, args); break;
            default:          sendUsage(sender); break;
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("=== Stratum Bootstrap ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/stb license").color(NamedTextColor.WHITE).append(Component.text(" - License info").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb version").color(NamedTextColor.WHITE).append(Component.text(" - Version banner").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb stats").color(NamedTextColor.WHITE).append(Component.text(" - Server stats").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb restart [seconds]").color(NamedTextColor.WHITE).append(Component.text(" - Restart server").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb restart now|cancel").color(NamedTextColor.WHITE).append(Component.text(" - Schedule/cancel restart").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb update").color(NamedTextColor.WHITE).append(Component.text(" - Apply server update").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb dash link").color(NamedTextColor.WHITE).append(Component.text(" - Link to dashboard (step 1)").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb dash link <code>").color(NamedTextColor.WHITE).append(Component.text(" - Confirm link (step 2)").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb vanish").color(NamedTextColor.WHITE).append(Component.text(" - Toggle vanish (perm: stratum.vanish)").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb snapshot save [name]").color(NamedTextColor.WHITE).append(Component.text(" - Take world snapshot").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb snapshot list").color(NamedTextColor.WHITE).append(Component.text(" - List snapshots").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb whitelist on|off").color(NamedTextColor.WHITE).append(Component.text(" - Toggle smart whitelist").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb gamemode <mode>").color(NamedTextColor.WHITE).append(Component.text(" - Change gamemode").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb fly").color(NamedTextColor.WHITE).append(Component.text(" - Toggle flight").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb speed <0.1-10>").color(NamedTextColor.WHITE).append(Component.text(" - Set fly/walk speed").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb heal").color(NamedTextColor.WHITE).append(Component.text(" - Heal self").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb feed").color(NamedTextColor.WHITE).append(Component.text(" - Feed self").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb ping").color(NamedTextColor.WHITE).append(Component.text(" - Check ping").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb near [range]").color(NamedTextColor.WHITE).append(Component.text(" - List nearby players").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb clear").color(NamedTextColor.WHITE).append(Component.text(" - Clear inventory").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb tp <player>").color(NamedTextColor.WHITE).append(Component.text(" - Teleport to player").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb time <day|night|noon|midnight>").color(NamedTextColor.WHITE).append(Component.text(" - Set world time").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb ai <query>").color(NamedTextColor.WHITE).append(Component.text(" - AI server assistant").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/stb gui").color(NamedTextColor.WHITE).append(Component.text(" - Open management GUI").color(NamedTextColor.GRAY)));
    }

    private void printEasterEgg() {
        String[] eggs = {
            "Did you know? The first Minecraft server ran on a potato.",
            "Stratum — because 'Paper' was already taken.",
            "This server has more uptime than my sleep schedule.",
            "Build #46 — still fewer bugs than Windows Vista.",
            "If you can read this, you're too close to the console.",
            "Powered by determination, caffeine, and Java 25.",
            "This console message brought to you by the letter 'S'.",
            "StratumMC: Now with 100% more blocks.",
            "The chunk just loaded. Be nice to it.",
            "Your ping is fine. It's the server that's wrong.",
        };
        getLogger().info("");
        getLogger().info("  " + "\033[38;5;45m" + "⚡ " + eggs[new Random().nextInt(eggs.length)] + "\033[0m");
        getLogger().info("");
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

        isUpdating = true;
        updatingMotdBackup = customMotd;
        customMotd = null;

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
            if (licenseKey != null) conn.setRequestProperty("Authorization", "Bearer " + licenseKey);
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

    // ══════════════════════════════════════════════════════════════════════════
    // PRO STATUS CHECK + BLUE VERSION BANNER
    // ══════════════════════════════════════════════════════════════════════════

    private void checkProStatus() {
        try {
            String resp = sendApiRequest("GET", "/../pro/check", null);
            serverHasPro = resp != null && resp.contains("\"pro\":true");
        } catch (Exception e) {
            serverHasPro = false;
        }
        printBlueVersionBanner();
    }

    private void printBlueVersionBanner() {
        io.papermc.paper.ServerBuildInfo build;
        try { build = io.papermc.paper.ServerBuildInfo.buildInfo(); } catch (Exception e) { build = null; }
        String mc = build != null ? build.minecraftVersionId() : "?";
        String buildNum = build != null && build.buildNumber().isPresent() ? String.valueOf(build.buildNumber().getAsInt()) : "?";

        String[] art = {
            "  ██████╗ ████████╗██████╗  █████╗ ████████╗██╗   ██╗███╗   ███╗",
            "  ██╔════╝╚══██╔══╝██╔══██╗██╔══██╗╚══██╔══╝██║   ██║████╗ ████║",
            "  ███████╗   ██║   ██████╔╝███████║   ██║   ██║   ██║██╔████╔██║",
            "  ╚════██║   ██║   ██╔══██╗██╔══██║   ██║   ██║   ██║██║╚██╔╝██║",
            "  ███████║   ██║   ██║  ██║██║  ██║   ██║   ╚██████╔╝██║ ╚═╝ ██║",
            "  ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝    ╚═════╝ ╚═╝     ╚═╝"
        };

        getLogger().info("");
        for (int i = 0; i < art.length; i++) {
            getLogger().info(BLUE[i % BLUE.length] + BOLD + art[i] + RESET);
        }
        getLogger().info("");
        getLogger().info(BLUE[2] + BOLD + "  Stratum " + STRATUM_VERSION + RESET
            + "  ·  Minecraft " + BLUE[4] + mc + RESET
            + "  ·  Build " + BLUE[5] + "#" + buildNum + RESET);
        getLogger().info(BLUE[1] + "  Plugin " + BOLD + "v" + getDescription().getVersion() + RESET
            + (serverHasPro ? "  " + BLUE[4] + BOLD + "◆ PRO" + RESET : "  " + "\033[90m◇ FREE\033[0m"));
        getLogger().info(BLUE[0] + "  stratumserver.net" + RESET);
        getLogger().info("");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VANISH SYSTEM
    // ══════════════════════════════════════════════════════════════════════════

    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();

    public void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && !vanishedPlayers.contains(other.getUniqueId())) {
                other.hidePlayer(this, player);
            }
        }
        player.sendMessage(Component.text("You are now vanished.").color(NamedTextColor.GRAY));
        CompletableFuture.runAsync(() -> sendDashApiRequest("POST", "/vanish/set",
            "{\"uuid\":\"" + player.getUniqueId() + "\",\"name\":\"" + player.getName() + "\",\"vanished\":true}"));
    }

    public void unvanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(this, player);
        }
        player.sendMessage(Component.text("You are no longer vanished.").color(NamedTextColor.GREEN));
        CompletableFuture.runAsync(() -> sendDashApiRequest("POST", "/vanish/set",
            "{\"uuid\":\"" + player.getUniqueId() + "\",\"name\":\"" + player.getName() + "\",\"vanished\":false}"));
    }

    public boolean isVanished(UUID uuid) { return vanishedPlayers.contains(uuid); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoinVanishCheck(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        // Hide vanished players from the joining player
        for (UUID vid : vanishedPlayers) {
            Player vp = Bukkit.getPlayer(vid);
            if (vp != null && !vp.equals(joining)) joining.hidePlayer(this, vp);
        }
        // Hide joining player from others if they're vanished
        if (vanishedPlayers.contains(joining.getUniqueId())) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(joining)) other.hidePlayer(this, joining);
            }
            event.joinMessage(null);
        }
        // Log join event
        logPlayerEvent(joining.getUniqueId().toString(), joining.getName(), "join");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuitVanishCheck(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        if (vanishedPlayers.contains(quitting.getUniqueId())) {
            vanishedPlayers.remove(quitting.getUniqueId());
            event.quitMessage(null);
            CompletableFuture.runAsync(() -> sendDashApiRequest("POST", "/vanish/set",
                "{\"uuid\":\"" + quitting.getUniqueId() + "\",\"name\":\"" + quitting.getName() + "\",\"vanished\":false}"));
        }
        logPlayerEvent(quitting.getUniqueId().toString(), quitting.getName(), "quit");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SMART WHITELIST
    // ══════════════════════════════════════════════════════════════════════════

    private volatile boolean whitelistEnabled = false;

    public void setWhitelistEnabled(boolean enabled) {
        whitelistEnabled = enabled;
        if (enabled) getLogger().info("[Stratum] Smart Whitelist enabled.");
        else getLogger().info("[Stratum] Smart Whitelist disabled.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (!whitelistEnabled) return;
        try {
            String name = event.getName();
            String uuid = event.getUniqueId().toString();
            String resp = sendApiRequest("GET", "/whitelist/check?name=" + name + "&uuid=" + uuid, null);
            if (resp == null || !resp.contains("\"allowed\":true")) {
                event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        net.kyori.adventure.text.Component.text("You are not whitelisted on this server."));
            }
        } catch (Exception e) {
            getLogger().warning("[Stratum] Whitelist check failed for " + event.getName() + ": " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PERFORMANCE WATCHDOG
    // ══════════════════════════════════════════════════════════════════════════

    private double watchdogTpsThreshold = 15.0;
    private boolean watchdogEnabled = false;
    private int watchdogViewDistanceNormal = 10;
    private int watchdogViewDistanceReduced = 4;
    private boolean watchdogReduced = false;

    private void startWatchdog() {
        watchdogEnabled = getConfig().getBoolean("watchdog.enabled", false);
        watchdogTpsThreshold = getConfig().getDouble("watchdog.tps-threshold", 15.0);
        watchdogViewDistanceNormal = getConfig().getInt("watchdog.view-distance-normal", 10);
        watchdogViewDistanceReduced = getConfig().getInt("watchdog.view-distance-reduced", 4);
        if (!watchdogEnabled) return;
        new BukkitRunnable() {
            @Override public void run() {
                if (!watchdogEnabled) return;
                double tps = Bukkit.getTPS()[0];
                if (tps < watchdogTpsThreshold && !watchdogReduced) {
                    watchdogReduced = true;
                    Bukkit.getWorlds().forEach(w -> w.setViewDistance(watchdogViewDistanceReduced));
                    getLogger().warning("[Stratum Watchdog] TPS " + String.format("%.1f", tps) + " < " + watchdogTpsThreshold + " — reduced view distance to " + watchdogViewDistanceReduced);
                    Bukkit.broadcast(Component.text("[Stratum] Server under load — temporarily reducing view distance.").color(NamedTextColor.YELLOW));
                } else if (tps >= watchdogTpsThreshold + 2 && watchdogReduced) {
                    watchdogReduced = false;
                    Bukkit.getWorlds().forEach(w -> w.setViewDistance(watchdogViewDistanceNormal));
                    getLogger().info("[Stratum Watchdog] TPS recovered to " + String.format("%.1f", tps) + " — restored view distance to " + watchdogViewDistanceNormal);
                }
            }
        }.runTaskTimer(this, 200L, 200L); // check every 10s
        getLogger().info("[Stratum] Performance Watchdog active (threshold: " + watchdogTpsThreshold + " TPS).");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULED RESTARTS
    // ══════════════════════════════════════════════════════════════════════════

    private BukkitRunnable restartCountdownTask;
    private volatile boolean restartScheduled = false;

    public void scheduleRestart(int warningSeconds) {
        if (restartScheduled) {
            Bukkit.getScheduler().runTask(this, () ->
                Bukkit.broadcast(Component.text("[Stratum] A restart is already scheduled.").color(NamedTextColor.YELLOW)));
            return;
        }
        restartScheduled = true;
        restartCountdownTask = new BukkitRunnable() {
            int remaining = warningSeconds;
            @Override public void run() {
                if (remaining <= 0) {
                    Bukkit.broadcast(Component.text("[Stratum] Restarting now...").color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
                    cancel(); return;
                }
                if (remaining == warningSeconds || remaining == 60 || remaining == 30 || remaining == 10 || remaining <= 5) {
                    String msg = remaining >= 60 ? (remaining / 60) + " minute" + (remaining / 60 > 1 ? "s" : "") : remaining + " second" + (remaining > 1 ? "s" : "");
                    Bukkit.broadcast(Component.text("[Stratum] Server restarting in " + msg + ".").color(NamedTextColor.GOLD));
                }
                remaining--;
            }
        };
        restartCountdownTask.runTaskTimer(this, 0L, 20L);
    }

    public void cancelRestart() {
        if (restartCountdownTask != null) restartCountdownTask.cancel();
        restartScheduled = false;
        Bukkit.broadcast(Component.text("[Stratum] Scheduled restart cancelled.").color(NamedTextColor.GREEN));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CUSTOM MOTD
    // ══════════════════════════════════════════════════════════════════════════

    private String customMotd = null;
    private long motdLastFetch = 0;

    private void refreshMotd() {
        if (System.currentTimeMillis() - motdLastFetch < 60_000) return;
        motdLastFetch = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                String resp = sendApiRequest("GET", "/motd", null);
                if (resp != null && resp.contains("\"motd\":")) {
                    int start = resp.indexOf("\"motd\":") + 7;
                    if (resp.charAt(start) == '"') {
                        int end = resp.indexOf('"', start + 1);
                        customMotd = resp.substring(start + 1, end);
                    } else {
                        customMotd = null;
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPingMotd(ServerListPingEvent event) {
        if (licenseBlocked) { event.setMotd(SUSPENDED_MOTD); return; }
        if (isUpdating) { event.setMotd("§6⚠ §eUpdating §6⚠ §7| §bstratumserver.net"); return; }
        refreshMotd();
        if (customMotd != null) {
            event.setMotd(customMotd);
            return;
        }
        String line = MOTD_ANIMATED[motdAnimIndex % MOTD_ANIMATED.length];
        motdAnimIndex++;
        line = line.replace("%d", String.valueOf(Bukkit.getOnlinePlayers().size()))
                   .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));
        event.setMotd(line);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WORLD SNAPSHOTS
    // ══════════════════════════════════════════════════════════════════════════

    public void takeSnapshot(CommandSender sender, String name) {
        sender.sendMessage(Component.text("[Stratum] Taking snapshot '" + name + "'...").color(NamedTextColor.YELLOW));
        CompletableFuture.runAsync(() -> {
            try {
                File serverDir = getDataFolder().getParentFile().getParentFile();
                File worldDir = new File(serverDir, "world");
                if (!worldDir.exists()) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(Component.text("[Stratum] World folder not found.").color(NamedTextColor.RED)));
                    return;
                }
                File snapshotsDir = new File(serverDir, "snapshots");
                snapshotsDir.mkdirs();
                File zipFile = new File(snapshotsDir, name.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + System.currentTimeMillis() + ".zip");
                zipDirectory(worldDir, zipFile);
                long size = zipFile.length();
                sendDashApiRequest("POST", "/snapshots/record",
                    "{\"name\":\"" + name + "\",\"world\":\"world\",\"size_bytes\":" + size + "}");
                Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage(Component.text("[Stratum] Snapshot '" + name + "' saved (" + (size / 1024 / 1024) + " MB).").color(NamedTextColor.GREEN)));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage(Component.text("[Stratum] Snapshot failed: " + e.getMessage()).color(NamedTextColor.RED)));
            }
        });
    }

    public void listSnapshots(CommandSender sender) {
        File snapshotsDir = new File(getDataFolder().getParentFile().getParentFile(), "snapshots");
        if (!snapshotsDir.exists() || snapshotsDir.listFiles() == null) {
            sender.sendMessage(Component.text("[Stratum] No snapshots found.").color(NamedTextColor.GRAY)); return;
        }
        File[] files = snapshotsDir.listFiles((d, n) -> n.endsWith(".zip"));
        if (files == null || files.length == 0) { sender.sendMessage(Component.text("[Stratum] No snapshots found.").color(NamedTextColor.GRAY)); return; }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        sender.sendMessage(Component.text("[Stratum] Snapshots:").color(NamedTextColor.GOLD));
        for (File f : files) {
            sender.sendMessage(Component.text("  " + f.getName() + " (" + (f.length() / 1024 / 1024) + " MB)").color(NamedTextColor.GRAY));
        }
    }

    private void zipDirectory(File dir, File output) throws IOException {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(output))) {
            zipDir(dir, dir.getName(), zos);
        }
    }

    private void zipDir(File dir, String prefix, java.util.zip.ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) { zipDir(f, prefix + "/" + f.getName(), zos); continue; }
            try (InputStream in = new FileInputStream(f)) {
                zos.putNextEntry(new java.util.zip.ZipEntry(prefix + "/" + f.getName()));
                byte[] buf = new byte[8192]; int len;
                while ((len = in.read(buf)) > 0) zos.write(buf, 0, len);
                zos.closeEntry();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAYER EVENT LOGGING
    // ══════════════════════════════════════════════════════════════════════════

    private final List<String[]> playerEventBatch = Collections.synchronizedList(new ArrayList<>());

    private void logPlayerEvent(String uuid, String name, String event) {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date());
        playerEventBatch.add(new String[]{uuid, name, event, ts});
        if (playerEventBatch.size() >= 10) flushPlayerEvents();
    }

    private void flushPlayerEvents() {
        List<String[]> batch;
        synchronized (playerEventBatch) {
            batch = new ArrayList<>(playerEventBatch);
            playerEventBatch.clear();
        }
        if (batch.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"fingerprint\":\"").append(fingerprint).append("\",\"events\":[");
        for (int i = 0; i < batch.size(); i++) {
            String[] e = batch.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"uuid\":\"").append(e[0]).append("\",\"name\":\"").append(e[1])
              .append("\",\"event\":\"").append(e[2]).append("\",\"ts\":\"").append(e[3]).append("\"}");
        }
        sb.append("]}");
        final String body = sb.toString();
        CompletableFuture.runAsync(() -> sendDashApiRequest("POST", "/player-events/push", body));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STB COMMAND ADDITIONS
    // ══════════════════════════════════════════════════════════════════════════

    private void cmdVanish(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (!sender.hasPermission("stratum.vanish")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        Player player = (Player) sender;
        if (isVanished(player.getUniqueId())) unvanish(player);
        else vanish(player);
    }

    private void cmdSnapshot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.snapshot") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return;
        }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /stb snapshot <save|list> [name]").color(NamedTextColor.YELLOW)); return; }
        switch (args[1].toLowerCase()) {
            case "save" -> takeSnapshot(sender, args.length > 2 ? args[2] : "snapshot");
            case "list" -> listSnapshots(sender);
            default -> sender.sendMessage(Component.text("Usage: /stb snapshot <save|list> [name]").color(NamedTextColor.YELLOW));
        }
    }

    private void cmdRestartSchedule(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.restart") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /stb restart <now|<seconds>|cancel>").color(NamedTextColor.YELLOW)); return;
        }
        switch (args[1].toLowerCase()) {
            case "now" -> scheduleRestart(10);
            case "cancel" -> cancelRestart();
            default -> {
                try { scheduleRestart(Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(Component.text("Invalid seconds.").color(NamedTextColor.RED)); }
            }
        }
    }

    private void cmdWhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.whitelist") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /stb whitelist <on|off>").color(NamedTextColor.YELLOW)); return;
        }
        switch (args[1].toLowerCase()) {
            case "on"  -> { setWhitelistEnabled(true);  sender.sendMessage(Component.text("Smart Whitelist enabled.").color(NamedTextColor.GREEN)); }
            case "off" -> { setWhitelistEnabled(false); sender.sendMessage(Component.text("Smart Whitelist disabled.").color(NamedTextColor.YELLOW)); }
            default    -> sender.sendMessage(Component.text("Usage: /stb whitelist <on|off>").color(NamedTextColor.YELLOW));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEW INGAME FEATURES
    // ══════════════════════════════════════════════════════════════════════════

    private void cmdGamemode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.gamemode") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return;
        }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /stb gamemode <survival|creative|adventure|spectator>").color(NamedTextColor.YELLOW)); return; }
        Player p = (Player) sender;
        org.bukkit.GameMode gm;
        switch (args[1].toLowerCase()) {
            case "survival": case "s": gm = org.bukkit.GameMode.SURVIVAL; break;
            case "creative": case "c": gm = org.bukkit.GameMode.CREATIVE; break;
            case "adventure": case "a": gm = org.bukkit.GameMode.ADVENTURE; break;
            case "spectator": case "sp": gm = org.bukkit.GameMode.SPECTATOR; break;
            default: sender.sendMessage(Component.text("Invalid gamemode.").color(NamedTextColor.RED)); return;
        }
        p.setGameMode(gm);
        p.sendMessage(Component.text("Gamemode updated to " + gm.name().toLowerCase() + ".").color(NamedTextColor.GREEN));
    }

    private void cmdFly(CommandSender sender) {
        if (!sender.hasPermission("stratum.fly")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        p.setAllowFlight(!p.getAllowFlight());
        p.sendMessage(Component.text(p.getAllowFlight() ? "Flight enabled." : "Flight disabled.").color(p.getAllowFlight() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void cmdSpeed(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.speed")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /stb speed <0.1-10>").color(NamedTextColor.YELLOW)); return; }
        Player p = (Player) sender;
        try {
            float speed = Float.parseFloat(args[1]);
            speed = Math.max(0.1f, Math.min(10f, speed));
            if (p.isFlying()) p.setFlySpeed(speed / 10f);
            else p.setWalkSpeed(speed / 10f);
            p.sendMessage(Component.text("Speed set to " + String.format("%.1f", speed) + ".").color(NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid speed.").color(NamedTextColor.RED));
        }
    }

    private void cmdHeal(CommandSender sender) {
        if (!sender.hasPermission("stratum.heal")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        p.setHealth(p.getMaxHealth());
        p.setFireTicks(0);
        p.sendMessage(Component.text("Healed.").color(NamedTextColor.GREEN));
    }

    private void cmdFeed(CommandSender sender) {
        if (!sender.hasPermission("stratum.feed")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        p.setFoodLevel(20);
        p.setSaturation(10f);
        p.sendMessage(Component.text("Fed.").color(NamedTextColor.GREEN));
    }

    private void cmdPing(CommandSender sender) {
        if (!sender.hasPermission("stratum.ping")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        int ping = p.getPing();
        NamedTextColor color = ping < 50 ? NamedTextColor.GREEN : ping < 150 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        p.sendMessage(Component.text("Your ping: " + ping + "ms").color(color));
    }

    private void cmdNear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.near")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        double range = 50;
        if (args.length > 1) { try { range = Double.parseDouble(args[1]); } catch (NumberFormatException ignored) {} }
        List<String> nearby = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            if (p.getWorld().equals(other.getWorld()) && p.getLocation().distance(other.getLocation()) <= range) {
                nearby.add(other.getName() + " (" + (int) p.getLocation().distance(other.getLocation()) + "m)");
            }
        }
        if (nearby.isEmpty()) {
            p.sendMessage(Component.text("No players within " + (int) range + "m.").color(NamedTextColor.GRAY));
        } else {
            p.sendMessage(Component.text("Nearby (" + (int) range + "m): " + String.join(", ", nearby)).color(NamedTextColor.GREEN));
        }
    }

    private void cmdClear(CommandSender sender) {
        if (!sender.hasPermission("stratum.clear")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        p.getInventory().clear();
        p.sendMessage(Component.text("Inventory cleared.").color(NamedTextColor.GREEN));
    }

    private void cmdTeleport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.teleport")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /stb tp <player>").color(NamedTextColor.YELLOW)); return; }
        Player p = (Player) sender;
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            p.sendMessage(Component.text("Player not found.").color(NamedTextColor.RED)); return;
        }
        p.teleport(target.getLocation());
        p.sendMessage(Component.text("Teleported to " + target.getName() + ".").color(NamedTextColor.GREEN));
    }

    private void cmdTime(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.time")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (args.length < 2) { sender.sendMessage(Component.text("Usage: /stb time <day|night|noon|midnight>").color(NamedTextColor.YELLOW)); return; }
        Player p = (Player) sender;
        long time;
        switch (args[1].toLowerCase()) {
            case "day": time = 1000; break;
            case "night": time = 13000; break;
            case "noon": time = 6000; break;
            case "midnight": time = 18000; break;
            default: sender.sendMessage(Component.text("Usage: /stb time <day|night|noon|midnight>").color(NamedTextColor.YELLOW)); return;
        }
        p.getWorld().setTime(time);
        p.sendMessage(Component.text("Time set to " + args[1].toLowerCase() + ".").color(NamedTextColor.GREEN));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AI COMMAND
    // ══════════════════════════════════════════════════════════════════════════

    private void cmdAi(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stratum.admin") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /stb ai <query>").color(NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Example: /stb ai why is the server lagging").color(NamedTextColor.GRAY));
            return;
        }
        StringBuilder query = new StringBuilder();
        for (int i = 1; i < args.length; i++) query.append(args[i]).append(" ");
        String q = query.toString().trim();

        sender.sendMessage(Component.text("Stratum AI is thinking...").color(NamedTextColor.GRAY));

        CompletableFuture.runAsync(() -> {
            try {
                String context = buildAiContext();
                String body = "{\"query\":" + jsonString(q) + ",\"context\":" + context + ",\"licenseKey\":" + jsonString(licenseKey) + "}";
                String response = sendApiRequest("POST", "/ai/query", body);
                if (response == null) {
                    Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage(Component.text("AI request failed. Is the AI server running?").color(NamedTextColor.RED)));
                    return;
                }
                Map<String, Object> map = parseJson(response);
                String answer = asString(map.get("answer"));
                String toolCmd = asString(map.get("command"));

                String finalAnswer = answer != null ? answer : "No response from AI.";
                Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage(Component.text("◆ ").color(NamedTextColor.AQUA)
                        .append(Component.text("AI: ").color(NamedTextColor.GOLD))
                        .append(Component.text(finalAnswer).color(NamedTextColor.WHITE))));

                if (toolCmd != null && !toolCmd.isEmpty()) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        sender.sendMessage(Component.text("◆ AI is running: " + toolCmd).color(NamedTextColor.GRAY));
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toolCmd);
                    });
                }
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () ->
                    sender.sendMessage(Component.text("AI error: " + e.getMessage()).color(NamedTextColor.RED)));
            }
        });
    }

    private String buildAiContext() {
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        long maxMB = rt.maxMemory() / 1048576;
        double tps = getTps();
        double mspt = getMspt();
        long uptimeSec = (System.currentTimeMillis() - serverStartTime) / 1000;

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"tps\":").append(String.format("%.1f", tps)).append(",");
        sb.append("\"mspt\":").append(String.format("%.1f", mspt)).append(",");
        sb.append("\"ram_used_mb\":").append(usedMB).append(",");
        sb.append("\"ram_max_mb\":").append(maxMB).append(",");
        sb.append("\"uptime_seconds\":").append(uptimeSec).append(",");
        sb.append("\"online_players\":").append(Bukkit.getOnlinePlayers().size()).append(",");
        sb.append("\"max_players\":").append(Bukkit.getMaxPlayers()).append(",");
        sb.append("\"version\":").append(jsonString(getDescription().getVersion())).append(",");
        sb.append("\"build\":").append(jsonString(currentBuild)).append(",");
        sb.append("\"has_pro\":").append(serverHasPro).append(",");
        sb.append("\"is_updating\":").append(isUpdating).append(",");
        sb.append("\"license_active\":").append(licenseVerified && !licenseBlocked);
        sb.append("}");
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GUI COMMAND
    // ══════════════════════════════════════════════════════════════════════════

    private void cmdGui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(Component.text("Players only.").color(NamedTextColor.RED)); return; }
        if (!sender.hasPermission("stratum.admin")) { sender.sendMessage(Component.text("No permission.").color(NamedTextColor.RED)); return; }
        Player p = (Player) sender;
        openMainGui(p);
    }

    private void openMainGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§8◆ Stratum Control Panel");
        guiPages.put(p.getUniqueId(), "main");

        // Row 1: Categories
        inv.setItem(0, makeItem(Material.PLAYER_HEAD, "§bPlayer Tools", "§7Manage players and sessions"));
        inv.setItem(1, makeItem(Material.COMPASS, "§bWorld", "§7World management & time"));
        inv.setItem(2, makeItem(Material.CLOCK, "§bServer", "§7Server stats & performance"));
        inv.setItem(3, makeItem(Material.REDSTONE, "§bAdmin", "§7Whitelist, restart, updates"));
        inv.setItem(4, makeItem(Material.BEACON, "§bAI Assistant", "§7Ask the AI anything"));
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", "§7Close the GUI"));

        // Separator
        ItemStack sep = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 9; i < 18; i++) inv.setItem(i, sep);

        // Player Tools (Row 3-5)
        inv.setItem(18, makeItem(Material.DIAMOND_SWORD, "§eGamemode", "§7Click to change"));
        inv.setItem(19, makeItem(Material.FEATHER, "§eFly", "§7Toggle flight"));
        inv.setItem(20, makeItem(Material.SUGAR, "§eSpeed", "§7Set fly/walk speed"));
        inv.setItem(21, makeItem(Material.GOLDEN_APPLE, "§eHeal", "§7Full heal"));
        inv.setItem(22, makeItem(Material.COOKED_BEEF, "§eFeed", "§7Restore hunger"));
        inv.setItem(23, makeItem(Material.ENDER_PEARL, "§eTeleport", "§7Teleport to player"));
        inv.setItem(24, makeItem(Material.COMPARATOR, "§ePing", "§7Check your ping"));
        inv.setItem(25, makeItem(Material.ENDER_EYE, "§eNear", "§7Find nearby players"));
        inv.setItem(26, makeItem(Material.TRIDENT, "§eVanish", "§7Toggle vanish"));

        // World Tools (Row 4)
        inv.setItem(27, makeItem(Material.SUNFLOWER, "§eTime: Day", "§7Set world to daytime"));
        inv.setItem(28, makeItem(Material.CLOCK, "§eSnapshot", "§7Take world snapshot"));

        // Server (Row 4)
        inv.setItem(29, makeItem(Material.REDSTONE_TORCH, "§eStats", "§7View server statistics"));

        // Admin (Row 5)
        inv.setItem(36, makeItem(Material.SHIELD, "§eWhitelist", "§7Toggle smart whitelist"));
        inv.setItem(37, makeItem(Material.REPEATER, "§eRestart", "§7Restart the server"));
        inv.setItem(38, makeItem(Material.ENDER_CHEST, "§eUpdate", "§7Apply server update"));
        inv.setItem(39, makeItem(Material.ENCHANTED_BOOK, "§eAI Query", "§7Click to type a query"));

        p.openInventory(inv);
    }

    private ItemStack makeItem(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(java.util.Arrays.asList(lore.split("\n")));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!guiPages.containsKey(p.getUniqueId())) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String name = clicked.getItemMeta().getDisplayName();
        int slot = event.getSlot();

        // Close
        if (name.contains("Close") || slot == 8) { p.closeInventory(); guiPages.remove(p.getUniqueId()); return; }

        // Player Tools
        if (name.contains("Gamemode")) { p.closeInventory(); guiPages.remove(p.getUniqueId()); Bukkit.dispatchCommand(p, "stb gamemode"); p.sendMessage(Component.text("Usage: /stb gamemode <survival|creative|adventure|spectator>").color(NamedTextColor.GRAY)); }
        else if (name.contains("Fly")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb fly"); }
        else if (name.contains("Speed")) { p.closeInventory(); guiPages.remove(p.getUniqueId()); Bukkit.dispatchCommand(p, "stb speed"); p.sendMessage(Component.text("Usage: /stb speed <0.1-10>").color(NamedTextColor.GRAY)); }
        else if (name.contains("Heal")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb heal"); }
        else if (name.contains("Feed")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb feed"); }
        else if (name.contains("Teleport")) { p.closeInventory(); guiPages.remove(p.getUniqueId()); Bukkit.dispatchCommand(p, "stb tp"); p.sendMessage(Component.text("Usage: /stb tp <player>").color(NamedTextColor.GRAY)); }
        else if (name.contains("Ping")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb ping"); }
        else if (name.contains("Near")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb near"); }
        else if (name.contains("Vanish")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb vanish"); }

        // World Tools
        else if (name.contains("Day")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb time day"); }
        else if (name.contains("Snapshot")) { p.closeInventory(); guiPages.remove(p.getUniqueId()); Bukkit.dispatchCommand(p, "stb snapshot"); }

        // Server
        else if (name.contains("Stats")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb stats"); }

        // Admin
        else if (name.contains("Whitelist")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb whitelist"); p.sendMessage(Component.text("Usage: /stb whitelist <on|off>").color(NamedTextColor.GRAY)); }
        else if (name.contains("Restart")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb restart"); p.sendMessage(Component.text("Usage: /stb restart <now|<seconds>|cancel>").color(NamedTextColor.GRAY)); }
        else if (name.contains("Update")) { p.closeInventory(); Bukkit.dispatchCommand(p, "stb update"); }

        // AI
        else if (name.contains("AI")) { p.closeInventory(); guiPages.remove(p.getUniqueId()); p.sendMessage(Component.text("Type: /stb ai <your question>").color(NamedTextColor.GRAY)); }
    }
}
