package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class BreakController implements RuntimeController {
    private final String name;
    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final int firstDelayMinMinutes;
    private final int firstDelayMaxMinutes;
    private final int intervalMinMinutes;
    private final int intervalMaxMinutes;
    private final int durationMinSeconds;
    private final int durationMaxSeconds;
    private long nextBreakAt;
    private boolean firstSchedule = true;

    private BreakController(
            String name,
            Consumer<String> logger,
            int firstDelayMinMinutes,
            int firstDelayMaxMinutes,
            int intervalMinMinutes,
            int intervalMaxMinutes,
            int durationMinSeconds,
            int durationMaxSeconds,
            ScriptStats stats
    ) {
        this.name = name;
        this.logger = logger;
        this.stats = stats;
        this.firstDelayMinMinutes = firstDelayMinMinutes;
        this.firstDelayMaxMinutes = firstDelayMaxMinutes;
        this.intervalMinMinutes = intervalMinMinutes;
        this.intervalMaxMinutes = intervalMaxMinutes;
        this.durationMinSeconds = durationMinSeconds;
        this.durationMaxSeconds = durationMaxSeconds;
        scheduleNextBreak();
    }

    public static BreakController micro(Consumer<String> logger) {
        return micro(logger, null);
    }

    public static BreakController micro(Consumer<String> logger, ScriptStats stats) {
        return new BreakController("runtime.micro_break", logger, 3, 6, 10, 25, 8, 35, stats);
    }

    public static BreakController normal(Consumer<String> logger) {
        return normal(logger, null);
    }

    public static BreakController normal(Consumer<String> logger, ScriptStats stats) {
        return new BreakController("runtime.normal_break", logger, 45, 90, 120, 240, 120, 360, stats);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return System.currentTimeMillis() >= nextBreakAt
                && !ctx.bank().isOpen()
                && !ctx.localPlayer().isAttacking()
                && !ctx.localPlayer().isInCombat()
                && !ctx.localPlayer().isAnimating()
                && !ctx.localPlayer().isMoving();
    }

    @Override
    public void execute(APIContext ctx) {
        long durationMs = randomLong(durationMinSeconds, durationMaxSeconds) * 1000L;
        long endAt = System.currentTimeMillis() + durationMs;
        logger.accept("[Break] Starting " + name + " for about " + (durationMs / 1000L) + " seconds");

        while (System.currentTimeMillis() < endAt) {
            if (ctx.localPlayer().isAttacking()
                    || ctx.localPlayer().isInCombat()
                    || ctx.localPlayer().isAnimating()
                    || ctx.localPlayer().isMoving()) {
                logger.accept("[Break] Player became active; ending break early");
                break;
            }

            int action = randomInt(0, 3);
            if (action == 0) {
                ctx.mouse().moveOffScreen();
            } else if (action == 1) {
                ctx.camera().setYawDeg(randomInt(0, 360));
            } else if (action == 2) {
                ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            } else {
                ctx.mouse().moveRandomly(randomInt(120, 350), randomInt(250, 700));
            }

            Time.sleep(1200, 3200);
        }

        logger.accept("[Break] Finished " + name);
        if (stats != null) {
            stats.recordRuntimeBreak();
        }
        scheduleNextBreak();
    }

    private void scheduleNextBreak() {
        int minMinutes = firstSchedule ? firstDelayMinMinutes : intervalMinMinutes;
        int maxMinutes = firstSchedule ? firstDelayMaxMinutes : intervalMaxMinutes;
        firstSchedule = false;
        long delayMinutes = randomLong(minMinutes, maxMinutes);
        nextBreakAt = System.currentTimeMillis() + delayMinutes * 60_000L;
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private long randomLong(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }
}
