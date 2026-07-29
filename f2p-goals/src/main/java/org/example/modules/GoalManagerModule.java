package org.example.modules;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.F2PModule;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.navigation.Navigation;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class GoalManagerModule implements F2PModule {
    private static final long MIN_GOAL_WINDOW_MILLIS = 60 * 60 * 1000L;
    private static final long MAX_GOAL_WINDOW_MILLIS = 90 * 60 * 1000L;
    private static final long WATCHDOG_REROLL_COOLDOWN_MILLIS = 15 * 60_000L;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final List<ManagedF2PModule> skillingModules;
    private final F2PModule fallbackModule;
    private ManagedF2PModule activeModule;
    private long activeUntil;
    private long nextActiveModuleLogAt;
    private boolean inventoryCleanupPending = true;
    private String inventoryCleanupTarget = "initial goal";
    private int inventoryCleanupDepositAttempts;
    private final Map<String, Long> watchdogCooldowns = new HashMap<>();
    private boolean watchdogRerollRequested;
    private String watchdogRerollReason;

    public GoalManagerModule(
            Consumer<String> logger,
            ScriptStats stats,
            List<ManagedF2PModule> skillingModules,
            F2PModule fallbackModule
    ) {
        this.logger = logger;
        this.stats = stats;
        this.skillingModules = skillingModules;
        this.fallbackModule = fallbackModule;
    }

    @Override
    public String name() {
        return "goal.manager";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return true;
    }

    public void requestReroll(String reason) {
        watchdogRerollRequested = true;
        watchdogRerollReason = reason == null || reason.isBlank()
                ? "Loop watchdog requested task reroll"
                : reason;
    }

    @Override
    public void execute(APIContext ctx) {
        if (watchdogRerollRequested) {
            handleWatchdogReroll(ctx);
        }

        boolean timedOut = activeModule != null && System.currentTimeMillis() >= activeUntil;
        boolean completed = activeModule != null && activeModule.isComplete(ctx);
        if (completed && closeCompletedGoalScreen(ctx, activeModule)) {
            return;
        }

        if (activeModule == null
                || timedOut
                || completed
                || !activeModule.shouldExecute(ctx)) {
            chooseNextGoal(ctx, timedOut);
        }

        if (activeModule != null) {
            if (ensureCleanInventoryBeforeGoal(ctx, displayGoalName(activeModule.name()))) {
                return;
            }

            logActiveModuleTick();
            activeModule.execute(ctx);
            return;
        }

        if (ensureCleanInventoryBeforeGoal(ctx, displayGoalName(fallbackModule.name()))) {
            return;
        }

        fallbackModule.execute(ctx);
    }

    private boolean closeCompletedGoalScreen(APIContext ctx, ManagedF2PModule completedModule) {
        String goalName = displayGoalName(completedModule.name());

        if (ctx.bank().isOpen()) {
            log(goalName + " complete; closing bank before next goal");
            ctx.bank().close();
            Time.sleep(450, 800, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        if (ctx.grandExchange().isOpen()) {
            log(goalName + " complete; closing GE before next goal");
            ctx.grandExchange().close();
            Time.sleep(450, 800, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (hasActionableDialogue(ctx)) {
            log(goalName + " complete; closing dialogue before next goal");
            if (ctx.dialogues().canContinue() && ctx.dialogues().selectContinue()) {
                Time.sleep(450, 800);
                return true;
            }
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(450, 800);
            return true;
        }

        if (ctx.widgets().isInterfaceOpen()) {
            log(goalName + " complete; closing interface before next goal");
            if (!ctx.widgets().closeInterface()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(450, 800);
            return true;
        }

        return false;
    }

    private void chooseNextGoal(APIContext ctx, boolean avoidPreviousAfterTimeout) {
        ManagedF2PModule previous = activeModule;
        activeModule = null;
        expireWatchdogCooldowns();

        List<ManagedF2PModule> candidates = new ArrayList<>();
        boolean skippedCooldownCandidate = false;
        for (ManagedF2PModule module : skillingModules) {
            if (!module.isComplete(ctx) && module.shouldExecute(ctx)) {
                if (isOnWatchdogCooldown(module)) {
                    skippedCooldownCandidate = true;
                    continue;
                }
                candidates.add(module);
            }
        }

        if (candidates.isEmpty()) {
            stats.clearGoal();
            if (skippedCooldownCandidate) {
                log("No managed goals outside watchdog cooldown. Falling back to combat/money making");
                markInventoryCleanupPending("combat/money making");
            } else if (previous != null) {
                log("All managed goal caps reached. Falling back to combat/money making");
                markInventoryCleanupPending("combat/money making");
            }
            return;
        }

        boolean skippedPrevious = false;
        if (avoidPreviousAfterTimeout && previous != null && candidates.size() > 1) {
            candidates.remove(previous);
            skippedPrevious = true;
        }

        candidates.sort(Comparator.comparingInt((ManagedF2PModule module) -> module.priority(ctx)).reversed());
        int bestPriority = candidates.get(0).priority(ctx);
        List<ManagedF2PModule> bestCandidates = new ArrayList<>();
        for (ManagedF2PModule module : candidates) {
            if (module.priority(ctx) == bestPriority) {
                bestCandidates.add(module);
            }
        }

        activeModule = bestCandidates.get(ThreadLocalRandom.current().nextInt(bestCandidates.size()));
        long goalWindowMillis = randomLong(MIN_GOAL_WINDOW_MILLIS, MAX_GOAL_WINDOW_MILLIS);
        activeUntil = System.currentTimeMillis() + goalWindowMillis;
        stats.setGoal(displayGoalName(activeModule.name()), activeUntil);

        if (previous != activeModule) {
            markInventoryCleanupPending(displayGoalName(activeModule.name()));
        }

        if (previous != activeModule || avoidPreviousAfterTimeout) {
            log(selectionLog(ctx, activeModule, goalWindowMillis, skippedPrevious, previous));
        }
    }

    private void handleWatchdogReroll(APIContext ctx) {
        String reason = watchdogRerollReason == null || watchdogRerollReason.isBlank()
                ? "Loop watchdog requested task reroll"
                : watchdogRerollReason;
        watchdogRerollRequested = false;
        watchdogRerollReason = null;

        ManagedF2PModule previous = activeModule;
        if (previous != null) {
            long cooldownUntil = System.currentTimeMillis() + WATCHDOG_REROLL_COOLDOWN_MILLIS;
            watchdogCooldowns.put(previous.name(), cooldownUntil);
            log("Watchdog recovery: rerolling from " + displayGoalName(previous.name())
                    + " for ~" + (WATCHDOG_REROLL_COOLDOWN_MILLIS / 60_000L)
                    + " min; reason=" + reason);
        } else {
            log("Watchdog recovery: reroll requested while fallback was active; reason=" + reason);
        }

        chooseNextGoal(ctx, true);
        String nextTarget = activeModule == null
                ? displayGoalName(fallbackModule.name())
                : displayGoalName(activeModule.name());
        markInventoryCleanupPending(nextTarget);
    }

    private void expireWatchdogCooldowns() {
        long now = System.currentTimeMillis();
        watchdogCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean isOnWatchdogCooldown(ManagedF2PModule module) {
        Long cooldownUntil = watchdogCooldowns.get(module.name());
        return cooldownUntil != null && cooldownUntil > System.currentTimeMillis();
    }

    private boolean ensureCleanInventoryBeforeGoal(APIContext ctx, String goalName) {
        if (!inventoryCleanupPending) {
            return false;
        }

        String target = inventoryCleanupTarget == null || inventoryCleanupTarget.isBlank()
                ? goalName
                : inventoryCleanupTarget;
        if (ctx.inventory().getCount() <= 0) {
            clearInventoryCleanup();
            return false;
        }

        if (ctx.grandExchange().isOpen()) {
            log("Preparing " + target + ": closing GE before inventory cleanup");
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (hasActionableDialogue(ctx)) {
            log("Preparing " + target + ": closing dialogue before inventory cleanup");
            if (ctx.dialogues().canContinue() && ctx.dialogues().selectContinue()) {
                Time.sleep(450, 800);
                return true;
            }
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(450, 800);
            return true;
        }

        if (ctx.widgets().isInterfaceOpen() && !isBankOpen(ctx)) {
            log("Preparing " + target + ": closing interface before inventory cleanup");
            if (!ctx.widgets().closeInterface()) {
                ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            }
            Time.sleep(450, 800);
            return true;
        }

        if (!isBankOpen(ctx)) {
            if (!Navigation.isBankReachable(ctx)) {
                log("Preparing " + target + ": walking to bank to clear previous inventory");
                Navigation.walkToBank(ctx);
                Time.sleep(1200, 1800);
                return true;
            }

            log("Preparing " + target + ": opening bank to clear previous inventory");
            Navigation.openBank(ctx);
            Time.sleep(1200, 1800, () -> isBankOpen(ctx), 100);
            return true;
        }

        int beforeCount = ctx.inventory().getCount();
        log("Preparing " + target + ": depositing leftover inventory from previous task");
        ctx.bank().depositInventory();
        Time.sleep(700, 1100, () -> ctx.inventory().getCount() <= 0, 100);

        if (ctx.inventory().getCount() <= 0) {
            clearInventoryCleanup();
            ctx.bank().close();
            Time.sleep(500, 800, () -> !isBankOpen(ctx), 100);
            return true;
        }

        inventoryCleanupDepositAttempts++;
        if (inventoryCleanupDepositAttempts >= 3 && ctx.inventory().getCount() >= beforeCount) {
            log("Preparing " + target + ": inventory cleanup could not deposit every item; continuing");
            clearInventoryCleanup();
            ctx.bank().close();
            Time.sleep(500, 800);
            return true;
        }

        return true;
    }

    private void markInventoryCleanupPending(String target) {
        inventoryCleanupPending = true;
        inventoryCleanupTarget = target;
        inventoryCleanupDepositAttempts = 0;
    }

    private void clearInventoryCleanup() {
        inventoryCleanupPending = false;
        inventoryCleanupTarget = null;
        inventoryCleanupDepositAttempts = 0;
    }

    private boolean hasActionableDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }
        return ctx.dialogues().canContinue() || !ctx.dialogues().getOptions().isEmpty();
    }

    private void logActiveModuleTick() {
        long now = System.currentTimeMillis();
        if (now < nextActiveModuleLogAt) {
            return;
        }

        log("Running goal module: " + displayGoalName(activeModule.name()));
        nextActiveModuleLogAt = now + 10_000L;
    }

    private void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }

    private long randomLong(long minInclusive, long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    private String displayGoalName(String moduleName) {
        if ("combat.lumbridge_cows".equals(moduleName)) {
            return "Melee Combat";
        }
        if ("combat.ranged_engine".equals(moduleName)) {
            return "Ranged Combat";
        }
        if ("combat.melee".equals(moduleName)) {
            return "Melee Combat";
        }
        if ("combat.ranged".equals(moduleName)) {
            return "Ranged Combat";
        }
        if ("combat.magic".equals(moduleName)) {
            return "Magic Combat";
        }
        if ("quests.cooks_assistant".equals(moduleName)) {
            return "Cook's Assistant";
        }
        if ("quests.dorics_quest".equals(moduleName)) {
            return "Doric's Quest";
        }
        if ("skills.woodcutting_firemaking".equals(moduleName)) {
            return "WC/FM";
        }
        if ("skills.fishing_cooking".equals(moduleName)) {
            return "Fishing/Cooking";
        }
        if ("skills.mining_smithing".equals(moduleName)) {
            return "Mining/Smithing";
        }
        if ("money.beer_glass".equals(moduleName)) {
            return "Beer Glass";
        }
        return moduleName == null ? "Unknown" : moduleName.replace("skills.", "");
    }

    private boolean isBankOpen(APIContext ctx) {
        return ctx.bank().isOpen()
                || hasVisibleWidgetText(ctx, "The Bank of Gielinor")
                || hasVisibleWidgetText(ctx, "Bank of Gielinor");
    }

    private boolean hasVisibleWidgetText(APIContext ctx, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String needle = text.toLowerCase();
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (candidate == null || !candidate.isValid() || candidate.getWidth() <= 0 || candidate.getHeight() <= 0) {
                return false;
            }

            String widgetText = candidate.getText();
            String rawText = candidate.getRawText();
            return containsIgnoreCase(widgetText, needle) || containsIgnoreCase(rawText, needle);
        })) {
            return true;
        }

        return false;
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }

    private String selectionLog(
            APIContext ctx,
            ManagedF2PModule selected,
            long goalWindowMillis,
            boolean skippedPrevious,
            ManagedF2PModule previous
    ) {
        StringBuilder message = new StringBuilder("Goal selected: ")
                .append(displayGoalName(selected.name()))
                .append(" for ~")
                .append(goalWindowMillis / 60000L)
                .append(" min; mode=lowest-first");

        if (skippedPrevious && previous != null) {
            message.append("; skipped previous ")
                    .append(displayGoalName(previous.name()))
                    .append(" after timer");
        }

        message.append("; priorities=");
        for (ManagedF2PModule module : skillingModules) {
            message.append(' ')
                    .append(displayGoalName(module.name()))
                    .append('=')
                    .append(module.priority(ctx));
        }
        return message.toString();
    }
}
