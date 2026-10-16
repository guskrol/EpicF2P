package org.example.core.navigation;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.*;
import org.example.core.runtime.CameraZoomController;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;

public final class CameraMovementRegressionTest {
    public static void main(String[] args) {
        int[] angles = {0, 30};
        int[] calls = {0, 0, 0}; // keyboard setters, middle-button drags, wheel scrolls
        boolean[] keyboardAccepts = {false};
        boolean[] mouseAccepts = {true};
        ICameraAPI camera = stub(ICameraAPI.class, (p, m, a) -> switch (m.getName()) {
            case "getYawDeg" -> angles[0];
            case "getPitch" -> angles[1];
            case "setYawDeg", "setPitch" -> {
                require(a.length == 2 && Boolean.FALSE.equals(a[1]), "camera target must not run asynchronously");
                int axis = m.getName().equals("setYawDeg") ? 0 : 1;
                if (axis == 1) require((int) a[0] >= 0 && (int) a[0] <= 90, "pitch uses the API's 0-90 degree scale");
                calls[0]++;
                if (keyboardAccepts[0]) angles[axis] = (int) a[0];
                yield null;
            }
            default -> null;
        });
        IMouseAPI mouse = stub(IMouseAPI.class, (p, m, a) -> {
            if (m.getName().equals("drag")) {
                require(a.length == 3 && (int) a[2] == MouseEvent.BUTTON2, "mouse rotation must hold the middle button");
                calls[1]++;
                if (!mouseAccepts[0]) return false;
                Point start = (Point) a[0], end = (Point) a[1];
                require(new Rectangle(100, 100, 900, 600).contains(start)
                        && new Rectangle(100, 100, 900, 600).contains(end), "drag stays inside the viewport");
                angles[0] = Math.floorMod(angles[0] + (int) Math.round((end.x - start.x) * -0.75), 360);
                angles[1] = Math.max(0, Math.min(90, angles[1] + (int) Math.round((end.y - start.y) * 0.5)));
                return true;
            }
            if (m.getName().equals("scroll")) { calls[2]++; return true; }
            return null;
        });
        IGameAPI game = stub(IGameAPI.class, (p, m, a) -> new Rectangle(100, 100, 900, 600));
        IBankAPI bank = stub(IBankAPI.class, (p, m, a) -> false);
        IGrandExchangeAPI ge = stub(IGrandExchangeAPI.class, (p, m, a) -> false);
        APIContext ctx = stub(APIContext.class, (p, m, a) -> switch (m.getName()) {
            case "camera" -> camera;
            case "mouse" -> mouse;
            case "game" -> game;
            case "bank" -> bank;
            case "grandExchange" -> ge;
            default -> null;
        });
        CameraMovement.reset(ctx);
        require(!CameraMovement.turn(ctx, 90, 60, null, CameraMovement.Method.KEYBOARD), "unfinished keyboard target");
        require(!CameraMovement.turn(ctx, 180, 75, null, CameraMovement.Method.MIDDLE_MOUSE), "pending target retains its method");
        require(calls[1] == 0 && calls[0] == 4, "no mouse fallback in the middle of a keyboard adjustment");
        new CameraZoomController(message -> { }).execute(ctx);
        require(calls[2] == 0, "wheel zoom must wait for the keyboard target to finish");
        keyboardAccepts[0] = true;
        require(!CameraMovement.turn(ctx, 180, 75, null, CameraMovement.Method.MIDDLE_MOUSE),
                "finish the old target before accepting a new target");
        require(angles[0] == 90 && angles[1] == 60 && calls[1] == 0, "old target completed entirely with keyboard");
        int keyboardBeforeMouse = calls[0];
        require(CameraMovement.turn(ctx, 180, 75, null, CameraMovement.Method.MIDDLE_MOUSE), "mouse feedback reaches the new target");
        require(calls[1] > 0 && calls[0] == keyboardBeforeMouse && calls[2] == 0,
                "middle-button rotation uses neither keyboard nor wheel scrolling");
        mouseAccepts[0] = false;
        require(!CameraMovement.turn(ctx, 270, 60, null, CameraMovement.Method.MIDDLE_MOUSE), "unfinished mouse target");
        require(!CameraMovement.turn(ctx, 270, 60, null, CameraMovement.Method.KEYBOARD), "mouse retry must retain the mouse method");
        require(calls[0] == keyboardBeforeMouse, "no keyboard fallback inside a mouse adjustment");
        CameraMovement.cancel(ctx);
        int dragCount = calls[1];
        try {
            CameraMovement.turn(ctx, 270, 60, null);
            throw new AssertionError("cancelled camera target continued");
        } catch (CancellationException expected) { }
        require(calls[1] == dragCount, "cancelled adjustments send no new input");
        require(CameraMovement.yawDelta(10, 350) == 20 && CameraMovement.yawDelta(350, 10) == -20,
                "rotation uses the shorter angle across north");
        System.out.println("PASS: keyboard/middle-mouse target lock, synchronous completion, degree scale, zoom exclusion and cancellation");
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
