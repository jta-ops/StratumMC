package com.destroystokyo.paper;

import com.destroystokyo.paper.util.VersionFetcher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import io.papermc.paper.ServerBuildInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;
import static io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_SIMPLE;

@DefaultQualifier(NonNull.class)
public class PaperVersionFetcher implements VersionFetcher {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final ComponentLogger COMPONENT_LOGGER = ComponentLogger.logger(LogManager.getRootLogger().getName());
    private static final int DISTANCE_ERROR = -1;
    private static final int DISTANCE_UNKNOWN = -2;
    private static final String DOWNLOAD_PAGE = "https://stratumserver.net/downloads";
    private static final String API_BUILDS_LATEST = "https://stratumserver.net/api/builds/latest";
    private static final ServerBuildInfo BUILD_INFO = ServerBuildInfo.buildInfo();
    private static final String USER_AGENT = BUILD_INFO.brandName() + "/" + BUILD_INFO.asString(VERSION_SIMPLE) + " (https://stratumserver.net)";
    // Build artifacts are published as stratum-<mcVersion>-<build>.jar
    private static final Pattern BUILD_FILENAME = Pattern.compile("stratum-(?<mc>[^-]+)-(?<build>\\d+)\\.jar");
    private static final Gson GSON = new Gson();

    @Override
    public long getCacheTime() {
        return 720000;
    }

    @Override
    public Component getVersionMessage() {
        final Component updateMessage;
        if (BUILD_INFO.buildNumber().isEmpty() && BUILD_INFO.gitCommit().isEmpty()) {
            updateMessage = text("You are running a development version without access to version information", color(0xFF5300));
        } else {
            updateMessage = getUpdateStatusMessage();
        }
        final @Nullable Component history = this.getHistory();

        return history != null ? Component.textOfChildren(updateMessage, Component.newline(), history) : updateMessage;
    }

    public static void getUpdateStatusStartupMessage() {
        final OptionalInt buildNumber = BUILD_INFO.buildNumber();
        if (buildNumber.isEmpty() && BUILD_INFO.gitCommit().isEmpty()) {
            COMPONENT_LOGGER.warn(text("*** You are running a development version without access to version information ***"));
            return;
        }

        final int distance = buildNumber.isPresent() ? fetchDistanceFromStratumApi(buildNumber.getAsInt()) : DISTANCE_UNKNOWN;
        switch (distance) {
            case DISTANCE_ERROR -> COMPONENT_LOGGER.error(text("*** Error obtaining version information! Cannot fetch version info ***"));
            case 0 -> COMPONENT_LOGGER.info(text("You are running the latest Stratum build for Minecraft " + BUILD_INFO.minecraftVersionId()));
            case DISTANCE_UNKNOWN -> COMPONENT_LOGGER.warn(text("*** You are running an unknown version! Cannot fetch version info ***"));
            default -> {
                COMPONENT_LOGGER.info(text("*** Currently you are " + distance + " build(s) behind ***"));
                COMPONENT_LOGGER.info(text("*** It is highly recommended to download the latest build from " + DOWNLOAD_PAGE + " ***"));
            }
        }
    }

    private static Component getUpdateStatusMessage() {
        final OptionalInt buildNumber = PaperVersionFetcher.BUILD_INFO.buildNumber();
        final int distance = buildNumber.isPresent() ? fetchDistanceFromStratumApi(buildNumber.getAsInt()) : DISTANCE_UNKNOWN;

        return switch (distance) {
            case DISTANCE_ERROR -> text("Error obtaining version information", NamedTextColor.YELLOW);
            case 0 -> text("You are running the latest version", NamedTextColor.GREEN);
            case DISTANCE_UNKNOWN -> text("Unknown version", NamedTextColor.YELLOW);
            default -> text("You are " + distance + " build(s) behind", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(text("Download the new version at: ")
                    .append(text(DOWNLOAD_PAGE, NamedTextColor.GOLD)
                        .hoverEvent(text("Click to open", NamedTextColor.WHITE))
                        .clickEvent(ClickEvent.openUrl(DOWNLOAD_PAGE))));
        };
    }

    private static int fetchDistanceFromStratumApi(final int currentBuild) {
        final OptionalInt latest = fetchLatestBuildNumber();
        if (latest.isEmpty()) {
            return DISTANCE_ERROR;
        }
        return Math.max(latest.getAsInt() - currentBuild, 0);
    }

    private static OptionalInt fetchLatestBuildNumber() {
        try {
            final URL buildsUrl = URI.create(API_BUILDS_LATEST).toURL();
            final HttpURLConnection connection = (HttpURLConnection) buildsUrl.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", PaperVersionFetcher.USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                final JsonObject json = GSON.fromJson(reader, JsonObject.class);
                final Matcher matcher = BUILD_FILENAME.matcher(json.get("filename").getAsString());
                if (matcher.matches()) {
                    return OptionalInt.of(Integer.parseInt(matcher.group("build")));
                }
                return OptionalInt.empty();
            } catch (final JsonSyntaxException | NumberFormatException | NullPointerException ex) {
                LOGGER.error("Error parsing json from Stratum's builds API", ex);
                return OptionalInt.empty();
            }
        } catch (final IOException e) {
            LOGGER.error("Error while fetching the latest Stratum build", e);
            return OptionalInt.empty();
        }
    }

    private @Nullable Component getHistory() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        if (data == null) {
            return null;
        }

        final @Nullable String oldVersion = data.getOldVersion();
        if (oldVersion == null) {
            return null;
        }

        return text("Previous version: " + oldVersion, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
