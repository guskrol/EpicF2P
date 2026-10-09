package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.methods.ICombatAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Rune;
import com.epicbot.api.shared.model.RuneRequirement;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Spell;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.runtime.AccountSkillCapController;
import org.example.modules.combat.CombatPhase;
import org.example.modules.combat.LumbridgeCowCombatModule;
import org.example.modules.combat.RangedCombatTrainingModule;
import org.example.modules.magic.MagicCombatTrainingModule;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public class F2PCombatRegressionTest {
    private static final Skill.Skills[] COMBAT_SKILLS = {
            Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE,
            Skill.Skills.RANGED, Skill.Skills.MAGIC
    };

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("f2p-combat-regression-");
        try {
            testAccountCaps(directory.resolve("profiles"));
            testPersistenceFailure(directory.resolve("not-a-directory"));
            testCombatBoundaries(directory.resolve("combat"));
            testMagicArmour(directory.resolve("armour"));
            testMagicSpellProgression(directory.resolve("spells"));
            testRangedRestocking(directory.resolve("arrows"));
            testSupplyFunding(directory.resolve("funding"));
            System.out.println("PASS: account caps, combat progression, Magic armour and spells, arrow batches and supply funding");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void testAccountCaps(Path directory) throws Exception {
        SkillCapManager first = new SkillCapManager(directory);
        require(!first.initializeAccount(" "), "Unknown account must not create a profile");
        require(!Files.exists(directory), "Unknown account must wait for identification");
        require(first.initializeAccount(" Account One "), "First account must initialize");
        Map<Skill.Skills, Integer> selected = caps(first);
        Path profile;
        try (var files = Files.list(directory)) {
            profile = files.findFirst().orElseThrow();
        }
        checkRanges(first);
        require(!first.initializeAccount("ACCOUNT ONE"), "Same account must not reroll");
        require(selected.equals(caps(first)), "Repeated initialization changed caps");

        SkillCapManager restarted = new SkillCapManager(directory);
        restarted.initializeAccount("account one");
        require(selected.equals(caps(restarted)), "Restart must load the same caps");
        require(restarted.initializeAccount("account two"), "New account must initialize separately");
        checkRanges(restarted);
        restarted.initializeAccount("account one");
        require(selected.equals(caps(restarted)), "Switching accounts lost the original caps");

        try (var files = Files.list(directory)) {
            require(files.count() == 2, "Different accounts must have separate profiles");
        }
        Properties properties = load(profile);
        properties.setProperty("ATTACK", "20");
        properties.setProperty("MAGIC", "invalid");
        store(profile, properties);
        SkillCapManager repaired = new SkillCapManager(directory);
        repaired.initializeAccount("account one");
        checkRanges(repaired);
        require(repaired.capFor(Skill.Skills.STRENGTH) == selected.get(Skill.Skills.STRENGTH),
                "Repair must preserve valid caps");
        SkillCapManager repairedRestart = new SkillCapManager(directory);
        repairedRestart.initializeAccount("account one");
        require(caps(repaired).equals(caps(repairedRestart)), "Repaired caps must be saved");
    }

    private static void testPersistenceFailure(Path path) throws Exception {
        Files.createFile(path);
        SkillCapManager caps = new SkillCapManager(path);
        try {
            caps.initializeAccount("account");
            throw new AssertionError("Failed persistence must not silently start training");
        } catch (UncheckedIOException expected) {
            require(!caps.isInitializedForAccount("account"), "Failed save must not mark an account ready");
        }
    }

    private static void testCombatBoundaries(Path directory) throws Exception {
        SkillCapManager caps = new SkillCapManager(directory);
        caps.initializeAccount("combat account");
        Map<Skill.Skills, Integer> levels = new EnumMap<>(Skill.Skills.class);
        for (Skill.Skills skill : Skill.Skills.values()) {
            levels.put(skill, 40);
        }
        APIContext ctx = context(levels, null);
        LumbridgeCowCombatModule melee = engine(caps, LumbridgeCowCombatModule.TrainingMode.MELEE);
        for (Skill.Skills unfinished : List.of(Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE)) {
            for (Skill.Skills skill : List.of(Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE)) {
                levels.put(skill, caps.capFor(skill));
            }
            levels.put(unfinished, caps.capFor(unfinished) - 1);
            require(melee.shouldExecute(ctx), "Melee stopped before a skill reached its cap");
            for (int attempt = 0; attempt < 100; attempt++) {
                require(invoke(melee, "pickWeightedMeleeSkill", ctx, null) == unfinished,
                        "Melee selected a skill that already reached its cap");
            }
            setField(melee, "meleePlanSkill", Skill.Skills.ATTACK);
            levels.put(Skill.Skills.ATTACK, caps.capFor(Skill.Skills.ATTACK));
            require((boolean) invoke(melee, "shouldSwitchMeleePlan", ctx), "Completed plan must switch immediately");
        }
        for (Skill.Skills skill : COMBAT_SKILLS) {
            levels.put(skill, caps.capFor(skill));
        }
        require(!melee.shouldExecute(ctx), "Completed fallback melee must stop training");
        require(invoke(melee, "pickWeightedMeleeSkill", ctx, null) == null, "Completed melee must not select a skill");

        LumbridgeCowCombatModule rangedEngine = engine(caps, LumbridgeCowCombatModule.TrainingMode.RANGED);
        RangedCombatTrainingModule ranged = new RangedCombatTrainingModule(rangedEngine, caps);
        levels.put(Skill.Skills.RANGED, 21);
        require(ranged.shouldExecute(ctx), "Ranged must continue beyond the old level-20 cap");
        levels.put(Skill.Skills.RANGED, caps.capFor(Skill.Skills.RANGED));
        require(ranged.isComplete(ctx) && !rangedEngine.shouldExecute(ctx), "Ranged wrapper and engine disagree on cap");

        MagicCombatTrainingModule magic = new MagicCombatTrainingModule(message -> {}, new ScriptStats(), caps);
        for (int level : new int[]{13, 19, 20, 21, caps.capFor(Skill.Skills.MAGIC) - 1}) {
            levels.put(Skill.Skills.MAGIC, level);
            require(magic.shouldExecute(ctx) && !magic.isComplete(ctx), "Magic must continue through the old caps");
        }
        levels.put(Skill.Skills.MAGIC, caps.capFor(Skill.Skills.MAGIC));
        require(magic.isComplete(ctx), "Magic must stop at the account cap");
        levels.put(Skill.Skills.DEFENCE, 14);
        levels.put(Skill.Skills.MAGIC, 13);
        require(!magic.shouldExecute(ctx), "Magic must still require Defence 15");
        levels.put(Skill.Skills.DEFENCE, 15);

        AtomicBoolean setupCalled = new AtomicBoolean();
        LumbridgeCowCombatModule magicEngine = new LumbridgeCowCombatModule(message -> {}, new ScriptStats(),
                LumbridgeCowCombatModule.TrainingMode.MAGIC, caps, api -> { setupCalled.set(true); return false; });
        require(!(boolean) invoke(magicEngine, "prepareCombatGear", ctx) && setupCalled.get(),
                "Magic must use its own gear preparation");
        for (int level : new int[]{5, 10, 20}) {
            levels.put(Skill.Skills.MAGIC, level);
            require((int) invoke(magicEngine, "combatTargetLevel", ctx) == level, "Magic mobs must progress with Magic level");
            require(invoke(magicEngine, "currentCombatPhase", ctx) == CombatPhase.forSkillLevel(level),
                    "Magic must share the normal combat phases");
        }
        AccountSkillCapController controller = new AccountSkillCapController(caps, new ScriptStats(), message -> {});
        require(controller.shouldExecute(ctx), "New account must initialize before training");
        controller.execute(ctx);
        require(!controller.shouldExecute(ctx), "Initialized account must not reroll each tick");
    }

    private static void testMagicArmour(Path directory) throws Exception {
        SkillCapManager caps = new SkillCapManager(directory);
        AtomicBoolean removed = new AtomicBoolean();
        ItemWidget shield = proxy(ItemWidget.class, (object, method, arguments) -> switch (method.getName()) {
            case "getName" -> "Iron kiteshield";
            case "interact" -> { removed.set(true); yield true; }
            default -> defaultValue(method.getReturnType());
        });
        MagicCombatTrainingModule magic = new MagicCombatTrainingModule(message -> {}, new ScriptStats(), caps);
        APIContext ctx = context(new EnumMap<>(Skill.Skills.class), (object, method, arguments) -> switch (method.getName()) {
            case "getItem" -> arguments[0] == IEquipmentAPI.Slot.SHIELD && !removed.get() ? shield : null;
            case "contains" -> !removed.get();
            default -> defaultValue(method.getReturnType());
        });
        require((boolean) invoke(magic, "removeNonMagicArmour", ctx) && removed.get(),
                "Magic must remove the old splash shield");
    }

    private static LumbridgeCowCombatModule engine(SkillCapManager caps, LumbridgeCowCombatModule.TrainingMode mode) {
        return new LumbridgeCowCombatModule(message -> {}, new ScriptStats(), mode, caps);
    }

    private static void testMagicSpellProgression(Path directory) throws Exception {
        MagicCombatTrainingModule magic = new MagicCombatTrainingModule(
                message -> {}, new ScriptStats(), new SkillCapManager(directory));
        MagicScenario supplies = new MagicScenario();
        APIContext ctx = supplies.context();
        for (int level : new int[]{1, 12, 13, 19, 25}) {
            supplies.magicLevel = level;
            Spell expected = level < 13 ? Spell.Modern.WIND_STRIKE : Spell.Modern.FIRE_STRIKE;
            require(invoke(magic, "combatSpell", ctx) == expected,
                    "Wrong Magic spell at level " + level);
        }

        RuneRequirement[] runes = (RuneRequirement[]) invoke(magic, "consumableRunes", Spell.Modern.FIRE_STRIKE);
        require(java.util.Arrays.stream(runes).noneMatch(rune -> rune.getRune() == Rune.AIR),
                "Staff of air must supply the Air runes for Fire Strike");
        RuneRequirement fire = java.util.Arrays.stream(runes)
                .filter(rune -> rune.getRune() == Rune.FIRE).findFirst().orElseThrow();
        int minimumFire = 25 * fire.getAmount();
        int targetFire = 200 * fire.getAmount();
        require((int) invoke(magic, "minimumRuneStock", fire) == minimumFire,
                "Fire readiness must account for runes consumed per cast");
        require((int) invoke(magic, "targetRuneStock", fire) == targetFire,
                "Fire restocks must use a small 200-cast batch");
        supplies.inventory.put("Mind rune", 650);
        require((boolean) invoke(magic, "hasRequiredRuneStock", ctx, Spell.Modern.WIND_STRIKE),
                "Wind Strike must not require Fire runes");
        require(!(boolean) invoke(magic, "hasRequiredRuneStock", ctx, Spell.Modern.FIRE_STRIKE),
                "Mind runes alone must not mark Fire Strike ready");
        supplies.inventory.put("Fire rune", minimumFire - 1);
        require(!(boolean) invoke(magic, "hasRequiredRuneStock", ctx, Spell.Modern.FIRE_STRIKE),
                "Fire Strike must refill below its minimum cast reserve");
        supplies.inventory.put("Fire rune", minimumFire);
        require((boolean) invoke(magic, "hasRequiredRuneStock", ctx, Spell.Modern.FIRE_STRIKE),
                "Adequate Fire and Mind runes must permit Fire Strike");

        supplies.magicLevel = 19;
        supplies.inventory.put("Fire rune", 10);
        supplies.bank.put("Fire rune", targetFire);
        setField(magic, "magicGearBankChecked", true);
        setField(magic, "nextMissingGearLogAt", Long.MAX_VALUE);
        require((boolean) invoke(magic, "prepareNormalMagicGearAndRunes", ctx),
                "An existing level-19 account must prepare Fire runes before attacking");
        require(supplies.keptFireRunes, "Magic bank preparation must preserve carried Fire runes");
        require(supplies.inventory.get("Fire rune") == targetFire
                        && supplies.bank.get("Fire rune") == 10,
                "Fire restock must use bank supplies and subtract carried runes");
        require(getField(magic, "pendingBuyItem") == null,
                "Banked Fire runes must not cause a duplicate GE purchase");
        require((boolean) invoke(magic, "prepareNormalMagicGearAndRunes", ctx) && !supplies.bankOpen,
                "Prepared Magic supplies must close the bank");
        require(!(boolean) invoke(magic, "prepareNormalMagicGearAndRunes", ctx),
                "Ready Magic supplies must allow combat to resume");

        setField(magic, "pendingBuyItem", "Fire rune");
        setField(magic, "pendingBuyRequiredAvailable", targetFire);
        supplies.inventory.put("Fire rune", 40);
        require(!(boolean) invoke(magic, "purchaseSatisfied", ctx),
                "A partial Fire rune purchase must not be accepted as complete");
        supplies.inventory.put("Fire rune", targetFire);
        require((boolean) invoke(magic, "purchaseSatisfied", ctx),
                "The full Fire rune reserve must complete its pending purchase");
        invoke(magic, "clearPendingPurchase");

        setField(magic, "trustedAutocastSpell", Spell.Modern.FIRE_STRIKE);
        setField(magic, "trustedAutocastUntil", Long.MAX_VALUE);
        require(!(boolean) invoke(magic, "autocastReady", ctx, Spell.Modern.FIRE_STRIKE),
                "A cached Fire Strike must not override an API-confirmed Wind Strike");
        require(getField(magic, "trustedAutocastSpell") == null,
                "An explicitly different spell must invalidate the autocast cache");
        require(!(boolean) invoke(magic, "prepareMagicCombat", ctx),
                "A supplied level-19 account must switch autocast and become ready");
        require(supplies.requestedSpell == Spell.Modern.FIRE_STRIKE && !supplies.defensiveCast,
                "Magic must request non-defensive Fire Strike autocast");
        supplies.attackStyle = ICombatAPI.AttackStyle.DEFENSIVE_CASTING;
        require(!(boolean) invoke(magic, "autocastReady", ctx, Spell.Modern.FIRE_STRIKE),
                "Defensive casting must not be accepted as normal Magic training");

        supplies.attackStyle = ICombatAPI.AttackStyle.CASTING;
        supplies.autocastSpell = Spell.Modern.WIND_STRIKE;
        supplies.confirmAutocast = false;
        require((boolean) invoke(magic, "ensureAutocast", ctx, Spell.Modern.FIRE_STRIKE),
                "A successful click with Wind Strike still selected must block new attacks");
        require(getField(magic, "trustedAutocastSpell") == null,
                "An unconfirmed Fire Strike switch must not populate the trusted cache");
    }

    private static void testRangedRestocking(Path directory) throws Exception {
        SkillCapManager caps = new SkillCapManager(directory);
        caps.initializeAccount("arrow account");
        SupplyScenario supplies = new SupplyScenario();
        supplies.rangedLevel = caps.capFor(Skill.Skills.RANGED) - 3;
        APIContext ctx = supplies.context();
        LumbridgeCowCombatModule ranged = engine(caps, LumbridgeCowCombatModule.TrainingMode.RANGED);
        Object arrow = invoke(ranged, "desiredRangedArrow", supplies.rangedLevel);
        invoke(ranged, "setPendingGearPurchase", arrow, false);
        require((int) invoke(ranged, "targetRangedArrowQuantity", ctx) == 200,
                "Normal arrow stock must be 200, not 1000");
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 200,
                "Empty arrow stock must buy a small batch");

        supplies.inventory.put("Mithril arrow", 10);
        supplies.bank.put("Mithril arrow", 10);
        supplies.equippedArrows = 10;
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 170,
                "Purchase must subtract inventory, equipment and bank arrows");
        supplies.bankOpen = false;
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 170,
                "Closing the bank must not discard the audited arrow stock");

        supplies.rangedLevel = caps.capFor(Skill.Skills.RANGED) - 2;
        require((int) invoke(ranged, "targetRangedArrowQuantity", ctx) == 100,
                "Arrow stock must shrink for the final two levels");
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 70,
                "Near-cap purchase must still subtract existing arrows");
        supplies.rangedLevel++;
        require((int) invoke(ranged, "targetRangedArrowQuantity", ctx) == 50,
                "Final-level arrow stock must shrink again");
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 50,
                "Small restocks must avoid repeated one-arrow GE trips");

        supplies.inventory.put("Mithril arrow", 29);
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 50,
                "49 arrows must refill with a useful small batch");
        supplies.inventory.put("Mithril arrow", 30);
        require((boolean) invoke(ranged, "isPendingGearPurchaseSatisfied", ctx),
                "50 arrows must be enough to resume training");
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 0,
                "Adequate stock must not cause a new purchase");

        supplies.inventory.clear();
        supplies.bank.clear();
        supplies.equippedArrows = 0;
        supplies.bankOpen = true;
        supplies.rangedLevel = caps.capFor(Skill.Skills.RANGED);
        require((int) invoke(ranged, "pendingPurchaseQuantity", ctx) == 0,
                "Completed Ranged must not buy any more arrows");
    }

    private static void testSupplyFunding(Path directory) throws Exception {
        SkillCapManager caps = new SkillCapManager(directory);
        SupplyScenario supplies = new SupplyScenario();
        APIContext ctx = supplies.context();
        LumbridgeCowCombatModule ranged = engine(caps, LumbridgeCowCombatModule.TrainingMode.RANGED);
        int stock = (int) getField(ranged, "foodStockTarget");
        require(stock >= 28 && stock <= 42, "Combat food must use a small reserve");
        setField(ranged, "foodStockTarget", 30);
        supplies.inventory.put("Trout", 14);
        supplies.bank.put("Trout", 14);
        require(!(boolean) invoke(ranged, "shouldFundStarterFood", ctx),
                "Existing food must not trigger extra food farming");
        require((int) invoke(ranged, "foodToBuyForStock", ctx) == 2,
                "Food purchase must subtract both inventory and bank food");
        require((int) invoke(ranged, "fundingTargetCoins", ctx, 1600) == 1750,
                "Arrow funding must not include an unnecessary food stock");

        supplies.bank.put("Trout", 100);
        require((int) invoke(ranged, "foodToBuyForStock", ctx) == 0,
                "Existing food reserve must prevent duplicate purchases");
        supplies.inventory.clear();
        supplies.bank.clear();
        require((boolean) invoke(ranged, "shouldFundStarterFood", ctx), "No food must request food funding");
        require((int) invoke(ranged, "foodToBuyForStock", ctx) == 30,
                "Food funding must cover only the next small reserve");
        setField(ranged, "pendingStarterFoodPurchase", true);
        require((int) invoke(ranged, "fundingTargetCoins", ctx, 1600) > 1750,
                "Missing food must be included in the funding target");

        Object arrow = invoke(ranged, "desiredRangedArrow", supplies.rangedLevel);
        invoke(ranged, "setPendingGearPurchase", arrow, false);
        supplies.bank.put("Mithril arrow", 200);
        supplies.bank.put("Trout", 20);
        setField(ranged, "woodcuttingFundingMode", true);
        setField(ranged, "pendingStarterFoodPurchase", true);
        setField(ranged, "woodcuttingFundingTargetCoins", 10000);
        require(!(boolean) invoke(ranged, "ensureWoodcuttingFundingBankAudited", ctx, 10000),
                "Banked arrows and food must cancel unnecessary farming");
        require(!(boolean) getField(ranged, "woodcuttingFundingMode"),
                "Funding must stop when the bank already has combat supplies");
        require((int) getField(ranged, "pendingRangedArrowBankStock") == -1,
                "Funding must invalidate arrow stock before the next purchase");
    }

    private static final class MagicScenario {
        private final Map<String, Integer> inventory = new HashMap<>();
        private final Map<String, Integer> bank = new HashMap<>();
        private int magicLevel;
        private boolean bankOpen = true;
        private boolean keptFireRunes;
        private boolean confirmAutocast = true;
        private boolean defensiveCast;
        private Spell autocastSpell = Spell.Modern.WIND_STRIKE;
        private Spell requestedSpell;
        private ICombatAPI.AttackStyle attackStyle = ICombatAPI.AttackStyle.CASTING;

        @SuppressWarnings("unchecked")
        private APIContext context() {
            return proxy(APIContext.class, (object, method, arguments) ->
                    proxy(method.getReturnType(), (api, apiMethod, apiArguments) -> {
                        String surface = method.getName();
                        String action = apiMethod.getName();
                        if (surface.equals("skills") && action.equals("get")) {
                            Skill.Skills requested = (Skill.Skills) apiArguments[0];
                            return skill(requested, requested == Skill.Skills.MAGIC ? magicLevel : 40);
                        }
                        if (action.equals("getCount") || action.equals("contains")) {
                            Object names = apiArguments[apiArguments.length - 1];
                            int count = 0;
                            if (names instanceof String[] requested) {
                                for (String name : requested) {
                                    if (surface.equals("inventory")) count += inventory.getOrDefault(name, 0);
                                    if (surface.equals("bank")) count += bank.getOrDefault(name, 0);
                                    if (surface.equals("equipment") && name.equals("Staff of air")) count++;
                                }
                            }
                            if (action.equals("contains")) return count > 0;
                            return count;
                        }
                        if (surface.equals("bank")) {
                            if (action.equals("isOpen")) return bankOpen;
                            if (action.equals("isWithdrawMode")) return true;
                            if (action.equals("close")) { bankOpen = false; return true; }
                            if (action.equals("depositAllExcept")) {
                                Predicate<ItemWidget> keep = (Predicate<ItemWidget>) apiArguments[0];
                                ItemWidget fire = proxy(ItemWidget.class, (item, itemMethod, itemArguments) ->
                                        itemMethod.getName().equals("getName") ? "Fire rune"
                                                : defaultValue(itemMethod.getReturnType()));
                                keptFireRunes = keep.test(fire);
                                return true;
                            }
                            if (action.equals("withdraw")) {
                                int amount = (int) apiArguments[0];
                                String name = ((String[]) apiArguments[1])[0];
                                bank.merge(name, -amount, Integer::sum);
                                inventory.merge(name, amount, Integer::sum);
                                return true;
                            }
                        }
                        if (surface.equals("magic")) {
                            if (action.equals("getAutoCastSpell")) return autocastSpell;
                            if (action.equals("setAutoCast")) {
                                requestedSpell = (Spell) apiArguments[0];
                                defensiveCast = (boolean) apiArguments[1];
                                if (confirmAutocast) autocastSpell = requestedSpell;
                                return true;
                            }
                        }
                        if (surface.equals("combat") && action.equals("getAttackStyle")) return attackStyle;
                        if (surface.equals("tabs") && action.equals("isOpen")) return true;
                        if (action.equals("getItems") || action.equals("getAllChildren")) return List.of();
                        return defaultValue(apiMethod.getReturnType());
                    }));
        }
    }

    private static final class SupplyScenario {
        private final Map<String, Integer> inventory = new HashMap<>();
        private final Map<String, Integer> bank = new HashMap<>();
        private int rangedLevel = 20;
        private int equippedArrows;
        private boolean bankOpen = true;

        private APIContext context() {
            return proxy(APIContext.class, (object, method, arguments) ->
                    proxy(method.getReturnType(), (api, apiMethod, apiArguments) -> {
                        String surface = method.getName();
                        String action = apiMethod.getName();
                        if (surface.equals("skills") && action.equals("get")) {
                            Skill.Skills requested = (Skill.Skills) apiArguments[0];
                            return skill(requested, requested == Skill.Skills.RANGED ? rangedLevel : 40);
                        }
                        if (surface.equals("bank") && action.equals("isOpen")) return bankOpen;
                        if (action.equals("getCount")) {
                            if (surface.equals("inventory")) return count(inventory, apiArguments);
                            if (surface.equals("bank")) return count(bank, apiArguments);
                            if (surface.equals("equipment")) return equippedArrows;
                        }
                        if (surface.equals("equipment") && action.equals("getItem")) {
                            return apiArguments[0] == IEquipmentAPI.Slot.AMMO && equippedArrows > 0
                                    ? proxy(ItemWidget.class, (ammo, ammoMethod, ammoArguments) -> switch (ammoMethod.getName()) {
                                        case "getName" -> "Mithril arrow";
                                        case "getStackSize" -> equippedArrows;
                                        default -> defaultValue(ammoMethod.getReturnType());
                                    }) : null;
                        }
                        if (action.equals("getItems") || action.equals("getAllChildren")) return List.of();
                        return defaultValue(apiMethod.getReturnType());
                    }));
        }

        private int count(Map<String, Integer> items, Object[] arguments) {
            if (arguments == null || arguments.length == 0) return 0;
            Object names = arguments[arguments.length - 1];
            if (!(names instanceof String[] requested)) return 0;
            int count = 0;
            for (String name : requested) count += items.getOrDefault(name, 0);
            return count;
        }
    }

    private static APIContext context(Map<Skill.Skills, Integer> levels, InvocationHandler equipment) {
        return proxy(APIContext.class, (object, method, arguments) -> {
            Class<?> api = method.getReturnType();
            return proxy(api, (apiObject, apiMethod, apiArguments) -> {
                if (method.getName().equals("equipment") && equipment != null) {
                    return equipment.invoke(apiObject, apiMethod, apiArguments);
                }
                if (method.getName().equals("skills") && apiMethod.getName().equals("get")) {
                    Skill.Skills skill = (Skill.Skills) apiArguments[0];
                    return skill(skill, levels.getOrDefault(skill, 1));
                }
                if (apiMethod.getName().equals("getUsername")) {
                    return "test account";
                }
                if (apiMethod.getName().equals("isOpen")) {
                    return true;
                }
                return defaultValue(apiMethod.getReturnType());
            });
        });
    }

    private static Skill skill(Skill.Skills skill, int level) {
        return new Skill(skill) {
            @Override public int getRealLevel() { return level; }
            @Override public int getExperience() { return Skill.getExpAtLevel(level); }
            @Override protected int[] getSkillExperiences() { return new int[Skill.Skills.values().length]; }
            @Override protected int[] getCurrentSkillLevels() { return new int[Skill.Skills.values().length]; }
            @Override protected int[] getRealSkillLevels() { return new int[Skill.Skills.values().length]; }
        };
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Map<Skill.Skills, Integer> caps(SkillCapManager manager) {
        Map<Skill.Skills, Integer> selected = new EnumMap<>(Skill.Skills.class);
        for (Skill.Skills skill : COMBAT_SKILLS) selected.put(skill, manager.capFor(skill));
        return selected;
    }

    private static void checkRanges(SkillCapManager caps) {
        for (Skill.Skills skill : COMBAT_SKILLS) {
            int cap = caps.capFor(skill);
            int min = skill == Skill.Skills.MAGIC ? 22 : 30;
            int max = skill == Skill.Skills.MAGIC ? 25 : 33;
            require(cap >= min && cap <= max, "Combat cap is outside the requested range");
        }
        require(caps.capFor(Skill.Skills.CRAFTING) == 15, "Crafting cap changed unexpectedly");
        require(caps.capFor(Skill.Skills.WOODCUTTING) >= 29 && caps.capFor(Skill.Skills.WOODCUTTING) <= 35,
                "Non-combat skill cap changed unexpectedly");
    }

    private static Properties load(Path profile) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(profile)) { properties.load(input); }
        return properties;
    }

    private static void store(Path profile, Properties properties) throws Exception {
        try (OutputStream output = Files.newOutputStream(profile)) { properties.store(output, "Regression fixture"); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
