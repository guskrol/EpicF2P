package org.example.core.runtime;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.methods.IDialogueAPI;
import com.epicbot.api.shared.methods.IWidgetsAPI;
import com.epicbot.api.shared.methods.ILocalPlayerAPI;
import com.epicbot.api.shared.methods.IWebWalkingAPI;
import com.epicbot.api.shared.methods.IInventoryAPI;
import com.epicbot.api.shared.methods.IMenuAPI;
import com.epicbot.api.shared.methods.IKeyboardAPI;
import com.epicbot.api.shared.methods.ICalculationsAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.navigation.AccessRecovery;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

public final class AlKharidGateRegressionTest {
    public static void main(String[] args) throws Exception {
        Scenario s = new Scenario();
        var controller = s.controller();
        s.widgets = List.of(widget("You must pay a toll of 10 gold.", 231, 4, true));
        check(!controller.shouldExecute(s.ctx), "hidden toll widgets cannot seize navigation despite stale chat flags");
        s.widgets = List.of(widget("You must pay a toll of 10 gold.", InterfaceID.CHATBOX, 999, false));
        check(!controller.shouldExecute(s.ctx), "visible chat history is not a current toll dialogue");
        s.widgets = List.of();
        s.options = List.of(widget("No, thanks.", 219, 1, false));
        check(!controller.shouldExecute(s.ctx), "unrelated negative options near the gate are not toll evidence");

        s.widgets = List.of(widget("You must pay a toll of 10 gold.", 231, 4, false));
        s.options = List.of();
        check(controller.shouldExecute(s.ctx), "an actual visible toll dialogue activates recovery");
        controller.execute(s.ctx);
        check(s.walks == 1, "unactionable dialogue must start a walk that cancels the conversation");
        check(s.keys == 0, "do not send Escape or toggle inventory to dismiss stale dialogue");
        check(controller.shouldExecute(s.ctx), "bypass persists while the dialogue API remains open");
        controller.execute(s.ctx);
        check(s.walks == 2 && s.keys == 0, "stale chat flags cannot block the bypass walk");
        s.here = new Tile(3262, 3265, 0);
        check(controller.shouldExecute(s.ctx), "reaching the approach tile must not end the detour prematurely");
        s.here = new Tile(3269, 3278, 0);
        controller.execute(s.ctx);
        s.widgets = List.of();
        check(!controller.shouldExecute(s.ctx), "reaching the exit releases the normal task route");

        s = new Scenario();
        controller = s.controller();
        s.widgets = List.of(widget("You must pay a toll of 10 gold.", 231, 4, false));
        check(controller.shouldExecute(s.ctx), "fixture activates gate context");
        AccessRecovery.beginScope(s.ctx, "money.beer_glass");
        AccessRecovery.destination(s.ctx, s.here);
        controller.execute(s.ctx);
        for (int retry = 0; retry < 2; retry++) {
            set(controller, "fallbackProgressAt", System.currentTimeMillis() - 21_000L);
            controller.execute(s.ctx);
        }
        set(controller, "fallbackProgressAt", System.currentTimeMillis() - 21_000L);
        boolean yielded = false;
        try { controller.execute(s.ctx); }
        catch (CancellationException expected) { yielded = true; }
        check(yielded && AccessRecovery.mustYield(s.ctx), "stalled bypass yields to generic recovery after bounded retries");
        check(s.keys == 0, "bounded recovery never toggles inventory with Escape");
        System.out.println("PASS: stale/hidden toll widgets, unrelated chat, persistent north bypass and bounded recovery");
    }

    private static final class Scenario {
        Tile here = new Tile(3265, 3227, 0);
        List<WidgetChild> widgets = List.of();
        List<WidgetChild> options = List.of();
        int walks;
        int keys;
        final APIContext ctx;

        Scenario() {
            var dialogue = stub(IDialogueAPI.class, (p, m, a) -> switch (m.getName()) {
                case "isChatOpen", "isDialogueOpen" -> true; // Deliberately stale API flags.
                case "getOptions" -> options;
                default -> value(m.getReturnType());
            });
            var widgetApi = stub(IWidgetsAPI.class, (p, m, a) -> {
                if (m.getName().equals("getAllChildren")) {
                    @SuppressWarnings("unchecked") Predicate<WidgetChild> filter = (Predicate<WidgetChild>) a[0];
                    return widgets.stream().filter(filter).toList();
                }
                return value(m.getReturnType());
            });
            var player = stub(ILocalPlayerAPI.class, (p, m, a) -> m.getName().equals("getLocation")
                    ? here : value(m.getReturnType()));
            var web = stub(IWebWalkingAPI.class, (p, m, a) -> {
                if (m.getName().equals("walkTo")) { walks++; return WalkState.SUCCESS; }
                return value(m.getReturnType());
            });
            var inventory = stub(IInventoryAPI.class, (p, m, a) -> value(m.getReturnType()));
            var calculations = stub(ICalculationsAPI.class, (p, m, a) ->
                    m.getName().equals("tileDistanceTo") ? 100 : value(m.getReturnType()));
            var menu = stub(IMenuAPI.class, (p, m, a) -> value(m.getReturnType()));
            var keyboard = stub(IKeyboardAPI.class, (p, m, a) -> {
                if (m.getName().equals("sendKey")) keys++;
                return value(m.getReturnType());
            });
            ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
                case "dialogues" -> dialogue;
                case "widgets" -> widgetApi;
                case "localPlayer" -> player;
                case "webWalking" -> web;
                case "inventory" -> inventory;
                case "menu" -> menu;
                case "keyboard" -> keyboard;
                case "calculations" -> calculations;
                default -> value(m.getReturnType());
            });
        }

        AlKharidGateDialogueController controller() {
            return new AlKharidGateDialogueController(message -> {}, null);
        }
    }

    private static WidgetChild widget(String text, int groupId, int childId, boolean hidden) {
        var group = stub(WidgetGroup.class, (p, m, a) -> m.getName().equals("getIndex") ? groupId : value(m.getReturnType()));
        return stub(WidgetChild.class, (p, m, a) -> switch (m.getName()) {
            case "getText", "getRawText" -> text;
            case "isValid" -> true;
            case "isVisible" -> !hidden;
            case "isHidden" -> hidden;
            case "getWidth", "getHeight" -> 100;
            case "getGroup" -> group;
            case "getParentId" -> groupId;
            case "getChildId" -> childId;
            default -> value(m.getReturnType());
        });
    }

    private static void set(Object target, String name, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
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
