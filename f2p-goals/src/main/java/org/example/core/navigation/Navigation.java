package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WalkState;

import java.util.concurrent.ThreadLocalRandom;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.webwalking.model.WalkCondition;
import com.epicbot.api.shared.webwalking.model.WebPath;
import com.epicbot.api.shared.webwalking.model.RSBank;
import com.epicbot.api.shared.util.time.Time;
import java.util.Comparator;
import java.util.List;
import java.awt.Point;
import java.awt.Rectangle;

public final class Navigation {
    private static final int LOCAL_WALK_MAX_TILES = 20;
    private static final Map<APIContext, MovementState> MOVEMENT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int LUMBRIDGE_MIN_X = 3180;
    private static final int LUMBRIDGE_MAX_X = 3268;
    private static final int LUMBRIDGE_MIN_Y = 3140;
    private static final int LUMBRIDGE_MAX_Y = 3315;
    private static final Area DESERT_P2P_BANK_AVOID_AREA = new Area(3295, 3105, 3336, 3150);
    // Public banks only: guild/quest access must never be inferred from visibility.
    private static final RSBank[] PUBLIC_F2P_BANKS = {
            RSBank.FALADOR_WEST, RSBank.FALADOR_EAST, RSBank.EDGEVILLE,
            RSBank.GRAND_EXCHANGE, RSBank.VARROCK_WEST, RSBank.VARROCK_EAST,
            RSBank.AL_KHARID, RSBank.LUMBRIDGE_TOP, RSBank.DRAYNOR
    };
    private static final int BANK_APPROACH_RADIUS = 16;

    private Navigation() {
    }

    public static WalkState walkTo(APIContext ctx, Locatable destination) {
        return walkWeb(ctx, destination, false);
    }

    public static WalkState walkToNoTeleports(APIContext ctx, Locatable destination) {
        return walkWeb(ctx, destination, false);
    }

    public static boolean walkLocally(APIContext ctx, Locatable destination) {
        rememberOutboundTrip(ctx, destination);
        return walkLocally(ctx, destination, false);
    }

    private static void rememberOutboundTrip(APIContext ctx, Locatable destination) {
        if (ctx == null || destination == null || destination.getLocation() == null) return;
        MovementState state = state(ctx);
        synchronized (state) {
            Tile here = ctx.localPlayer() == null ? null : ctx.localPlayer().getLocation();
            if (here != null && state.departureBank == null && isPublicBankLocation(here)) {
                state.departureBank = state.bankTarget != null
                        ? state.bankTarget : nearestPublicBank(here);
            }
            state.outboundDestination = destination.getLocation();
            state.bankTarget = null;
        }
    }

