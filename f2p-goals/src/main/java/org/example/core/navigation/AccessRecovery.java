package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.model.Tile;
import org.example.core.funding.FundingPlanner;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Shared by tasks, routes and nested funding executors; no game variables are written. */
public final class AccessRecovery {
    private static final Map<APIContext, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private AccessRecovery() { }

    private static final class State {
        final Object owner;
        final String account;
        final AccessFailureTracker tracker = new AccessFailureTracker();
        final ArrayDeque<Tile> visited = new ArrayDeque<>();
        String scope = "initialization";
        Tile position, destination, explicitTarget;
        int objectId = -1;
        String action = "task";
        long explicitAt;
        AccessFailureTracker.Denial recovery;
        Tile retreat, recoveryOrigin;
        long recoveryStartedAt, nextRetreatAt;
        int retreatAttempts;
        Consumer<String> logger = ignored -> { };

        State(Object owner, String account) { this.owner = owner; this.account = account; }
    }

    private static State state(APIContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("Missing API context");
        Object owner = ctx.script() == null ? null : ctx.script().getScript();
        String account = ctx.client() == null ? "" : String.valueOf(ctx.client().getUsername());
        synchronized (STATES) {
            State state = STATES.get(ctx);
            if (state == null || state.owner != owner || !state.account.equals(account)) {
                state = new State(owner, account);
                STATES.put(ctx, state);
            }
            return state;
        }
    }

    public static void reset(APIContext ctx) {
        if (ctx != null) STATES.remove(ctx);
    }

    public static void beginScope(APIContext ctx, String scope) {
        scope = scopeKey(scope);
        State state = state(ctx);
        synchronized (state) {
            if (!scope.equals(state.scope)) {
                state.scope = scope;
                state.destination = null;
                state.explicitTarget = null;
                state.objectId = -1;
                state.action = "task";
            }
            refreshIntent(state);
        }
    }

    public static boolean isScopeBlocked(APIContext ctx, String scope) {
        return ctx != null && state(ctx).tracker.isScopeBlocked(scopeKey(scope), System.currentTimeMillis());
    }

    private static String scopeKey(String scope) {
        return "combat.lumbridge_cows".equals(scope) ? "combat.melee" : scope;
    }

    public static void destination(APIContext ctx, Tile target) {
        State state = state(ctx);
        synchronized (state) {
            requireAllowed(state, target);
            state.destination = target;
            refreshIntent(state);
        }
    }

    /** Discard an obsolete action when a quest stage or game plane changes. */
    public static void clearIntent(APIContext ctx) {
        if (ctx == null) return;
        State state = state(ctx);
        synchronized (state) {
            state.destination = null;
            state.explicitTarget = null;
            state.objectId = -1;
            state.action = "task";
            state.tracker.clearIntent();
        }
    }

    public static void interaction(APIContext ctx, SceneObject object, String action) {
        if (object == null) return;
        State state = state(ctx);
        synchronized (state) {
            requireAllowed(state, object.getLocation());
            state.explicitTarget = object.getLocation();
            state.objectId = object.getId();
            state.action = action;
            state.explicitAt = System.currentTimeMillis();
            refreshIntent(state);
        }
    }

    private static void refreshIntent(State state) {
        long now = System.currentTimeMillis();
        boolean explicit = state.explicitTarget != null && now - state.explicitAt <= 15000L;
        Tile target = explicit ? state.explicitTarget
                : state.destination != null ? state.destination : state.position;
        state.tracker.intend(state.scope, target, explicit ? state.objectId : -1,
                explicit ? state.action : "route/task", now);
    }

    private static void requireAllowed(State state, Tile target) {
        long now = System.currentTimeMillis();
        if (state.tracker.hasPending() || state.recovery != null
                || state.tracker.isScopeBlocked(state.scope, now))
            throw new CancellationException("Access refused; selecting another target");
        if (state.tracker.isTargetBlocked(target, now)) {
            state.tracker.intend(state.scope, target, -1, "previously refused target", now);
            state.tracker.rejectCurrent("Target already blocked by earlier access refusals", now);
            state.logger.accept("[Access] Excluding " + state.scope + "; attempted to reuse blocked target " + target);
            throw new CancellationException("Target already blocked by access refusals");
        }
    }

    public static boolean mustYield(APIContext ctx) {
        if (ctx == null) return false;
        State state = state(ctx);
        synchronized (state) { return state.tracker.hasPending() || state.recovery != null; }
    }

    /** Called from the chat event thread. This only records evidence, never clicks or walks. */
    public static void onChatMessage(APIContext ctx, ChatMessageEvent event) {
        if (ctx == null || event == null || !isGameFeedback(event.getType())) return;
        State state = state(ctx);
        synchronized (state) {
            AccessFailureTracker.Denial denial = state.tracker.record(event.getMessage(), System.currentTimeMillis());
            if (denial != null) state.logger.accept("[Access] Refusal " + denial.count() + "/"
                    + AccessFailureTracker.REFUSAL_LIMIT + " scope=" + denial.intent().scope()
                    + " target=" + denial.intent().target() + " object=" + denial.intent().objectId()
                    + " action=" + denial.intent().action() + " message='" + denial.message() + "'");
        }
    }

