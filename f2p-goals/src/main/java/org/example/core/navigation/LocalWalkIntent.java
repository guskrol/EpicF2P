package org.example.core.navigation;

import com.epicbot.api.shared.model.Tile;

/** Keeps one destination and permits another click only after observed lack of progress. */
final class LocalWalkIntent {
    enum Action { WAIT, ARRIVED, SCREEN, MINIMAP, WEB }

    final Tile destination;
    private final boolean screenFirst;
    private final long stallMillis;
    private Tile lastPosition;
    private long lastProgressAt;
    private long nextActionAt;
    private int attempts;
    private boolean lastAccepted;

    LocalWalkIntent(Tile destination, Tile position, long now, boolean screenFirst, long stallMillis) {
        this.destination = destination;
        this.lastPosition = position;
        this.lastProgressAt = now;
        this.screenFirst = screenFirst;
        this.stallMillis = stallMillis;
    }

    Action next(Tile position, boolean moving, long now) {
        if (!sameTile(position, lastPosition)) {
            lastPosition = position;
            lastProgressAt = now;
        }
        if (sameTile(position, destination)) return Action.ARRIVED;
        if (now < nextActionAt || (moving && now - lastProgressAt < stallMillis)
                || (lastAccepted && now - lastProgressAt < stallMillis)) return Action.WAIT;
        if (attempts >= 2) return Action.WEB;
        boolean useScreen = attempts == 0 ? screenFirst : !screenFirst;
        return useScreen ? Action.SCREEN : Action.MINIMAP;
    }

    void attempted(long now, boolean accepted) {
        attempts++;
        lastAccepted = accepted;
        lastProgressAt = now;
        nextActionAt = now + (accepted ? 650L : 300L);
    }

    static boolean sameTile(Tile first, Tile second) {
        return first != null && second != null && first.getX() == second.getX()
                && first.getY() == second.getY() && first.getPlane() == second.getPlane();
    }
}
