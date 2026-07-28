package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.function.Consumer;

public class CameraZoomController implements RuntimeController {
    private static final int ONE_TIME_SCROLLS = 18;
    private static final boolean ZOOM_OUT_SCROLL_DIRECTION = false;
    private static final int SETUP_PITCH = 380;

    private final Consumer<String> logger;
    private boolean complete;

    public CameraZoomController(Consumer<String> logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "runtime.camera_zoom_setup";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !complete;
    }

    @Override
    public void execute(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            logger.accept("[Camera] Closing bank for zoom setup");
            ctx.bank().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.grandExchange().isOpen()) {
            logger.accept("[Camera] Closing GE for zoom setup");
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        moveMouseToViewport(ctx);
        ctx.camera().setPitch(SETUP_PITCH, true);
        Time.sleep(250, 450);

        logger.accept("[Camera] One-time zoom out setup: scrolling camera far away once");
        ctx.mouse().scroll(ZOOM_OUT_SCROLL_DIRECTION, ONE_TIME_SCROLLS);
        Time.sleep(600, 900);
        finish("Camera zoom setup done once");
    }

    private void moveMouseToViewport(APIContext ctx) {
        try {
            if (ctx.localPlayer().get() != null && ctx.mouse().move(ctx.localPlayer().get())) {
                Time.sleep(150, 300);
                return;
            }
        } catch (RuntimeException ignored) {
            // Fallback to a central viewport point below.
        }

        Rectangle viewport = ctx.game().getViewport();
        if (viewport == null) {
            int x = Math.max(120, ctx.client().getCanvasWidth() / 2);
            int y = Math.max(120, ctx.client().getCanvasHeight() / 2);
            ctx.mouse().move(new Point(x, y));
            return;
        }

        int x = viewport.x + Math.max(80, viewport.width / 3);
        int y = viewport.y + Math.max(80, viewport.height / 3);
        ctx.mouse().move(new Point(x, y));
    }

    private void finish(String message) {
        complete = true;
        logger.accept("[Camera] " + message);
    }
}
