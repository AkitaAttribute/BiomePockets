package com.akitaattribute.biomepockets.world;

import com.akitaattribute.biomepockets.BiomePockets;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Explicit operator/debug inspection and cleanup for temporary BiomePockets levels.
 * This is intentionally command-driven rather than a periodic runtime sweep.
 */
public final class PocketCleanupAdmin {
    private static final String MARKER_FILE = ".biomepockets-owned";

    private PocketCleanupAdmin() { }

    /**
     * Report all currently loaded, unclaimed BiomePockets dimensions. Nothing is
     * removed by this overload.
     */
    public static int report(CommandSourceStack source) {
        List<Candidate> candidates = collect(source.getServer());
        if (candidates.isEmpty()) {
            source.sendSuccess(new TextComponent("No non-player-owned BiomePockets dimensions are loaded."), false);
            return 0;
        }

        source.sendSuccess(new TextComponent(
                "Non-player-owned BiomePockets dimensions (" + candidates.size() + "):"), false);
        for (Candidate candidate : candidates) {
            source.sendSuccess(new TextComponent(" - " + describe(candidate, -1L)), false);
        }
        source.sendSuccess(new TextComponent(
                "Report only. Use /biomepockets cleanup <minimumAgeMinutes> to remove eligible empty pockets."),
                false);
        return candidates.size();
    }

    /**
     * Remove only unclaimed pockets that are empty, old enough, not reserved as a
     * Visit/Exit destination, and still pass the normal marker-backed teardown path.
     */
    public static int cleanup(CommandSourceStack source, int minimumAgeMinutes) {
        MinecraftServer server = source.getServer();
        long minimumAgeMillis = Duration.ofMinutes(minimumAgeMinutes).toMillis();
        List<Candidate> candidates = collect(server);

        if (candidates.isEmpty()) {
            source.sendSuccess(new TextComponent("No non-player-owned BiomePockets dimensions are loaded."), false);
            return 0;
        }

        int removed = 0;
        source.sendSuccess(new TextComponent(
                "Checking " + candidates.size() + " non-player-owned pocket(s); minimum age "
                        + minimumAgeMinutes + " minute(s):"), false);

        for (Candidate candidate : candidates) {
            String reason = ineligibleReason(candidate, minimumAgeMillis);
            if (reason != null) {
                source.sendSuccess(new TextComponent(
                        " - KEEP " + describe(candidate, minimumAgeMillis) + " [reason=" + reason + "]"),
                        false);
                continue;
            }

            PocketDimensionManager.teardownIfEmpty(server, candidate.dimension());
            if (!PocketDimensionManager.isOwned(candidate.dimension())) {
                removed++;
                source.sendSuccess(new TextComponent(
                        " - REMOVED " + candidate.dimension().location()
                                + " [age=" + formatAge(candidate.ageMillis()) + "]"
                                + " [players=0]"),
                        false);
            } else {
                // teardownIfEmpty performs its own final reservation/player checks and
                // strict marker/path validation. If any of those veto teardown, report
                // the refusal rather than trying to bypass the safety layer.
                source.sendSuccess(new TextComponent(
                        " - KEEP " + describe(candidate, minimumAgeMillis)
                                + " [reason=teardown safety check refused removal]"),
                        false);
            }
        }

        source.sendSuccess(new TextComponent(
                "Cleanup complete: removed " + removed + " pocket(s), kept "
                        + (candidates.size() - removed) + "."), false);
        return removed;
    }

    @SuppressWarnings("deprecation")
    private static List<Candidate> collect(MinecraftServer server) {
        Map<ResourceKey<Level>, ServerLevel> worlds = server.forgeGetWorldMap();
        List<Candidate> result = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<ResourceKey<Level>, ServerLevel> entry : worlds.entrySet()) {
            ResourceKey<Level> dimension = entry.getKey();
            if (!PocketDimensionManager.isOwned(dimension)
                    || PocketClaimManager.isClaimed(dimension)) {
                continue;
            }

            ServerLevel level = entry.getValue();
            MarkerAge markerAge = markerAge(server, dimension, now);
            boolean returnReserved = PocketClaimManager.isProtectedReturnDimension(dimension);

            result.add(new Candidate(
                    dimension,
                    level == null ? 0 : level.players().size(),
                    markerAge.ageMillis(),
                    markerAge.valid(),
                    returnReserved,
                    PocketDiagnostics.describe(server, dimension)));
        }

        result.sort(Comparator.comparing(candidate -> candidate.dimension().location().toString()));
        return result;
    }

    private static String ineligibleReason(Candidate candidate, long minimumAgeMillis) {
        if (candidate.players() > 0) {
            return "players present";
        }
        if (candidate.returnReserved()) {
            return "return-reserved";
        }
        if (!candidate.validMarker()) {
            return "missing/invalid ownership marker";
        }
        if (candidate.ageMillis() < 0L) {
            return "age unavailable";
        }
        if (candidate.ageMillis() < minimumAgeMillis) {
            return "too new";
        }
        return null;
    }

    private static String describe(Candidate candidate, long minimumAgeMillis) {
        StringBuilder text = new StringBuilder(candidate.dimension().location().toString());
        text.append(" [age=")
                .append(candidate.ageMillis() < 0L ? "unknown" : formatAge(candidate.ageMillis()))
                .append(']');
        text.append(" [players=").append(candidate.players()).append(']');
        text.append(candidate.diagnostics());
        if (!candidate.validMarker()) {
            text.append(" [marker=INVALID]");
        }
        if (minimumAgeMillis >= 0L
                && candidate.ageMillis() >= 0L
                && candidate.ageMillis() >= minimumAgeMillis
                && candidate.players() == 0
                && !candidate.returnReserved()
                && candidate.validMarker()) {
            text.append(" [eligible=yes]");
        }
        return text.toString();
    }

    /**
     * The ownership marker is written once when the pocket is created and is not
     * rewritten during normal persistence/recovery, so its timestamp is a stable
     * creation-age source for both current and previously-created pockets.
     */
    private static MarkerAge markerAge(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            long nowMillis) {
        Path folder = DimensionType.getStorageFolder(
                        dimension,
                        server.getWorldPath(LevelResource.ROOT))
                .toAbsolutePath()
                .normalize();
        Path marker = folder.resolve(MARKER_FILE).normalize();
        if (!marker.startsWith(folder) || !Files.isRegularFile(marker)) {
            return new MarkerAge(-1L, false);
        }

        try {
            String storedDimension = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!storedDimension.equals(dimension.location().toString())) {
                return new MarkerAge(-1L, false);
            }
            FileTime timestamp = Files.getLastModifiedTime(marker);
            return new MarkerAge(Math.max(0L, nowMillis - timestamp.toMillis()), true);
        } catch (IOException exception) {
            BiomePockets.LOGGER.warn(
                    "Could not inspect ownership marker age for {}",
                    dimension.location(),
                    exception);
            return new MarkerAge(-1L, false);
        }
    }

    private static String formatAge(long ageMillis) {
        long totalSeconds = Math.max(0L, ageMillis / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0L) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private record Candidate(
            ResourceKey<Level> dimension,
            int players,
            long ageMillis,
            boolean validMarker,
            boolean returnReserved,
            String diagnostics) { }

    private record MarkerAge(long ageMillis, boolean valid) { }
}
