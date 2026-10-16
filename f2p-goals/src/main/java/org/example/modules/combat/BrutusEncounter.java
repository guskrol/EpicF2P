package org.example.modules.combat;

import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID1;
import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Direction;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.path.LocalPath;
import org.example.core.ScriptStats;
import org.example.core.navigation.Navigation;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The repeatable boss uses an instance, so normal world-area target routing cannot handle it. */
final class BrutusEncounter {
    private static final Tile GATE_APPROACH = new Tile(3261, 3292, 0);
    private static final Area GATE_AREA = new Area(3260, 3292, 3264, 3296);
    private static final Area GRAVE_AREA = new Area(3248, 3280, 3276, 3310);
    private final Consumer<String> logger;
    private final ScriptStats stats;
    private boolean entered;
    private boolean seenBoss;
    private boolean exitRequested;
    private boolean tripComplete;
    private int kills;
    private int targetKills;
    private long enteredAt;
    private long entryStartedAt;
    private long nextActionAt;
    private long nextStatusAt;
    private String lastWarning = "";
    private long lastDodgeAt;
    private Tile dodgeOrigin;
    private Tile dodgeTarget;
    private Tile chargeAnchor;
    private int[] chargeDirection;
    private long dodgeStartedAt;
    private int dodgeAttempt;
    private long dodgeRecoveryUntil;
    private long bossMissingSince;
    private Tile lastBossTile;
    private long lootUntil;
    private GroundItem pendingLoot;
    private int pendingLootCount;
    private int pendingLootAttempts;
    private String failedLootName;
    private Tile failedLootTile;
    private long failedLootUntil;
    private boolean graveRecoveryPending;
    private boolean graveResumePending;
    private long graveRecoveryStartedAt;
    private long lastGraveLogAt;
    private long nextGraveActionAt;
    private int carriedBeforeGrave;
    private boolean graveOpened;

