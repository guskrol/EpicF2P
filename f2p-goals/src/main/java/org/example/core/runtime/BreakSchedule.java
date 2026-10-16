package org.example.core.runtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Session-only schedule. The activity clock advances only while logged in and working. */
public final class BreakSchedule {
    public enum Type {
        MICRO("Micro", false, 45, 120),
        NORMAL("Normal", true, 600, 1200),
        BIO("Bio", true, 240, 420),
        LUNCH("Almoço", true, 1200, 1800),
        DINNER("Janta", true, 1200, 1800),
        SLEEP("Sleeping", true, 18000, 25200);

        public final String label;
        public final boolean logout;
        private final int minSeconds;
        private final int maxSeconds;

        Type(String label, boolean logout, int minSeconds, int maxSeconds) {
            this.label = label;
            this.logout = logout;
            this.minSeconds = minSeconds;
            this.maxSeconds = maxSeconds;
        }
    }

    private static final long MIN_GAP_MS = 5 * 60_000L;
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");
    private final Map<Type, Long> dueActivity = new EnumMap<>(Type.class);
    private final Map<Type, Long> mealAt = new EnumMap<>(Type.class);
    private long activityMs;
    private long lastWallMs;
    private long lastBreakActivityMs = -MIN_GAP_MS;
    private long endAt;
    private Type active;
    private boolean waitingForLogin;

    public BreakSchedule(long now) {
        lastWallMs = now;
        scheduleCycle();
        scheduleMeal(Type.LUNCH, now);
        scheduleMeal(Type.DINNER, now);
    }

    public void update(long now, boolean loggedIn) {
        long delta = Math.max(0L, now - lastWallMs);
        if (active == null && !waitingForLogin && loggedIn) {
            activityMs += delta;
        }
        lastWallMs = now;
    }

    public Type due(long now) {
        if (active != null || waitingForLogin || activityMs - lastBreakActivityMs < MIN_GAP_MS) {
            return null;
        }
        if (activityMs >= dueActivity.get(Type.SLEEP)) return Type.SLEEP;
        if (now >= mealAt.get(Type.LUNCH)) return Type.LUNCH;
        if (now >= mealAt.get(Type.DINNER)) return Type.DINNER;
        if (activityMs >= dueActivity.get(Type.NORMAL)) return Type.NORMAL;
        if (activityMs >= dueActivity.get(Type.BIO)) return Type.BIO;
        if (activityMs >= dueActivity.get(Type.MICRO)) return Type.MICRO;
        return null;
    }

    public long start(Type type, long now) {
        active = type;
        endAt = now + random(type.minSeconds, type.maxSeconds) * 1000L;
        return endAt;
    }

    public boolean finishIfElapsed(long now) {
        if (active == null || now < endAt) return false;
        Type finished = active;
        active = null;
        endAt = 0L;
        waitingForLogin = true;
        lastBreakActivityMs = activityMs;
        if (finished == Type.SLEEP) {
            activityMs = 0L;
            lastBreakActivityMs = 0L;
            scheduleCycle();
        } else if (finished == Type.MICRO || finished == Type.NORMAL || finished == Type.BIO) {
            schedule(finished);
        } else {
            scheduleMeal(finished, now + 1);
        }
        return true;
    }

    public boolean resumeIfReady(boolean loggedIn) {
        if (!waitingForLogin || !loggedIn) return false;
        waitingForLogin = false;
        return true;
    }

    public Type active() { return active; }
    public boolean waitingForLogin() { return waitingForLogin; }
    public long remainingMs(long now) { return Math.max(0L, endAt - now); }
    public long activityMs() { return activityMs; }

    private void scheduleCycle() {
        schedule(Type.MICRO);
        schedule(Type.NORMAL);
        schedule(Type.BIO);
        schedule(Type.SLEEP);
    }

    private void schedule(Type type) {
        long seconds = switch (type) {
            case MICRO -> random(20 * 60, 45 * 60);
            case NORMAL -> random(60 * 60, 150 * 60);
            case BIO -> random(2 * 60 * 60, 4 * 60 * 60);
            case SLEEP -> random(12 * 60 * 60, 15 * 60 * 60);
            default -> throw new IllegalArgumentException(type.name());
        };
        dueActivity.put(type, activityMs + seconds * 1000L);
    }

    private void scheduleMeal(Type type, long now) {
        ZonedDateTime local = Instant.ofEpochMilli(now).atZone(BRAZIL);
        int hour = type == Type.LUNCH ? 12 : 18;
        LocalDate date = local.toLocalDate();
        LocalTime windowStart = LocalTime.of(hour, 0);
        int earliest = local.getHour() == hour ? local.getMinute() * 60 + local.getSecond() : 0;
        LocalTime time = windowStart.plusSeconds(random(earliest, 59 * 60 + 59));
        long target = LocalDateTime.of(date, time).atZone(BRAZIL).toInstant().toEpochMilli();
        if (target < now) target = LocalDateTime.of(date.plusDays(1), time).atZone(BRAZIL).toInstant().toEpochMilli();
        mealAt.put(type, target);
    }

    private static long random(long min, long max) {
        return ThreadLocalRandom.current().nextLong(min, max + 1L);
    }
}
