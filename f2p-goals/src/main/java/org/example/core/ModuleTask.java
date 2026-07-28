package org.example.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.runtime.RuntimeController;

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
