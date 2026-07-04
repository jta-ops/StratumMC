package mc.stratum.console;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Suppresses non-essential INFO spam on the terminal console during server
 * startup, so only key milestones (version, ports, world prep, done) and any
 * warnings/errors are shown. The full, unfiltered log is still written to
 * {@code logs/latest.log}.
 *
 * <p>The filter disarms itself permanently once the "Done (...s)!" line is
 * seen (or after a 5 minute failsafe), so normal runtime logging is
 * completely unaffected.
 *
 * <p>Opt out with {@code -Dstratum.quiet-startup=false} or by adding
 * {@code quiet-startup: false} under {@code console} in {@code stratum.yml}.
 */
public final class QuietStartupFilter extends AbstractFilter {

    private static final long FAILSAFE_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** Substrings of INFO messages that should always reach the console. */
    private static final List<String> MILESTONES = List.of(
        "Starting minecraft server version",
        "Loading properties",
        "Starting Minecraft server on",
        "Preparing level ",
        "Done (",
        "For help, type",
        "You need to agree to the EULA",
        "Stopping server",
        "Stratum"
    );

    private final long armedAt = System.nanoTime();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean done;

    private QuietStartupFilter() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    @Override
    public Result filter(final LogEvent event) {
        if (this.done) {
            return Result.NEUTRAL;
        }
        if (System.nanoTime() - this.armedAt > FAILSAFE_NANOS) {
            this.done = true;
            return Result.NEUTRAL;
        }
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return Result.NEUTRAL;
        }

        final String loggerName = event.getLoggerName();
        if (loggerName != null && (loggerName.startsWith("Stratum") || loggerName.startsWith("mc.stratum"))) {
            return Result.NEUTRAL;
        }

        final String message = event.getMessage().getFormattedMessage();
        for (final String milestone : MILESTONES) {
            if (message.contains(milestone)) {
                if (message.contains("Done (")) {
                    this.finish();
                }
                return Result.NEUTRAL;
            }
        }

        this.suppressed.incrementAndGet();
        return Result.DENY;
    }

    private void finish() {
        this.done = true;
        final long count = this.suppressed.get();
        if (count > 0) {
            LogManager.getLogger("Stratum").info(
                "Quiet startup hid {} log lines from the console (full log: logs/latest.log, disable with -Dstratum.quiet-startup=false)", count);
        }
    }

    /**
     * Attaches the filter to the terminal console appender only, so the log
     * file keeps the complete startup output. No-op when disabled via system
     * property or {@code quiet-startup: false} in {@code stratum.yml}.
     */
    public static void install() {
        if (!enabled()) {
            return;
        }
        final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        final Appender appender = context.getConfiguration().getAppender("TerminalConsole");
        if (appender instanceof AbstractAppender abstractAppender) {
            abstractAppender.addFilter(new QuietStartupFilter());
        }
    }

    private static boolean enabled() {
        if ("false".equalsIgnoreCase(System.getProperty("stratum.quiet-startup"))) {
            return false;
        }
        try {
            final Path config = Path.of("stratum.yml");
            if (Files.isRegularFile(config)) {
                final String yaml = Files.readString(config);
                if (yaml.contains("quiet-startup: false")) {
                    return false;
                }
            }
        } catch (final Exception ignored) {
            // unreadable config never blocks startup
        }
        return true;
    }
}
