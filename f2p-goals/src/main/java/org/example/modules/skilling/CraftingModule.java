package org.example.modules.skilling;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.funding.FundingPlanner;
import org.example.core.items.F2PItemRegistry;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class CraftingModule extends AbstractSkillingModule {
    private static final String NEEDLE = "Needle";
    private static final String THREAD = "Thread";
    private static final String LEATHER = "Leather";
    private static final String COINS = "Coins";
    private static final int INVENTORY_WIDGET_GROUP = 149;
    private static final int SKILLMULTI_GROUP = InterfaceID.SKILLMULTI;
    private static final int SKILLMULTI_ALL_CHILD = childId(InterfaceID.Skillmulti.ALL);
    private static final int SKILLMULTI_FIRST_ITEM_CHILD = childId(InterfaceID.Skillmulti.A);
    private static final int LEVELUP_GROUP = InterfaceID.LEVELUP_DISPLAY;
    private static final int LEVELUP_CONTINUE_CHILD = childId(InterfaceID.LevelupDisplay.CONTINUE);
    private static final int MIN_LEATHER_BATCH = 60;
    private static final int MAX_LEATHER_BATCH = 140;
    private static final int THREAD_BATCH = 80;
    private static final int CRAFTING_FUNDING_BUFFER_COINS = 450;
    private static final long GE_OFFER_CHECK_DELAY_MILLIS = 7_000L;
    private static final String[] CRAFTING_FUNDING_SAFE_SELL_ITEMS = F2PItemRegistry.fundingSellItems();
    private static final String[] CRAFTING_FUNDING_SALE_KEEP_ITEMS = {
            NEEDLE,
            THREAD,
            LEATHER,
            COINS
    };

    private static final LeatherProduct[] PRODUCTS = {
            new LeatherProduct("Leather gloves", 1),
            new LeatherProduct("Leather boots", 7),
            new LeatherProduct("Leather cowl", 9),
            new LeatherProduct("Leather vambraces", 11),
            new LeatherProduct("Leather body", 14),
            new LeatherProduct("Leather chaps", 18)
    };

    private LeatherProduct activeProduct;
    private int activeProductBatchesLeft;
    private LeatherProduct pendingUnlockAwarenessProduct;
    private int pendingUnlockAwarenessBatches;
    private boolean craftingBatchActive;
    private int lastLeatherCount = -1;
    private long lastLeatherChangeAt;
    private long nextCraftingProgressLogAt;
    private long nextInterfaceRecoveryLogAt;
    private String pendingBuyItem;
    private int pendingBuyQuantity;
    private int pendingBuyPrice;
    private long pendingOfferCheckAt;
    private final FundingPlanner fundingPlanner = new FundingPlanner();
    private String pendingFundingSellItem;
    private int pendingFundingSellQuantity;
    private int pendingFundingSellPrice;
    private boolean pendingFundingSellOfferPlaced;
    private long nextPendingFundingSellCheckAt;
    private String activeFundingTargetItem;
    private int activeFundingTargetCoins;

    public CraftingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        super(logger, stats, caps);
    }

    @Override
    public String name() {
        return "skills.crafting";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return complete(ctx, Skill.Skills.CRAFTING);
    }

    @Override
    public int priority(APIContext ctx) {
        return caps.levelsRemaining(ctx, Skill.Skills.CRAFTING);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);
        stats.setTrainingSkill("Crafting");

        if (clearBlockingContinue(ctx)) {
            return;
        }

        if (pendingFundingSellItem != null) {
            handlePendingFundingSale(ctx);
            return;
        }

        if (pendingBuyItem != null || ctx.grandExchange().isOpen()) {
            handlePendingBuy(ctx);
            return;
        }

        if (isComplete(ctx)) {
            finishCraftingCap(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            handleBank(ctx);
            return;
        }

        if (hasCraftingProducts(ctx) && (ctx.inventory().isFull() || leatherCount(ctx) == 0)) {
            openBankForCrafting(ctx, "depositing crafted leather items");
            Time.sleep(900, 1400);
            return;
        }

        if (!hasNeedle(ctx) || threadCount(ctx) <= 0 || leatherCount(ctx) <= 0) {
            openBankForCrafting(ctx, "preparing leather crafting materials");
            Time.sleep(900, 1400);
            return;
        }

        if (waitIfBusy(ctx)) {
            trackCraftingProgress(ctx);
            return;
        }

        craftLeather(ctx);
    }

    private void craftLeather(APIContext ctx) {
        LeatherProduct product = chooseActiveProduct(ctx);
        if (product == null) {
            log("No leather product available for current Crafting level");
            Time.sleep(1200, 1800);
            return;
        }

        int leatherBefore = leatherCount(ctx);
        if (leatherBefore <= 0) {
            openBankForCrafting(ctx, "out of Leather");
            Time.sleep(900, 1400);
            return;
        }

        if (craftingInterfaceOpen(ctx, product)) {
            if (selectCraftingAllQuantity(ctx)) {
                Time.sleep(250, 550);
            }
            if (clickLeatherProduct(ctx, product)) {
                craftingBatchActive = true;
                rememberLeatherCount(ctx);
                Time.sleep(
                        1800,
                        3200,
                        () -> leatherCount(ctx) < leatherBefore
                                || ctx.localPlayer().isAnimating()
                                || ctx.dialogues().canContinue(),
                        100
                );
                return;
            }

            logInterfaceRecovery("Crafting interface open, but product click failed for " + product.name());
            Time.sleep(800, 1300);
            return;
        }

        log("Opening leather Crafting interface for " + product.name()
                + " with " + leatherBefore + " Leather");
        if (useNeedleOnLeather(ctx)) {
            Time.sleep(
                    900,
                    1800,
                    () -> craftingInterfaceOpen(ctx, product)
                            || leatherCount(ctx) < leatherBefore
                            || ctx.dialogues().canContinue(),
                    100
            );
            return;
        }

        log("Could not use Needle on Leather");
        clearInventoryInteractionState(ctx);
        Time.sleep(900, 1400);
    }

    private void handleBank(APIContext ctx) {
        if (hasCraftingProducts(ctx)) {
            log("Depositing crafted leather products");
            for (LeatherProduct product : PRODUCTS) {
                ctx.bank().depositAll(product.name());
            }
            Time.sleep(600, 900);
            finishCraftingBatch();
            return;
        }

        if (inventoryHasUnexpectedItems(ctx)) {
            log("Depositing non-crafting inventory before Crafting");
            ctx.bank().depositAllExcept(NEEDLE, THREAD, LEATHER, COINS);
            Time.sleep(650, 1000);
            return;
        }

        if (!hasNeedle(ctx)) {
            if (bankHasItem(ctx, NEEDLE)) {
                log("Withdrawing Needle for Crafting");
                withdrawOne(ctx, NEEDLE);
                Time.sleep(600, 900);
                return;
            }
            planMaterialBuyFromBank(ctx, NEEDLE, 1);
            return;
        }

        if (threadCount(ctx) <= 0) {
            if (bankHasItem(ctx, THREAD)) {
                int amount = Math.max(1, Math.min(THREAD_BATCH, ctx.bank().getCount(THREAD)));
                log("Withdrawing " + amount + "x Thread for Crafting");
                ctx.bank().withdraw(amount, THREAD);
                Time.sleep(600, 900);
                return;
            }
            planMaterialBuyFromBank(ctx, THREAD, THREAD_BATCH);
            return;
        }

        if (leatherCount(ctx) <= 0) {
            int bankLeather = ctx.bank().getCount(LEATHER);
            if (bankLeather > 0) {
                int emptySlots = Math.max(1, ctx.inventory().getEmptySlotCount());
                int amount = Math.max(1, Math.min(bankLeather, emptySlots));
                log("Withdrawing " + amount + "x Leather for Crafting");
                ctx.bank().withdraw(amount, LEATHER);
                Time.sleep(600, 900);
                return;
            }
            planMaterialBuyFromBank(ctx, LEATHER, randomLeatherBuyQuantity());
            return;
        }

        log("Leather Crafting inventory ready; closing bank");
        ctx.bank().close();
        Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
    }

    private void planMaterialBuyFromBank(APIContext ctx, String itemName, int quantity) {
        int unitPrice = materialBuyPrice(ctx, itemName);
        int totalCost = Math.max(1, unitPrice * Math.max(1, quantity));
        int inventoryCoins = ctx.inventory().getCount(true, COINS);
        int bankCoins = ctx.bank().getCount(COINS);

        if (inventoryCoins < totalCost && bankCoins > 0) {
            int toWithdraw = Math.min(bankCoins, totalCost - inventoryCoins);
            log("Withdrawing " + toWithdraw + " coins for Crafting buy: " + itemName);
            ctx.bank().withdraw(toWithdraw, COINS);
            Time.sleep(600, 900);
            return;
        }

        if (inventoryCoins + bankCoins < totalCost) {
            startCraftingFundingFromBank(ctx, itemName, totalCost, inventoryCoins, bankCoins);
            return;
        }

        pendingBuyItem = itemName;
        pendingBuyQuantity = Math.max(1, quantity);
        pendingBuyPrice = unitPrice;
        pendingOfferCheckAt = 0L;
        log("Planning Crafting buy: " + pendingBuyQuantity + "x " + pendingBuyItem
                + " at " + pendingBuyPrice + " each");
        ctx.bank().close();
        Time.sleep(600, 900, () -> !ctx.bank().isOpen(), 100);
    }

    private void startCraftingFundingFromBank(
            APIContext ctx,
            String targetItem,
            int targetCost,
            int inventoryCoins,
            int bankCoins
    ) {
        activeFundingTargetItem = targetItem;
        activeFundingTargetCoins = targetCost + fundingPlanner.randomBufferCoins(CRAFTING_FUNDING_BUFFER_COINS);

        FundingPlanner.Decision decision = chooseCraftingFundingDecision(ctx);
        if (decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            startCraftingFundingStockSale(ctx, decision);
            return;
        }

        log("Not enough coins for Crafting buy: " + targetItem + " ("
                + (inventoryCoins + bankCoins) + "/" + targetCost
                + "), and no ready sellable funding stock was found");
        stats.setFundingReason("Crafting waiting for coins: " + targetItem);
        ctx.bank().close();
        Time.sleep(900, 1400);
    }

    private FundingPlanner.Decision chooseCraftingFundingDecision(APIContext ctx) {
        int knownCoins = knownCraftingFundingCoins(ctx);
        FundingPlanner.Decision decision = fundingPlanner.choose(
                activeFundingTargetCoins,
                knownCoins,
                craftingFundingAssets(ctx)
        );
        if (decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            log("Crafting FundingPlanner selected stock sale: "
                    + decision.itemName()
                    + " inv=" + decision.inventoryCount()
                    + " bank=" + decision.bankCount()
                    + " projected~" + decision.projectedValue() + "gp");
            stats.setFundingReason("Crafting stock sale: " + decision.itemName()
                    + " for " + activeFundingTargetItem);
        }
        return decision;
    }

    private List<FundingPlanner.Asset> craftingFundingAssets(APIContext ctx) {
        List<FundingPlanner.Asset> assets = new ArrayList<>();
        for (String itemName : CRAFTING_FUNDING_SAFE_SELL_ITEMS) {
            if (!isSafeCraftingFundingItem(itemName)) {
                continue;
            }

            assets.add(new FundingPlanner.Asset(
                    itemName,
                    inventoryItemCount(ctx, itemName),
                    bankItemCount(ctx, itemName),
                    sellPriceFor(ctx, itemName)
            ));
        }
        return assets;
    }

    private boolean isSafeCraftingFundingItem(String itemName) {
        if (itemName == null || itemName.isBlank() || !F2PItemRegistry.isGeSellable(itemName)) {
            return false;
        }
        if (namesMatch(itemName, NEEDLE)
                || namesMatch(itemName, THREAD)
                || namesMatch(itemName, LEATHER)
                || namesMatch(itemName, COINS)
                || isCraftingProduct(itemName)) {
            return false;
        }
        return true;
    }

    private void startCraftingFundingStockSale(APIContext ctx, FundingPlanner.Decision decision) {
        pendingFundingSellItem = decision.itemName();
        pendingFundingSellQuantity = Math.max(1, decision.inventoryCount() + decision.bankCount());
        pendingFundingSellPrice = sellPriceFor(ctx, pendingFundingSellItem);
        pendingFundingSellOfferPlaced = false;
        nextPendingFundingSellCheckAt = 0L;
        log("Crafting funding selected stock sale: " + pendingFundingSellQuantity
                + "x " + pendingFundingSellItem + " for " + activeFundingTargetItem);
        stats.setFundingReason("Crafting stock sale: " + pendingFundingSellItem
                + " for " + activeFundingTargetItem);
        withdrawFundingStockFromBank(ctx);
    }

    private void handlePendingFundingSale(APIContext ctx) {
        if (isBankOpen(ctx)) {
            withdrawFundingStockFromBank(ctx);
            return;
        }

        if (inventoryItemCount(ctx, pendingFundingSellItem) <= 0 && !pendingFundingSellOfferPlaced) {
            if (ctx.grandExchange().isOpen()) {
                log("Closing GE before Crafting funding stock bank");
                ctx.grandExchange().close();
                Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
                return;
            }
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for Crafting funding stock");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank for Crafting funding stock");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return;
        }

        if (!isAtGrandExchange(ctx)) {
            log("Walking to GE to sell Crafting funding stock: " + pendingFundingSellItem);
            Navigation.walkToNoTeleports(ctx, grandExchangeArea().getRandomTile());
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to sell Crafting funding stock: " + pendingFundingSellItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (pendingFundingSellOfferPlaced) {
            if (System.currentTimeMillis() < nextPendingFundingSellCheckAt) {
                Time.sleep(600, 900);
                return;
            }

            collectSaleOffers(ctx);
            if (inventoryItemCount(ctx, pendingFundingSellItem) <= 0) {
                log("Crafting funding sale collected: " + pendingFundingSellItem);
                clearPendingFundingSale();
                closeGrandExchangeAfterTrade(ctx, "Crafting funding sale collection");
            } else {
                nextPendingFundingSellCheckAt = System.currentTimeMillis() + 5_000L;
            }
            return;
        }

        int quantity = Math.min(
                pendingFundingSellQuantity,
                inventoryItemCount(ctx, pendingFundingSellItem)
        );
        if (quantity <= 0) {
            clearPendingFundingSale();
            return;
        }

        log("Selling Crafting funding stock: " + quantity + "x " + pendingFundingSellItem
                + " at " + pendingFundingSellPrice + " each");
        boolean placed = ctx.grandExchange().placeSellOffer(pendingFundingSellItem, quantity, pendingFundingSellPrice);
        if (!placed) {
            log("Crafting funding sell offer was not placed; retrying " + pendingFundingSellItem);
            Time.sleep(1200, 1800);
            return;
        }

        pendingFundingSellOfferPlaced = true;
        nextPendingFundingSellCheckAt = System.currentTimeMillis() + 5_000L;
        Time.sleep(5000, 8000);
        collectSaleOffers(ctx);
        closeGrandExchangeAfterTrade(ctx, "Crafting funding sale");
    }

    private void withdrawFundingStockFromBank(APIContext ctx) {
        if (!isBankOpen(ctx)) {
            return;
        }

        if (!craftingFundingSaleInventoryIsClean(ctx)) {
            log("Clearing inventory before Crafting funding sale");
            ctx.bank().depositAllExcept(CRAFTING_FUNDING_SALE_KEEP_ITEMS);
            Time.sleep(600, 900);
            return;
        }

        int bankCount = bankItemCount(ctx, pendingFundingSellItem);
        int inventoryCount = inventoryItemCount(ctx, pendingFundingSellItem);
        if (bankCount <= 0) {
            if (inventoryCount > 0) {
                log("Crafting funding stock already in inventory: " + pendingFundingSellItem);
                closeBank(ctx);
                Time.sleep(600, 900);
                return;
            }

            log("Crafting funding stock no longer available: " + pendingFundingSellItem);
            clearPendingFundingSale();
            return;
        }

        if (inventoryCount >= pendingFundingSellQuantity) {
            closeBank(ctx);
            Time.sleep(600, 900);
            return;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            log("Selecting noted withdraw mode for Crafting funding stock");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
            return;
        }

        int quantity = Math.min(bankCount, pendingFundingSellQuantity - inventoryCount);
        log("Withdrawing " + quantity + "x " + pendingFundingSellItem + " as notes for Crafting funding");
        boolean withdrew = quantity >= bankCount
                ? ctx.bank().withdrawAll(pendingFundingSellItem)
                : ctx.bank().withdraw(quantity, pendingFundingSellItem);
        Time.sleep(600, 900);

        if (withdrew) {
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(400, 700, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
            closeBank(ctx);
            Time.sleep(600, 900);
        }
    }

    private boolean craftingFundingSaleInventoryIsClean(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (namesMatch(item.getName(), pendingFundingSellItem)
                    || matchesAnyName(item.getName(), CRAFTING_FUNDING_SALE_KEEP_ITEMS)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private void handlePendingBuy(APIContext ctx) {
        if (pendingBuyItem == null) {
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(500, 800);
            }
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        if (ctx.inventory().getCount(true, pendingBuyItem) >= Math.min(pendingBuyQuantity, 27)) {
            log("Crafting buy obtained in inventory: " + pendingBuyItem);
            clearPendingBuy();
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
            }
            Time.sleep(600, 900);
            return;
        }

        if (!isAtGrandExchange(ctx)) {
            log("Walking to GE for Crafting buy: " + pendingBuyItem);
            Navigation.walkToNoTeleports(ctx, grandExchangeArea().getRandomTile());
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE for Crafting buy: " + pendingBuyItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (confirmGeHighPriceWarning(ctx)) {
            return;
        }

        collectBuyOffers(ctx);
        if (ctx.inventory().getCount(true, pendingBuyItem) >= Math.min(pendingBuyQuantity, 27)) {
            log("Crafting buy collected: " + pendingBuyItem);
            clearPendingBuy();
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        long now = System.currentTimeMillis();
        if (now < pendingOfferCheckAt) {
            log("Waiting for Crafting buy offer: " + pendingBuyItem);
            Time.sleep(1000, 1600);
            return;
        }

        log("Buying Crafting material: " + pendingBuyQuantity + "x " + pendingBuyItem
                + " for " + pendingBuyPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, pendingBuyQuantity, pendingBuyPrice);
        pendingOfferCheckAt = now + GE_OFFER_CHECK_DELAY_MILLIS;
        Time.sleep(5000, 8000);
        if (!placed) {
            log("Crafting buy offer was not placed; retrying " + pendingBuyItem);
            return;
        }

        collectBuyOffers(ctx);
        clearPendingBuy();
        ctx.grandExchange().close();
        Time.sleep(600, 900);
    }

    private void finishCraftingCap(APIContext ctx) {
        if (!ctx.inventory().isEmpty()) {
            if (isBankOpen(ctx)) {
                log("Depositing inventory after Crafting cap");
                ctx.bank().depositInventory();
                Time.sleep(600, 900);
                ctx.bank().close();
                Time.sleep(500, 800);
                return;
            }

            openBankForCrafting(ctx, "depositing inventory after Crafting cap");
            Time.sleep(900, 1400);
            return;
        }

        log("Crafting cap reached: " + level(ctx, Skill.Skills.CRAFTING)
                + "/" + caps.capFor(Skill.Skills.CRAFTING));
        Time.sleep(1200, 1800);
    }

    private LeatherProduct chooseActiveProduct(APIContext ctx) {
        int craftingLevel = level(ctx, Skill.Skills.CRAFTING);
        LeatherProduct highest = highestUnlockedProduct(craftingLevel);
        if (highest == null) {
            return null;
        }

        if (activeProduct == null || activeProduct.level() > craftingLevel) {
            activeProduct = lowestUnlockedProduct(craftingLevel);
            activeProductBatchesLeft = randomInt(2, 4);
            return activeProduct;
        }

        if (highest.level() > activeProduct.level()) {
            updateUnlockAwareness(highest);
            if (pendingUnlockAwarenessBatches > 0) {
                return activeProduct;
            }
        }

        if (activeProductBatchesLeft <= 0) {
            activeProduct = chooseAwareProduct(craftingLevel);
            activeProductBatchesLeft = randomInt(2, 5);
            log("Crafting product selected: " + activeProduct.name()
                    + " for " + activeProductBatchesLeft + " batches");
        }

        return activeProduct;
    }

    private void updateUnlockAwareness(LeatherProduct unlockedProduct) {
        if (pendingUnlockAwarenessProduct == null
                || !pendingUnlockAwarenessProduct.name().equals(unlockedProduct.name())) {
            pendingUnlockAwarenessProduct = unlockedProduct;
            pendingUnlockAwarenessBatches = randomInt(2, 6);
            log("Crafting unlocked " + unlockedProduct.name()
                    + "; continuing old product for ~" + pendingUnlockAwarenessBatches + " batches");
        }
    }

    private LeatherProduct chooseAwareProduct(int craftingLevel) {
        List<LeatherProduct> options = new ArrayList<>();
        LeatherProduct highest = highestUnlockedProduct(craftingLevel);
        for (LeatherProduct product : PRODUCTS) {
            if (product.level() <= craftingLevel) {
                if (pendingUnlockAwarenessBatches > 0
                        && pendingUnlockAwarenessProduct != null
                        && product.level() >= pendingUnlockAwarenessProduct.level()) {
                    continue;
                }
                options.add(product);
                if (highest != null && product.name().equals(highest.name())) {
                    options.add(product);
                    options.add(product);
                }
            }
        }

        if (options.isEmpty()) {
            return lowestUnlockedProduct(craftingLevel);
        }
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private LeatherProduct highestUnlockedProduct(int craftingLevel) {
        LeatherProduct selected = null;
        for (LeatherProduct product : PRODUCTS) {
            if (product.level() <= craftingLevel) {
                selected = product;
            }
        }
        return selected;
    }

    private LeatherProduct lowestUnlockedProduct(int craftingLevel) {
        for (LeatherProduct product : PRODUCTS) {
            if (product.level() <= craftingLevel) {
                return product;
            }
        }
        return null;
    }

    private void finishCraftingBatch() {
        if (!craftingBatchActive) {
            return;
        }

        craftingBatchActive = false;
        lastLeatherCount = -1;
        activeProductBatchesLeft = Math.max(0, activeProductBatchesLeft - 1);
        if (pendingUnlockAwarenessBatches > 0) {
            pendingUnlockAwarenessBatches--;
            if (pendingUnlockAwarenessBatches == 0 && pendingUnlockAwarenessProduct != null) {
                log("Crafting noticed unlock: " + pendingUnlockAwarenessProduct.name());
            }
        }
    }

    private void trackCraftingProgress(APIContext ctx) {
        int leather = leatherCount(ctx);
        long now = System.currentTimeMillis();
        if (lastLeatherCount < 0 || leather != lastLeatherCount) {
            lastLeatherCount = leather;
            lastLeatherChangeAt = now;
            return;
        }

        if (craftingBatchActive && now - lastLeatherChangeAt >= 20_000L && now >= nextCraftingProgressLogAt) {
            log("Crafting batch active but Leather has not changed for "
                    + Math.max(1, Math.round((now - lastLeatherChangeAt) / 1000.0)) + "s");
            nextCraftingProgressLogAt = now + 12_000L;
        }
    }

    private void rememberLeatherCount(APIContext ctx) {
        lastLeatherCount = leatherCount(ctx);
        lastLeatherChangeAt = System.currentTimeMillis();
        nextCraftingProgressLogAt = lastLeatherChangeAt + 12_000L;
    }

    private boolean useNeedleOnLeather(APIContext ctx) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 65) {
            return selectInventoryItemForUse(ctx, NEEDLE) && useSelectedItemOnInventoryItem(ctx, LEATHER);
        }
        return selectInventoryItemForUse(ctx, LEATHER) && useSelectedItemOnInventoryItem(ctx, NEEDLE);
    }

    private boolean selectInventoryItemForUse(APIContext ctx, String itemName) {
        clearInventoryInteractionState(ctx);

        if (ctx.inventory().selectItem(itemName)) {
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
            if (ctx.inventory().isItemSelected()) {
                return true;
            }
        }

        if (ctx.menu().isOpen() && selectUseFromOpenMenu(ctx, itemName)) {
            return true;
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return selectUseFromOpenMenu(ctx, itemName);
        }
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean useSelectedItemOnInventoryItem(APIContext ctx, String itemName) {
        if (!ctx.inventory().isItemSelected()) {
            return false;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(350, 1000, () -> !ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return clickUseFromOpenMenu(ctx, itemName);
        }
        return clicked;
    }

    private boolean selectUseFromOpenMenu(APIContext ctx, String itemName) {
        boolean clicked = clickUseFromOpenMenu(ctx, itemName);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || !ctx.menu().isOpen(), 50);
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean clickUseFromOpenMenu(APIContext ctx, String itemName) {
        if (!ctx.menu().isOpen()) {
            return false;
        }

        boolean clicked = false;
        if (ctx.menu().contains("Use", itemName)) {
            clicked = ctx.menu().interact("Use", itemName, true);
        }
        if (!clicked && ctx.menu().contains("Use")) {
            clicked = ctx.menu().interact("Use", true);
        }
        Time.sleep(250, 700, () -> !ctx.menu().isOpen(), 50);
        return clicked;
    }

    private boolean craftingInterfaceOpen(APIContext ctx, LeatherProduct product) {
        return findLeatherProductWidget(ctx, product) != null
                || hasVisibleWidgetText(ctx, "What would you like to make?")
                || hasVisibleWidgetText(ctx, "How many would you like to make?")
                || hasVisibleWidgetText(ctx, "Choose a quantity");
    }

    private boolean clickLeatherProduct(APIContext ctx, LeatherProduct product) {
        WidgetChild widget = findLeatherProductWidget(ctx, product);
        if (widget == null) {
            logInterfaceRecovery("Leather product widget not found: " + product.name());
            return false;
        }

        log("Clicking Crafting product: " + product.name());
        if (clickWidgetCenter(ctx, widget) || clickWidget(ctx, widget)) {
            return true;
        }

        String[] actions = {"Make All", "Make-all", "Craft All", "Craft-all", "All", "Make", "Craft"};
        for (String action : actions) {
            if (widget.interact(action, product.name())
                    || widget.interact(action)
                    || ctx.menu().interact(action, product.name(), widget, true)
                    || ctx.menu().interact(action, widget, true)) {
                return true;
            }
        }
        return false;
    }

    private WidgetChild findLeatherProductWidget(APIContext ctx, LeatherProduct product) {
        WidgetChild byItemName = firstLeatherProductWidget(ctx.widgets()
                .query()
                .itemName(product.name())
                .results());
        if (byItemName != null) {
            return byItemName;
        }

        WidgetChild byText = firstLeatherProductWidget(ctx.widgets()
                .query()
                .textContains(product.name())
                .results());
        if (byText != null) {
            return byText;
        }

        WidgetChild firstSkillmultiItem = ctx.widgets().get(SKILLMULTI_GROUP, SKILLMULTI_FIRST_ITEM_CHILD);
        if (isLeatherProductWidget(firstSkillmultiItem)) {
            return firstSkillmultiItem;
        }

        return firstLeatherProductWidget(ctx.widgets()
                .query()
                .group(InterfaceID.CHATBOX, SKILLMULTI_GROUP)
                .results());
    }

    private WidgetChild firstLeatherProductWidget(Iterable<WidgetChild> widgets) {
        for (WidgetChild widget : widgets) {
            if (isLeatherProductWidget(widget)) {
                return widget;
            }
        }
        return null;
    }

    private boolean isLeatherProductWidget(WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        if (widget.getParentId() == INVENTORY_WIDGET_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == INVENTORY_WIDGET_GROUP)) {
            return false;
        }
        return widget.getItemId() > 0 || isSkillmultiWidget(widget) || isChatboxProductWidget(widget);
    }

    private boolean selectCraftingAllQuantity(APIContext ctx) {
        WidgetChild allWidget = findCraftingAllWidget(ctx);
        if (allWidget == null) {
            return false;
        }
        return clickWidgetCenter(ctx, allWidget) || clickWidget(ctx, allWidget);
    }

    private WidgetChild findCraftingAllWidget(APIContext ctx) {
        WidgetChild exactAll = ctx.widgets().get(SKILLMULTI_GROUP, SKILLMULTI_ALL_CHILD);
        if (isCraftingControlWidget(exactAll)) {
            return exactAll;
        }

        for (WidgetChild widget : ctx.widgets()
                .query()
                .group(SKILLMULTI_GROUP, InterfaceID.CHATBOX)
                .actions("All", "Make All", "Make-all", "Craft All", "Craft-all")
                .results()) {
            if (isCraftingControlWidget(widget)) {
                return widget;
            }
        }

        return firstCraftingControlWidget(ctx.widgets()
                .query()
                .text("All")
                .results());
    }

    private WidgetChild firstCraftingControlWidget(Iterable<WidgetChild> widgets) {
        for (WidgetChild widget : widgets) {
            if (isCraftingControlWidget(widget)) {
                return widget;
            }
        }
        return null;
    }

    private boolean isCraftingControlWidget(WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        if (widget.getParentId() == INVENTORY_WIDGET_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == INVENTORY_WIDGET_GROUP)) {
            return false;
        }
        String text = (widget.getText() + " " + widget.getRawText()).toLowerCase();
        return isSkillmultiWidget(widget)
                || text.contains("all")
                || widget.hasAction("All", "Make All", "Make-all", "Craft All", "Craft-all");
    }

    private boolean isSkillmultiWidget(WidgetChild widget) {
        return widget != null
                && (widget.getParentId() == SKILLMULTI_GROUP
                || (widget.getGroup() != null && widget.getGroup().getIndex() == SKILLMULTI_GROUP));
    }

    private boolean isChatboxProductWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getGroup() != null
                && widget.getGroup().getIndex() == InterfaceID.CHATBOX
                && widget.getItemId() > 0
                && widget.getWidth() >= 25
                && widget.getHeight() >= 20
                && widget.getAbsoluteY() >= 330
                && widget.getAbsoluteY() <= 510;
    }

    private boolean clearBlockingContinue(APIContext ctx) {
        if (!hasBlockingContinue(ctx)) {
            return false;
        }

        log("Closing continue/level-up before Crafting");
        if (ctx.dialogues().canContinue() && ctx.dialogues().selectContinue()) {
            Time.sleep(500, 850, () -> !hasBlockingContinue(ctx), 100);
            return true;
        }

        ctx.keyboard().typeKey(KeyEvent.VK_SPACE);
        Time.sleep(500, 850, () -> !hasBlockingContinue(ctx), 100);
        if (hasBlockingContinue(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(500, 850, () -> !hasBlockingContinue(ctx), 100);
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
        return null;
    }

    private boolean inventoryHasUnexpectedItems(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!namesMatch(item.getName(), NEEDLE)
                    && !namesMatch(item.getName(), THREAD)
                    && !namesMatch(item.getName(), LEATHER)
                    && !namesMatch(item.getName(), COINS)
                    && !isCraftingProduct(item.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCraftingProducts(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && isCraftingProduct(item.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCraftingProduct(String itemName) {
        for (LeatherProduct product : PRODUCTS) {
            if (namesMatch(itemName, product.name())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNeedle(APIContext ctx) {
        return ctx.inventory().contains(NEEDLE);
    }

    private int threadCount(APIContext ctx) {
        return ctx.inventory().getCount(true, THREAD);
    }

    private int leatherCount(APIContext ctx) {
        return ctx.inventory().getCount(true, LEATHER);
    }

    private int randomLeatherBuyQuantity() {
        return ThreadLocalRandom.current().nextInt(MIN_LEATHER_BATCH, MAX_LEATHER_BATCH + 1);
    }

    private int materialBuyPrice(APIContext ctx, String itemName) {
        return GePricing.exchangeQuickBuyPrice(ctx, itemName, 0L);
    }

    private boolean openBankForCrafting(APIContext ctx, String reason) {
        if (isBankOpen(ctx)) {
            return false;
        }

        if (!Navigation.isBankReachable(ctx)) {
            log("Walking to bank for Crafting: " + reason);
            Navigation.walkToBank(ctx);
            return true;
        }

        log("Opening bank for Crafting: " + reason);
        Navigation.openBank(ctx);
        return true;
    }

    private void collectBuyOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToBank();
            Time.sleep(600, 900);
        } catch (RuntimeException ignored) {
            // No ready offer is normal while waiting for a buy to fill.
        }
    }

    private void collectSaleOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(500, 800);
        } catch (RuntimeException ignored) {
            // Collection fails harmlessly when the sale is still pending.
        }
    }

    private void closeGrandExchangeAfterTrade(APIContext ctx, String reason) {
        if (!ctx.grandExchange().isOpen()) {
            return;
        }
        log("Closing GE after " + reason);
        ctx.grandExchange().close();
        Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
    }

    private void closeBank(APIContext ctx) {
        if (!isBankOpen(ctx)) {
            return;
        }
        ctx.bank().close();
        Time.sleep(500, 800, () -> !isBankOpen(ctx), 100);
    }

    private int knownCraftingFundingCoins(APIContext ctx) {
        int coins = ctx.inventory().getCount(true, COINS);
        if (isBankOpen(ctx)) {
            coins += ctx.bank().getCount(COINS);
        }
        return coins;
    }

    private int inventoryItemCount(APIContext ctx, String itemName) {
        return ctx.inventory().getCount(true, itemName);
    }

    private int bankItemCount(APIContext ctx, String itemName) {
        if (!isBankOpen(ctx)) {
            return 0;
        }

        int count = ctx.bank().getCount(itemName);
        if (count > 0) {
            return count;
        }

        for (ItemWidget item : ctx.bank().getItems()) {
            if (item != null && namesMatch(item.getName(), itemName)) {
                return Math.max(1, item.getStackSize());
            }
        }
        return bankHasItem(ctx, itemName) ? 1 : 0;
    }

    private int sellPriceFor(APIContext ctx, String itemName) {
        return GePricing.exchangeQuickSellPrice(ctx, itemName, 1L);
    }

    private boolean matchesAnyName(String itemName, String... allowedNames) {
        for (String allowedName : allowedNames) {
            if (namesMatch(itemName, allowedName)) {
                return true;
            }
        }
        return false;
    }

    private boolean confirmGeHighPriceWarning(APIContext ctx) {
        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null || !hasVisibleWidgetText(ctx, "much higher than the guide price")) {
            return false;
        }

        log("Confirming GE high-price warning for Crafting buy");
        if (clickWidgetCenter(ctx, yes)
                || ctx.mouse().click(yes, false)
                || yes.click(false)
                || yes.click()
                || ctx.menu().interact("Yes", yes, true)
                || ctx.menu().interact("Yes", true)
                || ctx.menu().interact("Yes")) {
            pendingOfferCheckAt = System.currentTimeMillis() + GE_OFFER_CHECK_DELAY_MILLIS;
            Time.sleep(1200, 1800);
            collectBuyOffers(ctx);
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
            return text.equalsIgnoreCase(widgetText == null ? "" : widgetText.trim())
                    || text.equalsIgnoreCase(rawText == null ? "" : rawText.trim());
        })) {
            return widget;
        }
        return null;
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
            return containsIgnoreCase(candidate.getText(), needle)
                    || containsIgnoreCase(candidate.getRawText(), needle);
        })) {
            return true;
        }
        return false;
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
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

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private Point inventoryItemCenter(ItemWidget item) {
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private void clearInventoryInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private void clearPendingBuy() {
        pendingBuyItem = null;
        pendingBuyQuantity = 0;
        pendingBuyPrice = 0;
        pendingOfferCheckAt = 0L;
    }

    private void clearPendingFundingSale() {
        pendingFundingSellItem = null;
        pendingFundingSellQuantity = 0;
        pendingFundingSellPrice = 0;
        pendingFundingSellOfferPlaced = false;
        nextPendingFundingSellCheckAt = 0L;
        activeFundingTargetItem = null;
        activeFundingTargetCoins = 0;
    }

    private void logInterfaceRecovery(String message) {
        long now = System.currentTimeMillis();
        if (now >= nextInterfaceRecoveryLogAt) {
            log(message);
            nextInterfaceRecoveryLogAt = now + 10_000L;
        }
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        return grandExchangeArea().contains(ctx.localPlayer().getLocation());
    }

    private com.epicbot.api.shared.model.Area grandExchangeArea() {
        return new com.epicbot.api.shared.model.Area(3160, 3478, 3175, 3490);
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }

    private record LeatherProduct(String name, int level) {
    }
}