    private static boolean walkLocally(APIContext ctx, Locatable destination, boolean handoff) {
        if (ctx == null || destination == null || ctx.localPlayer() == null
                || destination.getLocation() == null) return false;
        MovementState state = state(ctx);
        ensureActive(ctx, state);
        AccessRecovery.destination(ctx, destination.getLocation());
        synchronized (state) {
            if (state.webWalking) return true;
            Tile here = ctx.localPlayer().getLocation();
            if (here == null) return false;
            long now = System.currentTimeMillis();
            if (state.local != null) {
                LocalWalkIntent.Action pending = state.local.next(here, ctx.localPlayer().isMoving(), now);
                if (pending == LocalWalkIntent.Action.WAIT) return true;
                if (pending == LocalWalkIntent.Action.ARRIVED) state.local = null;
            }
            if (destination.getPlane() != here.getPlane()
                    || destination.tileDistanceTo(ctx) > LOCAL_WALK_MAX_TILES) return false;
            if (state.local == null || !LocalWalkIntent.sameTile(state.local.destination, destination.getLocation())) {
                if (LocalWalkIntent.sameTile(state.localFallback, destination.getLocation())
                        && now < state.localFallbackUntil) return false;
                if (!destination.canReach(ctx)) return false;
                int distance = destination.tileDistanceTo(ctx);
                boolean visible = destination.getLocation().isVisible();
                if (distance > 14 && !visible) return false;
                boolean screenFirst = visible && (handoff || distance <= 8
                        || ThreadLocalRandom.current().nextInt(100) < 80);
                state.local = new LocalWalkIntent(destination.getLocation(), here, now,
                        screenFirst,
                        ThreadLocalRandom.current().nextLong(1800L, 2801L));
            }
            LocalWalkIntent.Action action = state.local.next(here, !handoff && ctx.localPlayer().isMoving(), now);
            if (action == LocalWalkIntent.Action.WAIT || action == LocalWalkIntent.Action.ARRIVED) return true;
            if (action == LocalWalkIntent.Action.WEB) {
                state.localFallback = state.local.destination;
                state.localFallbackUntil = now + 10_000L;
                state.local = null;
                log(ctx, "No local movement progress; using the route walker");
                return false;
            }
            // One movement command per decision. A failed click is retried on a later tick.
            Tile target = state.local.destination;
            boolean accepted = action == LocalWalkIntent.Action.SCREEN
                    ? ctx.walking().walkToOnScreen(target) : ctx.walking().walkOnMap(target);
            state.local.attempted(System.currentTimeMillis(), accepted);
            logProgress(ctx, state, target, accepted, action + " -> " + target.getX() + ","
                    + target.getY() + "," + target.getPlane() + (accepted ? "" : " (click unavailable)"));
            return true;
        }
    }

    public static WalkState walkToBank(APIContext ctx) {
        MovementState state = state(ctx);
        RSBank target;
        synchronized (state) {
            if (state.bankTarget == null) {
                Tile here = ctx.localPlayer() == null ? null : ctx.localPlayer().getLocation();
                state.bankTarget = selectPublicBank(here, state.outboundDestination,
                        state.departureBank);
                log(ctx, "Public F2P bank selected: " + state.bankTarget.name()
                        + (state.departureBank == null ? "" : " after leaving "
                            + state.departureBank.name()));
                state.outboundDestination = null;
                state.departureBank = null;
            }
            target = state.bankTarget;
        }
        return walkWeb(ctx, target.getTile(), true);
    }

    /** A fresh banking need during a trip prefers a bank ahead of the active destination. */
    static RSBank selectPublicBank(Tile here, Tile destination, RSBank departureBank) {
        RSBank nearest = nearestPublicBank(here);
        if (here == null || destination == null || departureBank == null
                || here.getPlane() != destination.getPlane()
                || bankDistance(here, departureBank.getTile()) <= BANK_APPROACH_RADIUS + 8) {
            return nearest;
        }
        long remaining = bankDistance(here, destination);
        RSBank ahead = null;
        long bestCost = Long.MAX_VALUE;
        for (RSBank candidate : PUBLIC_F2P_BANKS) {
            if (candidate == departureBank) continue;
            long goalDistance = bankDistance(candidate.getTile(), destination);
            // The bank must advance the current trip by a meaningful margin.
            if (goalDistance + 12 >= remaining) continue;
            long cost = bankDistance(here, candidate.getTile()) + goalDistance / 3;
            if (cost < bestCost) {
                bestCost = cost;
                ahead = candidate;
            }
        }
        return ahead == null ? nearest : ahead;
    }

    private static long bankDistance(Tile first, Tile second) {
        return Math.max(Math.abs(first.getX() - second.getX()),
                Math.abs(first.getY() - second.getY()))
                + 12L * Math.abs(first.getPlane() - second.getPlane());
    }

    private static RSBank nearestPublicBank(Tile here) {
        if (here != null && DESERT_P2P_BANK_AVOID_AREA.contains(here)) return RSBank.AL_KHARID;
        RSBank selected = RSBank.GRAND_EXCHANGE;
        long best = Long.MAX_VALUE;
        for (RSBank bank : PUBLIC_F2P_BANKS) {
            Tile tile = bank.getTile();
            long distance = here == null ? Long.MAX_VALUE : bankDistance(here, tile);
            if (distance < best) { best = distance; selected = bank; }
        }
        return selected;
    }

