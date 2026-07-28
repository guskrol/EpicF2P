package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
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
    private static final Area EGG_AREA = new Area(3225, 3294, 3237, 3302);
    private static final Area DAIRY_COW_AREA = new Area(3253, 3267, 3267, 3282);
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490);
    private static final int DAIRY_COW_ID = 1172;

    private static final String EGG = "Egg";
    private static final String BUCKET = "Bucket";
    private static final String BUCKET_OF_MILK = "Bucket of milk";
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
    private long nextStaleDialogueLogAt;
    private boolean bankChecked;
    private int bankWalkFailures;

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
        stats.setTrainingSkill("Questing");

        if (isComplete(ctx)) {
            stats.setStatus("Cook's Assistant complete");
            return;
        }

        if (handleDialogue(ctx)) {
            return;
        }

        if (!ctx.quests().isStarted(IQuestAPI.Quest.COOKS_ASSISTANT)) {
            talkToCook(ctx);
            return;
        }

        if (hasAllFinalIngredients(ctx)) {
            talkToCook(ctx);
            return;
        }

        if (ctx.grandExchange().isOpen() || pendingBuyItem != null) {
            handleGrandExchange(ctx);
            return;
        }

        if (!bankChecked || ctx.bank().isOpen()) {
            handleBank(ctx);
            return;
        }

        if (collectSimpleWorldIngredient(ctx)) {
            return;
        }

        String itemToBuy = firstMissingBuyableIngredient(ctx);
        if (itemToBuy != null) {
            startGrandExchangeBuy(ctx, itemToBuy);
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
        return isComplete(ctx) ? 0 : 60;
    }

    private boolean handleDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }

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
            if (!ctx.bank().isReachable()) {
                walkToBankForIngredients(ctx);
                return;
            }

            stats.setStatus("Cook's Assistant: opening bank for ingredients");
            log("Cook's Assistant: opening bank for ingredients");
            ctx.bank().open();
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (ctx.inventory().getEmptySlotCount() < 3 && !hasAllFinalIngredients(ctx)) {
            stats.setStatus("Cook's Assistant: depositing non-quest inventory");
            log("Cook's Assistant: depositing non-quest inventory");
            ctx.bank().depositAllExcept(item -> item != null && shouldKeepInventoryItem(item.getName()));
            Time.sleep(650, 1000);
            return;
        }

        for (String item : FINAL_INGREDIENTS) {
            if (!ctx.inventory().contains(item) && ctx.bank().contains(item)) {
                stats.setStatus("Cook's Assistant: withdrawing " + item);
                log("Cook's Assistant: withdrawing " + item);
                ctx.bank().withdraw(1, item);
                Time.sleep(650, 1000, () -> ctx.inventory().contains(item), 100);
                return;
            }
        }

        if (!ctx.inventory().contains(BUCKET_OF_MILK)
                && !ctx.inventory().contains(BUCKET)
                && ctx.bank().contains(BUCKET)) {
            stats.setStatus("Cook's Assistant: withdrawing Bucket for milk");
            log("Cook's Assistant: withdrawing Bucket for milk");
            ctx.bank().withdraw(1, BUCKET);
            Time.sleep(650, 1000, () -> ctx.inventory().contains(BUCKET), 100);
            return;
        }

        if (needsGeBuy(ctx) && ctx.inventory().getCount(COINS) < estimatedMissingBuyCost(ctx) && ctx.bank().contains(COINS)) {
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
        if (!ctx.inventory().contains(EGG)) {
            return collectGroundIngredient(ctx, EGG, EGG_AREA);
        }

        if (!ctx.inventory().contains(BUCKET_OF_MILK) && ctx.inventory().contains(BUCKET)) {
            return milkDairyCow(ctx);
        }

        return false;
    }

    private boolean collectGroundIngredient(APIContext ctx, String itemName, Area area) {
        if (!area.contains(ctx.localPlayer().getLocation())) {
            stats.setStatus("Cook's Assistant: walking to " + itemName);
            logTravel("Cook's Assistant: walking to " + itemName + " area");
            walkSafelyTo(ctx, area.getRandomTile(), itemName + " area");
            Time.sleep(700, 1100);
            return true;
        }

        GroundItem item = ctx.groundItems()
                .query()
                .named(itemName)
                .actions("Take")
                .within(area)
                .results()
                .nearest();

        if (item == null || !item.isValid()) {
            logRecovery("Cook's Assistant: no " + itemName + " found on ground yet");
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Cook's Assistant: taking " + itemName);
        log("Cook's Assistant: taking " + itemName);
        if (item.interact("Take")) {
            Time.sleep(900, 1500, () -> ctx.inventory().contains(itemName), 100);
            bankChecked = false;
        }
        return true;
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
        ctx.camera().turnTo(cow);
        if (clickMilkCow(ctx, cow)) {
            Time.sleep(1200, 2200, () -> ctx.inventory().contains(BUCKET_OF_MILK), 100);
            bankChecked = false;
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
        if (confirmHighPriceGeOffer(ctx)) {
            return;
        }

        if (pendingBuyItem == null) {
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(500, 800, () -> !ctx.grandExchange().isOpen(), 100);
            }
            return;
        }

        if (ctx.inventory().contains(pendingBuyItem)) {
            stats.setStatus("Cook's Assistant: bought " + pendingBuyItem);
            log("Cook's Assistant: GE purchase obtained: " + pendingBuyItem);
            pendingBuyItem = null;
            pendingBuyPrice = 0;
            closeGrandExchange(ctx);
            bankChecked = false;
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

        if (System.currentTimeMillis() >= nextGeCollectAt) {
            collectGeOffers(ctx);
            nextGeCollectAt = System.currentTimeMillis() + 2_500L;
            if (ctx.inventory().contains(pendingBuyItem)) {
                return;
            }
        }

        stats.setStatus("Cook's Assistant: buying " + pendingBuyItem);
        log("Cook's Assistant: buying " + pendingBuyItem + " for " + pendingBuyPrice);
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, 1, pendingBuyPrice);
        Time.sleep(900, 1400);
        if (!placed) {
            logRecovery("Cook's Assistant: GE buy offer was not placed for " + pendingBuyItem);
            return;
        }

        collectGeOffers(ctx);
        nextGeCollectAt = System.currentTimeMillis() + 2_500L;
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
            stats.setStatus("Cook's Assistant: talking to Cook");
            log("Cook's Assistant: talking to Cook"
                    + (hasAllFinalIngredients(ctx) ? " with ingredients" : " to start quest"));
            ctx.camera().turnTo(cook);
            if (cook.interact("Talk-to")) {
                Time.sleep(1200, 2000,
                        () -> ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen(),
                        100);
            }
            return;
        }

        stats.setStatus("Cook's Assistant: walking to Cook");
        logTravel("Cook's Assistant: walking to Lumbridge Cook");
        walkSafelyTo(ctx, COOK_AREA.getRandomTile(), "Lumbridge Cook");
        Time.sleep(700, 1100);
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
        if (ctx.bank().isReachable()) {
            stats.setStatus("Cook's Assistant: opening bank for ingredients");
            log("Cook's Assistant: opening bank for ingredients");
            ctx.bank().open();
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return ctx.bank().isOpen();
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
            boolean walked = ctx.walking().walkOnScreen(destination)
                    || ctx.walking().walkToOnScreen(destination)
                    || ctx.walking().walkOnMap(destination)
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
        return ctx.inventory().contains(BUCKET_OF_MILK)
                && ctx.inventory().contains(EGG)
                && ctx.inventory().contains(POT_OF_FLOUR);
    }

    private String firstMissingIngredient(APIContext ctx) {
        for (String item : FINAL_INGREDIENTS) {
            if (!ctx.inventory().contains(item)) {
                return item;
            }
        }
        return "none";
    }

    private String firstMissingBuyableIngredient(APIContext ctx) {
        if (!ctx.inventory().contains(POT_OF_FLOUR)) {
            return POT_OF_FLOUR;
        }
        if (!ctx.inventory().contains(BUCKET_OF_MILK) && !ctx.inventory().contains(BUCKET)) {
            return BUCKET_OF_MILK;
        }
        if (!ctx.inventory().contains(EGG) && ctx.inventory().getCount(COINS) >= buyPrice(EGG)) {
            return EGG;
        }
        return null;
    }

    private boolean needsGeBuy(APIContext ctx) {
        return firstMissingBuyableIngredient(ctx) != null
                || !ctx.inventory().contains(POT_OF_FLOUR);
    }

    private int estimatedMissingBuyCost(APIContext ctx) {
        int cost = 0;
        if (!ctx.inventory().contains(POT_OF_FLOUR)) {
            cost += buyPrice(POT_OF_FLOUR);
        }
        if (!ctx.inventory().contains(BUCKET_OF_MILK) && !ctx.inventory().contains(BUCKET)) {
            cost += buyPrice(BUCKET_OF_MILK);
        }
        if (!ctx.inventory().contains(EGG)) {
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
        if (yes == null) {
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

    private void collectGeOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(500, 800);
        } catch (RuntimeException ignored) {
            // No ready offer is fine; the next loop will retry.
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
            stats.recordRecoverableError(message);
            log(message);
            nextRecoveryLogAt = now + 5_000L;
        }
    }

    private void log(String message) {
        logger.accept(message);
    }
}
