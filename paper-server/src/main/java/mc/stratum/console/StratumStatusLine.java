package mc.stratum.console;

import mc.stratum.StratumConfig;
import org.bukkit.Bukkit;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Renders a live status bar at the bottom of the JLine terminal.
 *
 * <p>Format: {@code [Stratum] TPS: XX.XX | Players: X/Y | RAM: XXXmb/YYYmb | MSPT: XX.Xms}
 *
 * <p>Updates every 2 seconds. Stops cleanly on {@link #stop()}.
 */
public final class StratumStatusLine {

    private static final Logger LOGGER = Logger.getLogger("StratumStatusLine");

    private final Terminal terminal;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ANSI style helpers via JLine AttributedStyle
    private static final AttributedStyle STYLE_BRACKET = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle STYLE_VALUE   = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.WHITE).bold();
    private static final AttributedStyle STYLE_LABEL   = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT);

    public StratumStatusLine(final Terminal terminal) {
        this.terminal = terminal;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stratum-status-line");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the status-line update loop (no-op if already running or disabled in config).
     */
    public void start() {
        if (!StratumConfig.statusLineEnabled) {
            LOGGER.fine("[Stratum] Status line disabled by config.");
            return;
        }
        if (running.compareAndSet(false, true)) {
            future = scheduler.scheduleAtFixedRate(this::render, 0L, 2L, TimeUnit.SECONDS);
        }
    }

    /** Stops the update loop and clears the status line. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (future != null) future.cancel(false);
            clearLine();
        }
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private void render() {
        if (!StratumConfig.statusLineEnabled) {
            stop();
            return;
        }

        try {
            final String status = buildStatusLine();
            // Print the status at the bottom of the terminal without disturbing log output.
            // JLine's terminal writer moves to the bottom row, prints, then resets position.
            final PrintWriter writer = terminal.writer();
            // Save cursor, move to last line, clear it, print status, restore cursor
            writer.print("\033[s");             // save cursor
            writer.print("\033[999;1H");        // move to bottom row
            writer.print("\033[2K");            // clear line
            writer.print(status);
            writer.print("\033[u");             // restore cursor
            writer.flush();
        } catch (Exception ex) {
            // Terminal may not support repositioning — silently suppress
        }
    }

    private void clearLine() {
        try {
            final PrintWriter writer = terminal.writer();
            writer.print("\033[s\033[999;1H\033[2K\033[u");
            writer.flush();
        } catch (Exception ignored) {}
    }

    private static String buildStatusLine() {
        final Runtime rt = Runtime.getRuntime();
        final long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
        final long maxMb  = rt.maxMemory() / 1_048_576L;

        final int onlinePlayers = Bukkit.getOnlinePlayers().size();
        final int maxPlayers    = Bukkit.getMaxPlayers();

        final double tps  = getTps1m();
        final double mspt = getMspt();

        return String.format(
                "[Stratum] TPS: %.2f | Players: %d/%d | RAM: %dmb/%dmb | MSPT: %.1fms",
                tps, onlinePlayers, maxPlayers, usedMb, maxMb, mspt
        );
    }

    private static double getTps1m() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return tps.length > 0 ? tps[0] : 20.0;
        } catch (Exception e) {
            return 20.0;
        }
    }

    private static double getMspt() {
        try {
            // Paper API: getAverageTickTime() returns milliseconds per tick
            return Bukkit.getServer().getAverageTickTime();
        } catch (Exception e) {
            return 0.0;
        }
    }
}
