package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WalkState;

public final class Navigation {
    private static final int LUMBRIDGE_MIN_X = 3180;
    private static final int LUMBRIDGE_MAX_X = 3268;
    private static final int LUMBRIDGE_MIN_Y = 3140;
    private static final int LUMBRIDGE_MAX_Y = 3315;

    private Navigation() {
    }

    public static WalkState walkTo(APIContext ctx, Locatable destination) {
        disableTeleports(ctx);
        return ctx.webWalking().walkTo(destination);
    }

    public static WalkState walkToNoTeleports(APIContext ctx, Locatable destination) {
        disableTeleports(ctx);
        return ctx.webWalking().walkTo(destination);
    }

    public static WalkState walkToBank(APIContext ctx) {
        disableTeleports(ctx);
        return ctx.webWalking().walkToBank();
    }

    public static void configureTeleportsForCurrentRegion(APIContext ctx) {
        disableTeleports(ctx);
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
