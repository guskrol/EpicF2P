package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
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
    private static final Area DORIC_AREA = new Area(2946, 3448, 2957, 3458, 0);
    private static final Area DORIC_INSIDE_AREA = new Area(2950, 3449, 2954, 3453, 0);
    private static final Area DORIC_DOOR_AREA = new Area(2948, 3449, 2951, 3451, 0);
    private static final Tile DORIC_NPC_TILE = new Tile(2952, 3452, 0);
    private static final Tile DORIC_DOOR_TILE = new Tile(2949, 3450, 0);
    private static final Tile DORIC_OUTSIDE_DOOR_TILE = new Tile(2948, 3450, 0);
    private static final Tile DORIC_INSIDE_TILE = new Tile(2952, 3451, 0);
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490, 0);

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
    private boolean bankChecked;
    private boolean doricStartAttemptedThisRun;

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
        stats.setTrainingSkill("Questing");

        if (isComplete(ctx)) {
            stats.setStatus("Doric's Quest complete");
            return;
        }

        if (shouldForceInitialDoricTalk(ctx)) {
            if (hasActionableDialogue(ctx) && handleDialogue(ctx)) {
                return;
            }

            talkToDoric(ctx, "start quest");
            return;
        }

        if (handleDialogue(ctx)) {
            return;
        }

        if (ctx.grandExchange().isOpen() || pendingBuyItem != null) {
            handleGrandExchange(ctx);
            return;
        }

        if (!hasAllRequiredItems(ctx) && shouldTalkToDoricBeforeSupplies(ctx)) {
            talkToDoric(ctx, "start quest");
            return;
        }

        if (hasAllRequiredItems(ctx)) {
            talkToDoric(ctx, "deliver ores");
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

    private boolean shouldTalkToDoricBeforeSupplies(APIContext ctx) {
        if (!doricStartAttemptedThisRun) {
            return true;
        }

        return !ctx.quests().isStarted(IQuestAPI.Quest.DORICS_QUEST);
    }

    private boolean shouldForceInitialDoricTalk(APIContext ctx) {
        return !doricStartAttemptedThisRun
                && !ctx.quests().isStarted(IQuestAPI.Quest.DORICS_QUEST);
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
        return isComplete(ctx) ? 0 : 500;
    }

    private boolean handleDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }

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
            if (!ctx.bank().isReachable()) {
                stats.setStatus("Doric's Quest: walking to bank for materials");
                logTravel("Doric's Quest: walking to GE bank for materials");
                Navigation.walkToNoTeleports(ctx, GE_AREA.getRandomTile());
                Time.sleep(700, 1100);
                return;
            }

            stats.setStatus("Doric's Quest: opening bank for materials");
            log("Doric's Quest: opening bank for materials");
            ctx.bank().open();
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
            int inventoryCount = ctx.inventory().getCount(item.name);
            int needed = item.quantity - inventoryCount;
            if (needed <= 0) {
                continue;
            }

            int bankCount = ctx.bank().getCount(item.name);
            if (bankCount <= 0) {
                continue;
            }

            int amount = Math.min(needed, bankCount);
            stats.setStatus("Doric's Quest: withdrawing " + amount + "x " + item.name);
            log("Doric's Quest: withdrawing " + amount + "x " + item.name);
            ctx.bank().withdraw(amount, item.name);
            Time.sleep(700, 1100, () -> ctx.inventory().getCount(item.name) >= inventoryCount + amount, 100);
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
        pendingBuyQuantity = Math.max(1, item.quantity - ctx.inventory().getCount(item.name));
        pendingBuyPrice = GePricing.quickBuyPrice(ctx, item.name, item.buyPrice);
        handleGrandExchange(ctx);
    }

    private void handleGrandExchange(APIContext ctx) {
        if (pendingBuyItem == null) {
            closeGrandExchange(ctx);
            return;
        }

        if (ctx.inventory().getCount(pendingBuyItem) >= requiredQuantity(pendingBuyItem)) {
            stats.setStatus("Doric's Quest: bought " + pendingBuyItem);
            log("Doric's Quest: GE purchase obtained: " + pendingBuyItem);
            pendingBuyItem = null;
            pendingBuyQuantity = 0;
            pendingBuyPrice = 0;
            closeGrandExchange(ctx);
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

        if (System.currentTimeMillis() >= nextGeCollectAt) {
            collectGeOffers(ctx);
            nextGeCollectAt = System.currentTimeMillis() + 2_500L;
            if (ctx.inventory().getCount(pendingBuyItem) >= requiredQuantity(pendingBuyItem)) {
                return;
            }
        }

        stats.setStatus("Doric's Quest: buying " + pendingBuyQuantity + "x " + pendingBuyItem);
        log("Doric's Quest: buying " + pendingBuyQuantity + "x " + pendingBuyItem + " for " + pendingBuyPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, pendingBuyQuantity, pendingBuyPrice);
        Time.sleep(900, 1400);
        if (!placed) {
            if (confirmHighPriceGeOffer(ctx)) {
                return;
            }
            log("Doric's Quest: GE buy offer was not placed for " + pendingBuyItem);
            return;
        }

        collectGeOffers(ctx);
        nextGeCollectAt = System.currentTimeMillis() + 2_500L;
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

        NPC doric = findDoric(ctx);
        if (doric != null && doric.isValid() && doric.tileDistanceTo(ctx) <= 10) {
            stats.setStatus("Doric's Quest: talking to Doric to " + reason);
            log("Doric's Quest: talking to Doric to " + reason + ": id=" + doric.getId()
                    + ", tile=" + doric.getLocation());
            ctx.camera().turnTo(doric);
            if (doric.interact("Talk-to", "Doric")
                    || doric.interact("Talk-to")
                    || doric.interactMatch("Talk-to")
                    || doric.click(false)
                    || ctx.mouse().click(doric, false)
                    || ctx.menu().interact("Talk-to", doric, true)
                    || ctx.menu().interact("Talk-to", doric, false)) {
                if ("start quest".equals(reason)) {
                    doricStartAttemptedThisRun = true;
                }
                Time.sleep(1200, 2000,
                        () -> ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen(),
                        100);
                return;
            }

            logRecovery("Doric's Quest: failed to click Doric; repositioning near NPC tile");
            walkLocallyTo(ctx, DORIC_INSIDE_TILE, "Doric talk tile");
            Time.sleep(700, 1100);
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

        if (!DORIC_INSIDE_AREA.contains(location)) {
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
            boolean walked = ctx.walking().walkOnScreen(destination)
                    || ctx.walking().walkToOnScreen(destination)
                    || ctx.walking().walkOnMap(destination)
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
            if (ctx.inventory().getCount(item.name) < item.quantity) {
                return false;
            }
        }
        return true;
    }

    private RequiredItem firstMissingItem(APIContext ctx) {
        for (RequiredItem item : REQUIRED_ITEMS) {
            if (ctx.inventory().getCount(item.name) < item.quantity) {
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
            collectGeOffers(ctx);
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

    private void collectGeOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(500, 800);
        } catch (RuntimeException ignored) {
            // Offer may not be ready yet; the next loop will retry.
        }
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

    private record RequiredItem(String name, int quantity, int buyPrice) {
    }
}
