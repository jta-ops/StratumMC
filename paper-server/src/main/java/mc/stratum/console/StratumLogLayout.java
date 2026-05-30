package mc.stratum.console;

import mc.stratum.StratumConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Log4j2 layout that applies ANSI colour coding to console output.
 *
 * <p>Colour scheme:
 * <ul>
 *   <li>INFO  → bold white</li>
 *   <li>WARN  → yellow</li>
 *   <li>ERROR → bold red</li>
 *   <li>DEBUG → dim cyan</li>
 * </ul>
 */
@Plugin(name = "StratumLogLayout", category = "Core", elementType = "layout", printObject = true)
public final class StratumLogLayout extends AbstractStringLayout {

    // ── ANSI codes ─────────────────────────────────────────────────────────────
    private static final String RESET      = "[0m";
    private static final String BOLD_WHITE = "[1;37m";
    private static final String YELLOW     = "[33m";
    private static final String BOLD_RED   = "[1;31m";
    private static final String DIM_CYAN   = "[2;36m";
    private static final String GREY       = "[90m";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Whether console theming is active.
     * This is read from {@link StratumConfig#consoleThemingEnabled} at each log event
     * so that config reloads take effect immediately.
     */
    public static boolean enabled = true;

    // ── Constructor ────────────────────────────────────────────────────────────

    private StratumLogLayout() {
        super(StandardCharsets.UTF_8);
    }

    @PluginFactory
    public static StratumLogLayout createLayout() {
        return new StratumLogLayout();
    }

    // ── Layout implementation ──────────────────────────────────────────────────

    @Override
    public String toSerializable(final LogEvent event) {
        // Sync enabled flag from config (supports live reload)
        enabled = StratumConfig.consoleThemingEnabled;

        final String time    = TIME_FMT.format(Instant.ofEpochMilli(event.getTimeMillis()));
        final String thread  = event.getThreadName();
        final Level  level   = event.getLevel();
        final String module  = deriveModule(event.getLoggerName());
        final String message = event.getMessage().getFormattedMessage();

        if (!enabled) {
            // Plain format — no ANSI codes
            return plainFormat(time, thread, level, module, message, event);
        }

        return ansiFormat(time, thread, level, module, message, event);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String plainFormat(String time, String thread, Level level, String module,
                               String message, LogEvent event) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(time).append("] ");
        sb.append('[').append(thread).append('/').append(level.name()).append("] ");
        sb.append('[').append(module).append("]: ");
        sb.append(message);
        appendThrowable(sb, event);
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    private String ansiFormat(String time, String thread, Level level, String module,
                              String message, LogEvent event) {
        final String colour = levelColour(level);
        final String levelName = level.name();

        StringBuilder sb = new StringBuilder(160);
        // timestamp in grey
        sb.append(GREY).append('[').append(time).append(']').append(RESET).append(' ');
        // thread/level bracket in level colour
        sb.append(colour).append('[').append(thread).append('/').append(levelName).append(']').append(RESET).append(' ');
        // module in grey
        sb.append(GREY).append('[').append(module).append("]: ").append(RESET);
        // message in level colour
        sb.append(colour).append(message).append(RESET);
        appendThrowable(sb, event);
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    private static String levelColour(Level level) {
        if (level == Level.ERROR || level == Level.FATAL) return BOLD_RED;
        if (level == Level.WARN)  return YELLOW;
        if (level == Level.DEBUG || level == Level.TRACE) return DIM_CYAN;
        return BOLD_WHITE; // INFO default
    }

    private static String deriveModule(String loggerName) {
        if (loggerName == null || loggerName.isEmpty()) return "Server";
        // Use simple class name as module label
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot >= 0 ? loggerName.substring(lastDot + 1) : loggerName;
    }

    private static void appendThrowable(StringBuilder sb, LogEvent event) {
        if (event.getThrown() != null) {
            sb.append(System.lineSeparator());
            final Throwable t = event.getThrown();
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage());
            for (StackTraceElement ste : t.getStackTrace()) {
                sb.append(System.lineSeparator()).append("\tat ").append(ste);
            }
        }
    }
}
