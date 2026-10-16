package org.example.modules;

import com.epicbot.api.shared.APIContext;
import org.example.core.ManagedF2PModule;

import java.util.List;

public final class GoalCategoryRegressionTest {
    public static void main(String[] args) {
        ManagedF2PModule quest = module("quests.cooks_assistant");
        ManagedF2PModule combat = module("combat.melee");
        ManagedF2PModule skill = module("skills.fishing_cooking");
        ManagedF2PModule funding = module("money.beer_glass");

        List<ManagedF2PModule> all = List.of(quest, combat, skill, funding);
        assertEquals(List.of(quest), GoalManagerModule.chooseCategoryCandidates(all, true));
        assertEquals(List.of(combat, skill, funding), GoalManagerModule.chooseCategoryCandidates(all, false));
        assertEquals(List.of(quest), GoalManagerModule.chooseCategoryCandidates(List.of(quest), false));
        assertEquals(List.of(combat, skill), GoalManagerModule.chooseCategoryCandidates(List.of(combat, skill), true));
        System.out.println("PASS: 50/50 goal category pools and unavailable-category fallback");
    }

    private static ManagedF2PModule module(String name) {
        return new ManagedF2PModule() {
            @Override public String name() { return name; }
            @Override public boolean shouldExecute(APIContext ctx) { return true; }
            @Override public boolean isComplete(APIContext ctx) { return false; }
            @Override public void execute(APIContext ctx) { }
        };
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
}
