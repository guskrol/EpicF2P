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
    private static final Tile AL_KHARID_GATE_BYPASS_WEST_TILE = new Tile(3226, 3146, 0);
    private static final Tile AL_KHARID_GATE_BYPASS_SOUTH_TILE = new Tile(3258, 3146, 0);
    private static final Tile AL_KHARID_GATE_BYPASS_EAST_TILE = new Tile(3272, 3162, 0);
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
            return waypoint == null ? null : ctx.webWalking().walkTo(waypoint);
        }

        if (isAlKharidOrDesertSide(location) && isLumbridgeSideOfGate(destinationTile)) {
            Tile waypoint = nextEastToWestBypassWaypoint(location);
            return waypoint == null ? null : ctx.webWalking().walkTo(waypoint);
        }

        return null;
    }

    private static Tile nextWestToEastBypassWaypoint(Tile location) {
        if (location.getX() < 3238 || location.getY() > 3165) {
            return AL_KHARID_GATE_BYPASS_WEST_TILE;
        }
        if (location.getX() < 3263) {
            return AL_KHARID_GATE_BYPASS_SOUTH_TILE;
        }
        if (location.getX() < 3271 || location.getY() < 3160) {
            return AL_KHARID_GATE_BYPASS_EAST_TILE;
        }
        return null;
    }

    private static Tile nextEastToWestBypassWaypoint(Tile location) {
        if (location.getX() > 3274 || location.getY() > 3168) {
            return AL_KHARID_GATE_BYPASS_EAST_TILE;
        }
        if (location.getX() > 3258) {
            return AL_KHARID_GATE_BYPASS_SOUTH_TILE;
        }
        if (location.getX() > 3238 || location.getY() < 3160) {
            return AL_KHARID_GATE_BYPASS_WEST_TILE;
        }
        return null;
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
                && tile.getY() <= 3238;
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
