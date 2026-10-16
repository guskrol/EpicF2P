package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.IScriptAPI;
import com.epicbot.api.shared.random.AntiRandom;
import com.epicbot.api.shared.random.AntiRandomType;
import com.epicbot.api.shared.random.RandomHandler;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.methods.IClientAPI;
import com.epicbot.api.shared.methods.IGameAPI;
import com.epicbot.api.shared.methods.ILocalPlayerAPI;
import org.example.core.ScriptStats;
import org.example.core.runtime.BreakSchedule;
import org.example.core.runtime.LoopWatchdogController;
import org.example.core.runtime.ScheduledBreakController;
import org.example.modules.GoalManagerModule;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BreakLogoutRegressionTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        AtomicBoolean loggedIn = new AtomicBoolean(true);
        AtomicInteger logouts = new AtomicInteger();
        var client = stub(IClientAPI.class, (proxy, method, callArgs) ->
                method.getName().equals("isLoggedIn") ? loggedIn.get() : defaultValue(method.getReturnType()));
        var game = stub(IGameAPI.class, (proxy, method, callArgs) -> {
            if (method.getName().equals("logout")) { logouts.incrementAndGet(); return true; }
            return defaultValue(method.getReturnType());
        });
        var player = stub(ILocalPlayerAPI.class, (proxy, method, callArgs) ->
                defaultValue(method.getReturnType()));
        AntiRandom login = new AntiRandom(AntiRandomType.LOGIN, "test-login") {
            public void execute() { }
            public boolean shouldExecute() { return true; }
        };
        login.setEnabled(true);
        RandomHandler randoms = stub(RandomHandler.class, (proxy, method, callArgs) ->
                method.getName().equals("getRandomHandler") ? login : defaultValue(method.getReturnType()));
        Script script = new TestScript(randoms);
        var scriptApi = stub(IScriptAPI.class, (proxy, method, callArgs) ->
                method.getName().equals("getScript") ? script : defaultValue(method.getReturnType()));
        var ctx = stub(APIContext.class, (proxy, method, callArgs) -> switch (method.getName()) {
            case "client" -> client;
            case "script" -> scriptApi;
            case "game" -> game;
            case "localPlayer" -> player;
            default -> defaultValue(method.getReturnType());
        });
        ScriptStats stats = new ScriptStats();
        var goals = new GoalManagerModule(message -> { }, stats, List.of(), null) {
            @Override public void pauseForBreak() { }
        };
        var watchdog = new LoopWatchdogController(message -> { }, stats);
        var controller = new ScheduledBreakController(message -> { }, goals, watchdog, stats);
        Field scheduleField = ScheduledBreakController.class.getDeclaredField("schedule");
        scheduleField.setAccessible(true);
        BreakSchedule schedule = (BreakSchedule) scheduleField.get(controller);
        Field activityField = BreakSchedule.class.getDeclaredField("activityMs");
        activityField.setAccessible(true);
        activityField.setLong(schedule, 151L * 60_000L);
        Field mealField = BreakSchedule.class.getDeclaredField("mealAt");
        mealField.setAccessible(true);
        EnumMap<BreakSchedule.Type, Long> meals = (EnumMap<BreakSchedule.Type, Long>) mealField.get(schedule);
        meals.put(BreakSchedule.Type.LUNCH, Long.MAX_VALUE);
        meals.put(BreakSchedule.Type.DINNER, Long.MAX_VALUE);
        controller.tick(ctx);
        if (logouts.get() != 1 || schedule.active() != BreakSchedule.Type.NORMAL)
            throw new AssertionError("normal break must request one logout");
        if (login.isEnabled()) throw new AssertionError("login must be blocked before logout");
        loggedIn.set(false);
        controller.tick(ctx);
        loggedIn.set(true);
        controller.tick(ctx);
        controller.tick(ctx);
        if (logouts.get() != 1) throw new AssertionError("relogin triggered repeated logout");
        controller.restoreLogin();
        if (!login.isEnabled()) throw new AssertionError("previous enabled state was not restored");
        login.setEnabled(false);
        var disabledController = new ScheduledBreakController(message -> { }, goals, watchdog, stats);
        java.lang.reflect.Method block = ScheduledBreakController.class.getDeclaredMethod("blockLogin", APIContext.class);
        block.setAccessible(true);
        if (!(Boolean) block.invoke(disabledController, ctx)) throw new AssertionError("block failed");
        disabledController.restoreLogin();
        if (login.isEnabled()) throw new AssertionError("previous disabled state was not preserved");
        System.out.println("PASS: reconnect during normal break does not cause repeated logout");
    }

    @com.epicbot.api.shared.script.ScriptManifest(name = "break-test", gameType = com.epicbot.api.shared.GameType.OS)
    public static class TestScript extends Script {
        private final RandomHandler randoms;
        public TestScript(RandomHandler randoms) { this.randoms = randoms; }
        public boolean onStart(String... args) { return true; }
        @Override public RandomHandler getRandomHandler() { return randoms; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
