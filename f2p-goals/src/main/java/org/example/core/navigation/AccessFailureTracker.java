package org.example.core.navigation;

import com.epicbot.api.shared.model.Tile;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Session-only evidence of refused actions. Object IDs alone are never unique targets. */
public final class AccessFailureTracker {
    public static final int REFUSAL_LIMIT = 3;
    private static final long WINDOW_MS = 45_000L;
    private static final long INTENT_MAX_AGE_MS = 15_000L;
    private final Map<String, Failure> failures = new HashMap<>();
    private final Map<String, Long> blockedScopes = new HashMap<>();
    private final Map<String, Long> blockedTargets = new HashMap<>();
    private Intent intent;
    private Denial pending;
    private String lastMessage = "";
    private long lastMessageAt = Long.MIN_VALUE;

    public record Intent(String scope, Tile target, int objectId, String action, long at) {
        String key() { return scope + ":" + position(target); }
    }
    public record Denial(Intent intent, String message, int count, long blockedUntil) { }
    private record Failure(int count, long lastAt) { }

    public synchronized void intend(String scope, Tile target, int objectId, String action, long now) {
        intent = new Intent(scope, target, objectId, action, now);
    }

    public synchronized void clearIntent() { intent = null; }

    public synchronized Denial record(String message, long now) {
        String normalized = normalize(message);
        if (!isAccessRefusal(normalized) || intent == null || intent.scope() == null
                || now - intent.at() > INTENT_MAX_AGE_MS || pending != null) return null;
        if (normalized.equals(lastMessage) && now - lastMessageAt < 350L) return null;
        lastMessage = normalized;
        lastMessageAt = now;
        if (isScopeBlocked(intent.scope(), now)) return null;
        failures.entrySet().removeIf(entry -> now - entry.getValue().lastAt() > WINDOW_MS);
        Failure previous = failures.get(intent.key());
        int count = previous == null ? 1 : previous.count() + 1;
        failures.put(intent.key(), new Failure(count, now));
        long until = now + (isRequirementRefusal(normalized) ? 30 * 60_000L : 5 * 60_000L);
        Denial result = new Denial(intent, normalized, count, until);
        if (count >= REFUSAL_LIMIT) {
            blockedScopes.put(intent.scope(), until);
            if (intent.target() != null) blockedTargets.put(position(intent.target()), until);
            failures.remove(intent.key());
            pending = result;
        }
        return result;
    }

    public synchronized boolean isScopeBlocked(String scope, long now) {
        blockedScopes.entrySet().removeIf(entry -> entry.getValue() <= now);
        return blockedScopes.containsKey(scope);
    }

    public synchronized boolean isTargetBlocked(Tile target, long now) {
        blockedTargets.entrySet().removeIf(entry -> entry.getValue() <= now);
        return target != null && blockedTargets.containsKey(position(target));
    }

    public synchronized boolean hasPending() { return pending != null; }
    public synchronized void blockScope(String scope, long until) { blockedScopes.put(scope, until); }
    public synchronized void rejectCurrent(String reason, long now) {
        if (intent == null || pending != null) return;
        long until = now + 5 * 60_000L;
        blockedScopes.put(intent.scope(), until);
        if (intent.target() != null) blockedTargets.put(position(intent.target()), until);
        pending = new Denial(intent, reason, REFUSAL_LIMIT, until);
    }
    public synchronized Denial takePending() {
        Denial result = pending;
        pending = null;
        return result;
    }

    public static boolean isAccessRefusal(String message) {
        String text = normalize(message);
        return isRequirementRefusal(text)
                || text.contains("can't reach") || text.contains("cannot reach")
                || text.contains("can't get there") || text.contains("cannot get there")
                || text.contains("can't go through") || text.contains("cannot go through")
                || text.contains("can't open this") || text.contains("cannot open this")
                || text.contains("can't open that") || text.contains("cannot open that")
                || text.contains("you can't access") || text.contains("you cannot access")
                || text.contains("you can't do that from here")
                || text.contains("no way through") || text.contains("unable to find a route")
                || ((text.contains("door") || text.contains("gate"))
                    && (text.contains("won't open") || text.contains("won't budge")
                        || text.contains("seems to be locked") || text.contains("unable to open")
                        || text.contains("only be opened from")));
    }

    private static boolean isRequirementRefusal(String text) {
        return text.contains("door is locked") || text.contains("gate is locked")
                || text.equals("it's locked.") || text.equals("it is locked.")
                || text.contains("you need a key") || text.contains("you'll need a key")
                || text.contains("you don't have permission") || text.contains("you do not have permission")
                || text.contains("you don't have access") || text.contains("you do not have access")
                || text.contains("you are not allowed") || text.contains("you aren't allowed")
                || text.contains("you can't enter") || text.contains("you cannot enter")
                || text.contains("you need to have completed") || text.contains("you must have completed")
                || text.contains("you need to have started") || text.contains("you must have started")
                || text.contains("you need to complete") || text.contains("you must complete")
                || text.contains("you don't meet the requirements") || text.contains("you do not meet the requirements")
                || (text.contains("member") && (text.contains("you need") || text.contains("you must")
                    || text.contains("only available") || text.contains("only be used")))
                || ((text.contains("to enter") || text.contains("to access") || text.contains("to pass"))
                    && (text.contains("you need") || text.contains("you require") || text.contains("you must")));
    }

    private static String normalize(String message) {
        return message == null ? "" : message.replaceAll("<[^>]+>", "")
                .replace('\u2019', '\'').toLowerCase(Locale.ROOT).trim();
    }

    private static String position(Tile tile) {
        return tile == null ? "unknown" : tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }
}