    public static boolean isPublicBankLocation(Tile location) {
        if (location == null) return false;
        for (RSBank bank : PUBLIC_F2P_BANKS) {
            if (MixedRoute.distance(location, bank.getTile()) <= BANK_APPROACH_RADIUS) return true;
        }
        return false;
    }

    /** A random approach tile is chosen once and retained until that area is reached. */
    public static Tile destinationInArea(APIContext ctx, Area area, Tile... candidates) {
        MovementState state = state(ctx);
        synchronized (state) {
            if (state.area != area || state.areaDestination == null) {
                state.area = area;
                state.areaDestination = candidates.length == 0 ? area.getRandomTile()
                        : candidates[ThreadLocalRandom.current().nextInt(candidates.length)];
            }
            return state.areaDestination;
        }
    }

    public static void observe(APIContext ctx) {
        MovementState state = state(ctx);
        synchronized (state) {
            if (ctx.localPlayer() != null && ctx.bank() != null && ctx.bank().isOpen()
                    && isPublicBankLocation(ctx.localPlayer().getLocation())) {
                state.bankTarget = nearestPublicBank(ctx.localPlayer().getLocation());
                state.departureBank = null;
                state.outboundDestination = null;
            }
            if (state.area != null && ctx.localPlayer() != null
                    && state.area.contains(ctx.localPlayer().getLocation())) {
                state.area = null;
                state.areaDestination = null;
                state.outboundDestination = null;
                state.departureBank = null;
            }
        }
    }

    public static void reset(APIContext ctx) {
        cancel(ctx);
        CameraMovement.reset(ctx);
        AccessRecovery.reset(ctx);
        if (ctx != null) MOVEMENT.put(ctx, new MovementState(owner(ctx)));
    }

    /** Drop the entire failed trip so a different executor cannot inherit its target. */
    public static void abandonTrip(APIContext ctx) {
        CameraMovement.reset(ctx);
        MovementState state = state(ctx);
        synchronized (state) {
            state.local = null;
            state.mixed = null;
            state.area = null;
            state.areaDestination = null;
            state.localFallback = null;
            state.localFallbackUntil = 0L;
            state.nextRouteLookupAt = 0L;
            state.mixedFallbackUntil = 0L;
            state.bankTarget = null;
            state.departureBank = null;
            state.outboundDestination = null;
        }
    }

    /** Rebuild a stalled route without losing the selected destination or target area. */
    public static void retryRoute(APIContext ctx) {
        MovementState state = state(ctx);
        synchronized (state) {
            state.local = null;
            state.mixed = null;
            state.nextRouteLookupAt = 0L;
            state.mixedFallbackUntil = 0L;
        }
    }

    public static void cancel(APIContext ctx) {
        if (ctx == null) return;
        CameraMovement.cancel(ctx);
        MovementState previous = MOVEMENT.get(ctx);
        if (previous != null) previous.cancelled = true;
    }

    private static MovementState state(APIContext ctx) {
        synchronized (MOVEMENT) {
            Script owner = owner(ctx);
            MovementState current = MOVEMENT.get(ctx);
            if (current == null || current.owner != owner) {
                if (current != null) current.cancelled = true;
                current = new MovementState(owner);
                MOVEMENT.put(ctx, current);
            }
            return current;
        }
    }

    private static Script owner(APIContext ctx) {
        return ctx.script() == null ? null : ctx.script().getScript();
    }

    private static void ensureActive(APIContext ctx, MovementState state) {
        if (state.cancelled || Thread.currentThread().isInterrupted() || AccessRecovery.mustYield(ctx))
            throw new CancellationException();
        Script owner = ctx.script() == null ? null : ctx.script().getScript();
        if (owner != null && (!owner.isRunning() || owner.isPaused() || owner.isStopping())) {
            throw new CancellationException();
        }
    }

