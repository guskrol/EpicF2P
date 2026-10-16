package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class ViewRecovery {
    private static final boolean ZOOM_OUT_SCROLL_DIRECTION = false;
    private static final int MIN_ZOOM_OUT_SCROLLS = 6;
    private static final int MAX_ZOOM_OUT_SCROLLS = 11;
    private static final int MIN_PITCH = 60;
    private static final int MAX_PITCH = 80;

    private ViewRecovery() {
    }

    public static void faceWalkingDirection(APIContext ctx, Locatable focus, Consumer<String> logger) {
        if (ctx == null || focus == null || ctx.camera() == null) return;
        CameraMovement.focus(ctx, focus, logger);
    }

    public static void recover(APIContext ctx, String targetLabel, Consumer<String> logger) {
        recover(ctx, null, targetLabel, logger);
    }

    public static void recover(APIContext ctx, Locatable focus, String targetLabel, Consumer<String> logger) {
        if (ctx == null) {
            return;
        }

        log(logger, "Adjusting camera/zoom to search for " + safeLabel(targetLabel));
        clearInteractionState(ctx);
        if (!turnCamera(ctx, focus, logger)) return;
        moveMouseToViewport(ctx);
        zoomOut(ctx);
        Time.sleep(700, 1200);
    }

    private static void clearInteractionState(APIContext ctx) {
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
                Time.sleep(150, 300);
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
                Time.sleep(150, 300);
            }
        } catch (RuntimeException ignored) {
            // Camera recovery should never break the owning module.
        }
    }

    private static boolean turnCamera(APIContext ctx, Locatable focus, Consumer<String> logger) {
        if (focus != null) return CameraMovement.focus(ctx, focus, logger);
        int yaw = (ctx.camera().getYawDeg() + ThreadLocalRandom.current().nextInt(75, 181)) % 360;
        int pitch = ThreadLocalRandom.current().nextInt(MIN_PITCH, MAX_PITCH + 1);
        return CameraMovement.turn(ctx, yaw, pitch, logger);
    }

    private static void moveMouseToViewport(APIContext ctx) {
        try {
            Rectangle viewport = ctx.game().getViewport();
            if (viewport != null && viewport.width > 0 && viewport.height > 0) {
                int x = viewport.x + Math.max(80, viewport.width / 3);
                int y = viewport.y + Math.max(80, viewport.height / 3);
                ctx.mouse().move(new Point(x, y));
                return;
            }

            int x = Math.max(120, ctx.client().getCanvasWidth() / 2);
            int y = Math.max(120, ctx.client().getCanvasHeight() / 2);
            ctx.mouse().move(new Point(x, y));
        } catch (RuntimeException ignored) {
            // Scrolling can still work when the mouse move fails.
        }
    }

    private static void zoomOut(APIContext ctx) {
        try {
            int scrolls = ThreadLocalRandom.current().nextInt(MIN_ZOOM_OUT_SCROLLS, MAX_ZOOM_OUT_SCROLLS + 1);
            ctx.mouse().scroll(ZOOM_OUT_SCROLL_DIRECTION, scrolls);
        } catch (RuntimeException ignored) {
            // Ignore; yaw/pitch recovery already happened.
        }
    }

    private static String safeLabel(String targetLabel) {
        return targetLabel == null || targetLabel.isBlank() ? "scene object" : targetLabel;
    }

    private static void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}