    BrutusEncounter(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    static boolean inside(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        return tile != null && (tile.getX() > 10_000 || tile.getY() > 10_000);
    }

    boolean isActive() { return entered || exitRequested || graveRecoveryPending || graveResumePending; }
    boolean needsGraveRecovery() { return graveRecoveryPending; }
    boolean needsRecoveryPriority() { return graveRecoveryPending || graveResumePending; }
    boolean exitRequested() { return exitRequested; }
    boolean isTripComplete() { return tripComplete; }
    int kills() { return kills; }

    void startTrip(int targetKills) {
        reset();
        this.targetKills = targetKills;
    }

    void requestExit() { exitRequested = true; }

    void onDeath() {
        if (graveRecoveryPending || graveResumePending) return;
        reset();
        graveRecoveryPending = true;
        graveRecoveryStartedAt = System.currentTimeMillis();
        logger.accept("Brutus: death detected; reclaiming items at grave outside arena");
    }

    void reset() {
        entered = false;
        seenBoss = false;
        exitRequested = false;
        tripComplete = false;
        kills = 0;
        enteredAt = 0;
        entryStartedAt = 0;
        nextActionAt = 0;
        lastWarning = "";
        dodgeOrigin = null;
        dodgeTarget = null;
        chargeAnchor = null;
        chargeDirection = null;
        dodgeStartedAt = 0;
        dodgeAttempt = 0;
        dodgeRecoveryUntil = 0;
        bossMissingSince = 0;
        lastBossTile = null;
        lootUntil = 0;
        pendingLoot = null;
        pendingLootAttempts = 0;
        failedLootName = null;
        failedLootTile = null;
        failedLootUntil = 0;
        graveRecoveryPending = false;
        graveResumePending = false;
        graveRecoveryStartedAt = 0;
        lastGraveLogAt = 0;
        nextGraveActionAt = 0;
        carriedBeforeGrave = 0;
        graveOpened = false;
    }

    void recoverGrave(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (ctx.localPlayer().getLocation() == null) return;
        stats.setStatus("Brutus: recovering items from grave");
        if (GATE_APPROACH.tileDistanceTo(ctx) > 6) {
            Navigation.walkToNoTeleports(ctx, GATE_APPROACH);
            return;
        }
        WidgetChild retrieve = ctx.widgets().get(InterfaceID.GRAVESTONE_RETRIEVAL,
                InterfaceID.GravestoneRetrieval.BUTTON & 0xffff);
        WidgetChild free = ctx.widgets().get(InterfaceID.GRAVESTONE_GENERIC,
                InterfaceID.GravestoneGeneric.FREEBUTTON & 0xffff);
        if ((retrieve != null && retrieve.isVisible()) || (free != null && free.isVisible())) return;
        int carried = carriedItemCount(ctx);
        if (graveOpened && carried > carriedBeforeGrave) {
            graveRecoveryPending = false;
            graveResumePending = true;
            logger.accept("Brutus: grave items recovered; preparing to return to fight");
            return;
        }
        SceneObject grave = ctx.objects().query().nameContains("Grave").within(GRAVE_AREA).results().nearest();
        if (grave != null && grave.isValid()) {
            if (!graveOpened) carriedBeforeGrave = carried;
            graveOpened = true;
            if (now < nextGraveActionAt) return;
            for (String action : new String[]{"Loot", "Retrieve", "Open"}) {
                if (grave.hasAction(action) && (grave.interact(action)
                        || ctx.menu().interact(action, grave, true))) {
                    logger.accept("Brutus: opening grave using " + action + " at " + grave.getLocation());
                    nextGraveActionAt = now + 1_500L;
                    return;
                }
            }
            if (now - lastGraveLogAt > 8_000L) {
                logger.accept("Brutus: grave found at " + grave.getLocation()
                        + " actions=" + grave.getActions());
                lastGraveLogAt = now;
            }
        } else if (now - lastGraveLogAt > 8_000L) {
            logger.accept("Brutus: searching grave outside arena near " + GATE_APPROACH
                    + " elapsed=" + (now - graveRecoveryStartedAt) / 1000 + "s");
            lastGraveLogAt = now;
        }
    }

    private int carriedItemCount(APIContext ctx) {
        int total = 0;
        for (ItemWidget item : ctx.inventory().getItems())
            if (item != null) total += Math.max(1, item.getStackSize());
        for (ItemWidget item : ctx.equipment().getItems())
            if (item != null) total += Math.max(1, item.getStackSize());
        return total;
    }

    void tick(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;
        if (!inside(ctx)) {
            if (entered || exitRequested) {
                logger.accept("Brutus: left instance after " + kills + " kills");
                entered = false;
                exitRequested = false;
                tripComplete = true;
                return;
            }
            enter(ctx);
            return;
        }
        if (!entered) {
            entered = true;
            enteredAt = now;
            graveResumePending = false;
            logger.accept("Brutus: entered repeatable boss instance; target=" + targetKills + " kills");
        }
        if (foodCount(ctx) < 2
                || now - enteredAt > 12 * 60_000L || kills >= targetKills) {
            exitRequested = true;
        }
        NPC boss = ctx.npcs().query().id(NpcID.COWBOSS).results().nearest();
        if (boss == null || !boss.isValid())
            boss = ctx.npcs().query().id(NpcID.COWBOSS_ROUTEFIND).actions("Attack").results().nearest();
        if (boss == null || !boss.isValid()) {
            if (seenBoss && bossMissingSince == 0) bossMissingSince = now;
            if (seenBoss && now - bossMissingSince >= 4_000L) {
                seenBoss = false;
                bossMissingSince = 0;
                kills++;
                stats.recordKill();
                logger.accept("Brutus: confirmed kill " + kills + "/" + targetKills);
                lootUntil = now + 20_000L;
                if (kills >= targetKills) exitRequested = true;
            }
            if (loot(ctx)) return;
            if (exitRequested) { exit(ctx); return; }
            if (now >= nextStatusAt) {
                stats.setStatus("Brutus: waiting for respawn (" + kills + "/" + targetKills + ")");
                nextStatusAt = now + 10_000L;
            }
            nextActionAt = now + 900L;
            return;
        }
        seenBoss = true;
        bossMissingSince = 0;
        if (lootUntil <= now && boss.getLocation() != null) lastBossTile = boss.getLocation();
        if (exitRequested && (lootUntil <= now || ctx.inventory().isFull())) { exit(ctx); return; }
        if (dodgeOrigin != null) {
            Tile current = ctx.localPlayer().getLocation();
            if (current != null && current.equals(dodgeTarget)) {
                logger.accept("Brutus: dodge confirmed at " + current);
                dodgeOrigin = null;
                dodgeTarget = null;
                dodgeAttempt = 0;
                chargeAnchor = null;
                chargeDirection = null;
                dodgeRecoveryUntil = now + (lastWarning.contains("snort") ? 4_000L : 2_000L);
                nextActionAt = now + 500L;
                return;
            } else if (now - dodgeStartedAt >= 1_350L) {
                logger.accept("Brutus: dodge target not reached " + dodgeTarget + "; recalculating from " + current);
                dodge(ctx, boss);
                return;
            } else {
                nextActionAt = now + 150L;
                return;
            }
        }
        String warning = boss.getMessage();
        if (warning == null || warning.isBlank()) lastWarning = "";
        else {
            String lower = warning.toLowerCase(Locale.ROOT);
            if ((lower.contains("snort") || lower.contains("growl"))
                    && (!lower.equals(lastWarning) || now - lastDodgeAt > 4_500L)) {
                lastWarning = lower;
                lastDodgeAt = now;
                dodge(ctx, boss);
                return;
            }
            if (lower.contains("snort") || lower.contains("growl")) {
                nextActionAt = now + 250L;
                return;
            }
        }
        if (now < dodgeRecoveryUntil) {
            nextActionAt = now + 250L;
            return;
        }
        if (lootUntil > now && !ctx.localPlayer().isInCombat() && loot(ctx)) return;
        if (exitRequested) { exit(ctx); return; }
        if ((ctx.skills().get(Skill.Skills.HITPOINTS).getCurrentLevel() <= 15
                || ctx.localPlayer().getHealthPercent() <= 85) && eat(ctx)) return;
        if (!ctx.localPlayer().isInCombat() && boss.interact("Attack")) {
            stats.setStatus("Brutus: melee training (" + kills + "/" + targetKills + ")");
            nextActionAt = now + 900L;
        }
    }

    private void enter(APIContext ctx) {
        long now = System.currentTimeMillis();
        int hp = ctx.skills().get(Skill.Skills.HITPOINTS).getCurrentLevel();
        if (hp <= 15 || foodCount(ctx) == 0) {
            stats.setStatus("Brutus: waiting for food and HP above 15 (HP=" + hp + ")");
            if (hp <= 15) eat(ctx);
            nextActionAt = System.currentTimeMillis() + 600L;
            return;
        }
        if (entryStartedAt == 0) entryStartedAt = now;
        if (now - entryStartedAt > 3 * 60_000L) {
            tripComplete = true;
            logger.accept("Brutus: entry timed out after 3 minutes; returning to normal melee targets");
            return;
        }
        if (ctx.dialogues().canContinue()) {
            ctx.dialogues().selectContinue();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options != null && !options.isEmpty()) {
            for (String wanted : new String[]{"yes and don't ask again", "yes", "fight"}) {
                for (WidgetChild option : options) {
                    String text = option.getText();
                    if (text != null && text.toLowerCase(Locale.ROOT).contains(wanted)
                            && option.click(false)) {
                        logger.accept("Brutus: selected entry dialogue " + text);
                        nextActionAt = System.currentTimeMillis() + 1200L;
                        return;
                    }
                }
            }
            stats.setStatus("Brutus: waiting for gate dialogue choice");
            nextActionAt = System.currentTimeMillis() + 1000L;
            return;
        }
        if (ctx.menu().isOpen()) {
            if (ctx.menu().contains("Release") && ctx.menu().interact("Release", true)) {
                logger.accept("Brutus: selected Release from gate menu");
                nextActionAt = System.currentTimeMillis() + 1300L;
                return;
            }
            ctx.menu().closeMenu();
        }
        if (GATE_APPROACH.tileDistanceTo(ctx) > 6) {
            stats.setStatus("Brutus: travelling to cow field gate");
            Navigation.walkToNoTeleports(ctx, GATE_APPROACH);
            nextActionAt = System.currentTimeMillis() + 1100L;
            return;
        }
        SceneObject gate = ctx.objects().query()
                .id(ObjectID1.FENCEGATE_L_COWBOSS_START, ObjectID1.FENCEGATE_R_COWBOSS_START)
                .results().nearest();
        if (gate == null || !gate.isValid())
            gate = ctx.objects().query().nameContains("Gate").within(GATE_AREA).results().nearest();
        if (gate != null && gate.isValid() && gate.hasAction("Release")) {
            if (gate.interact("Release") || ctx.menu().interact("Release", gate, true)) {
                logger.accept("Brutus: released from gate " + gate.getId());
                nextActionAt = System.currentTimeMillis() + 1400L;
                return;
            }
        }
        stats.setStatus("Brutus: locating Release gate");
        nextActionAt = System.currentTimeMillis() + 1000L;
    }

    private void exit(APIContext ctx) {
        if (ctx.dialogues().hasOptionContaining("Yes")) {
            if (ctx.dialogues().selectOption(text -> text != null
                    && text.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT).startsWith("yes"), true)) {
                logger.accept("Brutus: confirmed Yes to leave arena");
                nextActionAt = System.currentTimeMillis() + 1300L;
                return;
            }
        }
        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options != null && !options.isEmpty()) {
            for (WidgetChild option : options) {
                String label = option.getText();
                if (label != null && label.replaceAll("<[^>]*>", "").trim()
                        .toLowerCase(Locale.ROOT).startsWith("yes") && option.click(false)) {
                    logger.accept("Brutus: clicked exit dialogue " + label);
                    nextActionAt = System.currentTimeMillis() + 1300L;
                    return;
                }
            }
            stats.setStatus("Brutus: waiting for exit confirmation");
            nextActionAt = System.currentTimeMillis() + 400L;
            return;
        }
        if (ctx.dialogues().canContinue()) {
            ctx.dialogues().selectContinue();
            nextActionAt = System.currentTimeMillis() + 500L;
            return;
        }
        if (ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen()) {
            stats.setStatus("Brutus: waiting for exit dialogue");
            nextActionAt = System.currentTimeMillis() + 400L;
            return;
        }
        SceneObject gate = ctx.objects().query()
                .id(ObjectID1.FENCEGATE_R_COWBOSS_EXIT, ObjectID1.FENCEGATE_L_COWBOSS_EXIT)
                .results().nearest();
        if (gate == null || !gate.isValid())
            gate = ctx.objects().query().nameContains("Gate").results().nearest();
        if (gate != null && gate.isValid()) {
            for (String action : new String[]{"Exit", "Leave", "Open"}) {
                if (gate.hasAction(action) && (gate.interact(action)
                        || ctx.menu().interact(action, gate, true))) {
                    logger.accept("Brutus: leaving instance through gate using " + action);
                    nextActionAt = System.currentTimeMillis() + 1300L;
                    return;
                }
            }
            if (gate.getLocation() != null && gate.tileDistanceTo(ctx) > 3) {
                ctx.walking().walkToOnScreen(gate.getLocation());
                nextActionAt = System.currentTimeMillis() + 900L;
                return;
            }
        }
        Tile me = ctx.localPlayer().getLocation();
        if (me != null) ctx.walking().walkToOnScreen(new Tile(me.getX() - 3, me.getY() - 3, me.getPlane()));
        if (System.currentTimeMillis() >= nextStatusAt) {
            logger.accept("Brutus: searching southwest exit gate from " + me);
            nextStatusAt = System.currentTimeMillis() + 10_000L;
        }
        nextActionAt = System.currentTimeMillis() + 900L;
    }

    private boolean loot(APIContext ctx) {
        if (ctx.inventory().isFull() || lastBossTile == null || System.currentTimeMillis() > lootUntil) return false;
        if (pendingLoot != null) {
            String name = pendingLoot.getName();
            if (name != null && ctx.inventory().getCount(true, name) > pendingLootCount) {
                logger.accept("Brutus: collected " + name);
                pendingLoot = null;
                pendingLootAttempts = 0;
            } else if (name == null || !pendingLoot.isValid()) {
                pendingLoot = null;
                pendingLootAttempts = 0;
            } else if (pendingLootAttempts == 1) {
                boolean opened = pendingLoot.click(true);
                if (opened) {
                    logger.accept("Brutus: opened loot menu for " + name);
                    pendingLootAttempts = 2;
                    nextActionAt = System.currentTimeMillis() + 250L;
                    return true;
                }
                pendingLoot = null;
            } else if (pendingLootAttempts == 2) {
                boolean selected = ctx.menu().isOpen()
                        && (ctx.menu().interact("Take", name) || ctx.menu().interact("Take", false));
                logger.accept("Brutus: selected Take for " + name + " = " + selected);
                pendingLootAttempts = 3;
                nextActionAt = System.currentTimeMillis() + 750L;
                return true;
            } else {
                logger.accept("Brutus: loot click did not collect " + name);
                failedLootName = name;
                failedLootTile = pendingLoot.getLocation();
                failedLootUntil = System.currentTimeMillis() + 3_000L;
                pendingLoot = null;
                pendingLootAttempts = 0;
            }
        }
        GroundItem nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (GroundItem item : ctx.groundItems().getAll()) {
            if (item == null || !item.isValid() || item.getLocation() == null || item.getName() == null) continue;
            Tile tile = item.getLocation();
            if (System.currentTimeMillis() < failedLootUntil && tile.equals(failedLootTile)
                    && item.getName().equals(failedLootName)) continue;
            if (tile.getPlane() != lastBossTile.getPlane()
                    || Math.max(Math.abs(tile.getX() - lastBossTile.getX()),
                                Math.abs(tile.getY() - lastBossTile.getY())) > 3) continue;
            int distance = item.tileDistanceTo(ctx);
            if (distance > 12 || distance >= nearestDistance) continue;
            nearest = item;
            nearestDistance = distance;
        }
        if (nearest != null) {
            int before = ctx.inventory().getCount(true, nearest.getName());
            if (!nearest.click(false)) return false;
            pendingLoot = nearest;
            pendingLootCount = before;
            pendingLootAttempts = 1;
            logger.accept("Brutus: left-clicked drop " + nearest.getName() + " at " + nearest.getLocation());
            nextActionAt = System.currentTimeMillis() + 750L;
            return true;
        }
        return false;
    }

    private int foodCount(APIContext ctx) {
        int count = 0;
        for (String name : new String[]{"Lobster", "Swordfish", "Salmon", "Tuna", "Trout", "Cake", "Bread", "Shrimps"})
            count += ctx.inventory().getCount(name);
        return count;
    }

    private boolean eat(APIContext ctx) {
        for (String name : new String[]{"Lobster", "Swordfish", "Salmon", "Tuna", "Trout", "Cake", "Bread", "Shrimps"}) {
            if (ctx.inventory().contains(name) && ctx.inventory().interactItem("Eat", name)) {
                logger.accept("Brutus: eating " + name);
                nextActionAt = System.currentTimeMillis() + 450L;
                return true;
            }
        }
        return false;
    }

    private void dodge(APIContext ctx, NPC boss) {
        Tile me = ctx.localPlayer().getLocation();
        Tile bull = boss.getLocation();
        if (me == null || bull == null) return;
        int dx = me.getX() - bull.getX();
        int dy = me.getY() - bull.getY();
        boolean alongX = Math.abs(dx) >= Math.abs(dy);
        Tile origin = me;
        boolean charge = lastWarning.contains("growl");
        if (charge && (chargeAnchor == null || chargeDirection == null)) {
            chargeAnchor = bull;
            chargeDirection = chargeHeading(boss, me);
        }
        int[] heading = charge ? chargeDirection : null;
        // Growls follows Brutus's facing direction, including diagonal charges.
        // Snorts is a frontal slam and allows a backward fallback.
        Tile[] candidates = charge
                ? new Tile[]{new Tile(origin.getX() - heading[1] * 2,
                        origin.getY() + heading[0] * 2, origin.getPlane()),
                    new Tile(origin.getX() + heading[1] * 2,
                        origin.getY() - heading[0] * 2, origin.getPlane())}
                : alongX
                ? new Tile[]{new Tile(origin.getX(), origin.getY() + 2, origin.getPlane()),
                    new Tile(origin.getX(), origin.getY() - 2, origin.getPlane()),
                    new Tile(origin.getX() + Integer.signum(dx) * 2, origin.getY(), origin.getPlane())}
                : new Tile[]{new Tile(origin.getX() + 2, origin.getY(), origin.getPlane()),
                    new Tile(origin.getX() - 2, origin.getY(), origin.getPlane()),
                    new Tile(origin.getX(), origin.getY() + Integer.signum(dy) * 2, origin.getPlane())};
        for (int i = 0; i < candidates.length; i++) {
            Tile tile = candidates[i];
            if ((tile.equals(dodgeTarget) && dodgeAttempt >= 2) || !tile.isVisible()
                    || !reachableInsideArena(ctx, origin, tile, heading, chargeAnchor)) continue;
            if (tile.click(false)) {
                dodgeOrigin = origin;
                dodgeTarget = tile;
                dodgeStartedAt = System.currentTimeMillis();
                dodgeAttempt++;
                logger.accept("Brutus: dodge requested for " + lastWarning + " from " + origin + " to " + tile
                        + (charge ? " boss=" + chargeAnchor + " charge heading=" + boss.getDirection()
                        + " lane distance=" + Math.abs(chargeSide(heading, chargeAnchor, origin))
                        + "->" + Math.abs(chargeSide(heading, chargeAnchor, tile)) : ""));
                nextActionAt = dodgeStartedAt + 180L;
                return;
            }
        }
        logger.accept("Brutus: no reachable dodge tile from " + me);
        dodgeOrigin = null;
        dodgeTarget = null;
        dodgeAttempt = 0;
        chargeAnchor = null;
        chargeDirection = null;
        if (charge) dodgeRecoveryUntil = System.currentTimeMillis() + 2_000L;
        nextActionAt = System.currentTimeMillis() + 350L;
    }

    private int[] chargeHeading(NPC boss, Tile player) {
        Direction facing = boss.getDirection();
        int[] direction = switch (facing == null ? Direction.NORTH : facing) {
            case NORTH -> new int[]{0, 1};
            case NORTH_EAST -> new int[]{1, 1};
            case EAST -> new int[]{1, 0};
            case SOUTH_EAST -> new int[]{1, -1};
            case SOUTH -> new int[]{0, -1};
            case SOUTH_WEST -> new int[]{-1, -1};
            case WEST -> new int[]{-1, 0};
            case NORTH_WEST -> new int[]{-1, 1};
        };
        Tile bossTile = boss.getLocation();
        if (bossTile != null && (facing == null || direction[0] * (player.getX() - bossTile.getX())
                + direction[1] * (player.getY() - bossTile.getY()) <= 0)) {
            direction[0] = Integer.signum(player.getX() - bossTile.getX());
            direction[1] = Integer.signum(player.getY() - bossTile.getY());
            if (direction[0] == 0 && direction[1] == 0) direction[1] = 1;
        }
        return direction;
    }

    private static int chargeSide(int[] heading, Tile anchor, Tile tile) {
        return heading[0] * (tile.getY() - anchor.getY())
                - heading[1] * (tile.getX() - anchor.getX());
    }

    private static boolean fartherFromChargeLane(int initialSide, int nextSide) {
        return Math.abs(nextSide) > Math.abs(initialSide)
                && (initialSide == 0 || Integer.signum(nextSide) == Integer.signum(initialSide));
    }

    private boolean reachableInsideArena(APIContext ctx, Tile origin, Tile destination,
                                         int[] chargeHeading, Tile chargeAnchor) {
        int initialSide = 0;
        if (chargeHeading != null) {
            initialSide = chargeSide(chargeHeading, chargeAnchor, origin);
            if (!fartherFromChargeLane(initialSide,
                    chargeSide(chargeHeading, chargeAnchor, destination))) return false;
        }
        if (ctx.walking().getBlockingObjectBetween(origin, destination) != null) return false;
        LocalPath path = ctx.walking().findPath(origin, destination, 0);
        if (path == null || !path.validate(ctx)) return false;
        Tile[] steps = path.getTiles();
        if (steps == null || steps.length == 0 || !destination.equals(steps[steps.length - 1])) return false;
        int previousSide = initialSide;
        for (Tile step : steps) {
            if (step == null || Math.max(Math.abs(step.getX() - origin.getX()),
                    Math.abs(step.getY() - origin.getY())) > 2) return false;
            if (chargeHeading != null && !step.equals(origin)) {
                int side = chargeSide(chargeHeading, chargeAnchor, step);
                if (!fartherFromChargeLane(initialSide, side)
                        || Math.abs(side) < Math.abs(previousSide)) return false;
                previousSide = side;
            }
        }
        return true;
    }
}
