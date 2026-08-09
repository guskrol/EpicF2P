package org.example.modules.skilling;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

abstract class AbstractSkillingModule implements ManagedF2PModule {
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490);

    protected final Consumer<String> logger;
    protected final ScriptStats stats;
    protected final SkillCapManager caps;
    private String pendingToolPurchase;
    private int pendingToolBuyPrice;
    private long toolPurchaseRetryAt;
    private long pendingToolOfferCheckAt;
    private int cleanInventoryDepositAttempts;
    private int cleanInventoryLastNonKeepCount = -1;
    private long cleanInventoryBypassUntil;
    private String cleanInventoryBypassSignature = "";

    AbstractSkillingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        this.logger = logger;
        this.stats = stats;
        this.caps = caps;
    }

    protected int level(APIContext ctx, Skill.Skills skill) {
        return ctx.skills().get(skill).getRealLevel();
    }

    protected boolean complete(APIContext ctx, Skill.Skills skill) {
        return caps.isComplete(ctx, skill);
    }

    protected boolean hasAnyTool(APIContext ctx, String... toolNames) {
        for (String toolName : toolNames) {
            if (inventoryHasUsableItem(ctx, toolName) || ctx.equipment().contains(toolName)) {
                return true;
            }
        }
        return false;
    }

    private boolean inventoryHasUsableItem(APIContext ctx, String itemName) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null
                    && namesMatch(item.getName(), itemName)
                    && !item.isNoted()) {
                return true;
            }
        }
        return false;
    }

    protected boolean ensureAnyTool(APIContext ctx, String toolLabel, String... toolNames) {
        if (hasAnyTool(ctx, toolNames)) {
            clearPendingToolPurchase();
            return true;
        }

        if (pendingToolPurchase != null) {
            handlePendingToolPurchase(ctx, toolLabel);
            return hasAnyTool(ctx, toolNames);
        }

        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for " + toolLabel);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return false;
            }

            log("Opening bank for " + toolLabel);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return false;
        }

        for (String toolName : toolNames) {
            if (bankHasItem(ctx, toolName)) {
                log("Withdrawing " + toolName);
                if (withdrawOne(ctx, toolName)) {
                    Time.sleep(600, 900);
                    return true;
                }
            }
        }

        String fallbackTool = fallbackToolName(toolNames);
        if (fallbackTool != null && planMissingToolPurchase(ctx, toolLabel, fallbackTool)) {
            return false;
        }

        log("Missing " + toolLabel + " in bank/inventory");
        Time.sleep(1200, 1800);
        return false;
    }

    private boolean planMissingToolPurchase(APIContext ctx, String toolLabel, String toolName) {
        long now = System.currentTimeMillis();
        if (now < toolPurchaseRetryAt) {
            return false;
        }

        int buyPrice = toolBuyPrice(ctx, toolName);
        int inventoryCoins = ctx.inventory().getCount(true, "Coins");
        int bankCoins = ctx.bank().getCount("Coins");
        int availableCoins = inventoryCoins + bankCoins;
        if (availableCoins < buyPrice) {
            log("Missing " + toolLabel + " and not enough coins for " + toolName
                    + " (" + availableCoins + "/" + buyPrice + ")");
            toolPurchaseRetryAt = now + ThreadLocalRandom.current().nextLong(4, 8) * 60_000L;
            ctx.bank().close();
            Time.sleep(600, 900);
            return true;
        }

        pendingToolPurchase = toolName;
        pendingToolBuyPrice = buyPrice;
        pendingToolOfferCheckAt = 0L;
        if (inventoryCoins < buyPrice && bankCoins > 0) {
            int neededCoins = Math.min(buyPrice - inventoryCoins, bankCoins);
            log("Withdrawing " + neededCoins + " coins for missing " + toolLabel + ": " + toolName);
            ctx.bank().withdraw(neededCoins, "Coins");
            Time.sleep(600, 900);
            return true;
        }

        log("Planning missing " + toolLabel + " purchase: " + toolName);
        ctx.bank().close();
        Time.sleep(600, 900);
        return true;
    }

    private String fallbackToolName(String... toolNames) {
        if (toolNames == null || toolNames.length == 0) {
            return null;
        }
        return toolNames[toolNames.length - 1];
    }

    protected boolean ensureToolUpgrade(APIContext ctx, String toolLabel, String desiredTool) {
        if (desiredTool == null || desiredTool.isBlank()) {
            return false;
        }

        if (inventoryHasUsableItem(ctx, desiredTool) || ctx.equipment().contains(desiredTool)) {
            clearPendingToolPurchase();
            return false;
        }

        long now = System.currentTimeMillis();
        if (pendingToolPurchase == null && now < toolPurchaseRetryAt) {
            return false;
        }

        if (pendingToolPurchase != null) {
            return handlePendingToolPurchase(ctx, toolLabel);
        }

        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for " + toolLabel + " upgrade: " + desiredTool);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for " + toolLabel + " upgrade: " + desiredTool);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        if (bankHasItem(ctx, desiredTool)) {
            log("Withdrawing upgraded " + toolLabel + ": " + desiredTool);
            if (withdrawOne(ctx, desiredTool)) {
                Time.sleep(600, 900);
                return true;
            }
        }

        int buyPrice = toolBuyPrice(ctx, desiredTool);
        int inventoryCoins = ctx.inventory().getCount(true, "Coins");
        int bankCoins = ctx.bank().getCount("Coins");
        int availableCoins = inventoryCoins + bankCoins;
        if (availableCoins < buyPrice) {
            log("Not enough coins for " + desiredTool + " upgrade (" + availableCoins + "/" + buyPrice + "); using current " + toolLabel);
            toolPurchaseRetryAt = now + ThreadLocalRandom.current().nextLong(4, 8) * 60_000L;
            ctx.bank().close();
            Time.sleep(600, 900);
            return false;
        }

        if (inventoryCoins < buyPrice && bankCoins > 0) {
            pendingToolPurchase = desiredTool;
            pendingToolBuyPrice = buyPrice;
            pendingToolOfferCheckAt = 0L;
            int neededCoins = Math.min(buyPrice - inventoryCoins, bankCoins);
            log("Withdrawing " + neededCoins + " coins for " + desiredTool);
            ctx.bank().withdraw(neededCoins, "Coins");
            Time.sleep(600, 900);
            return true;
        }

        pendingToolPurchase = desiredTool;
        pendingToolBuyPrice = buyPrice;
        pendingToolOfferCheckAt = 0L;
        log("Planning " + toolLabel + " upgrade: " + desiredTool);
        ctx.bank().close();
        Time.sleep(600, 900);
        return true;
    }

    private boolean handlePendingToolPurchase(APIContext ctx, String toolLabel) {
        if (pendingToolPurchase == null) {
            return false;
        }

        if (inventoryHasUsableItem(ctx, pendingToolPurchase) || ctx.equipment().contains(pendingToolPurchase)) {
            log("Tool upgrade obtained: " + pendingToolPurchase);
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(600, 900);
            }
            clearPendingToolPurchase();
            return true;
        }

        if (pendingToolOfferCheckAt == 0L && checkBankForPendingTool(ctx, toolLabel)) {
            return true;
        }

        if (isBankOpen(ctx)) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return true;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to buy " + pendingToolPurchase);
            Navigation.walkToNoTeleports(ctx, GE_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return true;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to buy " + pendingToolPurchase);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (confirmToolHighPriceGeOffer(ctx)) {
            return true;
        }

        collectToolOffers(ctx);
        if (inventoryHasUsableItem(ctx, pendingToolPurchase)) {
            log("Collected " + toolLabel + " upgrade: " + pendingToolPurchase);
            ctx.grandExchange().close();
            clearPendingToolPurchase();
            Time.sleep(600, 900);
            return true;
        }

        long now = System.currentTimeMillis();
        if (pendingToolOfferCheckAt > now) {
            log("Waiting for " + pendingToolPurchase + " GE offer");
            Time.sleep(1000, 1600);
            return true;
        }

        pendingToolBuyPrice = Math.max(pendingToolBuyPrice, toolBuyPrice(ctx, pendingToolPurchase));
        log("Buying " + toolLabel + " upgrade: 1x " + pendingToolPurchase + " for " + pendingToolBuyPrice);
        boolean placed = placeToolBuyOffer(ctx, pendingToolPurchase, 1, pendingToolBuyPrice);
        if (!placed) {
            if (confirmToolHighPriceGeOffer(ctx)) {
                return true;
            }
            pendingToolOfferCheckAt = now + 8_000L;
            log("Tool buy offer was not placed; retrying " + pendingToolPurchase);
            Time.sleep(1200, 1800);
            return true;
        }

        pendingToolOfferCheckAt = now + 8_000L;
        Time.sleep(5000, 8000);
        collectToolOffers(ctx);
        if (inventoryHasUsableItem(ctx, pendingToolPurchase)) {
            log("Collected " + toolLabel + " upgrade: " + pendingToolPurchase);
            ctx.grandExchange().close();
            clearPendingToolPurchase();
        }
        Time.sleep(600, 900);
        return true;
    }

    private boolean checkBankForPendingTool(APIContext ctx, String toolLabel) {
        if (ctx.grandExchange().isOpen()) {
            log("Closing GE to recheck bank for " + pendingToolPurchase);
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                return false;
            }

            log("Opening bank to recheck " + toolLabel + ": " + pendingToolPurchase);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        if (!bankHasItem(ctx, pendingToolPurchase)) {
            return false;
        }

        log("Found pending " + toolLabel + " in bank; withdrawing instead of buying: " + pendingToolPurchase);
        if (withdrawOne(ctx, pendingToolPurchase)) {
            Time.sleep(600, 900);
            clearPendingToolPurchase();
            return true;
        }
        return true;
    }

    private boolean placeToolBuyOffer(APIContext ctx, String itemName, int quantity, int price) {
        if (ctx.grandExchange().placeBuyOffer(itemName, quantity, price)) {
            return true;
        }

        if (clickVisibleWidgetByText(ctx, itemName)) {
            Time.sleep(500, 900);
        }

        if (isBuyOfferSetupScreen(ctx)) {
            return completeToolBuyOffer(ctx, quantity, price);
        }

        ctx.grandExchange().backToOverview();
        Time.sleep(300, 600);
        if (ctx.grandExchange().newBuyOffer(itemName)) {
            Time.sleep(700, 1200);
            if (clickVisibleWidgetByText(ctx, itemName)) {
                Time.sleep(500, 900);
            }
            return completeToolBuyOffer(ctx, quantity, price);
        }

        if (clickVisibleWidgetByText(ctx, itemName)) {
            Time.sleep(500, 900);
            return completeToolBuyOffer(ctx, quantity, price);
        }

        return false;
    }

    private boolean completeToolBuyOffer(APIContext ctx, int quantity, int price) {
        if (!isBuyOfferSetupScreen(ctx)) {
            return false;
        }

        ctx.grandExchange().setQuantity(quantity);
        Time.sleep(250, 450);
        ctx.grandExchange().setPrice(price);
        Time.sleep(250, 450);
        return ctx.grandExchange().confirmOffer();
    }

    private boolean isBuyOfferSetupScreen(APIContext ctx) {
        IGrandExchangeAPI.GrandExchangeScreen screen = ctx.grandExchange().getCurrentScreen();
        return screen == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER
                || screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER;
    }

    private boolean confirmToolHighPriceGeOffer(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null || !hasVisibleWidgetText(ctx, "much higher than the guide price")) {
            return false;
        }

        log("Confirming GE high-price warning for " + pendingToolPurchase);
        if (clickWidgetCenter(ctx, yes)
                || ctx.mouse().click(yes, false)
                || yes.click(false)
                || yes.click()
                || ctx.menu().interact("Yes", yes, true)
                || ctx.menu().interact("Yes", true)
                || ctx.menu().interact("Yes")) {
            pendingToolOfferCheckAt = System.currentTimeMillis() + 8_000L;
            Time.sleep(1200, 1800);
            collectToolOffers(ctx);
            return true;
        }

        Time.sleep(600, 900);
        return true;
    }

    private void collectToolOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(600, 900);
            ctx.grandExchange().collectToBank();
            Time.sleep(600, 900);
        } catch (RuntimeException ignored) {
            // No ready offer is fine here; the next loop will retry or collect.
        }
    }

    private int toolBuyPrice(APIContext ctx, String itemName) {
        return GePricing.quickBuyPrice(ctx, itemName, 1000L);
    }

    private WidgetChild findVisibleWidgetByText(APIContext ctx, String text) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            return text.equalsIgnoreCase(widgetText == null ? "" : widgetText.trim())
                    || text.equalsIgnoreCase(rawText == null ? "" : rawText.trim());
        })) {
            return widget;
        }

        WidgetChild queried = ctx.widgets()
                .query()
                .textContains(text)
                .results()
                .first();
        return isVisibleWidget(queried) ? queried : null;
    }

    private boolean hasVisibleWidgetText(APIContext ctx, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String needle = text.toLowerCase();
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            return containsIgnoreCase(widgetText, needle) || containsIgnoreCase(rawText, needle);
        })) {
            return true;
        }

        return false;
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean clickVisibleWidgetByText(APIContext ctx, String text) {
        WidgetChild widget = findVisibleWidgetByText(ctx, text);
        if (!isVisibleWidget(widget)) {
            return false;
        }

        return clickWidgetCenter(ctx, widget)
                || ctx.mouse().click(widget, false)
                || widget.click(false)
                || widget.click();
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private void clearPendingToolPurchase() {
        pendingToolPurchase = null;
        pendingToolBuyPrice = 0;
        pendingToolOfferCheckAt = 0L;
    }

    protected boolean bankAllExcept(APIContext ctx, String reason, String... keepNames) {
        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank: " + reason);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank: " + reason);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        ctx.bank().depositAllExcept(keepNames);
        Time.sleep(600, 900);
        ctx.bank().close();
        return true;
    }

    protected boolean ensureCleanInventory(APIContext ctx, String reason, String... keepNames) {
        if (pendingToolPurchase != null) {
            return false;
        }

        int nonKeepCount = nonKeptInventoryCount(ctx, keepNames);
        if (nonKeepCount <= 0) {
            resetCleanInventoryState();
            return false;
        }

        String currentSignature = nonKeptInventorySignature(ctx, keepNames);
        long now = System.currentTimeMillis();
        if (now < cleanInventoryBypassUntil && currentSignature.equals(cleanInventoryBypassSignature)) {
            return false;
        }
        if (!currentSignature.equals(cleanInventoryBypassSignature)) {
            cleanInventoryBypassUntil = 0L;
            cleanInventoryDepositAttempts = 0;
            cleanInventoryLastNonKeepCount = -1;
        }

        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank: " + reason);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank: " + reason);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        int beforeCount = nonKeptInventoryCount(ctx, keepNames);
        log("Depositing inventory before skilling");
        ctx.bank().depositAllExcept(keepNames);
        Time.sleep(700, 1100, () -> nonKeptInventoryCount(ctx, keepNames) <= 0, 100);
        if (nonKeptInventoryCount(ctx, keepNames) > 0) {
            depositNonKeptInventoryByName(ctx, keepNames);
            Time.sleep(700, 1100, () -> nonKeptInventoryCount(ctx, keepNames) <= 0, 100);
        }

        int afterCount = nonKeptInventoryCount(ctx, keepNames);
        if (afterCount <= 0) {
            resetCleanInventoryState();
            ctx.bank().close();
            Time.sleep(500, 800, () -> !isBankOpen(ctx), 100);
            return true;
        }

        if (cleanInventoryLastNonKeepCount >= 0
                && afterCount >= cleanInventoryLastNonKeepCount
                && afterCount >= beforeCount) {
            cleanInventoryDepositAttempts++;
        } else {
            cleanInventoryDepositAttempts = 1;
        }
        cleanInventoryLastNonKeepCount = afterCount;

        if (cleanInventoryDepositAttempts >= 3) {
            String leftovers = describeNonKeptInventory(ctx, keepNames);
            log("Inventory cleanup still has " + leftovers + "; continuing to avoid bank loop");
            cleanInventoryBypassSignature = currentSignature;
            cleanInventoryBypassUntil = System.currentTimeMillis() + 5 * 60_000L;
            resetCleanInventoryAttemptCounters();
            ctx.bank().close();
            Time.sleep(500, 800, () -> !isBankOpen(ctx), 100);
            return false;
        }

        return true;
    }

    protected boolean walkTo(APIContext ctx, Area area, String label) {
        if (area.contains(ctx.localPlayer().getLocation())) {
            return false;
        }

        log("Walking to " + label);
        Navigation.walkTo(ctx, area.getRandomTile());
        Time.sleep(1200, 1800);
        return true;
    }

    protected boolean isBankOpen(APIContext ctx) {
        return ctx.bank().isOpen()
                || hasVisibleWidgetText(ctx, "The Bank of Gielinor")
                || hasVisibleWidgetText(ctx, "Bank of Gielinor");
    }

    protected boolean waitIfBusy(APIContext ctx) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            Time.sleep(600, 900);
            return true;
        }
        return false;
    }

    protected boolean bankHasItem(APIContext ctx, String name) {
        if (ctx.bank().contains(name) || ctx.bank().getItem(name) != null) {
            return true;
        }

        for (ItemWidget item : ctx.bank().getItems()) {
            if (item != null && namesMatch(item.getName(), name)) {
                return true;
            }
        }
        return false;
    }

    private void depositNonKeptInventoryByName(APIContext ctx, String... keepNames) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (!matchesAny(item.getName(), keepNames)) {
                ctx.bank().depositAll(item.getName());
                Time.sleep(120, 220);
            }
        }
    }

    private int nonKeptInventoryCount(APIContext ctx, String... keepNames) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (!matchesAny(item.getName(), keepNames)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private String nonKeptInventorySignature(APIContext ctx, String... keepNames) {
        StringBuilder signature = new StringBuilder();
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (!matchesAny(item.getName(), keepNames)) {
                signature.append(normalizedName(item.getName()))
                        .append(':')
                        .append(Math.max(1, item.getStackSize()))
                        .append('|');
            }
        }
        return signature.toString();
    }

    private String describeNonKeptInventory(APIContext ctx, String... keepNames) {
        StringBuilder items = new StringBuilder();
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (!matchesAny(item.getName(), keepNames)) {
                if (items.length() > 0) {
                    items.append(", ");
                }
                items.append(item.getName()).append(" x").append(Math.max(1, item.getStackSize()));
            }
        }
        return items.length() == 0 ? "unknown inventory leftovers" : items.toString();
    }

    private void resetCleanInventoryState() {
        cleanInventoryBypassUntil = 0L;
        cleanInventoryBypassSignature = "";
        resetCleanInventoryAttemptCounters();
    }

    private void resetCleanInventoryAttemptCounters() {
        cleanInventoryDepositAttempts = 0;
        cleanInventoryLastNonKeepCount = -1;
    }

    private boolean matchesAny(String itemName, String... allowedNames) {
        for (String allowedName : allowedNames) {
            if (namesMatch(itemName, allowedName)) {
                return true;
            }
        }
        return false;
    }

    protected boolean withdrawOne(APIContext ctx, String name) {
        ensureItemWithdrawMode(ctx);
        return ctx.bank().withdraw(1, name)
                || ctx.bank().withdrawAny(1, name)
                || ctx.bank().interactItem("Withdraw-1", name)
                || ctx.bank().interactItem("Withdraw 1", name)
                || ctx.bank().interactItem("Withdraw", name);
    }

    private boolean ensureItemWithdrawMode(APIContext ctx) {
        if (!isBankOpen(ctx) || ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            return false;
        }

        log("Switching bank withdraw mode to Item for tool withdrawal");
        ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
        Time.sleep(500, 800, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
        return true;
    }

    protected boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    protected String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    protected void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }
}