    private static WalkState walkWeb(APIContext ctx, Locatable destination, boolean bank) {
        MovementState state = state(ctx);
        ensureActive(ctx, state);
        if (destination != null) AccessRecovery.destination(ctx, destination.getLocation());
        if (!bank) rememberOutboundTrip(ctx, destination);
        if (!bank && destination != null && walkLocally(ctx, destination)) return WalkState.SUCCESS;
        if (bank && ctx.bank() != null && isBankReachable(ctx) && ctx.bank().isVisible()) {
            log(ctx, "Bank already visible and reachable; use the bank interaction");
            return WalkState.SUCCESS;
        }
        Script owner = ctx.script() == null ? null : ctx.script().getScript();
        synchronized (state) {
            if (state.webWalking) return WalkState.SUCCESS;
            state.local = null;
            state.webWalking = true;
        }
        WalkCondition cancelled = () -> state.cancelled || Thread.currentThread().isInterrupted()
                || AccessRecovery.mustYield(ctx)
                || (owner != null && (!owner.isRunning() || owner.isPaused() || owner.isStopping()
                    || ctx.script().getScript() != owner));
        AtomicBoolean handoff = new AtomicBoolean();
        AtomicBoolean routeYield = new AtomicBoolean();
        Tile routeOrigin = ctx.localPlayer() == null ? null : ctx.localPlayer().getLocation();
        long[] nextApproachCheck = {0L};
        WalkCondition stop = () -> {
            if (cancelled.getAsBoolean()) return true;
            if (handoff.get() || routeYield.get()) return true;
            long now = System.currentTimeMillis();
            if (now < nextApproachCheck[0]) return handoff.get();
            nextApproachCheck[0] = now + 500L;
            if (state.mixed != null && routeOrigin != null && ctx.localPlayer() != null) {
                Tile here = ctx.localPlayer().getLocation();
                if (here != null && (here.getPlane() != routeOrigin.getPlane()
                        || MixedRoute.distance(here, routeOrigin) >= 8)) {
                    routeYield.set(true);
                    return true;
                }
            }
            if (bank) {
                handoff.set(ctx.bank() != null && isBankReachable(ctx) && ctx.bank().isVisible());
            } else if (destination != null && ctx.localPlayer() != null
                    && destination.getPlane() == ctx.localPlayer().getPlane()
                    && destination.tileDistanceTo(ctx) <= LOCAL_WALK_MAX_TILES
                    && !(LocalWalkIntent.sameTile(state.localFallback, destination.getLocation())
                        && now < state.localFallbackUntil)
                    && destination.getLocation().isVisible() && destination.canReach(ctx)) {
                handoff.set(true);
            }
            return handoff.get();
        };
        WalkState result;
        try {
            disableTeleports(ctx);
            if (followMixedRoute(ctx, state, destination, bank)) return WalkState.SUCCESS;
            result = ctx.webWalking().walkTo(destination, stop);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (!handoff.get() && !routeYield.get()
                    && (result == WalkState.FAILED || result == WalkState.END_BLOCKED || result == WalkState.ERROR)) {
                synchronized (state) {
                    state.area = null;
                    state.areaDestination = null;
                    state.mixed = null;
                }
            }
        } finally {
            state.webWalking = false;
        }
        if (routeYield.get()) {
            log(ctx, "Walker completed the route transition; resuming mixed route clicks");
            return WalkState.SUCCESS;
        }
        if (handoff.get()) {
            log(ctx, bank ? "Walker yielded to visible bank interaction"
                    : "Walker yielded to visible on-screen approach");
            if (!bank) walkLocally(ctx, destination, true);
            return WalkState.SUCCESS;
        }
        return result;
    }

