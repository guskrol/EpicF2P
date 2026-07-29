package org.example.modules.questing;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.navigation.Navigation;

import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.function.Consumer;

public class RuneMysteriesQuestModule implements ManagedF2PModule {
    private static final Area DUKE_AREA = new Area(3204, 3218, 3214, 3226, 1);
    private static final Area SEDRIDOR_AREA = new Area(3102, 9566, 3112, 9577, 0);
    private static final Area AUBURY_AREA = new Area(3250, 3397, 3256, 3404, 0);

    private static final String AIR_TALISMAN = "Air talisman";
    private static final String RESEARCH_PACKAGE = "Research package";
    private static final String NOTES = "Notes";

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long nextTravelLogAt;
    private long nextRecoveryLogAt;

    public RuneMysteriesQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "quests.rune_mysteries";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.setTrainingSkill("Questing");

        if (isComplete(ctx)) {
            stats.setStatus("Rune Mysteries complete");
            return;
        }

        if (handleDialogue(ctx)) {
            return;
        }

        if (withdrawBankedQuestItem(ctx)) {
            return;
        }

        QuestStep step = nextStep(ctx);
        stats.setStatus("Rune Mysteries: " + step.status);

        switch (step) {
            case START_WITH_DUKE, RECOVER_FROM_DUKE -> talkToNpc(ctx, "Duke Horacio", DUKE_AREA, "Duke Horacio");
            case DELIVER_TALISMAN_TO_SEDRIDOR, RETURN_NOTES_TO_SEDRIDOR ->
                    talkToNpc(ctx, "Sedridor", SEDRIDOR_AREA, "Sedridor");
            case DELIVER_PACKAGE_TO_AUBURY -> talkToNpc(ctx, "Aubury", AUBURY_AREA, "Aubury");
        }
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(IQuestAPI.Quest.RUNE_MYSTERIES);
    }

    @Override
    public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0 : 500;
    }

    private QuestStep nextStep(APIContext ctx) {
        if (ctx.inventory().contains(NOTES)) {
            return QuestStep.RETURN_NOTES_TO_SEDRIDOR;
        }
        if (ctx.inventory().contains(RESEARCH_PACKAGE)) {
            return QuestStep.DELIVER_PACKAGE_TO_AUBURY;
        }
        if (ctx.inventory().contains(AIR_TALISMAN)) {
            return QuestStep.DELIVER_TALISMAN_TO_SEDRIDOR;
        }
        if (ctx.quests().isStarted(IQuestAPI.Quest.RUNE_MYSTERIES)) {
            return QuestStep.RECOVER_FROM_DUKE;
        }
        return QuestStep.START_WITH_DUKE;
    }

    private boolean handleDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }

        if (ctx.dialogues().canContinue()) {
            log("Rune Mysteries: continuing dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(500, 850);
            return true;
        }

        String[] preferredOptions = {
                "have you any quests",
                "i'm looking for the head wizard",
                "i am looking for the head wizard",
                "ok, here you are",
                "yes, certainly",
                "sure",
                "i'll",
                "i will",
                "yes"
        };

        for (String option : preferredOptions) {
            if (ctx.dialogues().hasOptionContaining(option)) {
                log("Rune Mysteries: selecting dialogue option containing '" + option + "'");
                ctx.dialogues().selectOption(text -> text != null
                        && text.toLowerCase(Locale.ROOT).contains(option));
                Time.sleep(600, 950);
                return true;
            }
        }

        if (!ctx.dialogues().getOptions().isEmpty()) {
            log("Rune Mysteries: selecting first non-negative dialogue option");
            ctx.dialogues().selectOption(text -> text != null && !isNegativeOption(text));
            Time.sleep(600, 950);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now >= nextRecoveryLogAt) {
            log("Rune Mysteries: dialogue open but no option/continue resolved; waiting");
            nextRecoveryLogAt = now + 5_000L;
        }
        return true;
    }

    private boolean withdrawBankedQuestItem(APIContext ctx) {
        if (ctx.inventory().contains(AIR_TALISMAN, RESEARCH_PACKAGE, NOTES)) {
            return false;
        }
        if (!ctx.quests().isStarted(IQuestAPI.Quest.RUNE_MYSTERIES)) {
            return false;
        }

        if (!ctx.bank().isOpen()) {
            if (!Navigation.isBankReachable(ctx)) {
                return false;
            }
            stats.setStatus("Rune Mysteries: checking bank for quest item");
            log("Rune Mysteries: opening bank to recover quest item");
            Navigation.openBank(ctx);
            Time.sleep(900, 1400, () -> ctx.bank().isOpen(), 100);
            return true;
        }

        for (String item : new String[]{AIR_TALISMAN, RESEARCH_PACKAGE, NOTES}) {
            if (ctx.bank().contains(item)) {
                stats.setStatus("Rune Mysteries: withdrawing " + item);
                log("Rune Mysteries: withdrawing " + item + " from bank");
                ctx.bank().withdraw(1, item);
                Time.sleep(700, 1100, () -> ctx.inventory().contains(item), 100);
                ctx.bank().close();
                return true;
            }
        }

        ctx.bank().close();
        Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
        return false;
    }

    private void talkToNpc(APIContext ctx, String npcName, Area area, String label) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 800, () -> !ctx.bank().isOpen(), 100);
            return;
        }
        if (ctx.widgets().isInterfaceOpen() && !ctx.dialogues().isDialogueOpen()) {
            ctx.widgets().closeInterface();
            Time.sleep(400, 700);
            return;
        }

        NPC npc = ctx.npcs()
                .query()
                .named(npcName)
                .actions("Talk-to")
                .reachable()
                .results()
                .nearest();

        if (npc == null) {
            npc = ctx.npcs()
                    .query()
                    .named(npcName)
                    .actions("Talk-to")
                    .results()
                    .nearest();
        }

        if (npc != null && npc.isValid() && npc.tileDistanceTo(ctx) <= 8) {
            stats.setStatus("Rune Mysteries: talking to " + label);
            log("Rune Mysteries: talking to " + label);
            ctx.camera().turnTo(npc);
            if (npc.interact("Talk-to")) {
                Time.sleep(1200, 2000,
                        () -> ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen(),
                        100);
            }
            return;
        }

        stats.setStatus("Rune Mysteries: walking to " + label);
        logTravel("Rune Mysteries: walking to " + label + " area");
        Navigation.walkToNoTeleports(ctx, area.getRandomTile());
        Time.sleep(700, 1100);
    }

    private boolean isNegativeOption(String option) {
        String lower = option.toLowerCase(Locale.ROOT);
        return lower.contains("no thanks")
                || lower.equals("no")
                || lower.contains("not right now")
                || lower.contains("don't")
                || lower.contains("do not");
    }

    private void logTravel(String message) {
        long now = System.currentTimeMillis();
        if (now >= nextTravelLogAt) {
            log(message);
            nextTravelLogAt = now + 5_000L;
        }
    }

    private void log(String message) {
        logger.accept(message);
    }

    private enum QuestStep {
        START_WITH_DUKE("talk to Duke Horacio"),
        DELIVER_TALISMAN_TO_SEDRIDOR("deliver Air talisman to Sedridor"),
        DELIVER_PACKAGE_TO_AUBURY("deliver research package to Aubury"),
        RETURN_NOTES_TO_SEDRIDOR("return notes to Sedridor"),
        RECOVER_FROM_DUKE("recover quest item from Duke");

        private final String status;

        QuestStep(String status) {
            this.status = status;
        }
    }
}
