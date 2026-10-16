package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.details.Locatable;
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

public class DoricQuestModule implements ManagedF2PModule {
    private static final int DORIC_NPC_ID = 3893;
    private static final int DORIC_DOOR_CLOSED_ID = 1535;
    private static final int DORIC_DOOR_OPEN_ID = 1536;
    private static final Area DORIC_AREA = new Area(2946, 3448, 2957, 3458);
    private static final Area DORIC_INSIDE_AREA = new Area(2950, 3449, 2954, 3453);
    private static final Area DORIC_DOOR_AREA = new Area(2948, 3449, 2951, 3451);
    private static final Tile DORIC_NPC_TILE = new Tile(2952, 3452, 0);
    private static final Tile DORIC_DOOR_TILE = new Tile(2949, 3450, 0);
    private static final Tile DORIC_OUTSIDE_DOOR_TILE = new Tile(2948, 3450, 0);
    private static final Tile DORIC_INSIDE_TILE = new Tile(2952, 3451, 0);
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490);

    private static final RequiredItem[] REQUIRED_ITEMS = {
            new RequiredItem("Clay", 6, 100),
            new RequiredItem("Copper ore", 4, 100),
            new RequiredItem("Iron ore", 2, 200)
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private String pendingBuyItem;
    private int pendingBuyQuantity;
    private int pendingBuyPrice;
    private long nextTravelLogAt;
    private long nextRecoveryLogAt;
    private long nextGeCollectAt;
    private long offerPlacedAt;
    private int geCollectionAttempts;
    private long nextActionAt;
    private long lastProgressAt;
    private int lastMaterialCount = -1;
    private boolean bankChecked;
    private String dialogueSnapshot = "";
    private int unchangedDialogueActions;
    private boolean dialogueRecoveryIssued;

    public DoricQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "quests.dorics_quest";
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
            stats.setStatus("Doric's Quest complete");
            closeCompletionScreen(ctx);
            return;
        }

        int materialCount = materialCount(ctx);
        if (materialCount != lastMaterialCount) {
            lastMaterialCount = materialCount;
            lastProgressAt = System.currentTimeMillis();
        } else if (lastProgressAt > 0 && System.currentTimeMillis() - lastProgressAt > 4 * 60_000L) {
            logRecovery("Doric's Quest: no material progress for 4 minutes; rechecking bank and GE state");
            bankChecked = false;
            lastProgressAt = System.currentTimeMillis();
        }

        if (handleDialogue(ctx)) {
            return;
        }

        if (ctx.grandExchange().isOpen() || pendingBuyItem != null) {
            handleGrandExchange(ctx);
            return;
        }

        if (hasAllRequiredItems(ctx)) {
            talkToDoric(ctx, ctx.quests().isStarted(IQuestAPI.Quest.DORICS_QUEST)
                    ? "deliver ores" : "start and complete quest");
            return;
        }

        if (!bankChecked || ctx.bank().isOpen()) {
            handleBank(ctx);
            return;
        }

        RequiredItem missing = firstMissingItem(ctx);
        if (missing != null) {
            startGrandExchangeBuy(ctx, missing);
            return;
        }

        bankChecked = false;
    }

    private boolean hasActionableDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }
        return ctx.dialogues().canContinue() || !ctx.dialogues().getOptions().isEmpty();
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(IQuestAPI.Quest.DORICS_QUEST);
    }

    @Override
    public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0
                : ctx.quests().isStarted(IQuestAPI.Quest.DORICS_QUEST) ? 92 : 62;
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
            log("Doric's Quest: continuing dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(500, 850);
            return true;
        }

        if (selectDialogueOption(ctx, "yes", true, "confirming quest start")) {
            return true;
        }
        if (selectDialogueOption(ctx, "yes, i will get you the materials", false,
                "accepting Doric's material request")) return true;

        String[] preferredOptions = {
                "use your anvils",
                "any quests",
                "quest",
                "sure",
                "ok",
                "okay",
                "i have",
                "here you go"
        };

        for (String option : preferredOptions) {
            if (ctx.dialogues().hasOptionContaining(option)) {
                log("Doric's Quest: selecting dialogue option containing '" + option + "'");
                ctx.dialogues().selectOption(text -> text != null
                        && text.toLowerCase(Locale.ROOT).contains(option));
                Time.sleep(600, 950);
                return true;
            }
        }

        if (!ctx.dialogues().getOptions().isEmpty()) {
            log("Doric's Quest: selecting first non-negative dialogue option");
            ctx.dialogues().selectOption(text -> text != null && !isNegativeOption(text));
            Time.sleep(600, 950);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now >= nextRecoveryLogAt) {
            log("Doric's Quest: ignoring stale dialogue flag; no option/continue resolved");
            nextRecoveryLogAt = now + 5_000L;
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
            log("Doric's Quest: dialogue unchanged after four actions; closing it before retrying stage");
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

            stats.setStatus("Doric's Quest: " + reason);
            log("Doric's Quest: " + reason + " -> " + expected);
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
            stats.setStatus("Doric's Quest: " + reason);
            log("Doric's Quest: " + reason + " containing '" + expected + "'");
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
                stats.setStatus("Doric's Quest: walking to bank for materials");
                logTravel("Doric's Quest: walking to GE bank for materials");
                Navigation.walkToNoTeleports(ctx, GE_AREA.getRandomTile());
                Time.sleep(700, 1100);
                return;
            }

            stats.setStatus("Doric's Quest: opening bank for materials");
            log("Doric's Quest: opening bank for materials");
            Navigation.openBank(ctx);
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (hasNonQuestInventory(ctx)) {
            stats.setStatus("Doric's Quest: depositing non-quest inventory");
            log("Doric's Quest: depositing non-quest inventory");
            ctx.bank().depositAllExcept(item -> item != null && shouldKeepInventoryItem(item.getName()));
            Time.sleep(700, 1100);
            return;
        }

        for (RequiredItem item : REQUIRED_ITEMS) {
            if (ctx.inventory().contains(carried -> isRequiredItem(carried, item.name) && isNoted(carried))) {
                boolean deposited = ctx.bank().depositAll(carried -> isRequiredItem(carried, item.name)
                        && isNoted(carried));
                log("Doric's Quest: depositing noted " + item.name + " for item withdrawal=" + deposited);
                nextActionAt = System.currentTimeMillis() + 800L;
                return;
            }
        }

        for (RequiredItem item : REQUIRED_ITEMS) {
            int inventoryCount = usableCount(ctx, item.name);
            int needed = item.quantity - inventoryCount;
            if (needed <= 0) {
                continue;
            }

            int bankCount = ctx.bank().getCount(item.name);
            if (bankCount <= 0) {
                continue;
            }

            int amount = Math.min(needed, bankCount);
            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                boolean selected = ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                log("Doric's Quest: selected bank item withdrawal mode=" + selected);
                nextActionAt = System.currentTimeMillis() + 800L;
                return;
            }
            stats.setStatus("Doric's Quest: withdrawing " + amount + "x " + item.name);
            log("Doric's Quest: withdrawing " + amount + "x " + item.name);
            ctx.bank().withdraw(amount, item.name);
            Time.sleep(700, 1100, () -> usableCount(ctx, item.name) >= inventoryCount + amount, 100);
            return;
        }

        bankChecked = true;
        ctx.bank().close();
        Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
    }

    private void startGrandExchangeBuy(APIContext ctx, RequiredItem item) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            stats.setStatus("Doric's Quest: walking to GE for " + item.name);
            logTravel("Doric's Quest: walking to GE for " + item.name);
            Navigation.walkToNoTeleports(ctx, GE_AREA.getRandomTile());
            Time.sleep(700, 1100);
            return;
        }

        pendingBuyItem = item.name;
        pendingBuyQuantity = Math.max(1, item.quantity - usableCount(ctx, item.name));
        pendingBuyPrice = GePricing.quickBuyPrice(ctx, item.name, item.buyPrice);
        handleGrandExchange(ctx);
    }

    private void handleGrandExchange(APIContext ctx) {
        if (pendingBuyItem == null) {
            closeGrandExchange(ctx);
            return;
        }

        if (ctx.inventory().contains(item -> isRequiredItem(item, pendingBuyItem) && isNoted(item))) {
            log("Doric's Quest: GE material is noted; converting it at bank");
            clearPendingBuy(ctx);
            bankChecked = false;
            return;
        }

        if (usableCount(ctx, pendingBuyItem) >= requiredQuantity(pendingBuyItem)) {
            stats.setStatus("Doric's Quest: bought " + pendingBuyItem);
            log("Doric's Quest: GE purchase obtained: " + pendingBuyItem);
            clearPendingBuy(ctx);
            bankChecked = false;
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
                stats.setStatus("Doric's Quest: walking to GE for " + pendingBuyItem);
                logTravel("Doric's Quest: walking to GE for " + pendingBuyItem);
                Navigation.walkToNoTeleports(ctx, GE_AREA.getRandomTile());
                Time.sleep(700, 1100);
                return;
            }

            stats.setStatus("Doric's Quest: opening GE for " + pendingBuyItem);
            log("Doric's Quest: opening GE for " + pendingBuyItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmHighPriceGeOffer(ctx)) {
            return;
        }

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
            log("Doric's Quest: GE inventory collection stalled; collect-to-bank=" + collected);
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
            log("Doric's Quest: collect-to-inventory " + pendingBuyItem + "=" + collected);
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
            stats.setStatus("Doric's Quest: waiting for GE offer " + pendingBuyItem);
            nextActionAt = System.currentTimeMillis() + 1500L;
            return;
        }
        if (offerPlacedAt > 0) {
            stats.setStatus("Doric's Quest: verifying GE offer before retry");
            nextActionAt = System.currentTimeMillis() + 2500L;
            return;
        }
        if (screen != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }

        stats.setStatus("Doric's Quest: buying " + pendingBuyQuantity + "x " + pendingBuyItem);
        log("Doric's Quest: buying " + pendingBuyQuantity + "x " + pendingBuyItem + " for " + pendingBuyPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, pendingBuyQuantity, pendingBuyPrice);
        nextActionAt = System.currentTimeMillis() + 1200L;
        if (!placed) {
            if (confirmHighPriceGeOffer(ctx)) {
                return;
            }
            log("Doric's Quest: GE buy offer was not placed for " + pendingBuyItem);
            return;
        }
        offerPlacedAt = System.currentTimeMillis();
    }

    private void talkToDoric(APIContext ctx, String reason) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }
        if (ctx.grandExchange().isOpen()) {
            closeGrandExchange(ctx);
            return;
        }
        if (ctx.widgets().isInterfaceOpen() && !ctx.dialogues().isDialogueOpen()) {
            ctx.widgets().closeInterface();
            Time.sleep(400, 700);
            return;
        }

        clearInteractionState(ctx);

        Tile location = ctx.localPlayer().getLocation();
        if (location == null || !isInsideDoricHouse(location)) {
            approachDoricHouse(ctx);
            return;
        }

        NPC doric = findDoric(ctx);
        if (doric != null && doric.isValid() && doric.tileDistanceTo(ctx) <= 10) {
            stats.setStatus("Doric's Quest: talking to Doric to " + reason);
            log("Doric's Quest: talking to Doric to " + reason + ": id=" + doric.getId()
                    + ", tile=" + doric.getLocation());
            org.example.core.navigation.CameraMovement.focus(ctx, doric, logger);
            if (doric.interact("Talk-to", "Doric")
                    || doric.interact("Talk-to")
                    || doric.interactMatch("Talk-to")
                    || doric.click(false)
                    || ctx.mouse().click(doric, false)
                    || ctx.menu().interact("Talk-to", doric, true)
                    || ctx.menu().interact("Talk-to", doric, false)) {
                Time.sleep(1200, 2000,
                        () -> ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen(),
                        100);
                nextActionAt = System.currentTimeMillis() + 1400L;
                return;
            }

            logRecovery("Doric's Quest: failed to click Doric; repositioning near NPC tile");
            walkLocallyTo(ctx, DORIC_INSIDE_TILE, "Doric talk tile");
            Time.sleep(700, 1100);
            nextActionAt = System.currentTimeMillis() + 1100L;
            return;
        }

        approachDoricHouse(ctx);
    }

    private NPC findDoric(APIContext ctx) {
        NPC doric = ctx.npcs()
                .query()
                .id(DORIC_NPC_ID)
                .results()
                .nearest();
        if (doric != null && doric.isValid()) {
            return doric;
        }

        doric = ctx.npcs()
                .query()
                .named("Doric")
                .results()
                .nearest();
        if (doric != null && doric.isValid()) {
            return doric;
        }

        doric = ctx.npcs()
                .query()
                .id(DORIC_NPC_ID)
                .actions("Talk-to")
                .reachable()
                .results()
                .nearest();
        if (doric != null && doric.isValid()) {
            return doric;
        }

        return ctx.npcs()
                .query()
                .named("Doric")
                .actions("Talk-to")
                .results()
                .nearest();
    }

    private void approachDoricHouse(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return;
        }

        if (!isInsideDoricHouse(location)) {
            if (DORIC_OUTSIDE_DOOR_TILE.tileDistanceTo(ctx) > 1) {
                stats.setStatus("Doric's Quest: walking to Doric outside door tile");
                logTravel("Doric's Quest: walking to Doric outside door tile 2948,3450");
                walkSafelyTo(ctx, DORIC_OUTSIDE_DOOR_TILE, "Doric outside door tile");
                Time.sleep(700, 1100);
                return;
            }

            SceneObject closedDoor = findDoricClosedDoor(ctx);
            if (closedDoor != null && closedDoor.isValid()) {
                logTravel("Doric's Quest: closed Doric door found at tile 2949,3450; opening it");
                if (tryOpenDoricDoor(ctx, closedDoor, "Doric door")) {
                    return;
                }
                Time.sleep(700, 1100);
                return;
            }

            SceneObject blocker = ctx.walking().getBlockingObjectBetween(location, DORIC_INSIDE_TILE);
            if (tryOpenDoricDoor(ctx, blocker, "blocking Doric door")) {
                return;
            }

            stats.setStatus("Doric's Quest: walking inside Doric house");
            logTravel("Doric's Quest: walking inside Doric house to tile 2952,3451");
            walkLocallyTo(ctx, DORIC_INSIDE_TILE, "Doric inside tile");
            Time.sleep(700, 1100);
            return;
        }

        stats.setStatus("Doric's Quest: positioning near Doric");
        logTravel("Doric's Quest: positioning near Doric inside house; NPC tile is " + DORIC_NPC_TILE);
        walkLocallyTo(ctx, DORIC_INSIDE_TILE, "Doric inside tile");
        Time.sleep(700, 1100);
    }

    private static boolean isInsideDoricHouse(Tile location) {
        return location != null && DORIC_INSIDE_AREA.contains(location);
    }

    private SceneObject findDoricClosedDoor(APIContext ctx) {
        for (SceneObject object : ctx.objects().getAt(DORIC_DOOR_TILE)) {
            if (object != null
                    && object.isValid()
                    && object.getId() == DORIC_DOOR_CLOSED_ID) {
                return object;
            }
        }

        SceneObject door = ctx.objects()
                .query()
                .id(DORIC_DOOR_CLOSED_ID)
                .within(DORIC_DOOR_AREA)
                .results()
                .nearest();
        if (door != null && door.isValid()) {
            return door;
        }

        return ctx.objects()
                .query()
                .named("Door")
                .actions("Open")
                .within(DORIC_DOOR_AREA)
                .results()
                .nearest();
    }

    private boolean tryOpenDoricDoor(APIContext ctx, SceneObject object, String label) {
        if (object == null || !object.isValid()) {
            return false;
        }
        if (object.getId() != DORIC_DOOR_CLOSED_ID
                && object.getId() != DORIC_DOOR_OPEN_ID
                && !object.hasAction("Open", "Enter", "Walk-through")) {
            return false;
        }
        if (object.getId() == DORIC_DOOR_OPEN_ID && !object.hasAction("Open")) {
            return false;
        }

        stats.setStatus("Doric's Quest: opening Doric door");
        org.example.core.navigation.AccessRecovery.interaction(ctx, object, "Open");
        log("Doric's Quest: opening " + label + ": id=" + object.getId());
        boolean interacted = object.interact("Open")
                || object.interact("Enter")
                || object.interact("Walk-through")
                || ctx.menu().interact("Open", object, false)
                || ctx.menu().interact("Enter", object, false);
        if (interacted) {
            Time.sleep(900, 1600, () -> DORIC_INSIDE_AREA.contains(ctx.localPlayer().getLocation())
                    || findDoricClosedDoor(ctx) == null, 100);
        }
        return interacted;
    }

    private boolean walkSafelyTo(APIContext ctx, Locatable destination, String label) {
        if (destination == null) {
            return false;
        }

        int distance = destination.tileDistanceTo(ctx);
        if (distance <= 35 && walkLocallyTo(ctx, destination, label)) {
            return true;
        }

        try {
            WalkState state = Navigation.walkToNoTeleports(ctx, destination);
            if (state == WalkState.SUCCESS) {
                return true;
            }
            logRecovery("Doric's Quest: web walk to " + label + " returned " + state);
        } catch (RuntimeException e) {
            logRecovery("Doric's Quest: web walk to " + label + " failed: " + e.getClass().getSimpleName());
        }
        return false;
    }

    private boolean walkLocallyTo(APIContext ctx, Locatable destination, String label) {
        try {
            logTravel("Doric's Quest: walking locally to " + label);
            boolean walked = Navigation.walkLocally(ctx, destination)
                    || ctx.walking().walkTo(destination);
            if (walked) {
                Time.sleep(700, 1200, () -> ctx.localPlayer().isMoving(), 100);
            }
            return walked;
        } catch (RuntimeException e) {
            logRecovery("Doric's Quest: local walk to " + label + " failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void clearInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
            if (ctx.inventory().isItemSelected()) {
                ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
                Time.sleep(150, 300);
            }
        }
    }

    private boolean hasAllRequiredItems(APIContext ctx) {
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (usableCount(ctx, item.name) < item.quantity) {
                return false;
            }
        }
        return true;
    }

    private RequiredItem firstMissingItem(APIContext ctx) {
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (usableCount(ctx, item.name) < item.quantity) {
                return item;
            }
        }
        return null;
    }

    private int requiredQuantity(String itemName) {
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (item.name.equals(itemName)) {
                return item.quantity;
            }
        }
        return 1;
    }

    private boolean hasNonQuestInventory(APIContext ctx) {
        return ctx.inventory().getCount() > questInventoryCount(ctx);
    }

    private int questInventoryCount(APIContext ctx) {
        int count = ctx.inventory().getCount(true, "Coins") > 0 ? 1 : 0;
        for (RequiredItem item : REQUIRED_ITEMS) {
            count += ctx.inventory().getCount(item.name);
        }
        return count;
    }

    private static boolean isRequiredItem(Item item, String name) {
        return item != null && name.equalsIgnoreCase(item.getName());
    }

    private static boolean isNoted(Item item) {
        return item != null && (item.isNoted()
                || (item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId()));
    }

    private static int usableCount(APIContext ctx, String name) {
        return ctx.inventory().getCount(true, item -> isRequiredItem(item, name) && !isNoted(item));
    }

    private int materialCount(APIContext ctx) {
        int count = 0;
        for (RequiredItem item : REQUIRED_ITEMS) count += Math.min(item.quantity, usableCount(ctx, item.name));
        return count;
    }

    private boolean shouldKeepInventoryItem(String name) {
        if (name == null || "Coins".equals(name)) {
            return name != null;
        }
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (item.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean confirmHighPriceGeOffer(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null || !hasVisibleWidgetText(ctx, "much higher than the guide price")) {
            return false;
        }

        stats.setStatus("Doric's Quest: confirming GE price warning");
        log("Doric's Quest: confirming GE high-price warning");
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

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            if (containsIgnoreCase(widgetText, text) || containsIgnoreCase(rawText, text)) {
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

    private boolean hasVisibleWidgetText(APIContext ctx, String text) {
        return findVisibleWidgetContaining(ctx, text) != null;
    }

    private WidgetChild findVisibleWidgetContaining(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }
            return containsIgnoreCase(candidate.getText(), text)
                    || containsIgnoreCase(candidate.getRawText(), text);
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

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
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
        pendingBuyQuantity = 0;
        pendingBuyPrice = 0;
        offerPlacedAt = 0L;
        geCollectionAttempts = 0;
        closeGrandExchange(ctx);
    }

    private void closeCompletionScreen(APIContext ctx) {
        if (!hasVisibleWidgetText(ctx, "quest complete")
                && !hasVisibleWidgetText(ctx, "you have completed")) return;
        log("Doric's Quest: closing completion widget");
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
            log(message);
            nextRecoveryLogAt = now + 5_000L;
        }
    }

    private void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }

    private int requiredBuyPrice(String itemName) {
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (item.name.equalsIgnoreCase(itemName)) {
                return item.buyPrice;
            }
        }
        return 0;
    }

    private record RequiredItem(String name, int quantity, int buyPrice) {
    }
}
