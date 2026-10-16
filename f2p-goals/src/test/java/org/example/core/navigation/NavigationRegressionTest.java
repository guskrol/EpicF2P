package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.IWebWalkingAPI;
import com.epicbot.api.shared.methods.ILocalPlayerAPI;
import com.epicbot.api.shared.methods.IWalkingAPI;
import com.epicbot.api.shared.methods.ICalculationsAPI;
import com.epicbot.api.shared.methods.ICameraAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IObjectsAPI;
import com.epicbot.api.shared.methods.IGameAPI;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.webwalking.model.RSBank;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WalkCondition;
import com.epicbot.api.shared.webwalking.model.WalkState;
import com.epicbot.api.shared.webwalking.model.WebPath;
import com.epicbot.api.shared.webwalking.model.PathStatus;
import com.epicbot.api.shared.webwalking.model.Point3D;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;
import java.util.Arrays;
import java.awt.Point;
import java.awt.Rectangle;

public final class NavigationRegressionTest {
    public static void main(String[] args) {
        Tile start = new Tile(3200, 3200, 0);
        Tile goal = new Tile(3208, 3200, 0);
        LocalWalkIntent intent = new LocalWalkIntent(goal, start, 0L, true, 3500L);
        require(intent.next(start, false, 0L) == LocalWalkIntent.Action.SCREEN, "initial screen approach");
        intent.attempted(0L, true);
        require(intent.next(start, false, 500L) == LocalWalkIntent.Action.WAIT, "wait for movement to start");
        Tile halfway = new Tile(3204, 3200, 0);
        require(intent.next(halfway, true, 2000L) == LocalWalkIntent.Action.WAIT, "do not click during progress");
        require(intent.next(halfway, false, 4000L) == LocalWalkIntent.Action.WAIT, "allow a short scene pause");
        require(intent.next(halfway, false, 5600L) == LocalWalkIntent.Action.MINIMAP, "switch method only after a stall");
        require(LocalWalkIntent.sameTile(intent.destination, goal), "recovery must preserve the destination");
        intent.attempted(5600L, true);
        require(intent.next(halfway, false, 9200L) == LocalWalkIntent.Action.WEB, "bound local retries");
        require(intent.next(goal, false, 9300L) == LocalWalkIntent.Action.ARRIVED, "arrival ends movement");

        LocalWalkIntent unavailable = new LocalWalkIntent(goal, start, 0L, false, 3500L);
        require(unavailable.next(start, false, 0L) == LocalWalkIntent.Action.MINIMAP, "support a minimap approach");
        unavailable.attempted(0L, false);
        require(unavailable.next(start, false, 100L) == LocalWalkIntent.Action.WAIT, "no chained click after rejection");
        require(unavailable.next(start, false, 500L) == LocalWalkIntent.Action.SCREEN, "fallback on a later decision");

        int[] calls = {0};
        boolean[] cancelInFlight = {true};
        APIContext[] context = new APIContext[1];
        IWebWalkingAPI web = stub(IWebWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("isUseTeleports")) return false;
            if (m.getName().equals("walkTo")) {
                calls[0]++;
                WalkCondition stop = (WalkCondition) a[1];
                require(!stop.getAsBoolean(), "new route starts active");
                if (cancelInFlight[0]) {
                    Navigation.cancel(context[0]);
                    require(stop.getAsBoolean(), "stop must cancel an already running walker");
                }
                return WalkState.SUCCESS;
            }
            return null;
        });
        context[0] = stub(APIContext.class, (p, m, a) -> m.getName().equals("webWalking") ? web : null);
        Navigation.reset(context[0]);
        Area area = new Area(3205, 3195, 3215, 3205);
        Tile selected = Navigation.destinationInArea(context[0], area, goal);
        for (int i = 0; i < 50; i++) {
            require(Navigation.destinationInArea(context[0], area, new Tile(3210, 3201, 0)) == selected,
                    "repeated area requests must not reroll the tile");
        }
        try {
            Navigation.walkTo(context[0], goal);
            throw new AssertionError("cancelled route returned as an active movement");
        } catch (CancellationException expected) { }
        try {
            Navigation.walkTo(context[0], goal);
            throw new AssertionError("stopped session issued another route");
        } catch (CancellationException expected) { }
        require(calls[0] == 1, "cancelled navigation must not issue a second command");
        Navigation.reset(context[0]);
        cancelInFlight[0] = false;
        require(Navigation.walkTo(context[0], goal) == WalkState.SUCCESS, "a resumed session can navigate");
        testScreenApproach();
        testLongRoute();
        testLongRouteClicks();
        testTrapdoorTransition();
        testPublicBanks();
        System.out.println("PASS: stable destination, bounded recovery, route cancellation, long-distance screen/map clicks, camera and transitions");
    }

    private static void testPublicBanks() {
        Tile[] position = {new Tile(3152, 3425, 0)};
        int[] calls = {0, 0}; // unsafe bank open, public route
        Tile[] destination = {null};
        ILocalPlayerAPI player = stub(ILocalPlayerAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getLocation" -> position[0];
            case "getPlane" -> position[0].getPlane();
            case "isMoving" -> false;
            default -> null;
        });
        IBankAPI bank = stub(IBankAPI.class, (p, m, a) -> switch (m.getName()) {
            case "isReachable", "isVisible" -> true; // Guild booth reported accessible by API.
            case "open" -> { calls[0]++; yield true; }
            default -> null;
        });
        IWebWalkingAPI web = stub(IWebWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("isUseTeleports")) return false;
            if (m.getName().equals("getPathToNearestBank") || m.getName().equals("walkToBank"))
                throw new AssertionError("unrestricted nearest-bank routing must not be used");
            if (m.getName().equals("getPath")) {
                destination[0] = ((Locatable) a[0]).getLocation();
                return null; // Exercise walker fallback as well as route preview.
            }
            if (m.getName().equals("walkTo")) {
                calls[1]++;
                Tile target = ((Locatable) a[0]).getLocation();
                require(target.equals(destination[0]), "fallback must retain the approved bank endpoint");
                WalkCondition stop = (WalkCondition) a[1];
                require(!stop.getAsBoolean(), "visible guild bank must not end the public-bank route");
                position[0] = target;
                return WalkState.SUCCESS;
            }
            return null;
        });
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "localPlayer" -> player;
            case "bank" -> bank;
            case "webWalking" -> web;
            default -> null;
        });
        Navigation.reset(ctx);
        require(Navigation.shouldAvoidNearestBank(ctx), "guild approach must reject raw API bank detection");
        require(!Navigation.isBankReachable(ctx), "restricted booth must not become an interaction target");
        require(!Navigation.openBank(ctx), "opening a restricted booth must redirect instead");
        require(calls[0] == 0 && calls[1] == 1, "do not click the restricted booth; walk to a public bank");
        require(Navigation.isPublicBankLocation(destination[0]), "route endpoint must be an approved public bank");
        require(Navigation.isBankReachable(ctx) && Navigation.openBank(ctx), "ordinary public bank must still open");
        require(calls[0] == 1, "public bank interaction should use existing API");
        require(!Navigation.isPublicBankLocation(new Tile(3145, 3449, 0)), "guild location excluded");
        position[0] = new Tile(3310, 3120, 0);
        Navigation.reset(ctx);
        Navigation.walkToBank(ctx);
        require(destination[0].equals(RSBank.AL_KHARID.getTile()), "preserve the desert public-bank override");
        Tile upstairs = RSBank.LUMBRIDGE_TOP.getTile();
        require(Navigation.isPublicBankLocation(upstairs), "Lumbridge upstairs bank remains available");
        require(!Navigation.isPublicBankLocation(new Tile(upstairs.getX(), upstairs.getY(), 0)),
                "bank on another floor must not authorize a ground-floor interaction");
        MixedRoute route = new MixedRoute(List.of(destination[0]), destination[0], true);
        require(!route.matches(RSBank.GRAND_EXCHANGE.getTile(), true), "bank route cache must track the selected bank");

        Tile ge = RSBank.GRAND_EXCHANGE.getTile();
        RSBank desertExit = Navigation.selectPublicBank(new Tile(3270, 3260, 0), ge,
                RSBank.AL_KHARID);
        require(desertExit != RSBank.AL_KHARID,
                "a new banking need after leaving Al Kharid must not blindly reverse the trip");
        require(Navigation.selectPublicBank(new Tile(3270, 3172, 0), ge,
                RSBank.AL_KHARID) == RSBank.AL_KHARID,
                "a bank just departed may still handle an immediate banking need");
        require(Navigation.selectPublicBank(new Tile(3030, 3410, 0), ge,
                RSBank.FALADOR_EAST) != RSBank.FALADOR_EAST,
                "the same forward-bank rule must work after leaving Falador");
        Tile westernGoal = new Tile(2920, 3370, 0);
        require(Navigation.selectPublicBank(new Tile(2980, 3370, 0), westernGoal,
                RSBank.FALADOR_EAST) == RSBank.FALADOR_WEST,
                "forward bank selection must follow a non-GE destination too");
        require(Navigation.selectPublicBank(new Tile(2980, 3370, 0), null,
                RSBank.FALADOR_EAST) != null,
                "without an active trip the nearest public bank remains available");
    }

    private static void testLongRoute() {
        List<Tile> tiles = new ArrayList<>();
        for (int x = 3200; x <= 3500; x++) tiles.add(new VisibleTile(x, 3200));
        MixedRoute route = new MixedRoute(tiles, tiles.get(tiles.size() - 1), false);
        Random random = new Random(17L);
        Tile position = tiles.get(0);
        int previousIndex = -1;
        boolean seenScreen = false, seenMap = false;
        for (int i = 0; i < 12; i++) {
            MixedRoute.Step step = route.next(position, i * 1000L, tile -> true, random);
            require(step != null && step.pathIndex() > previousIndex, "route traversal must always advance");
            require(tiles.get(step.pathIndex()).equals(step.target()), "waypoint must belong to the API route");
            LocalWalkIntent.Action action = step.intent().next(position, false, i * 1000L);
            seenScreen |= action == LocalWalkIntent.Action.SCREEN;
            seenMap |= action == LocalWalkIntent.Action.MINIMAP;
            route.accepted(action == LocalWalkIntent.Action.SCREEN);
            previousIndex = step.pathIndex();
            position = step.target();
        }
        require(seenScreen && seenMap, "long route must vary methods well before reaching its destination");
        MixedRoute passedWaypoint = new MixedRoute(tiles, tiles.get(300), false);
        MixedRoute.Step first = passedWaypoint.next(tiles.get(0), 0L, tile -> true, random);
        Tile beyond = tiles.get(first.pathIndex() + 4);
        MixedRoute.Step advanced = passedWaypoint.next(beyond, 1000L, tile -> true, random);
        require(advanced != null && advanced.pathIndex() > first.pathIndex(),
                "walker progress past a waypoint must not send the player backward");
        List<Tile> invisible = new ArrayList<>();
        for (int x = 3200; x <= 3250; x++) invisible.add(new InvisibleTile(x, 3200));
        MixedRoute offscreen = new MixedRoute(invisible, invisible.get(invisible.size() - 1), false);
        for (int i = 0; i < 8; i++) {
            MixedRoute.Step step = offscreen.next(invisible.get(0), i * 1000L, tile -> true, random);
            require(step != null && step.intent().next(invisible.get(0), false, i * 1000L)
                    == LocalWalkIntent.Action.MINIMAP,
                    "off-screen points must skip camera adjustment and choose minimap immediately");
            offscreen.delegateTransition();
        }
        MixedRoute transition = new MixedRoute(List.of(new Tile(3200, 3200, 0),
                new Tile(3200, 9600, 0)), new Tile(3200, 9600, 0), false);
        require(transition.next(new Tile(3200, 3200, 0), 0L, tile -> true, random) == null,
                "scene transitions must be delegated to the existing walker");
        require(transition.nearbyTransition(new Tile(3200, 3200, 0)) != null,
                "route must expose a nearby entrance for a scene transition");
        require(transition.nearbyTransition(new Tile(3210, 3200, 0)) == null,
                "distant entrances must remain normal route travel");
        require(transition.nearbyTransition(new Tile(3200, 9600, 0)) == null,
                "a completed descent must not reopen the surface entrance");
        MixedRoute blocked = new MixedRoute(tiles, tiles.get(300), false);
        require(blocked.next(tiles.get(0), 0L, tile -> false, random) == null,
                "unreachable path segments must not receive screen or map clicks");
    }

    private static void testTrapdoorTransition() {
        Tile entrance = new Tile(3096, 3468, 0);
        Tile underground = new Tile(3096, 9867, 0);
        Tile goal = new Tile(3098, 9908, 0);
        Tile[] position = {entrance};
        boolean[] open = {false};
        int[] calls = {0, 0, 0}; // open, descend, route preview
        int[] view = {10, 35, 0}; // yaw, pitch, keyboard yaw requests
        ILocalPlayerAPI player = stub(ILocalPlayerAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getLocation" -> position[0];
            case "getPlane" -> position[0].getPlane();
            case "isMoving" -> false;
            default -> null;
        });
        SceneObject trapdoor = stub(SceneObject.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "Trapdoor";
            case "getLocation" -> entrance;
            case "getId" -> open[0] ? 2 : 1;
            case "isValid", "isVisible" -> true;
            case "getCentralPoint" -> !open[0] || view[0] == 145
                    ? new Point(200, 200) : new Point(-20, -20);
            case "hasAction" -> Arrays.asList((String[]) a[0]).contains(open[0] ? "Climb-down" : "Open");
            case "interact" -> {
                String action = (String) a[0];
                if (action.equals("Open")) { calls[0]++; open[0] = true; }
                else if (action.equals("Climb-down")) {
                    require(view[0] == 145 && view[1] >= 74 && view[2] >= 2,
                            "Climb-down must wait for the targeted camera adjustment to complete");
                    calls[1]++; position[0] = underground;
                }
                else throw new AssertionError("Unexpected trapdoor action " + action);
                yield true;
            }
            default -> null;
        });
        SceneObject unrelated = stub(SceneObject.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "Trapdoor";
            case "getLocation" -> new Tile(3091, 3468, 0);
            case "isValid", "isVisible", "hasAction" -> true;
            case "interact" -> throw new AssertionError("An unrelated trapdoor must never be opened");
            default -> null;
        });
        IObjectsAPI objects = stub(IObjectsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getAll")) {
                @SuppressWarnings("unchecked") Predicate<SceneObject> filter = (Predicate<SceneObject>) a[1];
                return List.of(unrelated, trapdoor).stream().filter(filter).toList();
            }
            return null;
        });
        WebPath path = new WebPath();
        path.setPathStatus(PathStatus.SUCCESS);
        path.setPath(List.of(new Point3D(3096, 3468, 0), new Point3D(3096, 9867, 0),
                new Point3D(3096, 9885, 0), new Point3D(3098, 9908, 0)));
        IWebWalkingAPI web = stub(IWebWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("isUseTeleports")) return false;
            if (m.getName().equals("getPath")) { calls[2]++; return path; }
            throw new AssertionError("Known trapdoor should be handled before generic walking: " + m.getName());
        });
        ICalculationsAPI calculations = stub(ICalculationsAPI.class, (p, m, a) ->
                m.getName().equals("tileDistanceTo")
                        ? MixedRoute.distance(position[0], ((Locatable) a[0]).getLocation()) : null);
        ICameraAPI camera = stub(ICameraAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getAngleToDeg" -> 145;
            case "getYawDeg" -> view[0];
            case "getPitch" -> view[1];
            case "setYawDeg" -> {
                view[2]++;
                if (view[2] > 1) view[0] = (int) a[0];
                yield null;
            }
            case "setPitch" -> { view[1] = (int) a[0]; yield null; }
            default -> null;
        });
        IGameAPI game = stub(IGameAPI.class, (p, m, a) ->
                m.getName().equals("getViewport") ? new Rectangle(0, 0, 600, 450) : null);
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "localPlayer" -> player;
            case "objects" -> objects;
            case "webWalking" -> web;
            case "calculations" -> calculations;
            case "camera" -> camera;
            case "game" -> game;
            default -> null;
        });
        Navigation.reset(ctx);
        Navigation.walkToNoTeleports(ctx, goal);
        Navigation.walkToNoTeleports(ctx, goal);
        require(calls[0] == 1 && calls[1] == 0,
                "opening a trapdoor must not issue a second action during its cooldown");
        try { Thread.sleep(1850L); } catch (InterruptedException error) { throw new AssertionError(error); }
        Navigation.walkToNoTeleports(ctx, goal);
        require(calls[1] == 0 && view[2] == 1,
                "an unfinished camera adjustment must retain the trapdoor action without clicking");
        Navigation.walkToNoTeleports(ctx, goal);
        require(calls[0] == 1 && calls[1] == 1 && position[0].equals(underground),
                "an open trapdoor must receive Climb-down and confirm the underground transition");
        require(calls[2] == 1, "opening and descending must retain the same API route and target");
        position[0] = entrance;
        view[0] = 10;
        view[1] = 35;
        Navigation.retryRoute(ctx);
        Navigation.walkToNoTeleports(ctx, goal);
        require(calls[0] == 1 && calls[1] == 2 && view[2] == 3,
                "returning to an already-open entrance must frame the trapdoor again before descending");
    }

    private static void testLongRouteClicks() {
        Tile[] position = {new Tile(3200, 3200, 0)};
        int[] calls = {0, 0, 0, 0}; // screen, minimap, route lookup, camera
        ILocalPlayerAPI player = stub(ILocalPlayerAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getLocation" -> position[0];
            case "getPlane" -> 0;
            case "isMoving" -> false;
            default -> null;
        });
        IWalkingAPI walking = stub(IWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("walkOnScreen") || m.getName().equals("walkOnMap")) {
                calls[m.getName().equals("walkOnScreen") ? 0 : 1]++;
                position[0] = ((Locatable) a[0]).getLocation();
                return true;
            }
            throw new AssertionError("Unexpected movement API: " + m.getName());
        });
        ICalculationsAPI calculations = stub(ICalculationsAPI.class, (p, m, a) ->
                m.getName().equals("tileDistanceTo")
                        ? MixedRoute.distance(position[0], ((Locatable) a[0]).getLocation()) : null);
        WebPath path = new WebPath();
        path.setPathStatus(PathStatus.SUCCESS);
        List<Point3D> points = new ArrayList<>();
        for (int x = 3200; x <= 3500; x++) {
            final int pathX = x;
            points.add(new Point3D(x, 3200, 0) {
                @Override public Tile toPosition() {
                    return pathX <= 3220 ? new InvisibleTile(pathX, 3200)
                            : new VisibleTile(pathX, 3200);
                }
            });
        }
        path.setPath(points);
        IWebWalkingAPI web = stub(IWebWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("getPath")) { calls[2]++; return path; }
            if (m.getName().equals("isUseTeleports")) return false;
            throw new AssertionError("Ordinary long-route segments should use the cached route: " + m.getName());
        });
        int[] cameraAngles = {0, 40};
        ICameraAPI camera = stub(ICameraAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getAngleToDeg" -> 90;
            case "getYawDeg" -> cameraAngles[0];
            case "getPitch" -> cameraAngles[1];
            case "setYawDeg" -> { calls[3]++; cameraAngles[0] = (int) a[0]; yield null; }
            case "setPitch" -> {
                require((int) a[0] >= 60 && (int) a[0] <= 78, "walking camera pitch is expressed in degrees");
                cameraAngles[1] = (int) a[0];
                yield null;
            }
            default -> null;
        });
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "localPlayer" -> player;
            case "walking" -> walking;
            case "calculations" -> calculations;
            case "webWalking" -> web;
            case "camera" -> camera;
            default -> null;
        });
        Navigation.reset(ctx);
        Tile goal = new VisibleTile(3500, 3200);
        for (int i = 0; i < 8; i++) Navigation.walkTo(ctx, goal);
        require(calls[0] > 0 && calls[1] > 0, "global long travel must issue both real screen and map API calls");
        require(calls[2] == 1, "the same destination must reuse its API route");
        require(calls[3] > 0, "route segments must orient the camera even when initially using the minimap");
        require(MixedRoute.distance(position[0], goal) > 100, "variation must occur far from the final approach");
    }

    private static void testScreenApproach() {
        Tile[] position = {new Tile(3200, 3200, 0)};
        boolean[] moving = {false};
        int[] issued = {0, 0, 0}; // screen, map, web
        ILocalPlayerAPI player = stub(ILocalPlayerAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getLocation" -> position[0];
            case "getX" -> position[0].getX();
            case "getY" -> position[0].getY();
            case "getPlane" -> 0;
            case "isMoving" -> moving[0];
            default -> null;
        });
        IWalkingAPI walking = stub(IWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("walkToOnScreen")) { issued[0]++; return true; }
            if (m.getName().equals("walkOnMap")) { issued[1]++; return true; }
            return null;
        });
        ICalculationsAPI calculations = stub(ICalculationsAPI.class, (p, m, a) -> {
            if (m.getName().equals("tileDistanceTo")) {
                Locatable target = (Locatable) a[0];
                return Math.max(Math.abs(target.getX() - position[0].getX()),
                        Math.abs(target.getY() - position[0].getY()));
            }
            return null;
        });
        IWebWalkingAPI web = stub(IWebWalkingAPI.class, (p, m, a) -> {
            if (m.getName().equals("isUseTeleports")) return false;
            if (m.getName().equals("walkTo")) {
                issued[2]++;
                WalkCondition stop = (WalkCondition) a[1];
                require(!stop.getAsBoolean(), "distant target starts with the route walker");
                position[0] = new Tile(3230, 3200, 0);
                moving[0] = true;
                // A fresh callback after the scene has changed, outside its polling window.
                try { Thread.sleep(550L); } catch (InterruptedException e) { throw new AssertionError(e); }
                require(stop.getAsBoolean(), "walker must yield when its target becomes visible nearby");
                return WalkState.SUCCESS;
            }
            return null;
        });
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "localPlayer" -> player;
            case "walking" -> walking;
            case "calculations" -> calculations;
            case "webWalking" -> web;
            default -> null;
        });
        Navigation.reset(ctx);
        Navigation.walkTo(ctx, new VisibleTile(3205, 3200));
        require(issued[0] == 1 && issued[1] == 0 && issued[2] == 0,
                "shared walkTo must use the screen for a nearby visible target");
        Navigation.reset(ctx);
        issued[0] = 0;
        Navigation.walkTo(ctx, new VisibleTile(3240, 3200));
        require(issued[0] == 1 && issued[1] == 0 && issued[2] == 1,
                "long trip must hand off to a screen approach while preserving direction");
    }

    private static final class VisibleTile extends Tile {
        VisibleTile(int x, int y) { super(x, y, 0); }
        @Override public boolean isVisible() { return true; }
        @Override public boolean canReach(APIContext ctx) { return true; }
    }

    private static final class InvisibleTile extends Tile {
        InvisibleTile(int x, int y) { super(x, y, 0); }
        @Override public boolean isVisible() { return false; }
        @Override public boolean canReach(APIContext ctx) { return true; }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("toString")) return type.getSimpleName();
            return handler.invoke(p, m, a);
        });
    }
}
