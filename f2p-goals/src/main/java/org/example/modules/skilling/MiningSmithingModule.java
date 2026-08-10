package org.example.modules.skilling;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.navigation.Navigation;
import org.example.core.navigation.ViewRecovery;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class MiningSmithingModule extends AbstractSkillingModule {
    private static final Area LUMBRIDGE_SWAMP_MINE_SEARCH = new Area(3220, 3144, 3234, 3159);
    private static final Area LUMBRIDGE_SWAMP_MINE_WORK_AREA = new Area(3220, 3144, 3234, 3159);
    private static final Tile LUMBRIDGE_SWAMP_MINE_CENTER = new Tile(3226, 3146, 0);
    private static final Area AL_KHARID_REGION = new Area(3250, 3130, 3310, 3205);
    private static final int FURNACE_ID = 24009;
    private static final FurnaceTarget[] FURNACE_TARGETS = {
            new FurnaceTarget(
                    "Lumbridge furnace",
                    new Area(3223, 3250, 3233, 3261),
                    new Area(3218, 3246, 3237, 3264),
                    new Tile(3227, 3257, 0),
                    new Tile(3227, 3257, 0)
            ),
            new FurnaceTarget(
                    "Al Kharid furnace",
                    new Area(3269, 3182, 3277, 3190),
                    new Area(3267, 3178, 3282, 3194),
                    new Tile(3273, 3186, 0),
                    new Tile(3276, 3186, 0)
            )
    };
    private static final int MIN_ORE_PAIRS_BEFORE_SMELTING = 100;
    private static final int BRONZE_BATCH_ORE_AMOUNT = 14;
    private static final int ORE_BALANCE_TOLERANCE = 4;
    private static final int[] LOADED_COPPER_TIN_ROCK_IDS = {
            10943,
            11161,
            11360,
            11361
    };
    private static final int[] DEPLETED_COPPER_TIN_ROCK_IDS = {
            11390
    };
    private static final long FAILED_ROCK_AVOID_MILLIS = 12_000L;
    private static final long ROCK_DEBUG_LOG_INTERVAL_MILLIS = 15_000L;
    private static final int MINING_ROCK_INTERACT_DISTANCE = 4;
    private static final String COPPER_ORE = "Copper ore";
    private static final String TIN_ORE = "Tin ore";
    private static final String BRONZE_BAR = "Bronze bar";
    private static final String[] MINING_BYPRODUCTS = {
            "Clue geode",
            "Clue geode (beginner)",
            "Clue geode (easy)",
            "Uncut sapphire",
            "Uncut emerald",
            "Uncut ruby",
            "Uncut diamond",
            "Sapphire",
            "Emerald",
            "Ruby",
            "Diamond",
            "Uncut opal",
            "Uncut jade",
            "Uncut red topaz",
            "Opal",
            "Jade",
            "Red topaz"
    };
    private static final int INVENTORY_WIDGET_GROUP = 149;
    private static final int SKILLMULTI_GROUP = InterfaceID.SKILLMULTI;
    private static final int SKILLMULTI_FIRST_ITEM_CHILD = childId(InterfaceID.Skillmulti.A);
    private static final int LEVELUP_GROUP = InterfaceID.LEVELUP_DISPLAY;
    private static final int LEVELUP_CONTINUE_CHILD = childId(InterfaceID.LevelupDisplay.CONTINUE);
    private static final String[] PICKAXES = {
            "Mithril pickaxe",
            "Steel pickaxe",
            "Iron pickaxe",
            "Bronze pickaxe"
    };

    private boolean smeltingBatchActive;
    private boolean oreSnapshotKnown;
    private int bankCopperSnapshot;
    private int bankTinSnapshot;
    private final List<RockCooldown> failedRocks = new ArrayList<>();
    private long lastRockDebugAt;
    private FurnaceTarget smeltingFurnaceTarget;
    private final boolean smeltingOnlyMode;
    private final boolean fundingMode;
    private boolean restartSmeltingAfterContinue;
    private long nextMiningViewRecoverAt;
    private long nextFurnaceViewRecoverAt;

    public MiningSmithingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        this(logger, stats, caps, false);
    }

    public MiningSmithingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps, boolean smeltingOnlyMode) {
        this(logger, stats, caps, smeltingOnlyMode, false);
    }

    public MiningSmithingModule(
            Consumer<String> logger,
            ScriptStats stats,
            SkillCapManager caps,
            boolean smeltingOnlyMode,
            boolean fundingMode
    ) {
        super(logger, stats, caps);
        this.smeltingOnlyMode = smeltingOnlyMode;
        this.fundingMode = fundingMode;
    }

    @Override
    public String name() {
        return "skills.mining_smithing";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return fundingMode || !isComplete(ctx);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        if (fundingMode) {
            return false;
        }
        return complete(ctx, Skill.Skills.MINING) && complete(ctx, Skill.Skills.SMITHING);
    }

    @Override
    public int priority(APIContext ctx) {
        return caps.levelsRemaining(ctx, Skill.Skills.MINING)
                + caps.levelsRemaining(ctx, Skill.Skills.SMITHING);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);

        if (ensureCleanInventory(ctx, "preparing Mining/Smithing inventory", skillingItemsToKeep())) {
            return;
        }

        if (smeltingBatchActive) {
            smeltBronzeBars(ctx);
            return;
        }

        if (hasBronzeBars(ctx)) {
            bankBars(ctx);
            return;
        }

        if (handleLooseMiningOres(ctx)) {
            return;
        }

        if (smeltingOnlyMode) {
            prepareSmeltingBatch(ctx);
            return;
        }

        if (shouldStartSmeltingFromBank(ctx)) {
            prepareSmeltingBatch(ctx);
            return;
        }

        if (!skillComplete(ctx, Skill.Skills.MINING) || !hasEnoughOrePairsKnown()) {
            mineUntilInventoryFull(ctx);
            return;
        }

        if (!skillComplete(ctx, Skill.Skills.SMITHING)) {
            prepareSmeltingBatch(ctx);
            return;
        }

        log("Mining/Smithing caps reached");
        Time.sleep(1200, 1800);
    }

    private boolean handleLooseMiningOres(APIContext ctx) {
        if (oreCount(ctx) <= 0) {
            return false;
        }

        if (smeltingOnlyMode && hasSmeltingOres(ctx)) {
            startSmeltingBatch("ores already in inventory");
            smeltBronzeBars(ctx);
            return true;
        }

        if (ctx.inventory().isFull()) {
            bankOres(ctx);
            return true;
        }

        if (ctx.bank().isOpen() || !LUMBRIDGE_SWAMP_MINE_SEARCH.contains(ctx.localPlayer().getLocation())) {
            log("Banking partial copper/tin ores before continuing Mining");
            bankOres(ctx);
            return true;
        }

        mineUntilInventoryFull(ctx);
        return true;
    }

    private void mineUntilInventoryFull(APIContext ctx) {
        stats.setTrainingSkill("Mining");

        if (!fundingMode && oreCount(ctx) == 0 && ensureToolUpgrade(ctx, "pickaxe", desiredPickaxeUpgrade(ctx))) {
            return;
        }

        if (!ensureAnyTool(ctx, "pickaxe", PICKAXES)) {
            return;
        }

        if (ctx.inventory().isFull()) {
            bankOres(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            updateOreSnapshot(ctx);
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        if (clearBlockingContinue(ctx, "Mining", false)) {
            return;
        }

        if (waitIfBusy(ctx)) {
            return;
        }

        if (walkToSwampMineCenter(ctx)) {
            return;
        }

        OreKind preferredOre = preferredOreKind(ctx);
        SceneObject rock = findMineableRock(ctx, preferredOre);
        if (rock == null || !rock.isValid()) {
            log("No mineable " + preferredOre.label + " rock found; adjusting view");
            recoverMiningView(ctx, "mineable " + preferredOre.label + " rock");
            Time.sleep(1000, 1600);
            return;
        }

        int beforeOres = oreCount(ctx);
        Tile rockTile = rock.getLocation();
        if (moveCloserToRock(ctx, rock, rockTile)) {
            return;
        }

        log("Mining " + rock.getName() + " id=" + rock.getId()
                + " for " + preferredOre.label + " balance until inventory full: "
                + ctx.inventory().getCount() + "/28");
        if (leftClickRock(ctx, rock)) {
            Time.sleep(
                    1800,
                    2800,
                    () -> ctx.localPlayer().isAnimating()
                            || oreCount(ctx) > beforeOres
                            || ctx.inventory().isFull(),
                    100
            );
            Time.sleep(
                    1200,
                    5200,
                    () -> oreCount(ctx) > beforeOres
                            || ctx.inventory().isFull()
                            || (!ctx.localPlayer().isAnimating() && !ctx.localPlayer().isMoving()),
                    100
            );
            if (stats.consumeRecentMiningNoOreMessage()) {
                rememberFailedRock(rockTile);
            }
            return;
        }

        log("Could not click mining rock at " + tileText(rockTile) + "; moving closer before retry");
        if (rockTile != null) {
            org.example.core.navigation.Navigation.walkTo(ctx, rockTile);
        }
        Time.sleep(1200, 1800);
    }

    private SceneObject findMineableRock(APIContext ctx, OreKind preferredOre) {
        forgetExpiredFailedRocks();

        List<SceneObject> actionRocks = filterMineableRocks(ctx.objects()
                .query()
                .filter(rock -> rock != null && isLoadedOreRockId(rock.getId()))
                .within(LUMBRIDGE_SWAMP_MINE_SEARCH)
                .reachable()
                .results()
                .nearestList());
        logRockDebug(ctx, actionRocks);
        SceneObject preferred = randomNearbyRock(actionRocks, preferredOre);
        if (preferred != null) {
            return preferred;
        }

        List<SceneObject> reachableRocks = filterMineableRocks(ctx.objects()
                .query()
                .nameContains("Tin rocks", "Copper rocks", "Tin", "Copper")
                .actions("Mine")
                .within(LUMBRIDGE_SWAMP_MINE_SEARCH)
                .reachable()
                .results()
                .nearestList());
        logRockDebug(ctx, reachableRocks);
        preferred = randomNearbyRock(reachableRocks, preferredOre);
        if (preferred != null) {
            return preferred;
        }

        List<SceneObject> anyAreaRock = filterMineableRocks(ctx.objects()
                .query()
                .filter(rock -> rock != null
                        && !isDepletedOreRockId(rock.getId())
                        && (isLoadedOreRockId(rock.getId())
                        || nameLooksLikeOreRock(rock.getName())))
                .within(LUMBRIDGE_SWAMP_MINE_SEARCH)
                .results()
                .nearestList());
        logRockDebug(ctx, anyAreaRock);
        return randomNearbyRock(anyAreaRock, preferredOre);
    }

    private boolean walkToSwampMineCenter(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (isAtSwampMineWorkArea(location)) {
            return false;
        }

        log("Walking to Lumbridge swamp mine center: " + tileText(LUMBRIDGE_SWAMP_MINE_CENTER)
                + " from " + tileText(location));
        org.example.core.navigation.Navigation.walkTo(ctx, LUMBRIDGE_SWAMP_MINE_CENTER);
        Time.sleep(
                1800,
                3200,
                () -> isAtSwampMineWorkArea(ctx.localPlayer().getLocation())
                        || !ctx.localPlayer().isMoving(),
                100
        );
        return true;
    }

    private boolean isAtSwampMineWorkArea(Tile location) {
        return location != null && LUMBRIDGE_SWAMP_MINE_WORK_AREA.contains(location);
    }

    private void recoverMiningView(APIContext ctx, String targetLabel) {
        long now = System.currentTimeMillis();
        if (now < nextMiningViewRecoverAt || ctx.localPlayer().isMoving()) {
            return;
        }

        nextMiningViewRecoverAt = now + ThreadLocalRandom.current().nextLong(2500L, 5001L);
        ViewRecovery.recover(ctx, LUMBRIDGE_SWAMP_MINE_CENTER, targetLabel, this::log);
    }

    private void recoverFurnaceView(APIContext ctx, FurnaceTarget target) {
        long now = System.currentTimeMillis();
        if (now < nextFurnaceViewRecoverAt || ctx.localPlayer().isMoving()) {
            return;
        }

        nextFurnaceViewRecoverAt = now + ThreadLocalRandom.current().nextLong(2500L, 5001L);
        ViewRecovery.recover(ctx, target.tile, target.label + " furnace", this::log);
    }

    private boolean moveCloserToRock(APIContext ctx, SceneObject rock, Tile rockTile) {
        if (rock == null || rockTile == null || rock.tileDistanceTo(ctx) <= MINING_ROCK_INTERACT_DISTANCE) {
            return false;
        }

        log("Moving closer to mining rock at " + tileText(rockTile) + " before clicking");
        org.example.core.navigation.Navigation.walkTo(ctx, rockTile);
        Time.sleep(
                1200,
                1800,
                () -> rock.tileDistanceTo(ctx) <= MINING_ROCK_INTERACT_DISTANCE
                        || ctx.localPlayer().isMoving(),
                100
        );
        return true;
    }

    private String tileText(Tile tile) {
        if (tile == null) {
            return "?";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private List<SceneObject> filterMineableRocks(List<SceneObject> rocks) {
        List<SceneObject> candidates = new ArrayList<>();
        for (SceneObject rock : rocks) {
            if (rock == null || !rock.isValid() || isRecentlyFailedRock(rock.getLocation())) {
                continue;
            }
            if (isDepletedOreRockId(rock.getId())) {
                continue;
            }
            candidates.add(rock);
        }
        return candidates;
    }

    private SceneObject randomNearbyRock(List<SceneObject> rocks, OreKind preferredOre) {
        List<SceneObject> preferred = preferredOre == OreKind.ANY
                ? rocks
                : rocks.stream().filter(rock -> rockKind(rock) == preferredOre).toList();
        List<SceneObject> candidates = preferred.isEmpty() ? rocks : preferred;
        if (candidates.isEmpty()) {
            return null;
        }

        int limit = Math.min(4, candidates.size());
        return candidates.get(ThreadLocalRandom.current().nextInt(limit));
    }

    private boolean leftClickRock(APIContext ctx, SceneObject rock) {
        return rock.click(false) || ctx.mouse().click(rock, false);
    }

    private boolean isLoadedOreRockId(int id) {
        for (int rockId : LOADED_COPPER_TIN_ROCK_IDS) {
            if (id == rockId) {
                return true;
            }
        }
        return false;
    }

    private OreKind preferredOreKind(APIContext ctx) {
        int copper = (oreSnapshotKnown ? bankCopperSnapshot : 0) + ctx.inventory().getCount(COPPER_ORE);
        int tin = (oreSnapshotKnown ? bankTinSnapshot : 0) + ctx.inventory().getCount(TIN_ORE);

        if (copper > tin + ORE_BALANCE_TOLERANCE) {
            return OreKind.TIN;
        }
        if (tin > copper + ORE_BALANCE_TOLERANCE) {
            return OreKind.COPPER;
        }
        return OreKind.ANY;
    }

    private OreKind rockKind(SceneObject rock) {
        if (rock == null) {
            return OreKind.ANY;
        }

        int id = rock.getId();
        if (id == 10943 || id == 11161) {
            return OreKind.COPPER;
        }
        if (id == 11360 || id == 11361) {
            return OreKind.TIN;
        }

        String normalized = normalizedName(rock.getName());
        if (normalized.contains("copperrock")) {
            return OreKind.COPPER;
        }
        if (normalized.contains("tinrock")) {
            return OreKind.TIN;
        }
        return OreKind.ANY;
    }

    private boolean isDepletedOreRockId(int id) {
        for (int rockId : DEPLETED_COPPER_TIN_ROCK_IDS) {
            if (id == rockId) {
                return true;
            }
        }
        return false;
    }

    private boolean nameLooksLikeOreRock(String name) {
        String normalized = normalizedName(name);
        return normalized.contains("tinrock") || normalized.contains("copperrock");
    }

    private void rememberFailedRock(Tile tile) {
        if (tile == null) {
            return;
        }

        long avoidUntil = System.currentTimeMillis() + FAILED_ROCK_AVOID_MILLIS;
        failedRocks.add(new RockCooldown(tile, avoidUntil));
        log("Avoiding depleted rock tile " + tile.getX() + "," + tile.getY() + " for "
                + (FAILED_ROCK_AVOID_MILLIS / 1000L) + "s");
    }

    private void forgetExpiredFailedRocks() {
        long now = System.currentTimeMillis();
        failedRocks.removeIf(cooldown -> cooldown.avoidUntil <= now);
    }

    private boolean isRecentlyFailedRock(Tile tile) {
        if (tile == null) {
            return false;
        }

        for (RockCooldown cooldown : failedRocks) {
            if (sameTile(tile, cooldown.tile)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameTile(Tile left, Tile right) {
        return left != null
                && right != null
                && left.getX() == right.getX()
                && left.getY() == right.getY()
                && left.getPlane() == right.getPlane();
    }

    private void logRockDebug(APIContext ctx, List<SceneObject> rocks) {
        long now = System.currentTimeMillis();
        if (now - lastRockDebugAt < ROCK_DEBUG_LOG_INTERVAL_MILLIS) {
            return;
        }

        lastRockDebugAt = now;
        List<SceneObject> allRocks = ctx.objects()
                .query()
                .filter(rock -> rock != null
                        && (isLoadedOreRockId(rock.getId())
                        || isDepletedOreRockId(rock.getId())
                        || nameLooksLikeOreRock(rock.getName())))
                .within(LUMBRIDGE_SWAMP_MINE_SEARCH)
                .results()
                .nearestList();

        StringBuilder builder = new StringBuilder("Mining rock debug: candidates=")
                .append(rocks.size())
                .append(" all=");
        int logged = 0;
        for (SceneObject rock : allRocks) {
            if (rock == null || !rock.isValid()) {
                continue;
            }
            if (logged++ >= 10) {
                builder.append(" ...");
                break;
            }
            Tile tile = rock.getLocation();
            builder.append(" [id=")
                    .append(rock.getId())
                    .append(", tile=")
                    .append(tile == null ? "?" : tile.getX() + "," + tile.getY())
                    .append(", actions=")
                    .append(rock.getActions())
                    .append(']');
        }
        log(builder.toString());
    }

    private void bankOres(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank with copper/tin ores");
                org.example.core.navigation.Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank to store copper/tin ores");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        log("Banking copper/tin ores");
        ctx.bank().depositAll(COPPER_ORE);
        ctx.bank().depositAll(TIN_ORE);
        depositMiningByproducts(ctx);
        Time.sleep(600, 900);
        updateOreSnapshot(ctx);

        int pairs = bankOrePairs();
        if (!skillComplete(ctx, Skill.Skills.SMITHING)
                && pairs >= MIN_ORE_PAIRS_BEFORE_SMELTING
                && (skillComplete(ctx, Skill.Skills.MINING) || ThreadLocalRandom.current().nextInt(100) < 45)) {
            startSmeltingBatch("ore pairs banked");
            log("Bronze smelting batch ready: " + pairs + " ore pairs banked");
            return;
        }

        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private boolean shouldStartSmeltingFromBank(APIContext ctx) {
        if (skillComplete(ctx, Skill.Skills.SMITHING)) {
            return false;
        }

        if (smeltingBatchActive) {
            return true;
        }

        if (oreSnapshotKnown && bankOrePairs() >= MIN_ORE_PAIRS_BEFORE_SMELTING
                && skillComplete(ctx, Skill.Skills.MINING)) {
            startSmeltingBatch("mining cap reached");
            return true;
        }

        return false;
    }

    private void prepareSmeltingBatch(APIContext ctx) {
        stats.setTrainingSkill("Smithing");

        if (hasSmeltingOres(ctx)) {
            if (!smeltingOnlyMode && !smeltingBatchActive) {
                log("Loose ores found before smelting threshold; banking them instead of walking to furnace");
                bankOres(ctx);
                return;
            }
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(500, 800);
            }
            startSmeltingBatch("ores already in inventory");
            smeltBronzeBars(ctx);
            return;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for bronze smelting materials");
                org.example.core.navigation.Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank for bronze smelting materials");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (!ctx.inventory().isEmpty()) {
            log("Depositing inventory before bronze smelting");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return;
        }

        updateOreSnapshot(ctx);
        int pairs = bankOrePairs();
        if (pairs <= 0) {
            smeltingBatchActive = false;
            log("No copper/tin pairs available for bronze bars");
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!smeltingOnlyMode && !smeltingBatchActive && pairs < MIN_ORE_PAIRS_BEFORE_SMELTING) {
            smeltingBatchActive = false;
            log("Need " + MIN_ORE_PAIRS_BEFORE_SMELTING + " copper/tin pairs before furnace; bank has " + pairs);
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            log("Selecting item withdraw mode for ores");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
            return;
        }

        FurnaceTarget target = ensureSmeltingFurnaceTarget();
        int maxPairsByInventory = Math.max(1, ctx.inventory().getEmptySlotCount() / 2);
        int amount = Math.min(BRONZE_BATCH_ORE_AMOUNT, Math.min(pairs, maxPairsByInventory));
        log("Withdrawing " + amount + " copper/tin pairs for bronze bars");
        ctx.bank().withdraw(amount, COPPER_ORE);
        Time.sleep(400, 700);
        ctx.bank().withdraw(amount, TIN_ORE);
        Time.sleep(600, 900);
        updateOreSnapshot(ctx);
        startSmeltingBatch("ores withdrawn");
        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private boolean isInAlKharid(APIContext ctx) {
        return AL_KHARID_REGION.contains(ctx.localPlayer().getLocation());
    }

    private void smeltBronzeBars(APIContext ctx) {
        stats.setTrainingSkill("Smithing");

        if (clearBlockingContinue(ctx, "Smithing tick")) {
            return;
        }

        if (restartSmeltingAfterContinue) {
            restartSmeltingAfterContinue(ctx);
            return;
        }

        if (skillComplete(ctx, Skill.Skills.SMITHING)) {
            smeltingBatchActive = false;
            if (!ctx.inventory().isEmpty()) {
                bankBars(ctx);
            }
            return;
        }

        if (!hasSmeltingOres(ctx)) {
            if (hasBronzeBars(ctx)) {
                bankBars(ctx);
                return;
            }
            prepareSmeltingBatch(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        if (waitIfBusy(ctx)) {
            return;
        }

        int beforeBars = ctx.inventory().getCount(BRONZE_BAR);
        int beforeOres = oreCount(ctx);
        if (beforeBars > 0 && beforeOres > 0) {
            log("Continuing bronze smelting batch: " + beforeBars + " bars, " + beforeOres + " ores left");
        }

        if (bronzeInterfaceOpen(ctx)) {
            if (interactBronzeBarAll(ctx)) {
                Time.sleep(
                        1800,
                        4200,
                        () -> ctx.inventory().getCount(BRONZE_BAR) > beforeBars || oreCount(ctx) < beforeOres,
                        100
                );
            } else {
                log("Bronze smelting interface open, but product click failed");
                Time.sleep(800, 1300);
            }
            return;
        }

        FurnaceTarget target = ensureSmeltingFurnaceTarget();
        if (walkToFurnaceArea(ctx, target)) {
            return;
        }

        SceneObject furnace = findFurnace(ctx, target);
        if ((furnace == null || !furnace.isValid()) && stepToFurnaceStand(ctx, target)) {
            return;
        }

        if (furnace == null || !furnace.isValid()) {
            furnace = findFurnace(ctx, target);
        }

        if (furnace == null || !furnace.isValid()) {
            log("No furnace found for bronze bars; expected id " + FURNACE_ID
                    + " at " + target.tile.getX() + "," + target.tile.getY()
                    + " (" + target.label + ")");
            recoverFurnaceView(ctx, target);
            if (target.tile.tileDistanceTo(ctx) > 3) {
                org.example.core.navigation.Navigation.walkTo(ctx, target.tile);
            }
            Time.sleep(1000, 1600);
            return;
        }

        log("Smelting bronze bars at " + target.label);
        if (furnace.interact("Smelt") || furnace.interact("Use")) {
            Time.sleep(
                    1200,
                    2400,
                    () -> bronzeInterfaceOpen(ctx)
                            || ctx.dialogues().isDialogueOpen()
                            || ctx.inventory().getCount(BRONZE_BAR) > beforeBars
                            || oreCount(ctx) < beforeOres,
                    100
            );
            if (!interactBronzeBarAll(ctx)) {
                log("Could not click Bronze bar product for smelting");
                Time.sleep(800, 1300);
                return;
            }
            Time.sleep(
                    1800,
                    4200,
                    () -> ctx.inventory().getCount(BRONZE_BAR) > beforeBars || oreCount(ctx) < beforeOres,
                    100
            );
            return;
        }

        log("Could not open furnace for bronze bars");
        Time.sleep(1000, 1600);
    }

    private void restartSmeltingAfterContinue(APIContext ctx) {
        log("Restarting Smithing after continue/level-up");
        restartSmeltingAfterContinue = false;

        if (!hasSmeltingOres(ctx)) {
            if (hasBronzeBars(ctx)) {
                bankBars(ctx);
                return;
            }
            prepareSmeltingBatch(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        FurnaceTarget target = ensureSmeltingFurnaceTarget();
        SceneObject furnace = findFurnace(ctx, target);
        if (furnace == null || !furnace.isValid()) {
            if (walkToFurnaceArea(ctx, target) || stepToFurnaceStand(ctx, target)) {
                restartSmeltingAfterContinue = true;
                return;
            }

            furnace = findFurnace(ctx, target);
        }

        if (furnace == null || !furnace.isValid()) {
            log("No furnace found while restarting Smithing after continue; adjusting view");
            recoverFurnaceView(ctx, target);
            restartSmeltingAfterContinue = true;
            stepToFurnaceStand(ctx, target);
            Time.sleep(800, 1200);
            return;
        }

        int beforeBars = ctx.inventory().getCount(BRONZE_BAR);
        int beforeOres = oreCount(ctx);
        log("Reopening Smithing interface from furnace after continue: " + beforeOres + " ores left");
        if (furnace.interact("Smelt") || furnace.interact("Use")) {
            Time.sleep(
                    1000,
                    1800,
                    () -> bronzeInterfaceOpen(ctx)
                            || ctx.inventory().getCount(BRONZE_BAR) > beforeBars
                            || oreCount(ctx) < beforeOres,
                    100
            );
            if (interactBronzeBarAll(ctx)) {
                return;
            }
        }

        log("Could not reopen Smithing from furnace after continue; retrying next tick");
        restartSmeltingAfterContinue = true;
        Time.sleep(800, 1200);
    }

    private boolean clearBlockingContinue(APIContext ctx, String reason) {
        return clearBlockingContinue(ctx, reason, true);
    }

    private boolean clearBlockingContinue(APIContext ctx, String reason, boolean resumeSmeltingAfterClear) {
        if (!hasBlockingContinue(ctx)) {
            return false;
        }

        log("Closing continue/level-up with SPACE before " + reason);
        ctx.keyboard().typeKey(32);
        Time.sleep(550, 900, () -> !hasBlockingContinue(ctx), 100);

        if (hasBlockingContinue(ctx)) {
            ctx.keyboard().sendKey(32);
            Time.sleep(550, 900, () -> !hasBlockingContinue(ctx), 100);
        }

        if (resumeSmeltingAfterClear && hasSmeltingOres(ctx)) {
            smeltingBatchActive = true;
            restartSmeltingAfterContinue = true;
        }

        return true;
    }

    private boolean hasBlockingContinue(APIContext ctx) {
        return ctx.dialogues().canContinue()
                || isVisibleWidget(ctx.widgets().get(LEVELUP_GROUP, LEVELUP_CONTINUE_CHILD))
                || (ctx.dialogues().isChatOpen() && findContinueTextWidget(ctx) != null);
    }

    private WidgetChild findContinueTextWidget(APIContext ctx) {
        WidgetChild[] candidates = {
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.MES_TEXT)),
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.MES_TEXT2)),
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.INPUT_CLICKAREA))
        };

        for (WidgetChild widget : candidates) {
            if (!isVisibleWidget(widget)) {
                continue;
            }
            String text = (widget.getText() + " " + widget.getRawText()).toLowerCase();
            if (text.contains("click here to continue")) {
                return widget;
            }
        }

        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> {
            if (!isVisibleWidget(widget) || widget.getGroup() == null) {
                return false;
            }
            if (widget.getGroup().getIndex() != LEVELUP_GROUP) {
                return false;
            }
            String text = (widget.getText() + " " + widget.getRawText()).toLowerCase();
            return text.contains("click here to continue");
        })) {
            return widget;
        }

        return null;
    }

    private boolean stepToFurnaceStand(APIContext ctx, FurnaceTarget target) {
        if (target.standTile == null || target.standTile.tileDistanceTo(ctx) <= 1) {
            return false;
        }
        if (target.area.contains(ctx.localPlayer().getLocation()) || target.tile.tileDistanceTo(ctx) <= 3) {
            return false;
        }

        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(200, 400);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }

        SceneObject blocker = ctx.walking().getBlockingObjectBetween(
                ctx.localPlayer().getLocation(),
                target.standTile
        );
        if (tryOpenFurnaceRouteObject(ctx, blocker, "blocking object")) {
            return true;
        }

        int distance = target.standTile.tileDistanceTo(ctx);
        if (distance <= 25) {
            if (walkLocallyTo(ctx, target.standTile, target.label + " stand tile")) {
                return true;
            }
        }

        if (target.tile.tileDistanceTo(ctx) <= 25) {
            if (walkLocallyTo(ctx, target.tile, target.label + " object tile")) {
                return true;
            }
        }

        log("Local furnace approach failed; walking to " + target.label + " stand tile");
        org.example.core.navigation.Navigation.walkTo(ctx, target.standTile);
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean walkLocallyTo(APIContext ctx, Tile destination, String label) {
        log("Walking locally to " + label);
        int beforeDistance = destination.tileDistanceTo(ctx);
        boolean walked = ctx.walking().walkOnScreen(destination)
                || ctx.walking().walkToOnScreen(destination)
                || ctx.walking().walkOnMap(destination)
                || ctx.walking().walkTo(destination);
        if (walked) {
            Time.sleep(700, 1200, () -> ctx.localPlayer().isMoving(), 100);
        }
        int afterDistance = destination.tileDistanceTo(ctx);
        boolean progressed = ctx.localPlayer().isMoving() || afterDistance < beforeDistance;
        if (walked && !progressed) {
            log("Local furnace walk did not progress to " + label + " (" + beforeDistance + " -> " + afterDistance + ")");
        }
        return walked && progressed;
    }

    private boolean tryOpenFurnaceRouteObject(APIContext ctx, SceneObject object, String label) {
        if (object == null || !object.isValid()) {
            return false;
        }

        log("Opening " + label + " before furnace approach: " + object.getName());
        boolean opened = object.interact("Open")
                || object.interact("Pass")
                || object.interact("Walk-through")
                || object.interact("Use");
        if (opened) {
            Time.sleep(900, 1400, () -> ctx.localPlayer().isMoving(), 100);
        }
        return opened;
    }

    private SceneObject findFurnace(APIContext ctx, FurnaceTarget target) {
        SceneObject byId = ctx.objects()
                .query()
                .id(FURNACE_ID)
                .within(target.searchArea)
                .results()
                .nearest();
        if (byId != null && byId.isValid()) {
            return byId;
        }

        SceneObject bySmeltAction = ctx.objects()
                .query()
                .actions("Smelt")
                .within(target.searchArea)
                .results()
                .nearest();
        if (bySmeltAction != null && bySmeltAction.isValid()) {
            return bySmeltAction;
        }

        SceneObject byName = ctx.objects()
                .query()
                .nameContains("Furnace", "furnace")
                .within(target.searchArea)
                .results()
                .nearest();
        if (byName != null && byName.isValid()) {
            return byName;
        }

        return ctx.objects()
                .query()
                .id(FURNACE_ID)
                .tileDistance(20)
                .results()
                .nearest();
    }

    private FurnaceTarget ensureSmeltingFurnaceTarget() {
        if (smeltingFurnaceTarget == null) {
            smeltingFurnaceTarget = randomFurnaceTarget();
            log("Selected " + smeltingFurnaceTarget.label + " for bronze smelting batch");
        }
        return smeltingFurnaceTarget;
    }

    private void startSmeltingBatch(String reason) {
        if (smeltingFurnaceTarget == null) {
            smeltingFurnaceTarget = randomFurnaceTarget();
        }
        if (!smeltingBatchActive) {
            log("Selected " + smeltingFurnaceTarget.label + " for bronze smelting batch (" + reason + ")");
        }
        smeltingBatchActive = true;
    }

    private FurnaceTarget randomFurnaceTarget() {
        if (smeltingOnlyMode) {
            return FURNACE_TARGETS[1];
        }
        return FURNACE_TARGETS[ThreadLocalRandom.current().nextInt(FURNACE_TARGETS.length)];
    }

    private boolean walkToFurnaceArea(APIContext ctx, FurnaceTarget target) {
        if (target.area.contains(ctx.localPlayer().getLocation())) {
            return false;
        }

        log("Walking to " + target.label + " without teleports");
        org.example.core.navigation.Navigation.walkToNoTeleports(ctx, target.area.getRandomTile());
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean bronzeInterfaceOpen(APIContext ctx) {
        return findBronzeBarWidget(ctx) != null;
    }

    private boolean interactBronzeBarAll(APIContext ctx) {
        if (ctx.dialogues().canContinue()) {
            ctx.dialogues().selectContinue();
            Time.sleep(400, 700);
        }

        if (ctx.dialogues().hasOptionContaining("Bronze")) {
            ctx.dialogues().selectOption(option -> option != null && option.toLowerCase().contains("bronze"));
            Time.sleep(800, 1200);
            return true;
        }

        WidgetChild bronze = findBronzeBarWidget(ctx);
        if (bronze == null) {
            log("Bronze bar product widget not found");
            return false;
        }

        log("Clicking Bronze bar product widget: " + widgetSummary(bronze));
        if (clickWidgetCenter(ctx, bronze)) {
            return true;
        }

        String[] actions = {"Smelt All", "Smelt-all", "Make All", "Make-all", "All", "Smelt", "Make"};
        for (String action : actions) {
            if (bronze.interact(action, BRONZE_BAR)
                    || bronze.interact(action)
                    || ctx.menu().interact(action, BRONZE_BAR, bronze, true)
                    || ctx.menu().interact(action, bronze, true)) {
                return true;
            }
        }
        return false;
    }

    private WidgetChild findBronzeBarWidget(APIContext ctx) {
        WidgetChild firstSkillmultiItem = ctx.widgets().get(SKILLMULTI_GROUP, SKILLMULTI_FIRST_ITEM_CHILD);
        if (isBronzeBarProductWidget(firstSkillmultiItem)) {
            return firstSkillmultiItem;
        }

        WidgetChild itemWidget = firstBronzeBarProductWidget(ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .itemName(BRONZE_BAR)
                .results()
        );
        if (itemWidget != null && itemWidget.isValid()) {
            return itemWidget;
        }

        return firstBronzeBarProductWidget(ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .textContains(BRONZE_BAR)
                .results()
        );
    }

    private WidgetChild firstBronzeBarProductWidget(Iterable<WidgetChild> widgets) {
        for (WidgetChild widget : widgets) {
            if (isBronzeBarProductWidget(widget)) {
                return widget;
            }
        }
        return null;
    }

    private boolean isBronzeBarProductWidget(WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        if (widget.getParentId() == INVENTORY_WIDGET_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == INVENTORY_WIDGET_GROUP)) {
            return false;
        }
        if (isSkillmultiWidget(widget)) {
            String name = widget.getName() == null ? "" : widget.getName().toLowerCase();
            String text = (widget.getText() + " " + widget.getRawText()).toLowerCase();
            return widget.getItemId() > 0
                    || name.contains("bronze")
                    || text.contains("bronze");
        }
        return isChatboxSmithingProductWidget(widget);
    }

    private boolean isSkillmultiWidget(WidgetChild widget) {
        return widget != null
                && (widget.getParentId() == SKILLMULTI_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == SKILLMULTI_GROUP));
    }

    private boolean isChatboxSmithingProductWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getGroup() != null
                && widget.getGroup().getIndex() == InterfaceID.CHATBOX
                && widget.getItemId() > 0
                && widget.getWidth() >= 25
                && widget.getHeight() >= 20
                && widget.getAbsoluteY() >= 330
                && widget.getAbsoluteY() <= 510
                && widget.getAbsoluteX() >= 120
                && widget.getAbsoluteX() <= 380;
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        int group = widget.getGroup() == null ? -1 : widget.getGroup().getIndex();
        return "group=" + group
                + ", parent=" + widget.getParentId()
                + ", child=" + widget.getChildId()
                + ", item=" + widget.getItemId()
                + ", bounds=" + widget.getBounds();
    }

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }

    private void bankBars(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank with bronze bars");
                org.example.core.navigation.Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank to store bronze bars");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        log("Banking bronze bars/leftover ores");
        ctx.bank().depositAll(BRONZE_BAR);
        ctx.bank().depositAll(COPPER_ORE);
        ctx.bank().depositAll(TIN_ORE);
        depositMiningByproducts(ctx);
        Time.sleep(600, 900);
        updateOreSnapshot(ctx);

        if (fundingMode) {
            log("Funding bronze bars banked; keeping bank open for planner audit");
            return;
        }

        if (smeltingBatchActive && !skillComplete(ctx, Skill.Skills.SMITHING) && bankOrePairs() > 0) {
            return;
        }

        smeltingBatchActive = false;
        smeltingFurnaceTarget = null;
        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private void updateOreSnapshot(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            return;
        }
        bankCopperSnapshot = ctx.bank().getCount(COPPER_ORE);
        bankTinSnapshot = ctx.bank().getCount(TIN_ORE);
        oreSnapshotKnown = true;
    }

    private boolean hasEnoughOrePairsKnown() {
        return oreSnapshotKnown && bankOrePairs() >= MIN_ORE_PAIRS_BEFORE_SMELTING;
    }

    private int bankOrePairs() {
        return Math.min(bankCopperSnapshot, bankTinSnapshot);
    }

    private int oreCount(APIContext ctx) {
        return ctx.inventory().getCount(COPPER_ORE) + ctx.inventory().getCount(TIN_ORE);
    }

    private boolean hasSmeltingOres(APIContext ctx) {
        return ctx.inventory().contains(COPPER_ORE) && ctx.inventory().contains(TIN_ORE);
    }

    private boolean hasBronzeBars(APIContext ctx) {
        return ctx.inventory().contains(BRONZE_BAR);
    }

    private void depositMiningByproducts(APIContext ctx) {
        for (String itemName : MINING_BYPRODUCTS) {
            ctx.bank().depositAll(itemName);
        }
    }

    private boolean skillComplete(APIContext ctx, Skill.Skills skill) {
        return !fundingMode && complete(ctx, skill);
    }

    private String[] skillingItemsToKeep() {
        if (smeltingOnlyMode) {
            return withMiningByproducts(
                    "Coins",
                    COPPER_ORE,
                    TIN_ORE,
                    BRONZE_BAR
            );
        }

        return withMiningByproducts(
                "Mithril pickaxe",
                "Steel pickaxe",
                "Iron pickaxe",
                "Bronze pickaxe",
                "Coins",
                COPPER_ORE,
                TIN_ORE,
                BRONZE_BAR
        );
    }

    private String desiredPickaxeUpgrade(APIContext ctx) {
        int miningLevel = level(ctx, Skill.Skills.MINING);
        if (miningLevel >= 21) {
            return "Mithril pickaxe";
        }
        if (miningLevel >= 6) {
            return "Steel pickaxe";
        }
        return null;
    }

    private String[] withMiningByproducts(String... baseNames) {
        String[] names = new String[baseNames.length + MINING_BYPRODUCTS.length];
        System.arraycopy(baseNames, 0, names, 0, baseNames.length);
        System.arraycopy(MINING_BYPRODUCTS, 0, names, baseNames.length, MINING_BYPRODUCTS.length);
        return names;
    }

    private static class RockCooldown {
        private final Tile tile;
        private final long avoidUntil;

        private RockCooldown(Tile tile, long avoidUntil) {
            this.tile = tile;
            this.avoidUntil = avoidUntil;
        }
    }

    private enum OreKind {
        ANY("copper/tin"),
        COPPER("copper"),
        TIN("tin");

        private final String label;

        OreKind(String label) {
            this.label = label;
        }
    }

    private static class FurnaceTarget {
        private final String label;
        private final Area area;
        private final Area searchArea;
        private final Tile tile;
        private final Tile standTile;

        private FurnaceTarget(String label, Area area, Area searchArea, Tile tile, Tile standTile) {
            this.label = label;
            this.area = area;
            this.searchArea = searchArea;
            this.tile = tile;
            this.standTile = standTile;
        }
    }
}
