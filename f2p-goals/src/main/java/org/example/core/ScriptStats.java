package org.example.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ScriptStats {
    private static final Skill.Skills[] TRACKED_XP_SKILLS = {
            Skill.Skills.ATTACK,
            Skill.Skills.STRENGTH,
            Skill.Skills.DEFENCE,
            Skill.Skills.HITPOINTS,
            Skill.Skills.RANGED,
            Skill.Skills.MAGIC,
            Skill.Skills.PRAYER,
            Skill.Skills.WOODCUTTING,
            Skill.Skills.FIREMAKING,
            Skill.Skills.FISHING,
            Skill.Skills.COOKING,
            Skill.Skills.MINING,
            Skill.Skills.SMITHING
    };

    private final long startedAt = System.currentTimeMillis();
    private final AtomicInteger kills = new AtomicInteger();
    private final AtomicInteger itemsLooted = new AtomicInteger();
    private final AtomicInteger coinsLooted = new AtomicInteger();
    private final AtomicInteger cowhidesLooted = new AtomicInteger();
    private final AtomicInteger feathersLooted = new AtomicInteger();
    private final AtomicInteger bonesBuried = new AtomicInteger();
    private final AtomicInteger runtimeBreaks = new AtomicInteger();
    private final AtomicLong estimatedGold = new AtomicLong();
    private final AtomicLong firemakingBlockedMessageAt = new AtomicLong();
    private final AtomicLong miningNoOreMessageAt = new AtomicLong();
    private final AtomicLong rangedNoAmmoMessageAt = new AtomicLong();
    private final AtomicLong alreadyUnderAttackMessageAt = new AtomicLong();
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    private volatile boolean xpStarted;
    private volatile int startingXp;
    private volatile String status = "Starting";
    private volatile String trainingSkill = "Melee";
    private volatile String goalName = "Selecting";
    private volatile long goalEndsAt;
    private volatile boolean geRestricted;
    private volatile String currentTask = "Selecting";
    private volatile String internalPhase = "starting";
    private volatile String nextObjective = "Starting";
    private volatile String lastFundingReason = "-";
    private volatile String lastRecoverableError = "-";

    public long runtimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public String runtimeText() {
        long seconds = runtimeMillis() / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    public void startExperienceIfNeeded(APIContext ctx) {
        if (xpStarted) {
            return;
        }

        synchronized (this) {
            if (!xpStarted) {
                startingXp = totalTrackedXp(ctx);
                xpStarted = true;
            }
        }
    }

    public int xpGained(APIContext ctx) {
        startExperienceIfNeeded(ctx);
        return Math.max(0, totalTrackedXp(ctx) - startingXp);
    }

    public long xpPerHour(APIContext ctx) {
        return perHour(xpGained(ctx));
    }

    public int recordKill() {
        return kills.incrementAndGet();
    }

    public void recordLoot(String itemName, int quantity, long value) {
        itemsLooted.addAndGet(Math.max(1, quantity));
        if ("Coins".equals(itemName)) {
            coinsLooted.addAndGet(Math.max(1, quantity));
        }
        if ("Cowhide".equals(itemName)) {
            cowhidesLooted.addAndGet(Math.max(1, quantity));
        }
        if ("Feather".equals(itemName) || "Feathers".equals(itemName)) {
            feathersLooted.addAndGet(Math.max(1, quantity));
        }
        estimatedGold.addAndGet(Math.max(0L, value));
    }

    public void recordBonesBuried() {
        bonesBuried.incrementAndGet();
    }

    public void recordRuntimeBreak() {
        runtimeBreaks.incrementAndGet();
    }

    public void setStatus(String status) {
        String sanitized = sanitize(status, "Unknown");
        this.status = sanitized;
        this.internalPhase = phaseFromStatus(sanitized);
        this.nextObjective = sanitized;
        if (looksLikeFunding(sanitized)) {
            this.lastFundingReason = sanitized;
        }
        if (looksLikeRecoverableError(sanitized)) {
            this.lastRecoverableError = sanitized;
        }
        recordEvent(sanitized);
    }

    public void setTrainingSkill(String trainingSkill) {
        this.trainingSkill = trainingSkill;
    }

    public void setGoal(String goalName, long goalEndsAt) {
        this.goalName = goalName == null || goalName.isBlank() ? "Unknown" : goalName;
        this.currentTask = this.goalName;
        this.goalEndsAt = Math.max(0L, goalEndsAt);
    }

    public void clearGoal() {
        this.goalName = "Combat/Money";
        this.currentTask = this.goalName;
        this.goalEndsAt = 0L;
    }

    public void setGeRestricted(boolean geRestricted) {
        this.geRestricted = geRestricted;
    }

    public void recordFiremakingBlockedMessage() {
        firemakingBlockedMessageAt.set(System.currentTimeMillis());
        recordRecoverableError("Firemaking blocked by game message");
    }

    public boolean consumeRecentFiremakingBlockedMessage() {
        long messageAt = firemakingBlockedMessageAt.get();
        if (messageAt <= 0 || System.currentTimeMillis() - messageAt > 30_000L) {
            return false;
        }
        return firemakingBlockedMessageAt.compareAndSet(messageAt, 0L);
    }

    public void recordMiningNoOreMessage() {
        miningNoOreMessageAt.set(System.currentTimeMillis());
        recordRecoverableError("Mining rock is depleted");
    }

    public boolean consumeRecentMiningNoOreMessage() {
        long messageAt = miningNoOreMessageAt.get();
        if (messageAt <= 0 || System.currentTimeMillis() - messageAt > 10_000L) {
            return false;
        }
        return miningNoOreMessageAt.compareAndSet(messageAt, 0L);
    }

    public void recordRangedNoAmmoMessage() {
        rangedNoAmmoMessageAt.set(System.currentTimeMillis());
        recordRecoverableError("Ranged has no ammo equipped");
    }

    public boolean consumeRecentRangedNoAmmoMessage() {
        long messageAt = rangedNoAmmoMessageAt.get();
        if (messageAt <= 0 || System.currentTimeMillis() - messageAt > 30_000L) {
            return false;
        }
        return rangedNoAmmoMessageAt.compareAndSet(messageAt, 0L);
    }

    public void recordAlreadyUnderAttackMessage() {
        alreadyUnderAttackMessageAt.set(System.currentTimeMillis());
        recordRecoverableError("Combat already under attack");
    }

    public boolean hasRecentAlreadyUnderAttackMessage() {
        long messageAt = alreadyUnderAttackMessageAt.get();
        return messageAt > 0 && System.currentTimeMillis() - messageAt <= 8_000L;
    }

    public boolean consumeRecentAlreadyUnderAttackMessage() {
        long messageAt = alreadyUnderAttackMessageAt.get();
        if (messageAt <= 0 || System.currentTimeMillis() - messageAt > 8_000L) {
            return false;
        }
        return alreadyUnderAttackMessageAt.compareAndSet(messageAt, 0L);
    }

    public int kills() {
        return kills.get();
    }

    public int itemsLooted() {
        return itemsLooted.get();
    }

    public int coinsLooted() {
        return coinsLooted.get();
    }

    public int cowhidesLooted() {
        return cowhidesLooted.get();
    }

    public int feathersLooted() {
        return feathersLooted.get();
    }

    public int bonesBuried() {
        return bonesBuried.get();
    }

    public long estimatedGold() {
        return estimatedGold.get();
    }

    public long goldPerHour() {
        return perHour(estimatedGold());
    }

    public String status() {
        return status;
    }

    public String trainingSkill() {
        return trainingSkill;
    }

    public String goalText() {
        return goalName + " | " + goalRemainingText();
    }

    public String goalRemainingText() {
        if (goalEndsAt <= 0L) {
            return "--";
        }

        long remainingMillis = goalEndsAt - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            return "switching";
        }

        long totalMinutes = Math.max(1L, (long) Math.ceil(remainingMillis / 60_000.0));
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m left";
        }
        return minutes + "m left";
    }

    public String currentTask() {
        return currentTask;
    }

    public String internalPhase() {
        return internalPhase;
    }

    public String nextObjective() {
        return nextObjective;
    }

    public String lastFundingReason() {
        return lastFundingReason;
    }

    public String lastRecoverableError() {
        return lastRecoverableError;
    }

    public void setFundingReason(String reason) {
        String sanitized = sanitize(reason, "-");
        lastFundingReason = sanitized;
        recordEvent("Funding: " + sanitized);
    }

    public void recordRelevantLog(String message) {
        recordEvent(sanitize(message, "log"));
    }

    public void recordRecoverableError(String message) {
        String sanitized = sanitize(message, "Recoverable error");
        lastRecoverableError = sanitized;
        setStatus(sanitized);
    }

    public List<String> recentEvents(int max) {
        synchronized (recentEvents) {
            int limit = Math.max(0, max);
            int skip = Math.max(0, recentEvents.size() - limit);
            List<String> result = new ArrayList<>(Math.min(limit, recentEvents.size()));
            int index = 0;
            for (String event : recentEvents) {
                if (index++ >= skip) {
                    result.add(event);
                }
            }
            return result;
        }
    }

    public boolean isGeRestricted() {
        return geRestricted;
    }

    public long progressScore() {
        return kills.get()
                + itemsLooted.get()
                + coinsLooted.get()
                + cowhidesLooted.get()
                + feathersLooted.get()
                + bonesBuried.get()
                + runtimeBreaks.get()
                + estimatedGold.get();
    }

    private int totalTrackedXp(APIContext ctx) {
        int total = 0;
        for (Skill.Skills skill : TRACKED_XP_SKILLS) {
            total += ctx.skills().get(skill).getExperience();
        }
        return total;
    }

    private long perHour(long amount) {
        long runtime = Math.max(1L, runtimeMillis());
        return Math.round(amount * 3_600_000.0 / runtime);
    }

    private void recordEvent(String message) {
        String event = runtimeText() + " | " + sanitize(message, "event");
        synchronized (recentEvents) {
            recentEvents.addLast(event);
            while (recentEvents.size() > 120) {
                recentEvents.removeFirst();
            }
        }
    }

    private String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String phaseFromStatus(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("equip") || lower.contains("wield") || lower.contains("wear") || lower.contains("gear")) {
            return "equipping";
        }
        if (lower.contains("buy") || lower.contains("purchase")) {
            return "buying";
        }
        if (lower.contains("sell") || lower.contains("sale") || lower.contains("offer")) {
            return "selling";
        }
        if (lower.contains("bank") || lower.contains("withdraw") || lower.contains("deposit")) {
            return "banking";
        }
        if (lower.contains("walk") || lower.contains("navigating") || lower.contains("route")) {
            return "walking";
        }
        if (lower.contains("attack") || lower.contains("combat") || lower.contains("splash")) {
            return "fighting";
        }
        if (lower.contains("funding")) {
            return "funding";
        }
        return "working";
    }

    private boolean looksLikeFunding(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("funding") || lower.contains("money maker") || lower.contains("money-making");
    }

    private boolean looksLikeRecoverableError(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("could not")
                || lower.contains("failed")
                || lower.contains("blocked")
                || lower.contains("retry")
                || lower.contains("stuck")
                || lower.contains("missing")
                || lower.contains("no ")
                || lower.contains("cannot")
                || lower.contains("can't");
    }
}
