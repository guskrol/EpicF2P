package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.gameval.ObjectID;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class CookAssistantQuestModule implements ManagedF2PModule {
    private static final Area COOK_AREA = new Area(3205, 3211, 3213, 3218);
    private static final Tile COOK_STAND_TILE = new Tile(3206, 3214, 0);
    private static final Area EGG_AREA = new Area(3174, 3292, 3181, 3300);
    private static final Area DAIRY_COW_AREA = new Area(3168, 3312, 3177, 3322);
    private static final Area MILL_AREA = new Area(3161, 3302, 3171, 3311);
    private static final Tile MILL_LADDER = new Tile(3164, 3307, 0);
    private static final Tile MILL_HOPPER = new Tile(3166, 3307, 2);
    private static final Tile MILL_CONTROLS = new Tile(3166, 3305, 2);
    private static final Tile MILL_BIN = new Tile(3166, 3306, 0);
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490);
    private static final int EGG_SEARCH_DISTANCE = 18;
    private static final int EGG_REPOSITION_AFTER_MISSES = 3;
    private static final int DAIRY_COW_ID = 1172;

    private static final String EGG = "Egg";
    private static final String BUCKET = "Bucket";
    private static final String BUCKET_OF_MILK = "Bucket of milk";
    private static final String POT = "Pot";
    private static final String GRAIN = "Grain";
    private static final String POT_OF_FLOUR = "Pot of flour";
    private static final String COINS = "Coins";

    private static final String[] FINAL_INGREDIENTS = {
            BUCKET_OF_MILK,
            EGG,
            POT_OF_FLOUR
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;

    private String pendingBuyItem;
    private int pendingBuyPrice;
    private long nextTravelLogAt;
    private long nextRecoveryLogAt;
    private long nextGeCollectAt;
    private long offerPlacedAt;
    private int geCollectionAttempts;
    private long nextActionAt;
    private long lastMaterialProgressAt;
    private int lastMaterialSignature = -1;
    private long nextStaleDialogueLogAt;
    private String dialogueSnapshot = "";
    private int unchangedDialogueActions;
    private boolean dialogueRecoveryIssued;
    private long nextEggDebugAt;
    private boolean bankChecked;
    private int bankWalkFailures;
    private int consecutiveEggMisses;
    private boolean grainLoaded;
    private int millActionFailures;

    public CookAssistantQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "quests.cooks_assistant";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        if (System.currentTimeMillis() < nextActionAt) return;
        stats.setTrainingSkill("Questing");

        if (isComplete(ctx)) {
            stats.setStatus("Cook's Assistant complete");
            closeCompletionScreen(ctx);
            return;
        }

        int signature = materialSignature(ctx);
        if (signature != lastMaterialSignature) {
            lastMaterialSignature = signature;
            lastMaterialProgressAt = System.currentTimeMillis();
        } else if (lastMaterialProgressAt > 0
                && System.currentTimeMillis() - lastMaterialProgressAt > 4 * 60_000L) {
            logRecovery("Cook's Assistant: no ingredient progress for 4 minutes; rechecking bank/GE");
            bankChecked = false;
            lastMaterialProgressAt = System.currentTimeMillis();
        }

        if (handleDialogue(ctx)) {
            return;
        }

        if (ctx.grandExchange().isOpen() || pendingBuyItem != null) {
            handleGrandExchange(ctx);
            return;
        }

        if (hasAllFinalIngredients(ctx)) {
            talkToCook(ctx);
            return;
        }

        if (!bankChecked || ctx.bank().isOpen()) {
            handleBank(ctx);
            return;
        }

        String itemToBuy = firstMissingBuyableIngredient(ctx);
        if (itemToBuy != null) {
            if (ctx.inventory().getCount(true, COINS) >= buyPrice(itemToBuy))
                startGrandExchangeBuy(ctx, itemToBuy);
            else if (!collectSimpleWorldIngredient(ctx))
                logRecovery("Cook's Assistant: insufficient coins for " + itemToBuy
                        + "; local ingredient recovery needed");
            return;
        }

        String missing = firstMissingIngredient(ctx);
        stats.setStatus("Cook's Assistant: missing " + missing + "; waiting for manual fallback");
        logRecovery("Cook's Assistant: cannot resolve missing ingredient yet: " + missing);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(IQuestAPI.Quest.COOKS_ASSISTANT);
    }

    @Override
    public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0
                : ctx.quests().isStarted(IQuestAPI.Quest.COOKS_ASSISTANT) ? 92 : 62;
    }

    private boolean handleDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            dialogueSnapshot = "";
            unchangedDialogueActions = 0;
            dialogueRecoveryIssued = false;
            return false;
        }
        if (!allowDialogueAction(ctx)) return false;

        if (ctx.dialogues().canContinue()) {
            stats.setStatus("Cook's Assistant: continuing dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(500, 850);
            return true;
        }

        if (selectDialogueOption(ctx, "yes", true, "confirming quest start")) {
            return true;
        }

        String[] preferredOptions = {
                "what's wrong",
                "what is wrong",
                "can i help",
                "help a cook",
                "happy to help a cook",
                "i'm always happy",
                "i am always happy",
                "happy to help",
                "i have everything",
                "i've got",
                "i have",
                "here you go",
                "sure"
        };

        for (String option : preferredOptions) {
            if (selectDialogueOption(ctx, option, false, "selecting dialogue option")) {
                return true;
            }
        }

        if (!ctx.dialogues().getOptions().isEmpty()) {
            stats.setStatus("Cook's Assistant: selecting first non-negative option");
            ctx.dialogues().selectOption(text -> text != null && !isNegativeOption(text));
            Time.sleep(600, 950);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now >= nextStaleDialogueLogAt) {
            log("Cook's Assistant: stale dialogue flag ignored; continuing quest logic");
            nextStaleDialogueLogAt = now + 15_000L;
        }
        return false;
    }

    private boolean allowDialogueAction(APIContext ctx) {
        List<WidgetChild> options = ctx.dialogues().getOptions();
        String snapshot = String.valueOf(ctx.dialogues().getText()) + ":"
                + (options == null ? List.of() : options.stream()
                .map(option -> option == null ? "" : option.getText()).toList());
        if (!snapshot.equals(dialogueSnapshot)) {
            dialogueSnapshot = snapshot;
            unchangedDialogueActions = 0;
            dialogueRecoveryIssued = false;
        }
        if (++unchangedDialogueActions <= 4) return true;
        if (!dialogueRecoveryIssued) {
            log("Cook's Assistant: dialogue unchanged after four actions; closing it before retrying stage");
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            dialogueRecoveryIssued = true;
        }
        return false;
    }

    private boolean selectDialogueOption(APIContext ctx, String expected, boolean exact, String reason) {
        String normalizedExpected = normalizeDialogueText(expected);
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            String text = normalizeDialogueText(option.getText());
            String rawText = normalizeDialogueText(option.getRawText());
            if (!matchesDialogueOption(text, normalizedExpected, exact)
                    && !matchesDialogueOption(rawText, normalizedExpected, exact)) {
                continue;
            }

            stats.setStatus("Cook's Assistant: " + reason);
            log("Cook's Assistant: " + reason + " -> " + expected);
            if (clickWidgetCenter(ctx, option)
                    || option.click(false)
                    || ctx.dialogues().selectOption(candidate -> matchesDialogueOption(
                    normalizeDialogueText(candidate),
                    normalizedExpected,
                    exact))) {
                Time.sleep(700, 1100);
                return true;
            }
        }

        if (!exact && ctx.dialogues().hasOptionContaining(expected)) {
            stats.setStatus("Cook's Assistant: " + reason);
            log("Cook's Assistant: " + reason + " containing '" + expected + "'");
            if (ctx.dialogues().selectOption(text -> matchesDialogueOption(
                    normalizeDialogueText(text),
                    normalizedExpected,
                    false))) {
                Time.sleep(600, 950);
                return true;
            }
        }

        return false;
    }

    private boolean matchesDialogueOption(String candidate, String expected, boolean exact) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return exact ? candidate.equals(expected) : candidate.contains(expected);
    }

    private String normalizeDialogueText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("<br>", " ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("[^a-z0-9' ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void handleBank(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                walkToBankForIngredients(ctx);
                return;
            }

            stats.setStatus("Cook's Assistant: opening bank for ingredients");
            log("Cook's Assistant: opening bank for ingredients");
            Navigation.openBank(ctx);
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (ctx.inventory().getEmptySlotCount() < 4 && !hasAllFinalIngredients(ctx)) {
            stats.setStatus("Cook's Assistant: depositing non-quest inventory");
            log("Cook's Assistant: depositing non-quest inventory");
            ctx.bank().depositAllExcept(item -> item != null && shouldKeepInventoryItem(item.getName()));
            Time.sleep(650, 1000);
            return;
        }

        for (String item : FINAL_INGREDIENTS) {
            if (ctx.inventory().contains(carried -> isNamedItem(carried, item) && isNoted(carried))) {
                boolean deposited = ctx.bank().depositAll(carried -> isNamedItem(carried, item)
                        && isNoted(carried));
                log("Cook's Assistant: depositing noted " + item + " for item withdrawal=" + deposited);
                nextActionAt = System.currentTimeMillis() + 800L;
                return;
            }
        }

        for (String item : FINAL_INGREDIENTS) {
            if (!hasUsable(ctx, item) && ctx.bank().contains(item)) {
                if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                    boolean selected = ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                    log("Cook's Assistant: selected bank item withdrawal mode=" + selected);
                    nextActionAt = System.currentTimeMillis() + 800L;
                    return;
                }
                stats.setStatus("Cook's Assistant: withdrawing " + item);
                log("Cook's Assistant: withdrawing " + item);
                ctx.bank().withdraw(1, item);
                Time.sleep(650, 1000, () -> hasUsable(ctx, item), 100);
                return;
            }
        }

        if (!hasUsable(ctx, BUCKET_OF_MILK)
                && !ctx.inventory().contains(BUCKET)
                && ctx.bank().contains(BUCKET)) {
            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                nextActionAt = System.currentTimeMillis() + 800L;
                return;
            }
            stats.setStatus("Cook's Assistant: withdrawing Bucket for milk");
            log("Cook's Assistant: withdrawing Bucket for milk");
            ctx.bank().withdraw(1, BUCKET);
            Time.sleep(650, 1000, () -> ctx.inventory().contains(BUCKET), 100);
            return;
        }

        if (needsGeBuy(ctx) && ctx.inventory().getCount(true, COINS) < estimatedMissingBuyCost(ctx)
                && ctx.bank().contains(COINS)) {
            stats.setStatus("Cook's Assistant: withdrawing coins for GE ingredients");
            log("Cook's Assistant: withdrawing coins for GE ingredients");
            ctx.bank().withdrawAll(COINS);
            Time.sleep(650, 1000, () -> ctx.inventory().contains(COINS), 100);
            return;
        }

        bankChecked = true;
        stats.setStatus("Cook's Assistant: ingredient bank check complete");
        ctx.bank().close();
        Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
    }

    private boolean collectSimpleWorldIngredient(APIContext ctx) {
        if (!hasUsable(ctx, EGG)) {
            return collectEgg(ctx);
        }

        if (!hasUsable(ctx, BUCKET_OF_MILK) && !ctx.inventory().contains(BUCKET)) {
            return collectContainer(ctx, BUCKET);
        }

        if (!hasUsable(ctx, BUCKET_OF_MILK)) {
            if (ctx.localPlayer().getLocation().getY() > 9000) return leaveCellar(ctx);
            return milkDairyCow(ctx);
        }

        if (!hasUsable(ctx, POT_OF_FLOUR)) return makeFlour(ctx);

        return false;
    }

    private boolean collectContainer(APIContext ctx, String name) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return true;
        boolean cellar = here.getY() > 9000;
        if (POT.equals(name) && cellar) return leaveCellar(ctx);
        if (BUCKET.equals(name) && !cellar) {
            if (!COOK_AREA.contains(here)) {
                walkSafelyTo(ctx, COOK_STAND_TILE, "castle kitchen for Bucket");
                return true;
            }
            if (openKitchenDoor(ctx, COOK_STAND_TILE)) return true;
            SceneObject trapdoor = ctx.objects().query().named("Trapdoor")
                    .actions("Climb-down").within(COOK_AREA).results().nearest();
            if (trapdoor != null && trapdoor.isValid() && trapdoor.interact("Climb-down")) {
                log("Cook's Assistant: entering castle cellar for Bucket");
                nextActionAt = System.currentTimeMillis() + 1400L;
            } else logRecovery("Cook's Assistant: locating castle cellar trapdoor for Bucket");
            return true;
        }
        if (POT.equals(name) && !COOK_AREA.contains(here)) {
            walkSafelyTo(ctx, COOK_STAND_TILE, "castle kitchen for Pot");
            return true;
        }
        if (POT.equals(name) && openKitchenDoor(ctx, COOK_STAND_TILE)) return true;
        GroundItem item = ctx.groundItems().query().named(name).results().nearest();
        if (item != null && item.isValid() && item.tileDistanceTo(ctx) <= 12) {
            boolean taken = item.interact("Take") || ctx.menu().interact("Take", item, false);
            log("Cook's Assistant: taking ground " + name + "=" + taken);
            nextActionAt = System.currentTimeMillis() + 1000L;
        } else logRecovery("Cook's Assistant: waiting for " + name + " ground spawn");
        return true;
    }

    private boolean leaveCellar(APIContext ctx) {
        SceneObject exit = ctx.objects().query().named("Ladder")
                .actions("Climb-up").results().nearest();
        if (exit == null) exit = ctx.objects().query().actions("Climb-up").results().nearest();
        if (exit != null && exit.isValid() && exit.tileDistanceTo(ctx) <= 12) {
            boolean climbed = exit.interact("Climb-up");
            log("Cook's Assistant: leaving castle cellar=" + climbed);
            nextActionAt = System.currentTimeMillis() + 1200L;
        } else logRecovery("Cook's Assistant: locating cellar exit ladder");
        return true;
    }

    private boolean makeFlour(APIContext ctx) {
        if (!ctx.inventory().contains(POT)) return collectContainer(ctx, POT);
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return true;
        if (here.getY() > 9000) return leaveCellar(ctx);
        if (ctx.vars().getVarbit(VarbitID.MILL_FLOUR) > 0) {
            grainLoaded = false;
            if (!goToMillFloor(ctx, 0)) return true;
            return interactMill(ctx, ObjectID.MILLBASE_FLOUR, MILL_BIN,
                    new String[]{"Empty", "Collect", "Take-flour"}, "collect flour");
        }
        if (grainLoaded) {
            if (!goToMillFloor(ctx, 2)) return true;
            return interactMill(ctx, ObjectID.HOPPERLEVERS1, MILL_CONTROLS,
                    new String[]{"Operate"}, "operate hopper controls");
        }
        if (ctx.inventory().contains(GRAIN)) {
            if (!goToMillFloor(ctx, 2)) return true;
            boolean acted = interactMill(ctx, ObjectID.HOPPER1, MILL_HOPPER,
                    new String[]{"Fill"}, "fill hopper");
            if (acted) grainLoaded = true;
            return true;
        }
        if (here.getPlane() > 0 && MILL_AREA.contains(here.getX(), here.getY())) {
            goToMillFloor(ctx, 0);
            return true;
        }
        Tile wheatTile = new Tile(3161, 3292, 0);
        if (wheatTile.tileDistanceTo(ctx) > 8) {
            walkSafelyTo(ctx, wheatTile, "wheat field for flour");
            return true;
        }
        SceneObject wheat = ctx.objects().query().id(ObjectID.FAI_VARROCK_WHEAT_CORNER)
                .results().nearest();
        if (wheat == null || !wheat.isValid()) wheat = ctx.objects().query()
                .named("Wheat").actions("Pick").results().nearest();
        if (wheat != null && wheat.isValid() && wheat.tileDistanceTo(ctx) <= 10) {
            boolean picked = wheat.interact("Pick");
            log("Cook's Assistant: picking grain for flour=" + picked);
            nextActionAt = System.currentTimeMillis() + 1000L;
        } else logRecovery("Cook's Assistant: locating wheat beside Mill Lane Mill");
        return true;
    }

    private boolean goToMillFloor(APIContext ctx, int targetPlane) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return false;
        if (!MILL_AREA.contains(here.getX(), here.getY())) {
            walkSafelyTo(ctx, MILL_LADDER, "Mill Lane Mill");
            return false;
        }
        if (here.getPlane() == targetPlane) return true;
        boolean up = here.getPlane() < targetPlane;
        int ladderId = here.getPlane() == 0 ? ObjectID.QIP_COOK_LADDER
                : here.getPlane() == 1 ? ObjectID.QIP_COOK_LADDER_MIDDLE
                : ObjectID.QIP_COOK_LADDER_TOP;
        SceneObject ladder = ctx.objects().query().id(ladderId).results().nearest();
        if (ladder == null || !ladder.isValid()) ladder = ctx.objects().query()
                .named("Ladder").results().nearest();
        if (ladder != null && ladder.isValid() && ladder.tileDistanceTo(ctx) <= 9) {
            String action = up ? "Climb-up" : "Climb-down";
            boolean climbed = ladder.interact(action) || ladder.interact(up ? "Climb Up" : "Climb Down");
            log("Cook's Assistant: " + action + " mill ladder=" + climbed);
            nextActionAt = System.currentTimeMillis() + 1200L;
        } else logRecovery("Cook's Assistant: locating mill ladder on floor " + here.getPlane());
        return false;
    }

    private boolean interactMill(APIContext ctx, int id, Tile tile, String[] actions, String reason) {
        SceneObject object = ctx.objects().query().id(id).results().nearest();
        if (object == null || !object.isValid() || object.tileDistanceTo(ctx) > 8) {
            walkSafelyTo(ctx, tile, reason);
            return false;
        }
        for (String action : actions) {
            if (object.interact(action)) {
                log("Cook's Assistant: " + reason + " via " + action);
                millActionFailures = 0;
                nextActionAt = System.currentTimeMillis() + 1100L;
                return true;
            }
        }
        millActionFailures++;
        if (millActionFailures <= 3) logRecovery("Cook's Assistant: mill interaction failed: " + reason);
        nextActionAt = System.currentTimeMillis() + 1500L;
        return false;
    }

    private boolean collectEgg(APIContext ctx) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating() || ctx.localPlayer().isInCombat()) {
            Time.sleep(500, 800);
            return true;
        }

        if (!EGG_AREA.contains(ctx.localPlayer().getLocation())) {
            stats.setStatus("Cook's Assistant: walking to Egg");
            logTravel("Cook's Assistant: walking to chicken coop for Egg");
            walkSafelyTo(ctx, EGG_AREA.getRandomTile(), "chicken coop Egg area");
            Time.sleep(700, 1100);
            return true;
        }

        GroundItem item = findGroundIngredient(ctx, EGG, EGG_AREA, EGG_SEARCH_DISTANCE);

        if (item == null || !item.isValid()) {
            consecutiveEggMisses++;
            debugNearbyGroundItems(ctx, EGG, EGG_AREA);
            logRecovery("Cook's Assistant: no Egg found on ground yet (miss "
                    + consecutiveEggMisses + ")");
            if (consecutiveEggMisses >= EGG_REPOSITION_AFTER_MISSES) {
                stats.setStatus("Cook's Assistant: repositioning for Egg spawn");
                logTravel("Cook's Assistant: repositioning inside chicken coop for Egg spawn");
                walkSafelyTo(ctx, EGG_AREA.getRandomTile(), "chicken coop Egg spawn");
                consecutiveEggMisses = 0;
            }
            Time.sleep(700, 1100);
            return true;
        }

        if (item.tileDistanceTo(ctx) > 8) {
            stats.setStatus("Cook's Assistant: moving closer to Egg");
            logTravel("Cook's Assistant: moving closer to Egg at " + item.getLocation());
            walkSafelyTo(ctx, item.getLocation(), "Egg");
            Time.sleep(700, 1100);
            return true;
        }

        int before = ctx.inventory().getCount(true, EGG);
        stats.setStatus("Cook's Assistant: taking Egg");
        log("Cook's Assistant: taking Egg at " + item.getLocation());
        org.example.core.navigation.CameraMovement.focus(ctx, item, logger);
        if (item.interact("Take", EGG) || item.interact("Take") || ctx.menu().interact("Take", item, false)) {
            Time.sleep(900, 1500, () -> ctx.inventory().getCount(true, EGG) > before, 100);
            consecutiveEggMisses = 0;
        } else {
            logRecovery("Cook's Assistant: failed to interact Take on Egg at " + item.getLocation());
        }
        return true;
    }

    private GroundItem findGroundIngredient(APIContext ctx, String itemName, Area area, int maxDistance) {
        for (GroundItem item : ctx.groundItems()
                .query()
                .named(itemName)
                .within(area)
                .results()
                .nearestList()) {
            if (isValidGroundIngredient(ctx, item, itemName, area, maxDistance)) {
                return item;
            }
        }

        for (GroundItem item : ctx.groundItems()
                .query()
                .named(itemName)
                .results()
                .nearestList()) {
            if (isValidGroundIngredient(ctx, item, itemName, area, maxDistance)) {
                return item;
            }
        }

        return null;
    }

    private boolean isValidGroundIngredient(
            APIContext ctx,
            GroundItem item,
            String itemName,
            Area area,
            int maxDistance
    ) {
        if (item == null || !item.isValid() || !namesMatch(item.getName(), itemName)) {
            return false;
        }

        Tile itemTile = item.getLocation();
        return item.tileDistanceTo(ctx) <= maxDistance
                && (itemTile == null || area.contains(itemTile) || area.contains(ctx.localPlayer().getLocation()));
    }

    private void debugNearbyGroundItems(APIContext ctx, String itemName, Area area) {
        long now = System.currentTimeMillis();
        if (now < nextEggDebugAt) {
            return;
        }

        StringBuilder builder = new StringBuilder("Cook's Assistant ground item debug for ")
                .append(itemName)
                .append(":");
        int inspected = 0;
        for (GroundItem item : ctx.groundItems().query().results().nearestList()) {
            if (item == null || !item.isValid()) {
                continue;
            }
            Tile tile = item.getLocation();
            if (item.tileDistanceTo(ctx) > EGG_SEARCH_DISTANCE
                    && (tile == null || !area.contains(tile))) {
                continue;
            }
            builder.append(" [")
                    .append(item.getName())
                    .append(" at ")
                    .append(tile)
                    .append(", dist=")
                    .append(item.tileDistanceTo(ctx))
                    .append(']');
            inspected++;
            if (inspected >= 8) {
                break;
            }
        }
        if (inspected == 0) {
            builder.append(" none nearby");
        }
        logRecovery(builder.toString());
        nextEggDebugAt = now + 10_000L;
    }

    private boolean milkDairyCow(APIContext ctx) {
        if (!DAIRY_COW_AREA.contains(ctx.localPlayer().getLocation())) {
            stats.setStatus("Cook's Assistant: walking to dairy cow");
            logTravel("Cook's Assistant: walking to dairy cow area");
            walkSafelyTo(ctx, DAIRY_COW_AREA.getRandomTile(), "dairy cow area");
            Time.sleep(700, 1100);
            return true;
        }

        NPC cow = findDairyCow(ctx);
        if (cow == null || !cow.isValid()) {
            logRecovery("Cook's Assistant: no Dairy cow candidate found for milking in area");
            Time.sleep(700, 1100);
            return true;
        }

        if (cow.tileDistanceTo(ctx) > 8 || !cow.canReach(ctx, 1)) {
            stats.setStatus("Cook's Assistant: moving closer to dairy cow");
            logTravel("Cook's Assistant: moving closer to Dairy cow " + describeCow(ctx, cow));
            walkSafelyTo(ctx, cow.getLocation(), "Dairy cow");
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Cook's Assistant: milking dairy cow");
        log("Cook's Assistant: milking dairy cow " + describeCow(ctx, cow));
        org.example.core.navigation.CameraMovement.focus(ctx, cow, logger);
        if (clickMilkCow(ctx, cow)) {
            Time.sleep(1200, 2200, () -> ctx.inventory().contains(BUCKET_OF_MILK), 100);
        } else {
            logRecovery("Cook's Assistant: failed to interact Milk on Dairy cow " + describeCow(ctx, cow));
        }
        return true;
    }

    private NPC findDairyCow(APIContext ctx) {
        NPC cow = ctx.npcs()
                .query()
                .id(DAIRY_COW_ID)
                .within(DAIRY_COW_AREA)
                .reachable()
                .results()
                .nearest();
        if (isUsableCow(cow)) {
            return cow;
        }

        cow = ctx.npcs()
                .query()
                .id(DAIRY_COW_ID)
                .within(DAIRY_COW_AREA)
                .results()
                .nearest();
        if (isUsableCow(cow)) {
            return cow;
        }

        cow = ctx.npcs()
                .query()
                .actions("Milk")
                .within(DAIRY_COW_AREA)
                .reachable()
                .results()
                .nearest();
        if (isUsableCow(cow)) {
            return cow;
        }

        cow = ctx.npcs()
                .query()
                .actions("Milk")
                .within(DAIRY_COW_AREA)
                .results()
                .nearest();
        if (isUsableCow(cow)) {
            return cow;
        }

        cow = ctx.npcs()
                .query()
                .nameContains("Dairy")
                .within(DAIRY_COW_AREA)
                .reachable()
                .results()
                .nearest();
        if (isUsableCow(cow)) {
            return cow;
        }

        cow = ctx.npcs()
                .query()
                .nameContains("Dairy")
                .within(DAIRY_COW_AREA)
                .results()
                .nearest();
        return isUsableCow(cow) ? cow : null;
    }

    private boolean isUsableCow(NPC cow) {
        return cow != null && cow.isValid();
    }

    private boolean clickMilkCow(APIContext ctx, NPC cow) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }

        boolean clicked = cow.interact("Milk", "Dairy cow")
                || cow.interact("Milk")
                || cow.interactMatch("Milk")
                || ctx.menu().interact("Milk", cow, false)
                || ctx.menu().interact("Milk", true);
        Time.sleep(700, 1200, () -> ctx.inventory().contains(BUCKET_OF_MILK) || ctx.localPlayer().isMoving(), 100);
        return clicked;
    }

    private String describeCow(APIContext ctx, NPC cow) {
        if (cow == null) {
            return "(null)";
        }
        return "id=" + cow.getId()
                + ", name=" + cow.getName()
                + ", tile=" + cow.getLocation()
                + ", dist=" + cow.tileDistanceTo(ctx)
                + ", actions=" + cow.getActions();
    }

    private void startGrandExchangeBuy(APIContext ctx, String itemName) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            stats.setStatus("Cook's Assistant: walking to GE for " + itemName);
            logTravel("Cook's Assistant: walking to GE to buy " + itemName);
            walkSafelyTo(ctx, GE_AREA.getRandomTile(), "Grand Exchange");
            Time.sleep(700, 1100);
            return;
        }

        pendingBuyItem = itemName;
        pendingBuyPrice = GePricing.quickBuyPrice(ctx, itemName, buyPrice(itemName));
        handleGrandExchange(ctx);
    }

    private void handleGrandExchange(APIContext ctx) {
        if (pendingBuyItem == null) {
            closeGrandExchange(ctx);
            return;
        }

        if (ctx.inventory().contains(item -> isNamedItem(item, pendingBuyItem) && isNoted(item))) {
            log("Cook's Assistant: GE ingredient is noted; converting it at bank");
            clearPendingBuy(ctx);
            bankChecked = false;
            return;
        }

        if (hasUsable(ctx, pendingBuyItem)) {
            stats.setStatus("Cook's Assistant: bought " + pendingBuyItem);
            log("Cook's Assistant: GE purchase obtained: " + pendingBuyItem);
            clearPendingBuy(ctx);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
                stats.setStatus("Cook's Assistant: walking to GE for " + pendingBuyItem);
                logTravel("Cook's Assistant: walking to GE for " + pendingBuyItem);
                walkSafelyTo(ctx, GE_AREA.getRandomTile(), "Grand Exchange");
                Time.sleep(700, 1100);
                return;
            }

            stats.setStatus("Cook's Assistant: opening GE for " + pendingBuyItem);
            log("Cook's Assistant: opening GE for " + pendingBuyItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmHighPriceGeOffer(ctx)) return;
        IGrandExchangeAPI.GrandExchangeScreen screen = ctx.grandExchange().getCurrentScreen();
        if (screen == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER && offerPlacedAt > 0) {
            if (System.currentTimeMillis() - offerPlacedAt > 5_000L) ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 1200L;
            return;
        }

        GrandExchangeSlot slot = findBuySlot(ctx, pendingBuyItem);
        if (geCollectionAttempts >= 3 && slot != null && slot.canCollect()
                && System.currentTimeMillis() >= nextGeCollectAt) {
            boolean collected = ctx.grandExchange().collectToBank();
            logRecovery("Cook's Assistant: GE inventory collection stalled; collect-to-bank=" + collected);
            nextGeCollectAt = System.currentTimeMillis() + 8_000L;
            nextActionAt = System.currentTimeMillis() + 1000L;
            if (collected) {
                clearPendingBuy(ctx);
                bankChecked = false;
            }
            return;
        }
        if (System.currentTimeMillis() >= nextGeCollectAt
                && (slot != null && slot.canCollect()
                || screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER)) {
            boolean collected = ctx.grandExchange().collectToInventory();
            log("Cook's Assistant: collect-to-inventory " + pendingBuyItem + "=" + collected);
            geCollectionAttempts++;
            nextGeCollectAt = System.currentTimeMillis() + 2_500L;
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        if (slot != null && slot.canCollect()) {
            nextActionAt = nextGeCollectAt;
            return;
        }
        if (screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        if (slot != null && slot.inUse()) {
            stats.setStatus("Cook's Assistant: waiting for GE offer " + pendingBuyItem);
            nextActionAt = System.currentTimeMillis() + 1500L;
            return;
        }
        if (offerPlacedAt > 0) {
            stats.setStatus("Cook's Assistant: verifying GE offer before retry");
            nextActionAt = System.currentTimeMillis() + 2500L;
            return;
        }
        if (screen != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }

        stats.setStatus("Cook's Assistant: buying " + pendingBuyItem);
        log("Cook's Assistant: buying " + pendingBuyItem + " for " + pendingBuyPrice);
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, 1, pendingBuyPrice);
        nextActionAt = System.currentTimeMillis() + 1200L;
        if (!placed) {
            if (!confirmHighPriceGeOffer(ctx))
                logRecovery("Cook's Assistant: GE buy offer was not placed for " + pendingBuyItem);
            return;
        }
        offerPlacedAt = System.currentTimeMillis();
    }

    private void talkToCook(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }
        if (ctx.grandExchange().isOpen()) {
            closeGrandExchange(ctx);
            return;
        }
        NPC cook = ctx.npcs()
                .query()
                .named("Cook")
                .actions("Talk-to")
                .within(COOK_AREA)
                .reachable()
                .results()
                .nearest();
        if (cook == null) {
            cook = ctx.npcs()
                    .query()
                    .named("Cook")
                    .actions("Talk-to")
                    .within(COOK_AREA)
                    .results()
                    .nearest();
        }

        if (cook != null && cook.isValid() && cook.tileDistanceTo(ctx) <= 8) {
            if (!cook.canReach(ctx, 1)) {
                if (openKitchenDoor(ctx, cook.getLocation())) return;
                stats.setStatus("Cook's Assistant: approaching Cook through kitchen entrance");
                walkSafelyTo(ctx, COOK_STAND_TILE, "Cook kitchen entrance");
                nextActionAt = System.currentTimeMillis() + 1200L;
                return;
            }
            stats.setStatus("Cook's Assistant: talking to Cook");
            log("Cook's Assistant: talking to Cook"
                    + (hasAllFinalIngredients(ctx) ? " with ingredients" : " to start quest"));
            org.example.core.navigation.CameraMovement.focus(ctx, cook, logger);
            if (cook.interact("Talk-to")) {
                Time.sleep(1200, 2000,
                        () -> ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen(),
                        100);
            }
            nextActionAt = System.currentTimeMillis() + 1400L;
            return;
        }

        stats.setStatus("Cook's Assistant: walking to Cook");
        logTravel("Cook's Assistant: walking to Lumbridge Cook");
        if (openKitchenDoor(ctx, COOK_STAND_TILE)) return;
        walkSafelyTo(ctx, COOK_STAND_TILE, "Lumbridge Cook");
        Time.sleep(700, 1100);
    }

    private boolean openKitchenDoor(APIContext ctx, Tile destination) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || destination == null || destination.tileDistanceTo(ctx) > 12) return false;
        SceneObject door = ctx.walking().getBlockingObjectBetween(location, destination);
        if (!isOpenableDoor(door)) door = ctx.objects().query().named("Door")
                .actions("Open").within(COOK_AREA).results().nearest();
        if (!isOpenableDoor(door) || door.tileDistanceTo(ctx) > 8) return false;
        if (door.tileDistanceTo(ctx) > 3) {
            walkSafelyTo(ctx, door.getLocation(), "kitchen door");
            nextActionAt = System.currentTimeMillis() + 1000L;
            return true;
        }
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        boolean opened = door.interact("Open") || ctx.menu().interact("Open", door, false);
        log("Cook's Assistant: opening kitchen door at " + door.getLocation() + "=" + opened);
        nextActionAt = System.currentTimeMillis() + 1200L;
        return true;
    }

    private static boolean isOpenableDoor(SceneObject object) {
        if (object == null || !object.isValid() || !object.hasAction("Open")) return false;
        String name = object.getName();
        return name != null && (name.toLowerCase(Locale.ROOT).contains("door")
                || name.toLowerCase(Locale.ROOT).contains("gate"));
    }

    private void walkToBankForIngredients(APIContext ctx) {
        if (tryOpenNearbyBank(ctx)) {
            bankWalkFailures = 0;
            return;
        }

        stats.setStatus("Cook's Assistant: walking to bank for ingredients");
        logTravel("Cook's Assistant: walking to bank for ingredients");

        if (Navigation.isInLumbridgeRegion(ctx)) {
            bankChecked = true;
            stats.setStatus("Cook's Assistant: skipping Lumbridge bank walk; collecting ingredients directly");
            logRecovery("Cook's Assistant: not pathing locally to Lumbridge bank because it requires floor/stair handling");
            Time.sleep(700, 1100);
            return;
        }

        try {
            WalkState state = Navigation.walkToBank(ctx);
            if (state == WalkState.SUCCESS) {
                bankWalkFailures = 0;
            } else {
                bankWalkFailures++;
                logRecovery("Cook's Assistant: web bank walk returned " + state + " (" + bankWalkFailures + "/3)");
            }
        } catch (RuntimeException e) {
            bankWalkFailures++;
            logRecovery("Cook's Assistant: web bank walk failed: " + e.getClass().getSimpleName()
                    + " (" + bankWalkFailures + "/3)");
        }

        if (bankWalkFailures >= 3) {
            bankChecked = true;
            stats.setStatus("Cook's Assistant: bank route unavailable; collecting ingredients directly");
            logRecovery("Cook's Assistant: skipping bank ingredient check after repeated web bank path failures");
        }
        Time.sleep(700, 1100);
    }

    private boolean tryOpenNearbyBank(APIContext ctx) {
        if (Navigation.isBankReachable(ctx)) {
            stats.setStatus("Cook's Assistant: opening bank for ingredients");
            log("Cook's Assistant: opening bank for ingredients");
            Navigation.openBank(ctx);
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return ctx.bank().isOpen();
        }

        if (Navigation.shouldAvoidNearestBank(ctx)) {
            return false;
        }

        SceneObject bankObject = ctx.objects()
                .query()
                .actions("Bank")
                .reachable()
                .results()
                .nearest();
        if (bankObject == null || !bankObject.isValid() || bankObject.tileDistanceTo(ctx) > 8) {
            return false;
        }

        stats.setStatus("Cook's Assistant: opening nearby bank");
        log("Cook's Assistant: opening nearby bank object: " + bankObject.getName());
        if (bankObject.interact("Bank")) {
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
        }
        return ctx.bank().isOpen();
    }

    private boolean walkSafelyTo(APIContext ctx, Locatable destination, String label) {
        if (destination == null) {
            return false;
        }

        int distance = destination.tileDistanceTo(ctx);
        if (distance <= 35) {
            Tile here = ctx.localPlayer().getLocation();
            if (here != null) {
                SceneObject blocker = ctx.walking().getBlockingObjectBetween(here, destination.getLocation());
                if (isOpenableDoor(blocker) && blocker.tileDistanceTo(ctx) <= 8) {
                    if (blocker.tileDistanceTo(ctx) > 3)
                        return walkLocallyTo(ctx, blocker.getLocation(), label + " route door");
                    boolean opened = blocker.interact("Open")
                            || ctx.menu().interact("Open", blocker, false);
                    log("Cook's Assistant: opening route door/gate for " + label + "=" + opened);
                    nextActionAt = System.currentTimeMillis() + 1200L;
                    return true;
                }
            }
            if (walkLocallyTo(ctx, destination, label)) {
                return true;
            }
        }

        try {
            WalkState state = Navigation.walkToNoTeleports(ctx, destination);
            if (state == WalkState.SUCCESS) {
                return true;
            }
            logRecovery("Cook's Assistant: web walk to " + label + " returned " + state);
        } catch (RuntimeException e) {
            logRecovery("Cook's Assistant: web walk to " + label + " failed: " + e.getClass().getSimpleName());
        }
        return false;
    }

    private boolean walkLocallyTo(APIContext ctx, Locatable destination, String label) {
        try {
            logTravel("Cook's Assistant: walking locally to " + label);
            boolean walked = Navigation.walkLocally(ctx, destination)
                    || ctx.walking().walkTo(destination);
            if (walked) {
                Time.sleep(700, 1200, () -> ctx.localPlayer().isMoving(), 100);
            }
            return walked;
        } catch (RuntimeException e) {
            logRecovery("Cook's Assistant: local walk to " + label + " failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean hasAllFinalIngredients(APIContext ctx) {
        return hasUsable(ctx, BUCKET_OF_MILK)
                && hasUsable(ctx, EGG)
                && hasUsable(ctx, POT_OF_FLOUR);
    }

    private String firstMissingIngredient(APIContext ctx) {
        for (String item : FINAL_INGREDIENTS) {
            if (!hasUsable(ctx, item)) {
                return item;
            }
        }
        return "none";
    }

    private String firstMissingBuyableIngredient(APIContext ctx) {
        if (!hasUsable(ctx, POT_OF_FLOUR)) {
            return POT_OF_FLOUR;
        }
        if (!hasUsable(ctx, BUCKET_OF_MILK)) {
            return BUCKET_OF_MILK;
        }
        if (!hasUsable(ctx, EGG)) {
            return EGG;
        }
        return null;
    }

    private boolean needsGeBuy(APIContext ctx) {
        return firstMissingBuyableIngredient(ctx) != null;
    }

    private int estimatedMissingBuyCost(APIContext ctx) {
        int cost = 0;
        if (!hasUsable(ctx, POT_OF_FLOUR)) {
            cost += buyPrice(POT_OF_FLOUR);
        }
        if (!hasUsable(ctx, BUCKET_OF_MILK)) {
            cost += buyPrice(BUCKET_OF_MILK);
        }
        if (!hasUsable(ctx, EGG)) {
            cost += buyPrice(EGG);
        }
        return cost;
    }

    private int buyPrice(String itemName) {
        return switch (itemName) {
            case EGG -> 100;
            case BUCKET_OF_MILK -> 250;
            case POT_OF_FLOUR -> 250;
            default -> 150;
        };
    }

    private static boolean isNamedItem(Item item, String name) {
        return item != null && name.equalsIgnoreCase(item.getName());
    }

    private static boolean isNoted(Item item) {
        return item != null && (item.isNoted()
                || (item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId()));
    }

    private static boolean hasUsable(APIContext ctx, String name) {
        return ctx.inventory().contains(item -> isNamedItem(item, name) && !isNoted(item));
    }

    private int materialSignature(APIContext ctx) {
        int signature = 0;
        for (String item : FINAL_INGREDIENTS) signature = signature * 2 + (hasUsable(ctx, item) ? 1 : 0);
        return signature;
    }

    private GrandExchangeSlot findBuySlot(APIContext ctx, String itemName) {
        List<GrandExchangeSlot> slots = ctx.grandExchange().getSlots();
        if (slots == null) return null;
        for (GrandExchangeSlot slot : slots) {
            if (slot == null || slot.getOffer() == null) continue;
            GrandExchangeOffer.OfferState state = slot.getState();
            if (state != GrandExchangeOffer.OfferState.BUYING
                    && state != GrandExchangeOffer.OfferState.BOUGHT
                    && state != GrandExchangeOffer.OfferState.CANCELLED_BUY) continue;
            if (itemName.equalsIgnoreCase(slot.getOffer().getItemName())) return slot;
        }
        return null;
    }

    private void clearPendingBuy(APIContext ctx) {
        pendingBuyItem = null;
        pendingBuyPrice = 0;
        offerPlacedAt = 0L;
        geCollectionAttempts = 0;
        closeGrandExchange(ctx);
    }

    private boolean shouldKeepInventoryItem(String name) {
        return name != null
                && (name.equals(EGG)
                || name.equals(BUCKET)
                || name.equals(BUCKET_OF_MILK)
                || name.equals(POT_OF_FLOUR)
                || name.equals(COINS));
    }

    private boolean confirmHighPriceGeOffer(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null || !hasVisibleWidgetText(ctx, "much higher than the guide price")) {
            return false;
        }

        stats.setStatus("Cook's Assistant: confirming GE price warning");
        log("Cook's Assistant: confirming GE high-price warning");
        if (clickWidgetCenter(ctx, yes)
                || ctx.mouse().click(yes, false)
                || yes.click(false)
                || yes.click()
                || ctx.menu().interact("Yes", yes, true)
                || ctx.menu().interact("Yes", true)
                || ctx.menu().interact("Yes")) {
            Time.sleep(1200, 1800);
            nextActionAt = System.currentTimeMillis() + 1200L;
            return true;
        }

        Time.sleep(600, 900);
        return true;
    }

    private boolean hasVisibleWidgetText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) return false;
            String label = candidate.getText();
            String raw = candidate.getRawText();
            return label != null && label.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))
                    || raw != null && raw.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
        })) return true;
        return false;
    }

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            if (text.equalsIgnoreCase(widgetText == null ? "" : widgetText.trim())
                    || text.equalsIgnoreCase(rawText == null ? "" : rawText.trim())) {
                return true;
            }

            List<String> actions = candidate.getActions();
            if (actions == null) {
                return false;
            }
            for (String action : actions) {
                if (text.equalsIgnoreCase(action == null ? "" : action.trim())) {
                    return true;
                }
            }
            return false;
        })) {
            return widget;
        }
        return null;
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

    private void closeCompletionScreen(APIContext ctx) {
        if (!hasVisibleWidgetText(ctx, "quest complete")
                && !hasVisibleWidgetText(ctx, "you have completed")) return;
        log("Cook's Assistant: closing quest completion screen");
        if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
        nextActionAt = System.currentTimeMillis() + 800L;
    }

    private void closeGrandExchange(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
        }
    }

    private boolean isNegativeOption(String option) {
        String lower = option.toLowerCase(Locale.ROOT);
        return lower.contains("no thanks")
                || lower.equals("no")
                || lower.contains("not right now")
                || lower.contains("don't")
                || lower.contains("do not");
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void logTravel(String message) {
        long now = System.currentTimeMillis();
        if (now >= nextTravelLogAt) {
            log(message);
            nextTravelLogAt = now + 5_000L;
        }
    }

    private void logRecovery(String message) {
        long now = System.currentTimeMillis();
        if (now >= nextRecoveryLogAt) {
            stats.recordRecoverableError(message);
            log(message);
            nextRecoveryLogAt = now + 5_000L;
        }
    }

    private void log(String message) {
        logger.accept(message);
    }
}
