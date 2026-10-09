package org.example.modules.magic;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.ICombatAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Rune;
import com.epicbot.api.shared.model.RuneRequirement;
import com.epicbot.api.shared.model.Spell;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.funding.FundingPlanner;
import org.example.core.items.F2PItemRegistry;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;
import org.example.modules.combat.LumbridgeCowCombatModule;
import org.example.modules.moneymaking.BeerGlassCollectorModule;
import org.example.modules.skilling.FishingCookingModule;
import org.example.modules.skilling.MiningSmithingModule;

import java.awt.Point;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class MagicCombatTrainingModule implements ManagedF2PModule {
    private static final int MIN_DEFENCE_LEVEL = 15;
    private static final int MIN_CATCH_UP_TARGET_LEVEL = 13;
    private static final int CATCH_UP_PRIORITY_BOOST = 85;
    private static final int FIRE_STRIKE_LEVEL = 13;
    private static final String STAFF_OF_AIR = "Staff of air";
    private static final String MIND_RUNE = "Mind rune";
    private static final String FIRE_RUNE = "Fire rune";
    private static final String AMULET_OF_POWER = "Amulet of power";
    private static final String[] OPTIONAL_MAGIC_GEAR = {
            AMULET_OF_POWER, "Blue wizard hat", "Blue wizard robe", "Blue skirt"
    };
    private static final String[] BANK_KEEP_ITEMS = {
            "Coins", STAFF_OF_AIR, MIND_RUNE, FIRE_RUNE, AMULET_OF_POWER,
            "Blue wizard hat", "Blue wizard robe", "Blue skirt"
    };
    private static final IEquipmentAPI.Slot[] MAGIC_ARMOUR_SLOTS = {
            IEquipmentAPI.Slot.HELMET, IEquipmentAPI.Slot.BODY,
            IEquipmentAPI.Slot.LEGS, IEquipmentAPI.Slot.SHIELD, IEquipmentAPI.Slot.HANDS
    };
    private static final int TARGET_MIND_RUNES = 650;
    private static final int TARGET_ELEMENTAL_RUNE_CASTS = 200;
    private static final int MIN_MIND_RUNES_READY = 25;
    private static final int MAGIC_FUNDING_BUFFER_COINS = 800;
    private static final long AUTOCAST_TRUST_MILLIS = 8 * 60_000L;
    private static final Area GRAND_EXCHANGE_AREA = new Area(3160, 3478, 3175, 3490);
    private static final String[] MAGIC_FUNDING_SAFE_SELL_ITEMS = F2PItemRegistry.fundingSellItems();
    private static final String[] MAGIC_FUNDING_SALE_KEEP_ITEMS = {
            "Coins", STAFF_OF_AIR, MIND_RUNE, FIRE_RUNE
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final SkillCapManager caps;
    private final LumbridgeCowCombatModule combatModule;
    private final FundingPlanner fundingPlanner = new FundingPlanner();
    private final SkillCapManager fundingSkillCaps = new SkillCapManager();
    private final BeerGlassCollectorModule beerGlassFundingModule;
    private final FishingCookingModule fishingCookingFundingModule;
    private final MiningSmithingModule miningSmithingFundingModule;
    private String pendingBuyItem;
    private int pendingBuyQuantity;
    private int pendingBuyRequiredAvailable;
    private int pendingBuyPrice;
    private boolean pendingOfferPlaced;
    private long nextPendingOfferCheckAt;
    private boolean awaitingHighPriceConfirmation;
    private int highPriceConfirmationAttempts;
    private long highPriceConfirmationStartedAt;
    private long nextMissingGearLogAt;
    private boolean magicGearBankChecked;
    private String pendingFundingSellItem;
    private int pendingFundingSellQuantity;
    private int pendingFundingSellPrice;
    private boolean pendingFundingSellOfferPlaced;
    private long nextPendingFundingSellCheckAt;
    private FundingPlanner.Decision activeFundingDecision;
    private String activeFundingTargetItem;
    private int activeFundingTargetCoins;
    private Spell trustedAutocastSpell;
    private long trustedAutocastUntil;
    private long nextAutocastWarningAt;

    public MagicCombatTrainingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        this.logger = logger;
        this.stats = stats;
        this.caps = caps;
        this.combatModule = new LumbridgeCowCombatModule(
                logger, stats, LumbridgeCowCombatModule.TrainingMode.MAGIC, caps, this::prepareMagicCombat);
        this.beerGlassFundingModule = new BeerGlassCollectorModule(logger, stats);
        this.fishingCookingFundingModule = new FishingCookingModule(logger, stats, fundingSkillCaps, false, true);
        this.miningSmithingFundingModule = new MiningSmithingModule(logger, stats, fundingSkillCaps, false, true);
    }

    @Override
    public String name() {
        return "combat.magic";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx)
                && ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel() >= MIN_DEFENCE_LEVEL;
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);
        stats.setTrainingSkill("Magic");
        if (isComplete(ctx)) {
            stats.setStatus("Magic " + caps.capFor(Skill.Skills.MAGIC) + " reached");
            return;
        }
        if (handleMagicFundingPool(ctx) || handlePendingPurchase(ctx)) {
            return;
        }
        combatModule.execute(ctx);
    }

    private boolean prepareMagicCombat(APIContext ctx) {
        return prepareNormalMagicGearAndRunes(ctx)
                || equipNormalMagicGear(ctx)
                || ensureAutocast(ctx, combatSpell(ctx));
    }

    private Spell combatSpell(APIContext ctx) {
        return ctx.skills().get(Skill.Skills.MAGIC).getRealLevel() >= FIRE_STRIKE_LEVEL
                ? Spell.Modern.FIRE_STRIKE : Spell.Modern.WIND_STRIKE;
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return caps.isComplete(ctx, Skill.Skills.MAGIC);
    }

    @Override
    public boolean isProtectedSubphase(APIContext ctx) {
        return activeFundingDecision != null || pendingFundingSellItem != null
                || combatModule.isProtectedFundingSubphase();
    }

    @Override
    public String protectedSubphaseName(APIContext ctx) {
        String targetItem = activeFundingTargetItem == null || activeFundingTargetItem.isBlank()
                ? "Magic supplies"
                : activeFundingTargetItem;
        if (pendingFundingSellItem != null) {
            return "Magic stock sale for " + targetItem + ": " + pendingFundingSellItem;
        }
        if (activeFundingDecision != null) {
            return "Magic " + fundingMethodLabel(activeFundingDecision.method()) + " for " + targetItem;
        }
        return combatModule.isProtectedFundingSubphase()
                ? combatModule.protectedFundingLabel() : "Magic funding";
    }

    @Override
    public int priority(APIContext ctx) {
        int magicLevel = ctx.skills().get(Skill.Skills.MAGIC).getRealLevel();
        int remaining = caps.levelsRemaining(ctx, Skill.Skills.MAGIC);
        int catchUpTarget = catchUpTarget(ctx);
        if (magicLevel < catchUpTarget) {
            return CATCH_UP_PRIORITY_BOOST + Math.max(1, catchUpTarget - magicLevel);
        }
        return remaining;
    }

    private int catchUpTarget(APIContext ctx) {
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        if (defenceLevel < MIN_DEFENCE_LEVEL) {
            return 0;
        }

        int target = Math.max(MIN_CATCH_UP_TARGET_LEVEL, defenceLevel);
        return Math.min(caps.capFor(Skill.Skills.MAGIC), target);
    }

    private boolean prepareNormalMagicGearAndRunes(APIContext ctx) {
        Spell spell = combatSpell(ctx);
        if (hasMandatoryNormalMagicLoadout(ctx)
                && hasRequiredRuneStock(ctx, spell)
                && magicGearBankChecked) {
            if (isBankOpen(ctx)) {
                closeBank(ctx);
                Time.sleep(500, 800);
                return true;
            }
            return false;
        }

        if (!isBankOpen(ctx)) {
            if (closeGrandExchangeForBank(ctx, "Magic setup")) {
                return true;
            }
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for Magic setup");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Magic setup");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        ctx.bank().depositAllExcept(item -> matchesAny(item.getName(), BANK_KEEP_ITEMS)
                || matchesAny(item.getName(), F2PItemRegistry.foodItems()));
        Time.sleep(300, 500);

        if (ensureItemWithdrawMode(ctx)) {
            return true;
        }

        if (!hasCarriedOrEquipped(ctx, STAFF_OF_AIR)) {
            if (bankHasItem(ctx, STAFF_OF_AIR)) {
                log("Withdrawing Magic staff: " + STAFF_OF_AIR);
                withdrawOne(ctx, STAFF_OF_AIR);
                Time.sleep(500, 800);
                return true;
            }

            return startPurchaseFromBank(ctx, STAFF_OF_AIR, 1);
        }

        for (String gear : OPTIONAL_MAGIC_GEAR) {
            if (hasCarriedOrEquipped(ctx, gear)) {
                continue;
            }
            if (bankHasItem(ctx, gear)) {
                log("Withdrawing Magic gear: " + gear);
                withdrawOne(ctx, gear);
                Time.sleep(500, 800);
                return true;
            }

            if (shouldTryOptionalGearPurchase() && canAffordFromBankOrInventory(ctx, gear, 1)) {
                return startPurchaseFromBank(ctx, gear, 1);
            }

            logMissingOptionalGear(gear);
        }
        magicGearBankChecked = true;

        for (RuneRequirement requirement : consumableRunes(spell)) {
            String itemName = runeItemName(requirement);
            int held = ctx.inventory().getCount(true, itemName);
            if (held >= minimumRuneStock(requirement)) {
                continue;
            }
            int target = targetRuneStock(requirement);
            int bankRunes = ctx.bank().getCount(itemName);
            if (bankRunes > 0) {
                int amount = Math.min(bankRunes, target - held);
                log("Withdrawing " + amount + "x " + itemName + " for " + spell.getSpellName());
                ctx.bank().withdraw(amount, itemName);
                Time.sleep(500, 800);
                return true;
            }

            return startPurchaseFromBank(ctx, itemName, target - held, target);
        }

        closeBank(ctx);
        Time.sleep(500, 800);
        return true;
    }

    private boolean startPurchaseFromBank(APIContext ctx, String itemName, int quantity) {
        return startPurchaseFromBank(ctx, itemName, quantity, quantity);
    }

    private boolean startPurchaseFromBank(APIContext ctx, String itemName, int quantity, int requiredAvailable) {
        int price = buyPriceFor(ctx, itemName);
        int cost = price * quantity;
        int inventoryCoins = ctx.inventory().getCount(true, "Coins");
        int bankCoins = ctx.bank().getCount("Coins");

        if (inventoryCoins < cost && bankCoins > 0) {
            int toWithdraw = Math.min(bankCoins, cost - inventoryCoins);
            log("Withdrawing coins for Magic buy: " + toWithdraw
                    + " (" + quantity + "x " + itemName + " at " + price + " each = " + cost + ")");
            ctx.bank().withdraw(toWithdraw, "Coins");
            Time.sleep(500, 800);
            return true;
        }

        if (inventoryCoins + bankCoins < cost) {
            return startMagicFundingFromBank(ctx, itemName, cost, inventoryCoins, bankCoins);
        }

        pendingBuyItem = itemName;
        pendingBuyQuantity = quantity;
        pendingBuyRequiredAvailable = Math.max(1, requiredAvailable);
        pendingBuyPrice = price;
        pendingOfferPlaced = false;
        nextPendingOfferCheckAt = 0L;
        awaitingHighPriceConfirmation = false;
        highPriceConfirmationAttempts = 0;
        highPriceConfirmationStartedAt = 0L;
        log("Planning Magic buy: " + quantity + "x " + itemName
                + " at " + price + " each = " + cost + " gp");
        closeBank(ctx);
        Time.sleep(500, 800);
        return true;
    }

    private boolean handlePendingPurchase(APIContext ctx) {
        if (pendingBuyItem == null) {
            return false;
        }

        if (purchaseSatisfied(ctx)) {
            log("Magic purchase obtained: " + pendingBuyItem);
            clearPendingPurchase();
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(600, 900);
            }
            return true;
        }

        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(500, 800);
            return true;
        }

        if (!GRAND_EXCHANGE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE for Magic buy: " + pendingBuyItem);
            Navigation.walkToNoTeleports(ctx, GRAND_EXCHANGE_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return true;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE for Magic buy: " + pendingBuyItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (awaitingHighPriceConfirmation) {
            return handleHighPriceGeWarning(ctx);
        }

        if (hasGeHighPriceWarning(ctx)) {
            startHighPriceConfirmation();
            return true;
        }

        if (pendingOfferPlaced) {
            if (System.currentTimeMillis() < nextPendingOfferCheckAt) {
                Time.sleep(600, 900);
                return true;
            }

            collectGeOffers(ctx);
            nextPendingOfferCheckAt = System.currentTimeMillis() + 6_000L;
            return true;
        }

        pendingBuyPrice = Math.max(pendingBuyPrice, buyPriceFor(ctx, pendingBuyItem));
        log("Buying Magic setup item: " + pendingBuyQuantity + "x " + pendingBuyItem
                + " for " + pendingBuyPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingBuyItem, pendingBuyQuantity, pendingBuyPrice);
        if (!placed) {
            if (hasGeHighPriceWarning(ctx)) {
                startHighPriceConfirmation();
                return handleHighPriceGeWarning(ctx);
            }

            log("Magic buy offer was not placed; retrying " + pendingBuyItem);
            Time.sleep(1200, 1800);
            return true;
        }

        pendingOfferPlaced = true;
        nextPendingOfferCheckAt = System.currentTimeMillis() + 5_000L;
        Time.sleep(5000, 8000);
        collectGeOffers(ctx);
        return true;
    }

    private boolean startMagicFundingFromBank(
            APIContext ctx,
            String targetItem,
            int targetCost,
            int inventoryCoins,
            int bankCoins
    ) {
        activeFundingTargetItem = targetItem;
        activeFundingTargetCoins = targetCost + fundingPlanner.randomBufferCoins(MAGIC_FUNDING_BUFFER_COINS);

        FundingPlanner.Decision decision = chooseMagicFundingDecision(ctx);
        if (decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            return startMagicFundingStockSale(ctx, decision);
        }

        activeFundingDecision = decision;
        log("Magic funding pool selected " + fundingMethodLabel(decision.method())
                + " for " + targetItem + " (cost=" + targetCost
                + ", coins=" + (inventoryCoins + bankCoins)
                + ", target=" + activeFundingTargetCoins + ")");
        updateMagicFundingProgress(ctx);
        return true;
    }

    private boolean startMagicFundingStockSale(APIContext ctx, FundingPlanner.Decision decision) {
        activeFundingDecision = decision;
        pendingFundingSellItem = decision.itemName();
        pendingFundingSellQuantity = Math.max(1, decision.inventoryCount() + decision.bankCount());
        pendingFundingSellPrice = sellPriceFor(ctx, pendingFundingSellItem);
        pendingFundingSellOfferPlaced = false;
        nextPendingFundingSellCheckAt = 0L;
        log("Magic funding pool selected stock sale: " + pendingFundingSellQuantity
                + "x " + pendingFundingSellItem + " for " + activeFundingTargetItem);
        updateMagicFundingProgress(ctx);
        return withdrawFundingStockFromBank(ctx);
    }

    private boolean handleMagicFundingPool(APIContext ctx) {
        if (pendingFundingSellItem != null) {
            updateMagicFundingProgress(ctx);
            return handlePendingFundingSale(ctx);
        }

        if (activeFundingDecision == null) {
            return false;
        }

        updateMagicFundingProgress(ctx);
        if (isBankOpen(ctx)) {
            int knownCoins = knownMagicFundingCoins(ctx);
            if (knownCoins >= activeFundingTargetCoins) {
                log("Magic funding target reached for " + activeFundingTargetItem
                        + ": " + knownCoins + "/" + activeFundingTargetCoins);
                clearMagicFundingPool();
                closeBank(ctx);
                Time.sleep(600, 900);
                return true;
            }

            FundingPlanner.Decision decision = chooseMagicFundingDecision(ctx);
            if (decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
                return startMagicFundingStockSale(ctx, decision);
            }
        }

        executeMagicFundingMoneyMaker(ctx, activeFundingDecision);
        return true;
    }

    private boolean handlePendingFundingSale(APIContext ctx) {
        if (isBankOpen(ctx)) {
            return withdrawFundingStockFromBank(ctx);
        }

        if (ctx.inventory().getCount(true, pendingFundingSellItem) <= 0 && !pendingFundingSellOfferPlaced) {
            if (closeGrandExchangeForBank(ctx, "Magic funding stock bank")) {
                return true;
            }
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for Magic funding stock");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Magic funding stock");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        if (!GRAND_EXCHANGE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to sell Magic funding stock: " + pendingFundingSellItem);
            Navigation.walkToNoTeleports(ctx, GRAND_EXCHANGE_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return true;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to sell Magic funding stock: " + pendingFundingSellItem);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (pendingFundingSellOfferPlaced) {
            if (System.currentTimeMillis() < nextPendingFundingSellCheckAt) {
                Time.sleep(600, 900);
                return true;
            }

            collectGeOffers(ctx);
            if (ctx.inventory().getCount(true, pendingFundingSellItem) <= 0) {
                log("Magic funding sale collected: " + pendingFundingSellItem);
                clearPendingFundingSale();
                activeFundingDecision = null;
                closeGrandExchangeAfterTrade(ctx, "Magic funding sale collection");
            } else {
                nextPendingFundingSellCheckAt = System.currentTimeMillis() + 5_000L;
            }
            return true;
        }

        int quantity = Math.min(
                pendingFundingSellQuantity,
                ctx.inventory().getCount(true, pendingFundingSellItem)
        );
        if (quantity <= 0) {
            clearPendingFundingSale();
            return true;
        }

        log("Selling Magic funding stock: " + quantity + "x " + pendingFundingSellItem
                + " at " + pendingFundingSellPrice + " each");
        boolean placed = ctx.grandExchange().placeSellOffer(pendingFundingSellItem, quantity, pendingFundingSellPrice);
        if (!placed) {
            log("Magic funding sell offer was not placed; retrying " + pendingFundingSellItem);
            Time.sleep(1200, 1800);
            return true;
        }

        pendingFundingSellOfferPlaced = true;
        nextPendingFundingSellCheckAt = System.currentTimeMillis() + 5_000L;
        Time.sleep(5000, 8000);
        collectGeOffers(ctx);
        closeGrandExchangeAfterTrade(ctx, "Magic funding sale");
        return true;
    }

    private void executeMagicFundingMoneyMaker(APIContext ctx, FundingPlanner.Decision decision) {
        if (ctx.grandExchange().isOpen() && !fundingMoneyMakerCanUseGrandExchange(decision.method())) {
            log("Closing GE before Magic funding money maker: " + fundingMethodLabel(decision.method()));
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        log("Magic funding pool running " + fundingMethodLabel(decision.method())
                + " for " + activeFundingTargetItem);
        FundingPlanner.Method method = decision.method();
        if (method == FundingPlanner.Method.BEER_GLASS) {
            beerGlassFundingModule.execute(ctx);
        } else if (method == FundingPlanner.Method.MINING_SMITHING) {
            miningSmithingFundingModule.execute(ctx);
        } else if (method == FundingPlanner.Method.FISHING_COOKING) {
            log("Magic funding received Fishing/Cooking, which cannot fund GP; rerolling funding method");
            activeFundingDecision = null;
        } else if (method == FundingPlanner.Method.WOODCUTTING) {
            activeFundingDecision = new FundingPlanner.Decision(
                    FundingPlanner.Method.BEER_GLASS,
                    "Beer glass",
                    0,
                    0,
                    0L
            );
            log("Magic funding uses Beer glass fallback instead of WC inside Magic-only mode");
        } else {
            activeFundingDecision = null;
        }
    }

    private boolean fundingMoneyMakerCanUseGrandExchange(FundingPlanner.Method method) {
        return method == FundingPlanner.Method.MINING_SMITHING;
    }

    private boolean withdrawFundingStockFromBank(APIContext ctx) {
        if (!isBankOpen(ctx)) {
            return true;
        }

        if (!magicFundingSaleInventoryIsClean(ctx)) {
            log("Clearing inventory before Magic funding sale");
            ctx.bank().depositAllExcept(MAGIC_FUNDING_SALE_KEEP_ITEMS);
            Time.sleep(600, 900);
            return true;
        }

        int bankCount = ctx.bank().getCount(pendingFundingSellItem);
        if (bankCount <= 0) {
            if (ctx.inventory().getCount(true, pendingFundingSellItem) > 0) {
                log("Magic funding stock already in inventory: " + pendingFundingSellItem);
                closeBank(ctx);
                Time.sleep(600, 900);
                return true;
            }

            log("Magic funding stock no longer available: " + pendingFundingSellItem);
            clearPendingFundingSale();
            return true;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            log("Selecting noted withdraw mode for Magic funding stock");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
            return true;
        }

        int quantity = Math.min(bankCount, pendingFundingSellQuantity);
        log("Withdrawing " + quantity + "x " + pendingFundingSellItem + " as notes for Magic funding");
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
        return true;
    }

    private boolean magicFundingSaleInventoryIsClean(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (namesMatch(item.getName(), pendingFundingSellItem)
                    || matchesAny(item.getName(), MAGIC_FUNDING_SALE_KEEP_ITEMS)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private FundingPlanner.Decision chooseMagicFundingDecision(APIContext ctx) {
        int knownCoins = knownMagicFundingCoins(ctx);
        FundingPlanner.Decision decision = fundingPlanner.choose(
                activeFundingTargetCoins,
                knownCoins,
                magicFundingAssets(ctx)
        );
        if (decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            log("Magic FundingPlanner selected stock sale: "
                    + decision.itemName()
                    + " inv=" + decision.inventoryCount()
                    + " bank=" + decision.bankCount()
                    + " projected~" + decision.projectedValue() + "gp");
        } else {
            log("Magic FundingPlanner selected " + fundingMethodLabel(decision.method())
                    + " for " + activeFundingTargetItem);
        }
        updateMagicFundingProgress(ctx);
        return decision;
    }

    private java.util.List<FundingPlanner.Asset> magicFundingAssets(APIContext ctx) {
        java.util.List<FundingPlanner.Asset> assets = new java.util.ArrayList<>();
        for (String itemName : MAGIC_FUNDING_SAFE_SELL_ITEMS) {
            if (!F2PItemRegistry.isGeSellable(itemName)) {
                continue;
            }
            assets.add(new FundingPlanner.Asset(
                    itemName,
                    ctx.inventory().getCount(true, itemName),
                    isBankOpen(ctx) ? ctx.bank().getCount(itemName) : 0,
                    sellPriceFor(ctx, itemName)
            ));
        }
        return assets;
    }

    private int knownMagicFundingCoins(APIContext ctx) {
        int coins = ctx.inventory().getCount(true, "Coins");
        if (isBankOpen(ctx)) {
            coins += ctx.bank().getCount("Coins");
        }
        return coins;
    }

    private void updateMagicFundingProgress(APIContext ctx) {
        if (activeFundingTargetCoins <= 0) {
            return;
        }

        int knownCoins = knownMagicFundingCoins(ctx);
        stats.setFundingReason(
                magicFundingDisplayLabel(),
                knownCoins,
                activeFundingTargetCoins,
                projectedMagicFundingGp(ctx, knownCoins)
        );
    }

    private String magicFundingDisplayLabel() {
        String targetItem = activeFundingTargetItem == null || activeFundingTargetItem.isBlank()
                ? "target"
                : activeFundingTargetItem;
        if (pendingFundingSellItem != null) {
            return "Magic stock sale: " + pendingFundingSellItem + " for " + targetItem;
        }
        if (activeFundingDecision != null) {
            return "Magic " + fundingMethodLabel(activeFundingDecision.method()) + " for " + targetItem;
        }
        return "Magic funding for " + targetItem;
    }

    private long projectedMagicFundingGp(APIContext ctx, int knownCoins) {
        long projected = Math.max(0, knownCoins);
        for (FundingPlanner.Asset asset : magicFundingAssets(ctx)) {
            projected += (long) asset.totalCount() * asset.unitSellPrice();
        }
        return projected;
    }

    private String fundingMethodLabel(FundingPlanner.Method method) {
        if (method == FundingPlanner.Method.BEER_GLASS) {
            return "Beer glass";
        }
        if (method == FundingPlanner.Method.MINING_SMITHING) {
            return "Mining/Smithing";
        }
        if (method == FundingPlanner.Method.FISHING_COOKING) {
            return "Fishing/Cooking";
        }
        if (method == FundingPlanner.Method.WOODCUTTING) {
            return "Woodcutting";
        }
        if (method == FundingPlanner.Method.SELL_READY_STOCK) {
            return "stock sale";
        }
        return "funding";
    }

    private void clearMagicFundingPool() {
        activeFundingDecision = null;
        activeFundingTargetItem = null;
        activeFundingTargetCoins = 0;
        clearPendingFundingSale();
        stats.clearFundingReason();
    }

    private FundingStock bestFundingStock(APIContext ctx, int missingCoins) {
        int desiredCoins = Math.max(1, missingCoins + MAGIC_FUNDING_BUFFER_COINS);
        for (String itemName : MAGIC_FUNDING_SAFE_SELL_ITEMS) {
            int bankCount = ctx.bank().getCount(itemName);
            if (bankCount <= 0) {
                continue;
            }

            int sellPrice = sellPriceFor(ctx, itemName);
            int quantity = Math.max(1, (int) Math.ceil(desiredCoins / (double) sellPrice));
            quantity = Math.min(bankCount, quantity);
            return new FundingStock(itemName, quantity, sellPrice);
        }
        return null;
    }

    private void clearPendingFundingSale() {
        pendingFundingSellItem = null;
        pendingFundingSellQuantity = 0;
        pendingFundingSellPrice = 0;
        pendingFundingSellOfferPlaced = false;
        nextPendingFundingSellCheckAt = 0L;
    }

    private void collectGeOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(500, 800);
        } catch (RuntimeException ignored) {
            // Collection fails harmlessly when the offer is not ready yet.
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

    private boolean closeGrandExchangeForBank(APIContext ctx, String reason) {
        if (!ctx.grandExchange().isOpen()) {
            return false;
        }
        log("Closing GE before opening bank for " + reason);
        ctx.grandExchange().close();
        Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
        return true;
    }

    private boolean purchaseSatisfied(APIContext ctx) {
        if (pendingBuyItem == null) {
            return true;
        }

        if (isRune(pendingBuyItem)) {
            int required = Math.max(1, pendingBuyRequiredAvailable);
            int available = ctx.inventory().getCount(true, pendingBuyItem)
                    + (isBankOpen(ctx) ? ctx.bank().getCount(pendingBuyItem) : 0);
            return available >= required;
        }

        return hasCarriedOrEquipped(ctx, pendingBuyItem)
                || (isBankOpen(ctx) && bankHasItem(ctx, pendingBuyItem));
    }

    private void clearPendingPurchase() {
        pendingBuyItem = null;
        pendingBuyQuantity = 0;
        pendingBuyRequiredAvailable = 0;
        pendingBuyPrice = 0;
        pendingOfferPlaced = false;
        nextPendingOfferCheckAt = 0L;
        awaitingHighPriceConfirmation = false;
        highPriceConfirmationAttempts = 0;
        highPriceConfirmationStartedAt = 0L;
    }

    private boolean equipNormalMagicGear(APIContext ctx) {
        if (ctx.inventory().isFull()) {
            magicGearBankChecked = false;
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return true;
        }

        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(500, 800);
            return true;
        }

        if (removeNonMagicArmour(ctx)) {
            return true;
        }

        if (!ctx.equipment().contains(IEquipmentAPI.Slot.WEAPON, STAFF_OF_AIR)) {
            if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
                log("Opening inventory for Magic gear");
                ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
                Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
                return true;
            }

            if (ctx.inventory().contains(STAFF_OF_AIR)) {
                return equipInventoryItem(ctx, STAFF_OF_AIR, "Wield");
            }
        }

        for (String gear : OPTIONAL_MAGIC_GEAR) {
            if (!ctx.equipment().contains(gear) && ctx.inventory().contains(gear)) {
                if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
                    log("Opening inventory for optional Magic gear");
                    ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
                    Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
                    return true;
                }
                return equipInventoryItem(ctx, gear, "Wear");
            }
        }

        return false;
    }

    private boolean removeNonMagicArmour(APIContext ctx) {
        for (IEquipmentAPI.Slot slot : MAGIC_ARMOUR_SLOTS) {
            ItemWidget equipped = ctx.equipment().getItem(slot);
            if (equipped == null || matchesAny(equipped.getName(), OPTIONAL_MAGIC_GEAR)) {
                continue;
            }

            if (!ctx.tabs().isOpen(ITabsAPI.Tabs.EQUIPMENT)) {
                ctx.tabs().open(ITabsAPI.Tabs.EQUIPMENT);
                Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.EQUIPMENT), 100);
                return true;
            }

            String itemName = equipped.getName();
            log("Removing non-Magic armour: " + itemName);
            equipped.interact("Remove");
            Time.sleep(600, 1200, () -> !ctx.equipment().contains(itemName), 100);
            return true;
        }
        return false;
    }

    private boolean equipInventoryItem(APIContext ctx, String itemName, String preferredAction) {
        ItemWidget widget = ctx.inventory().getItem(item ->
                item != null && namesMatch(item.getName(), itemName));
        if (widget == null) {
            return false;
        }

        int beforeInventoryCount = ctx.inventory().getCount(true, itemName);
        log("Equipping Magic item: " + itemName);
        boolean interacted = widget.interact(preferredAction, itemName)
                || widget.interact(preferredAction)
                || widget.interact("Wield", itemName)
                || widget.interact("Wield")
                || widget.interact("Wear", itemName)
                || widget.interact("Wear")
                || widget.interact("Equip", itemName)
                || widget.interact("Equip")
                || ctx.inventory().interactItem(preferredAction, itemName);
        if (!interacted) {
            Time.sleep(600, 900);
            return true;
        }

        Time.sleep(
                700,
                1600,
                () -> ctx.equipment().contains(itemName)
                        || ctx.inventory().getCount(true, itemName) < beforeInventoryCount,
                100
        );
        return true;
    }

    private boolean ensureAutocast(APIContext ctx, Spell spell) {
        if (autocastReady(ctx, spell)) {
            return false;
        }

        if (ctx.localPlayer().isInCombat()) {
            log("Waiting to set " + spell.getSpellName() + " autocast after combat");
            Time.sleep(600, 900);
            return true;
        }

        log("Setting autocast: " + spell.getSpellName());
        boolean set = ctx.magic().setAutoCast(spell, false)
                || ctx.combat().toggleAttackStyle(ICombatAPI.AttackStyle.CASTING, spell);
        Time.sleep(
                1400,
                2300,
                () -> autocastReady(ctx, spell),
                100
        );

        if (autocastReady(ctx, spell)) {
            trustedAutocastSpell = spell;
            trustedAutocastUntil = System.currentTimeMillis() + AUTOCAST_TRUST_MILLIS;
            return false;
        }

        if (set && ctx.magic().getAutoCastSpell() == null && combatCastingStyleSelected(ctx)) {
            trustedAutocastSpell = spell;
            trustedAutocastUntil = System.currentTimeMillis() + AUTOCAST_TRUST_MILLIS;
            log("Autocast visual/style accepted for " + spell.getSpellName()
                    + "; continuing despite API spell confirmation lag");
            return false;
        }

        long now = System.currentTimeMillis();
        if (now >= nextAutocastWarningAt) {
            log("Autocast API did not confirm " + spell.getSpellName() + "; retrying");
            nextAutocastWarningAt = now + 10_000L;
        }
        return true;
    }

    private boolean autocastReady(APIContext ctx, Spell spell) {
        Spell selected = ctx.magic().getAutoCastSpell();
        if (selected == spell && combatCastingStyleSelected(ctx)) {
            trustedAutocastSpell = spell;
            trustedAutocastUntil = System.currentTimeMillis() + AUTOCAST_TRUST_MILLIS;
            return true;
        }

        if (selected != null && selected != spell) {
            trustedAutocastSpell = null;
            trustedAutocastUntil = 0L;
            return false;
        }

        return trustedAutocastSpell == spell
                && System.currentTimeMillis() < trustedAutocastUntil
                && combatCastingStyleSelected(ctx);
    }

    private boolean combatCastingStyleSelected(APIContext ctx) {
        ICombatAPI.AttackStyle style = ctx.combat().getAttackStyle();
        return style == ICombatAPI.AttackStyle.CASTING;
    }

    private boolean hasMandatoryNormalMagicLoadout(APIContext ctx) {
        return hasCarriedOrEquipped(ctx, STAFF_OF_AIR);
    }

    private boolean isRune(String itemName) {
        return namesMatch(itemName, MIND_RUNE) || namesMatch(itemName, FIRE_RUNE);
    }

    private boolean hasCarriedOrEquipped(APIContext ctx, String itemName) {
        return ctx.inventory().contains(itemName) || ctx.equipment().contains(itemName);
    }

    private RuneRequirement[] consumableRunes(Spell spell) {
        // The mandatory Staff of air supplies all AIR requirements.
        return Arrays.stream(spell.getRunes())
                .filter(requirement -> requirement.getRune() != Rune.AIR)
                .toArray(RuneRequirement[]::new);
    }

    private String runeItemName(RuneRequirement requirement) {
        return switch (requirement.getRune()) {
            case MIND -> MIND_RUNE;
            case FIRE -> FIRE_RUNE;
            default -> throw new IllegalArgumentException("Unsupported combat rune: " + requirement.getRune());
        };
    }

    private int minimumRuneStock(RuneRequirement requirement) {
        return MIN_MIND_RUNES_READY * requirement.getAmount();
    }

    private int targetRuneStock(RuneRequirement requirement) {
        return (requirement.getRune() == Rune.MIND ? TARGET_MIND_RUNES : TARGET_ELEMENTAL_RUNE_CASTS)
                * requirement.getAmount();
    }

    private boolean hasRequiredRuneStock(APIContext ctx, Spell spell) {
        for (RuneRequirement requirement : consumableRunes(spell)) {
            if (ctx.inventory().getCount(true, runeItemName(requirement)) < minimumRuneStock(requirement)) {
                return false;
            }
        }
        return true;
    }

    private boolean bankHasItem(APIContext ctx, String itemName) {
        if (ctx.bank().contains(itemName) || ctx.bank().getItem(itemName) != null) {
            return true;
        }

        for (ItemWidget item : ctx.bank().getItems()) {
            if (item != null && namesMatch(item.getName(), itemName)) {
                return true;
            }
        }
        return false;
    }

    private boolean withdrawOne(APIContext ctx, String itemName) {
        return ctx.bank().withdraw(1, itemName)
                || ctx.bank().withdrawAny(1, itemName)
                || ctx.bank().interactItem("Withdraw-1", itemName)
                || ctx.bank().interactItem("Withdraw 1", itemName)
                || ctx.bank().interactItem("Withdraw", itemName);
    }

    private boolean ensureItemWithdrawMode(APIContext ctx) {
        if (isBankOpen(ctx) && !ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            log("Restoring item withdraw mode for Magic setup");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
            return true;
        }
        return false;
    }

    private boolean shouldTryOptionalGearPurchase() {
        return System.currentTimeMillis() >= nextMissingGearLogAt;
    }

    private boolean canAffordFromBankOrInventory(APIContext ctx, String itemName, int quantity) {
        int cost = buyPriceFor(ctx, itemName) * quantity;
        return ctx.inventory().getCount(true, "Coins") + ctx.bank().getCount("Coins") >= cost;
    }

    private void logMissingOptionalGear(String itemName) {
        if (System.currentTimeMillis() < nextMissingGearLogAt) {
            return;
        }

        nextMissingGearLogAt = System.currentTimeMillis() + 60_000L;
        log("Optional Magic gear missing; continuing without blocking if needed: " + itemName);
    }

    private int buyPriceFor(APIContext ctx, String itemName) {
        return GePricing.quickBuyPrice(ctx, itemName, fallbackBuyPrice(itemName));
    }

    private int sellPriceFor(APIContext ctx, String itemName) {
        return GePricing.quickSellPrice(ctx, itemName, fallbackSellPrice(itemName));
    }

    private long fallbackSellPrice(String itemName) {
        String normalized = normalizedName(itemName);
        if (normalized.equals("beerglass")) {
            return 20L;
        }
        if (normalized.equals("bronzebar")) {
            return 180L;
        }
        if (normalized.equals("logs")) {
            return 20L;
        }
        if (normalized.equals("oaklogs")) {
            return 30L;
        }
        if (normalized.equals("willowlogs")) {
            return 20L;
        }
        return 10L;
    }

    private long fallbackBuyPrice(String itemName) {
        String normalized = normalizedName(itemName);
        if (normalized.equals("mindrune")) {
            return 4L;
        }
        if (normalized.equals("firerune")) {
            return 5L;
        }
        if (normalized.equals("staffofair")) {
            return 1500L;
        }
        if (normalized.equals("amuletofpower")) {
            return 3000L;
        }
        if (normalized.contains("bluewizard")) {
            return 300L;
        }
        if (normalized.equals("blueskirt")) {
            return 250L;
        }
        return 500L;
    }

    private void startHighPriceConfirmation() {
        awaitingHighPriceConfirmation = true;
        if (highPriceConfirmationStartedAt <= 0L) {
            highPriceConfirmationStartedAt = System.currentTimeMillis();
            highPriceConfirmationAttempts = 0;
        }
    }

    private boolean handleHighPriceGeWarning(APIContext ctx) {
        if (!hasGeHighPriceWarning(ctx)) {
            awaitingHighPriceConfirmation = false;
            pendingOfferPlaced = true;
            nextPendingOfferCheckAt = System.currentTimeMillis() + 5_000L;
            collectGeOffers(ctx);
            return true;
        }

        if (highPriceConfirmationAttempts >= 5
                || System.currentTimeMillis() - highPriceConfirmationStartedAt > 20_000L) {
            stats.recordRecoverableError("Magic GE high-price warning stuck; reopening GE");
            awaitingHighPriceConfirmation = false;
            pendingOfferPlaced = false;
            highPriceConfirmationAttempts = 0;
            highPriceConfirmationStartedAt = 0L;
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
                Time.sleep(800, 1200, () -> !ctx.grandExchange().isOpen(), 100);
            } else {
                Time.sleep(800, 1200);
            }
            return true;
        }

        WidgetChild yes = findVisibleWidgetByText(ctx, "Yes");
        if (yes == null) {
            highPriceConfirmationAttempts++;
            Time.sleep(600, 900);
            return true;
        }

        log("Confirming GE high-price warning for Magic buy");
        highPriceConfirmationAttempts++;
        Point point = yes.getCentralPoint();
        boolean clicked = point != null && ctx.mouse().click(point, false);
        if (!clicked) {
            clicked = yes.click(false) || yes.click() || ctx.menu().interact("Yes", yes, true);
        }
        if (clicked) {
            awaitingHighPriceConfirmation = false;
            pendingOfferPlaced = true;
            nextPendingOfferCheckAt = System.currentTimeMillis() + 5_000L;
            Time.sleep(1200, 1800);
            collectGeOffers(ctx);
            return true;
        }
        Time.sleep(600, 900);
        return true;
    }

    private boolean hasGeHighPriceWarning(APIContext ctx) {
        return hasVisibleWidgetText(ctx, "much higher than the guide price");
    }

    private boolean isBankOpen(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            return true;
        }

        return ctx.widgets().isInterfaceOpen()
                && (hasVisibleWidgetText(ctx, "The Bank of Gielinor")
                || hasVisibleWidgetText(ctx, "Bank of Gielinor"));
    }

    private void closeBank(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            if (ctx.bank().close()) {
                return;
            }
        }

        if (ctx.widgets().isInterfaceOpen()) {
            ctx.widgets().closeInterface();
        }
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

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private boolean matchesAny(String itemName, String[] names) {
        for (String name : names) {
            if (namesMatch(itemName, name)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }

    private record FundingStock(String itemName, int quantity, int sellPrice) {
    }
}
