package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import org.example.core.ScriptStats;
import org.example.core.ManagedF2PModule;
import org.example.core.SkillCapManager;
import org.example.core.funding.FundingPlanner;
import org.example.core.items.WikiPriceClient;
import org.example.modules.skilling.CraftingModule;
import org.example.modules.GoalManagerModule;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CraftingFundingRegressionTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("f2p-crafting-funding-regression-");
        Object originalMapping = getField(WikiPriceClient.class, "itemIdsByName");
        Object originalMappingTime = getField(WikiPriceClient.class, "mappingFetchedAt");
        try {
            // Keep these control-flow tests independent of live market prices and network access.
            setField(WikiPriceClient.class, "itemIdsByName", Map.of("regression-only", 1));
            setField(WikiPriceClient.class, "mappingFetchedAt", System.currentTimeMillis());
            testBeerGlassCleanup(directory.resolve("beer"));
            testInventoryBankingRules(directory.resolve("inventory"));
            testMiningCleanupAndToolSetup(directory.resolve("mining"));
            testBankStockSale(directory.resolve("stock"));
            testSellAvailableStockBeforeGrinding();
            testFundingTarget(directory.resolve("target"));
            testStalledFundingProtection(directory.resolve("stall"));
            testWatchdogReroll(directory.resolve("watchdog"));
            System.out.println("PASS: Crafting funding bank cleanup, tool setup, stock sale, target and stall recovery");
        } finally {
            setField(WikiPriceClient.class, "itemIdsByName", originalMapping);
            setField(WikiPriceClient.class, "mappingFetchedAt", originalMappingTime);
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void testSellAvailableStockBeforeGrinding() {
        FundingPlanner planner = new FundingPlanner();
        FundingPlanner.Decision partial = planner.choose(1_000, 100, List.of(
                new FundingPlanner.Asset("Bronze bar", 0, 2, 170)));
        require(partial.method() == FundingPlanner.Method.SELL_READY_STOCK,
                "Banked stock below the full shortage must be sold before gathering more gold");
        require(planner.choose(1_000, 1_200, List.of(
                new FundingPlanner.Asset("Bronze bar", 0, 2, 170))).method()
                == FundingPlanner.Method.SELL_READY_STOCK,
                "Remaining eligible stock must still be selected after the coin target is reached");
    }

    private static void testBeerGlassCleanup(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.BEER_GLASS);
        scenario.bankOpen = false;
        scenario.inventory.putAll(Map.of("Needle", 1, "Thread", 80, "Leather", 4,
                "Leather gloves", 2, "Mind rune", 25, "Coins", 20));
        scenario.module.execute(scenario.ctx);
        require(scenario.bankOpen && scenario.opens == 1 && scenario.closes == 0,
                "Beer glass must open the Al Kharid bank to clean the Crafting inventory");

        scenario.module.execute(scenario.ctx);
        require(scenario.bankOpen && scenario.closes == 0 && scenario.deposits == 1,
                "Crafting must not close the bank before Beer glass deposits its unwanted items");
        require(scenario.inventory.equals(Map.of("Coins", 20)),
                "Needle, Thread, Leather, products and unrelated items must leave the farming inventory");
        require(scenario.bank.get("Thread") == 80 && scenario.bank.get("Leather") == 4,
                "Crafting materials must be preserved in the bank");

        scenario.module.execute(scenario.ctx);
        require(!scenario.bankOpen && scenario.closes == 1, "Clean funding inventory must close the bank once");
        require(scenario.method() == FundingPlanner.Method.BEER_GLASS,
                "Bank setup must preserve the current funding method rather than reroll it");
        scenario.moving = true;
        scenario.module.execute(scenario.ctx);
        require(scenario.opens == 1, "After cleanup, Beer glass must not reopen the bank for the same items");
    }

    private static void testInventoryBankingRules(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.BEER_GLASS);
        for (String name : List.of("Needle", "Thread", "Leather", "Leather gloves", "Mind rune", "Beer glass")) {
            scenario.inventory.clear();
            scenario.inventory.put(name, 1);
            for (FundingPlanner.Method method : List.of(FundingPlanner.Method.BEER_GLASS,
                    FundingPlanner.Method.MINING_SMITHING)) {
                require((boolean) invoke(scenario.module, "fundingMoneyMakerShouldBankInventory", scenario.ctx, method),
                        "Funding bank work must not ignore " + name + " for " + method);
            }
        }
        scenario.inventory.clear();
        scenario.inventory.put("Coins", 20);
        require(!(boolean) invoke(scenario.module, "fundingMoneyMakerShouldBankInventory", scenario.ctx,
                FundingPlanner.Method.BEER_GLASS), "Coins alone must not require funding inventory cleanup");
        scenario.inventory.put("Bronze pickaxe", 1);
        require(!(boolean) invoke(scenario.module, "fundingMoneyMakerShouldBankInventory", scenario.ctx,
                FundingPlanner.Method.MINING_SMITHING), "A usable pickaxe must not prevent auditing banked stock");
        require((boolean) invoke(scenario.module, "fundingMoneyMakerShouldBankInventory", scenario.ctx,
                FundingPlanner.Method.BEER_GLASS), "Beer glass must bank an unrelated pickaxe");
    }

    private static void testMiningCleanupAndToolSetup(Path directory) throws Exception {
        Scenario cleanup = new Scenario(directory.resolve("cleanup"), FundingPlanner.Method.MINING_SMITHING);
        cleanup.inventory.putAll(Map.of("Needle", 1, "Thread", 40, "Bronze pickaxe", 1));
        cleanup.module.execute(cleanup.ctx);
        require(cleanup.deposits == 1 && !cleanup.inventory.containsKey("Needle")
                        && !cleanup.inventory.containsKey("Thread") && cleanup.inventory.containsKey("Bronze pickaxe"),
                "Mining funding must complete its own inventory cleanup and preserve the pickaxe");

        Scenario setup = new Scenario(directory.resolve("setup"), FundingPlanner.Method.MINING_SMITHING);
        setup.bank.put("Bronze pickaxe", 1);
        setup.module.execute(setup.ctx);
        require(setup.inventory.getOrDefault("Bronze pickaxe", 0) == 1,
                "Crafting must let Mining withdraw its tool before closing the bank");
        require(setup.method() == FundingPlanner.Method.MINING_SMITHING,
                "Mining bank setup must not switch to a random funding method");
    }

    private static void testBankStockSale(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.MINING_SMITHING);
        scenario.inventory.put("Bronze pickaxe", 1);
        scenario.bank.put("Beer glass", 300);
        setField(scenario.module, "activeFundingTargetCoins", 100);
        scenario.module.execute(scenario.ctx);
        require("Beer glass".equals(getField(scenario.module, "pendingFundingSellItem")),
                "Banked funding stock must still be audited and selected for sale");
        require((int) getField(scenario.module, "pendingFundingSellQuantity") == 300,
                "The full eligible stack should be listed, not only the shortage quantity");
        require(scenario.method() == FundingPlanner.Method.SELL_READY_STOCK,
                "Completing bank cleanup must not bypass the funding stock sale");
    }

    private static void testFundingTarget(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.BEER_GLASS);
        scenario.bank.put("Coins", 10000);
        scenario.module.execute(scenario.ctx);
        require(getField(scenario.module, "activeFundingDecision") == null && !scenario.bankOpen,
                "Enough banked coins must exit funding and close the bank");
        require(!scenario.module.isProtectedSubphase(scenario.ctx), "Finished funding must not remain protected");
    }

    private static void testStalledFundingProtection(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.BEER_GLASS);
        scenario.inventory.put("Needle", 1);
        require(scenario.module.isProtectedSubphase(scenario.ctx), "New funding must initially be protected");
        long stale = System.currentTimeMillis() - 6 * 60_000L;
        setField(scenario.module, "lastFundingProgressAt", stale);
        scenario.bankOpen = false;
        scenario.stats.setStatus("Opening bank again");
        require(!scenario.module.isProtectedSubphase(scenario.ctx),
                "Bank toggles and changing log messages must not refresh stalled funding protection");
        scenario.bankOpen = true;
        require(!scenario.module.isProtectedSubphase(scenario.ctx),
                "Reopening the same bank must leave stalled funding eligible for watchdog recovery");
        scenario.inventory.put("Beer glass", 1);
        require(scenario.module.isProtectedSubphase(scenario.ctx), "Actual inventory progress must restore protection");
        setField(scenario.module, "lastFundingProgressAt", stale);
        scenario.miningXp++;
        require(scenario.module.isProtectedSubphase(scenario.ctx), "Mining XP must count as funding progress");
        setField(scenario.module, "lastFundingProgressAt", stale);
        scenario.bank.put("Coins", 500);
        require(scenario.module.isProtectedSubphase(scenario.ctx), "Newly audited coins must count as funding progress");
        setField(scenario.module, "lastFundingProgressAt", stale);
        scenario.bankOpen = false;
        require(!scenario.module.isProtectedSubphase(scenario.ctx),
                "Hiding bank coins must not be treated as material progress");
        setField(scenario.module, "activeFundingDecision", null);
        setField(scenario.module, "pendingFundingSellItem", "Beer glass");
        require(!scenario.module.isProtectedSubphase(scenario.ctx), "Stalled pending sales must also allow recovery");
        invoke(scenario.module, "clearCraftingFundingPool");
        require(getField(scenario.module, "lastFundingProgressSignature") == null,
                "Leaving funding must reset its progress tracker");
    }

    private static void testWatchdogReroll(Path directory) throws Exception {
        Scenario scenario = new Scenario(directory, FundingPlanner.Method.BEER_GLASS);
        scenario.bankOpen = false;
        scenario.inventory.put("Needle", 1);
        scenario.module.isProtectedSubphase(scenario.ctx);
        setField(scenario.module, "lastFundingProgressAt", System.currentTimeMillis() - 6 * 60_000L);
        ManagedF2PModule alternative = new ManagedF2PModule() {
            @Override public String name() { return "regression.alternative"; }
            @Override public boolean shouldExecute(APIContext ctx) { return true; }
            @Override public boolean isComplete(APIContext ctx) { return false; }
            @Override public void execute(APIContext ctx) {}
        };
        GoalManagerModule manager = new GoalManagerModule(message -> {}, scenario.stats,
                List.of(scenario.module, alternative), alternative);
        setField(manager, "activeModule", scenario.module);
        setField(manager, "activeUntil", Long.MAX_VALUE);
        setField(manager, "inventoryCleanupPending", false);
        manager.requestReroll("Crafting bank loop without material progress");
        manager.execute(scenario.ctx);
        require(getField(manager, "activeModule") == alternative,
                "The goal manager must honor watchdog recovery when Crafting funding is stalled");
    }

    private static final class Scenario {
        private final Map<String, Integer> inventory = new HashMap<>();
        private final Map<String, Integer> bank = new HashMap<>();
        private final ScriptStats stats = new ScriptStats();
        private final CraftingModule module;
        private final APIContext ctx;
        private boolean bankOpen = true;
        private boolean moving;
        private int miningXp;
        private int opens;
        private int closes;
        private int deposits;

        private Scenario(Path directory, FundingPlanner.Method method) throws Exception {
            module = new CraftingModule(message -> {}, stats, new SkillCapManager(directory));
            setField(module, "activeFundingDecision", new FundingPlanner.Decision(method, "stock", 0, 0, 0L));
            setField(module, "activeFundingTargetItem", "Leather");
            setField(module, "activeFundingTargetCoins", 10000);
            ctx = proxy(APIContext.class, (object, apiMethod, apiArguments) -> {
                    if (apiMethod.getDeclaringClass() == Object.class) {
                        return switch (apiMethod.getName()) {
                            case "hashCode" -> System.identityHashCode(object);
                            case "equals" -> object == apiArguments[0];
                            case "toString" -> "CraftingFundingContext";
                            default -> null;
                        };
                    }
                    return proxy(apiMethod.getReturnType(), (api, methodCall, arguments) -> {
                        String surface = apiMethod.getName();
                        String action = methodCall.getName();
                        Map<String, Integer> items = surface.equals("bank") ? bank : inventory;
                        if (surface.equals("skills") && action.equals("get")) {
                            Skill.Skills requested = (Skill.Skills) arguments[0];
                            return skill(requested, requested == Skill.Skills.MINING ? miningXp : 0);
                        }
                        if (surface.equals("localPlayer")) {
                            if (action.equals("getLocation")) return new Tile(3270, 3167, 0);
                            if (action.equals("isMoving")) return moving;
                        }
                        if (surface.equals("inventory") || surface.equals("bank")) {
                            if (action.equals("getItems")) return items.entrySet().stream()
                                    .map(entry -> item(entry.getKey(), entry.getValue())).toList();
                            if (action.equals("getCount")) return count(items, arguments);
                            if (action.equals("contains")) return count(items, arguments) > 0;
                            if (action.equals("isEmpty")) return items.isEmpty();
                            if (action.equals("getEmptySlotCount")) return 28 - inventory.size();
                        }
                        if (surface.equals("bank")) {
                            if (action.equals("isOpen")) return bankOpen;
                            if (action.equals("isReachable") || action.equals("isWithdrawMode")) return true;
                            if (action.equals("open")) { opens++; bankOpen = true; return true; }
                            if (action.equals("close")) { closes++; bankOpen = false; return true; }
                            if (action.equals("depositAllExcept")) {
                                List<String> keep = Arrays.asList((String[]) arguments[0]);
                                for (String name : new ArrayList<>(inventory.keySet())) {
                                    if (!keep.contains(name)) bank.merge(name, inventory.remove(name), Integer::sum);
                                }
                                deposits++;
                                return true;
                            }
                            if (action.equals("withdraw")) {
                                int amount = (int) arguments[0];
                                String name = ((String[]) arguments[1])[0];
                                amount = Math.min(amount, bank.getOrDefault(name, 0));
                                if (amount <= 0) return false;
                                int remaining = bank.get(name) - amount;
                                if (remaining == 0) bank.remove(name); else bank.put(name, remaining);
                                inventory.merge(name, amount, Integer::sum);
                                return true;
                            }
                        }
                        if (action.equals("getAllChildren")) return List.of();
                        return defaultValue(methodCall.getReturnType());
                    });
            });
        }

        private FundingPlanner.Method method() throws Exception {
            return ((FundingPlanner.Decision) getField(module, "activeFundingDecision")).method();
        }
    }

    private static int count(Map<String, Integer> items, Object[] arguments) {
        if (arguments == null || arguments.length == 0) return items.size();
        Object last = arguments[arguments.length - 1];
        if (!(last instanceof String[] names)) return 0;
        return Arrays.stream(names).mapToInt(name -> items.getOrDefault(name, 0)).sum();
    }

    private static ItemWidget item(String name, int amount) {
        return proxy(ItemWidget.class, (object, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getId" -> name.hashCode();
            case "getStackSize" -> amount;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Skill skill(Skill.Skills skill, int xp) {
        return new Skill(skill) {
            @Override public int getRealLevel() { return 1; }
            @Override public int getExperience() { return xp; }
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

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        Method method = Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = (target instanceof Class<?> type ? type : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target instanceof Class<?> ? null : target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        return field(target, name).get(target instanceof Class<?> ? null : target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
