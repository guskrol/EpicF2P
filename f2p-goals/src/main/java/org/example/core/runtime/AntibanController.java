package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.util.time.Time;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class AntibanController implements RuntimeController {
    private final Consumer<String> logger;
    private long nextActionAt;

    public AntibanController(Consumer<String> logger) {
        this.logger = logger;
        scheduleNextAction(45, 120);
    }

    @Override
    public String name() {
        return "runtime.antiban";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return System.currentTimeMillis() >= nextActionAt
                && !ctx.bank().isOpen()
                && !ctx.localPlayer().isAttacking()
                && !ctx.localPlayer().isMoving();
    }

    @Override
    public void execute(APIContext ctx) {
        int action = ThreadLocalRandom.current().nextInt(100);

        if (action < 35) {
            logger.accept("[Antiban] Adjusting camera");
            ctx.camera().setYawDeg(randomInt(0, 360));
            ctx.camera().setPitch(randomInt(260, 380));
            Time.sleep(500, 1100);
        } else if (action < 60) {
            logger.accept("[Antiban] Moving mouse randomly");
            ctx.mouse().moveRandomly(randomInt(120, 420), randomInt(220, 760));
            Time.sleep(250, 650);
        } else if (action < 82) {
            logger.accept("[Antiban] Checking a tab");
            ctx.tabs().open(randomTab());
            Time.sleep(900, 2200);
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
        } else {
            logger.accept("[Antiban] Moving mouse off screen");
            ctx.mouse().moveOffScreen();
            Time.sleep(1600, 4200);
        }

        scheduleNextAction(45, 180);
    }

    private ITabsAPI.Tabs randomTab() {
        ITabsAPI.Tabs[] tabs = {
                ITabsAPI.Tabs.SKILLS,
                ITabsAPI.Tabs.EQUIPMENT,
                ITabsAPI.Tabs.COMBAT_OPTIONS,
                ITabsAPI.Tabs.PRAYER
        };
        return tabs[randomInt(0, tabs.length - 1)];
    }

    private void scheduleNextAction(int minSeconds, int maxSeconds) {
        nextActionAt = System.currentTimeMillis() + randomLong(minSeconds, maxSeconds) * 1000L;
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private long randomLong(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }
}
