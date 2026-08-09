package org.example.modules.combat;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.Actor;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.ICombatAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.ItemDetail;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.F2PModule;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class LumbridgeCowCombatModule implements F2PModule {
    private static final Area CHICKEN_PEN = new Area(
            new Tile(3226, 3301, 0),
            new Tile(3225, 3299, 0),
            new Tile(3225, 3295, 0),
            new Tile(3235, 3294, 0),
            new Tile(3236, 3294, 0),
            new Tile(3235, 3301, 0),
            new Tile(3226, 3301, 0)
    );
    private static final Area COW_PEN = new Area(3253, 3255, 3265, 3297);
    private static final Area WC_FUNDING_TREES = new Area(3188, 3232, 3218, 3264);
    private static final Tile[] WC_FUNDING_SAFE_TILES = {
            new Tile(3197, 3243, 0),
            new Tile(3202, 3249, 0),
            new Tile(3207, 3238, 0),
            new Tile(3194, 3254, 0)
    };
    private static final int MIN_FULL_INVENTORY_BANK_DELAY_MINUTES = 10;
    private static final int MAX_FULL_INVENTORY_BANK_DELAY_MINUTES = 15;
    private static final int MAX_TARGET_SEARCH_FAILURES = 8;
    private static final int MAX_WC_FUNDING_CHOP_FAILURES = 3;
    private static final int OWN_LOOT_RADIUS_TILES = 2;
    private static final long OWN_LOOT_WINDOW_MILLIS = 25_000L;
    private static final long LOOT_FILTER_LOG_INTERVAL_MILLIS = 5_000L;
    private static final long MAX_TARGET_TRAVEL_MILLIS = 3 * 60 * 1000L;
    private static final long MELEE_ATTACK_CLICK_LOCK_MILLIS = 4_000L;
    private static final long RANGED_ATTACK_CLICK_LOCK_MILLIS = 6_500L;
    private static final boolean LOW_TIER_COMBAT_ONLY = false;
    private static final int EAT_AT_HEALTH_PERCENT = 55;
    private static final int RETREAT_AT_HEALTH_PERCENT = 1;
    private static final int RESUME_AT_HEALTH_PERCENT = 85;
    private static final int MIN_DEFENCE_FOR_RANGED = 15;
    private static final int MIN_RANGED_ARROWS_EQUIPPED = 50;
    private static final int MIN_RANGED_ARROW_PURCHASE = 200;
    public static final int RANGED_TRAINING_CAP = 30;
    private static final String WC_FUNDING_LOG_NAME = "Logs";
    private static final String STARTER_FOOD_NAME = "Trout";
    private static final int MIN_FOOD_STOCK_TARGET = 120;
    private static final int MAX_FOOD_STOCK_TARGET = 180;
    private static final int LOW_TOTAL_FOOD_THRESHOLD = 8;
    private static final int MIN_COMBAT_FOOD_WITHDRAW = 8;
    private static final int MAX_COMBAT_FOOD_WITHDRAW = 14;
    private static final int MIN_STARTER_FOOD_BUY = 10;
    private static final int STARTER_FOOD_BUFFER_COINS = 150;
    private static final int MELEE_BALANCE_WEIGHT_GAP = 3;
    private static final int MELEE_BALANCE_FORCE_GAP = 6;
    private static final int MELEE_PLAN_MIN_MINUTES = 28;
    private static final int MELEE_PLAN_MAX_MINUTES = 68;
    private static final int MELEE_PLAN_MIN_LEVELS = 2;
    private static final int MELEE_PLAN_MAX_LEVELS = 5;
    private static final int MELEE_PLAN_EARLY_MIN_XP = 1800;
    private static final int MELEE_PLAN_EARLY_MAX_XP = 5200;
    private static final int MELEE_PLAN_MIN_XP = 3500;
    private static final int MELEE_PLAN_MAX_XP = 11000;
    private static final Area GE_AREA = new Area(3160, 3478, 3175, 3490);
    private static final String[] GEAR_FUNDING_ITEMS = F2PItemRegistry.fundingSellItems();
    private static final String[] NON_SELLABLE_FUNDING_ITEMS = F2PItemRegistry.restrictedGeItems();
    private static final String[] WC_FUNDING_AXES = {
            "Mithril axe",
            "Steel axe",
            "Iron axe",
            "Bronze axe"
    };
    private static final String[] WC_FUNDING_KEEP_ITEMS = {
            "Coins",
            "Logs",
            "Mithril axe",
            "Steel axe",
            "Iron axe",
            "Bronze axe"
    };
    private static final String[] WC_FUNDING_SALE_KEEP_ITEMS = {
            "Coins",
            "Logs"
    };
    private static final String[] COW_LOOT = {
            "Coins",
            "Bones"
    };
    private static final String[] CHICKEN_LOOT = {
            "Coins",
            "Feather",
            "Feathers",
            "Bones",
    };
    private static final String[] BASIC_FOODS = F2PItemRegistry.foodItems();
    private static final String[] BANKABLE_COMBAT_LOOT = {
            "Cowhide",
            "Hard leather",
            "Leather",
            "Feather",
            "Feathers",
            "Raw chicken",
            "Raw beef",
            "Raw rat meat",
            "Big bones",
            "Limpwurt root",
            "Uncut sapphire",
            "Uncut emerald",
            "Uncut ruby",
            "Giant key",
            "Bronze spear",
            "Iron dagger",
            "Bronze axe",
            "Mead",
            "Steel axe",
            "Iron sword",
            "Ensouled goblin head",
            "Iron sq shield",
            "Iron square shield",
            "Bronze longsword",
            "Coins"
    };

    private static final GearItem[] WEAPONS = {
            new GearItem("Rune scimitar", 40, "Wield"),
            new GearItem("Rune sword", 40, "Wield"),
            new GearItem("Rune longsword", 40, "Wield"),
            new GearItem("Rune mace", 40, "Wield"),
            new GearItem("Rune dagger", 40, "Wield"),
            new GearItem("Adamant scimitar", 30, "Wield"),
            new GearItem("Adamant sword", 30, "Wield"),
            new GearItem("Adamant longsword", 30, "Wield"),
            new GearItem("Adamant mace", 30, "Wield"),
            new GearItem("Adamant dagger", 30, "Wield"),
            new GearItem("Mithril scimitar", 20, "Wield"),
            new GearItem("Mithril sword", 20, "Wield"),
            new GearItem("Mithril longsword", 20, "Wield"),
            new GearItem("Mithril mace", 20, "Wield"),
            new GearItem("Mithril dagger", 20, "Wield"),
            new GearItem("Black scimitar", 10, "Wield"),
            new GearItem("Black sword", 10, "Wield"),
            new GearItem("Black longsword", 10, "Wield"),
            new GearItem("Black mace", 10, "Wield"),
            new GearItem("Black dagger", 10, "Wield"),
            new GearItem("Steel scimitar", 5, "Wield"),
            new GearItem("Steel sword", 5, "Wield"),
            new GearItem("Steel longsword", 5, "Wield"),
            new GearItem("Steel mace", 5, "Wield"),
            new GearItem("Steel dagger", 5, "Wield"),
            new GearItem("Iron scimitar", 1, "Wield"),
            new GearItem("Iron sword", 1, "Wield"),
            new GearItem("Iron longsword", 1, "Wield"),
            new GearItem("Iron mace", 1, "Wield"),
            new GearItem("Iron dagger", 1, "Wield"),
            new GearItem("Bronze scimitar", 1, "Wield"),
            new GearItem("Bronze sword", 1, "Wield"),
            new GearItem("Bronze longsword", 1, "Wield"),
            new GearItem("Bronze mace", 1, "Wield"),
            new GearItem("Bronze dagger", 1, "Wield")
    };
    private static final GearItem[] HELMETS = {
            new GearItem("Rune full helm", 40, "Wear"),
            new GearItem("Rune med helm", 40, "Wear"),
            new GearItem("Adamant full helm", 30, "Wear"),
            new GearItem("Adamant med helm", 30, "Wear"),
            new GearItem("Mithril full helm", 20, "Wear"),
            new GearItem("Mithril med helm", 20, "Wear"),
            new GearItem("Black full helm", 10, "Wear"),
            new GearItem("Black med helm", 10, "Wear"),
            new GearItem("Steel full helm", 5, "Wear"),
            new GearItem("Steel med helm", 5, "Wear"),
            new GearItem("Iron full helm", 1, "Wear"),
            new GearItem("Iron med helm", 1, "Wear"),
            new GearItem("Bronze full helm", 1, "Wear"),
            new GearItem("Bronze med helm", 1, "Wear")
    };
    private static final GearItem[] BODIES = {
            new GearItem("Rune chainbody", 40, "Wear"),
            new GearItem("Adamant platebody", 30, "Wear"),
            new GearItem("Adamant chainbody", 30, "Wear"),
            new GearItem("Mithril platebody", 20, "Wear"),
            new GearItem("Mithril chainbody", 20, "Wear"),
            new GearItem("Black platebody", 10, "Wear"),
            new GearItem("Black chainbody", 10, "Wear"),
            new GearItem("Steel platebody", 5, "Wear"),
            new GearItem("Steel chainbody", 5, "Wear"),
            new GearItem("Iron platebody", 1, "Wear"),
            new GearItem("Iron chainbody", 1, "Wear"),
            new GearItem("Bronze platebody", 1, "Wear"),
            new GearItem("Bronze chainbody", 1, "Wear"),
            new GearItem("Leather body", 1, "Wear")
    };
    private static final GearItem[] LEGS = {
            new GearItem("Rune platelegs", 40, "Wear"),
            new GearItem("Rune plateskirt", 40, "Wear"),
            new GearItem("Adamant platelegs", 30, "Wear"),
            new GearItem("Adamant plateskirt", 30, "Wear"),
            new GearItem("Mithril platelegs", 20, "Wear"),
            new GearItem("Mithril plateskirt", 20, "Wear"),
            new GearItem("Black platelegs", 10, "Wear"),
            new GearItem("Black plateskirt", 10, "Wear"),
            new GearItem("Steel platelegs", 5, "Wear"),
            new GearItem("Steel plateskirt", 5, "Wear"),
            new GearItem("Iron platelegs", 1, "Wear"),
            new GearItem("Iron plateskirt", 1, "Wear"),
            new GearItem("Bronze platelegs", 1, "Wear"),
            new GearItem("Bronze plateskirt", 1, "Wear"),
            new GearItem("Leather chaps", 1, "Wear")
    };
    private static final GearItem[] SHIELDS = {
            new GearItem("Rune kiteshield", 40, "Wield"),
            new GearItem("Rune sq shield", 40, "Wield"),
            new GearItem("Adamant kiteshield", 30, "Wield"),
            new GearItem("Adamant sq shield", 30, "Wield"),
            new GearItem("Mithril kiteshield", 20, "Wield"),
            new GearItem("Mithril sq shield", 20, "Wield"),
            new GearItem("Black kiteshield", 10, "Wield"),
            new GearItem("Black sq shield", 10, "Wield"),
            new GearItem("Steel kiteshield", 5, "Wield"),
            new GearItem("Steel sq shield", 5, "Wield"),
            new GearItem("Iron kiteshield", 1, "Wield"),
            new GearItem("Iron sq shield", 1, "Wield"),
            new GearItem("Bronze kiteshield", 1, "Wield"),
            new GearItem("Bronze sq shield", 1, "Wield"),
            new GearItem("Wooden shield", 1, "Wield")
    };
    private static final GearItem[] AMULETS = {
            new GearItem("Amulet of power", 1, "Wear"),
            new GearItem("Amulet of strength", 1, "Wear"),
            new GearItem("Amulet of accuracy", 1, "Wear")
    };
    private static final GearItem[] BOOTS = {
            new GearItem("Fighting boots", 1, "Wear"),
            new GearItem("Fancy boots", 1, "Wear"),
            new GearItem("Leather boots", 1, "Wear")
    };
    private static final GearItem[] GLOVES = {
            new GearItem("Leather gloves", 1, "Wear")
    };
    private static final GearItem[] RANGED_WEAPONS = {
            new GearItem("Maple shortbow", 30, "Wield"),
            new GearItem("Willow shortbow", 20, "Wield"),
            new GearItem("Oak shortbow", 5, "Wield"),
            new GearItem("Shortbow", 1, "Wield")
    };
    private static final GearItem[] RANGED_AMMO = {
            new GearItem("Mithril arrow", 20, "Wield"),
            new GearItem("Steel arrow", 5, "Wield"),
            new GearItem("Bronze arrow", 1, "Wield")
    };
    private static final GearItem[] RANGED_BODIES = {
            new GearItem("Studded body", 20, "Wear"),
            new GearItem("Leather body", 1, "Wear")
    };
    private static final GearItem[] RANGED_LEGS = {
            new GearItem("Green d'hide chaps", 40, "Wear"),
            new GearItem("Studded chaps", 20, "Wear"),
            new GearItem("Leather chaps", 1, "Wear")
    };
    private static final GearItem[] RANGED_HELMETS = {
            new GearItem("Coif", 20, "Wear"),
            new GearItem("Leather cowl", 1, "Wear")
    };
    private static final GearItem[] RANGED_GLOVES = {
            new GearItem("Green d'hide vambraces", 40, "Wear"),
            new GearItem("Leather vambraces", 1, "Wear")
    };
    private static final GearItem[] RANGED_BOOTS = {
            new GearItem("Leather boots", 1, "Wear")
    };
    private static final GearItem[] RANGED_AMULETS = {
            new GearItem("Amulet of power", 1, "Wear"),
            new GearItem("Amulet of accuracy", 1, "Wear")
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final TrainingMode trainingMode;
    private final FundingPlanner fundingPlanner = new FundingPlanner();
    private final SkillCapManager fundingSkillCaps = new SkillCapManager();
    private final BeerGlassCollectorModule beerGlassFundingModule;
    private final FishingCookingModule fishingCookingFundingModule;
    private final MiningSmithingModule miningSmithingFundingModule;
    private final Set<String> geBlockedFundingItems = new HashSet<>();
    private FundingPlanner.Decision activeFundingDecision;
    private long bankAfterFullInventoryAt;
    private boolean initialGearChecked;
    private int gearCheckedAttackLevel;
    private int gearCheckedDefenceLevel;
    private int gearCheckedRangedLevel;
    private int gearCheckedRangedArmorLevel;
    private boolean forceRangedArrowRestock;
    private boolean wasInCombat;
    private boolean recoveringHealth;
    private boolean gearDebugLogged;
    private boolean fundingDebugLogged;
    private Skill.Skills currentMeleeTrainingSkill;
    private Skill.Skills meleePlanSkill;
    private long meleePlanSwitchAt;
    private int meleePlanStartXp;
    private int meleePlanTargetXp;
    private int meleePlanStartLevel;
    private int meleePlanTargetLevels;
    private MobDefinition currentTarget;
    private CombatPhase currentCombatPhase;
    private GearItem pendingGearPurchase;
    private boolean pendingGearPurchaseOptional;
    private boolean woodcuttingFundingMode;
    private boolean pendingStarterFoodPurchase;
    private int foodStockTarget;
    private int woodcuttingFundingTargetCoins;
    private String woodcuttingFundingGearName;
    private int woodcuttingFundingChopFailures;
    private boolean woodcuttingFundingBankAudited;
    private int woodcuttingFundingBankCoinsSnapshot;
    private int woodcuttingFundingBankLogsSnapshot;
    private long gearPurchaseDeferredUntil;
    private boolean gearBuyOfferPending;
    private long desiredGearPurchaseRetryAt;
    private long geRestrictionRetryAt;
    private int killsOnCurrentTarget;
    private int killsBeforeTargetSwitch;
    private int targetSearchFailures;
    private long targetTravelStartedAt;
    private long lastKillRecordedAt;
    private Tile lastCombatNpcTile;
    private Tile lastCombatPlayerTile;
    private Tile pendingAttackTargetTile;
    private String pendingAttackTargetName;
    private long pendingAttackUntil;
    private Tile recentOwnLootTile;
    private long recentOwnLootExpiresAt;
    private long lastLootFilterLogAt;
    private long nextCombatTickLogAt;
    private String lastCombatDecision;
    private long nextCombatDecisionRepeatAt;

    public LumbridgeCowCombatModule(Consumer<String> logger) {
        this(logger, new ScriptStats());
    }

    public LumbridgeCowCombatModule(Consumer<String> logger, ScriptStats stats) {
        this(logger, stats, TrainingMode.MELEE);
    }

    public LumbridgeCowCombatModule(Consumer<String> logger, ScriptStats stats, TrainingMode trainingMode) {
        this.logger = logger;
        this.stats = stats;
        this.trainingMode = trainingMode == null ? TrainingMode.MELEE : trainingMode;
        this.foodStockTarget = randomFoodStockTarget();
        this.beerGlassFundingModule = new BeerGlassCollectorModule(logger, stats);
        this.fishingCookingFundingModule = new FishingCookingModule(logger, stats, fundingSkillCaps, false, true);
        this.miningSmithingFundingModule = new MiningSmithingModule(logger, stats, fundingSkillCaps, false, true);
    }

    @Override
    public String name() {
        return trainingMode == TrainingMode.RANGED ? "combat.ranged_engine" : "combat.lumbridge_cows";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return true;
    }

    public boolean isProtectedFundingSubphase() {
        return woodcuttingFundingMode || activeFundingDecision != null;
    }

    public String protectedFundingLabel() {
        if (!isProtectedFundingSubphase()) {
            return "combat funding";
        }
        return activeFundingMethodLabel() + " funding for " + fundingTargetName();
    }

    @Override
    public void execute(APIContext ctx) {
        logCombatTickEntered();
        stats.startExperienceIfNeeded(ctx);
        updateCombatTracking(ctx);

        if (closeWorldSwitcherBeforeCombat(ctx)) {
            logCombatDecision("Combat decision: closed world switcher");
            return;
        }

        if (handleSurvival(ctx)) {
            logCombatDecision("Combat decision: survival/recovery handled");
            return;
        }

        if (handleRangedNoAmmoMessage(ctx)) {
            logCombatDecision("Combat decision: ranged ammo restock triggered");
            return;
        }

        if (woodcuttingFundingMode && pendingGearPurchase == null && pendingStarterFoodPurchase) {
            if (handleWoodcuttingFunding(ctx)) {
                logCombatDecision("Combat decision: " + activeFundingMethodLabel() + " funding for " + fundingTargetName());
                return;
            }
        }

        if (pendingGearPurchase != null && handlePendingGearPurchase(ctx)) {
            logCombatDecision("Combat decision: pending gear purchase handled");
            return;
        }

        if (ensureCombatFood(ctx)) {
            logCombatDecision("Combat decision: combat food handled");
            return;
        }

        if (handlePendingGearPurchase(ctx)) {
            logCombatDecision("Combat decision: pending gear purchase handled");
            return;
        }

        if (prepareCombatGear(ctx)) {
            logCombatDecision("Combat decision: gear preparation handled");
            return;
        }

        if (handleObsoleteGearSale(ctx)) {
            logCombatDecision("Combat decision: old tier gear sale handled");
            return;
        }

        if (ensureCombatStyle(ctx)) {
            logCombatDecision("Combat decision: combat style handled");
            return;
        }

        ensureCombatTarget(ctx);

        buryBones(ctx);

        if (shouldBank(ctx)) {
            logCombatDecision("Combat decision: banking combat loot");
            bankCowhides(ctx);
            return;
        }

        Area combatArea = currentCombatArea(ctx);
        if (isCombatActionPending(ctx, combatArea)) {
            logCombatDecision("Combat decision: waiting for current movement/combat");
            Time.sleep(600, 900);
            return;
        }

        if (lootCowDrops(ctx)) {
            logCombatDecision("Combat decision: looting own drop");
            return;
        }

        if (!combatArea.contains(ctx.localPlayer().getLocation())) {
            if (targetTravelStartedAt == 0L) {
                targetTravelStartedAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - targetTravelStartedAt >= MAX_TARGET_TRAVEL_MILLIS
                    && !isVeryWeakAccount(ctx)) {
                log("Could not reach " + currentTargetLabel(ctx) + "; switching combat target");
                switchCombatTarget(ctx, true);
                return;
            }

            log("Walking to " + currentTargetLabel(ctx));
            walkToArea(ctx, combatArea, currentTargetLabel(ctx));
            logCombatDecision("Combat decision: walking to target area");
            Time.sleep(800, 1200);
            return;
        }
        targetTravelStartedAt = 0L;

        NPC target = findCombatTarget(ctx, combatArea);

        if (target == null) {
            log("No available " + currentTargetLabel(ctx) + " found");
            targetSearchFailures++;
            if (targetSearchFailures >= MAX_TARGET_SEARCH_FAILURES && !isVeryWeakAccount(ctx)) {
                log("Target search failed repeatedly; switching combat target");
                switchCombatTarget(ctx, true);
                targetSearchFailures = 0;
            }
            Time.sleep(1200, 1800);
            logCombatDecision("Combat decision: waiting for target spawn");
            return;
        }

        log("Attacking " + target.getName());
        if (target.interact("Attack")) {
            rememberAttackAttempt(target);
            targetSearchFailures = 0;
            lastCombatNpcTile = target.getLocation();
            lastCombatPlayerTile = ctx.localPlayer().getLocation();
            Time.sleep(1200, 1800, () -> isCombatEngaged(ctx), 100);
        } else {
            Tile targetTile = target.getLocation();
            if (targetTile != null) {
                log("Attack click failed on " + target.getName() + "; repositioning near target");
                walkToTile(ctx, targetTile, target.getName());
            }
        }
    }

    private boolean ensureCombatStyle(APIContext ctx) {
        return trainingMode == TrainingMode.RANGED
                ? ensureRangedCombatStyle(ctx)
                : ensureMeleeCombatStyle(ctx);
    }

    private boolean handleRangedNoAmmoMessage(APIContext ctx) {
        if (trainingMode != TrainingMode.RANGED || !stats.consumeRecentRangedNoAmmoMessage()) {
            return false;
        }

        forceRangedArrowRestock = true;
        initialGearChecked = false;
        gearCheckedRangedLevel = -1;
        log("No ammo message detected; forcing ranged arrow restock before combat");

        if (ctx.localPlayer().isAttacking()) {
            Time.sleep(600, 900);
        }
        return true;
    }

    private boolean ensureMeleeCombatStyle(APIContext ctx) {
        if (ctx.bank().isOpen()
                || ctx.localPlayer().isAttacking()
                || ctx.localPlayer().isMoving()
                || isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.WEAPON)) {
            return false;
        }

        Skill.Skills targetSkill = pickMeleeTrainingSkill(ctx);
        ICombatAPI.AttackStyle desiredStyle = attackStyleForSkill(targetSkill);
        if (desiredStyle == null) {
            return false;
        }

        ICombatAPI.AttackStyle currentStyle = ctx.combat().getAttackStyle();
        if (currentStyle == desiredStyle) {
            currentMeleeTrainingSkill = targetSkill;
            stats.setTrainingSkill(friendlySkillName(targetSkill));
            return false;
        }

        if (!ctx.combat().hasOption(desiredStyle)) {
            if (ctx.combat().hasOption(ICombatAPI.AttackStyle.CONTROLLED)) {
                desiredStyle = ICombatAPI.AttackStyle.CONTROLLED;
            } else {
                log("Combat style unavailable for " + targetSkill.name() + ": " + desiredStyle.name());
                return false;
            }
        }

        log("Switching combat style to train " + friendlySkillName(targetSkill));
        if (ctx.combat().toggleAttackStyle(desiredStyle)) {
            currentMeleeTrainingSkill = targetSkill;
            stats.setTrainingSkill(friendlySkillName(targetSkill));
            Time.sleep(600, 900);
            return true;
        }

        return false;
    }

    private boolean ensureRangedCombatStyle(APIContext ctx) {
        if (ctx.bank().isOpen()
                || ctx.localPlayer().isAttacking()
                || ctx.localPlayer().isMoving()
                || isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.WEAPON)
                || isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.AMMO)) {
            return false;
        }

        ICombatAPI.AttackStyle desiredStyle = ICombatAPI.AttackStyle.RANGING;
        if (!ctx.combat().hasOption(desiredStyle)) {
            if (ctx.combat().hasOption(ICombatAPI.AttackStyle.ACCURATERANGING)) {
                desiredStyle = ICombatAPI.AttackStyle.ACCURATERANGING;
            } else if (ctx.combat().hasOption(ICombatAPI.AttackStyle.LONGRANGE)) {
                desiredStyle = ICombatAPI.AttackStyle.LONGRANGE;
            } else {
                log("Ranged combat style unavailable for current weapon");
                return false;
            }
        }

        stats.setTrainingSkill("Ranged");
        if (ctx.combat().getAttackStyle() == desiredStyle) {
            return false;
        }

        log("Switching combat style to train Ranged");
        if (ctx.combat().toggleAttackStyle(desiredStyle)) {
            Time.sleep(600, 900);
            return true;
        }

        return false;
    }

    private Skill.Skills pickMeleeTrainingSkill(APIContext ctx) {
        return plannedMeleeTrainingSkill(ctx);
    }

    private Skill.Skills plannedMeleeTrainingSkill(APIContext ctx) {
        if (meleePlanSkill == null || shouldSwitchMeleePlan(ctx)) {
            chooseNextMeleePlan(ctx);
        }

        return meleePlanSkill;
    }

    private boolean shouldSwitchMeleePlan(APIContext ctx) {
        if (meleePlanSkill == null) {
            return true;
        }
        if (trainingMode != TrainingMode.MELEE) {
            return false;
        }

        long now = System.currentTimeMillis();
        int currentLevel = ctx.skills().get(meleePlanSkill).getRealLevel();
        int currentXp = ctx.skills().get(meleePlanSkill).getExperience();
        Skill.Skills lowestSkill = lowestMeleeSkill(ctx);
        int currentSkillLevel = ctx.skills().get(meleePlanSkill).getRealLevel();
        int lowestLevel = ctx.skills().get(lowestSkill).getRealLevel();

        if (currentSkillLevel >= lowestLevel + MELEE_BALANCE_FORCE_GAP) {
            return true;
        }

        return now >= meleePlanSwitchAt
                || currentLevel - meleePlanStartLevel >= meleePlanTargetLevels
                || currentXp - meleePlanStartXp >= meleePlanTargetXp;
    }

    private void chooseNextMeleePlan(APIContext ctx) {
        Skill.Skills previous = meleePlanSkill;
        Skill.Skills picked = pickWeightedMeleeSkill(ctx, previous);

        meleePlanSkill = picked;
        currentMeleeTrainingSkill = picked;
        meleePlanStartLevel = ctx.skills().get(picked).getRealLevel();
        meleePlanStartXp = ctx.skills().get(picked).getExperience();
        meleePlanTargetLevels = randomInt(MELEE_PLAN_MIN_LEVELS, MELEE_PLAN_MAX_LEVELS);
        meleePlanTargetXp = meleePlanStartLevel < 20
                ? randomInt(MELEE_PLAN_EARLY_MIN_XP, MELEE_PLAN_EARLY_MAX_XP)
                : randomInt(MELEE_PLAN_MIN_XP, MELEE_PLAN_MAX_XP);
        int minutes = randomInt(MELEE_PLAN_MIN_MINUTES, MELEE_PLAN_MAX_MINUTES);
        meleePlanSwitchAt = System.currentTimeMillis() + minutes * 60_000L;
        stats.setTrainingSkill(friendlySkillName(picked));
        log("Melee plan selected: " + friendlySkillName(picked)
                + " for ~" + minutes + " min or "
                + meleePlanTargetLevels + " level(s)"
                + "; levels A/S/D="
                + ctx.skills().get(Skill.Skills.ATTACK).getRealLevel()
                + "/" + ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel()
                + "/" + ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel());
    }

    private Skill.Skills pickWeightedMeleeSkill(APIContext ctx, Skill.Skills previous) {
        Skill.Skills[] options = {Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE};
        int lowestLevel = meleeLevel(ctx, lowestMeleeSkill(ctx));
        int highestLevel = highestMeleeLevel(ctx);
        int gap = highestLevel - lowestLevel;
        if (gap >= MELEE_BALANCE_FORCE_GAP) {
            return randomLowestMeleeSkill(ctx);
        }

        int[] weights = new int[options.length];
        int total = 0;
        for (int i = 0; i < options.length; i++) {
            Skill.Skills skill = options[i];
            int level = meleeLevel(ctx, skill);
            int weight = 30;
            if (level <= lowestLevel) {
                weight += gap >= MELEE_BALANCE_WEIGHT_GAP ? 45 : 25;
            } else if (level <= lowestLevel + 1) {
                weight += 15;
            }
            if (gap >= MELEE_BALANCE_WEIGHT_GAP && level >= highestLevel) {
                weight -= 18;
            }
            if (previous == skill) {
                weight -= 12;
            }
            weights[i] = Math.max(6, weight);
            total += weights[i];
        }

        int roll = randomInt(1, total);
        int cumulative = 0;
        for (int i = 0; i < options.length; i++) {
            cumulative += weights[i];
            if (roll <= cumulative) {
                return options[i];
            }
        }
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private Skill.Skills lowestMeleeSkill(APIContext ctx) {
        int attack = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int strength = ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel();
        int defence = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();

        if (attack <= strength && attack <= defence) {
            return Skill.Skills.ATTACK;
        }
        if (strength <= attack && strength <= defence) {
            return Skill.Skills.STRENGTH;
        }
        return Skill.Skills.DEFENCE;
    }

    private Skill.Skills randomLowestMeleeSkill(APIContext ctx) {
        Skill.Skills[] options = {Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE};
        int lowestLevel = meleeLevel(ctx, lowestMeleeSkill(ctx));
        List<Skill.Skills> tied = new ArrayList<>();
        for (Skill.Skills skill : options) {
            if (meleeLevel(ctx, skill) == lowestLevel) {
                tied.add(skill);
            }
        }
        return tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
    }

    private int highestMeleeLevel(APIContext ctx) {
        return Math.max(
                ctx.skills().get(Skill.Skills.ATTACK).getRealLevel(),
                Math.max(
                        ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel(),
                        ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel()
                )
        );
    }

    private int meleeLevel(APIContext ctx, Skill.Skills skill) {
        return ctx.skills().get(skill).getRealLevel();
    }

    private ICombatAPI.AttackStyle attackStyleForSkill(Skill.Skills skill) {
        if (skill == Skill.Skills.ATTACK) {
            return ICombatAPI.AttackStyle.ACCURATE;
        }
        if (skill == Skill.Skills.STRENGTH) {
            return ICombatAPI.AttackStyle.AGGRESSIVE;
        }
        if (skill == Skill.Skills.DEFENCE) {
            return ICombatAPI.AttackStyle.DEFENSIVE;
        }
        return null;
    }

    private String friendlySkillName(Skill.Skills skill) {
        if (skill == Skill.Skills.ATTACK) {
            return "Attack";
        }
        if (skill == Skill.Skills.STRENGTH) {
            return "Strength";
        }
        if (skill == Skill.Skills.DEFENCE) {
            return "Defence";
        }
        return skill.name();
    }

    private boolean handleSurvival(APIContext ctx) {
        int healthPercent = ctx.localPlayer().getHealthPercent();

        if (recoveringHealth && healthPercent >= RESUME_AT_HEALTH_PERCENT) {
            recoveringHealth = false;
            log("Health recovered. Resuming combat");
            return false;
        }

        if (healthPercent <= EAT_AT_HEALTH_PERCENT && eatFood(ctx)) {
            return true;
        }

        if (healthPercent <= RETREAT_AT_HEALTH_PERCENT || recoveringHealth) {
            recoveringHealth = true;
            return retreatAndRecover(ctx, healthPercent);
        }

        return false;
    }

    private boolean retreatAndRecover(APIContext ctx, int healthPercent) {
        if (ctx.bank().isOpen()) {
            if (!hasFood(ctx)) {
                if (withdrawFoodFromBank(ctx)) {
                    ctx.bank().close();
                    Time.sleep(600, 900);
                }
                return true;
            }

            if (healthPercent <= EAT_AT_HEALTH_PERCENT) {
                ctx.bank().close();
                Time.sleep(600, 900);
                return true;
            }

            if (healthPercent < RESUME_AT_HEALTH_PERCENT) {
                log("Recovering health at bank: " + healthPercent + "%");
                Time.sleep(3000, 5000);
                return true;
            }

            ctx.bank().close();
            return true;
        }

        if (Navigation.isBankReachable(ctx)) {
            log("Opening bank while recovering health");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return true;
        }

        log("Low health. Retreating to nearest bank");
        walkToBank(ctx);
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean eatFood(APIContext ctx) {
        for (String food : BASIC_FOODS) {
            if (ctx.inventory().contains(food)) {
                log("Eating " + food + " for safety");
                ctx.inventory().interactItem("Eat", food);
                Time.sleep(600, 900);
                return true;
            }
        }

        return false;
    }

    private boolean hasFood(APIContext ctx) {
        for (String food : BASIC_FOODS) {
            if (ctx.inventory().contains(food)) {
                return true;
            }
        }

        return false;
    }

    private boolean ensureCombatFood(APIContext ctx) {
        if (ctx.localPlayer().isAttacking()) {
            return false;
        }

        int inventoryFood = inventoryFoodCount(ctx);
        if (pendingStarterFoodPurchase) {
            return handleStarterFoodPurchase(ctx);
        }

        if (!pendingStarterFoodPurchase && inventoryFood > 0) {
            return false;
        }

        if (!isBankOpen(ctx)) {
            if (inventoryFood > 0 && !isNearBankTarget(ctx)) {
                return false;
            }

            openBankOrWalk(ctx, "combat food");
            return true;
        }

        int bankFood = bankFoodCount(ctx);
        int totalFood = inventoryFood + bankFood;
        if (inventoryFood <= 0 && bankFood > 0) {
            return withdrawFoodFromBank(ctx);
        }

        if (pendingStarterFoodPurchase || totalFood <= LOW_TOTAL_FOOD_THRESHOLD) {
            pendingStarterFoodPurchase = true;
            return handleStarterFoodPurchase(ctx);
        }

        if (inventoryFood <= 0) {
            return withdrawFoodFromBank(ctx);
        }

        ctx.bank().close();
        Time.sleep(500, 800);
        return true;
    }

    private boolean withdrawFoodFromBank(APIContext ctx) {
        for (String food : BASIC_FOODS) {
            int bankCount = ctx.bank().getCount(food);
            if (bankCount > 0) {
                int emptySlots = ctx.inventory().getEmptySlotCount();
                if (emptySlots < MIN_COMBAT_FOOD_WITHDRAW && depositInventoryClutterBeforeFood(ctx)) {
                    return true;
                }

                emptySlots = ctx.inventory().getEmptySlotCount();
                if (emptySlots <= 0) {
                    log("No inventory space for combat food; waiting after cleanup attempt");
                    Time.sleep(600, 900);
                    return true;
                }

                int desiredAmount = randomInt(MIN_COMBAT_FOOD_WITHDRAW, MAX_COMBAT_FOOD_WITHDRAW);
                int amount = Math.max(1, Math.min(desiredAmount, Math.min(bankCount, emptySlots)));
                log("Withdrawing combat food: " + amount + "x " + food);
                ctx.bank().withdraw(amount, food);
                Time.sleep(600, 900);
                return true;
            }
        }

        log("No food found in bank; waiting to regenerate health");
        return false;
    }

    private boolean depositInventoryClutterBeforeFood(APIContext ctx) {
        if (!isBankOpen(ctx)) {
            return false;
        }

        boolean deposited = false;
        for (String itemName : GEAR_FUNDING_ITEMS) {
            if (ctx.inventory().contains(itemName)) {
                log("Depositing funding loot before combat food: " + itemName);
                ctx.bank().depositAll(itemName);
                deposited = true;
            }
        }

        for (String itemName : NON_SELLABLE_FUNDING_ITEMS) {
            if (!isCombatFoodName(itemName) && ctx.inventory().contains(itemName)) {
                log("Depositing restricted loot before combat food: " + itemName);
                ctx.bank().depositAll(itemName);
                deposited = true;
            }
        }

        for (String itemName : BANKABLE_COMBAT_LOOT) {
            if (!isCombatFoodName(itemName) && ctx.inventory().contains(itemName)) {
                log("Depositing combat loot before combat food: " + itemName);
                ctx.bank().depositAll(itemName);
                deposited = true;
            }
        }

        if (deposited) {
            Time.sleep(600, 900);
        }
        return deposited;
    }

    private int inventoryFoodCount(APIContext ctx) {
        int count = 0;
        for (String food : BASIC_FOODS) {
            count += ctx.inventory().getCount(food);
        }
        return count;
    }

    private int bankFoodCount(APIContext ctx) {
        int count = 0;
        for (String food : BASIC_FOODS) {
            count += ctx.bank().getCount(food);
        }
        return count;
    }

    private void ensureCombatTarget(APIContext ctx) {
        CombatPhase phase = currentCombatPhase(ctx);
        boolean weakAccount = isVeryWeakAccount(ctx);
        if (currentTarget == null
                || currentCombatPhase != phase
                || killsBeforeTargetSwitch <= 0
                || (!weakAccount && killsOnCurrentTarget >= killsBeforeTargetSwitch)
                || (weakAccount && !F2PCombatTargets.CHICKENS.key().equals(currentTarget.key()))) {
            switchCombatTarget(ctx, false);
        }
    }

    private void switchCombatTarget(APIContext ctx, boolean forceDifferent) {
        MobDefinition previous = currentTarget;
        currentCombatPhase = currentCombatPhase(ctx);
        currentTarget = isVeryWeakAccount(ctx)
                ? F2PCombatTargets.CHICKENS
                : F2PCombatTargets.pickRandomForLevel(combatTargetLevel(ctx), forceDifferent ? previous : null);
        killsOnCurrentTarget = 0;
        killsBeforeTargetSwitch = currentCombatPhase.randomKillsBeforeSwitch();
        targetSearchFailures = 0;
        targetTravelStartedAt = 0L;
        clearPendingAttackAttempt();
        clearRecentOwnLootSource();
        log("Combat target selected: " + currentTarget.primaryName()
                + " (" + currentCombatPhase.name().toLowerCase()
                + ", switch after ~" + killsBeforeTargetSwitch + " kills)");
    }

    private CombatPhase currentCombatPhase(APIContext ctx) {
        if (LOW_TIER_COMBAT_ONLY) {
            return CombatPhase.EARLY;
        }
        if (isVeryWeakAccount(ctx)) {
            return CombatPhase.EARLY;
        }
        return CombatPhase.forSkillLevel(combatTargetLevel(ctx));
    }

    private int combatTargetLevel(APIContext ctx) {
        if (trainingMode == TrainingMode.RANGED) {
            return ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        }

        int attack = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int strength = ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel();
        int defence = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        return Math.min(attack, Math.min(strength, defence));
    }

    private MobDefinition activeTarget(APIContext ctx) {
        ensureCombatTarget(ctx);
        return currentTarget;
    }

    private String[] targetNames(APIContext ctx) {
        return activeTarget(ctx).names();
    }

    private Area currentCombatArea(APIContext ctx) {
        return activeTarget(ctx).area();
    }

    private String[] currentLootNames(APIContext ctx) {
        return activeTarget(ctx).lootNames();
    }

    private boolean isCombatActionPending(APIContext ctx, Area combatArea) {
        if (isCombatEngaged(ctx)) {
            clearPendingAttackAttempt();
            return true;
        }

        long now = System.currentTimeMillis();
        if (pendingAttackUntil > now) {
            return true;
        }
        clearPendingAttackAttempt();

        if (stats.hasRecentAlreadyUnderAttackMessage()
                && findNpcInteractingWithMe(ctx, combatArea) == null) {
            return true;
        }

        return ctx.localPlayer().isMoving() && combatArea.contains(ctx.localPlayer().getLocation());
    }

    private boolean isCombatEngaged(APIContext ctx) {
        if (ctx.localPlayer().isAttacking() || ctx.localPlayer().isInCombat()) {
            return true;
        }

        Actor interacting = ctx.localPlayer().getInteracting();
        return interacting != null
                && interacting.isValid()
                && interacting.getName() != null
                && matchesAny(interacting.getName(), targetNames(ctx));
    }

    private void rememberAttackAttempt(NPC target) {
        pendingAttackTargetName = target.getName();
        pendingAttackTargetTile = target.getLocation();
        long lockMillis = trainingMode == TrainingMode.RANGED
                ? RANGED_ATTACK_CLICK_LOCK_MILLIS
                : MELEE_ATTACK_CLICK_LOCK_MILLIS;
        pendingAttackUntil = System.currentTimeMillis() + lockMillis;
    }

    private void clearPendingAttackAttempt() {
        pendingAttackTargetName = null;
        pendingAttackTargetTile = null;
        pendingAttackUntil = 0L;
    }

    private NPC findCombatTarget(APIContext ctx, Area combatArea) {
        NPC attacker = findNpcInteractingWithMe(ctx, combatArea);
        if (attacker != null) {
            log("Focusing " + attacker.getName() + " already attacking us");
            return attacker;
        }

        NPC target = ctx.npcs()
                .query()
                .named(targetNames(ctx))
                .actions("Attack")
                .notInCombat()
                .within(combatArea)
                .reachable()
                .results()
                .nearest();
        if (target != null && target.isValid()) {
            return target;
        }

        target = ctx.npcs()
                .query()
                .named(targetNames(ctx))
                .actions("Attack")
                .notInCombat()
                .within(combatArea)
                .results()
                .nearest();
        if (target != null && target.isValid()) {
            log("Found " + target.getName()
                    + " inside " + currentTargetLabel(ctx)
                    + " without reachable filter; attempting attack fallback");
            return target;
        }

        return null;
    }

    private NPC findNpcInteractingWithMe(APIContext ctx, Area combatArea) {
        NPC target = ctx.npcs()
                .query()
                .named(targetNames(ctx))
                .actions("Attack")
                .within(combatArea)
                .interactingWithMe()
                .results()
                .nearest();
        return target != null && target.isValid() ? target : null;
    }

    private String currentTargetLabel(APIContext ctx) {
        MobDefinition target = activeTarget(ctx);
        return target.primaryName() + " area";
    }

    private boolean isVeryWeakAccount(APIContext ctx) {
        int hitpoints = ctx.skills().get(Skill.Skills.HITPOINTS).getRealLevel();
        int attack = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int strength = ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel();
        int defence = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        int lowestMelee = Math.min(attack, Math.min(strength, defence));
        if (trainingMode == TrainingMode.RANGED) {
            int ranged = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
            return hitpoints < 12 || defence < MIN_DEFENCE_FOR_RANGED || ranged < 5;
        }
        return hitpoints < 12 || lowestMelee < 5;
    }

    private void updateCombatTracking(APIContext ctx) {
        boolean inCombat = ctx.localPlayer().isAttacking() || ctx.localPlayer().isInCombat();
        long now = System.currentTimeMillis();
        if (inCombat) {
            trackCombatLootSource(ctx);
        }
        if (wasInCombat && !inCombat && now - lastKillRecordedAt > 2500L) {
            int kills = stats.recordKill();
            killsOnCurrentTarget++;
            openRecentOwnLootWindow(ctx, now);
            clearPendingAttackAttempt();
            stats.setStatus("Combat ended. Kills: " + kills);
            lastKillRecordedAt = now;
        }
        if (!inCombat && recentOwnLootTile != null && now > recentOwnLootExpiresAt) {
            recentOwnLootTile = null;
        }
        wasInCombat = inCombat;
    }

    private void trackCombatLootSource(APIContext ctx) {
        lastCombatPlayerTile = ctx.localPlayer().getLocation();

        Actor interacting = ctx.localPlayer().getInteracting();
        if (interacting == null || !interacting.isValid() || interacting.getName() == null) {
            return;
        }

        if (currentTarget != null && matchesAny(interacting.getName(), currentTarget.names())) {
            lastCombatNpcTile = interacting.getLocation();
        }
    }

    private void openRecentOwnLootWindow(APIContext ctx, long now) {
        Tile lootTile = lastCombatNpcTile != null
                ? lastCombatNpcTile
                : lastCombatPlayerTile != null ? lastCombatPlayerTile : ctx.localPlayer().getLocation();
        recentOwnLootTile = lootTile;
        recentOwnLootExpiresAt = now + OWN_LOOT_WINDOW_MILLIS;
        lastCombatNpcTile = null;
        lastCombatPlayerTile = null;
        log("Tracking own loot near " + lootTile.getX() + "," + lootTile.getY());
    }

    private void clearRecentOwnLootSource() {
        lastCombatNpcTile = null;
        lastCombatPlayerTile = null;
        recentOwnLootTile = null;
        recentOwnLootExpiresAt = 0L;
    }

    private boolean closeWorldSwitcherBeforeCombat(APIContext ctx) {
        if (!ctx.world().isWorldMenuOpen()) {
            return false;
        }

        stats.setStatus("Closing world switcher before combat");
        log("Closing world switcher before combat action");
        ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
        Time.sleep(700, 1100, () -> !ctx.world().isWorldMenuOpen(), 100);
        return true;
    }

    private boolean handlePendingGearPurchase(APIContext ctx) {
        if (pendingGearPurchase == null) {
            woodcuttingFundingMode = false;
            return false;
        }

        if (hasNonSellableFundingItemInInventory(ctx)) {
            bankFundingLoot(ctx);
            return true;
        }

        if (pendingGearPurchaseOptional
                && isAmulet(pendingGearPurchase)
                && hasBetterOrEqualAmulet(ctx, pendingGearPurchase)) {
            log("Skipping optional amulet purchase; already have better/equal amulet than "
                    + pendingGearPurchase.name);
            clearPendingGearPurchase();
            return false;
        }

        if (woodcuttingFundingMode) {
            return handleWoodcuttingFunding(ctx);
        }

        if (isPendingGearPurchaseSatisfied(ctx)) {
            log("Gear upgrade obtained: " + pendingGearPurchase.name);
            if (closeGrandExchangeForEquipment(ctx, pendingGearPurchase.name)) {
                clearPendingGearPurchase();
                initialGearChecked = false;
                return true;
            }
            if (pendingStarterFoodPurchase && handleStarterFoodPurchase(ctx)) {
                return true;
            }
            clearPendingGearPurchase();
            initialGearChecked = false;
            return false;
        }

        if (ctx.bank().isOpen() && isPendingGearPurchaseSatisfied(ctx)) {
            log("Pending gear is now banked: " + pendingGearPurchase.name);
            if (pendingStarterFoodPurchase && handleStarterFoodPurchase(ctx)) {
                return true;
            }
            clearPendingGearPurchase();
            initialGearChecked = false;
            return false;
        }

        boolean mandatoryGearPurchase = !pendingGearPurchaseOptional;
        if (System.currentTimeMillis() < geRestrictionRetryAt && !mandatoryGearPurchase) {
            if (hasFundingItemInInventory(ctx)) {
                sellFundingItemsAtGe(ctx);
                return true;
            }
            if (hasNonSellableFundingItemInInventory(ctx)) {
                bankFundingLoot(ctx);
                return true;
            }
            return false;
        }

        stats.setGeRestricted(false);

        if (System.currentTimeMillis() < gearPurchaseDeferredUntil) {
            if (gearBuyOfferPending) {
                Time.sleep(600, 900);
                return true;
            }
            if (!mandatoryGearPurchase) {
                return false;
            }
            gearPurchaseDeferredUntil = 0;
        }

        int quantity = pendingPurchaseQuantity(ctx);
        int unitBuyPrice = buyPriceFor(ctx, pendingGearPurchase.name);
        int buyPrice = unitBuyPrice * quantity;

        if (ctx.inventory().getCount(true, "Coins") >= buyPrice) {
            buyPendingGearAtGe(ctx, buyPrice);
            return true;
        }

        if (hasFundingItemInInventory(ctx)) {
            sellFundingItemsAtGe(ctx);
            return true;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank to fund gear upgrade: " + pendingGearPurchase.name);
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Opening bank to fund gear upgrade: " + pendingGearPurchase.name);
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return true;
        }

        if (isPendingGearPurchaseSatisfied(ctx)) {
            pendingGearPurchase = null;
            initialGearChecked = false;
            return true;
        }

        int inventoryCoins = ctx.inventory().getCount(true, "Coins");
        int bankCoins = ctx.bank().getCount("Coins");
        if (inventoryCoins < buyPrice && bankCoins > 0) {
            int toWithdraw = Math.min(bankCoins, buyPrice - inventoryCoins);
            log("Withdrawing coins for gear upgrade: " + toWithdraw);
            ctx.bank().withdraw(toWithdraw, "Coins");
            Time.sleep(600, 900);
            return true;
        }

        if (inventoryCoins + bankCoins >= buyPrice) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return true;
        }

        if (withdrawFundingItemFromBank(ctx)) {
            return true;
        }

        logFundingBankSnapshot(ctx);
        if (pendingGearPurchaseOptional) {
            log("Need gold for desired " + pendingGearPurchase.name + "; deferring optional purchase");
            desiredGearPurchaseRetryAt = System.currentTimeMillis() + randomLong(30, 60) * 60_000L;
            clearPendingGearPurchase();
            ctx.bank().close();
            Time.sleep(600, 900);
            return false;
        }

        startWoodcuttingFunding(ctx, buyPrice, "no sellable funding loot found");
        ctx.bank().close();
        Time.sleep(600, 900);
        return true;
    }

    private void bankFundingLoot(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            log("Banking loot while GE is restricted");
            for (String itemName : fundingItemNames(ctx)) {
                ctx.bank().depositAll(itemName);
            }
            for (String itemName : NON_SELLABLE_FUNDING_ITEMS) {
                if (isCombatFoodName(itemName)) {
                    continue;
                }
                ctx.bank().depositAll(itemName);
            }
            Time.sleep(600, 900);
            ctx.bank().close();
            return;
        }

        if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank with loot while GE is restricted");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
        }

        log("Opening bank with loot while GE is restricted");
        Navigation.openBank(ctx);
        Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
    }

    private void buyPendingGearAtGe(APIContext ctx, int buyPrice) {
        boolean mandatoryGearPurchase = pendingGearPurchase != null && !pendingGearPurchaseOptional;
        if (!mandatoryGearPurchase && isGeTradeRestricted(ctx)) {
            activateGeRestrictionMode(ctx);
            return;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to buy " + pendingGearPurchase.name);
            walkToArea(ctx, GE_AREA, "GE");
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to buy " + pendingGearPurchase.name);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        if (!mandatoryGearPurchase && isGeTradeRestricted(ctx)) {
            activateGeRestrictionMode(ctx);
            return;
        }

        collectGeOffers(ctx);

        if (isPendingGearPurchaseSatisfied(ctx)) {
            log("Bought gear upgrade: " + pendingGearPurchase.name);
            ctx.grandExchange().close();
            clearPendingGearPurchase();
            initialGearChecked = false;
            Time.sleep(600, 900);
            return;
        }

        int quantity = pendingPurchaseQuantity(ctx);
        int unitBuyPrice = Math.max(1, buyPrice / Math.max(1, quantity));
        log("Buying gear upgrade: " + quantity + "x " + pendingGearPurchase.name + " for " + unitBuyPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(pendingGearPurchase.name, quantity, unitBuyPrice);
        if (!mandatoryGearPurchase && isGeTradeRestricted(ctx)) {
            activateGeRestrictionMode(ctx);
            return;
        }

        if (!placed) {
            if (confirmHighPriceGeOffer(ctx)) {
                return;
            }
            gearBuyOfferPending = false;
            log("Gear buy offer was not placed; retrying " + pendingGearPurchase.name);
            Time.sleep(1200, 1800);
            return;
        }

        if (placed) {
            Time.sleep(5000, 9000);
            collectGeOffers(ctx);
        }

        if (isPendingGearPurchaseSatisfied(ctx)) {
            log("Collected gear upgrade: " + pendingGearPurchase.name);
            ctx.grandExchange().close();
            clearPendingGearPurchase();
            initialGearChecked = false;
        } else {
            gearBuyOfferPending = true;
            log("Gear buy offer is pending: " + pendingGearPurchase.name);
            gearPurchaseDeferredUntil = System.currentTimeMillis() + randomLong(1, 3) * 60_000L;
        }

        Time.sleep(600, 900);
    }

    private boolean confirmHighPriceGeOffer(APIContext ctx) {
        WidgetChild yes = findGeWarningYesWidget(ctx);
        if (yes == null || !hasVisibleWidgetText(ctx, "much higher than the guide price")) {
            return false;
        }

        log("Confirming GE high-price warning");
        if (clickWidgetCenter(ctx, yes)
                || ctx.mouse().click(yes, false)
                || yes.click(false)
                || yes.click()
                || ctx.menu().interact("Yes", yes, true)
                || ctx.menu().interact("Yes", true)
                || ctx.menu().interact("Yes")) {
            gearBuyOfferPending = true;
            gearPurchaseDeferredUntil = System.currentTimeMillis() + randomLong(1, 2) * 10_000L;
            Time.sleep(1200, 1800);
            collectGeOffers(ctx);
            return true;
        }

        Time.sleep(600, 900);
        return true;
    }

    private WidgetChild findGeWarningYesWidget(APIContext ctx) {
        WidgetChild exactText = findVisibleWidgetByText(ctx, "Yes");
        if (exactText != null) {
            return exactText;
        }

        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            List<String> actions = candidate.getActions();
            if (actions == null) {
                return false;
            }

            for (String action : actions) {
                if ("Yes".equalsIgnoreCase(action == null ? "" : action.trim())) {
                    return true;
                }
            }
            return false;
        })) {
            return widget;
        }

        return null;
    }

    private boolean isPendingGearPurchaseSatisfied(APIContext ctx) {
        if (pendingGearPurchase == null) {
            return false;
        }
        if (isRangedArrow(pendingGearPurchase.name)) {
            return totalHeldCount(ctx, pendingGearPurchase.name) >= MIN_RANGED_ARROWS_EQUIPPED;
        }
        return ctx.equipment().contains(pendingGearPurchase.name)
                || ctx.inventory().contains(pendingGearPurchase.name)
                || (isBankOpen(ctx) && bankHasItem(ctx, pendingGearPurchase.name));
    }

    private int pendingPurchaseQuantity(APIContext ctx) {
        if (pendingGearPurchase == null || !isRangedArrow(pendingGearPurchase.name)) {
            return 1;
        }

        int held = totalHeldCount(ctx, pendingGearPurchase.name);
        int target = targetRangedArrowQuantity(pendingGearPurchase);
        return Math.max(1, target - held);
    }

    private boolean handleStarterFoodPurchase(APIContext ctx) {
        if (ctx.inventory().contains(STARTER_FOOD_NAME)) {
            if (ctx.grandExchange().isOpen() || GE_AREA.contains(ctx.localPlayer().getLocation())) {
                return bankPurchasedStarterFood(ctx);
            }

            pendingStarterFoodPurchase = false;
            log("Combat food already withdrawn; keeping " + ctx.inventory().getCount(STARTER_FOOD_NAME)
                    + "x " + STARTER_FOOD_NAME + " for combat");
            if (isBankOpen(ctx)) {
                closeBank(ctx);
                Time.sleep(500, 800);
                return true;
            }
            return false;
        }

        int bankFood = isBankOpen(ctx) ? bankFoodCount(ctx) : 0;
        if (isBankOpen(ctx) && inventoryFoodCount(ctx) <= 0 && bankFood > 0) {
            log("Using existing bank food before buying more: " + bankFood + " available");
            pendingStarterFoodPurchase = false;
            return withdrawFoodFromBank(ctx);
        }

        if (bankFood >= foodStockTarget) {
            log("Combat food stock ready: " + bankFood + "/" + foodStockTarget + " food");
            pendingStarterFoodPurchase = false;
            foodStockTarget = randomFoodStockTarget();
            if (inventoryFoodCount(ctx) <= 0) {
                return withdrawFoodFromBank(ctx);
            }
            return false;
        }

        int missingFood = foodToBuyForStock(ctx);
        int buyPrice = buyPriceForFood(ctx, STARTER_FOOD_NAME);
        int totalCost = foodPurchaseBudget(ctx);
        int inventoryCoins = ctx.inventory().getCount(true, "Coins");

        if (inventoryCoins < totalCost) {
            if (!isBankOpen(ctx)) {
                openBankOrWalk(ctx, "starter food coins");
                return true;
            }

            int bankCoins = ctx.bank().getCount("Coins");
            int availableCoins = inventoryCoins + bankCoins;
            int affordableFood = affordableFoodAmount(availableCoins, buyPrice);
            if (affordableFood >= MIN_STARTER_FOOD_BUY) {
                int affordableMissingFood = Math.min(missingFood, affordableFood);
                int affordableCost = affordableMissingFood * buyPrice;
                int toWithdraw = Math.min(bankCoins, Math.max(0, affordableCost - inventoryCoins));
                foodStockTarget = Math.min(foodStockTarget, bankFood + affordableMissingFood);
                missingFood = affordableMissingFood;
                totalCost = affordableCost;

                if (toWithdraw > 0) {
                    log("Withdrawing combat food coins: " + toWithdraw);
                    ctx.bank().withdraw(toWithdraw, "Coins");
                    Time.sleep(600, 900);
                    return true;
                }
            } else if (bankCoins <= 0) {
                log("No coins left for combat food; starting funding planner");
                startWoodcuttingFunding(ctx, 0, "combat food stock");
                closeBank(ctx);
                Time.sleep(600, 900);
                return true;
            } else {
                log("Not enough coins for a useful food batch; starting funding planner");
                startWoodcuttingFunding(ctx, 0, "combat food stock");
                closeBank(ctx);
                Time.sleep(600, 900);
                return true;
            }

            if (inventoryCoins < totalCost) {
                int toWithdraw = Math.min(bankCoins, totalCost - inventoryCoins);
                log("Withdrawing combat food coins: " + toWithdraw);
                ctx.bank().withdraw(toWithdraw, "Coins");
                Time.sleep(600, 900);
                return true;
            }
        }

        if (isBankOpen(ctx)) {
            closeBank(ctx);
            Time.sleep(600, 900);
            return true;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to buy combat food");
            walkToArea(ctx, GE_AREA, "GE");
            Time.sleep(1200, 1800);
            return true;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to buy combat food");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return true;
        }

        collectGeOffers(ctx);
        if (ctx.inventory().contains(STARTER_FOOD_NAME)) {
            return bankPurchasedStarterFood(ctx);
        }

        log("Buying combat food stock: " + missingFood + "x " + STARTER_FOOD_NAME
                + " at " + buyPrice + "gp each");
        boolean placed = ctx.grandExchange().placeBuyOffer(STARTER_FOOD_NAME, missingFood, buyPrice);
        if (isGeTradeRestricted(ctx)) {
            activateGeRestrictionMode(ctx);
            return true;
        }

        if (placed) {
            Time.sleep(5000, 9000);
            collectGeOffers(ctx);
        }

        if (ctx.inventory().contains(STARTER_FOOD_NAME)) {
            return bankPurchasedStarterFood(ctx);
        }

        Time.sleep(600, 900);
        return pendingStarterFoodPurchase;
    }

    private boolean bankPurchasedStarterFood(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return true;
        }

        if (!isBankOpen(ctx)) {
            openBankOrWalk(ctx, "storing combat food");
            return true;
        }

        log("Banking purchased combat food: " + ctx.inventory().getCount(STARTER_FOOD_NAME)
                + "x " + STARTER_FOOD_NAME);
        ctx.bank().depositAll(STARTER_FOOD_NAME);
        Time.sleep(600, 900);
        int bankFood = bankFoodCount(ctx);
        if (bankFood >= foodStockTarget) {
            pendingStarterFoodPurchase = false;
            log("Combat food stock banked: " + bankFood + "/" + foodStockTarget);
            foodStockTarget = randomFoodStockTarget();
        }
        return true;
    }

    private void sellFundingItemsAtGe(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to sell money-making loot");
            walkToArea(ctx, GE_AREA, "GE");
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to sell money-making loot");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        collectGeOffers(ctx);

        String itemName = firstFundingInventoryItem(ctx);
        if (itemName == null) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        int quantity = inventoryFundingItemCount(ctx, itemName);
        int sellPrice = sellPriceFor(ctx, itemName);
        log("Selling " + quantity + "x " + itemName + " at " + sellPrice + " each for " + fundingTargetName());
        boolean placed = ctx.grandExchange().placeSellOffer(itemName, quantity, sellPrice);
        if (isGeTradeRestrictedForItem(ctx, itemName)) {
            markFundingItemBlockedByGe(ctx, itemName);
            return;
        }

        if (placed) {
            Time.sleep(5000, 9000);
            collectGeOffers(ctx);
            gearPurchaseDeferredUntil = 0;
            closeGrandExchangeAfterTrade(ctx, "funding sale");
            return;
        }

        Time.sleep(600, 900);
    }

    private boolean handleObsoleteGearSale(APIContext ctx) {
        if (pendingGearPurchase != null && !pendingGearPurchaseOptional) {
            return false;
        }

        String itemName = firstObsoleteGearInventoryItem(ctx);
        if (itemName == null) {
            return false;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return true;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to sell old tier gear: " + itemName);
            walkToArea(ctx, GE_AREA, "GE");
            Time.sleep(1200, 1800);
            return true;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to sell old tier gear: " + itemName);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return true;
        }

        collectGeOffers(ctx);

        int quantity = inventoryFundingItemCount(ctx, itemName);
        if (quantity <= 0) {
            return true;
        }

        int sellPrice = sellPriceFor(ctx, itemName);
        log("Selling old tier gear: " + quantity + "x " + itemName + " at " + sellPrice + " each");
        boolean placed = ctx.grandExchange().placeSellOffer(itemName, quantity, sellPrice);
        if (isGeTradeRestrictedForItem(ctx, itemName)) {
            markFundingItemBlockedByGe(ctx, itemName);
            return true;
        }

        if (placed) {
            Time.sleep(5000, 9000);
            collectGeOffers(ctx);
            gearPurchaseDeferredUntil = 0;
            closeGrandExchangeAfterTrade(ctx, "old tier gear sale");
            return true;
        }

        Time.sleep(600, 900);
        return true;
    }

    private void startWoodcuttingFunding(APIContext ctx, int gearBuyPrice, String reason) {
        woodcuttingFundingMode = true;
        pendingStarterFoodPurchase = shouldFundStarterFood(ctx);
        woodcuttingFundingGearName = fundingTargetName();
        woodcuttingFundingTargetCoins = Math.max(
                gearBuyPrice + starterFoodBudget(ctx),
                gearBuyPrice + fundingPlanner.randomBufferCoins(STARTER_FOOD_BUFFER_COINS)
        );
        woodcuttingFundingChopFailures = 0;
        woodcuttingFundingBankAudited = false;
        woodcuttingFundingBankCoinsSnapshot = 0;
        woodcuttingFundingBankLogsSnapshot = 0;
        activeFundingDecision = null;
        gearPurchaseDeferredUntil = 0;

        int currentCoins = knownCoins(ctx);
        int logValue = Math.max(1, sellPriceFor(ctx, WC_FUNDING_LOG_NAME));
        int logsNeeded = (int) Math.ceil(Math.max(0, woodcuttingFundingTargetCoins - currentCoins) / (double) logValue);
        log("Starting FundingPlanner for " + woodcuttingFundingGearName
                + " (" + reason + "). Target: " + woodcuttingFundingTargetCoins
                + " coins, WC fallback est. logs: " + logsNeeded);
    }

    private boolean shouldFundStarterFood(APIContext ctx) {
        int totalFood = inventoryFoodCount(ctx);
        if (isBankOpen(ctx)) {
            totalFood += bankFoodCount(ctx);
        }
        return pendingStarterFoodPurchase || totalFood <= LOW_TOTAL_FOOD_THRESHOLD;
    }

    private boolean handleWoodcuttingFunding(APIContext ctx) {
        if (pendingGearPurchase == null && !pendingStarterFoodPurchase) {
            woodcuttingFundingMode = false;
            return false;
        }

        stats.setTrainingSkill("Woodcutting");
        if (!isGeRestrictionRetryActive()) {
            stats.setGeRestricted(false);
        }

        int targetCoins = woodcuttingFundingTargetCoins > 0
                ? woodcuttingFundingTargetCoins
                : (pendingGearPurchase == null ? 0 : buyPriceFor(ctx, pendingGearPurchase.name)) + starterFoodBudget(ctx);
        if (!ensureWoodcuttingFundingBankAudited(ctx, targetCoins)) {
            return true;
        }

        boolean geRestrictionActive = isGeRestrictionRetryActive();
        FundingPlanner.Decision decision = null;
        if (geRestrictionActive || knownCoins(ctx) < targetCoins) {
            decision = updateFundingDecision(ctx, targetCoins);
        }
        if (decision != null && decision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            if (hasFundingItemInInventory(ctx)) {
                sellFundingItemsAtGe(ctx);
                return true;
            }

            if (withdrawFundingItemFromBank(ctx)) {
                return true;
            }
        }

        if (hasNonSellableFundingItemInInventory(ctx)) {
            bankFundingLoot(ctx);
            return true;
        }

        if (knownCoins(ctx) >= targetCoins) {
            log("Funding target reached for " + fundingTargetName() + ": " + knownCoins(ctx) + "/" + targetCoins);
            if (geRestrictionActive) {
                log("GE restriction still active; deferring gear buy until retry window");
            }
            clearFundingState();
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(600, 900);
            }
            return true;
        }

        if (decision == null) {
            decision = updateFundingDecision(ctx, targetCoins);
        }

        if (decision.method() != FundingPlanner.Method.SELL_READY_STOCK
                && decision.method() != FundingPlanner.Method.WOODCUTTING) {
            executeFundingMoneyMaker(ctx, decision);
            return true;
        }

        if (hasFundingItemInInventory(ctx)
                && (decision.method() != FundingPlanner.Method.WOODCUTTING
                || hasNonWoodcuttingFundingItemInInventory(ctx))) {
            bankFundingLoot(ctx);
            return true;
        }

        if (hasNotedWcFundingLogsInInventory(ctx)) {
            sellWoodcuttingLogsAtGe(ctx);
            return true;
        }

        if (canReachFundingTargetWithKnownLogs(ctx, targetCoins)) {
            sellWoodcuttingLogsAtGe(ctx);
            return true;
        }

        if (ctx.inventory().contains(WC_FUNDING_LOG_NAME) && ctx.inventory().isFull()) {
            bankWoodcuttingFundingLogs(ctx, targetCoins);
            return true;
        }

        if (!ensureWoodcuttingFundingAxe(ctx)) {
            return true;
        }

        if (!prepareWoodcuttingFundingInventory(ctx)) {
            return true;
        }

        if (ctx.inventory().isFull()) {
            bankWoodcuttingFundingLogs(ctx, targetCoins);
            return true;
        }

        chopFundingLogs(ctx, targetCoins);
        return true;
    }

    private void executeFundingMoneyMaker(APIContext ctx, FundingPlanner.Decision decision) {
        if (ctx.grandExchange().isOpen() && !fundingMoneyMakerCanUseGrandExchange(decision.method())) {
            log("Closing GE before funding money maker: " + fundingMethodLabel(decision.method()));
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        logCombatDecision("FundingPlanner running " + fundingMethodLabel(decision.method())
                + " for " + fundingTargetName());
        FundingPlanner.Method method = decision.method();
        if (method == FundingPlanner.Method.BEER_GLASS) {
            beerGlassFundingModule.execute(ctx);
        } else if (method == FundingPlanner.Method.MINING_SMITHING) {
            miningSmithingFundingModule.execute(ctx);
        } else if (method == FundingPlanner.Method.FISHING_COOKING) {
            log("Fishing/Cooking is food training, not GP funding; rerolling funding method");
            activeFundingDecision = null;
        }
    }

    private boolean fundingMoneyMakerCanUseGrandExchange(FundingPlanner.Method method) {
        return method == FundingPlanner.Method.MINING_SMITHING;
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

    private String activeFundingMethodLabel() {
        if (activeFundingDecision == null || activeFundingDecision.method() == FundingPlanner.Method.WOODCUTTING) {
            return "Woodcutting";
        }
        return fundingMethodLabel(activeFundingDecision.method());
    }

    private void clearFundingState() {
        woodcuttingFundingMode = false;
        woodcuttingFundingTargetCoins = 0;
        woodcuttingFundingGearName = null;
        woodcuttingFundingChopFailures = 0;
        woodcuttingFundingBankAudited = false;
        woodcuttingFundingBankCoinsSnapshot = 0;
        woodcuttingFundingBankLogsSnapshot = 0;
        activeFundingDecision = null;
        if (isGeRestrictionRetryActive()) {
            gearPurchaseDeferredUntil = Math.max(gearPurchaseDeferredUntil, geRestrictionRetryAt);
        } else {
            geRestrictionRetryAt = 0;
            gearPurchaseDeferredUntil = 0;
        }
    }

    private boolean isGeRestrictionRetryActive() {
        return System.currentTimeMillis() < geRestrictionRetryAt;
    }

    private int starterFoodBudget(APIContext ctx) {
        return Math.max(STARTER_FOOD_BUFFER_COINS, foodPurchaseBudget(ctx));
    }

    private int foodPurchaseBudget(APIContext ctx) {
        long budget = (long) foodToBuyForStock(ctx) * buyPriceForFood(ctx, STARTER_FOOD_NAME);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, budget));
    }

    private int foodToBuyForStock(APIContext ctx) {
        int bankFood = isBankOpen(ctx) ? bankFoodCount(ctx) : 0;
        return Math.max(1, foodStockTarget - bankFood);
    }

    private int affordableFoodAmount(int coins, int buyPrice) {
        return Math.max(0, (coins - STARTER_FOOD_BUFFER_COINS) / Math.max(1, buyPrice));
    }

    private int buyPriceForFood(APIContext ctx, String itemName) {
        return GePricing.quickBuyPrice(ctx, itemName, 15L);
    }

    private int knownCoins(APIContext ctx) {
        int coins = ctx.inventory().getCount(true, "Coins");
        if (isBankOpen(ctx)) {
            coins += ctx.bank().getCount("Coins");
        } else if (woodcuttingFundingMode && woodcuttingFundingBankAudited) {
            coins += woodcuttingFundingBankCoinsSnapshot;
        }
        return coins;
    }

    private int knownWoodcuttingLogs(APIContext ctx) {
        int logs = inventoryWcFundingLogCount(ctx);
        if (ctx.bank().isOpen()) {
            logs += ctx.bank().getCount(WC_FUNDING_LOG_NAME);
        } else if (woodcuttingFundingMode && woodcuttingFundingBankAudited) {
            logs += woodcuttingFundingBankLogsSnapshot;
        }
        return logs;
    }

    private FundingPlanner.Decision updateFundingDecision(APIContext ctx, int targetCoins) {
        if (activeFundingDecision != null
                && activeFundingDecision.method() == FundingPlanner.Method.SELL_READY_STOCK
                && !isFundingItemBlockedByGe(activeFundingDecision.itemName())
                && (inventoryFundingItemCount(ctx, activeFundingDecision.itemName()) > 0
                || fundingItemCountInBank(ctx, activeFundingDecision.itemName()) > 0)) {
            return activeFundingDecision;
        }

        FundingPlanner.Decision planned = fundingPlanner.choose(targetCoins, knownCoins(ctx), fundingAssets(ctx));
        if (planned.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            activeFundingDecision = planned;
            log("FundingPlanner selected stock sale: "
                    + activeFundingDecision.itemName()
                    + " inv=" + activeFundingDecision.inventoryCount()
                    + " bank=" + activeFundingDecision.bankCount()
                    + " projected~" + activeFundingDecision.projectedValue() + "gp");
            stats.setFundingReason("Stock sale: " + activeFundingDecision.itemName()
                    + " for " + fundingTargetName());
            return activeFundingDecision;
        }

        if (activeFundingDecision != null
                && activeFundingDecision.method() != FundingPlanner.Method.SELL_READY_STOCK) {
            return activeFundingDecision;
        }

        activeFundingDecision = planned;
        if (activeFundingDecision.method() == FundingPlanner.Method.SELL_READY_STOCK) {
            log("FundingPlanner selected stock sale: "
                    + activeFundingDecision.itemName()
                    + " inv=" + activeFundingDecision.inventoryCount()
                    + " bank=" + activeFundingDecision.bankCount()
                    + " projected~" + activeFundingDecision.projectedValue() + "gp");
            stats.setFundingReason("Stock sale: " + activeFundingDecision.itemName()
                    + " for " + fundingTargetName());
        } else {
            log("FundingPlanner selected " + fundingMethodLabel(activeFundingDecision.method())
                    + " for " + fundingTargetName());
            stats.setFundingReason(fundingMethodLabel(activeFundingDecision.method())
                    + " for " + fundingTargetName());
        }
        return activeFundingDecision;
    }

    private List<FundingPlanner.Asset> fundingAssets(APIContext ctx) {
        List<FundingPlanner.Asset> assets = new ArrayList<>();
        for (String itemName : fundingItemNames(ctx)) {
            if (isFundingItemBlockedByGe(itemName)) {
                continue;
            }
            assets.add(new FundingPlanner.Asset(
                    itemName,
                    inventoryFundingItemCount(ctx, itemName),
                    ctx.bank().isOpen() ? fundingItemCountInBank(ctx, itemName) : 0,
                    sellPriceFor(ctx, itemName)
            ));
        }
        return assets;
    }

    private boolean ensureWoodcuttingFundingBankAudited(APIContext ctx, int targetCoins) {
        if (woodcuttingFundingBankAudited) {
            return true;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Checking bank coins before funding");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return false;
            }

            log("Opening bank to audit coins before funding");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return false;
        }

        woodcuttingFundingBankCoinsSnapshot = ctx.bank().getCount("Coins");
        woodcuttingFundingBankLogsSnapshot = ctx.bank().getCount(WC_FUNDING_LOG_NAME);
        woodcuttingFundingBankAudited = true;
        int totalCoins = ctx.inventory().getCount(true, "Coins") + woodcuttingFundingBankCoinsSnapshot;
        log("Funding coin audit: inv=" + ctx.inventory().getCount(true, "Coins")
                + " bank=" + woodcuttingFundingBankCoinsSnapshot
                + " logs=" + woodcuttingFundingBankLogsSnapshot
                + " target=" + targetCoins);

        if (totalCoins >= targetCoins) {
            log("Bank already has enough coins for " + fundingTargetName() + "; skipping funding");
            clearFundingState();
            return false;
        }

        return true;
    }

    private String fundingTargetName() {
        if (pendingGearPurchase != null) {
            return pendingGearPurchase.name;
        }
        if (pendingStarterFoodPurchase) {
            return "combat food";
        }
        return "funding";
    }

    private boolean canReachFundingTargetWithKnownLogs(APIContext ctx, int targetCoins) {
        int logs = knownWoodcuttingLogs(ctx);
        long projected = knownCoins(ctx) + (long) sellPriceFor(ctx, WC_FUNDING_LOG_NAME) * logs;
        return projected >= targetCoins;
    }

    private void bankWoodcuttingFundingLogs(APIContext ctx, int targetCoins) {
        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank to store WC funding logs");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank to store WC funding logs");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (ctx.inventory().contains(WC_FUNDING_LOG_NAME)) {
            log("Banking WC funding logs");
            ctx.bank().depositAll(WC_FUNDING_LOG_NAME);
            Time.sleep(600, 900);
        }

        woodcuttingFundingBankCoinsSnapshot = ctx.bank().getCount("Coins");
        woodcuttingFundingBankLogsSnapshot = ctx.bank().getCount(WC_FUNDING_LOG_NAME);
        woodcuttingFundingBankAudited = true;

        int logsKnown = knownWoodcuttingLogs(ctx);
        int logsNeeded = logsNeededForFunding(ctx, targetCoins);
        log("WC funding logs banked: " + logsKnown + "/" + logsNeeded
                + " logs, coins " + knownCoins(ctx) + "/" + targetCoins);

        if (canReachFundingTargetWithKnownLogs(ctx, targetCoins)) {
            log("WC funding logs target reached; preparing GE sale");
            return;
        }

        ctx.bank().close();
        Time.sleep(600, 900);
    }

    private int logsNeededForFunding(APIContext ctx, int targetCoins) {
        int logValue = Math.max(1, sellPriceFor(ctx, WC_FUNDING_LOG_NAME));
        return (int) Math.ceil(Math.max(0, targetCoins - knownCoins(ctx)) / (double) logValue);
    }

    private boolean ensureWoodcuttingFundingAxe(APIContext ctx) {
        for (String axe : WC_FUNDING_AXES) {
            if (inventoryContainsUsableItem(ctx, axe) || ctx.equipment().contains(axe)) {
                return true;
            }
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for WC funding axe");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return false;
            }

            log("Opening bank for WC funding axe");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return false;
        }

        if (hasNotedWcFundingAxeInInventory(ctx)) {
            log("Banking noted WC funding axe before withdrawing usable axe");
            ctx.bank().depositAll(item -> matchesAny(item.getName(), WC_FUNDING_AXES) && isEffectivelyNoted(item));
            Time.sleep(600, 900);
            return false;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            log("Selecting item withdraw mode for WC funding axe");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
            return false;
        }

        for (String axe : WC_FUNDING_AXES) {
            if (bankHasItem(ctx, axe)) {
                log("Withdrawing WC funding axe: " + axe);
                withdrawOneFromBank(ctx, axe);
                Time.sleep(600, 900);
                return false;
            }
        }

        log("No axe available for WC funding; returning to combat money maker");
        woodcuttingFundingMode = false;
        ctx.bank().close();
        Time.sleep(600, 900);
        return false;
    }

    private boolean prepareWoodcuttingFundingInventory(APIContext ctx) {
        if (woodcuttingFundingInventoryIsClean(ctx)) {
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(600, 900);
                return false;
            }
            return true;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank to clean WC funding inventory");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return false;
            }

            log("Opening bank to clean WC funding inventory");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return false;
        }

        log("Depositing non-WC funding items");
        ctx.bank().depositAllExcept(WC_FUNDING_KEEP_ITEMS);
        Time.sleep(600, 900);
        return false;
    }

    private boolean woodcuttingFundingInventoryIsClean(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!matchesAny(item.getName(), WC_FUNDING_KEEP_ITEMS)) {
                return false;
            }
        }
        return true;
    }

    private void chopFundingLogs(APIContext ctx, int targetCoins) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!WC_FUNDING_TREES.contains(ctx.localPlayer().getLocation())) {
            walkToWcFundingTrees(ctx, "Walking to safe Lumbridge trees for WC funding");
            Time.sleep(1200, 1800);
            return;
        }

        if (woodcuttingFundingChopFailures >= MAX_WC_FUNDING_CHOP_FAILURES) {
            woodcuttingFundingChopFailures = 0;
            walkToWcFundingTrees(ctx, "WC funding seems stuck near obstacle; repositioning");
            Time.sleep(1200, 1800);
            return;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            Time.sleep(600, 900);
            return;
        }

        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.COMBAT_OPTIONS)) {
            ctx.tabs().open(ITabsAPI.Tabs.COMBAT_OPTIONS);
            Time.sleep(350, 650);
            return;
        }

        SceneObject tree = ctx.objects()
                .query()
                .named("Tree", "Dead tree")
                .actions("Chop down")
                .within(WC_FUNDING_TREES)
                .reachable()
                .results()
                .nearest();
        if (tree == null || !tree.isValid()) {
            woodcuttingFundingChopFailures++;
            log("No WC funding tree found");
            Time.sleep(900, 1400);
            return;
        }

        int beforeLogs = ctx.inventory().getCount(WC_FUNDING_LOG_NAME);
        if (tree.interact("Chop down")) {
            log("WC funding: chopping logs for " + woodcuttingFundingGearName
                    + " (" + knownCoins(ctx) + "/" + targetCoins + " coins, "
                    + knownWoodcuttingLogs(ctx) + "/" + logsNeededForFunding(ctx, targetCoins) + " logs)");
            Time.sleep(
                    1800,
                    3000,
                    () -> ctx.localPlayer().isAnimating()
                            || ctx.inventory().getCount(WC_FUNDING_LOG_NAME) > beforeLogs
                            || ctx.inventory().isFull(),
                    100
            );
            Time.sleep(
                    1200,
                    4500,
                    () -> ctx.inventory().getCount(WC_FUNDING_LOG_NAME) > beforeLogs
                            || ctx.inventory().isFull()
                            || (!ctx.localPlayer().isAnimating() && !ctx.localPlayer().isMoving()),
                    100
            );

            if (ctx.inventory().getCount(WC_FUNDING_LOG_NAME) > beforeLogs) {
                woodcuttingFundingChopFailures = 0;
            } else if (!ctx.localPlayer().isAnimating()) {
                woodcuttingFundingChopFailures++;
                log("WC funding did not gain logs; will reposition if it repeats");
            }
            return;
        }

        woodcuttingFundingChopFailures++;
        log("Could not click WC funding tree; will reposition if it repeats");
        Time.sleep(700, 1100);
    }

    private void walkToWcFundingTrees(APIContext ctx, String reason) {
        log(reason);
        walkToTile(ctx, randomWcFundingTile(), "Lumbridge WC funding trees");
    }

    private Tile randomWcFundingTile() {
        return WC_FUNDING_SAFE_TILES[ThreadLocalRandom.current().nextInt(WC_FUNDING_SAFE_TILES.length)];
    }

    private void sellWoodcuttingLogsAtGe(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            if (hasUnnotedWcFundingLogsInInventory(ctx)) {
                log("Banking unnoted WC funding logs before noted withdrawal");
                ctx.bank().depositAll(item -> namesMatch(item.getName(), WC_FUNDING_LOG_NAME) && !item.isNoted());
                Time.sleep(600, 900);
                return;
            }

            if (!woodcuttingFundingSaleInventoryIsClean(ctx)) {
                log("Clearing inventory space for WC funding log sale");
                ctx.bank().depositAllExcept(WC_FUNDING_SALE_KEEP_ITEMS);
                Time.sleep(600, 900);
                return;
            }

            int bankLogs = ctx.bank().getCount(WC_FUNDING_LOG_NAME);
            woodcuttingFundingBankLogsSnapshot = bankLogs;
            woodcuttingFundingBankCoinsSnapshot = ctx.bank().getCount("Coins");
            if (bankLogs > 0) {
                if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
                    log("Selecting noted withdraw mode for WC funding logs");
                    ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
                    Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
                    return;
                }

                log("Withdrawing all WC funding logs as notes for GE sale");
                if (ctx.bank().withdrawAll(WC_FUNDING_LOG_NAME)) {
                    Time.sleep(600, 900);
                }
                woodcuttingFundingBankLogsSnapshot = ctx.bank().getCount(WC_FUNDING_LOG_NAME);
                woodcuttingFundingBankCoinsSnapshot = ctx.bank().getCount("Coins");
                return;
            }

            if (ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
                log("Restoring item withdraw mode after noted log withdrawal");
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
                return;
            }

            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (!hasNotedWcFundingLogsInInventory(ctx)
                || hasUnnotedWcFundingLogsInInventory(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to bank for noted WC funding logs");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }
            log("Opening bank for noted WC funding logs");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        if (!GE_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to GE to sell WC funding logs");
            walkToArea(ctx, GE_AREA, "GE");
            Time.sleep(1200, 1800);
            return;
        }

        if (!ctx.grandExchange().isOpen()) {
            log("Opening GE to sell WC funding logs");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return;
        }

        collectGeOffers(ctx);

        int quantity = inventoryWcFundingLogCount(ctx);
        if (quantity <= 0) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        int sellPrice = sellPriceFor(ctx, WC_FUNDING_LOG_NAME);
        log("Selling " + quantity + "x logs for WC funding");
        boolean placed = ctx.grandExchange().placeSellOffer(WC_FUNDING_LOG_NAME, quantity, sellPrice);
        if (placed) {
            Time.sleep(5000, 9000);
            collectGeOffers(ctx);
            gearPurchaseDeferredUntil = 0;
            closeGrandExchangeAfterTrade(ctx, "WC funding log sale");
            return;
        }

        Time.sleep(600, 900);
    }

    private boolean woodcuttingFundingSaleInventoryIsClean(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!matchesAny(item.getName(), WC_FUNDING_SALE_KEEP_ITEMS)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasUnnotedWcFundingLogsInInventory(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null
                    && namesMatch(item.getName(), WC_FUNDING_LOG_NAME)
                    && !isNotedWcFundingLog(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNotedWcFundingLogsInInventory(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null
                    && namesMatch(item.getName(), WC_FUNDING_LOG_NAME)
                    && isNotedWcFundingLog(item)) {
                return true;
            }
        }
        return false;
    }

    private int inventoryWcFundingLogCount(APIContext ctx) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && namesMatch(item.getName(), WC_FUNDING_LOG_NAME)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private boolean isNotedWcFundingLog(ItemWidget item) {
        return isEffectivelyNoted(item) || item.getStackSize() > 1;
    }

    private boolean inventoryContainsUsableItem(APIContext ctx, String name) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && namesMatch(item.getName(), name) && !isEffectivelyNoted(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNotedWcFundingAxeInInventory(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && matchesAny(item.getName(), WC_FUNDING_AXES) && isEffectivelyNoted(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEffectivelyNoted(Item item) {
        return item.isNoted() || (item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId());
    }

    private boolean isGeTradeRestricted(APIContext ctx) {
        if (isBankOpen(ctx)) {
            return false;
        }

        if (!ctx.grandExchange().isOpen() && !hasWidgetText(ctx, "Grand Exchange")) {
            return false;
        }

        return hasWidgetText(ctx, "restricted for trading")
                || hasWidgetText(ctx, "restricted for Trading")
                || hasWidgetText(ctx, "account will be restricted")
                || hasWidgetText(ctx, "account is currently restricted");
    }

    private boolean openBankOrWalk(APIContext ctx, String reason) {
        if (isBankOpen(ctx)) {
            return true;
        }

        if (Navigation.shouldAvoidNearestBank(ctx)) {
            log("Walking to F2P bank for " + reason);
            walkToBank(ctx);
            Time.sleep(1200, 1800);
            return true;
        }

        log("Opening bank directly for " + reason);
        Navigation.openBank(ctx);
        Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
        if (isBankOpen(ctx)) {
            return true;
        }

        SceneObject bankObject = nearestBankObject(ctx);
        if (bankObject != null) {
            log("Opening bank object for " + reason + ": " + bankObject.getName());
            if (bankObject.interact("Bank")) {
                Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
                if (isBankOpen(ctx)) {
                    return true;
                }
            }
        }

        NPC banker = nearestBanker(ctx);
        if (banker != null) {
            log("Opening banker for " + reason);
            if (banker.interact("Bank")) {
                Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
                if (isBankOpen(ctx)) {
                    return true;
                }
            }
        }

        log("Walking to bank for " + reason);
        walkToBank(ctx);
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean isNearBankTarget(APIContext ctx) {
        if (Navigation.shouldAvoidNearestBank(ctx)) {
            return false;
        }

        return Navigation.isBankReachable(ctx)
                || ctx.bank().isVisible()
                || nearestBankObject(ctx) != null
                || nearestBanker(ctx) != null;
    }

    private SceneObject nearestBankObject(APIContext ctx) {
        if (Navigation.shouldAvoidNearestBank(ctx)) {
            return null;
        }

        SceneObject bankObject = ctx.objects()
                .query()
                .actions("Bank")
                .tileDistance(16)
                .results()
                .nearest();
        if (bankObject != null) {
            return bankObject;
        }

        return ctx.objects()
                .query()
                .nameContains("Bank booth", "Bank chest")
                .tileDistance(16)
                .results()
                .nearest();
    }

    private NPC nearestBanker(APIContext ctx) {
        if (Navigation.shouldAvoidNearestBank(ctx)) {
            return null;
        }

        return ctx.npcs()
                .query()
                .named("Banker")
                .actions("Bank")
                .tileDistance(16)
                .results()
                .nearest();
    }

    private boolean isBankOpen(APIContext ctx) {
        return ctx.bank().isOpen()
                || hasWidgetText(ctx, "The Bank of Gielinor")
                || hasWidgetText(ctx, "Bank of Gielinor");
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

    private boolean hasWidgetText(APIContext ctx, String text) {
        WidgetChild widget = ctx.widgets()
                .query()
                .textContains(text)
                .results()
                .first();
        return widget != null && widget.isValid();
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
        return findVisibleWidgetContaining(ctx, text) != null;
    }

    private WidgetChild findVisibleWidgetContaining(APIContext ctx, String text) {
        if (text == null || text.isBlank()) {
            return null;
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
            return widget;
        }

        return null;
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

    private void activateGeRestrictionMode(APIContext ctx) {
        stats.setGeRestricted(true);
        geRestrictionRetryAt = System.currentTimeMillis() + randomLong(25, 40) * 60_000L;
        gearPurchaseDeferredUntil = geRestrictionRetryAt;
        log("GE restriction detected for current loot");

        if (pendingGearPurchaseOptional) {
            desiredGearPurchaseRetryAt = geRestrictionRetryAt;
            log("Desired gear purchase deferred until GE appears unlocked");
            clearPendingGearPurchase();
        } else if (pendingGearPurchase != null) {
            startWoodcuttingFunding(ctx, buyPriceFor(ctx, pendingGearPurchase.name), "restricted loot sale");
        } else if (pendingStarterFoodPurchase) {
            startWoodcuttingFunding(ctx, 0, "restricted combat food buy");
        }

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(800, 1400);
            return;
        }

        if (ctx.widgets().isInterfaceOpen()) {
            ctx.widgets().closeInterface();
            Time.sleep(800, 1400);
        }
    }

    private void collectGeOffers(APIContext ctx) {
        try {
            ctx.grandExchange().collectToInventory();
            Time.sleep(600, 900);
            ctx.grandExchange().collectToBank();
            Time.sleep(600, 900);
        } catch (RuntimeException ignored) {
            // GE collection can fail when no offer is ready.
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

    private boolean isGeTradeRestrictedForItem(APIContext ctx, String itemName) {
        if (isKnownUnrestrictedFundingItem(itemName)) {
            return false;
        }
        return isGeTradeRestricted(ctx);
    }

    private boolean isKnownUnrestrictedFundingItem(String itemName) {
        return F2PItemRegistry.isGeSellable(itemName)
                && F2PItemRegistry.minimumSaleBatch(itemName) > 0;
    }

    private void markFundingItemBlockedByGe(APIContext ctx, String itemName) {
        geBlockedFundingItems.add(normalizedName(itemName));
        activeFundingDecision = null;
        log("GE restriction applies to " + itemName + "; avoiding this item and trying another funding stock");

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.widgets().isInterfaceOpen()) {
            ctx.widgets().closeInterface();
            Time.sleep(600, 900);
        }
    }

    private boolean isFundingItemBlockedByGe(String itemName) {
        return geBlockedFundingItems.contains(normalizedName(itemName));
    }

    private boolean withdrawFundingItemFromBank(APIContext ctx) {
        if (activeFundingDecision != null
                && activeFundingDecision.method() == FundingPlanner.Method.SELL_READY_STOCK
                && !isFundingItemBlockedByGe(activeFundingDecision.itemName())
                && withdrawFundingItemFromBank(ctx, activeFundingDecision.itemName())) {
            return true;
        }

        for (String itemName : fundingItemNames(ctx)) {
            if (isFundingItemBlockedByGe(itemName)) {
                continue;
            }
            if (withdrawFundingItemFromBank(ctx, itemName)) {
                return true;
            }
        }

        return false;
    }

    private boolean withdrawFundingItemFromBank(APIContext ctx, String itemName) {
        int count = fundingItemCountInBank(ctx, itemName);
        if (count <= 0) {
            return false;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            log("Selecting noted withdraw mode for funding stock: " + itemName);
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
            return true;
        }

        log("Withdrawing " + count + "x " + itemName + " to sell for " + fundingTargetName());
        if (ctx.bank().withdrawAll(itemName)
                || ctx.bank().withdraw(count, itemName)
                || ctx.bank().interactItem("Withdraw-All", itemName)
                || ctx.bank().interactItem("Withdraw All", itemName)) {
            Time.sleep(600, 900);
            ctx.bank().close();
            Time.sleep(600, 900);
            return true;
        }

        return false;
    }

    private boolean withdrawObsoleteGearFromBank(APIContext ctx) {
        if (!ctx.bank().isOpen()
                || (pendingGearPurchase != null && !pendingGearPurchaseOptional)) {
            return false;
        }

        for (String itemName : obsoleteGearSaleItemNames(ctx)) {
            if (isFundingItemBlockedByGe(itemName)) {
                continue;
            }
            int count = fundingItemCountInBank(ctx, itemName);
            if (count <= 0) {
                continue;
            }

            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
                log("Selecting noted withdraw mode for old tier gear: " + itemName);
                ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
                Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
                return true;
            }

            log("Withdrawing old tier gear to sell: " + count + "x " + itemName);
            if (ctx.bank().withdrawAll(itemName)
                    || ctx.bank().withdraw(count, itemName)
                    || ctx.bank().interactItem("Withdraw-All", itemName)
                    || ctx.bank().interactItem("Withdraw All", itemName)) {
                Time.sleep(600, 900);
                ctx.bank().close();
                Time.sleep(600, 900);
                return true;
            }
        }

        return false;
    }

    private int fundingItemCountInBank(APIContext ctx, String itemName) {
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

    private void logFundingBankSnapshot(APIContext ctx) {
        StringBuilder message = new StringBuilder("Funding bank snapshot:");
        boolean foundFunding = false;
        for (String itemName : fundingItemNames(ctx)) {
            if (isFundingItemBlockedByGe(itemName)) {
                message.append(' ').append(itemName).append("=blocked");
                continue;
            }
            int count = fundingItemCountInBank(ctx, itemName);
            if (count > 0) {
                foundFunding = true;
            }
            message.append(' ').append(itemName).append('=').append(count);
        }
        message.append(" visibleItems=").append(ctx.bank().getItems().size());
        log(message.toString());
        if (!foundFunding) {
            logVisibleFundingBankItemsOnce(ctx);
        }
    }

    private boolean hasFundingItemInInventory(APIContext ctx) {
        return firstFundingInventoryItem(ctx) != null;
    }

    private boolean hasNonSellableFundingItemInInventory(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null
                    && !isCombatFoodName(item.getName())
                    && matchesAny(item.getName(), NON_SELLABLE_FUNDING_ITEMS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCombatFoodName(String itemName) {
        return F2PItemRegistry.isFood(itemName);
    }

    private boolean hasNonWoodcuttingFundingItemInInventory(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (isFundingItemName(ctx, item.getName())
                    && !namesMatch(item.getName(), WC_FUNDING_LOG_NAME)) {
                return true;
            }
        }
        return false;
    }

    private String firstFundingInventoryItem(APIContext ctx) {
        if (activeFundingDecision != null
                && activeFundingDecision.method() == FundingPlanner.Method.SELL_READY_STOCK
                && !isFundingItemBlockedByGe(activeFundingDecision.itemName())
                && inventoryFundingItemCount(ctx, activeFundingDecision.itemName()) > 0) {
            return activeFundingDecision.itemName();
        }

        for (String itemName : fundingItemNames(ctx)) {
            if (isFundingItemBlockedByGe(itemName)) {
                continue;
            }
            if (inventoryFundingItemCount(ctx, itemName) > 0) {
                return itemName;
            }
        }

        return null;
    }

    private String firstObsoleteGearInventoryItem(APIContext ctx) {
        for (String itemName : obsoleteGearSaleItemNames(ctx)) {
            if (!isFundingItemBlockedByGe(itemName)
                    && inventoryFundingItemCount(ctx, itemName) > 0) {
                return itemName;
            }
        }
        return null;
    }

    private int inventoryFundingItemCount(APIContext ctx, String itemName) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && namesMatch(item.getName(), itemName)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private List<String> fundingItemNames(APIContext ctx) {
        List<String> names = new ArrayList<>();
        for (String itemName : GEAR_FUNDING_ITEMS) {
            addFundingItemName(names, itemName);
        }
        for (String itemName : obsoleteGearSaleItemNames(ctx)) {
            if (inventoryFundingItemCount(ctx, itemName) > 0
                    || (isBankOpen(ctx) && fundingItemCountInBank(ctx, itemName) > 0)) {
                addFundingItemName(names, itemName);
            }
        }
        return names;
    }

    private boolean isFundingItemName(APIContext ctx, String itemName) {
        for (String fundingItemName : fundingItemNames(ctx)) {
            if (namesMatch(itemName, fundingItemName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> obsoleteGearSaleItemNames(APIContext ctx) {
        List<String> names = new ArrayList<>();

        int attackLevel = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        addLowerTierGearNames(names, desiredGearForLevel(attackLevel, WEAPONS), WEAPONS);
        addLowerTierGearNames(names, desiredGearForLevel(defenceLevel, HELMETS), HELMETS);
        addLowerTierGearNames(names, desiredMeleeSetGearForLevel(defenceLevel, BODIES), BODIES);
        addLowerTierGearNames(names, desiredMeleeSetGearForLevel(defenceLevel, LEGS), LEGS);
        addLowerTierGearNames(names, desiredMeleeSetGearForLevel(defenceLevel, SHIELDS), SHIELDS);

        int rangedLevel = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        int rangedArmorLevel = rangedArmorLevel(ctx);
        addLowerTierGearNames(names, desiredRangedWeapon(rangedLevel), RANGED_WEAPONS);
        addLowerTierGearNames(names, desiredRangedArrow(rangedLevel), RANGED_AMMO);
        addLowerTierGearNames(names, desiredGearForExactLevel(rangedArmorLevel, RANGED_HELMETS), RANGED_HELMETS);
        addLowerTierGearNames(names, desiredGearForExactLevel(rangedArmorLevel, RANGED_BODIES), RANGED_BODIES);
        addLowerTierGearNames(names, desiredGearForExactLevel(rangedArmorLevel, RANGED_LEGS), RANGED_LEGS);
        addLowerTierGearNames(names, desiredGearForExactLevel(rangedArmorLevel, RANGED_GLOVES), RANGED_GLOVES);

        return names;
    }

    private void addLowerTierGearNames(List<String> names, GearItem desired, GearItem[] items) {
        if (desired == null || items == null) {
            return;
        }

        for (GearItem item : items) {
            if (item != null && item.requiredLevel < desired.requiredLevel) {
                addFundingItemName(names, item.name);
            }
        }
    }

    private void addFundingItemName(List<String> names, String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return;
        }
        if (!F2PItemRegistry.isGeSellable(itemName)) {
            return;
        }
        for (String existing : names) {
            if (namesMatch(existing, itemName)) {
                return;
            }
        }
        names.add(itemName);
    }

    private int buyPriceFor(APIContext ctx, String itemName) {
        long minimum = isRangedArrow(itemName) ? 8L : 100L;
        return GePricing.quickBuyPrice(ctx, itemName, minimum);
    }

    private int sellPriceFor(APIContext ctx, String itemName) {
        int calculated = GePricing.quickSellPrice(ctx, itemName, 1L);
        int manualCap = manualEquipmentSellPrice(itemName);
        if (manualCap > 0 && calculated > manualCap * 2) {
            return manualCap;
        }
        return calculated;
    }

    private int manualEquipmentSellPrice(String itemName) {
        String normalized = normalizedName(itemName);
        if (normalized.equals("blackscimitar")) {
            return 1000;
        }
        if (normalized.equals("mithrilscimitar")) {
            return 900;
        }
        if (normalized.equals("steelscimitar")) {
            return 300;
        }
        if (normalized.equals("ironscimitar")) {
            return 80;
        }
        if (normalized.equals("bronzescimitar")) {
            return 20;
        }
        return 0;
    }

    private boolean prepareCombatGear(APIContext ctx) {
        return trainingMode == TrainingMode.RANGED ? prepareBestRangedGear(ctx) : prepareBestMeleeGear(ctx);
    }

    private boolean prepareBestMeleeGear(APIContext ctx) {
        if (equipBestInventoryGear(ctx)) {
            return true;
        }

        int attackLevel = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        int attackGearBracket = gearCheckBracket(attackLevel);
        int defenceGearBracket = meleeArmorCheckBracket(defenceLevel);
        boolean missingWeapon = isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.WEAPON)
                && bestHeldGear(ctx, attackLevel, WEAPONS) == null;
        boolean missingShield = isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.SHIELD)
                && bestHeldGear(ctx, defenceLevel, SHIELDS) == null;
        boolean weaponTrigger = !initialGearChecked
                || missingWeapon
                || (attackGearBracket >= 10 && gearCheckedAttackLevel != attackGearBracket);
        boolean armorTrigger = !initialGearChecked
                || missingShield
                || (defenceGearBracket >= 5 && gearCheckedDefenceLevel != defenceGearBracket);

        if (!weaponTrigger && !armorTrigger) {
            return false;
        }

        if (ctx.localPlayer().isAttacking()) {
            Time.sleep(600, 900);
            return true;
        }

        if (!isBankOpen(ctx)) {
            openBankOrWalk(ctx, "best available gear");
            return true;
        }

        freeGearInventorySlots(ctx);

        int withdrawn = 0;
        if (weaponTrigger) {
            withdrawn += withdrawBestGear(ctx, "weapon", attackLevel, WEAPONS);
        }
        if (armorTrigger) {
            int meleeSetLevel = meleeSetGearLevel(defenceLevel);
            withdrawn += withdrawBestGear(ctx, "helmet", defenceLevel, HELMETS);
            withdrawn += withdrawBestGear(ctx, "body", meleeSetLevel, BODIES);
            withdrawn += withdrawBestGear(ctx, "legs", meleeSetLevel, LEGS);
            withdrawn += withdrawBestGear(ctx, "shield", meleeSetLevel, SHIELDS);
        }
        if (!initialGearChecked) {
            withdrawn += withdrawBestGear(ctx, "amulet", 1, AMULETS);
            withdrawn += withdrawBestGear(ctx, "boots", 1, BOOTS);
            withdrawn += withdrawBestGear(ctx, "gloves", 1, GLOVES);
        }

        planGearPurchaseIfNeeded(ctx, attackLevel, defenceLevel, weaponTrigger, armorTrigger);
        if (pendingGearPurchase != null && !pendingGearPurchaseOptional) {
            initialGearChecked = false;
            log("Melee gear missing; handling mandatory purchase before selling old gear: "
                    + pendingGearPurchase.name);
            Time.sleep(600, 900);
            return true;
        }

        if (isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.WEAPON)
                && bestHeldGear(ctx, attackLevel, WEAPONS) == null) {
            withdrawn += withdrawFirstAvailable(ctx, "fallback weapon", "Bronze sword", "Iron dagger", "Bronze dagger");
        }

        if (isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.SHIELD)
                && bestHeldGear(ctx, defenceLevel, SHIELDS) == null) {
            withdrawn += withdrawFirstAvailable(ctx, "fallback shield", "Wooden shield", "Bronze sq shield");
        }

        initialGearChecked = true;
        if (weaponTrigger) {
            gearCheckedAttackLevel = attackGearBracket;
        }
        if (armorTrigger) {
            gearCheckedDefenceLevel = defenceGearBracket;
        }

        if (withdrawObsoleteGearFromBank(ctx)) {
            return true;
        }

        boolean stillMissingWeapon = isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.WEAPON)
                && bestHeldGear(ctx, attackLevel, WEAPONS) == null;
        boolean stillMissingShield = isEquipmentSlotEmpty(ctx, IEquipmentAPI.Slot.SHIELD)
                && bestHeldGear(ctx, defenceLevel, SHIELDS) == null;
        if (withdrawn == 0 && (stillMissingWeapon || stillMissingShield)) {
            logVisibleBankItemsOnce(ctx);
            initialGearChecked = false;
            log("Still missing weapon/shield after bank scan; waiting instead of fighting unarmed");
            Time.sleep(1200, 1800);
            return true;
        }

        log(withdrawn > 0
                ? "Best gear withdrawn: " + withdrawn + " item(s). Closing bank to equip"
                : "Best gear check complete; no upgrades found");

        ctx.bank().close();
        Time.sleep(600, 900);
        return true;
    }

    private boolean prepareBestRangedGear(APIContext ctx) {
        stats.setTrainingSkill("Ranged");
        if (equipBestInventoryGear(ctx)) {
            return true;
        }

        int rangedLevel = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        int rangedBracket = gearCheckBracket(rangedLevel);
        int rangedArmorLevel = rangedArmorLevel(ctx);
        int rangedArmorBracket = gearCheckBracket(rangedArmorLevel);
        GearItem desiredBow = desiredRangedWeapon(rangedLevel);
        GearItem desiredArrow = desiredRangedArrow(rangedLevel);

        boolean missingBow = desiredBow != null && !ownsGear(ctx, desiredBow.name);
        boolean missingArrows = forceRangedArrowRestock
                || (desiredArrow != null && carriedOrEquippedCount(ctx, desiredArrow.name) < MIN_RANGED_ARROWS_EQUIPPED);
        boolean weaponTrigger = !initialGearChecked
                || missingBow
                || missingArrows
                || (rangedBracket >= 5 && gearCheckedRangedLevel != rangedBracket);
        boolean armorTrigger = !initialGearChecked
                || (rangedArmorBracket >= 20 && gearCheckedRangedArmorLevel != rangedArmorBracket);

        if (!weaponTrigger && !armorTrigger) {
            return false;
        }

        if (ctx.localPlayer().isAttacking()) {
            Time.sleep(600, 900);
            return true;
        }

        if (!isBankOpen(ctx)) {
            openBankOrWalk(ctx, "best ranged gear");
            return true;
        }

        freeGearInventorySlots(ctx);

        int withdrawn = 0;
        if (weaponTrigger) {
            withdrawn += withdrawBestGear(ctx, "ranged weapon", rangedLevel, RANGED_WEAPONS);
            withdrawn += withdrawBestArrows(ctx, rangedLevel);
        }
        if (armorTrigger) {
            withdrawn += withdrawBestGear(ctx, "ranged helmet", rangedArmorLevel, RANGED_HELMETS);
            withdrawn += withdrawBestGear(ctx, "ranged body", rangedArmorLevel, RANGED_BODIES);
            withdrawn += withdrawBestGear(ctx, "ranged legs", rangedArmorLevel, RANGED_LEGS);
            withdrawn += withdrawBestGear(ctx, "ranged gloves", rangedArmorLevel, RANGED_GLOVES);
            withdrawn += withdrawBestGear(ctx, "ranged boots", 1, RANGED_BOOTS);
        }
        withdrawn += withdrawBestGear(ctx, "ranged amulet", 1, RANGED_AMULETS);

        planRangedPurchaseIfNeeded(ctx, rangedLevel, rangedArmorLevel, weaponTrigger, armorTrigger);
        if (pendingGearPurchase != null && !pendingGearPurchaseOptional) {
            initialGearChecked = false;
            log("Ranged gear missing; handling mandatory purchase before fighting: "
                    + pendingGearPurchase.name);
            Time.sleep(600, 900);
            return true;
        }

        if (desiredBow != null && !ownsGear(ctx, desiredBow.name)) {
            withdrawn += withdrawFirstAvailable(ctx, "fallback ranged weapon", "Shortbow", "Oak shortbow");
        }
        if (desiredArrow != null && carriedOrEquippedCount(ctx, desiredArrow.name) < MIN_RANGED_ARROWS_EQUIPPED) {
            withdrawn += withdrawFirstAvailableStack(
                    ctx,
                    "fallback arrows",
                    desiredArrow.name,
                    targetRangedArrowQuantity(desiredArrow)
            );
        }

        initialGearChecked = true;
        if (weaponTrigger) {
            gearCheckedRangedLevel = rangedBracket;
        }
        if (armorTrigger) {
            gearCheckedRangedArmorLevel = rangedArmorBracket;
        }

        if (withdrawObsoleteGearFromBank(ctx)) {
            return true;
        }

        boolean stillMissingBow = desiredBow != null && !ownsGear(ctx, desiredBow.name);
        boolean stillMissingArrows = desiredArrow != null && carriedOrEquippedCount(ctx, desiredArrow.name) < MIN_RANGED_ARROWS_EQUIPPED;
        if (!stillMissingArrows) {
            forceRangedArrowRestock = false;
        }
        if (withdrawn == 0 && (stillMissingBow || stillMissingArrows)) {
            logVisibleBankItemsOnce(ctx);
            initialGearChecked = false;
            log("Still missing ranged bow/arrows after bank scan; waiting instead of fighting");
            Time.sleep(1200, 1800);
            return true;
        }

        log(withdrawn > 0
                ? "Best ranged gear withdrawn: " + withdrawn + " item stack(s). Closing bank to equip"
                : "Best ranged gear check complete; no upgrades found");

        ctx.bank().close();
        Time.sleep(600, 900);
        return true;
    }

    private void planRangedPurchaseIfNeeded(
            APIContext ctx,
            int rangedLevel,
            int rangedArmorLevel,
            boolean weaponTrigger,
            boolean armorTrigger
    ) {
        if (pendingGearPurchase != null && !pendingGearPurchaseOptional) {
            return;
        }

        if (weaponTrigger) {
            GearItem desiredBow = desiredRangedWeapon(rangedLevel);
            if (shouldBuyGear(ctx, desiredBow)) {
                setPendingGearPurchase(desiredBow, false);
                log("Planned ranged weapon purchase: " + desiredBow.name);
                return;
            }

            GearItem desiredArrow = desiredRangedArrow(rangedLevel);
            if (desiredArrow != null && totalHeldCount(ctx, desiredArrow.name) < MIN_RANGED_ARROWS_EQUIPPED) {
                setPendingGearPurchase(desiredArrow, false);
                log("Planned ranged arrow purchase: " + desiredArrow.name);
                return;
            }
        }

        if (armorTrigger) {
            GearItem desiredHelmet = desiredGearForExactLevel(rangedArmorLevel, RANGED_HELMETS);
            if (shouldBuyGear(ctx, desiredHelmet)) {
                setPendingGearPurchase(desiredHelmet, false);
                log("Planned ranged helmet purchase: " + desiredHelmet.name);
                return;
            }

            GearItem desiredBody = desiredGearForExactLevel(rangedArmorLevel, RANGED_BODIES);
            if (shouldBuyGear(ctx, desiredBody)) {
                setPendingGearPurchase(desiredBody, false);
                log("Planned ranged body purchase: " + desiredBody.name);
                return;
            }

            GearItem desiredLegs = desiredGearForExactLevel(rangedArmorLevel, RANGED_LEGS);
            if (shouldBuyGear(ctx, desiredLegs)) {
                setPendingGearPurchase(desiredLegs, false);
                log("Planned ranged legs purchase: " + desiredLegs.name);
                return;
            }

            GearItem desiredGloves = desiredGearForExactLevel(rangedArmorLevel, RANGED_GLOVES);
            if (shouldBuyGear(ctx, desiredGloves)) {
                setPendingGearPurchase(desiredGloves, false);
                log("Planned ranged gloves purchase: " + desiredGloves.name);
                return;
            }
        }

        if (pendingGearPurchase == null) {
            GearItem rangedAmulet = desiredGearForExactLevel(1, RANGED_AMULETS);
            if (shouldBuyGear(ctx, rangedAmulet)) {
                setPendingGearPurchase(rangedAmulet, true);
                log("Planned desired ranged amulet purchase after GE unlock: " + rangedAmulet.name);
            }
        }
    }

    private void planGearPurchaseIfNeeded(
            APIContext ctx,
            int attackLevel,
            int defenceLevel,
            boolean weaponTrigger,
            boolean armorTrigger
    ) {
        if (pendingGearPurchase != null && !pendingGearPurchaseOptional) {
            return;
        }

        GearItem desiredWeapon = weaponTrigger ? desiredGearForLevel(attackLevel, WEAPONS) : null;
        if (shouldBuyGear(ctx, desiredWeapon)) {
            setPendingGearPurchase(desiredWeapon, false);
            log("Planned weapon purchase from Attack trigger: " + desiredWeapon.name);
            return;
        }

        if (armorTrigger) {
            GearItem desiredHelmet = desiredGearForLevel(defenceLevel, HELMETS);
            if (shouldBuyGear(ctx, desiredHelmet)) {
                setPendingGearPurchase(desiredHelmet, false);
                log("Planned helmet purchase from Defence trigger: " + desiredHelmet.name);
                return;
            }

            GearItem desiredShield = desiredMeleeSetGearForLevel(defenceLevel, SHIELDS);
            if (shouldBuyGear(ctx, desiredShield)) {
                setPendingGearPurchase(desiredShield, false);
                log("Planned set purchase from Defence trigger: " + desiredShield.name);
                return;
            }

            GearItem desiredBody = desiredMeleeSetGearForLevel(defenceLevel, BODIES);
            if (shouldBuyGear(ctx, desiredBody)) {
                setPendingGearPurchase(desiredBody, false);
                log("Planned set purchase from Defence trigger: " + desiredBody.name);
                return;
            }

            GearItem desiredLegs = desiredMeleeSetGearForLevel(defenceLevel, LEGS);
            if (shouldBuyGear(ctx, desiredLegs)) {
                setPendingGearPurchase(desiredLegs, false);
                log("Planned set purchase from Defence trigger: " + desiredLegs.name);
                return;
            }
        }

        if (pendingGearPurchase == null) {
            planDesiredAmuletPurchaseIfNeeded(ctx);
        }
    }

    private void planDesiredAmuletPurchaseIfNeeded(APIContext ctx) {
        if (System.currentTimeMillis() < desiredGearPurchaseRetryAt
                || System.currentTimeMillis() < geRestrictionRetryAt) {
            return;
        }

        for (GearItem amulet : AMULETS) {
            if (hasBetterOrEqualAmulet(ctx, amulet)) {
                log("Best desired amulet already covered: " + bestOwnedAmuletName(ctx, amulet));
                return;
            }

            if (shouldBuyGear(ctx, amulet)) {
                setPendingGearPurchase(amulet, true);
                log("Planned desired amulet purchase after GE unlock: " + amulet.name);
                return;
            }
        }
    }

    private void setPendingGearPurchase(GearItem item, boolean optional) {
        pendingGearPurchase = item;
        pendingGearPurchaseOptional = optional;
        gearPurchaseDeferredUntil = 0;
        gearBuyOfferPending = false;
    }

    private void clearPendingGearPurchase() {
        pendingGearPurchase = null;
        pendingGearPurchaseOptional = false;
        gearBuyOfferPending = false;
        pendingStarterFoodPurchase = false;
        woodcuttingFundingBankAudited = false;
        woodcuttingFundingBankCoinsSnapshot = 0;
        woodcuttingFundingBankLogsSnapshot = 0;
    }

    private GearItem desiredGearForLevel(int level, GearItem[] items) {
        if (gearCheckBracket(level) < 10) {
            return null;
        }

        for (GearItem item : items) {
            if (item.requiredLevel <= level) {
                return item;
            }
        }

        return null;
    }

    private GearItem desiredMeleeSetGearForLevel(int defenceLevel, GearItem[] items) {
        int cappedLevel = meleeSetGearLevel(defenceLevel);
        if (cappedLevel < 5) {
            return null;
        }
        return desiredGearForExactLevel(cappedLevel, items);
    }

    private int meleeSetGearLevel(int defenceLevel) {
        if (defenceLevel >= 20) {
            return defenceLevel;
        }
        if (defenceLevel >= 5) {
            return 5;
        }
        return defenceLevel;
    }

    private GearItem desiredGearForExactLevel(int level, GearItem[] items) {
        for (GearItem item : items) {
            if (item.requiredLevel <= level) {
                return item;
            }
        }

        return null;
    }

    private GearItem desiredRangedWeapon(int rangedLevel) {
        return desiredGearForExactLevel(rangedLevel, RANGED_WEAPONS);
    }

    private GearItem desiredRangedArrow(int rangedLevel) {
        if (rangedLevel >= 20) {
            return findGearByName(RANGED_AMMO, "Mithril arrow");
        }
        return findGearByName(RANGED_AMMO, rangedLevel >= 5 ? "Steel arrow" : "Bronze arrow");
    }

    private GearItem findGearByName(GearItem[] items, String itemName) {
        for (GearItem item : items) {
            if (namesMatch(item.name, itemName)) {
                return item;
            }
        }
        return null;
    }

    private int rangedArmorLevel(APIContext ctx) {
        int ranged = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        int defence = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        return Math.min(ranged, defence);
    }

    private int targetRangedArrowQuantity(GearItem arrows) {
        if (arrows == null) {
            return MIN_RANGED_ARROW_PURCHASE;
        }

        String normalized = normalizedName(arrows.name);
        if (normalized.contains("bronze")) {
            return 200;
        }
        if (normalized.contains("iron")) {
            return 1000;
        }
        if (normalized.contains("steel")) {
            return 1000;
        }
        if (normalized.contains("mithril")) {
            return 1000;
        }
        return MIN_RANGED_ARROW_PURCHASE;
    }

    private int withdrawBestArrows(APIContext ctx, int rangedLevel) {
        GearItem arrows = desiredRangedArrow(rangedLevel);
        if (arrows == null) {
            return 0;
        }

        int held = carriedOrEquippedCount(ctx, arrows.name);
        if (held >= MIN_RANGED_ARROWS_EQUIPPED) {
            log("Best arrows already carried/equipped: " + arrows.name + " x" + held);
            return 0;
        }

        int needed = Math.max(1, targetRangedArrowQuantity(arrows) - held);
        return withdrawFirstAvailableStack(ctx, "arrows", arrows.name, needed);
    }

    private int withdrawFirstAvailableStack(APIContext ctx, String label, String name, int amount) {
        int bankCount = ctx.bank().getCount(name);
        if (bankCount <= 0) {
            return 0;
        }

        int withdrawAmount = Math.max(1, Math.min(bankCount, amount));
        log("Withdrawing " + label + ": " + withdrawAmount + "x " + name);
        if (ctx.bank().withdraw(withdrawAmount, name)
                || ctx.bank().withdrawAny(withdrawAmount, name)
                || (withdrawAmount == bankCount && ctx.bank().withdrawAll(name))) {
            Time.sleep(600, 900);
            return 1;
        }

        return 0;
    }

    private boolean shouldBuyGear(APIContext ctx, GearItem item) {
        if (item != null && isRangedArrow(item.name)) {
            return totalHeldCount(ctx, item.name) < MIN_RANGED_ARROWS_EQUIPPED;
        }
        return item != null
                && !ctx.equipment().contains(item.name)
                && !ctx.inventory().contains(item.name)
                && !bankHasItem(ctx, item.name);
    }

    private boolean isRangedArrow(String itemName) {
        return itemName != null && normalizedName(itemName).contains("arrow");
    }

    private boolean isAmulet(GearItem item) {
        if (item == null) {
            return false;
        }

        for (GearItem amulet : AMULETS) {
            if (namesMatch(amulet.name, item.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBetterOrEqualAmulet(APIContext ctx, GearItem candidate) {
        return bestOwnedAmuletName(ctx, candidate) != null;
    }

    private String bestOwnedAmuletName(APIContext ctx, GearItem candidate) {
        if (candidate == null) {
            return null;
        }

        for (GearItem amulet : AMULETS) {
            if (ownsGear(ctx, amulet.name)) {
                return amulet.name;
            }
            if (namesMatch(amulet.name, candidate.name)) {
                return null;
            }
        }
        return null;
    }

    private boolean ownsGear(APIContext ctx, String itemName) {
        return ctx.equipment().contains(itemName)
                || ctx.inventory().contains(itemName)
                || bankHasItem(ctx, itemName);
    }

    private boolean equipBestInventoryGear(APIContext ctx) {
        if (trainingMode == TrainingMode.RANGED) {
            return equipBestInventoryRangedGear(ctx);
        }

        int attackLevel = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        return equipBestInventoryItem(ctx, attackLevel, WEAPONS)
                || equipBestInventoryItem(ctx, defenceLevel, HELMETS)
                || equipBestInventoryItem(ctx, defenceLevel, BODIES)
                || equipBestInventoryItem(ctx, defenceLevel, LEGS)
                || equipBestInventoryItem(ctx, defenceLevel, SHIELDS)
                || equipBestInventoryItem(ctx, 1, AMULETS)
                || equipBestInventoryItem(ctx, 1, BOOTS)
                || equipBestInventoryItem(ctx, 1, GLOVES);
    }

    private boolean equipBestInventoryRangedGear(APIContext ctx) {
        int rangedLevel = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        int rangedArmorLevel = rangedArmorLevel(ctx);
        GearItem arrows = desiredRangedArrow(rangedLevel);
        return equipBestInventoryItem(ctx, rangedLevel, RANGED_WEAPONS)
                || (arrows != null && equipBestInventoryItem(ctx, rangedLevel, new GearItem[]{arrows}))
                || equipBestInventoryItem(ctx, rangedArmorLevel, RANGED_HELMETS)
                || equipBestInventoryItem(ctx, rangedArmorLevel, RANGED_BODIES)
                || equipBestInventoryItem(ctx, rangedArmorLevel, RANGED_LEGS)
                || equipBestInventoryItem(ctx, rangedArmorLevel, RANGED_GLOVES)
                || equipBestInventoryItem(ctx, 1, RANGED_BOOTS)
                || equipBestInventoryItem(ctx, 1, RANGED_AMULETS);
    }

    private boolean equipBestInventoryItem(APIContext ctx, int level, GearItem[] items) {
        GearItem best = bestHeldGear(ctx, level, items);
        if (best == null) {
            return false;
        }

        boolean rangedArrow = isRangedArrow(best.name);
        if (!rangedArrow && ctx.equipment().contains(best.name)) {
            return false;
        }

        if (ctx.inventory().contains(best.name)) {
            if (closeGrandExchangeForEquipment(ctx, best.name)) {
                return true;
            }

            if (isBankOpen(ctx)) {
                log("Closing bank to equip " + best.name);
                closeBank(ctx);
                Time.sleep(700, 1100, () -> !isBankOpen(ctx), 100);
                if (isBankOpen(ctx)) {
                    return true;
                }
            }

            if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
                log("Opening inventory to equip " + best.name);
                ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
                Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
            }

            log("Equipping best gear: " + best.name);
            if (equipInventoryGear(ctx, best)) {
                log("Equipped gear confirmed: " + best.name);
                return true;
            }

            log("Gear equip not confirmed for " + best.name + "; retrying before combat");
            Time.sleep(900, 1400);
            return true;
        }

        return false;
    }

    private boolean closeGrandExchangeForEquipment(APIContext ctx, String itemName) {
        if (!ctx.grandExchange().isOpen()) {
            return false;
        }

        log("Closing GE to equip " + itemName);
        ctx.grandExchange().close();
        Time.sleep(600, 900);
        return true;
    }

    private boolean equipInventoryGear(APIContext ctx, GearItem item) {
        int beforeCount = ctx.inventory().getCount(true, item.name);
        int beforeEquippedCount = equippedItemCount(ctx, item.name);
        boolean rangedArrow = isRangedArrow(item.name);
        for (String action : equipActions(item)) {
            boolean interacted = interactInventoryGear(ctx, item, action);
            if (!interacted) {
                continue;
            }

            Time.sleep(
                    700,
                    1800,
                    () -> rangedArrow
                            ? ctx.inventory().getCount(true, item.name) < beforeCount
                            || equippedItemCount(ctx, item.name) > beforeEquippedCount
                            : ctx.equipment().contains(item.name)
                            || ctx.inventory().getCount(true, item.name) < beforeCount,
                    100
            );

            if (rangedArrow
                    ? ctx.inventory().getCount(true, item.name) < beforeCount
                    || equippedItemCount(ctx, item.name) > beforeEquippedCount
                    : ctx.equipment().contains(item.name)
                    || ctx.inventory().getCount(true, item.name) < beforeCount) {
                return true;
            }

            Time.sleep(250, 450);
        }

        return false;
    }

    private int equippedItemCount(APIContext ctx, String itemName) {
        if (isRangedArrow(itemName)) {
            ItemWidget ammo = ctx.equipment().getItem(IEquipmentAPI.Slot.AMMO);
            return ammo != null && namesMatch(ammo.getName(), itemName)
                    ? Math.max(1, ammo.getStackSize())
                    : 0;
        }

        return ctx.equipment().contains(itemName)
                ? Math.max(1, ctx.equipment().getCount(itemName))
                : 0;
    }

    private boolean interactInventoryGear(APIContext ctx, GearItem item, String action) {
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }

        ItemWidget widget = ctx.inventory().getItem(inventoryItem ->
                inventoryItem != null && namesMatch(inventoryItem.getName(), item.name));
        if (widget != null && widget.interact(action, item.name)) {
            return true;
        }
        if (widget != null && widget.interact(action)) {
            return true;
        }
        return ctx.inventory().interactItem(action, item.name);
    }

    private String[] equipActions(GearItem item) {
        if ("Wield".equals(item.action)) {
            return new String[]{"Wield", "Wear", "Equip"};
        }
        if ("Wear".equals(item.action)) {
            return new String[]{"Wear", "Wield", "Equip"};
        }
        return new String[]{item.action, "Wear", "Wield", "Equip"};
    }

    private int withdrawBestGear(APIContext ctx, String slotName, int level, GearItem[] items) {
        GearItem best = bestAvailableGear(ctx, level, items);
        if (best == null) {
            log("No " + slotName + " upgrade available for level " + level);
            return 0;
        }

        if (ctx.equipment().contains(best.name) || ctx.inventory().contains(best.name)) {
            log("Best " + slotName + " already held: " + best.name);
            return 0;
        }

        if (bankHasItem(ctx, best.name)) {
            log("Withdrawing best " + slotName + ": " + best.name);
            if (withdrawOneFromBank(ctx, best.name)) {
                Time.sleep(600, 900);
                return 1;
            }
        }

        return 0;
    }

    private GearItem bestAvailableGear(APIContext ctx, int level, GearItem[] items) {
        for (GearItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.requiredLevel <= level
                    && (ctx.equipment().contains(item.name)
                    || ctx.inventory().contains(item.name)
                    || bankHasItem(ctx, item.name))) {
                return item;
            }
        }

        return null;
    }

    private int withdrawFirstAvailable(APIContext ctx, String label, String... names) {
        for (String name : names) {
            if (ctx.inventory().contains(name) || ctx.equipment().contains(name)) {
                return 0;
            }

            if (bankHasItem(ctx, name)) {
                log("Withdrawing " + label + ": " + name);
                if (withdrawOneFromBank(ctx, name)) {
                    Time.sleep(600, 900);
                    return 1;
                }
            }
        }

        return 0;
    }

    private boolean bankHasItem(APIContext ctx, String name) {
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

    private boolean withdrawOneFromBank(APIContext ctx, String name) {
        return ctx.bank().withdraw(1, name)
                || ctx.bank().withdrawAny(1, name)
                || ctx.bank().interactItem("Withdraw-1", name)
                || ctx.bank().interactItem("Withdraw 1", name)
                || ctx.bank().interactItem("Withdraw", name);
    }

    private void logVisibleBankItemsOnce(APIContext ctx) {
        if (gearDebugLogged || !ctx.bank().isOpen()) {
            return;
        }

        gearDebugLogged = true;
        StringBuilder items = new StringBuilder();
        int count = 0;
        for (ItemWidget item : ctx.bank().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (items.length() > 0) {
                items.append(", ");
            }
            items.append(item.getName());
            count++;
            if (count >= 20) {
                break;
            }
        }

        log(items.length() == 0
                ? "Bank gear debug: no visible item names returned by API"
                : "Bank gear debug visible items: " + items);
    }

    private void logVisibleFundingBankItemsOnce(APIContext ctx) {
        if (fundingDebugLogged || !ctx.bank().isOpen()) {
            return;
        }

        fundingDebugLogged = true;
        StringBuilder items = new StringBuilder();
        int count = 0;
        for (ItemWidget item : ctx.bank().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            if (items.length() > 0) {
                items.append(", ");
            }
            items.append(item.getName()).append(" x").append(Math.max(1, item.getStackSize()));
            count++;
            if (count >= 30) {
                break;
            }
        }

        log(items.length() == 0
                ? "Funding debug: bank API returned no visible item names"
                : "Funding debug visible bank items: " + items);
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private boolean matchesAny(String itemName, String... names) {
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

    private boolean isEquipmentSlotEmpty(APIContext ctx, IEquipmentAPI.Slot slot) {
        return ctx.equipment().getItem(slot) == null;
    }

    private int totalHeldCount(APIContext ctx, String itemName) {
        int count = carriedOrEquippedCount(ctx, itemName);

        if (isBankOpen(ctx)) {
            count += ctx.bank().getCount(itemName);
        }

        return count;
    }

    private int carriedOrEquippedCount(APIContext ctx, String itemName) {
        int count = ctx.inventory().getCount(true, itemName);

        ItemWidget ammo = ctx.equipment().getItem(IEquipmentAPI.Slot.AMMO);
        if (ammo != null && namesMatch(ammo.getName(), itemName)) {
            count += Math.max(1, ammo.getStackSize());
        } else if (ctx.equipment().contains(itemName)) {
            count += Math.max(1, ctx.equipment().getCount(itemName));
        }

        return count;
    }

    private int gearCheckBracket(int level) {
        if (level < 10) {
            return 0;
        }

        return (level / 10) * 10;
    }

    private int meleeArmorCheckBracket(int level) {
        if (level >= 5 && level < 10) {
            return 5;
        }
        return gearCheckBracket(level);
    }

    private GearItem bestHeldGear(APIContext ctx, int level, GearItem[] items) {
        for (GearItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.requiredLevel <= level
                    && (ctx.equipment().contains(item.name) || ctx.inventory().contains(item.name))) {
                return item;
            }
        }

        return null;
    }

    private void freeGearInventorySlots(APIContext ctx) {
        if (ctx.inventory().getEmptySlotCount() >= 8) {
            return;
        }

        if (ctx.inventory().contains("Cowhide")) {
            log("Freeing inventory slots by depositing cowhides");
            ctx.bank().depositAll("Cowhide");
            ctx.bank().depositAll("Feather");
            ctx.bank().depositAll("Feathers");
            ctx.bank().depositAll("Coins");
            Time.sleep(600, 900);
        }
    }

    private void buryBones(APIContext ctx) {
        while (ctx.inventory().contains("Bones")) {
            log("Burying bones");
            if (!ctx.inventory().interactItem("Bury", "Bones")) {
                return;
            }

            stats.recordBonesBuried();
            Time.sleep(600, 900);
        }
    }

    private boolean lootCowDrops(APIContext ctx) {
        if (ctx.inventory().isFull()) {
            return false;
        }

        GroundItem drop = findCowLoot(ctx);

        if (drop == null) {
            return false;
        }

        String itemName = drop.getName();
        int quantity = Math.max(1, drop.getStackSize());
        int beforeCount = ctx.inventory().getCount(true, itemName);
        log("Taking " + itemName);
        boolean interacted = drop.interact("Take", itemName);
        if (!interacted) {
            interacted = drop.interact("Take");
        }

        if (interacted) {
            Time.sleep(
                    1200,
                    2400,
                    () -> !drop.isValid() || ctx.inventory().getCount(true, itemName) > beforeCount || ctx.inventory().isFull(),
                    100
            );

            if (!drop.isValid() || ctx.inventory().getCount(true, itemName) > beforeCount) {
                stats.recordLoot(itemName, quantity, estimateLootValue(ctx, itemName, quantity));
            }
        }

        return interacted;
    }

    private GroundItem findCowLoot(APIContext ctx) {
        Area lootArea = currentCombatArea(ctx);
        String[] lootNames = currentLootNames(ctx);
        for (String lootName : lootNames) {
            for (GroundItem drop : ctx.groundItems()
                    .query()
                    .named(lootName)
                    .within(lootArea)
                    .results()
                    .nearestList()) {
                if (isValidLoot(ctx, drop, lootArea)) {
                    return drop;
                }
            }
        }

        for (GroundItem nearbyDrop : ctx.groundItems()
                .query()
                .named(lootNames)
                .results()
                .nearestList()) {
            if (isValidLoot(ctx, nearbyDrop, lootArea)) {
                return nearbyDrop;
            }
        }

        return null;
    }

    private boolean isValidLoot(APIContext ctx, GroundItem drop, Area lootArea) {
        if (drop == null || !drop.isValid()) {
            return false;
        }

        return drop.tileDistanceTo(ctx) <= 12
                && (lootArea.contains(drop.getLocation()) || lootArea.contains(ctx.localPlayer().getLocation()))
                && isFromRecentOwnKill(ctx, drop);
    }

    private boolean isFromRecentOwnKill(APIContext ctx, GroundItem drop) {
        long now = System.currentTimeMillis();
        if (recentOwnLootTile == null || now > recentOwnLootExpiresAt) {
            if (now - lastLootFilterLogAt >= LOOT_FILTER_LOG_INTERVAL_MILLIS) {
                log("Skipping ground loot; no recent own kill tracked");
                lastLootFilterLogAt = now;
            }
            return false;
        }

        int distance = tileDistance(drop.getLocation(), recentOwnLootTile);
        boolean samePlane = drop.getPlane() == recentOwnLootTile.getPlane();
        if (!samePlane || distance > OWN_LOOT_RADIUS_TILES) {
            if (now - lastLootFilterLogAt >= LOOT_FILTER_LOG_INTERVAL_MILLIS) {
                log("Ignoring old/other loot: " + drop.getName() + " is " + distance + " tiles from own kill");
                lastLootFilterLogAt = now;
            }
            return false;
        }

        return true;
    }

    private int tileDistance(Tile left, Tile right) {
        if (left == null || right == null) {
            return Integer.MAX_VALUE;
        }

        return Math.max(
                Math.abs(left.getX() - right.getX()),
                Math.abs(left.getY() - right.getY())
        );
    }

    private long estimateLootValue(APIContext ctx, String itemName, int quantity) {
        if ("Coins".equals(itemName)) {
            return quantity;
        }

        try {
            ItemDetail detail = ctx.pricing().get(itemName);
            if (detail != null) {
                int price = Math.max(detail.getLowestPrice(), detail.getHighestPrice());
                if (price > 0) {
                    return (long) price * quantity;
                }
            }
        } catch (RuntimeException ignored) {
            // Pricing is a nice-to-have for paint stats; combat should not stop if it fails.
        }

        if ("Cowhide".equals(itemName)) {
            return 150L * quantity;
        }

        return 0L;
    }

    private long estimateItemValue(APIContext ctx, String itemName, int quantity) {
        if ("Coins".equals(itemName)) {
            return quantity;
        }

        int fixed = F2PItemRegistry.sellPrice(itemName);
        if (fixed > 0) {
            return (long) fixed * quantity;
        }

        try {
            ItemDetail detail = ctx.pricing().get(itemName);
            if (detail != null) {
                int price = Math.max(detail.getLowestPrice(), detail.getHighestPrice());
                if (price > 0) {
                    return (long) price * quantity;
                }
            }
        } catch (RuntimeException ignored) {
            // Profit estimates should never interrupt the main money-making loop.
        }

        return 0L;
    }

    private long estimateSellItemValue(APIContext ctx, String itemName, int quantity) {
        if ("Coins".equals(itemName)) {
            return quantity;
        }

        int fixed = F2PItemRegistry.sellPrice(itemName);
        if (fixed > 0) {
            return (long) fixed * quantity;
        }

        try {
            ItemDetail detail = ctx.pricing().get(itemName);
            if (detail != null) {
                int price = detail.getLowestPrice() > 0
                        ? detail.getLowestPrice()
                        : detail.getHighestPrice();
                if (price > 0 && detail.isEquipable() && detail.getHighAlch() > 0) {
                    price = (int) Math.min(price, (long) detail.getHighAlch() * 3L);
                }
                if (price > 0) {
                    return (long) price * quantity;
                }
                if (detail.getHighAlch() > 0) {
                    return (long) detail.getHighAlch() * quantity;
                }
            }
        } catch (RuntimeException ignored) {
            // Profit estimates should never interrupt the main money-making loop.
        }

        return 0L;
    }

    private boolean shouldBank(APIContext ctx) {
        if (!hasBankableCombatLoot(ctx)) {
            bankAfterFullInventoryAt = 0;
            return false;
        }

        if (!ctx.inventory().isFull()) {
            bankAfterFullInventoryAt = 0;
            return false;
        }

        return true;
    }

    private void bankCowhides(APIContext ctx) {
        if (ctx.localPlayer().isAttacking()) {
            Time.sleep(800, 1200);
            return;
        }

        buryBones(ctx);

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Walking to nearest bank");
                walkToBank(ctx);
                Time.sleep(1200, 1800);
                return;
            }

            log("Opening bank");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return;
        }

        log("Depositing combat loot");
        depositCombatLoot(ctx);
        Time.sleep(600, 900);
        ctx.bank().close();
        bankAfterFullInventoryAt = 0;
    }

    private boolean hasBankableCombatLoot(APIContext ctx) {
        for (String lootName : BANKABLE_COMBAT_LOOT) {
            if (ctx.inventory().contains(lootName)) {
                return true;
            }
        }
        return false;
    }

    private void depositCombatLoot(APIContext ctx) {
        ctx.bank().depositAll(BANKABLE_COMBAT_LOOT);
    }

    private void walkToArea(APIContext ctx, Area area, String label) {
        walkToTile(ctx, area.getRandomTile(), label);
    }

    private void walkToTile(APIContext ctx, Tile tile, String label) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        boolean walked = false;
        if (roll < 45 && tile.tileDistanceTo(ctx) <= 12) {
            walked = ctx.walking().walkOnScreen(tile)
                    || tile.interact("Walk here")
                    || tile.click(true);
            if (walked) {
                log("Walking via screen to " + label);
                return;
            }
        }

        if (roll < 75) {
            walked = ctx.walking().walkOnMap(tile);
            if (walked) {
                log("Walking via minimap to " + label);
                return;
            }
        }

        if (shouldAvoidTeleportsForTargetWalk(label)) {
            WalkState state = Navigation.walkToNoTeleports(ctx, tile);
            log("Walking via pathfinder (no teleports) to " + label + ": " + state);
            if (state == WalkState.ERROR || state == WalkState.FAILED || state == WalkState.RATE_LIMIT) {
                if (ctx.walking().walkOnMap(tile) || ctx.walking().walkTo(tile)) {
                    log("Fallback local walk to " + label);
                }
            }
            return;
        }

        Navigation.walkTo(ctx, tile);
        log("Walking via pathfinder to " + label);
    }

    private boolean shouldAvoidTeleportsForTargetWalk(String label) {
        return label != null && label.endsWith(" area");
    }

    private void walkToBank(APIContext ctx) {
        Navigation.walkToBank(ctx);
    }

    private void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }

    private void logCombatTickEntered() {
        long now = System.currentTimeMillis();
        if (now < nextCombatTickLogAt) {
            return;
        }

        log("Combat tick entered");
        nextCombatTickLogAt = now + 10_000L;
    }

    private void logCombatDecision(String message) {
        long now = System.currentTimeMillis();
        if (message.equals(lastCombatDecision) && now < nextCombatDecisionRepeatAt) {
            stats.setStatus(message);
            return;
        }

        lastCombatDecision = message;
        nextCombatDecisionRepeatAt = now + 5_000L;
        log(message);
    }

    private int randomFoodStockTarget() {
        return randomInt(MIN_FOOD_STOCK_TARGET, MAX_FOOD_STOCK_TARGET);
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private long randomLong(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    public enum TrainingMode {
        MELEE,
        RANGED
    }

    private static final class GearItem {
        private final String name;
        private final int requiredLevel;
        private final String action;

        private GearItem(String name, int requiredLevel, String action) {
            this.name = name;
            this.requiredLevel = requiredLevel;
            this.action = action;
        }
    }
}
