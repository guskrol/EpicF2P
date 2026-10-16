package org.example;

import org.example.core.runtime.BreakSchedule;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class BreakScheduleRegressionTest {
    public static void main(String[] args) {
        long start = LocalDateTime.of(2026, 10, 10, 0, 0)
                .atZone(ZoneId.of("America/Sao_Paulo")).toInstant().toEpochMilli();
        BreakSchedule schedule = new BreakSchedule(start);
        schedule.update(start + 46 * 60_000L, true);
        assertEquals(BreakSchedule.Type.MICRO, schedule.due(start + 46 * 60_000L));
        long end = schedule.start(BreakSchedule.Type.MICRO, start + 46 * 60_000L);
        long duration = end - start - 46 * 60_000L;
        assertTrue(duration >= 45_000L && duration <= 120_000L, "micro duration");
        schedule.update(end, false);
        assertTrue(schedule.finishIfElapsed(end), "break finished");
        assertEquals(null, schedule.due(end));
        schedule.update(end + 3_600_000L, false);
        assertEquals(46 * 60_000L, schedule.activityMs());
        assertTrue(schedule.resumeIfReady(true), "resume on login");
        schedule.update(end + 3_600_000L + 4 * 60_000L, true);
        assertEquals(null, schedule.due(end + 3_600_000L + 4 * 60_000L));
        schedule.update(end + 3_600_000L + 5 * 60_000L, true);
        assertTrue(schedule.due(end + 3_600_000L + 5 * 60_000L) != BreakSchedule.Type.MICRO,
                "micro rescheduled from break end");
        schedule.update(end + 3_600_000L + 16 * 3_600_000L, true);
        assertEquals(BreakSchedule.Type.SLEEP, schedule.due(end + 3_600_000L + 16 * 3_600_000L));
        System.out.println("PASS: activity clock, logout exclusion, duration, gap, reschedule and sleeping priority");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
    private static void assertTrue(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
