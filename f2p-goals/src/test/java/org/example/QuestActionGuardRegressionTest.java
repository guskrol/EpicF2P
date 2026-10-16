package org.example;

import org.example.core.questing.QuestActionGuard;

import java.util.ArrayList;
import java.util.List;

public final class QuestActionGuardRegressionTest {
    public static void main(String[] args) {
        List<String> logs = new ArrayList<>();
        QuestActionGuard guard = new QuestActionGuard(logs::add);
        for (int i = 0; i < 3; i++)
            check(guard.allow("manhole", "closed", 0, 3), "expected bounded action " + i);
        check(!guard.allow("manhole", "closed", 0, 3), "unchanged state must stop retries");
        check(guard.isStalled("manhole"), "stalled action should be reportable");
        check(logs.size() == 1, "stall should be logged once");
        check(guard.allow("manhole", "open", 0, 3), "object state change must permit next action");
        check(guard.allow("sewer key", "visible", 0, 3), "a different action must be independent");
        guard.reset();
        check(guard.allow("manhole", "closed", 0, 3), "quest progress must reset the guard");
        System.out.println("PASS: quest actions stop after unchanged retries and resume on observed state change");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
