package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.IQuestAPI;

import java.util.List;

public final class F2PQuestPlan {
    public static final int TARGET_QUEST_POINTS = 10;

    public static final List<QuestStep> ROBUST_10_QP_ROUTE = List.of(
            new QuestStep("cooks_assistant", "Cook's Assistant",
                    IQuestAPI.Quest.COOKS_ASSISTANT, 1, Complexity.EASY,
                    "dialogue and simple item collection"),
            new QuestStep("dorics_quest", "Doric's Quest",
                    IQuestAPI.Quest.DORICS_QUEST, 1, Complexity.EASY,
                    "banked ores or mining module handoff"),
            new QuestStep("sheep_shearer", "Sheep Shearer",
                    IQuestAPI.Quest.SHEEP_SHEARER, 1, Complexity.EASY,
                    "repeatable item collection plus spinning"),
            new QuestStep("rune_mysteries", "Rune Mysteries",
                    IQuestAPI.Quest.RUNE_MYSTERIES, 1, Complexity.EASY,
                    "dialogue and short NPC route"),
            new QuestStep("romeo_and_juliet", "Romeo & Juliet",
                    IQuestAPI.Quest.ROMEO_AND_JULIET, 5, Complexity.EASY,
                    "high QP dialogue route"),
            new QuestStep("x_marks_the_spot", "X Marks the Spot",
                    IQuestAPI.Quest.X_MARKS_THE_SPOT, 1, Complexity.MEDIUM,
                    "spade, dig tiles and reward lamp")
    );

    public static final List<QuestStep> FAST_10_QP_ROUTE = List.of(
            new QuestStep("romeo_and_juliet", "Romeo & Juliet",
                    IQuestAPI.Quest.ROMEO_AND_JULIET, 5, Complexity.EASY,
                    "fast 5 QP dialogue route"),
            new QuestStep("goblin_diplomacy", "Goblin Diplomacy",
                    IQuestAPI.Quest.GOBLIN_DIPLOMACY, 5, Complexity.MEDIUM,
                    "dyes and goblin mail setup")
    );

    private F2PQuestPlan() {
    }

    public static int completedPlannedQuestPoints(APIContext ctx) {
        int points = 0;
        for (QuestStep step : ROBUST_10_QP_ROUTE) {
            if (isCompleted(ctx, step)) {
                points += step.questPoints();
            }
        }
        return points;
    }

    public static QuestStep nextRobustQuest(APIContext ctx) {
        for (QuestStep step : ROBUST_10_QP_ROUTE) {
            if (!isCompleted(ctx, step)) {
                return step;
            }
        }
        return null;
    }

    public static boolean isTargetReached(APIContext ctx) {
        return ctx.quests().getQuestPoints() >= TARGET_QUEST_POINTS
                || completedPlannedQuestPoints(ctx) >= TARGET_QUEST_POINTS;
    }

    private static boolean isCompleted(APIContext ctx, QuestStep step) {
        return ctx != null
                && step != null
                && step.quest() != null
                && ctx.quests().isCompleted(step.quest());
    }

    public enum Complexity {
        EASY,
        MEDIUM
    }

    public record QuestStep(
            String id,
            String displayName,
            IQuestAPI.Quest quest,
            int questPoints,
            Complexity complexity,
            String implementationNote
    ) {
    }
}
