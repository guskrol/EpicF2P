package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WalkState;

import java.util.concurrent.ThreadLocalRandom;

public final class Navigation {
    private static final int LUMBRIDGE_MIN_X = 3180;
    private static final int LUMBRIDGE_MAX_X = 3268;
    private static final int LUMBRIDGE_MIN_Y = 3140;
    private static final int LUMBRIDGE_MAX_Y = 3315;
    private static final Area DESERT_P2P_BANK_AVOID_AREA = new Area(3295, 3105, 3336, 3150);
    private static final Area AL_KHARID_F2P_BANK_AREA = new Area(3268, 3161, 3274, 3173);
    private static final Tile AL_KHARID_GATE_NORTH_WEST_TILE = new Tile(3256, 3268, 0);
    private static final Tile AL_KHARID_GATE_NORTH_GAP_WEST_TILE = new Tile(3262, 3283, 0);
    private static final Tile AL_KHARID_GATE_NORTH_GAP_EAST_TILE = new Tile(3277, 3278, 0);
    private static final Tile AL_KHARID_GATE_NORTH_EAST_MID_TILE = new Tile(3282, 3260, 0);
    private static final Tile AL_KHARID_GATE_NORTH_EAST_START_TILE = new Tile(3282, 3238, 0);
    private static final int AL_KHARID_BYPASS_REACHED_DISTANCE = 5;
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
            Tile waypoint = nextWestToEastBypassWaypoint(location);
            return waypoint == null ? null : walkBypassWaypoint(ctx, waypoint);
        }

        if (isAlKharidOrDesertSide(location) && isLumbridgeSideOfGate(destinationTile)) {
            Tile waypoint = nextEastToWestBypassWaypoint(location);
            return waypoint == null ? null : walkBypassWaypoint(ctx, waypoint);
        }

        return null;
    }

    private static Tile nextWestToEastBypassWaypoint(Tile location) {
        if (near(location, AL_KHARID_GATE_NORTH_WEST_TILE)) {
            return AL_KHARID_GATE_NORTH_GAP_WEST_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_GAP_WEST_TILE)) {
            return AL_KHARID_GATE_NORTH_GAP_EAST_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_GAP_EAST_TILE)) {
            return AL_KHARID_GATE_NORTH_EAST_MID_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_EAST_MID_TILE)) {
            return AL_KHARID_GATE_NORTH_EAST_START_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_EAST_START_TILE)) {
            return null;
        }

        if (location.getY() < 3264 || location.getX() < 3248) {
            return AL_KHARID_GATE_NORTH_WEST_TILE;
        }
        if (location.getX() < 3265 || location.getY() < 3278) {
            return AL_KHARID_GATE_NORTH_GAP_WEST_TILE;
        }
        if (location.getX() < 3275 || location.getY() > 3266) {
            return AL_KHARID_GATE_NORTH_GAP_EAST_TILE;
        }
        if (location.getY() > 3242 || location.getX() < 3280) {
            return AL_KHARID_GATE_NORTH_EAST_MID_TILE;
        }
        return null;
    }

    private static Tile nextEastToWestBypassWaypoint(Tile location) {
        if (near(location, AL_KHARID_GATE_NORTH_EAST_START_TILE)) {
            return AL_KHARID_GATE_NORTH_EAST_MID_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_EAST_MID_TILE)) {
            return AL_KHARID_GATE_NORTH_GAP_EAST_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_GAP_EAST_TILE)) {
            return AL_KHARID_GATE_NORTH_GAP_WEST_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_GAP_WEST_TILE)) {
            return AL_KHARID_GATE_NORTH_WEST_TILE;
        }
        if (near(location, AL_KHARID_GATE_NORTH_WEST_TILE)) {
            return null;
        }

        if (location.getY() < 3236 || location.getX() > 3286) {
            return AL_KHARID_GATE_NORTH_EAST_START_TILE;
        }
        if (location.getY() < 3268 || location.getX() > 3282) {
            return AL_KHARID_GATE_NORTH_EAST_MID_TILE;
        }
        if (location.getX() > 3266 || location.getY() < 3280) {
            return AL_KHARID_GATE_NORTH_GAP_EAST_TILE;
        }
        if (location.getX() > 3248 || location.getY() > 3264) {
            return AL_KHARID_GATE_NORTH_WEST_TILE;
        }
        return null;
    }

    private static WalkState walkBypassWaypoint(APIContext ctx, Tile waypoint) {
        if (ctx.walking().walkOnScreen(waypoint)
                || waypoint.interact("Walk here")
                || waypoint.click(true)
                || ctx.walking().walkOnMap(waypoint)
                || ctx.walking().walkTo(waypoint)) {
            return WalkState.SUCCESS;
        }

        return ctx.webWalking().walkTo(waypoint);
    }

    private static boolean near(Tile location, Tile waypoint) {
        return location != null
                && waypoint != null
                && location.getPlane() == waypoint.getPlane()
                && Math.abs(location.getX() - waypoint.getX()) <= AL_KHARID_BYPASS_REACHED_DISTANCE
                && Math.abs(location.getY() - waypoint.getY()) <= AL_KHARID_BYPASS_REACHED_DISTANCE;
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
