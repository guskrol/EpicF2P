package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class LoopWatchdogController implements RuntimeController {
    private static final long WARMUP_MILLIS = 2 * 60_000L;
    private static final long MIN_IDLE_STUCK_MILLIS = 4 * 60_000L;
    private static final long MAX_IDLE_STUCK_MILLIS = 6 * 60_000L;
    private static final long MIN_HARD_STUCK_MILLIS = 10 * 60_000L;
    private static final long MAX_HARD_STUCK_MILLIS = 14 * 60_000L;
    private static final long SAME_STATUS_GRACE_MILLIS = 90_000L;
    private static final long INFO_LOG_INTERVAL_MILLIS = 90_000L;
    private static final int TILE_PROGRESS_DISTANCE = 5;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final String scriptVersion;
    private final long startedAt = System.currentTimeMillis();
    private Snapshot lastProgressSnapshot;
    private String lastStatus;
    private Tile lastProgressTile;
    private long lastProgressAt;
    private long sameStatusSince;
    private long sameTileSince;
    private long nextInfoLogAt;
    private long idleStuckMillis;
    private long hardStuckMillis;
    private String pendingStopReason;

    public LoopWatchdogController(Consumer<String> logger, ScriptStats stats) {
        this(logger, stats, "unknown");
    }

    public LoopWatchdogController(Consumer<String> logger, ScriptStats stats, String scriptVersion) {
        this.logger = logger;
        this.stats = stats;
        this.scriptVersion = scriptVersion == null || scriptVersion.isBlank() ? "unknown" : scriptVersion;
        resetThresholds();
    }

    @Override
    public String name() {
        return "runtime.loop_watchdog";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx.script().isStopping()) {
            return false;
        }

        long now = System.currentTimeMillis();
        Snapshot current = Snapshot.capture(ctx, stats);
        if (lastProgressSnapshot == null) {
            initialize(now, current);
            return false;
        }

        if (current.hasMaterialProgressSince(lastProgressSnapshot)) {
            initialize(now, current);
            return false;
        }

        updateStableState(ctx, now, current);

        if (now - startedAt < WARMUP_MILLIS) {
            return false;
        }

        long noProgressFor = now - lastProgressAt;
        long sameTileFor = now - sameTileSince;
        long sameStatusFor = now - sameStatusSince;
        boolean active = ctx.localPlayer().isMoving()
                || ctx.localPlayer().isAnimating()
                || ctx.localPlayer().isInCombat()
                || ctx.localPlayer().isAttacking();
        boolean interfaceStuck = ctx.bank().isOpen()
                || ctx.menu().isOpen()
                || ctx.dialogues().isDialogueOpen()
                || ctx.inventory().isItemSelected();

        if (!active
                && noProgressFor >= idleStuckMillis
                && sameTileFor >= idleStuckMillis
                && (sameStatusFor >= SAME_STATUS_GRACE_MILLIS || interfaceStuck)) {
            pendingStopReason = "Loop watchdog: idle without progress for "
                    + minutes(noProgressFor) + " min; status='" + stats.status() + "'";
            return true;
        }

        if (noProgressFor >= hardStuckMillis
                && sameTileFor >= hardStuckMillis / 2
                && (!active || interfaceStuck || sameStatusFor >= SAME_STATUS_GRACE_MILLIS)) {
            pendingStopReason = "Loop watchdog: no material progress for "
                    + minutes(noProgressFor) + " min; status='" + stats.status() + "'";
            return true;
        }

        if (noProgressFor >= 3 * 60_000L && now >= nextInfoLogAt) {
            logger.accept("[Watchdog] No material progress for " + minutes(noProgressFor)
                    + " min; status='" + stats.status() + "'");
            nextInfoLogAt = now + INFO_LOG_INTERVAL_MILLIS;
        }

        return false;
    }

    @Override
    public void execute(APIContext ctx) {
        String reason = pendingStopReason == null ? "Loop watchdog triggered" : pendingStopReason;
        stats.setStatus(reason);
        logger.accept("[Watchdog] " + reason);
        saveReport(ctx, reason);
        Time.sleep(600, 900);
        ctx.script().stop(reason);
    }

    private void initialize(long now, Snapshot snapshot) {
        lastProgressSnapshot = snapshot;
        lastStatus = snapshot.status;
        lastProgressTile = snapshot.tile;
        lastProgressAt = now;
        sameStatusSince = now;
        sameTileSince = now;
        nextInfoLogAt = now + INFO_LOG_INTERVAL_MILLIS;
        pendingStopReason = null;
        resetThresholds();
    }

    private void updateStableState(APIContext ctx, long now, Snapshot snapshot) {
        if (!snapshot.status.equals(lastStatus)) {
            lastStatus = snapshot.status;
            sameStatusSince = now;
        }

        Tile currentTile = ctx.localPlayer().getLocation();
        if (lastProgressTile == null
                || currentTile == null
                || lastProgressTile.tileDistanceTo(ctx, currentTile) >= TILE_PROGRESS_DISTANCE) {
            lastProgressTile = currentTile;
            sameTileSince = now;
        }
    }

    private void resetThresholds() {
        idleStuckMillis = randomLong(MIN_IDLE_STUCK_MILLIS, MAX_IDLE_STUCK_MILLIS);
        hardStuckMillis = randomLong(MIN_HARD_STUCK_MILLIS, MAX_HARD_STUCK_MILLIS);
    }

    private long randomLong(long minInclusive, long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    private long minutes(long millis) {
        return Math.max(1L, Math.round(millis / 60_000.0));
    }

    private void saveReport(APIContext ctx, String reason) {
        try {
            Path path = reportPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, buildReport(ctx, reason));
            logger.accept("[Watchdog] Report saved: " + path);
        } catch (RuntimeException | IOException firstFailure) {
            try {
                Path fallback = Path.of(
                        System.getProperty("user.home", "."),
                        "f2p-goals-watchdog-reports",
                        reportFileName()
                );
                Files.createDirectories(fallback.getParent());
                Files.writeString(fallback, buildReport(ctx, reason));
                logger.accept("[Watchdog] Report saved: " + fallback);
            } catch (RuntimeException | IOException secondFailure) {
                logger.accept("[Watchdog] Could not save report: " + secondFailure.getMessage());
            }
        }
    }

    private Path reportPath() {
        return Path.of(System.getProperty("user.dir", "."), "watchdog-reports", reportFileName());
    }

    private String reportFileName() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "watchdog-" + timestamp + ".txt";
    }

    private String buildReport(APIContext ctx, String reason) {
        StringBuilder report = new StringBuilder();
        report.append("F2P Goals watchdog report\n");
        report.append("version=").append(scriptVersion).append('\n');
        report.append("reason=").append(reason).append('\n');
        report.append("runtime=").append(stats.runtimeText()).append('\n');
        report.append("module=").append(stats.currentTask()).append('\n');
        report.append("training=").append(stats.trainingSkill()).append('\n');
        report.append("phase=").append(stats.internalPhase()).append('\n');
        report.append("status=").append(stats.status()).append('\n');
        report.append("nextObjective=").append(stats.nextObjective()).append('\n');
        report.append("taskRemaining=").append(stats.goalRemainingText()).append('\n');
        report.append("lastFunding=").append(stats.lastFundingReason()).append('\n');
        report.append("lastRecoverableError=").append(stats.lastRecoverableError()).append('\n');
        report.append("location=").append(locationText(ctx)).append('\n');
        report.append("inventory=").append(itemSummary(ctx.inventory().getItems())).append('\n');
        report.append("equipment=").append(itemSummary(ctx.equipment().getItems())).append('\n');
        report.append("recentLogs:\n");
        List<String> events = stats.recentEvents(30);
        if (events.isEmpty()) {
            report.append("- none\n");
        } else {
            for (String event : events) {
                report.append("- ").append(event).append('\n');
            }
        }
        return report.toString();
    }

    private String locationText(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null) {
            return "unknown";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private String itemSummary(Iterable<ItemWidget> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemWidget item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            String key = item.getName() + (item.isNoted() ? " (noted)" : "");
            int amount = Math.max(1, item.getStackSize());
            counts.merge(key, amount, Integer::sum);
        }

        if (counts.isEmpty()) {
            return "empty";
        }

        StringBuilder summary = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                summary.append(", ");
            }
            summary.append(entry.getKey()).append(" x").append(entry.getValue());
            first = false;
        }
        return summary.toString();
    }

    private static class Snapshot {
        private final int xp;
        private final int inventoryFingerprint;
        private final int equipmentFingerprint;
        private final long statsProgress;
        private final String status;
        private final Tile tile;

        private Snapshot(
                int xp,
                int inventoryFingerprint,
                int equipmentFingerprint,
                long statsProgress,
                String status,
                Tile tile
        ) {
            this.xp = xp;
            this.inventoryFingerprint = inventoryFingerprint;
            this.equipmentFingerprint = equipmentFingerprint;
            this.statsProgress = statsProgress;
            this.status = status == null ? "" : status;
            this.tile = tile;
        }

        private static Snapshot capture(APIContext ctx, ScriptStats stats) {
            return new Snapshot(
                    totalXp(ctx),
                    itemFingerprint(ctx.inventory().getItems()),
                    itemFingerprint(ctx.equipment().getItems()),
                    stats.progressScore(),
                    stats.status(),
                    ctx.localPlayer().getLocation()
            );
        }

        private boolean hasMaterialProgressSince(Snapshot previous) {
            return xp != previous.xp
                    || inventoryFingerprint != previous.inventoryFingerprint
                    || equipmentFingerprint != previous.equipmentFingerprint
                    || statsProgress != previous.statsProgress;
        }

        private static int totalXp(APIContext ctx) {
            int total = 0;
            for (Skill.Skills skill : Skill.Skills.values()) {
                try {
                    total += ctx.skills().get(skill).getExperience();
                } catch (RuntimeException ignored) {
                    // Some future/client-specific skills may not be readable; ignore them.
                }
            }
            return total;
        }

        private static int itemFingerprint(Iterable<ItemWidget> items) {
            int result = 17;
            for (ItemWidget item : items) {
                if (item == null || item.getName() == null || item.getName().isBlank()) {
                    continue;
                }

                result = 31 * result + item.getIndex();
                result = 31 * result + item.getId();
                result = 31 * result + item.getStackSize();
                result = 31 * result + (item.isNoted() ? 1 : 0);
            }
            return result;
        }
    }
}