    private static boolean followMixedRoute(APIContext ctx, MovementState state, Locatable destination, boolean bank) {
        if (ctx.localPlayer() == null || ctx.localPlayer().getLocation() == null) return false;
        long now = System.currentTimeMillis();
        Tile goal = destination == null ? null : destination.getLocation();
        if (state.mixed != null && !state.mixed.matches(goal, bank)) {
            state.mixed = null;
            state.nextRouteLookupAt = 0L;
            state.mixedFallbackUntil = 0L;
        }
        if (state.mixed == null) {
            if (now < state.nextRouteLookupAt) return false;
            state.nextRouteLookupAt = now + 10_000L;
            WebPath path;
            try {
                path = ctx.webWalking().getPath(destination);
            } catch (RuntimeException failure) {
                if (failure instanceof CancellationException) throw failure;
                log(ctx, "Route preview unavailable; using the existing walker");
                return false;
            }
            ensureActive(ctx, state);
            state.mixed = MixedRoute.from(path, goal, bank);
            if (state.mixed == null) return false;
            log(ctx, "API route cached: " + state.mixed.tiles.size() + " points; mixed screen/minimap traversal");
        }
        MixedRoute.Transition transition = state.mixed.nearbyTransition(ctx.localPlayer().getLocation());
        if (transition != null && handleRouteTrapdoor(ctx, state, transition)) return true;
        if (now < state.mixedFallbackUntil) return false;
        MixedRoute.Step step = state.mixed.next(ctx.localPlayer().getLocation(), now,
                tile -> tile.canReach(ctx), ThreadLocalRandom.current());
        if (step == null) {
            state.mixed.delegateTransition();
            return false;
        }
        LocalWalkIntent.Action action = step.intent().next(ctx.localPlayer().getLocation(),
                ctx.localPlayer().isMoving(), now);
        if (action == LocalWalkIntent.Action.WAIT || action == LocalWalkIntent.Action.ARRIVED) return true;
        if (action == LocalWalkIntent.Action.WEB) {
            state.mixed.delegateTransition();
            state.mixedFallbackUntil = now + 5000L;
            log(ctx, "Route segment stalled; delegating the obstruction to the existing walker");
            return false;
        }
        boolean screen = action == LocalWalkIntent.Action.SCREEN;
        if (ctx.camera() != null) {
            int yaw = ctx.camera().getAngleToDeg(step.target());
            int currentYaw = ctx.camera().getYawDeg();
            int yawError = Math.abs(CameraMovement.yawDelta(yaw, currentYaw));
            boolean sharpTurn = yawError >= 70 && now - state.lastWalkingCameraAt >= 4_000L;
            if (yawError >= 35 && (now >= state.nextWalkingCameraAt || sharpTurn)) {
                int pitch = Math.max(60, Math.min(78, ctx.camera().getPitch()));
                CameraMovement.turn(ctx, yaw, pitch, message -> log(ctx, message));
                state.lastWalkingCameraAt = System.currentTimeMillis();
                state.nextWalkingCameraAt = state.lastWalkingCameraAt
                        + ThreadLocalRandom.current().nextLong(9_000L, 15_001L);
            }
        }
        ensureActive(ctx, state);
        boolean accepted = screen ? step.target().isVisible() && ctx.walking().walkOnScreen(step.target())
                : ctx.walking().walkOnMap(step.target());
        if (screen && !accepted) {
            accepted = ctx.walking().walkOnMap(step.target());
            if (accepted) screen = false;
        }
        step.intent().attempted(System.currentTimeMillis(), accepted);
        if (accepted) state.mixed.accepted(screen);
        logProgress(ctx, state, state.mixed.goal, accepted,
                "ROUTE " + (screen ? "SCREEN" : "MINIMAP") + " point " + step.pathIndex() + "/" + state.mixed.tiles.size()
                        + " -> " + step.target() + (accepted ? "" : " (click unavailable)"));
        return true;
    }

