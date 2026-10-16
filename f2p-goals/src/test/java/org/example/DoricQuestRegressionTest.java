package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.methods.IInventoryAPI;
import org.example.modules.questing.DoricQuestModule;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Predicate;

public final class DoricQuestRegressionTest {
    public static void main(String[] args) throws Exception {
        List<Item> items = List.of(item("Clay", true, 6), item("Clay", false, 3),
                item("Copper ore", false, 4), item("Iron ore", false, 2));
        IInventoryAPI inventory = (IInventoryAPI) Proxy.newProxyInstance(
                IInventoryAPI.class.getClassLoader(), new Class<?>[]{IInventoryAPI.class},
                (proxy, method, arguments) -> {
                    if ("getCount".equals(method.getName()) && arguments != null
                            && arguments.length == 2 && arguments[1] instanceof Predicate<?> raw) {
                        @SuppressWarnings("unchecked") Predicate<Item> filter = (Predicate<Item>) raw;
                        return items.stream().filter(filter).mapToInt(Item::getStackSize).sum();
                    }
                    return defaultValue(method.getReturnType());
                });
        APIContext ctx = (APIContext) Proxy.newProxyInstance(
                APIContext.class.getClassLoader(), new Class<?>[]{APIContext.class},
                (proxy, method, arguments) -> "inventory".equals(method.getName())
                        ? inventory : defaultValue(method.getReturnType()));
        Method usable = DoricQuestModule.class.getDeclaredMethod("usableCount", APIContext.class, String.class);
        usable.setAccessible(true);
        Method complete = DoricQuestModule.class.getDeclaredMethod("hasAllRequiredItems", APIContext.class);
        complete.setAccessible(true);
        DoricQuestModule quest = new DoricQuestModule(message -> { }, new org.example.core.ScriptStats());
        if ((int) usable.invoke(null, ctx, "Clay") != 3 || (boolean) complete.invoke(quest, ctx))
            throw new AssertionError("Noted clay must not satisfy Doric's 6 clay requirement");
        assertArea("GE_AREA", 3160, 3478, 3175, 3490);
        assertArea("DORIC_AREA", 2946, 3448, 2957, 3458);
        assertArea("DORIC_INSIDE_AREA", 2950, 3449, 2954, 3453);
        assertArea("DORIC_DOOR_AREA", 2948, 3449, 2951, 3451);
        Method inside = DoricQuestModule.class.getDeclaredMethod("isInsideDoricHouse", Tile.class);
        inside.setAccessible(true);
        if ((boolean) inside.invoke(null, new Tile(2948, 3450, 0))
                || !(boolean) inside.invoke(null, new Tile(2952, 3451, 0)))
            throw new AssertionError("Doric must not be clicked through the closed doorway");
        System.out.println("PASS: Doric materials, route coordinates and door-before-talk boundary");
    }

    private static void assertArea(String fieldName, int minX, int minY, int maxX, int maxY)
            throws Exception {
        Field field = DoricQuestModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Tile center = ((Area) field.get(null)).getCentralTile();
        if (center.getX() < minX || center.getX() > maxX || center.getY() < minY
                || center.getY() > maxY || center.getPlane() != 0)
            throw new AssertionError(fieldName + " has invalid destination " + center);
    }

    private static Item item(String name, boolean noted, int count) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isNoted" -> noted;
                    case "getId" -> noted ? 2 : 1;
                    case "getNotedId" -> 2;
                    case "getUnNotedId" -> 1;
                    case "getStackSize" -> count;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
