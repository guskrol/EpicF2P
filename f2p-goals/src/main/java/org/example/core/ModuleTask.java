package org.example.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.runtime.RuntimeController;

import java.awt.Point;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ModuleTask implements ScriptTask {
    private static final long LOGIN_WAIT_LOG_INTERVAL_MILLIS = 5_000L;

    private final Supplier<APIContext> contextSupplier;
    private final Consumer<String> logger;
    private final List<RuntimeController> runtimeControllers;
    private final List<F2PModule> modules;
    private long nextLoginWaitLogAt;

    public ModuleTask(Supplier<APIContext> contextSupplier, Consumer<String> logger, List<F2PModule> modules) {
        this(contextSupplier, logger, List.of(), modules);
    }

    public ModuleTask(
            Supplier<APIContext> contextSupplier,
            Consumer<String> logger,
            List<RuntimeController> runtimeControllers,
            List<F2PModule> modules
    ) {
        this.contextSupplier = contextSupplier;
        this.logger = logger;
        this.runtimeControllers = runtimeControllers;
        this.modules = modules;
    }

    @Override
    public boolean shouldExecute() {
        return true;
    }

    @Override
    public void run() {
        APIContext ctx = contextSupplier.get();
        if (!isLoggedIn(ctx)) {
            if (acceptPendingEula(ctx)) {
                Time.sleep(900, 1400);
                return;
            }

            logWaitingForLogin();
            Time.sleep(900, 1400);
            return;
        }

        for (RuntimeController controller : runtimeControllers) {
            if (controller.shouldExecute(ctx)) {
                runController(controller, ctx);
                return;
            }
        }

        for (F2PModule module : modules) {
            if (module.shouldExecute(ctx)) {
                runModule(module, ctx);
                return;
            }
        }

        logger.accept("No module was ready to execute");
        Time.sleep(600, 900);
    }

    private boolean acceptPendingEula(APIContext ctx) {
        if (ctx == null || !hasVisibleWidgetText(ctx, "end user licence agreement")) {
            return false;
        }

        WidgetChild acceptButton = findVisibleWidgetWithExactText(ctx, "Accept");
        if (!clickWidgetCenter(ctx, acceptButton)) {
            return false;
        }

        logger.accept("Accepting OSRS EULA before login");
        return true;
    }

    private boolean isLoggedIn(APIContext ctx) {
        try {
            return ctx != null && ctx.client().isLoggedIn();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void logWaitingForLogin() {
        long now = System.currentTimeMillis();
        if (now < nextLoginWaitLogAt) {
            return;
        }

        logger.accept("Waiting for login before running F2P Goals");
        nextLoginWaitLogAt = now + LOGIN_WAIT_LOG_INTERVAL_MILLIS;
    }

    private boolean hasVisibleWidgetText(APIContext ctx, String text) {
        return findVisibleWidgetContaining(ctx, text) != null;
    }

    private WidgetChild findVisibleWidgetContaining(APIContext ctx, String text) {
        if (ctx == null || text == null || text.isBlank()) {
            return null;
        }

        String needle = text.toLowerCase();
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            return containsIgnoreCase(widgetText, needle) || containsIgnoreCase(rawText, needle);
        })) {
            return widget;
        }

        return null;
    }

    private WidgetChild findVisibleWidgetWithExactText(APIContext ctx, String text) {
        if (ctx == null || text == null || text.isBlank()) {
            return null;
        }

        String normalizedText = text.trim().toLowerCase();
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (!isVisibleWidget(candidate)) {
                return false;
            }

            return normalizedText.equals(normalizeWidgetText(candidate.getText()))
                    || normalizedText.equals(normalizeWidgetText(candidate.getRawText()));
        })) {
            return widget;
        }

        return null;
    }

    private String normalizeWidgetText(String text) {
        return text == null ? "" : text.trim().toLowerCase();
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        if (widget.click(false) || widget.click()) {
            return true;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private void runController(RuntimeController controller, APIContext ctx) {
        try {
            controller.execute(ctx);
        } catch (Throwable e) {
            logger.accept("Runtime controller failed: " + controller.name()
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Time.sleep(1200, 1800);
        }
    }

    private void runModule(F2PModule module, APIContext ctx) {
        try {
            module.execute(ctx);
        } catch (Throwable e) {
            logger.accept("Module failed: " + module.name()
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Time.sleep(1200, 1800);
        }
    }
}
