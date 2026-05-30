package mc.stratum.update;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Notifies operators on login when a newer Stratum major version is available.
 *
 * <p>The check is performed once per server startup; subsequent logins use the
 * cached result.
 */
public final class VersionNotifier implements Listener {

    private static final Logger LOGGER = Logger.getLogger("VersionNotifier");

    /** Whether the API check has been run this startup. */
    private final AtomicBoolean checked = new AtomicBoolean(false);

    /** Cached result of the last version check. */
    private volatile boolean updateAvailable = false;

    /** The API URL to check against (from config). */
    private final String apiUrl;
    private final UpdateManager updateManager;

    public VersionNotifier(final String apiUrl, final UpdateManager updateManager) {
        this.apiUrl        = apiUrl;
        this.updateManager = updateManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLogin(final PlayerLoginEvent event) {
        if (!event.getPlayer().isOp()) return;

        // Run the check once, lazily, on the first eligible login
        if (checked.compareAndSet(false, true)) {
            final Thread checkThread = new Thread(() -> {
                try {
                    updateAvailable = updateManager.checkForUpdate(apiUrl);
                    if (updateAvailable) {
                        LOGGER.info("[Stratum] A newer version is available: " + UpdateManager.latestVersion);
                    }
                } catch (Exception ex) {
                    LOGGER.warning("[Stratum] Version check failed: " + ex.getMessage());
                }
            }, "stratum-version-check");
            checkThread.setDaemon(true);
            checkThread.start();
            return; // Notification will show on the *next* op login if update found
        }

        if (!updateAvailable) return;

        final String latest = UpdateManager.latestVersion;
        if (latest == null || latest.isBlank()) return;

        // Notify after a small delay so the player is fully logged in
        final org.bukkit.entity.Player player = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                getPlugin(),
                () -> {
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.GOLD + "[Stratum] "
                                + ChatColor.YELLOW + "A new major version is available: "
                                + ChatColor.GREEN + "Stratum "
                                + ChatColor.WHITE + latest
                                + ChatColor.YELLOW + ". Use "
                                + ChatColor.GREEN + "/ST update"
                                + ChatColor.YELLOW + " to schedule.");
                    }
                },
                40L // 2 seconds after login
        );
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    /**
     * Returns the first available Bukkit plugin — used as a scheduling context.
     * The VersionNotifier is always registered via StratumServer which has an active plugin.
     */
    private static org.bukkit.plugin.Plugin getPlugin() {
        final org.bukkit.plugin.Plugin[] plugins = org.bukkit.Bukkit.getPluginManager().getPlugins();
        if (plugins.length > 0) return plugins[0];
        throw new IllegalStateException("No plugins registered — cannot schedule task.");
    }
}