    private static boolean handleRouteTrapdoor(APIContext ctx, MovementState state,
                                               MixedRoute.Transition transition) {
        int undergroundOffset = transition.to().getY() - transition.from().getY();
        boolean descending = transition.to().getPlane() < transition.from().getPlane()
                || (undergroundOffset >= 6000 && undergroundOffset <= 6800
                && Math.abs(transition.to().getX() - transition.from().getX()) <= 32);
        if (!descending || ctx.objects() == null) return false;
        long now = System.currentTimeMillis();
        if (!LocalWalkIntent.sameTile(state.trapdoorSource, transition.from())) {
            state.trapdoorSource = transition.from();
            state.trapdoorAction = null;
            state.trapdoorAttempts = 0;
            state.nextTrapdoorAt = 0L;
            state.trapdoorDelegateUntil = 0L;
        }
        if (now < state.trapdoorDelegateUntil) return false;
        List<SceneObject> entrances = ctx.objects().getAll(12, object ->
                object != null && object.isValid() && "Trapdoor".equalsIgnoreCase(object.getName())
                        && MixedRoute.distance(object.getLocation(), transition.from()) <= 3
                        && object.hasAction("Open", "Climb-down"));
        if (entrances == null) return false;
        SceneObject trapdoor = entrances.stream().min(Comparator.comparingInt(object ->
                MixedRoute.distance(object.getLocation(), transition.from()))).orElse(null);
        if (trapdoor == null) return false;
        if (ctx.localPlayer().isMoving() || now < state.nextTrapdoorAt) return true;
        String action = trapdoor.hasAction("Climb-down") ? "Climb-down" : "Open";
        if (!action.equals(state.trapdoorAction)) {
            state.trapdoorAction = action;
            state.trapdoorAttempts = 0;
            state.trapdoorCameraConfigured = false;
            state.trapdoorCameraReady = false;
            state.trapdoorViewRecoveries = 0;
        }
        if (state.trapdoorAttempts >= 3) {
            log(ctx, "Trapdoor did not progress after three " + action + " attempts; yielding to route recovery");
            state.trapdoorAttempts = 0;
            state.trapdoorDelegateUntil = now + 15_000L;
            return false;
        }
        // Opening replaces the model. Even when isVisible() is still true, its
        // new clickable point may be behind scenery or outside the viewport.
        boolean needsCamera = action.equals("Climb-down") || state.trapdoorAttempts > 0
                || !trapdoor.isVisible();
        if (needsCamera && !state.trapdoorCameraReady && ctx.camera() != null) {
            if (!state.trapdoorCameraConfigured) {
                state.trapdoorCameraYaw = ctx.camera().getAngleToDeg(trapdoor);
                state.trapdoorCameraPitch = ThreadLocalRandom.current().nextInt(74, 81);
                state.trapdoorCameraConfigured = true;
                log(ctx, "Framing route trapdoor before " + action);
            }
            if (!CameraMovement.turn(ctx, state.trapdoorCameraYaw, state.trapdoorCameraPitch,
                    message -> log(ctx, message))) return true;
            state.trapdoorCameraReady = true;
        }
        if (!trapdoorClickVisible(ctx, trapdoor)) {
            if (ctx.camera() == null || state.trapdoorViewRecoveries >= 2) {
                log(ctx, "Trapdoor remains outside the clickable view; yielding to route recovery");
                state.trapdoorDelegateUntil = System.currentTimeMillis() + 15_000L;
                state.trapdoorViewRecoveries = 0;
                return false;
            }
            state.trapdoorViewRecoveries++;
            ViewRecovery.recover(ctx, trapdoor, "route trapdoor", message -> log(ctx, message));
            state.trapdoorCameraConfigured = false;
            state.trapdoorCameraReady = false;
            state.nextTrapdoorAt = System.currentTimeMillis() + 900L;
            return true;
        }
        ensureActive(ctx, state);
        state.trapdoorAttempts++;
        state.nextTrapdoorAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1200L, 1801L);
        AccessRecovery.interaction(ctx, trapdoor, action);
        log(ctx, "Route trapdoor: " + action + " at " + trapdoor.getLocation());
        Tile before = ctx.localPlayer().getLocation();
        boolean clicked = trapdoor.interact(action);
        if (!clicked && ctx.menu() != null) clicked = ctx.menu().interact(action, trapdoor, true);
        if (clicked) {
            Time.sleep(1000, 1800, () -> MixedRoute.distance(before, ctx.localPlayer().getLocation()) > 32
                    || (action.equals("Open") && (!trapdoor.isValid() || !trapdoor.hasAction("Open"))), 100);
            ensureActive(ctx, state);
            if (MixedRoute.distance(before, ctx.localPlayer().getLocation()) > 32) {
                state.mixed.delegateTransition();
                state.local = null;
                state.trapdoorAttempts = 0;
                state.trapdoorSource = null;
                state.trapdoorAction = null;
                state.trapdoorCameraConfigured = false;
                state.trapdoorCameraReady = false;
                AccessRecovery.clearIntent(ctx);
                log(ctx, "Trapdoor transition confirmed; continuing the same route underground");
                return true;
            }
        }
        // A failed input or unchanged scene requires a fresh view before retry.
        state.trapdoorCameraConfigured = false;
        state.trapdoorCameraReady = false;
        return true;
    }

    private static boolean trapdoorClickVisible(APIContext ctx, SceneObject trapdoor) {
        if (!trapdoor.isVisible()) return false;
        if (ctx.game() == null || ctx.game().getViewport() == null) return true;
        Rectangle viewport = ctx.game().getViewport();
        Point point = trapdoor.getCentralPoint();
        return point != null && viewport.contains(point);
    }

    private static void logProgress(APIContext ctx, MovementState state, Tile destination,
                                    boolean accepted, String message) {
        long now = System.currentTimeMillis();
        if (accepted && LocalWalkIntent.sameTile(state.lastLoggedDestination, destination)
                && now < state.nextProgressLogAt) return;
        state.lastLoggedDestination = destination;
        state.nextProgressLogAt = now + 20000L;
        log(ctx, message);
    }

    private static void log(APIContext ctx, String message) {
        if (ctx.script() != null && ctx.script().logger() != null) {
            ctx.script().logger().info("[Navigation] " + message);
        }
    }

    private static final class MovementState {
        final Script owner;
        volatile boolean cancelled;
        volatile boolean webWalking;
        LocalWalkIntent local;
        Tile localFallback;
        long localFallbackUntil;
        MixedRoute mixed;
        long nextRouteLookupAt;
        long mixedFallbackUntil;
        long nextWalkingCameraAt;
        long lastWalkingCameraAt;
        long nextWalkingCameraRecoveryAt;
        Tile trapdoorSource;
        String trapdoorAction;
        int trapdoorAttempts;
        long nextTrapdoorAt;
        long trapdoorDelegateUntil;
        boolean trapdoorCameraConfigured;
        boolean trapdoorCameraReady;
        int trapdoorCameraYaw;
        int trapdoorCameraPitch;
        int trapdoorViewRecoveries;
        Area area;
        Tile areaDestination;
        RSBank bankTarget;
        RSBank departureBank;
        Tile outboundDestination;
        Tile lastLoggedDestination;
        long nextProgressLogAt;

        MovementState(Script owner) {
            this.owner = owner;
        }
    }

    public static boolean isBankReachable(APIContext ctx) {
        return !shouldAvoidNearestBank(ctx) && ctx.bank().isReachable();
    }

    public static boolean openBank(APIContext ctx) {
        if (shouldAvoidNearestBank(ctx)) {
            walkToBank(ctx);
            return false;
        }
        boolean opened = ctx.bank().open();
        if (opened) {
            MovementState state = state(ctx);
            synchronized (state) {
                state.bankTarget = nearestPublicBank(ctx.localPlayer().getLocation());
                state.departureBank = null;
                state.outboundDestination = null;
            }
        }
        return opened;
    }

    public static void configureTeleportsForCurrentRegion(APIContext ctx) {
        disableTeleports(ctx);
    }

    public static boolean shouldAvoidNearestBank(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return !isPublicBankLocation(location);
    }

    private static void disableTeleports(APIContext ctx) {
        if (ctx.webWalking().isUseTeleports()) {
            ctx.webWalking().setUseTeleports(false);
        }
    }

    public static boolean isInLumbridgeRegion(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return false;
        }

        return location.getX() >= LUMBRIDGE_MIN_X
                && location.getX() <= LUMBRIDGE_MAX_X
                && location.getY() >= LUMBRIDGE_MIN_Y
                && location.getY() <= LUMBRIDGE_MAX_Y;
    }

}
