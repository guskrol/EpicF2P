package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class F2PQuestingModule implements ManagedF2PModule {
    private static final boolean EXECUTION_ENABLED = false;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final AtomicBoolean scaffoldLogged = new AtomicBoolean();

    public F2PQuestingModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "quests.f2p_10qp";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return EXECUTION_ENABLED && !isComplete(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.setTrainingSkill("Questing");
        F2PQuestPlan.QuestStep next = F2PQuestPlan.nextRobustQuest(ctx);
        String nextName = next == null ? "none" : next.displayName();
        stats.setStatus("Quest scaffold ready; next=" + nextName);
        if (scaffoldLogged.compareAndSet(false, true)) {
            logger.accept("F2P questing scaffold ready for 10 QP route; next=" + nextName);
        }
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return F2PQuestPlan.isTargetReached(ctx);
    }

    @Override
    public int priority(APIContext ctx) {
        return Math.max(0, F2PQuestPlan.TARGET_QUEST_POINTS - ctx.quests().getQuestPoints()) * 12;
    }
}
