package org.example.modules.combat;

import com.epicbot.api.shared.model.Tile;
import java.util.concurrent.ThreadLocalRandom;

/** Only a stalled position can trigger recovery or a different combat target. */
final class TargetTravelProgress {
    enum Decision { CONTINUE, RETRY_ROUTE, SWITCH_TARGET }

    private Tile lastTile;
    private long lastMovedAt;
    private long stallAfterMillis;
    private int recoveries;

    Decision observe(Tile tile, long now) {
        if (tile == null) return Decision.CONTINUE;
        if (!sameTile(tile, lastTile)) {
            lastTile = tile;
            lastMovedAt = now;
            stallAfterMillis = ThreadLocalRandom.current().nextLong(25_000L, 35_001L);
            recoveries = 0;
            return Decision.CONTINUE;
        }
        if (now - lastMovedAt < stallAfterMillis) return Decision.CONTINUE;
        lastMovedAt = now;
        stallAfterMillis = ThreadLocalRandom.current().nextLong(25_000L, 35_001L);
        return ++recoveries >= 2 ? Decision.SWITCH_TARGET : Decision.RETRY_ROUTE;
    }

    void reset() {
        lastTile = null;
        lastMovedAt = 0L;
        recoveries = 0;
    }

    private static boolean sameTile(Tile a, Tile b) {
        return a != null && b != null && a.getX() == b.getX()
                && a.getY() == b.getY() && a.getPlane() == b.getPlane();
    }
}
