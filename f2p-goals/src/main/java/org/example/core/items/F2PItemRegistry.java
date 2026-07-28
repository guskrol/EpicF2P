package org.example.core.items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class F2PItemRegistry {
    private static final Map<String, ItemRule> ITEMS = new LinkedHashMap<>();

    static {
        register(new ItemRule("Beer glass", true, false, 25, 20, false, false, true, 140));
        register(new ItemRule("Bronze bar", true, false, 220, 170, false, false, true, 50));
        register(new ItemRule("Logs", true, false, 35, 20, false, false, true, 100));
        register(new ItemRule("Oak logs", true, false, 45, 30, false, false, true, 100));
        register(new ItemRule("Willow logs", true, false, 35, 20, false, false, true, 100));

        register(new ItemRule("Cowhide", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Hard leather", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Leather", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Feather", false, false, 0, 0, false, true, true, 0));
        register(new ItemRule("Feathers", false, false, 0, 0, false, true, true, 0));
        register(new ItemRule("Shrimps", false, true, 0, 0, false, false, true, 0));
        register(new ItemRule("Anchovies", false, true, 0, 0, false, false, true, 0));
        register(new ItemRule("Raw shrimps", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Raw anchovies", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Raw beef", false, false, 0, 0, false, false, true, 0));
        register(new ItemRule("Raw chicken", true, false, 0, 0, false, false, true, 54));

        register(new ItemRule("Trout", true, true, 45, 30, false, false, true, 0));
        register(new ItemRule("Salmon", true, true, 70, 45, false, false, true, 0));
        register(new ItemRule("Herring", true, true, 35, 20, false, false, true, 0));
        register(new ItemRule("Sardine", true, true, 30, 18, false, false, true, 0));
        register(new ItemRule("Cooked shrimp", true, true, 25, 10, false, false, true, 0));
        register(new ItemRule("Bread", true, true, 35, 20, false, false, true, 0));
        register(new ItemRule("Cooked meat", true, true, 35, 15, false, false, true, 0));
        register(new ItemRule("Cooked chicken", true, true, 35, 15, false, false, true, 0));

        register(new ItemRule("Bronze arrow", true, false, 8, 1, false, true, true, 0));
        register(new ItemRule("Steel arrow", true, false, 8, 2, false, true, true, 0));
        register(new ItemRule("Mithril arrow", true, false, 8, 2, false, true, true, 0));
        register(new ItemRule("Air rune", true, false, 5, 4, false, true, true, 0));
        register(new ItemRule("Mind rune", true, false, 4, 2, false, true, true, 0));
        register(new ItemRule("Fire rune", true, false, 5, 3, false, true, true, 0));
        register(new ItemRule("Water rune", true, false, 7, 3, false, true, true, 0));
        register(new ItemRule("Earth rune", true, false, 8, 4, false, true, true, 0));
        register(new ItemRule("Body rune", true, false, 5, 3, false, true, true, 0));

        register(new ItemRule("Steel axe", true, false, 600, 150, true, false, true, 0));
        register(new ItemRule("Mithril axe", true, false, 1500, 600, true, false, true, 0));
        register(new ItemRule("Steel pickaxe", true, false, 800, 250, true, false, true, 0));
        register(new ItemRule("Mithril pickaxe", true, false, 1600, 700, true, false, true, 0));

        registerOldGear("Bronze scimitar", 20);
        registerOldGear("Iron scimitar", 80);
        registerOldGear("Steel scimitar", 300);
        registerOldGear("Black scimitar", 1000);
        registerOldGear("Mithril scimitar", 900);
        registerOldGear("Bronze sword", 15);
        registerOldGear("Iron sword", 40);
        registerOldGear("Steel sword", 120);
        registerOldGear("Black sword", 450);
        registerOldGear("Mithril sword", 450);
        registerOldGear("Shortbow", 30);
        registerOldGear("Oak shortbow", 70);
        registerOldGear("Willow shortbow", 120);
        registerGear("Leather cowl", 300, 40);
        registerGear("Leather body", 300, 40);
        registerGear("Leather chaps", 300, 40);
        registerGear("Leather vambraces", 300, 40);
        registerGear("Leather boots", 250, 40);
        registerGear("Coif", 500, 120);
        registerGear("Studded body", 1_500, 600);
        registerGear("Studded chaps", 1_200, 500);
        registerGear("Green d'hide vambraces", 900, 250);
    }

    private F2PItemRegistry() {
    }

    public static boolean isGeSellable(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule == null || rule.geSellable();
    }

    public static boolean isFood(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule != null && rule.food();
    }

    public static int buyPrice(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule == null ? 0 : rule.buyPrice();
    }

    public static int sellPrice(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule == null ? 0 : rule.sellPrice();
    }

    public static boolean isOldGearSellable(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule != null && rule.oldGearSellable();
    }

    public static boolean isStackable(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule != null && rule.stackable();
    }

    public static boolean isNoteable(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule == null || rule.noteable();
    }

    public static int minimumSaleBatch(String itemName) {
        ItemRule rule = lookup(itemName);
        return rule == null ? 0 : rule.minimumSaleBatch();
    }

    public static String[] fundingSellItems() {
        return namesWhere(rule -> rule.geSellable() && rule.minimumSaleBatch() > 0);
    }

    public static String[] restrictedGeItems() {
        return namesWhere(rule -> !rule.geSellable());
    }

    public static String[] foodItems() {
        return namesWhere(ItemRule::food);
    }

    private static void registerOldGear(String name, int sellPrice) {
        register(new ItemRule(name, true, false, Math.max(1, sellPrice * 2), sellPrice, true, false, true, 0));
    }

    private static void registerGear(String name, int buyPrice, int sellPrice) {
        register(new ItemRule(name, true, false, Math.max(1, buyPrice), Math.max(1, sellPrice), true, false, true, 0));
    }

    private static void register(ItemRule rule) {
        ITEMS.put(normalize(rule.name()), rule);
    }

    private static ItemRule lookup(String itemName) {
        return ITEMS.get(normalize(itemName));
    }

    private static String[] namesWhere(RulePredicate predicate) {
        List<String> names = new ArrayList<>();
        for (ItemRule rule : ITEMS.values()) {
            if (predicate.test(rule)) {
                names.add(rule.name());
            }
        }
        return names.toArray(new String[0]);
    }

    private static String normalize(String itemName) {
        if (itemName == null) {
            return "";
        }
        return itemName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private interface RulePredicate {
        boolean test(ItemRule rule);
    }

    public record ItemRule(
            String name,
            boolean geSellable,
            boolean food,
            int buyPrice,
            int sellPrice,
            boolean oldGearSellable,
            boolean stackable,
            boolean noteable,
            int minimumSaleBatch
    ) {
    }
}
