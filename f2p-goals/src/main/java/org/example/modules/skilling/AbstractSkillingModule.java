package org.example.modules.skilling;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
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
            if (ctx.inventory().contains(toolName) || ctx.equipment().contains(toolName)) {
                return true;
            }
        }
        return false;
    }

    protected boolean ensureAnyTool(APIContext ctx, String toolLabel, String... toolNames) {
        if (hasAnyTool(ctx, toolNames)) {
            return true;
        }

        if (!isBankOpen(ctx)) {
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for " + toolLabel);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return false;
            }

            log("Opening bank for " + toolLabel);
            ctx.bank().open();
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

        log("Missing " + toolLabel + " in bank/inventory");
        Time.sleep(1200, 1800);
        return false;
    }

    protected boolean ensureToolUpgrade(APIContext ctx, String toolLabel, String desiredTool) {
        if (desiredTool == null || desiredTool.isBlank()) {
            return false;
        }

        if (ctx.inventory().contains(desiredTool) || ctx.equipment().contains(desiredTool)) {
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
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for " + toolLabel + " upgrade: " + desiredTool);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for " + toolLabel + " upgrade: " + desiredTool);
            ctx.bank().open();
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

        if (ctx.inventory().contains(pendingToolPurchase) || ctx.equipment().contains(pendingToolPurchase)) {
            log("Tool upgrade obtained: " + pendingToolPurchase);
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(600, 900);
            }
            clearPendingToolPurchase();
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
        if (ctx.inventory().contains(pendingToolPurchase)) {
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

        log("Buying " + toolLabel + " upgrade: 1x " + pendingToolPurchase + " for " + pendingToolBuyPrice);
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingToolPurchase, 1, pendingToolBuyPrice);
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
        if (ctx.inventory().contains(pendingToolPurchase)) {
            log("Collected " + toolLabel + " upgrade: " + pendingToolPurchase);
            ctx.grandExchange().close();
            clearPendingToolPurchase();
        }
        Time.sleep(600, 900);
        return true;
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
            if (!ctx.bank().isReachable()) {
                log("Walking to bank: " + reason);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank: " + reason);
            ctx.bank().open();
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

        if (inventoryOnlyContains(ctx, keepNames)) {
            return false;
        }

        if (!isBankOpen(ctx)) {
            if (!ctx.bank().isReachable()) {
                log("Walking to bank: " + reason);
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank: " + reason);
            ctx.bank().open();
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        log("Depositing inventory before skilling");
        ctx.bank().depositInventory();
        Time.sleep(600, 900);
        ctx.bank().close();
        Time.sleep(500, 800);
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

    private boolean inventoryOnlyContains(APIContext ctx, String... allowedNames) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (!matchesAny(item.getName(), allowedNames)) {
                return false;
            }
        }
        return true;
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
        return ctx.bank().withdraw(1, name)
                || ctx.bank().withdrawAny(1, name)
                || ctx.bank().interactItem("Withdraw-1", name)
                || ctx.bank().interactItem("Withdraw 1", name)
                || ctx.bank().interactItem("Withdraw", name);
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
