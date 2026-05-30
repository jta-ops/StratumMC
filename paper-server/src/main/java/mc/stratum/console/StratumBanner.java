package mc.stratum.console;

import io.papermc.paper.ServerBuildInfo;
import org.apache.logging.log4j.Logger;

/**
 * Prints the Stratum MC ASCII banner to the server console on startup.
 *
 * <p>The banner uses ANSI escape codes to produce a blue→cyan gradient.
 * Each row of ASCII art uses a slightly shifted hue so the text appears
 * to shimmer across the blue/cyan spectrum.
 */
public final class StratumBanner {

    // ── ANSI gradient colours (blue→cyan) ─────────────────────────────────────
    private static final String[] GRADIENT = {
        "[38;5;19m",   // deep blue
        "[38;5;20m",
        "[38;5;21m",
        "[38;5;27m",
        "[38;5;33m",
        "[38;5;39m",
        "[38;5;45m",
        "[38;5;51m",   // bright cyan
    };
    private static final String RESET    = "[0m";
    private static final String BOLD     = "[1m";
    private static final String GOLD     = "[38;5;220m";

    // ── ASCII art for "STRATUM MC" ─────────────────────────────────────────────
    // Each inner array = one row across all letters
    private static final String[] ART = {
        " _____ _____ ____  ___  _____ _   _ __  __ ____ ",
        "/  ___|_   _|  _ \\/ _ \\|_   _| | | |  \\/  / ___|",
        "\\ `--.  | | | |_) / /_\\ \\ | | | | | | |\\/| | |   ",
        " `--. \\ | | |    /|  _  | | | | |_| | |  | | |___",
        "/\\__/ /_| |_| |\\ \\| | | | | | |___,_|_|  |_|\\____|",
        "\\____/ \\___/_| \\_\\_| |_/ \\_/             MC       ",
    };

    private StratumBanner() {}

    /**
     * Logs the ASCII banner and version info via the provided Log4j2 logger.
     *
     * @param logger the logger to write to
     */
    public static void print(final Logger logger) {
        final ServerBuildInfo build = ServerBuildInfo.buildInfo();
        final String version     = build.minecraftVersionId();
        final String buildNumber = build.buildNumber().isPresent()
                ? String.valueOf(build.buildNumber().getAsInt()) : "DEV";
        final String commit      = build.gitCommit().orElse("unknown");

        // blank line before banner
        logger.info("");

        for (int i = 0; i < ART.length; i++) {
            final String colour = GRADIENT[i % GRADIENT.length];
            // Log4j will strip ANSI codes if the appender is not a terminal,
            // but StratumLogLayout passes them through intact.
            logger.info(colour + BOLD + ART[i] + RESET);
        }

        logger.info("");
        logger.info(GOLD + BOLD + "  Stratum MC  " + RESET
                + "Minecraft " + version
                + "  |  Build #" + buildNumber
                + "  |  git@" + commit);
        logger.info(GOLD + "  https://stratum.mc" + RESET);
        logger.info("");
    }
}
