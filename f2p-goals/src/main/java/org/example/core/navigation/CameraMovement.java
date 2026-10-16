package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.util.time.Time;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Serializes an entire yaw/pitch adjustment using a single input method. */
public final class CameraMovement {
    enum Method { KEYBOARD, MIDDLE_MOUSE }
    private static final Map<APIContext, Adjustment> ADJUSTMENTS = new WeakHashMap<>();

    private CameraMovement() { }

    public static boolean focus(APIContext ctx, Locatable focus, Consumer<String> logger) {
        if (ctx == null || ctx.camera() == null || focus == null) return false;
        return turn(ctx, ctx.camera().getAngleToDeg(focus),
                ThreadLocalRandom.current().nextInt(60, 79), logger);
    }

    public static boolean turn(APIContext ctx, int yaw, int pitch, Consumer<String> logger) {
        return turn(ctx, yaw, pitch, logger, null);
    }

    static boolean turn(APIContext ctx, int yaw, int pitch, Consumer<String> logger, Method preferred) {
        if (ctx == null || ctx.camera() == null) return false;
        Adjustment adjustment;
        synchronized (ADJUSTMENTS) {
            adjustment = ADJUSTMENTS.computeIfAbsent(ctx, ignored -> new Adjustment());
        }
        synchronized (adjustment) {
            active(ctx, adjustment);
            if (adjustment.complete) {
                adjustment.method = preferred == null ? chooseMethod(ctx) : preferred;
                adjustment.yaw = Math.floorMod(yaw, 360);
                adjustment.pitch = Math.max(0, Math.min(90, pitch));
                adjustment.complete = false;
                log(logger, "Camera target: " + adjustment.method + " yaw=" + adjustment.yaw
                        + " pitch=" + adjustment.pitch);
            }
            // A retry finishes the existing target with the existing method, even if a caller requests another.
            boolean requestedTarget = Math.abs(yawDelta(Math.floorMod(yaw, 360), adjustment.yaw)) <= 3
                    && Math.abs(Math.max(0, Math.min(90, pitch)) - adjustment.pitch) <= 3;
            if (aligned(ctx, adjustment)) return completed(adjustment, logger) && requestedTarget;
            try {
                if (adjustment.method == Method.KEYBOARD) {
                    ctx.camera().setYawDeg(adjustment.yaw, false);
                    active(ctx, adjustment);
                    ctx.camera().setPitch(adjustment.pitch, false);
                } else {
                    for (int correction = 0; correction < 4 && !aligned(ctx, adjustment); correction++) {
                        active(ctx, adjustment);
                        if (!dragTowardTarget(ctx, adjustment)) break;
                    }
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                log(logger, "Camera adjustment interrupted; retaining " + adjustment.method + " for retry");
                return false;
            }
            active(ctx, adjustment);
            if (aligned(ctx, adjustment)) return completed(adjustment, logger) && requestedTarget;
            log(logger, "Camera target not reached yet; retaining " + adjustment.method);
            return false;
        }
    }

    private static Method chooseMethod(APIContext ctx) {
        boolean mouseAvailable = ctx.mouse() != null && ctx.game() != null && ctx.vars() != null
                && ctx.vars().getVarbit(VarbitID.MOUSECAM_DISABLED) == 0;
        return mouseAvailable && ThreadLocalRandom.current().nextInt(100) < 55
                ? Method.MIDDLE_MOUSE : Method.KEYBOARD;
    }

    private static boolean dragTowardTarget(APIContext ctx, Adjustment adjustment) {
        Rectangle viewport = ctx.game().getViewport();
        if (viewport == null || viewport.width < 160 || viewport.height < 160) return false;
        int yawBefore = ctx.camera().getYawDeg();
        int pitchBefore = ctx.camera().getPitch();
        double yawError = yawDelta(adjustment.yaw, yawBefore);
        int pitchError = adjustment.pitch - pitchBefore;
        int dx = Math.abs(yawError) <= 3 ? 0 : Double.isNaN(adjustment.yawPerPixel)
                ? (int) -Math.copySign(30, yawError) : (int) Math.round(yawError / adjustment.yawPerPixel);
        int dy = Math.abs(pitchError) <= 3 ? 0 : Double.isNaN(adjustment.pitchPerPixel)
                ? (int) Math.copySign(18, pitchError) : (int) Math.round(pitchError / adjustment.pitchPerPixel);
        dx = clamp(dx, -Math.min(160, viewport.width / 3), Math.min(160, viewport.width / 3));
        dy = clamp(dy, -Math.min(90, viewport.height / 3), Math.min(90, viewport.height / 3));
        if (dx == 0 && dy == 0) return false;
        Point start = new Point(viewport.x + viewport.width / 2 + ThreadLocalRandom.current().nextInt(-16, 17),
                viewport.y + viewport.height / 2 + ThreadLocalRandom.current().nextInt(-12, 13));
        Point end = new Point(clamp(start.x + dx, viewport.x + 40, viewport.x + viewport.width - 40),
                clamp(start.y + dy, viewport.y + 40, viewport.y + viewport.height - 40));
        dx = end.x - start.x;
        dy = end.y - start.y;
        // The API drag releases BUTTON2 when the operation ends; no left/right click is sent to the world.
        if (!ctx.mouse().drag(start, end, MouseEvent.BUTTON2)) return false;
        Time.sleep(120, 220);
        double yawChange = yawDelta(ctx.camera().getYawDeg(), yawBefore);
        double pitchChange = ctx.camera().getPitch() - pitchBefore;
        if (dx != 0 && Math.abs(yawChange) >= 0.5 && Math.abs(yawChange / dx) < 6) {
            adjustment.yawPerPixel = yawChange / dx;
        }
        if (dy != 0 && Math.abs(pitchChange) >= 0.5 && Math.abs(pitchChange / dy) < 3) {
            adjustment.pitchPerPixel = pitchChange / dy;
        }
        return true;
    }

    private static boolean aligned(APIContext ctx, Adjustment adjustment) {
        return Math.abs(yawDelta(adjustment.yaw, ctx.camera().getYawDeg())) <= 3
                && Math.abs(adjustment.pitch - ctx.camera().getPitch()) <= 3;
    }

    static int yawDelta(int target, int current) {
        return Math.floorMod(target - current + 180, 360) - 180;
    }

    private static boolean completed(Adjustment adjustment, Consumer<String> logger) {
        adjustment.complete = true;
        log(logger, "Camera target reached using " + adjustment.method);
        return true;
    }

    public static void cancel(APIContext ctx) {
        synchronized (ADJUSTMENTS) {
            Adjustment current = ADJUSTMENTS.get(ctx);
            if (current != null) current.cancelled = true;
        }
    }

    public static void reset(APIContext ctx) {
        if (ctx == null) return;
        synchronized (ADJUSTMENTS) {
            cancel(ctx);
            ADJUSTMENTS.put(ctx, new Adjustment());
        }
    }

    private static void active(APIContext ctx, Adjustment adjustment) {
        if (adjustment.cancelled || Thread.currentThread().isInterrupted()
                || (ctx.script() != null && ctx.script().getScript() != null
                    && (!ctx.script().isRunning() || ctx.script().isPaused() || ctx.script().isStopping()))) {
            throw new CancellationException();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void log(Consumer<String> logger, String message) {
        if (logger != null) logger.accept("[Camera] " + message);
    }

    private static final class Adjustment {
        Method method;
        boolean complete = true;
        volatile boolean cancelled;
        int yaw;
        int pitch;
        double yawPerPixel = Double.NaN;
        double pitchPerPixel = Double.NaN;
    }
}
