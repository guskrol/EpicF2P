package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.path.TilePath;
import com.epicbot.api.shared.webwalking.model.WalkState;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public final class Navigation {
    private static final int LUMBRIDGE_MIN_X = 3180;
    private static final int LUMBRIDGE_MAX_X = 3268;
    private static final int LUMBRIDGE_MIN_Y = 3140;
    private static final int LUMBRIDGE_MAX_Y = 3315;
    private static final Area DESERT_P2P_BANK_AVOID_AREA = new Area(3295, 3105, 3336, 3150);
    private static final Area AL_KHARID_F2P_BANK_AREA = new Area(3268, 3161, 3274, 3173);
    private static final double AL_KHARID_BYPASS_PATH_PRECISION = 2.0;
    private static final int AL_KHARID_BYPASS_LOCAL_RANGE = 18;
    private static final Tile[] AL_KHARID_GATE_WEST_TO_EAST_PATH = {
            new Tile(3256, 3264, 0),
            new Tile(3256, 3270, 0),
            new Tile(3258, 3278, 0),
            new Tile(3262, 3284, 0),
            new Tile(3268, 3284, 0),
            new Tile(3274, 3281, 0),
            new Tile(3278, 3276, 0),
            new Tile(3280, 3270, 0),
            new Tile(3282, 3263, 0),
            new Tile(3282, 3256, 0),
            new Tile(3282, 3248, 0),
            new Tile(3282, 3238, 0),
            new Tile(3282, 3230, 0),
            new Tile(3282, 3222, 0),
            new Tile(3281, 3214, 0),
            new Tile(3280, 3206, 0),
            new Tile(3278, 3198, 0),
            new Tile(3276, 3190, 0),
            new Tile(3274, 3182, 0),
            new Tile(3272, 3174, 0),
            new Tile(3271, 3167, 0)
    };
    private static final Tile[] AL_KHARID_GATE_EAST_TO_WEST_PATH = {
            new Tile(3271, 3167, 0),
            new Tile(3272, 3174, 0),
            new Tile(3274, 3182, 0),
            new Tile(3276, 3190, 0),
            new Tile(3278, 3198, 0),
            new Tile(3280, 3206, 0),
            new Tile(3281, 3214, 0),
            new Tile(3282, 3222, 0),
            new Tile(3282, 3230, 0),
            new Tile(3282, 3238, 0),
            new Tile(3282, 3248, 0),
            new Tile(3282, 3256, 0),
            new Tile(3282, 3263, 0),
            new Tile(3280, 3270, 0),
            new Tile(3278, 3276, 0),
            new Tile(3274, 3281, 0),
            new Tile(3268, 3284, 0),
            new Tile(3262, 3284, 0),
            new Tile(3258, 3278, 0),
            new Tile(3256, 3270, 0),
            new Tile(3256, 3264, 0)
    };
    private static final Tile[] AL_KHARID_F2P_BANK_TILES = {
            new Tile(3270, 3167, 0),
            new Tile(3271, 3167, 0),
            new Tile(3272, 3166, 0)
    };

    private Navigation() {
    }

    public static WalkState walkTo(APIContext ctx, Locatable destination) {
        disableTeleports(ctx);
        WalkState bypassState = walkAroundAlKharidGateIfNeeded(ctx, destination);
        if (bypassState != null) {
            return bypassState;
        }
        return ctx.webWalking().walkTo(destination);
    }

    public static WalkState walkToNoTeleports(APIContext ctx, Locatable destination) {
        disableTeleports(ctx);
        WalkState bypassState = walkAroundAlKharidGateIfNeeded(ctx, destination);
        if (bypassState != null) {
            return bypassState;
        }
        return ctx.webWalking().walkTo(destination);
    }

    public static WalkState walkToBank(APIContext ctx) {
        disableTeleports(ctx);
        if (shouldAvoidNearestBank(ctx)) {
            return ctx.webWalking().walkTo(randomAlKharidBankTile());
        }
        return ctx.webWalking().walkToBank();
    }

    public static boolean isBankReachable(APIContext ctx) {
        return !shouldAvoidNearestBank(ctx) && ctx.bank().isReachable();
    }

    public static boolean openBank(APIContext ctx) {
        if (shouldAvoidNearestBank(ctx)) {
            walkToBank(ctx);
            return false;
        }
        return ctx.bank().open();
    }

    public static void configureTeleportsForCurrentRegion(APIContext ctx) {
        disableTeleports(ctx);
    }

    public static boolean shouldAvoidNearestBank(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return false;
        }

        return DESERT_P2P_BANK_AVOID_AREA.contains(location)
                && !AL_KHARID_F2P_BANK_AREA.contains(location);
    }

    private static void disableTeleports(APIContext ctx) {
        if (ctx.webWalking().isUseTeleports()) {
            ctx.webWalking().setUseTeleports(false);
        }
    }

    private static WalkState walkAroundAlKharidGateIfNeeded(APIContext ctx, Locatable destination) {
        Tile location = ctx.localPlayer().getLocation();
        Tile destinationTile = destination == null ? null : destination.getLocation();
        if (location == null || destinationTile == null) {
            return null;
        }

        if (isLumbridgeSideOfGate(location) && isAlKharidOrDesertSide(destinationTile)) {
            return walkBypassRoute(ctx, AL_KHARID_GATE_WEST_TO_EAST_PATH);
        }

        if (isAlKharidOrDesertSide(location) && isLumbridgeSideOfGate(destinationTile)) {
            return walkBypassRoute(ctx, AL_KHARID_GATE_EAST_TO_WEST_PATH);
        }

        return null;
    }

    private static WalkState walkBypassRoute(APIContext ctx, Tile[] path) {
        Tile location = ctx.localPlayer().getLocation();
        Tile[] remainingPath = remainingPathFromCurrentLocation(ctx, path);
        if (remainingPath.length == 0) {
            return WalkState.SUCCESS;
        }

        Tile nextPathTile = remainingPath[0];
        if (location == null || squaredDistance(location, nextPathTile) > square(AL_KHARID_BYPASS_LOCAL_RANGE)) {
            return ctx.webWalking().walkTo(nextPathTile);
        }

        WalkState localState = walkBypassPath(ctx, remainingPath);
        if (localState == WalkState.FAILED && squaredDistance(location, nextPathTile) > square(4)) {
            return ctx.webWalking().walkTo(nextPathTile);
        }
        return localState;
    }

    private static WalkState walkBypassPath(APIContext ctx, Tile[] remainingPath) {
        TilePath tilePath = new TilePath(AL_KHARID_BYPASS_PATH_PRECISION, remainingPath);
        if (ctx.walking().walkPath(tilePath)) {
            return WalkState.SUCCESS;
        }

        Tile next = tilePath.getNext(ctx);
        if (next != null && ctx.walking().walkTo(next, AL_KHARID_BYPASS_PATH_PRECISION)) {
            return WalkState.SUCCESS;
        }

        return WalkState.FAILED;
    }

    private static Tile[] remainingPathFromCurrentLocation(APIContext ctx, Tile[] path) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || path == null || path.length == 0) {
            return new Tile[0];
        }

        int closestIndex = 0;
        int closestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < path.length; i++) {
            Tile tile = path[i];
            if (tile == null || tile.getPlane() != location.getPlane()) {
                continue;
            }

            int distance = squaredDistance(location, tile);
            if (distance < closestDistance) {
                closestIndex = i;
                closestDistance = distance;
            }
        }

        int startIndex = closestIndex;
        if (Math.sqrt(closestDistance) <= AL_KHARID_BYPASS_PATH_PRECISION && closestIndex < path.length - 1) {
            startIndex = closestIndex + 1;
        }

        return Arrays.copyOfRange(path, startIndex, path.length);
    }

    private static int squaredDistance(Tile a, Tile b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        return dx * dx + dy * dy;
    }

    private static int square(int value) {
        return value * value;
    }

    private static boolean isLumbridgeSideOfGate(Tile tile) {
        return tile.getPlane() == 0
                && tile.getX() <= 3267
                && tile.getY() >= 3130
                && tile.getY() <= 3505;
    }

    private static boolean isAlKharidOrDesertSide(Tile tile) {
        return tile.getPlane() == 0
                && tile.getX() >= 3268
                && tile.getX() <= 3338
                && tile.getY() >= 3105
                && tile.getY() <= 3305;
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

    private static Tile randomAlKharidBankTile() {
        return AL_KHARID_F2P_BANK_TILES[
                ThreadLocalRandom.current().nextInt(AL_KHARID_F2P_BANK_TILES.length)
        ];
    }
}
