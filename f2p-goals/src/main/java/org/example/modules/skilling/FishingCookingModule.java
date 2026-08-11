package org.example.modules.skilling;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.navigation.Navigation;
import org.example.core.navigation.ViewRecovery;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class FishingCookingModule extends AbstractSkillingModule {
    private static final Area DRAYNOR_NET_FISHING = new Area(3079, 3221, 3097, 3237);
    private static final Area AL_KHARID_NET_FISHING = new Area(3265, 3138, 3284, 3158);
    private static final Area AL_KHARID_REGION = new Area(3250, 3130, 3310, 3205);
    private static final Area AL_KHARID_BANK = new Area(3268, 3161, 3274, 3173);
    private static final Area AL_KHARID_RANGE = new Area(3271, 3180, 3278, 3187);
    private static final Area AL_KHARID_RANGE_SEARCH = new Area(3264, 3175, 3285, 3192);
    private static final Tile AL_KHARID_RANGE_TILE = new Tile(3271, 3181, 0);
    private static final Tile AL_KHARID_COOKING_STAND_TILE = new Tile(3273, 3180, 0);
    private static final int AL_KHARID_RANGE_ID = 26181;
    private static final Area LUMBRIDGE_RANGE = new Area(3205, 3212, 3212, 3217);
    private static final Area LUMBRIDGE_RANGE_SEARCH = new Area(3195, 3205, 3220, 3225);
    private static final Area LUMBRIDGE_REGION = new Area(3170, 3190, 3245, 3275);
    private static final String[] COOKING_RANGE_NAMES = {"Range", "Cooking range", "Stove", "Fire"};
    private static final String[] RAW_FISH = {"Raw anchovies", "Raw shrimps"};
    private static final String[] COOKED_OR_BURNT_FISH = {
            "Anchovies",
            "Shrimps",
            "Burnt fish",
            "Burnt shrimp",
            "Burnt shrimps",
            "Burnt anchovies"
    };
    private static final int FULL_INVENTORY_SIZE = 28;
    private static final int MIN_RAW_FISH_BEFORE_COOKING = 100;
    private static final int INVENTORY_WIDGET_GROUP = 149;
    private static final int SKILLMULTI_GROUP = InterfaceID.SKILLMULTI;
    private static final int SKILLMULTI_ALL_CHILD = childId(InterfaceID.Skillmulti.ALL);
    private static final int SKILLMULTI_FIRST_ITEM_CHILD = childId(InterfaceID.Skillmulti.A);
    private static final int LEVELUP_GROUP = InterfaceID.LEVELUP_DISPLAY;
    private static final int LEVELUP_CONTINUE_CHILD = childId(InterfaceID.LevelupDisplay.CONTINUE);

    private boolean cookingBatchActive;
    private boolean preferCookingAfterFishingBank;
    private boolean needMoreRawFishForCooking;
    private final boolean forceCookingAfterBank;
    private final boolean fundingMode;
    private long cookingBlockedUntil;
    private long lastCookingActionAt;
    private long nextCookingRecoveryLogAt;
    private boolean cookingActionActive;
    private int lastCookingRawCount = -1;
    private long lastCookingRawChangeAt;
    private long cookingActionStartedAt;
    private long nextCookingProgressLogAt;
    private int missingCookingWidgetAttempts;
    private boolean restartCookingAfterContinue;
    private long rawFishEmptySince;
    private int failedFishingInteractions;
    private long nextFishingInteractAt;
    private long nextFishingViewAdjustAt;

    public FishingCookingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        this(logger, stats, caps, false);
    }

    public FishingCookingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps, boolean forceCookingAfterBank) {
        this(logger, stats, caps, forceCookingAfterBank, false);
    }

    public FishingCookingModule(
            Consumer<String> logger,
            ScriptStats stats,
            SkillCapManager caps,
            boolean forceCookingAfterBank,
            boolean fundingMode
    ) {
        super(logger, stats, caps);
        this.forceCookingAfterBank = forceCookingAfterBank;
        this.fundingMode = fundingMode;
    }

    @Override
    public String name() {
        return "skills.fishing_cooking";
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
        return complete(ctx, Skill.Skills.FISHING) && complete(ctx, Skill.Skills.COOKING);
    }

    @Override
    public int priority(APIContext ctx) {
        return caps.levelsRemaining(ctx, Skill.Skills.FISHING)
                + caps.levelsRemaining(ctx, Skill.Skills.COOKING);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);

        int rawFishCount = rawFishCount(ctx);

        if (rawFishCount > 0 && hasCookedOrBurntFish(ctx)) {
            cookingBatchActive = true;
            log("Cooked/burnt fish detected with raw fish; continuing Cooking");
            cookFish(ctx);
            return;
        }

        if (ensureCleanInventory(ctx, "preparing Fishing/Cooking inventory", skillingItemsToKeep())) {
            return;
        }

        rawFishCount = rawFishCount(ctx);
        boolean cookingReady = !skillComplete(ctx, Skill.Skills.COOKING)
                && System.currentTimeMillis() >= cookingBlockedUntil;

        if (cookingBatchActive) {
            if (rawFishCount > 0) {
                cookFish(ctx);
                return;
            }

            finishCookingBatch(ctx);
            return;
        }

        if (hasCookedOrBurntFish(ctx) && rawFishCount > 0) {
            cookingBatchActive = true;
            cookFish(ctx);
            return;
        }

        if (hasCookedOrBurntFish(ctx)) {
            bankCookedFish(ctx);
            return;
        }

        if (needMoreRawFishForCooking && rawFishCount == 0) {
            fishUntilInventoryFull(ctx);
            return;
        }

        if (rawFishCount > 0 && ctx.inventory().isFull()) {
            bankRawFish(ctx);
            return;
        }

        if (rawFishCount > 0) {
            fishUntilInventoryFull(ctx);
            return;
        }

        if (cookingReady
                && (preferCookingAfterFishingBank || skillComplete(ctx, Skill.Skills.FISHING))) {
            prepareCookingBatch(ctx);
            return;
        }

        if (!skillComplete(ctx, Skill.Skills.FISHING)) {
            fishUntilInventoryFull(ctx);
            return;
        }

        if (!skillComplete(ctx, Skill.Skills.COOKING)) {
            prepareCookingBatch(ctx);
            return;
        }

        log("Fishing/Cooking caps reached");
        Time.sleep(1200, 1800);
    }

    private void fishUntilInventoryFull(APIContext ctx) {
        stats.setTrainingSkill("Fishing");

        if (!ensureAnyTool(ctx, "small fishing net", "Small fishing net")) {
            return;
        }

        if (ctx.inventory().isFull()) {
            bankRawFish(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        fish(ctx);
    }

    private void fish(APIContext ctx) {
        stats.setTrainingSkill("Fishing");

        if (waitIfBusy(ctx)) {
            return;
        }

        FishingTarget target = fishingTarget(ctx);
        if (walkTo(ctx, target.area, target.label)) {
            return;
        }

        NPC fishingSpot = findNetFishingSpot(ctx, target);

        if (fishingSpot == null || !fishingSpot.isValid()) {
            if (System.currentTimeMillis() >= nextFishingViewAdjustAt) {
                nextFishingViewAdjustAt = System.currentTimeMillis()
                        + ThreadLocalRandom.current().nextLong(2500L, 5001L);
                log("No net fishing spot found; adjusting view before retry");
                ViewRecovery.recover(ctx, target.area.getRandomTile(), "net fishing spot", this::log);
                return;
            }

            log("No net fishing spot found");
            Time.sleep(1000, 1600);
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextFishingInteractAt) {
            Time.sleep(350, 650);
            return;
        }

        log("Net fishing at " + target.label + " until inventory full: "
                + ctx.inventory().getCount() + "/" + FULL_INVENTORY_SIZE);
        if (interactNetFishingSpot(fishingSpot)) {
            failedFishingInteractions = 0;
            nextFishingInteractAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(900L, 1601L);
            Time.sleep(2200, 3200, () -> ctx.localPlayer().isAnimating() || ctx.inventory().isFull(), 100);
            return;
        }

        handleFishingInteractionFailure(ctx, target);
    }

    private FishingTarget fishingTarget(APIContext ctx) {
        if (AL_KHARID_REGION.contains(ctx.localPlayer().getLocation())
                || needMoreRawFishForCooking
                || skillComplete(ctx, Skill.Skills.FISHING)) {
            return new FishingTarget(AL_KHARID_NET_FISHING, "Al Kharid net fishing spots");
        }

        return new FishingTarget(DRAYNOR_NET_FISHING, "Draynor net fishing spots");
    }

    private NPC findNetFishingSpot(APIContext ctx, FishingTarget target) {
        for (NPC spot : ctx.npcs()
                .query()
                .named("Fishing spot")
                .within(target.area)
                .results()
                .nearestList()) {
            if (spot != null && spot.isValid()) {
                return spot;
            }
        }

        return ctx.npcs()
                .query()
                .named("Fishing spot")
                .results()
                .nearest();
    }

    private boolean interactNetFishingSpot(NPC fishingSpot) {
        return fishingSpot.interact("Net", "Fishing spot")
                || fishingSpot.interact("Net")
                || fishingSpot.interact();
    }

    private void handleFishingInteractionFailure(APIContext ctx, FishingTarget target) {
        failedFishingInteractions++;
        nextFishingInteractAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1100L, 2201L);

        if (System.currentTimeMillis() < nextFishingViewAdjustAt) {
            log("Fishing spot click failed; waiting before retry " + failedFishingInteractions);
            Time.sleep(700, 1200);
            return;
        }

        nextFishingViewAdjustAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(2500L, 5001L);
        if (failedFishingInteractions % 3 == 0) {
            Tile tile = target.area.getRandomTile();
            log("Fishing spot click covered/blocked; repositioning near spot");
            if (!ctx.walking().walkOnMap(tile)) {
                ctx.walking().walkTo(tile);
            }
            Time.sleep(900, 1500);
            return;
        }

        log("Fishing spot click covered/blocked; adjusting camera/zoom");
        ViewRecovery.recover(ctx, target.area.getRandomTile(), "net fishing spot", this::log);
    }

    private void prepareCookingBatch(APIContext ctx) {
        stats.setTrainingSkill("Cooking");

        if (inventoryFullOfRawFish(ctx)) {
            startCookingBatchFromBank(ctx);
            return;
        }

        if (ensureAlKharidCookingBank(ctx)) {
            return;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for cooking batch");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank for cooking batch");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (rawFishCount(ctx) > 0 && inventoryOnlyHasRawFish(ctx)) {
            if (withdrawRawFishUntilFull(ctx) && inventoryFullOfRawFish(ctx)) {
                startCookingBatchFromBank(ctx);
            }
            return;
        }

        int availableRawFish = rawFishCountInBank(ctx);
        int minimumRawFish = minimumRawFishBeforeCooking();
        if (availableRawFish < minimumRawFish) {
            needMoreRawFishForCooking = true;
            preferCookingAfterFishingBank = false;
            cookingBlockedUntil = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(6, 14) * 60_000L;
            log("Need " + minimumRawFish + " raw fish before Cooking; bank has " + availableRawFish);
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!ctx.inventory().isEmpty()) {
            log("Depositing inventory before cooking batch");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.inventory().isFull() && rawFishCount(ctx) > 0) {
            startCookingBatchFromBank(ctx);
            return;
        }

        if (withdrawRawFishUntilFull(ctx) && inventoryFullOfRawFish(ctx)) {
            startCookingBatchFromBank(ctx);
        }
    }

    private void startCookingBatchFromBank(APIContext ctx) {
        cookingBatchActive = true;
        preferCookingAfterFishingBank = false;
        cookingActionActive = false;
        missingCookingWidgetAttempts = 0;
        restartCookingAfterContinue = false;
        rawFishEmptySince = 0;
        lastCookingRawCount = -1;
        lastCookingActionAt = System.currentTimeMillis();
        log("Cooking batch ready; closing bank and walking to Al Kharid range");
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(600, 900, () -> !ctx.bank().isOpen(), 100);
        }

        if (!ctx.bank().isOpen()) {
            walkToCookingRange(ctx, cookingTarget(ctx));
        }
    }

    private void cookFish(APIContext ctx) {
        stats.setTrainingSkill("Cooking");

        if (clearBlockingContinue(ctx, "Cooking tick")) {
            return;
        }

        if (restartCookingAfterContinue) {
            restartCookingAfterContinue(ctx);
            return;
        }

        if (monitorCookingAction(ctx)) {
            return;
        }

        if (waitIfCookingBusy(ctx)) {
            return;
        }

        String rawFish = rawFishInInventory(ctx);
        if (rawFish == null) {
            finishCookingBatch(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        if (ctx.menu().isOpen() && !ctx.inventory().isItemSelected()) {
            log("Closing stale menu before Cooking action: " + menuSummary(ctx));
            ctx.menu().closeMenu();
            Time.sleep(250, 450);
            return;
        }

        if (cookingInterfaceOpen(ctx)) {
            String interfaceRawFish = rawFishForOpenCookingInterface(ctx);
            handleCookInterface(ctx, interfaceRawFish != null ? interfaceRawFish : rawFish);
            Time.sleep(1200, 1800);
            return;
        }

        CookingTarget target = cookingTarget(ctx);
        if (walkToCookingRange(ctx, target)) {
            return;
        }

        SceneObject range = findReachableCookingObject(ctx, target);

        if (range == null || !range.isValid()) {
            log("No reachable range/fire found for cooking at " + target.label + "; adjusting view");
            ViewRecovery.recover(ctx, AL_KHARID_RANGE_TILE, "cooking range", this::log);
            lastCookingActionAt = System.currentTimeMillis();
            stepToCookingStand(ctx, target);
            Time.sleep(1000, 1600);
            return;
        }

        if (!canUseCookingRangeFromHere(ctx, target, range)) {
            log("Moving closer to cooking range before cooking");
            lastCookingActionAt = System.currentTimeMillis();
            stepToCookingStand(ctx, target);
            Time.sleep(1200, 1800);
            return;
        }

        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(300, 600);
            return;
        }

        int before = ctx.inventory().getCount(rawFish);
        log("Opening Cooking interface from range: " + rawFishCount(ctx) + "/" + FULL_INVENTORY_SIZE);
        lastCookingActionAt = System.currentTimeMillis();
        boolean interacted = openCookingInterfaceFromRange(ctx, range);

        if (interacted) {
            Time.sleep(
                    1800,
                    3000,
                    () -> ctx.inventory().getCount(rawFish) < before || ctx.dialogues().isDialogueOpen(),
                    100
            );
            handleCookInterface(ctx, rawFish);
            Time.sleep(1200, 1800);
            if (rawFishCount(ctx) == 0) {
                finishCookingBatch(ctx);
            }
            return;
        }

        log("Could not start cooking " + rawFish);
        clearCookingInteractionState(ctx);
        Time.sleep(1000, 1600);
    }

    private boolean openCookingInterfaceFromRange(APIContext ctx, SceneObject range) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }

        boolean clicked = range.interact("Cook", "Range")
                || range.interact("Cook")
                || ctx.mouse().click(range, false);
        Time.sleep(700, 1400, () -> cookingInterfaceOpen(ctx) || ctx.menu().isOpen(), 100);
        if (ctx.menu().isOpen()) {
            if (!selectCookRangeFromOpenMenu(ctx)) {
                log("Range click opened menu but Cook option was not found; closing: " + menuSummary(ctx));
                ctx.menu().closeMenu();
                return false;
            }
            Time.sleep(700, 1400, () -> cookingInterfaceOpen(ctx), 100);
        }

        return clicked && cookingInterfaceOpen(ctx);
    }

    private void restartCookingAfterContinue(APIContext ctx) {
        log("Restarting Cooking after continue/level-up");
        restartCookingAfterContinue = false;
        cookingActionActive = false;
        rawFishEmptySince = 0;
        lastCookingRawCount = -1;
        lastCookingActionAt = 0;
        nextCookingRecoveryLogAt = 0;
        clearCookingInteractionState(ctx);

        String rawFish = rawFishInInventory(ctx);
        if (rawFish == null) {
            finishCookingBatch(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        CookingTarget target = cookingTarget(ctx);
        SceneObject range = findReachableCookingObject(ctx, target);
        if (range == null || !range.isValid()) {
            range = findCookingObject(ctx, target);
        }

        if (range == null || !range.isValid()) {
            log("No range found while restarting Cooking after continue; adjusting view");
            ViewRecovery.recover(ctx, AL_KHARID_RANGE_TILE, "cooking range", this::log);
            stepToCookingStand(ctx, target);
            Time.sleep(900, 1400);
            restartCookingAfterContinue = true;
            return;
        }

        int before = ctx.inventory().getCount(rawFish);
        log("Reopening Cooking interface from range after continue: " + rawFishCount(ctx) + "/" + FULL_INVENTORY_SIZE);
        lastCookingActionAt = System.currentTimeMillis();
        if (openCookingInterfaceFromRange(ctx, range)) {
            Time.sleep(
                    1000,
                    1800,
                    () -> cookingInterfaceOpen(ctx) || ctx.inventory().getCount(rawFish) < before,
                    100
            );
            handleCookInterface(ctx, rawFish);
            return;
        }

        log("Could not reopen Cooking from range after continue; retrying next tick");
        restartCookingAfterContinue = true;
        Time.sleep(800, 1200);
    }

    private boolean selectCookRangeFromOpenMenu(APIContext ctx) {
        if (!ctx.menu().isOpen()) {
            return false;
        }

        log("Menu opened on range; selecting Cook through EpicBot menu API");
        boolean clicked = false;
        if (!clicked && ctx.menu().contains("Cook", "Range")) {
            clicked = ctx.menu().interact("Cook", "Range", true);
        }
        if (!clicked && ctx.menu().contains("Cook")) {
            clicked = ctx.menu().interact("Cook", true);
        }

        Time.sleep(300, 900, () -> !ctx.menu().isOpen() || cookingInterfaceOpen(ctx), 50);
        return clicked;
    }

    private void clearCookingInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private String menuSummary(APIContext ctx) {
        return "actions=" + ctx.menu().getActions() + ", options=" + ctx.menu().getOptions();
    }

    private boolean isOnCookingStandTile(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null
                && location.getX() == AL_KHARID_COOKING_STAND_TILE.getX()
                && location.getY() == AL_KHARID_COOKING_STAND_TILE.getY()
                && location.getPlane() == AL_KHARID_COOKING_STAND_TILE.getPlane();
    }

    private boolean isNearCookingStandTile(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null
                && location.getPlane() == AL_KHARID_COOKING_STAND_TILE.getPlane()
                && AL_KHARID_COOKING_STAND_TILE.tileDistanceTo(ctx) <= 2;
    }

    private boolean isAtCookingRangePosition(APIContext ctx, CookingTarget target) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null
                && location.getPlane() == AL_KHARID_COOKING_STAND_TILE.getPlane()
                && (isNearCookingStandTile(ctx) || target.area.contains(location));
    }

    private boolean canUseCookingRangeFromHere(APIContext ctx, CookingTarget target, SceneObject range) {
        if (range == null || !range.isValid()) {
            return false;
        }
        return isAtCookingRangePosition(ctx, target) || range.tileDistanceTo(ctx) <= 3;
    }

    private CookingTarget cookingTarget(APIContext ctx) {
        return new CookingTarget(AL_KHARID_RANGE, AL_KHARID_RANGE_SEARCH, "Al Kharid range");
    }

    private boolean ensureAlKharidCookingBank(APIContext ctx) {
        if (AL_KHARID_REGION.contains(ctx.localPlayer().getLocation())) {
            return false;
        }

        if (ctx.bank().isOpen()) {
            log("Closing bank before Al Kharid Cooking bypass route");
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        log("Walking to Al Kharid bank with webwalking for Cooking");
        Navigation.walkTo(ctx, AL_KHARID_BANK.getRandomTile());
        Time.sleep(1200, 1800);
        return true;
    }

    private SceneObject findCookingObject(APIContext ctx, CookingTarget target) {
        SceneObject cookingObject = ctx.objects()
                .query()
                .id(AL_KHARID_RANGE_ID)
                .within(target.searchArea)
                .reachable()
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        cookingObject = ctx.objects()
                .query()
                .id(AL_KHARID_RANGE_ID)
                .within(target.searchArea)
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        cookingObject = ctx.objects()
                .query()
                .named(COOKING_RANGE_NAMES)
                .within(target.searchArea)
                .reachable()
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        cookingObject = ctx.objects()
                .query()
                .named(COOKING_RANGE_NAMES)
                .within(target.searchArea)
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        return ctx.objects()
                .query()
                .named(COOKING_RANGE_NAMES)
                .results()
                .nearest();
    }

    private SceneObject findReachableCookingObject(APIContext ctx, CookingTarget target) {
        SceneObject cookingObject = ctx.objects()
                .query()
                .id(AL_KHARID_RANGE_ID)
                .within(target.searchArea)
                .reachable()
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        cookingObject = ctx.objects()
                .query()
                .actions("Cook")
                .within(target.searchArea)
                .reachable()
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        cookingObject = ctx.objects()
                .query()
                .named(COOKING_RANGE_NAMES)
                .within(target.searchArea)
                .reachable()
                .results()
                .nearest();
        if (cookingObject != null && cookingObject.isValid()) {
            return cookingObject;
        }

        if (isAtCookingRangePosition(ctx, target)) {
            return findCookingObject(ctx, target);
        }
        return null;
    }

    private void finishCookingBatch(APIContext ctx) {
        if (rawFishCount(ctx) > 0) {
            cookingBatchActive = true;
            log("Raw fish still in inventory; continuing Cooking before banking");
            cookFish(ctx);
            return;
        }

        cookingActionActive = false;
        lastCookingRawCount = -1;

        if (hasCookedOrBurntFish(ctx)) {
            bankCookedFish(ctx);
            return;
        }

        if (continueCookingBankedRawFish(ctx)) {
            return;
        }

        cookingBatchActive = false;
        needMoreRawFishForCooking = false;
        preferCookingAfterFishingBank = false;
        log("Cooking bank drain complete; no raw fish left in bank");
        Time.sleep(600, 900);
    }

    private boolean monitorCookingAction(APIContext ctx) {
        if (!cookingActionActive) {
            return false;
        }

        if (clearBlockingContinue(ctx, "Cooking progress")) {
            return true;
        }

        int rawFishCount = rawFishCount(ctx);
        long now = System.currentTimeMillis();
        if (rawFishCount <= 0) {
            if (rawFishEmptySince == 0) {
                rawFishEmptySince = now;
                log("Raw fish looks empty; confirming before banking cooked fish");
                Time.sleep(900, 1400);
                return true;
            }

            if (now - rawFishEmptySince < 3_000L || ctx.localPlayer().isAnimating() || hasBlockingContinue(ctx)) {
                Time.sleep(700, 1100);
                return true;
            }

            cookingActionActive = false;
            lastCookingRawCount = -1;
            log("Cooking inventory finished");
            finishCookingBatch(ctx);
            return true;
        }

        rawFishEmptySince = 0;
        if (lastCookingRawCount != rawFishCount) {
            lastCookingRawCount = rawFishCount;
            lastCookingRawChangeAt = now;
        }

        boolean recentlyStarted = now - cookingActionStartedAt < 10_000L;
        boolean recentlyProgressed = now - lastCookingRawChangeAt < 7_000L;
        if (ctx.localPlayer().isAnimating() || recentlyStarted || recentlyProgressed) {
            if (now >= nextCookingProgressLogAt) {
                log("Cooking in progress; raw fish left " + rawFishCount);
                nextCookingProgressLogAt = now + 10_000L;
            }
            Time.sleep(900, 1500);
            return true;
        }

        cookingActionActive = false;
        log("Cooking action stalled with raw fish left; reopening Cooking interface");
        return false;
    }

    private boolean continueCookingBankedRawFish(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for next Cooking inventory");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for next Cooking inventory");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return true;
        }

        if (!ctx.inventory().isEmpty()) {
            log("Depositing inventory before next Cooking withdrawal");
            ctx.bank().depositInventory();
            Time.sleep(600, 900);
            return true;
        }

        int bankedRawFish = rawFishCountInBank(ctx);
        if (bankedRawFish <= 0) {
            return false;
        }

        log("Continuing Cooking: raw fish left in bank " + bankedRawFish);
        if (withdrawRawFishUntilFull(ctx) && rawFishCount(ctx) > 0) {
            startCookingBatchFromBank(ctx);
            return true;
        }

        Time.sleep(900, 1400);
        return true;
    }

    private boolean walkToCookingRange(APIContext ctx, CookingTarget target) {
        SceneObject range = findReachableCookingObject(ctx, target);
        if (canUseCookingRangeFromHere(ctx, target, range)) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (ctx.localPlayer().isMoving() && now - lastCookingActionAt < 8_000L) {
            Time.sleep(600, 900);
            return true;
        }

        lastCookingActionAt = now;
        stepToCookingStand(ctx, target);
        Time.sleep(1200, 1800);
        return true;
    }

    private void stepToCookingStand(APIContext ctx, CookingTarget target) {
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
                AL_KHARID_COOKING_STAND_TILE
        );
        if (tryOpenCookingRouteObject(ctx, blocker, "blocking object")) {
            return;
        }

        SceneObject door = findCookingRouteDoor(ctx);
        if (door != null && door.isValid() && !isAtCookingRangePosition(ctx, target)) {
            if (door.tileDistanceTo(ctx) > 2) {
                walkLocallyTo(ctx, door, "Al Kharid range door");
                return;
            }
            if (tryOpenCookingRouteObject(ctx, door, "range door")) {
                return;
            }
        }

        int distance = AL_KHARID_COOKING_STAND_TILE.tileDistanceTo(ctx);
        if (distance <= 25) {
            if (walkLocallyTo(ctx, AL_KHARID_COOKING_STAND_TILE, target.label + " stand tile")) {
                return;
            }
        }

        if (AL_KHARID_RANGE_TILE.tileDistanceTo(ctx) <= 25) {
            if (walkLocallyTo(ctx, AL_KHARID_RANGE_TILE, target.label + " object tile")) {
                return;
            }
        }

        if (!AL_KHARID_REGION.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Al Kharid before local range approach");
            Navigation.walkTo(ctx, AL_KHARID_BANK.getRandomTile());
            return;
        }

        log("Local range approach failed; waiting for scene/path update");
    }

    private boolean walkLocallyTo(APIContext ctx, Locatable destination, String label) {
        log("Walking locally to " + label);
        boolean walked = ctx.walking().walkOnScreen(destination)
                || ctx.walking().walkToOnScreen(destination)
                || ctx.walking().walkOnMap(destination)
                || ctx.walking().walkTo(destination);
        if (walked) {
            Time.sleep(700, 1200, () -> ctx.localPlayer().isMoving(), 100);
        }
        return walked;
    }

    private boolean tryOpenCookingRouteObject(APIContext ctx, SceneObject object, String label) {
        if (object == null || !object.isValid()) {
            return false;
        }
        if (!object.hasAction("Open", "Enter", "Walk-through")) {
            return false;
        }

        log("Opening " + label + " before cooking: " + object.getName());
        boolean interacted = object.interact("Open")
                || object.interact("Enter")
                || object.interact("Walk-through")
                || ctx.menu().interact("Open", object, false)
                || ctx.menu().interact("Enter", object, false);
        if (interacted) {
            Time.sleep(900, 1600);
        }
        return interacted;
    }

    private SceneObject findCookingRouteDoor(APIContext ctx) {
        SceneObject door = ctx.objects()
                .query()
                .named("Door")
                .actions("Open")
                .within(AL_KHARID_RANGE_SEARCH)
                .results()
                .nearest();
        if (door != null && door.isValid()) {
            return door;
        }

        return ctx.objects()
                .query()
                .actions("Open")
                .within(AL_KHARID_RANGE_SEARCH)
                .results()
                .nearest();
    }

    private boolean waitIfCookingBusy(APIContext ctx) {
        if (ctx.localPlayer().isAnimating()) {
            Time.sleep(600, 900);
            return true;
        }

        if (!ctx.localPlayer().isMoving()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastCookingActionAt < 15_000L) {
            Time.sleep(600, 900);
            return true;
        }

        if (now >= nextCookingRecoveryLogAt) {
            log("Cooking movement looks stale; retrying route/action");
            nextCookingRecoveryLogAt = now + 20_000L;
        }
        return false;
    }

    private void bankRawFish(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank with raw fish");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank to store raw fish");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        int rawFishCount = rawFishCount(ctx);
        log("Banking " + rawFishCount + " raw fish");
        ctx.bank().depositAll("Raw anchovies");
        ctx.bank().depositAll("Raw shrimps");
        Time.sleep(600, 900);
        int bankedRawFish = rawFishCountInBank(ctx);
        int minimumRawFish = minimumRawFishBeforeCooking();
        needMoreRawFishForCooking = bankedRawFish < minimumRawFish;
        preferCookingAfterFishingBank = bankedRawFish >= minimumRawFish
                && !skillComplete(ctx, Skill.Skills.COOKING)
                && (forceCookingAfterBank || ThreadLocalRandom.current().nextInt(100) < 45);
        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private int minimumRawFishBeforeCooking() {
        return MIN_RAW_FISH_BEFORE_COOKING;
    }

    private boolean skillComplete(APIContext ctx, Skill.Skills skill) {
        return !fundingMode && complete(ctx, skill);
    }

    private void bankCookedFish(APIContext ctx) {
        if (rawFishCount(ctx) > 0) {
            log("Raw fish still in inventory with cooked fish; resuming Cooking instead of banking");
            cookingBatchActive = true;
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(500, 800);
                return;
            }
            cookFish(ctx);
            return;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank with cooked fish");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank to store cooked fish");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        log("Banking cooked/burnt fish");
        ctx.bank().depositAll(item -> isCookedOrBurntFoodName(item.getName()));
        Time.sleep(600, 900);
        if (fundingMode) {
            log("Funding cooked fish banked; keeping bank open for planner audit");
            return;
        }
        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private void handleCookInterface(APIContext ctx, String rawFish) {
        if (clearBlockingContinue(ctx, "Cooking interface")) {
            return;
        }

        if (ctx.dialogues().canContinue()) {
            ctx.dialogues().selectContinue();
            Time.sleep(400, 700);
        }

        if (ctx.dialogues().hasOptionContaining("Cook")) {
            ctx.dialogues().selectOption(option -> option != null && option.toLowerCase().contains("cook"));
            Time.sleep(800, 1200);
        }

        String interfaceRawFish = rawFishForOpenCookingInterface(ctx);
        if (interfaceRawFish != null) {
            rawFish = interfaceRawFish;
        }

        WidgetChild foodWidget = findCookingWidget(ctx, rawFish);
        if (foodWidget == null) {
            handleMissingCookingWidget(ctx, rawFish);
            return;
        }

        missingCookingWidgetAttempts = 0;
        final String selectedRawFish = rawFish;
        int before = ctx.inventory().getCount(selectedRawFish);
        selectCookingAllQuantity(ctx);

        log("Starting Cooking batch by clicking product widget: " + widgetSummary(foodWidget));
        if (clickCookingProduct(ctx, foodWidget)) {
            cookingActionActive = true;
            cookingActionStartedAt = System.currentTimeMillis();
            lastCookingRawCount = before;
            lastCookingRawChangeAt = cookingActionStartedAt;
            nextCookingProgressLogAt = cookingActionStartedAt + 10_000L;
            Time.sleep(
                    800,
                    1600,
                    () -> ctx.localPlayer().isAnimating()
                            || ctx.inventory().getCount(selectedRawFish) < before
                            || !cookingInterfaceOpen(ctx),
                    100
            );
            return;
        }

        log("Could not click Cooking product widget: " + widgetSummary(foodWidget));
    }

    private boolean clearBlockingContinue(APIContext ctx, String reason) {
        if (!hasBlockingContinue(ctx)) {
            return false;
        }

        log("Closing continue/level-up with SPACE before " + reason);
        cookingActionActive = false;
        lastCookingRawCount = -1;
        missingCookingWidgetAttempts = 0;
        rawFishEmptySince = 0;

        ctx.keyboard().typeKey(32);
        Time.sleep(550, 900, () -> !hasBlockingContinue(ctx), 100);

        if (hasBlockingContinue(ctx)) {
            ctx.keyboard().sendKey(32);
            Time.sleep(550, 900, () -> !hasBlockingContinue(ctx), 100);
        }

        if (rawFishCount(ctx) > 0) {
            cookingBatchActive = true;
            restartCookingAfterContinue = true;
            lastCookingActionAt = 0;
            nextCookingRecoveryLogAt = 0;
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

    private void handleMissingCookingWidget(APIContext ctx, String rawFish) {
        missingCookingWidgetAttempts++;
        log("Cooking interface open, but no product widget found for " + rawFish
                + " (attempt " + missingCookingWidgetAttempts + ")");

        if (clearBlockingContinue(ctx, "missing Cooking product")) {
            return;
        }

        ctx.keyboard().typeKey(32);
        Time.sleep(400, 800);

        if (missingCookingWidgetAttempts >= 3) {
            log("Resetting stale Cooking interface after missing product widget");
            ctx.widgets().closeInterface();
            clearCookingInteractionState(ctx);
            missingCookingWidgetAttempts = 0;
            Time.sleep(700, 1200);
        }
    }

    private boolean cookingInterfaceOpen(APIContext ctx) {
        return cookingPromptVisible(ctx) && findCookingWidget(ctx, rawFishInInventory(ctx)) != null;
    }

    private WidgetChild findCookingWidget(APIContext ctx, String rawFish) {
        WidgetChild firstSkillmultiItem = ctx.widgets().get(SKILLMULTI_GROUP, SKILLMULTI_FIRST_ITEM_CHILD);
        if (isCookingProductionWidget(firstSkillmultiItem)) {
            return firstSkillmultiItem;
        }

        if (!cookingPromptVisible(ctx)) {
            return null;
        }

        WidgetChild namedWidget = rawFish == null ? null : findNamedCookingWidget(ctx, rawFish);
        if (namedWidget != null) {
            return namedWidget;
        }

        return findChatboxCookingProductWidget(ctx);
    }

    private String rawFishForOpenCookingInterface(APIContext ctx) {
        for (String rawFish : RAW_FISH) {
            if (findNamedCookingWidget(ctx, rawFish) != null) {
                return rawFish;
            }
        }
        return cookingInterfaceOpen(ctx) ? rawFishInInventory(ctx) : null;
    }

    private WidgetChild findNamedCookingWidget(APIContext ctx, String rawFish) {
        WidgetChild byItemName = firstCookingProductionWidget(ctx.widgets()
                .query()
                .itemName(rawFish)
                .results());
        if (byItemName != null && byItemName.isValid()) {
            return byItemName;
        }

        WidgetChild byText = firstCookingProductionWidget(ctx.widgets()
                .query()
                .textContains(rawFish)
                .results());
        if (byText != null && byText.isValid()) {
            return byText;
        }

        String cookedName = rawFish.replace("Raw ", "");
        return firstCookingProductionWidget(ctx.widgets()
                .query()
                .group(InterfaceID.CHATBOX, SKILLMULTI_GROUP)
                .textContains(cookedName)
                .results());
    }

    private WidgetChild findChatboxCookingProductWidget(APIContext ctx) {
        for (WidgetChild widget : ctx.widgets()
                .query()
                .group(InterfaceID.CHATBOX)
                .results()) {
            if (isChatboxCookingProductWidget(widget)) {
                return widget;
            }
        }
        return null;
    }

    private void selectCookingAllQuantity(APIContext ctx) {
        WidgetChild allWidget = findCookingAllWidget(ctx);
        if (allWidget != null) {
            log("Selecting Cooking quantity All: " + widgetSummary(allWidget));
            clickWidgetCenter(ctx, allWidget);
            Time.sleep(250, 550);
            return;
        }

        if (clickChatboxAllFallback(ctx)) {
            log("Selecting Cooking quantity All by chatbox fallback point");
            Time.sleep(250, 550);
            return;
        }

        log("Cooking All quantity widget not found; waiting instead of clicking random product");
        Time.sleep(600, 1000);
    }

    private WidgetChild findCookingAllWidget(APIContext ctx) {
        WidgetChild exactAll = ctx.widgets().get(SKILLMULTI_GROUP, SKILLMULTI_ALL_CHILD);
        if (isCookingControlWidget(exactAll)) {
            return exactAll;
        }

        for (WidgetChild widget : ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .actions("All", "Cook All", "Cook-all", "Make All", "Make-all")
                .results()) {
            if (isCookingControlWidget(widget)) {
                return widget;
            }
        }

        for (WidgetChild widget : ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .text("All")
                .results()) {
            if (isCookingControlWidget(widget)) {
                return widget;
            }
        }

        return null;
    }

    private boolean clickWidget(APIContext ctx, WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && (ctx.mouse().click(widget, false) || widget.click(false) || widget.click());
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean clickCookingProduct(APIContext ctx, WidgetChild widget) {
        if (clickWidgetCenter(ctx, widget)) {
            return true;
        }
        return clickChatboxProductFallback(ctx);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private WidgetChild firstCookingProductionWidget(Iterable<WidgetChild> widgets) {
        for (WidgetChild widget : widgets) {
            if (isCookingProductionWidget(widget)) {
                return widget;
            }
        }
        return null;
    }

    private boolean isCookingProductionWidget(WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return false;
        }

        if (widget.getParentId() == INVENTORY_WIDGET_GROUP) {
            return false;
        }

        if (widget.getGroup() != null && widget.getGroup().getIndex() == INVENTORY_WIDGET_GROUP) {
            return false;
        }

        if (widget.getWidth() <= 0 || widget.getHeight() <= 0) {
            return false;
        }

        if (isSkillmultiWidget(widget)) {
            return widget.getItemId() > 0
                    || widget.getName() != null && widget.getName().toLowerCase().contains("raw");
        }

        return isChatboxCookingProductWidget(widget);
    }

    private boolean isCookingControlWidget(WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return false;
        }
        if (widget.getWidth() <= 0 || widget.getHeight() <= 0) {
            return false;
        }
        if (widget.getParentId() == INVENTORY_WIDGET_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == INVENTORY_WIDGET_GROUP)) {
            return false;
        }

        return isSkillmultiWidget(widget) || isChatboxCookingControlWidget(widget);
    }

    private boolean isSkillmultiWidget(WidgetChild widget) {
        return widget != null
                && (widget.getParentId() == SKILLMULTI_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == SKILLMULTI_GROUP));
    }

    private boolean isChatboxCookingProductWidget(WidgetChild widget) {
        return isChatboxCookingWidget(widget)
                && widget.getItemId() > 0
                && widget.getWidth() >= 25
                && widget.getHeight() >= 20
                && widget.getAbsoluteX() >= 120
                && widget.getAbsoluteX() <= 380;
    }

    private boolean isChatboxCookingControlWidget(WidgetChild widget) {
        if (!isChatboxCookingWidget(widget)) {
            return false;
        }

        String text = (widget.getText() + " " + widget.getRawText()).toLowerCase();
        return text.contains("all")
                || widget.hasAction("All", "Cook All", "Cook-all", "Make All", "Make-all");
    }

    private boolean isChatboxCookingWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getGroup() != null
                && widget.getGroup().getIndex() == InterfaceID.CHATBOX
                && widget.getAbsoluteY() >= 330
                && widget.getAbsoluteY() <= 510;
    }

    private boolean cookingPromptVisible(APIContext ctx) {
        return ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .textContains("How many would you like to cook?", "Choose a quantity", "What would you like to cook?")
                .results()
                .size() > 0;
    }

    private boolean clickChatboxAllFallback(APIContext ctx) {
        WidgetChild chatBackground = ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.CHAT_BACKGROUND));
        if (!isVisibleWidget(chatBackground)) {
            return false;
        }

        Point point = new Point(
                chatBackground.getAbsoluteX() + chatBackground.getWidth() - 36,
                chatBackground.getAbsoluteY() + 35
        );
        return ctx.mouse().click(point, false);
    }

    private boolean clickChatboxProductFallback(APIContext ctx) {
        WidgetChild chatBackground = ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.CHAT_BACKGROUND));
        if (!isVisibleWidget(chatBackground)) {
            return false;
        }

        Point point = new Point(
                chatBackground.getAbsoluteX() + chatBackground.getWidth() / 2,
                chatBackground.getAbsoluteY() + 72
        );
        return ctx.mouse().click(point, false);
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
                + ", text='" + widget.getText() + "'"
                + ", name='" + widget.getName() + "'";
    }

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }

    private int rawFishCount(APIContext ctx) {
        int count = 0;
        for (String fish : RAW_FISH) {
            count += ctx.inventory().getCount(fish);
        }
        return count;
    }

    private int rawFishCountInBank(APIContext ctx) {
        int count = 0;
        for (String fish : RAW_FISH) {
            count += ctx.bank().getCount(fish);
        }
        return count;
    }

    private String bestRawFishInBank(APIContext ctx) {
        int anchovies = ctx.bank().getCount("Raw anchovies");
        int shrimps = ctx.bank().getCount("Raw shrimps");
        return anchovies > shrimps ? "Raw anchovies" : "Raw shrimps";
    }

    private boolean withdrawRawFishUntilFull(APIContext ctx) {
        String firstFish = bestRawFishInBank(ctx);
        String secondFish = "Raw anchovies".equals(firstFish) ? "Raw shrimps" : "Raw anchovies";

        if (withdrawRawFish(ctx, firstFish)) {
            return true;
        }

        if (withdrawRawFish(ctx, secondFish)) {
            return true;
        }

        log("Could not withdraw raw fish for Cooking");
        Time.sleep(1000, 1600);
        return false;
    }

    private boolean withdrawRawFish(APIContext ctx, String fishName) {
        int bankCount = ctx.bank().getCount(fishName);
        int freeSlots = ctx.inventory().getEmptySlotCount();
        if (bankCount <= 0 || freeSlots <= 0) {
            return false;
        }

        int amount = Math.min(bankCount, freeSlots);
        int beforeRawFish = rawFishCount(ctx);
        log("Withdrawing " + amount + "x " + fishName + " for full Cooking inventory");
        boolean withdrawn = ctx.bank().withdraw(amount, fishName)
                || ctx.bank().withdrawAny(amount, fishName)
                || (amount == bankCount && ctx.bank().withdrawAll(fishName));
        if (withdrawn) {
            Time.sleep(600, 1200, () -> rawFishCount(ctx) > beforeRawFish || ctx.inventory().isFull(), 100);
        }
        return withdrawn && rawFishCount(ctx) > beforeRawFish;
    }

    private String rawFishInInventory(APIContext ctx) {
        for (String fish : RAW_FISH) {
            if (ctx.inventory().contains(fish)) {
                return fish;
            }
        }
        return null;
    }

    private boolean inventoryFullOfRawFish(APIContext ctx) {
        return ctx.inventory().isFull() && inventoryOnlyHasRawFish(ctx);
    }

    private boolean inventoryOnlyHasRawFish(APIContext ctx) {
        int rawFishCount = rawFishCount(ctx);
        return rawFishCount > 0 && ctx.inventory().getCount() == rawFishCount;
    }

    private boolean hasCookedOrBurntFish(APIContext ctx) {
        for (String fish : COOKED_OR_BURNT_FISH) {
            if (ctx.inventory().contains(fish)) {
                return true;
            }
        }

        for (var item : ctx.inventory().getItems()) {
            if (isCookedOrBurntFoodName(item.getName())) {
                return true;
            }
        }

        return false;
    }

    private boolean isCookedOrBurntFoodName(String name) {
        if (name == null) {
            return false;
        }

        String lower = name.toLowerCase();
        return lower.equals("anchovies")
                || lower.equals("shrimps")
                || lower.startsWith("burnt ");
    }

    private String[] skillingItemsToKeep() {
        return new String[]{
                "Small fishing net",
                "Coins",
                "Raw anchovies",
                "Raw shrimps",
                "Anchovies",
                "Shrimps",
                "Burnt fish",
                "Burnt shrimp",
                "Burnt shrimps",
                "Burnt anchovies"
        };
    }

    private static class FishingTarget {
        private final Area area;
        private final String label;

        private FishingTarget(Area area, String label) {
            this.area = area;
            this.label = label;
        }
    }

    private static class CookingTarget {
        private final Area area;
        private final Area searchArea;
        private final String label;

        private CookingTarget(Area area, Area searchArea, String label) {
            this.area = area;
            this.searchArea = searchArea;
            this.label = label;
        }
    }

}