    private static boolean isGameFeedback(ChatMessageEvent.ChatMessageType type) {
        return type == ChatMessageEvent.ChatMessageType.GAMEMESSAGE
                || type == ChatMessageEvent.ChatMessageType.ENGINE
                || type == ChatMessageEvent.ChatMessageType.SPAM
                || type == ChatMessageEvent.ChatMessageType.DIALOG
                || type == ChatMessageEvent.ChatMessageType.MESBOX;
    }

    /** Runs before ordinary tasks, after the existing break/login gates. Recovery is bounded. */
    public static boolean tick(APIContext ctx, Consumer<String> logger) {
        State state = state(ctx);
        synchronized (state) {
            state.logger = logger;
            if (ctx.localPlayer() == null || ctx.localPlayer().getLocation() == null) return false;
            Tile here = ctx.localPlayer().getLocation();
            state.position = here;
            if (state.recovery == null) {
                state.recovery = state.tracker.takePending();
                if (state.recovery == null) {
                    if (state.visited.isEmpty() || distance(state.visited.peekLast(), here) >= 2) {
                        state.visited.addLast(here);
                        while (state.visited.size() > 24) state.visited.removeFirst();
                    }
                    return false;
                }
                Navigation.abandonTrip(ctx);
                state.recoveryOrigin = here;
                state.recoveryStartedAt = System.currentTimeMillis();
                state.retreatAttempts = 0;
                state.nextRetreatAt = 0L;
                state.retreat = chooseRetreat(ctx, state, here);
                logger.accept("[Access] Three refusals; blocked " + state.recovery.intent().scope()
                        + " and target " + state.recovery.intent().target()
                        + "; retreat=" + state.retreat + "; next executor must select an available alternative");
                if (ctx.menu().isOpen()) ctx.menu().closeMenu();
                if (ctx.inventory().isItemSelected()) ctx.inventory().deselectItem();
            }
            long now = System.currentTimeMillis();
            if (ctx.localPlayer().getHealthPercent() <= 0 || state.retreat == null
                    || distance(here, state.recoveryOrigin) >= 3 || now - state.recoveryStartedAt >= 6000L) {
                logger.accept("[Access] Retreat finished; releasing task selection, failed target remains blocked");
                state.recovery = null;
                state.retreat = null;
                return true;
            }
            if (now >= state.nextRetreatAt && state.retreatAttempts < 2) {
                state.retreatAttempts++;
                state.nextRetreatAt = now + 2000L;
                if (state.retreat.canReach(ctx)) {
                    boolean accepted = state.retreat.isVisible()
                            ? ctx.walking().walkToOnScreen(state.retreat) : ctx.walking().walkOnMap(state.retreat);
                    logger.accept("[Access] Retreat attempt " + state.retreatAttempts + "/2 -> "
                            + state.retreat + " accepted=" + accepted);
                }
            }
            return true;
        }
    }

    private static Tile chooseRetreat(APIContext ctx, State state, Tile here) {
        Tile denied = state.recovery.intent().target();
        var previous = state.visited.descendingIterator();
        while (previous.hasNext()) {
            Tile candidate = previous.next();
            if (safeRetreat(ctx, here, denied, candidate)) return candidate;
        }
        for (int radius = 4; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx += radius) {
                for (int dy = -radius; dy <= radius; dy += radius) {
                    Tile candidate = new Tile(here.getX() + dx, here.getY() + dy, here.getPlane());
                    if (safeRetreat(ctx, here, denied, candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static boolean safeRetreat(APIContext ctx, Tile here, Tile denied, Tile candidate) {
        int distance = distance(here, candidate);
        return distance >= 3 && distance <= 12
                && (denied == null || distance(candidate, denied) > distance(here, denied))
                && candidate.canReach(ctx);
    }

    private static int distance(Tile first, Tile second) {
        return first == null || second == null || first.getPlane() != second.getPlane()
                ? Integer.MAX_VALUE : Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getY() - second.getY()));
    }

    public static boolean fundingAllowed(APIContext ctx, FundingPlanner.Method method) {
        if (method == FundingPlanner.Method.UNAVAILABLE) return false;
        String scope = switch (method) {
            case BEER_GLASS -> "money.beer_glass";
            case WOODCUTTING -> "skills.woodcutting_firemaking";
            case MINING_SMITHING -> "skills.mining_smithing";
            case FISHING_COOKING -> "skills.fishing_cooking";
            default -> "funding.stock_sale";
        };
        return !isScopeBlocked(ctx, scope);
    }

    public static void noFundingAlternative(APIContext ctx) {
        State state = state(ctx);
        synchronized (state) {
            state.tracker.blockScope(state.scope, System.currentTimeMillis() + 5 * 60_000L);
            state.logger.accept("[Access] No available funding method for " + state.scope + "; selecting another goal");
        }
        throw new CancellationException("No available funding method");
    }

    public static void rejectCurrentAction(APIContext ctx, String reason) {
        State state = state(ctx);
        synchronized (state) {
            state.tracker.rejectCurrent(reason, System.currentTimeMillis());
            state.logger.accept("[Access] Action made no progress after bounded retries: " + reason);
        }
        throw new CancellationException(reason);
    }
}
