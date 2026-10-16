package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.methods.IDialogueAPI;
import com.epicbot.api.shared.methods.IInventoryAPI;
import com.epicbot.api.shared.methods.ILocalPlayerAPI;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.methods.IVariablesAPI;
import com.epicbot.api.shared.methods.IWidgetsAPI;
import com.epicbot.api.shared.model.Tile;
import org.example.core.ScriptStats;
import org.example.core.navigation.AccessRecovery;
import org.example.modules.questing.RomeoJulietQuestModule;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class RomeoSceneTransitionRegressionTest {
    public static void main(String[] args) throws Exception {
        int[] stage = {50};
        List<String> logs = new ArrayList<>();
        var player = stub(ILocalPlayerAPI.class, (p, m, a) -> m.getName().equals("getLocation")
                ? new Tile(10509, 13129, 1) : value(m.getReturnType()));
        var dialogue = stub(IDialogueAPI.class, (p, m, a) -> m.getName().equals("getOptions")
                ? List.of() : m.getName().equals("getText") ? "" : value(m.getReturnType()));
        var inventory = stub(IInventoryAPI.class, (p, m, a) -> value(m.getReturnType()));
        var quests = stub(IQuestAPI.class, (p, m, a) -> value(m.getReturnType()));
        var widgets = stub(IWidgetsAPI.class, (p, m, a) -> m.getName().equals("getAllChildren")
                ? List.of() : value(m.getReturnType()));
        var vars = stub(IVariablesAPI.class, (p, m, a) -> m.getName().equals("getVarp")
                ? stage[0] : value(m.getReturnType()));
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "localPlayer" -> player;
            case "dialogues" -> dialogue;
            case "inventory" -> inventory;
            case "quests" -> quests;
            case "vars" -> vars;
            case "widgets" -> widgets;
            default -> value(m.getReturnType());
        });
        RomeoJulietQuestModule module = new RomeoJulietQuestModule(logs::add, new ScriptStats());
        AccessRecovery.beginScope(ctx, module.name());
        module.execute(ctx);
        check(logs.stream().anyMatch(log -> log.contains("waiting for Juliet scene")),
                "remapped scene must wait rather than route to the world-map Juliet tile");

        Tile oldJulietRoute = new Tile(3158, 3427, 1);
        AccessRecovery.destination(ctx, oldJulietRoute);
        stage[0] = 60;
        Field nextAction = RomeoJulietQuestModule.class.getDeclaredField("nextActionAt");
        nextAction.setAccessible(true);
        nextAction.setLong(module, 0L);
        module.execute(ctx);
        for (int i = 0; i < 3; i++) {
            AccessRecovery.onChatMessage(ctx, new ChatMessageEvent(
                    ChatMessageEvent.ChatMessageType.GAMEMESSAGE.getType(), 1, "", "I can't reach that!"));
        }
        check(!AccessRecovery.mustYield(ctx) && !AccessRecovery.isScopeBlocked(ctx, module.name()),
                "old Juliet target cannot quarantine the quest after the stage changes");
        check(logs.stream().anyMatch(log -> log.contains("cleared previous route after stage/plane transition")),
                "stage transition invalidates the prior route");
        AccessRecovery.reset(ctx);
        System.out.println("PASS: Romeo & Juliet instance wait and old route invalidation at stage 60");
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> type.getSimpleName();
            };
            return handler.invoke(p, m, a);
        });
    }

    private static Object value(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
