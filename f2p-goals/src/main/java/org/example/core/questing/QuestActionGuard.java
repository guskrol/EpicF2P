package org.example.core.questing;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Limits repeated quest actions until the observed game state changes. */
public final class QuestActionGuard {
    private static final int MAX_TRACKED_ACTIONS = 80;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Consumer<String> logger;

    public QuestActionGuard(Consumer<String> logger) { this.logger = logger; }

    public boolean allow(String action, String observation, long retryAfterMillis, int maxAttempts) {
        if (attempts.size() > MAX_TRACKED_ACTIONS) attempts.clear();
        Attempt attempt = attempts.computeIfAbsent(action, ignored -> new Attempt());
        if (!observation.equals(attempt.observation)) {
            attempt.observation = observation;
            attempt.count = 0;
            attempt.lastIssuedAt = 0;
            attempt.reported = false;
        }
        long now = System.currentTimeMillis();
        if (now - attempt.lastIssuedAt < retryAfterMillis) return false;
        if (attempt.count >= maxAttempts) {
            if (!attempt.reported) {
                logger.accept("Quest action stalled: " + action + " after " + maxAttempts
                        + " attempts without observed progress; state=" + observation);
                attempt.reported = true;
            }
            return false;
        }
        attempt.count++;
        attempt.lastIssuedAt = now;
        return true;
    }

    public boolean isStalled(String action) {
        Attempt attempt = attempts.get(action);
        return attempt != null && attempt.reported;
    }

    public void reset() { attempts.clear(); }

    private static final class Attempt {
        private String observation = "";
        private int count;
        private long lastIssuedAt;
        private boolean reported;
    }
}
