package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.World;
import com.epicbot.api.shared.model.WorldType;
import com.epicbot.api.shared.util.time.Time;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class WorldHopController implements RuntimeController {
    private static final String[] BUSY_INVENTORY_ITEMS = {
            "Logs",
            "Oak logs",
            "Raw shrimps",
            "Raw anchovies",
            "Shrimps",
            "Anchovies",
            "Burnt fish",
            "Cowhide",
            "Hard leather",
            "Leather"
    };
    private static final Set<WorldType> BLOCKED_WORLD_TYPES = Set.of(
            WorldType.MEMBERS,
            WorldType.PVP,
            WorldType.BOUNTY,
            WorldType.PVP_ARENA,
            WorldType.SKILL_TOTAL,
            WorldType.HIGH_RISK,
            WorldType.LAST_MAN_STANDING,
            WorldType.BETA_WORLD,
            WorldType.NOSAVE_MODE,
            WorldType.TOURNAMENT_WORLD,
            WorldType.FRESH_START_WORLD,
            WorldType.DEADMAN,
            WorldType.SEASONAL
    );

    private final Consumer<String> logger;
    private long nextHopAt;
    private long nextBusyInventoryLogAt;
    private boolean firstSchedule = true;

    public WorldHopController(Consumer<String> logger) {
        this.logger = logger;
        scheduleNextHop();
    }

    @Override
    public String name() {
        return "runtime.world_hop";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (System.currentTimeMillis() < nextHopAt
                || ctx.bank().isOpen()
                || ctx.grandExchange().isOpen()
                || ctx.widgets().isInterfaceOpen()
                || ctx.dialogues().isDialogueOpen()
                || ctx.menu().isOpen()
                || ctx.inventory().isItemSelected()
                || ctx.world().isWorldMenuOpen()
                || ctx.localPlayer().isAttacking()
                || ctx.localPlayer().isMoving()
                || ctx.localPlayer().isAnimating()) {
            return false;
        }

        if (hasBusySkillingInventory(ctx)) {
            if (System.currentTimeMillis() >= nextBusyInventoryLogAt) {
                logger.accept("[WorldHop] Delaying hop; skilling inventory is active");
                nextBusyInventoryLogAt = System.currentTimeMillis() + 60_000L;
            }
            return false;
        }

        return true;
    }

    @Override
    public void execute(APIContext ctx) {
        int currentWorld = ctx.world().getCurrent();
        logger.accept("[WorldHop] Looking for safe F2P world from " + currentWorld);

        boolean hopped = ctx.world().hop(world -> isCandidate(world, currentWorld));
        if (!hopped) {
            logger.accept("[WorldHop] Predicate hop failed; trying generic F2P hop");
            hopped = ctx.world().hopToF2P();
        }

        Time.sleep(2500, 5000);
        if (hopped) {
            logger.accept("[WorldHop] Hop requested. Current world: " + ctx.world().getCurrent());
        } else {
            logger.accept("[WorldHop] Could not hop this time");
        }

        if (ctx.world().isWorldMenuOpen()) {
            logger.accept("[WorldHop] Closing world switcher after hop attempt");
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(600, 900, () -> !ctx.world().isWorldMenuOpen(), 100);
        }

        scheduleNextHop();
    }

    private boolean isCandidate(World world, int currentWorld) {
        if (world == null || world.getId() == currentWorld || world.isMembers()) {
            return false;
        }

        if (world.getPopulation() <= 0 || world.getPopulation() >= 1800) {
            return false;
        }

        if (WorldType.isPvpWorld(world.getTypes())) {
            return false;
        }

        for (WorldType blockedType : BLOCKED_WORLD_TYPES) {
            if (world.getTypes().contains(blockedType)) {
                return false;
            }
        }

        return true;
    }

    private void scheduleNextHop() {
        int minMinutes = firstSchedule ? 12 : 45;
        int maxMinutes = firstSchedule ? 18 : 75;
        firstSchedule = false;
        nextHopAt = System.currentTimeMillis() + randomLong(minMinutes, maxMinutes) * 60_000L;
    }

    private long randomLong(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    private boolean hasBusySkillingInventory(APIContext ctx) {
        for (String itemName : BUSY_INVENTORY_ITEMS) {
            if (ctx.inventory().contains(itemName)) {
                return true;
            }
        }
        return false;
    }
}
