package org.example.modules.magic;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.ICombatAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Spell;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.funding.FundingPlanner;
import org.example.core.items.F2PItemRegistry;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;
import org.example.modules.moneymaking.BeerGlassCollectorModule;
import org.example.modules.skilling.FishingCookingModule;
import org.example.modules.skilling.MiningSmithingModule;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class MagicSplashingModule implements ManagedF2PModule {
    public static final int NORMAL_MAGIC_CAP = 13;
    public static final int FUTURE_SPLASH_CAP = 40;

    private static final Spell NORMAL_SPELL = Spell.Modern.WIND_STRIKE;
    private static final Spell FIRE_SPLASH_SPELL = Spell.Modern.FIRE_STRIKE;
    private static final Spell CURSE_SPLASH_SPELL = Spell.Modern.CURSE;
    private static final int CURSE_SPLASH_MAGIC_LEVEL = 19;
    private static final String STAFF_OF_AIR = "Staff of air";
    private static final String CURSED_GOBLIN_STAFF = "Cursed goblin staff";
    private static final String MIND_RUNE = "Mind rune";
    private static final String AIR_RUNE = "Air rune";
    private static final String FIRE_RUNE = "Fire rune";
    private static final String WATER_RUNE = "Water rune";
    private static final String EARTH_RUNE = "Earth rune";
    private static final String BODY_RUNE = "Body rune";
    private static final String AMULET_OF_POWER = "Amulet of power";
    private static final String[] OPTIONAL_MAGIC_GEAR = {
            AMULET_OF_POWER,
            "Blue wizard hat",
            "Blue wizard robe",
            "Blue skirt"
    };
    private static final String[] BANK_KEEP_ITEMS = {
            "Coins",
            STAFF_OF_AIR,
            CURSED_GOBLIN_STAFF,
            MIND_RUNE,
            AIR_RUNE,
            FIRE_RUNE,
            WATER_RUNE,
            EARTH_RUNE,
            BODY_RUNE,
            AMULET_OF_POWER,
            "Blue wizard hat",
            "Blue wizard robe",
            "Blue skirt",
            "Bronze full helm",
            "Iron full helm",
            "Steel full helm",
            "Black full helm",
            "Mithril full helm",
            "Adamant full helm",
            "Rune full helm",
            "Bronze platebody",
            "Iron platebody",
            "Steel platebody",
            "Black platebody",
            "Mithril platebody",
            "Adamant platebody",
            "Rune platebody",
            "Bronze platelegs",
            "Iron platelegs",
            "Steel platelegs",
            "Black platelegs",
            "Mithril platelegs",
            "Adamant platelegs",
            "Rune platelegs",
            "Bronze kiteshield",
            "Iron kiteshield",
            "Steel kiteshield",
            "Black kiteshield",
            "Mithril kiteshield",
            "Adamant kiteshield",
            "Rune kiteshield"
    };
    private static final SplashGearSlot[] SPLASH_GEAR = {
            new SplashGearSlot(IEquipmentAPI.Slot.WEAPON, "Wield", new SplashGearItem[]{
                    new SplashGearItem(CURSED_GOBLIN_STAFF, 1)
            }),
            new SplashGearSlot(IEquipmentAPI.Slot.HELMET, "Wear", new SplashGearItem[]{
                    new SplashGearItem("Bronze full helm", 1),
                    new SplashGearItem("Iron full helm", 1),
                    new SplashGearItem("Steel full helm", 5),
                    new SplashGearItem("Black full helm", 10),
                    new SplashGearItem("Mithril full helm", 20),
                    new SplashGearItem("Adamant full helm", 30),
                    new SplashGearItem("Rune full helm", 40)
            }),
            new SplashGearSlot(IEquipmentAPI.Slot.BODY, "Wear", new SplashGearItem[]{
                    new SplashGearItem("Bronze platebody", 1),
                    new SplashGearItem("Iron platebody", 1),
                    new SplashGearItem("Steel platebody", 5),
                    new SplashGearItem("Black platebody", 10),
                    new SplashGearItem("Mithril platebody", 20),
                    new SplashGearItem("Adamant platebody", 30),
                    new SplashGearItem("Rune platebody", 40)
            }),
            new SplashGearSlot(IEquipmentAPI.Slot.LEGS, "Wear", new SplashGearItem[]{
                    new SplashGearItem("Bronze platelegs", 1),
                    new SplashGearItem("Iron platelegs", 1),
                    new SplashGearItem("Steel platelegs", 5),
                    new SplashGearItem("Black platelegs", 10),
                    new SplashGearItem("Mithril platelegs", 20),
                    new SplashGearItem("Adamant platelegs", 30),
                    new SplashGearItem("Rune platelegs", 40)
            }),
            new SplashGearSlot(IEquipmentAPI.Slot.SHIELD, "Wield", new SplashGearItem[]{
                    new SplashGearItem("Bronze kiteshield", 1),
                    new SplashGearItem("Iron kiteshield", 1),
                    new SplashGearItem("Steel kiteshield", 5),
                    new SplashGearItem("Black kiteshield", 10),
                    new SplashGearItem("Mithril kiteshield", 20),
                    new SplashGearItem("Adamant kiteshield", 30),
                    new SplashGearItem("Rune kiteshield", 40)
            })
    };
    private static final String[] POSITIVE_SPLASH_GEAR = {
            AMULET_OF_POWER,
            "Amulet of magic",
            "Blue wizard hat",
            "Blue wizard robe",
            "Blue skirt"
    };
    private static final int TARGET_MIND_RUNES = 650;
    private static final int MIN_MIND_RUNES_READY = 25;
    private static final int MIN_SPLASH_CASTS_READY = 60;
    private static final int MIN_SPLASH_TARGET_CASTS = 180;
    private static final int MAX_SPLASH_TARGET_CASTS = 280;
    private static final int CURSED_STAFF_DIANGO_COINS = 45;
    private static final int MAGIC_FUNDING_BUFFER_COINS = 800;
    private static final long SPLASH_TARGET_BLOCK_MILLIS = 20_000L;
    private static final Area CHICKEN_AREA = new Area(
            new Tile(3226, 3301, 0),
            new Tile(3225, 3299, 0),
            new Tile(3225, 3295, 0),
            new Tile(3235, 3294, 0),
            new Tile(3236, 3294, 0),
            new Tile(3235, 3301, 0),
            new Tile(3226, 3301, 0)
    );
    private static final Area DIANGO_AREA = new Area(3078, 3244, 3085, 3252);
    private static final Area PORT_SARIM_SEAGULL_AREA = new Area(3017, 3225, 3044, 3241);
    private static final Area GRAND_EXCHANGE_AREA = new Area(3160, 3478, 3175, 3490);
    private static final String[] MAGIC_FUNDING_SAFE_SELL_ITEMS = F2PItemRegistry.fundingSellItems();
    private static final String[] MAGIC_FUNDING_SALE_KEEP_ITEMS = {
            "Coins",
            STAFF_OF_AIR,
            CURSED_GOBLIN_STAFF,
            MIND_RUNE,
            AIR_RUNE,
            FIRE_RUNE,
            WATER_RUNE,
            EARTH_RUNE,
            BODY_RUNE
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
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
    private long nextTargetDebugAt;
    private Tile lastSplashTargetTile;
    private Tile blockedSplashTargetTile;
    private long blockedSplashTargetUntil;
    private boolean magicGearBankChecked;
    private boolean splashGearBankChecked;
    private boolean cursedStaffBankAudited;
    private String pendingFundingSellItem;
    private int pendingFundingSellQuantity;
    private int pendingFundingSellPrice;
    private boolean pendingFundingSellOfferPlaced;
    private long nextPendingFundingSellCheckAt;
    private FundingPlanner.Decision activeFundingDecision;
    private String activeFundingTargetItem;
    private int activeFundingTargetCoins;
    private Spell activeSplashBatchSpell;
    private int activeSplashBatchTargetCasts;

    public MagicSplashingModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
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
        return !isComplete(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);
        stats.setTrainingSkill("Magic");

        int magicLevel = ctx.skills().get(Skill.Skills.MAGIC).getRealLevel();
        if (magicLevel >= FUTURE_SPLASH_CAP) {
            stats.setStatus("Magic " + FUTURE_SPLASH_CAP + " reached");
            return;
        }

        if (handleMagicFundingPool(ctx)) {
            return;
        }

        if (handlePendingPurchase(ctx)) {
            return;
        }

        if (magicLevel < NORMAL_MAGIC_CAP) {
            if (prepareNormalMagicGearAndRunes(ctx)) {
                return;
            }

            if (equipNormalMagicGear(ctx)) {
                return;
            }

            if (ensureAutocast(ctx, NORMAL_SPELL)) {
                return;
            }

            trainWindStrike(ctx);
            return;
        }

        Spell splashSpell = splashSpellForLevel(magicLevel);
        if (prepareSplashingGearAndRunes(ctx, splashSpell)) {
            return;
        }

        if ((!isManualSplashSelected(ctx, splashSpell)) && equipSplashingGear(ctx)) {
            return;
        }

        if (usesAutocastSplash(splashSpell)) {
            if (ensureAutocast(ctx, splashSpell)) {
                return;
            }

            splashAutoCastSpell(ctx, splashSpell);
        } else {
            splashManualSpell(ctx, splashSpell);
        }
    }

    private boolean isManualSplashSelected(APIContext ctx, Spell spell) {
        return !usesAutocastSplash(spell) && ctx.magic().isSpellSelected();
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return ctx.skills().get(Skill.Skills.MAGIC).getRealLevel() >= FUTURE_SPLASH_CAP;
    }

    @Override
    public int priority(APIContext ctx) {
        return Math.max(0, FUTURE_SPLASH_CAP - ctx.skills().get(Skill.Skills.MAGIC).getRealLevel());
    }

    private boolean prepareNormalMagicGearAndRunes(APIContext ctx) {
        if (hasMandatoryNormalMagicLoadout(ctx)
                && inventoryMindRunes(ctx) >= MIN_MIND_RUNES_READY
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
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for Magic setup");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Magic setup");
            ctx.bank().open();
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        ctx.bank().depositAllExcept(BANK_KEEP_ITEMS);
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

        int mindRunes = inventoryMindRunes(ctx);
        if (mindRunes < MIN_MIND_RUNES_READY) {
            int bankRunes = ctx.bank().getCount(MIND_RUNE);
            if (bankRunes > 0) {
                int amount = Math.min(bankRunes, Math.max(MIN_MIND_RUNES_READY, TARGET_MIND_RUNES - mindRunes));
                log("Withdrawing " + amount + "x " + MIND_RUNE + " for Wind Strike");
                ctx.bank().withdraw(amount, MIND_RUNE);
                Time.sleep(500, 800);
                return true;
            }

            return startPurchaseFromBank(ctx, MIND_RUNE, TARGET_MIND_RUNES);
        }

        closeBank(ctx);
        Time.sleep(500, 800);
        return true;
    }

    private boolean prepareSplashingGearAndRunes(APIContext ctx, Spell splashSpell) {
        if (hasSplashingSetupReadyToEquip(ctx, splashSpell)) {
            if (isBankOpen(ctx)) {
                log("Magic splashing setup ready; closing bank to equip gear");
                closeBank(ctx);
                Time.sleep(500, 800);
                return true;
            }
            return false;
        }

        if (hasSplashingLoadout(ctx) && hasEnoughForSplashCast(ctx, splashSpell) && splashGearBankChecked) {
            if (isBankOpen(ctx)) {
                log("Splash inventory can still cast; closing bank and resuming");
                closeBank(ctx);
                Time.sleep(500, 800);
                return true;
            }
            return false;
        }

        if (hasSplashingLoadout(ctx) && hasSplashRuneBatchReady(ctx, splashSpell) && splashGearBankChecked) {
            if (isBankOpen(ctx)) {
                closeBank(ctx);
                Time.sleep(500, 800);
                return true;
            }
            return false;
        }

        if (!isBankOpen(ctx)) {
            if (!hasCarriedOrEquipped(ctx, CURSED_GOBLIN_STAFF) && cursedStaffBankAudited) {
                return obtainCursedGoblinStaff(ctx);
            }
            if (closeGrandExchangeForBank(ctx, "Magic splashing setup")) {
                return true;
            }
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for Magic splashing setup");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Magic splashing setup");
            ctx.bank().open();
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        ctx.bank().depositAllExcept(BANK_KEEP_ITEMS);
        Time.sleep(300, 500);

        if (ensureItemWithdrawMode(ctx)) {
            return true;
        }

        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        for (SplashGearSlot slot : SPLASH_GEAR) {
            if (hasCarriedOrEquippedSplashSlot(ctx, slot, defenceLevel)) {
                continue;
            }

            SplashGearItem bankedGear = bestAvailableSplashGear(ctx, slot, defenceLevel, true);
            if (bankedGear != null) {
                if (slot.slot() == IEquipmentAPI.Slot.WEAPON) {
                    cursedStaffBankAudited = false;
                }
                log("Withdrawing splash gear: " + bankedGear.name());
                withdrawOne(ctx, bankedGear.name());
                Time.sleep(500, 800);
                return true;
            }

            if (slot.slot() == IEquipmentAPI.Slot.WEAPON) {
                cursedStaffBankAudited = true;
                return obtainCursedGoblinStaff(ctx);
            }

            SplashGearItem fallbackGear = cheapestSplashGearForSlot(slot, defenceLevel);
            if (fallbackGear != null) {
                return startPurchaseFromBank(ctx, fallbackGear.name(), 1);
            }
        }
        splashGearBankChecked = true;

        int targetCasts = splashTargetCastsFor(splashSpell);
        for (RuneNeed need : runeNeedsFor(splashSpell)) {
            if (ensureRuneStock(
                    ctx,
                    need.itemName(),
                    MIN_SPLASH_CASTS_READY * need.perCast(),
                    targetCasts * need.perCast()
            )) {
                return true;
            }
        }

        closeBank(ctx);
        Time.sleep(500, 800);
        return true;
    }

    private boolean hasSplashingSetupReadyToEquip(APIContext ctx, Spell splashSpell) {
        return hasCarriedOrEquippedSplashLoadout(ctx)
                && hasSplashRuneBatchReady(ctx, splashSpell);
    }

    private boolean ensureRuneStock(APIContext ctx, String runeName, int minimumReady, int targetQuantity) {
        int inventoryRunes = ctx.inventory().getCount(true, runeName);
        if (inventoryRunes >= minimumReady) {
            return false;
        }

        int bankRunes = ctx.bank().getCount(runeName);
        if (bankRunes > 0) {
            int amount = Math.min(bankRunes, Math.max(minimumReady, targetQuantity - inventoryRunes));
            log("Withdrawing " + amount + "x " + runeName + " for Magic splashing");
            ctx.bank().withdraw(amount, runeName);
            Time.sleep(500, 800);
            return true;
        }

        int quantityToBuy = Math.max(minimumReady - inventoryRunes, targetQuantity - inventoryRunes - bankRunes);
        return startPurchaseFromBank(ctx, runeName, quantityToBuy, inventoryRunes + quantityToBuy);
    }

    private boolean obtainCursedGoblinStaff(APIContext ctx) {
        if (hasCarriedOrEquipped(ctx, CURSED_GOBLIN_STAFF)
                || (isBankOpen(ctx) && bankHasItem(ctx, CURSED_GOBLIN_STAFF))) {
            cursedStaffBankAudited = false;
            return false;
        }

        if (!isBankOpen(ctx) && !cursedStaffBankAudited) {
            if (!ctx.bank().isReachable()) {
                log("Walking to bank to check for Cursed goblin staff");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank to check for Cursed goblin staff");
            ctx.bank().open();
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        if (isBankOpen(ctx)) {
            cursedStaffBankAudited = true;
            if (ctx.inventory().getCount(true, "Coins") < CURSED_STAFF_DIANGO_COINS
                    && ctx.bank().getCount("Coins") > 0) {
                int toWithdraw = Math.min(ctx.bank().getCount("Coins"), CURSED_STAFF_DIANGO_COINS);
                log("Withdrawing coins for Cursed goblin staff");
                ctx.bank().withdraw(toWithdraw, "Coins");
                Time.sleep(500, 800);
                return true;
            }

            closeBank(ctx);
            Time.sleep(500, 800);
            return true;
        }

        if (ctx.inventory().getCount(true, "Coins") < CURSED_STAFF_DIANGO_COINS) {
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for Cursed goblin staff coins");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Cursed goblin staff coins");
            ctx.bank().open();
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        if (ctx.store().isOpen()) {
            if (ctx.store().contains(CURSED_GOBLIN_STAFF)) {
                log("Buying Cursed goblin staff from Diango");
                boolean bought = ctx.store().buyOne(CURSED_GOBLIN_STAFF);
                Time.sleep(
                        700,
                        1400,
                        () -> ctx.inventory().contains(CURSED_GOBLIN_STAFF),
                        100
                );
                if (bought || ctx.inventory().contains(CURSED_GOBLIN_STAFF)) {
                    cursedStaffBankAudited = false;
                    return true;
                }
                return false;
            }

            ctx.store().close();
            Time.sleep(500, 800);
            return true;
        }

        if (ctx.dialogues().isDialogueOpen()) {
            if (ctx.dialogues().canContinue()) {
                ctx.dialogues().selectContinue();
                Time.sleep(500, 800);
                return true;
            }
            if (ctx.dialogues().hasOptionContaining("trade")
                    || ctx.dialogues().hasOptionContaining("holiday")
                    || ctx.dialogues().hasOptionContaining("toys")
                    || ctx.dialogues().hasOptionContaining("store")) {
                ctx.dialogues().selectOption(option -> {
                    String lower = option == null ? "" : option.toLowerCase();
                    return lower.contains("trade")
                            || lower.contains("holiday")
                            || lower.contains("toys")
                            || lower.contains("store");
                });
                Time.sleep(700, 1200);
                return true;
            }
        }

        if (!DIANGO_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Diango for Cursed goblin staff");
            Navigation.walkToNoTeleports(ctx, DIANGO_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return true;
        }

        NPC diango = ctx.npcs()
                .query()
                .named("Diango")
                .reachable()
                .results()
                .nearest();
        if (diango == null || !diango.isValid()) {
            log("No Diango found for Cursed goblin staff; adjusting camera");
            adjustCamera(ctx);
            Time.sleep(900, 1400);
            return true;
        }

        log("Opening Diango store for Cursed goblin staff");
        boolean interacted = diango.interact("Trade")
                || diango.interact("Talk-to");
        Time.sleep(
                900,
                1600,
                () -> ctx.store().isOpen() || ctx.dialogues().isDialogueOpen(),
                100
        );
        return interacted;
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
            log("Withdrawing coins for Magic buy: " + toWithdraw);
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
        log("Planning Magic buy: " + quantity + "x " + itemName + " at " + price + " each");
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
                + " for " + targetItem + " (" + activeFundingTargetCoins + " gp target)");
        stats.setFundingReason("Magic " + fundingMethodLabel(decision.method()) + " for " + targetItem);
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
        stats.setFundingReason("Magic stock sale: " + pendingFundingSellItem
                + " for " + activeFundingTargetItem);
        return withdrawFundingStockFromBank(ctx);
    }

    private boolean handleMagicFundingPool(APIContext ctx) {
        if (pendingFundingSellItem != null) {
            return handlePendingFundingSale(ctx);
        }

        if (activeFundingDecision == null) {
            return false;
        }

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
            if (!ctx.bank().isReachable()) {
                log("Walking to bank for Magic funding stock");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank for Magic funding stock");
            ctx.bank().open();
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
        switch (decision.method()) {
            case BEER_GLASS -> beerGlassFundingModule.execute(ctx);
            case MINING_SMITHING -> miningSmithingFundingModule.execute(ctx);
            case FISHING_COOKING -> fishingCookingFundingModule.execute(ctx);
            case WOODCUTTING -> {
                activeFundingDecision = new FundingPlanner.Decision(
                        FundingPlanner.Method.BEER_GLASS,
                        "Beer glass",
                        0,
                        0,
                        0L
                );
                log("Magic funding uses Beer glass fallback instead of WC inside Magic-only mode");
            }
            default -> activeFundingDecision = null;
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
            stats.setFundingReason("Magic stock sale: " + decision.itemName()
                    + " for " + activeFundingTargetItem);
        } else {
            log("Magic FundingPlanner selected " + fundingMethodLabel(decision.method())
                    + " for " + activeFundingTargetItem);
            stats.setFundingReason("Magic " + fundingMethodLabel(decision.method())
                    + " for " + activeFundingTargetItem);
        }
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

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            log("Opening inventory for Magic gear");
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
            return true;
        }

        if (!ctx.equipment().contains(IEquipmentAPI.Slot.WEAPON, STAFF_OF_AIR)
                && ctx.inventory().contains(STAFF_OF_AIR)) {
            return equipInventoryItem(ctx, STAFF_OF_AIR, "Wield");
        }

        for (String gear : OPTIONAL_MAGIC_GEAR) {
            if (!ctx.equipment().contains(gear) && ctx.inventory().contains(gear)) {
                return equipInventoryItem(ctx, gear, "Wear");
            }
        }

        return false;
    }

    private boolean equipSplashingGear(APIContext ctx) {
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

        if (ctx.store().isOpen()) {
            ctx.store().close();
            Time.sleep(500, 800);
            return true;
        }

        if (hasSplashingLoadout(ctx)) {
            return false;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            log("Opening inventory for Magic splashing gear");
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
            return true;
        }

        if (removePositiveSplashGear(ctx)) {
            return true;
        }

        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        for (SplashGearSlot slot : SPLASH_GEAR) {
            if (hasEquippedSplashSlot(ctx, slot, defenceLevel)) {
                continue;
            }

            SplashGearItem heldGear = bestAvailableSplashGear(ctx, slot, defenceLevel, false);
            if (heldGear != null && ctx.inventory().contains(heldGear.name())) {
                return equipInventoryItem(ctx, heldGear.name(), slot.action());
            }
        }

        return false;
    }

    private boolean removePositiveSplashGear(APIContext ctx) {
        for (String itemName : POSITIVE_SPLASH_GEAR) {
            ItemWidget equipped = ctx.equipment().getItem(itemName);
            if (equipped == null) {
                continue;
            }

            log("Removing positive Magic gear before splashing: " + itemName);
            boolean removed = equipped.interact("Remove")
                    || equipped.interact("Remove", itemName);
            Time.sleep(
                    600,
                    1200,
                    () -> !ctx.equipment().contains(itemName),
                    100
            );
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
        if (ctx.magic().getAutoCastSpell() == spell
                && ctx.combat().getAttackStyle() == ICombatAPI.AttackStyle.CASTING) {
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
                900,
                1500,
                () -> ctx.magic().getAutoCastSpell() == spell
                        || ctx.combat().getAttackStyle() == ICombatAPI.AttackStyle.CASTING,
                100
        );

        if (!set) {
            log("Autocast API did not confirm " + spell.getSpellName() + "; retrying");
        }
        return true;
    }

    private void trainWindStrike(APIContext ctx) {
        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(500, 800);
            return;
        }

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.localPlayer().isMoving()
                || ctx.localPlayer().isAnimating()
                || ctx.localPlayer().isAttacking()) {
            stats.setStatus("Wind Strike training in progress");
            Time.sleep(600, 900);
            return;
        }

        if (inventoryMindRunes(ctx) < 1) {
            log("Out of Mind rune; returning to Magic setup");
            return;
        }

        if (!CHICKEN_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to chickens for Wind Strike");
            Navigation.walkToNoTeleports(ctx, CHICKEN_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return;
        }

        NPC target = ctx.npcs()
                .query()
                .named("Chicken")
                .notInCombat()
                .within(CHICKEN_AREA)
                .reachable()
                .results()
                .nearest();
        if (target == null || !target.isValid()) {
            debugNoTarget(ctx);
            Time.sleep(700, 1100);
            return;
        }

        log("Casting Wind Strike on Chicken until Magic " + NORMAL_MAGIC_CAP);
        if (target.interact("Attack")) {
            Time.sleep(
                    900,
                    1800,
                    () -> ctx.localPlayer().isAttacking()
                            || ctx.localPlayer().isAnimating()
                            || target.isInCombat(),
                    100
            );
        } else {
            Time.sleep(500, 800);
        }
    }

    private void splashAutoCastSpell(APIContext ctx, Spell splashSpell) {
        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(500, 800);
            return;
        }

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.store().isOpen()) {
            ctx.store().close();
            Time.sleep(500, 800);
            return;
        }

        if (!hasSplashingLoadout(ctx) || !hasEnoughForSplashCast(ctx, splashSpell)) {
            log("Splash setup missing; returning to Magic splashing setup");
            splashGearBankChecked = false;
            cursedStaffBankAudited = false;
            return;
        }

        if (ctx.localPlayer().isMoving()
                || ctx.localPlayer().isAnimating()
                || ctx.localPlayer().isAttacking()) {
            stats.setStatus(splashSpell.getSpellName() + " splashing in progress");
            Time.sleep(600, 900);
            return;
        }

        if (!hasEnoughForSplashCast(ctx, splashSpell)) {
            log("Splash runes missing before " + splashSpell.getSpellName() + ": " + splashRuneStatus(ctx, splashSpell));
            splashGearBankChecked = false;
            cursedStaffBankAudited = false;
            Time.sleep(600, 900);
            return;
        }

        if (ctx.magic().getAutoCastSpell() != splashSpell) {
            log(splashSpell.getSpellName() + " autocast not ready; retrying autocast setup");
            return;
        }

        if (!PORT_SARIM_SEAGULL_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Port Sarim seagulls for " + splashSpell.getSpellName() + " splashing");
            Navigation.walkToNoTeleports(ctx, PORT_SARIM_SEAGULL_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return;
        }

        handleRecentContestedSplashTarget();
        NPC target = findSplashTarget(ctx);
        if (target == null || !target.isValid()) {
            debugNoSplashTarget(ctx);
            Time.sleep(700, 1100);
            return;
        }

        log("Splashing " + splashSpell.getSpellName() + " on Seagull until Magic "
                + nextSplashStageDescription(ctx));
        rememberSplashTarget(target);
        if (target.interact("Attack")) {
            Time.sleep(
                    900,
                    1800,
                    () -> ctx.localPlayer().isAttacking()
                            || ctx.localPlayer().isAnimating()
                            || target.isInCombat(),
                    100
            );
            handleRecentContestedSplashTarget();
        } else {
            Time.sleep(500, 800);
        }
    }

    private void splashManualSpell(APIContext ctx, Spell splashSpell) {
        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(500, 800);
            return;
        }

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.store().isOpen()) {
            ctx.store().close();
            Time.sleep(500, 800);
            return;
        }

        if (!hasSplashingLoadout(ctx) || !hasEnoughForSplashCast(ctx, splashSpell)) {
            log("Splash setup missing; returning to Magic splashing setup");
            splashGearBankChecked = false;
            cursedStaffBankAudited = false;
            return;
        }

        if (ensureAutoRetaliateOff(ctx)) {
            return;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus(splashSpell.getSpellName() + " splashing in progress");
            Time.sleep(600, 900);
            return;
        }

        if (!PORT_SARIM_SEAGULL_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Port Sarim seagulls for " + splashSpell.getSpellName() + " splashing");
            Navigation.walkToNoTeleports(ctx, PORT_SARIM_SEAGULL_AREA.getRandomTile());
            Time.sleep(1200, 1800);
            return;
        }

        handleRecentContestedSplashTarget();
        NPC target = findSplashTarget(ctx);
        if (target == null || !target.isValid()) {
            debugNoSplashTarget(ctx);
            Time.sleep(700, 1100);
            return;
        }

        if (!ctx.magic().isSpellSelected()) {
            selectManualSpell(ctx, splashSpell);
            return;
        }

        log("Splashing " + splashSpell.getSpellName() + " manually on Seagull until Magic " + FUTURE_SPLASH_CAP);
        rememberSplashTarget(target);
        boolean cast = target.click(false)
                || ctx.menu().interact("Cast", target, false)
                || target.interact("Cast");
        Time.sleep(
                900,
                1800,
                () -> ctx.localPlayer().isAnimating()
                        || !hasEnoughForSplashCast(ctx, splashSpell),
                100
        );
        handleRecentContestedSplashTarget();

        if (!cast) {
            log("Manual cast did not start for " + splashSpell.getSpellName() + "; retrying target/spell");
            Time.sleep(600, 900);
        }
    }

    private NPC findSplashTarget(APIContext ctx) {
        return ctx.npcs()
                .query()
                .named("Seagull")
                .actions("Attack")
                .notInCombat()
                .within(PORT_SARIM_SEAGULL_AREA)
                .reachable()
                .filter(target -> target != null
                        && !target.isInCombat()
                        && !isBlockedSplashTarget(target))
                .results()
                .nearest();
    }

    private void rememberSplashTarget(NPC target) {
        lastSplashTargetTile = target == null ? null : target.getLocation();
    }

    private boolean handleRecentContestedSplashTarget() {
        if (!stats.consumeRecentAlreadyUnderAttackMessage()) {
            return false;
        }

        if (lastSplashTargetTile != null) {
            blockedSplashTargetTile = lastSplashTargetTile;
            blockedSplashTargetUntil = System.currentTimeMillis() + SPLASH_TARGET_BLOCK_MILLIS;
            log("Avoiding busy Seagull at " + blockedSplashTargetTile.getX()
                    + "," + blockedSplashTargetTile.getY());
            lastSplashTargetTile = null;
            return true;
        }

        return false;
    }

    private boolean isBlockedSplashTarget(NPC target) {
        if (target == null || blockedSplashTargetTile == null) {
            return false;
        }

        if (System.currentTimeMillis() > blockedSplashTargetUntil) {
            blockedSplashTargetTile = null;
            blockedSplashTargetUntil = 0L;
            return false;
        }

        return sameTile(blockedSplashTargetTile, target.getLocation());
    }

    private boolean sameTile(Tile left, Tile right) {
        return left != null
                && right != null
                && left.getX() == right.getX()
                && left.getY() == right.getY()
                && left.getPlane() == right.getPlane();
    }

    private boolean ensureAutoRetaliateOff(APIContext ctx) {
        if (!ctx.combat().isAutoRetaliateOn()) {
            return false;
        }

        log("Turning Auto Retaliate off before manual Curse splashing");
        ctx.combat().toggleAutoRetaliate(false);
        Time.sleep(500, 900, () -> !ctx.combat().isAutoRetaliateOn(), 100);
        return true;
    }

    private void selectManualSpell(APIContext ctx, Spell spell) {
        String spellName = spell.getSpellName();
        log("Selecting manual spell: " + spellName);

        boolean selected = ctx.magic().cast(spell);
        if (!selected && ctx.menu().isOpen()) {
            selected = ctx.menu().interact("Cast " + spellName)
                    || ctx.menu().interact("Cast", spellName);
        }

        Time.sleep(500, 900, () -> ctx.magic().isSpellSelected(), 100);
        if (!ctx.magic().isSpellSelected()) {
            log("Manual spell was not selected: " + spellName);
        }
    }

    private void debugNoSplashTarget(APIContext ctx) {
        if (System.currentTimeMillis() < nextTargetDebugAt) {
            stats.setStatus("No seagull target found for Magic splashing");
            return;
        }

        nextTargetDebugAt = System.currentTimeMillis() + 10_000L;
        log("No seagull target found for Magic splashing; adjusting camera/position");
        adjustCamera(ctx);
        Navigation.walkToNoTeleports(ctx, PORT_SARIM_SEAGULL_AREA.getRandomTile());
    }

    private void debugNoTarget(APIContext ctx) {
        if (System.currentTimeMillis() < nextTargetDebugAt) {
            stats.setStatus("No chicken target found for Magic");
            return;
        }

        nextTargetDebugAt = System.currentTimeMillis() + 10_000L;
        log("No chicken target found for Magic; adjusting camera/position");
        adjustCamera(ctx);
        Tile tile = CHICKEN_AREA.getRandomTile();
        Navigation.walkToNoTeleports(ctx, tile);
    }

    private void adjustCamera(APIContext ctx) {
        int yaw = (ctx.camera().getYawDeg() + ThreadLocalRandom.current().nextInt(80, 181)) % 360;
        int pitch = ThreadLocalRandom.current().nextInt(280, 361);
        ctx.camera().setYawDeg(yaw);
        ctx.camera().setPitch(pitch);
    }

    private boolean hasMandatoryNormalMagicLoadout(APIContext ctx) {
        return hasCarriedOrEquipped(ctx, STAFF_OF_AIR);
    }

    private boolean hasSplashingLoadout(APIContext ctx) {
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        for (SplashGearSlot slot : SPLASH_GEAR) {
            if (!hasEquippedSplashSlot(ctx, slot, defenceLevel)) {
                return false;
            }
        }

        for (String positiveGear : POSITIVE_SPLASH_GEAR) {
            if (ctx.equipment().contains(positiveGear)) {
                return false;
            }
        }

        return true;
    }

    private boolean hasCarriedOrEquippedSplashSlot(APIContext ctx, SplashGearSlot slot, int defenceLevel) {
        return hasEquippedSplashSlot(ctx, slot, defenceLevel)
                || bestAvailableSplashGear(ctx, slot, defenceLevel, false) != null;
    }

    private boolean hasCarriedOrEquippedSplashLoadout(APIContext ctx) {
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        for (SplashGearSlot slot : SPLASH_GEAR) {
            if (!hasCarriedOrEquippedSplashSlot(ctx, slot, defenceLevel)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEquippedSplashSlot(APIContext ctx, SplashGearSlot slot, int defenceLevel) {
        for (SplashGearItem item : slot.items()) {
            if (item.requiredDefence() <= defenceLevel && ctx.equipment().contains(slot.slot(), item.name())) {
                return true;
            }
        }
        return false;
    }

    private SplashGearItem bestAvailableSplashGear(
            APIContext ctx,
            SplashGearSlot slot,
            int defenceLevel,
            boolean includeBank
    ) {
        for (SplashGearItem item : slot.items()) {
            if (item.requiredDefence() > defenceLevel) {
                continue;
            }

            if (ctx.equipment().contains(slot.slot(), item.name())
                    || ctx.inventory().contains(item.name())
                    || (includeBank && bankHasItem(ctx, item.name()))) {
                return item;
            }
        }
        return null;
    }

    private SplashGearItem cheapestSplashGearForSlot(SplashGearSlot slot, int defenceLevel) {
        for (SplashGearItem item : slot.items()) {
            if (item.requiredDefence() <= defenceLevel) {
                return item;
            }
        }
        return null;
    }

    private Spell splashSpellForLevel(int magicLevel) {
        return magicLevel >= CURSE_SPLASH_MAGIC_LEVEL
                ? CURSE_SPLASH_SPELL
                : FIRE_SPLASH_SPELL;
    }

    private boolean usesAutocastSplash(Spell spell) {
        return spell == FIRE_SPLASH_SPELL;
    }

    private int splashTargetCastsFor(Spell spell) {
        if (activeSplashBatchSpell != spell || activeSplashBatchTargetCasts <= 0) {
            activeSplashBatchSpell = spell;
            activeSplashBatchTargetCasts = ThreadLocalRandom.current()
                    .nextInt(MIN_SPLASH_TARGET_CASTS, MAX_SPLASH_TARGET_CASTS + 1);
            log("Magic splash rune batch selected: "
                    + activeSplashBatchTargetCasts
                    + " casts for "
                    + spell.getSpellName());
        }
        return activeSplashBatchTargetCasts;
    }

    private RuneNeed[] runeNeedsFor(Spell spell) {
        if (spell == CURSE_SPLASH_SPELL) {
            return new RuneNeed[]{
                    new RuneNeed(WATER_RUNE, 2),
                    new RuneNeed(EARTH_RUNE, 3),
                    new RuneNeed(BODY_RUNE, 1)
            };
        }

        return new RuneNeed[]{
                new RuneNeed(AIR_RUNE, 2),
                new RuneNeed(MIND_RUNE, 1),
                new RuneNeed(FIRE_RUNE, 3)
        };
    }

    private boolean hasSplashRuneBatchReady(APIContext ctx, Spell spell) {
        for (RuneNeed need : runeNeedsFor(spell)) {
            if (ctx.inventory().getCount(true, need.itemName()) < MIN_SPLASH_CASTS_READY * need.perCast()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughForSplashCast(APIContext ctx, Spell spell) {
        for (RuneNeed need : runeNeedsFor(spell)) {
            if (ctx.inventory().getCount(true, need.itemName()) < need.perCast()) {
                return false;
            }
        }
        return true;
    }

    private boolean isRune(String itemName) {
        return namesMatch(itemName, AIR_RUNE)
                || namesMatch(itemName, MIND_RUNE)
                || namesMatch(itemName, FIRE_RUNE)
                || namesMatch(itemName, WATER_RUNE)
                || namesMatch(itemName, EARTH_RUNE)
                || namesMatch(itemName, BODY_RUNE);
    }

    private String splashRuneStatus(APIContext ctx, Spell spell) {
        StringBuilder builder = new StringBuilder();
        for (RuneNeed need : runeNeedsFor(spell)) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(need.itemName().replace(" rune", ""))
                    .append('=')
                    .append(ctx.inventory().getCount(true, need.itemName()));
        }
        return builder.toString();
    }

    private String nextSplashStageDescription(APIContext ctx) {
        int magicLevel = ctx.skills().get(Skill.Skills.MAGIC).getRealLevel();
        if (magicLevel < CURSE_SPLASH_MAGIC_LEVEL) {
            return CURSE_SPLASH_MAGIC_LEVEL + " then Curse";
        }
        return String.valueOf(FUTURE_SPLASH_CAP);
    }

    private boolean hasCarriedOrEquipped(APIContext ctx, String itemName) {
        return ctx.inventory().contains(itemName) || ctx.equipment().contains(itemName);
    }

    private int inventoryMindRunes(APIContext ctx) {
        return ctx.inventory().getCount(true, MIND_RUNE);
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
        if (isRune(itemName)) {
            int fixedRunePrice = F2PItemRegistry.buyPrice(itemName);
            if (fixedRunePrice > 0) {
                return fixedRunePrice;
            }
        }
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
        if (normalized.equals("airrune")) {
            return 5L;
        }
        if (normalized.equals("firerune")) {
            return 5L;
        }
        if (normalized.equals("staffofair")) {
            return 1500L;
        }
        if (normalized.equals("cursedgoblinstaff")) {
            return CURSED_STAFF_DIANGO_COINS;
        }
        if (normalized.equals("amuletofpower")) {
            return 3000L;
        }
        if (isSplashArmourName(normalized)) {
            long tierPrice = splashArmourTierPrice(normalized);
            if (normalized.contains("platebody")) {
                return Math.round(tierPrice * 1.8);
            }
            if (normalized.contains("platelegs") || normalized.contains("kiteshield")) {
                return Math.round(tierPrice * 1.35);
            }
            return tierPrice;
        }
        if (normalized.contains("bluewizard")) {
            return 300L;
        }
        if (normalized.equals("blueskirt")) {
            return 250L;
        }
        return 500L;
    }

    private boolean isSplashArmourName(String normalizedName) {
        return normalizedName.contains("fullhelm")
                || normalizedName.contains("platebody")
                || normalizedName.contains("platelegs")
                || normalizedName.contains("kiteshield");
    }

    private long splashArmourTierPrice(String normalizedName) {
        if (normalizedName.startsWith("bronze")) {
            return 250L;
        }
        if (normalizedName.startsWith("iron")) {
            return 500L;
        }
        if (normalizedName.startsWith("steel")) {
            return 900L;
        }
        if (normalizedName.startsWith("black")) {
            return 1500L;
        }
        if (normalizedName.startsWith("mithril")) {
            return 2500L;
        }
        if (normalizedName.startsWith("adamant")) {
            return 5000L;
        }
        if (normalizedName.startsWith("rune")) {
            return 18_000L;
        }
        return 700L;
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

    private record SplashGearItem(String name, int requiredDefence) {
    }

    private record SplashGearSlot(IEquipmentAPI.Slot slot, String action, SplashGearItem[] items) {
    }

    private record RuneNeed(String itemName, int perCast) {
    }

    private record FundingStock(String itemName, int quantity, int sellPrice) {
    }
}
