package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.methods.IDialogueAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import org.example.core.ScriptStats;
import org.example.modules.questing.KnightsSwordQuestModule;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class KnightsSwordDialogueRegressionTest {
    public static void main(String[] args) throws Exception {
        IDialogueAPI staleDialogue = (IDialogueAPI) Proxy.newProxyInstance(
                IDialogueAPI.class.getClassLoader(), new Class<?>[]{IDialogueAPI.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "isDialogueOpen", "isChatOpen" -> true;
                    case "canContinue" -> false;
                    case "getOptions" -> List.of();
                    default -> defaultValue(method.getReturnType());
                });
        APIContext ctx = (APIContext) Proxy.newProxyInstance(
                APIContext.class.getClassLoader(), new Class<?>[]{APIContext.class},
                (proxy, method, inputs) -> "dialogues".equals(method.getName())
                        ? staleDialogue : defaultValue(method.getReturnType()));
        KnightsSwordQuestModule module = new KnightsSwordQuestModule(message -> { }, new ScriptStats());
        Method handle = KnightsSwordQuestModule.class.getDeclaredMethod("handleDialogue", APIContext.class, int.class);
        handle.setAccessible(true);
        if ((boolean) handle.invoke(module, ctx, 0))
            throw new AssertionError("Stale dialogue flag must not block travel to the Squire");
        AtomicInteger selections = new AtomicInteger();
        WidgetChild unrelatedOption = (WidgetChild) Proxy.newProxyInstance(
                WidgetChild.class.getClassLoader(), new Class<?>[]{WidgetChild.class},
                (proxy, method, inputs) -> "getText".equals(method.getName())
                        ? "Would you like to start another quest?" : defaultValue(method.getReturnType()));
        IDialogueAPI unrelatedDialogue = (IDialogueAPI) Proxy.newProxyInstance(
                IDialogueAPI.class.getClassLoader(), new Class<?>[]{IDialogueAPI.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "isDialogueOpen", "isChatOpen" -> true;
                    case "canContinue" -> false;
                    case "getOptions" -> List.of(unrelatedOption);
                    case "selectOption" -> { selections.incrementAndGet(); yield true; }
                    default -> defaultValue(method.getReturnType());
                });
        APIContext unrelatedCtx = (APIContext) Proxy.newProxyInstance(
                APIContext.class.getClassLoader(), new Class<?>[]{APIContext.class},
                (proxy, method, inputs) -> "dialogues".equals(method.getName())
                        ? unrelatedDialogue : defaultValue(method.getReturnType()));
        if (!(boolean) handle.invoke(module, unrelatedCtx, 1) || selections.get() != 0)
            throw new AssertionError("Unverified dialogue must be left untouched");
        Field until = KnightsSwordQuestModule.class.getDeclaredField("expectedDialogueUntil");
        Field stage = KnightsSwordQuestModule.class.getDeclaredField("expectedDialogueStage");
        until.setAccessible(true);
        stage.setAccessible(true);
        until.setLong(module, System.currentTimeMillis() + 45_000L);
        stage.setInt(module, 1);
        AtomicInteger clicks = new AtomicInteger();
        WidgetChild imcandoOption = (WidgetChild) Proxy.newProxyInstance(
                WidgetChild.class.getClassLoader(), new Class<?>[]{WidgetChild.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "getText" -> "What do you know about the Imcando dwarves?";
                    case "click" -> { clicks.incrementAndGet(); yield true; }
                    default -> defaultValue(method.getReturnType());
                });
        IDialogueAPI reldoDialogue = (IDialogueAPI) Proxy.newProxyInstance(
                IDialogueAPI.class.getClassLoader(), new Class<?>[]{IDialogueAPI.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "isDialogueOpen", "isChatOpen" -> true;
                    case "canContinue", "hasOptionContaining" -> false;
                    case "getOptions" -> List.of(imcandoOption);
                    default -> defaultValue(method.getReturnType());
                });
        APIContext reldoCtx = (APIContext) Proxy.newProxyInstance(
                APIContext.class.getClassLoader(), new Class<?>[]{APIContext.class},
                (proxy, method, inputs) -> "dialogues".equals(method.getName())
                        ? reldoDialogue : defaultValue(method.getReturnType()));
        if (!(boolean) handle.invoke(module, reldoCtx, 1) || clicks.get() != 1)
            throw new AssertionError("Reldo's visible Imcando option must be clicked directly");
        stage.setInt(module, 3);
        WidgetChild narrative = (WidgetChild) Proxy.newProxyInstance(
                WidgetChild.class.getClassLoader(), new Class<?>[]{WidgetChild.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "getText" -> "Thurgo describes the sword.";
                    case "click" -> { clicks.incrementAndGet(); yield true; }
                    default -> defaultValue(method.getReturnType());
                });
        IDialogueAPI narrativeDialogue = (IDialogueAPI) Proxy.newProxyInstance(
                IDialogueAPI.class.getClassLoader(), new Class<?>[]{IDialogueAPI.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "isDialogueOpen", "isChatOpen" -> true;
                    case "canContinue" -> false;
                    case "getOptions" -> List.of(narrative);
                    default -> defaultValue(method.getReturnType());
                });
        APIContext narrativeCtx = (APIContext) Proxy.newProxyInstance(
                APIContext.class.getClassLoader(), new Class<?>[]{APIContext.class},
                (proxy, method, inputs) -> "dialogues".equals(method.getName())
                        ? narrativeDialogue : defaultValue(method.getReturnType()));
        if (!(boolean) handle.invoke(module, narrativeCtx, 4) || clicks.get() != 2
                || stage.getInt(module) != 4)
            throw new AssertionError("Verified conversation must continue across quest stage changes");
        AtomicInteger cupboardClicks = new AtomicInteger();
        SceneObject cupboard = (SceneObject) Proxy.newProxyInstance(
                SceneObject.class.getClassLoader(), new Class<?>[]{SceneObject.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "isValid", "hasAction" -> true;
                    case "getName" -> "Cupboard";
                    case "interact" -> { cupboardClicks.incrementAndGet(); yield true; }
                    default -> defaultValue(method.getReturnType());
                });
        Method openDoor = KnightsSwordQuestModule.class.getDeclaredMethod(
                "tryOpenDoor", APIContext.class, SceneObject.class, String.class);
        openDoor.setAccessible(true);
        if ((boolean) openDoor.invoke(module, null, cupboard, "portrait") || cupboardClicks.get() != 0)
            throw new AssertionError("Cupboard must not be treated as a route door");
        Method keepItem = KnightsSwordQuestModule.class.getDeclaredMethod("isQuestCompatibleItem", String.class);
        keepItem.setAccessible(true);
        for (String name : List.of("Coins", "Portrait", "Blurite sword", "Blurite ore",
                "Iron bar", "Redberry pie", "Bronze pickaxe", "Trout")) {
            if (!(boolean) keepItem.invoke(module, name))
                throw new AssertionError("Required quest item would be deposited: " + name);
        }
        if ((boolean) keepItem.invoke(module, "Iron platebody"))
            throw new AssertionError("Unrelated gear should be banked before the quest");
        Method noted = KnightsSwordQuestModule.class.getDeclaredMethod("isNoted", Item.class);
        noted.setAccessible(true);
        Item notedBar = ironBar(true);
        Item regularBar = ironBar(false);
        if (!(boolean) noted.invoke(null, notedBar) || (boolean) noted.invoke(null, regularBar))
            throw new AssertionError("Iron bars in note form must not count as usable bars");
        assertArea("SQUIRE", 2974, 3337, 2982, 3345, 0);
        assertArea("RELDO", 3207, 3490, 3215, 3498, 0);
        assertArea("THURGO", 2996, 3141, 3004, 3149, 0);
        assertArea("GE", 3160, 3478, 3175, 3490, 0);
        assertArea("BEDROOM", 2981, 3331, 2986, 3336, 2);
        assertArea("DUNGEON", 2979, 9538, 3069, 9602, 0);
        System.out.println("PASS: dialogue safety, cupboard exclusion, quest inventory keep-list, and destinations");
    }

    private static void assertArea(String fieldName, int minX, int minY, int maxX, int maxY, int plane)
            throws Exception {
        Field field = KnightsSwordQuestModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Area area = (Area) field.get(null);
        Tile center = area.getCentralTile();
        if (center.getX() < minX || center.getX() > maxX || center.getY() < minY
                || center.getY() > maxY || center.getPlane() != plane) {
            throw new AssertionError(fieldName + " destination invalid: " + center);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static Item ironBar(boolean noted) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, inputs) -> switch (method.getName()) {
                    case "getName" -> "Iron bar";
                    case "isNoted" -> noted;
                    case "getId" -> noted ? 2364 : 2353;
                    case "getNotedId" -> 2364;
                    case "getUnNotedId" -> 2353;
                    default -> defaultValue(method.getReturnType());
                });
    }
}
