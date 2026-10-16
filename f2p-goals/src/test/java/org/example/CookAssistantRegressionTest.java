package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.methods.IInventoryAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import org.example.core.ScriptStats;
import org.example.modules.questing.CookAssistantQuestModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Predicate;

public final class CookAssistantRegressionTest {
    public static void main(String[] args) throws Exception {
        APIContext notedCtx = context(List.of(item("Egg", false), item("Bucket of milk", true),
                item("Pot of flour", false)));
        APIContext readyCtx = context(List.of(item("Egg", false), item("Bucket of milk", false),
                item("Pot of flour", false)));
        CookAssistantQuestModule quest = new CookAssistantQuestModule(message -> { }, new ScriptStats());
        Method all = CookAssistantQuestModule.class.getDeclaredMethod("hasAllFinalIngredients", APIContext.class);
        all.setAccessible(true);
        if ((boolean) all.invoke(quest, notedCtx) || !(boolean) all.invoke(quest, readyCtx))
            throw new AssertionError("Cook requires three usable, unnoted ingredients");
        assertArea("GE_AREA", 3160, 3478, 3175, 3490, 0);
        assertArea("EGG_AREA", 3174, 3292, 3181, 3300, 0);
        assertArea("DAIRY_COW_AREA", 3168, 3312, 3177, 3322, 0);
        assertArea("MILL_AREA", 3161, 3302, 3171, 3311, 0);
        System.out.println("PASS: Cook ingredients exclude notes; GE and local source routes are valid");
    }

    private static APIContext context(List<Item> items) {
        IInventoryAPI inventory = (IInventoryAPI) Proxy.newProxyInstance(
                IInventoryAPI.class.getClassLoader(), new Class<?>[]{IInventoryAPI.class},
                (proxy, method, inputs) -> {
                    if ("contains".equals(method.getName()) && inputs != null
                            && inputs.length == 1 && inputs[0] instanceof Predicate<?> raw) {
                        @SuppressWarnings("unchecked") Predicate<Item> filter = (Predicate<Item>) raw;
                        return items.stream().anyMatch(filter);
                    }
                    return defaultValue(method.getReturnType());
                });
        return (APIContext) Proxy.newProxyInstance(APIContext.class.getClassLoader(),
                new Class<?>[]{APIContext.class}, (proxy, method, inputs) ->
                        "inventory".equals(method.getName()) ? inventory : defaultValue(method.getReturnType()));
    }

    private static Item item(String name, boolean noted) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isNoted" -> noted;
                    case "getId" -> noted ? 2 : 1;
                    case "getNotedId" -> 2;
                    case "getUnNotedId" -> 1;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void assertArea(String fieldName, int minX, int minY, int maxX, int maxY, int plane)
            throws Exception {
        Field field = CookAssistantQuestModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Tile center = ((Area) field.get(null)).getCentralTile();
        if (center.getX() < minX || center.getX() > maxX || center.getY() < minY
                || center.getY() > maxY || center.getPlane() != plane)
            throw new AssertionError(fieldName + " invalid: " + center);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
