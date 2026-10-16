package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.methods.ILocalPlayerAPI;
import com.epicbot.api.shared.methods.IWalkingAPI;
import com.epicbot.api.shared.methods.IInventoryAPI;
import com.epicbot.api.shared.methods.IMenuAPI;
import org.example.core.ManagedF2PModule;
import org.example.core.funding.FundingPlanner;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class AccessRecoveryRegressionTest {
    public static void main(String[] args) {
        Tile door = new Tile(3321, 3142, 0);
        AccessFailureTracker tracker = new AccessFailureTracker();
        tracker.intend("money.beer_glass", door, 11773, "Open", 1000L);
        check(tracker.record("The door is locked.", 1000L).count() == 1, "first refusal");
        check(tracker.record("The door is locked.", 1100L) == null, "duplicate callback must not count twice");
        check(tracker.record("The door is locked.", 2000L).count() == 2, "second refusal");
        check(!tracker.hasPending(), "two refusals must not yet abandon the target");
        check(tracker.record("The door is locked.", 3000L).count() == 3, "third refusal");
        check(tracker.hasPending(), "third refusal schedules recovery");
        check(tracker.isScopeBlocked("money.beer_glass", 3001L), "nested method quarantined");
        check(!tracker.isScopeBlocked("combat.melee", 3001L), "parent combat remains available for another funding method");
        check(tracker.isTargetBlocked(door, 3001L), "exact failed tile quarantined");
        check(!tracker.isTargetBlocked(new Tile(3157, 3430, 1), 3001L), "same generic door ID elsewhere must remain available");
        check(tracker.takePending() != null && !tracker.hasPending(), "recovery consumed once");
        check(!tracker.isScopeBlocked("money.beer_glass", 30 * 60_000L + 3001L), "requirement quarantine expires");

        AccessFailureTracker separated = new AccessFailureTracker();
        separated.intend("quest.one", door, 11773, "Open", 0L);
        separated.record("You can't reach that!", 1000L);
        separated.intend("quest.one", new Tile(3200, 3200, 0), 11773, "Open", 2000L);
        check(separated.record("You can't reach that!", 2000L).count() == 1, "different tile has independent count");
        separated.intend("quest.one", door, 11773, "Open", 50000L);
        check(separated.record("You can't reach that!", 50000L).count() == 1, "old failures expire");
        check(separated.record("You can't reach that!", 70000L) == null, "old action cannot own a new message");
        for (String unrelated : List.of("You have no ammo left in your quiver.", "That player is already under attack.",
                "You can't light a fire here.", "Your inventory is full.", "You need more coins.", "The door opens."))
            check(!AccessFailureTracker.isAccessRefusal(unrelated), "not an access refusal: " + unrelated);
        for (String refusal : List.of("You need a key to open this door.", "You need to be on a members' world to enter.",
                "You don't have permission to enter.", "You need a level of 32 Cooking to enter.", "You cannot reach that."))
            check(AccessFailureTracker.isAccessRefusal(refusal), "recognize access refusal: " + refusal);

        APIContext context = context();
        APIContext otherAccount = context();
        AccessRecovery.beginScope(context, "money.beer_glass");
        AccessRecovery.destination(context, door);
        String[] warnings = {"The door is locked.", "You can't reach that!", "You cannot enter this area."};
        for (String warning : warnings) AccessRecovery.onChatMessage(context,
                new ChatMessageEvent(ChatMessageEvent.ChatMessageType.PUBLICCHAT.getType(), 1, "player", warning));
        check(!AccessRecovery.mustYield(context), "public chat must never trigger recovery");
        for (String warning : warnings) AccessRecovery.onChatMessage(context,
                new ChatMessageEvent(ChatMessageEvent.ChatMessageType.GAMEMESSAGE.getType(), 1, "", warning));
        check(AccessRecovery.mustYield(context), "game feedback preempts active execution");
        check(AccessRecovery.isScopeBlocked(context, "money.beer_glass"), "method blocked in this context");
        check(!AccessRecovery.isScopeBlocked(otherAccount, "money.beer_glass"), "other account context unaffected");
        boolean cancelled = false;
        try { Navigation.walkToNoTeleports(context, new Tile(3200, 3200, 0)); }
        catch (CancellationException expected) { cancelled = true; }
        check(cancelled, "pending recovery prevents route fallback from issuing another walk");
        int[] executions = {0};
        ManagedF2PModule module = new ManagedF2PModule() {
            public String name() { return "skills.mining_smithing"; }
            public boolean isComplete(APIContext ctx) { return false; }
            public boolean shouldExecute(APIContext ctx) { return true; }
            public void execute(APIContext ctx) { executions[0]++; }
        };
        try { module.executeWithContext(context); }
        catch (CancellationException expected) { }
        check(executions[0] == 0, "pending retreat preempts nested executors too");

        FundingPlanner planner = new FundingPlanner();
        for (int i = 0; i < 250; i++) {
            var decision = planner.choose(1000, 0, List.of(), method -> AccessRecovery.fundingAllowed(context, method));
            check(decision.method() != FundingPlanner.Method.BEER_GLASS, "funding must not reselect refused method");
        }
        check(planner.choose(1000, 0, List.of(), method -> false).method() == FundingPlanner.Method.UNAVAILABLE,
                "empty pool is explicit, never random retry of a blocked method");
        check(planner.choose(1000, 0, List.of(new FundingPlanner.Asset("Logs", 100, 0, 20)),
                method -> method != FundingPlanner.Method.BEER_GLASS).method() == FundingPlanner.Method.SELL_READY_STOCK,
                "eligible existing stock keeps priority");
        AccessRecovery.reset(context);
        check(!AccessRecovery.isScopeBlocked(context, "money.beer_glass"), "new session resets refusal evidence");
        testRetreat(warnings);
        System.out.println("PASS: access refusal threshold, duplicate and player-chat exclusion, target/account isolation, cancellation and alternate funding");
    }

    private static void testRetreat(String[] warnings) {
        Tile safe = new Tile(3310, 3142, 0) {
            @Override public boolean canReach(APIContext ctx) { return true; }
            @Override public boolean isVisible() { return true; }
        };
        Tile[] position = {safe};
        Tile[] clicked = {null};
        ILocalPlayerAPI player = stub(ILocalPlayerAPI.class, (p, method, args) -> switch (method.getName()) {
            case "getLocation" -> position[0];
            case "getHealthPercent" -> 100;
            default -> null;
        });
        IWalkingAPI walking = stub(IWalkingAPI.class, (p, method, args) -> {
            if (method.getName().equals("walkToOnScreen") || method.getName().equals("walkOnMap")) {
                clicked[0] = (Tile) args[0];
                return true;
            }
            return null;
        });
        IInventoryAPI inventory = stub(IInventoryAPI.class, (p, method, args) -> false);
        IMenuAPI menu = stub(IMenuAPI.class, (p, method, args) -> false);
        APIContext ctx = stub(APIContext.class, (p, method, args) -> switch (method.getName()) {
            case "localPlayer" -> player;
            case "walking" -> walking;
            case "inventory" -> inventory;
            case "menu" -> menu;
            default -> null;
        });
        check(!AccessRecovery.tick(ctx, ignored -> { }), "ordinary tick records previously visited tile");
        position[0] = new Tile(3314, 3142, 0);
        AccessRecovery.tick(ctx, ignored -> { });
        AccessRecovery.beginScope(ctx, "money.beer_glass");
        AccessRecovery.destination(ctx, new Tile(3321, 3142, 0));
        for (String warning : warnings) AccessRecovery.onChatMessage(ctx,
                new ChatMessageEvent(ChatMessageEvent.ChatMessageType.GAMEMESSAGE.getType(), 1, "", warning));
        check(AccessRecovery.tick(ctx, ignored -> { }), "recovery takes priority over normal task");
        check(clicked[0] == safe, "retreat uses reachable visited tile on approach side of door");
        position[0] = safe;
        check(AccessRecovery.tick(ctx, ignored -> { }), "arrival finishes retreat without starting task in same tick");
        check(!AccessRecovery.mustYield(ctx), "arrival releases task selection");
        check(AccessRecovery.isScopeBlocked(ctx, "money.beer_glass"), "failed method stays blocked after retreat");
        check(!AccessRecovery.tick(ctx, ignored -> { }), "retreat must not repeat after arrival");
    }

    private static APIContext context() {
        return stub(APIContext.class, (proxy, method, args) -> null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("toString")) return type.getSimpleName();
            return handler.invoke(proxy, method, args);
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
