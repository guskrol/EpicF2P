package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.random.AntiRandom;
import com.epicbot.api.shared.random.AntiRandomType;
import org.example.core.ScriptStats;
import org.example.modules.GoalManagerModule;

import java.util.function.Consumer;

/** Runs ahead of login handling and every task dispatch. Never sleeps inside a break. */
public final class ScheduledBreakController {
    private final BreakSchedule schedule;
    private final Consumer<String> logger;
    private final GoalManagerModule goals;
    private final LoopWatchdogController watchdog;
    private final ScriptStats stats;
    private AntiRandom loginHandler;
    private boolean previousLoginEnabled;
    private long logoutRequestedAt;
    private boolean logoutObserved;
    private volatile String hud = "";

    public ScheduledBreakController(Consumer<String> logger, GoalManagerModule goals,
                                    LoopWatchdogController watchdog, ScriptStats stats) {
        this.logger = logger;
        this.goals = goals;
        this.watchdog = watchdog;
        this.stats = stats;
        this.schedule = new BreakSchedule(System.currentTimeMillis());
    }

    /** True means the caller must not dispatch any other controller or module. */
    public boolean tick(APIContext ctx) {
        long now = System.currentTimeMillis();
        boolean loggedIn = loggedIn(ctx);
        schedule.update(now, ready(ctx));
        BreakSchedule.Type active = schedule.active();
        if (active != null) {
            if (schedule.finishIfElapsed(now)) {
                restoreLogin();
                logger.accept("[Break] Ended " + active.label);
                logger.accept("[Break] Task login gate released; waiting for account readiness");
                hud = "Waiting for login";
                active = null;
            } else {
                hud = active.label + " " + format(schedule.remainingMs(now));
                if (loginHandler != null) loginHandler.setEnabled(false);
                if (active.logout) {
                    if (!loggedIn) {
                        if (!logoutObserved) logger.accept("[Break] Logout confirmed; account held offline");
                        logoutObserved = true;
                    } else if (now - logoutRequestedAt >= 10_000L) {
                        logoutRequestedAt = now;
                        logger.accept("[Break] Retrying logout with automatic login blocked");
                        ctx.game().logout();
                    }
                }
                return true;
            }
        }
        if (schedule.waitingForLogin()) {
            if (!schedule.resumeIfReady(ready(ctx))) return true;
            goals.resumeAfterBreak();
            watchdog.resetAfterBreak(ctx);
            logger.accept("[Break] Login ready; resuming task " + stats.currentTask());
            hud = "";
            return true;
        }
        if (!loggedIn) return false;
        BreakSchedule.Type due = schedule.due(now);
        if (due == null) return false;
        if (!blockLogin(ctx)) {
            ctx.script().stop("Cannot block automatic login safely for scheduled break");
            return true;
        }
        goals.pauseForBreak();
        long until = schedule.start(due, now);
        hud = due.label + " " + format(until - now);
        logger.accept("[Break] Starting " + due.label + " for " + format(until - now));
        logger.accept("[Break] Task login gate active during break");
        if (due.logout) {
            logoutRequestedAt = now;
            logoutObserved = false;
            logger.accept("[Break] Logout requested once for " + due.label);
            try {
                if (!ctx.game().logout()) {
                    logger.accept("[Break] Logout request was not accepted; task remains paused");
                }
            } catch (RuntimeException e) {
                logger.accept("[Break] Logout failed: " + e.getMessage());
            }
        }
        return true;
    }

    public String hud() { return hud; }

    private boolean blockLogin(APIContext ctx) {
        try {
            AntiRandom handler = ctx.script().getScript().getRandomHandler()
                    .getRandomHandler(AntiRandomType.LOGIN);
            if (handler == null) {
                logger.accept("[Break] LOGIN handler missing; stopping before logout");
                return false;
            }
            previousLoginEnabled = handler.isEnabled();
            loginHandler = handler;
            handler.setEnabled(false);
            logger.accept("[Break] Automatic login blocked; previous enabled=" + previousLoginEnabled);
            return true;
        } catch (RuntimeException failure) {
            logger.accept("[Break] Cannot block automatic login: " + failure.getMessage());
            return false;
        }
    }

    public void restoreLogin() {
        if (loginHandler == null) return;
        loginHandler.setEnabled(previousLoginEnabled);
        logger.accept("[Break] Automatic login restored; enabled=" + previousLoginEnabled);
        loginHandler = null;
    }

    private static boolean loggedIn(APIContext ctx) {
        try { return ctx != null && ctx.client().isLoggedIn(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean ready(APIContext ctx) {
        try {
            return loggedIn(ctx) && ctx.localPlayer().getLocation() != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String format(long millis) {
        long seconds = Math.max(0L, (millis + 999L) / 1000L);
        return String.format("%02d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L);
    }
}
