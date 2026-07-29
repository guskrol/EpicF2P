package org.example;

import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import org.example.core.F2PModule;
import org.example.core.ManagedF2PModule;
import org.example.core.ModuleTask;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.runtime.AntibanController;
import org.example.core.runtime.BreakController;
import org.example.core.runtime.CameraZoomController;
import org.example.core.runtime.LoopWatchdogController;
import org.example.core.runtime.RuntimeController;
import org.example.core.runtime.WorldHopController;
import org.example.modules.GoalManagerModule;
import org.example.modules.combat.CombatTrainingModule;
import org.example.modules.combat.LumbridgeCowCombatModule;
import org.example.modules.combat.RangedCombatTrainingModule;
import org.example.modules.magic.MagicSplashingModule;
import org.example.modules.moneymaking.BeerGlassCollectorModule;
import org.example.modules.questing.CookAssistantQuestModule;
import org.example.modules.questing.RuneMysteriesQuestModule;
import org.example.modules.skilling.CraftingModule;
import org.example.modules.skilling.FishingCookingModule;
import org.example.modules.skilling.MiningSmithingModule;
import org.example.modules.skilling.WoodcuttingFiremakingModule;
import com.epicbot.api.shared.util.paint.PaintContext;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;

@ScriptManifest(name = "F2P Goals", gameType = GameType.OS)
public class F2PGoalsScript extends Script {
    private static final String SCRIPT_VERSION = "v0.4.213-crafting-funding-test";
    private static final boolean QUEST_TEST_ONLY = false;
    private static final boolean RANGED_TEST_ONLY = false;
    private static final boolean MAGIC_TEST_ONLY = false;
    private static final boolean BEER_GLASS_TEST_ONLY = false;
    private static final boolean FISHING_COOKING_TEST_ONLY = false;
    private static final boolean MINING_SMITHING_TEST_ONLY = false;
    private static final boolean MINING_SMITHING_BARS_TEST_ONLY = false;
    private static final boolean CRAFTING_TEST_ONLY = true;

    private ScriptStats stats;
    private SkillCapManager skillCaps;

    @Override
    public boolean onStart(String... args) {
        stats = new ScriptStats();
        skillCaps = new SkillCapManager();
        FishingCookingModule fishingCookingModule = new FishingCookingModule(
                this::logInfo,
                stats,
                skillCaps,
                FISHING_COOKING_TEST_ONLY
        );
        MiningSmithingModule miningSmithingModule = new MiningSmithingModule(
                this::logInfo,
                stats,
                skillCaps,
                MINING_SMITHING_BARS_TEST_ONLY
        );
        CraftingModule craftingModule = new CraftingModule(
                this::logInfo,
                stats,
                skillCaps
        );
        BeerGlassCollectorModule beerGlassCollectorModule = new BeerGlassCollectorModule(
                this::logInfo,
                stats
        );
        MagicSplashingModule magicModule = new MagicSplashingModule(
                this::logInfo,
                stats
        );
        CookAssistantQuestModule cookAssistantQuestModule = new CookAssistantQuestModule(
                this::logInfo,
                stats
        );
        List<ManagedF2PModule> managedModules;
        F2PModule fallbackModule;

        if (QUEST_TEST_ONLY) {
            managedModules = List.of(cookAssistantQuestModule);
            fallbackModule = cookAssistantQuestModule;
        } else if (RANGED_TEST_ONLY) {
            LumbridgeCowCombatModule rangedCombatModule = new LumbridgeCowCombatModule(
                    this::logInfo,
                    stats,
                    LumbridgeCowCombatModule.TrainingMode.RANGED
            );
            managedModules = List.of(new RangedCombatTrainingModule(rangedCombatModule));
            fallbackModule = rangedCombatModule;
        } else if (MAGIC_TEST_ONLY) {
            managedModules = List.of(magicModule);
            fallbackModule = magicModule;
        } else if (BEER_GLASS_TEST_ONLY) {
            managedModules = List.of(beerGlassCollectorModule);
            fallbackModule = beerGlassCollectorModule;
        } else if (FISHING_COOKING_TEST_ONLY) {
            managedModules = List.of(fishingCookingModule);
            fallbackModule = fishingCookingModule;
        } else if (MINING_SMITHING_TEST_ONLY) {
            managedModules = List.of(miningSmithingModule);
            fallbackModule = miningSmithingModule;
        } else if (CRAFTING_TEST_ONLY) {
            managedModules = List.of(craftingModule);
            fallbackModule = craftingModule;
        } else {
            LumbridgeCowCombatModule combatModule = new LumbridgeCowCombatModule(this::logInfo, stats);
            LumbridgeCowCombatModule rangedCombatModule = new LumbridgeCowCombatModule(
                    this::logInfo,
                    stats,
                    LumbridgeCowCombatModule.TrainingMode.RANGED
            );
            managedModules = List.of(
                    new CombatTrainingModule(combatModule, skillCaps),
                    new RangedCombatTrainingModule(rangedCombatModule),
                    magicModule,
                    cookAssistantQuestModule,
                    new WoodcuttingFiremakingModule(this::logInfo, stats, skillCaps),
                    fishingCookingModule,
                    miningSmithingModule,
                    craftingModule,
                    beerGlassCollectorModule
            );
            fallbackModule = combatModule;
        }

        logInfo("F2P Goals " + SCRIPT_VERSION + " started");
        if (QUEST_TEST_ONLY) {
            logInfo("Test mode enabled: Cook's Assistant quest only");
        }
        if (RANGED_TEST_ONLY) {
            logInfo("Test mode enabled: Ranged combat only");
        }
        if (MAGIC_TEST_ONLY) {
            logInfo("Test mode enabled: Magic Wind Strike to 13, Fire Strike to 19, then Curse splashing");
        }
        if (BEER_GLASS_TEST_ONLY) {
            logInfo("Test mode enabled: Beer glass collector only");
        }
        if (FISHING_COOKING_TEST_ONLY) {
            logInfo("Test mode enabled: Fishing/Cooking only");
        }
        if (MINING_SMITHING_TEST_ONLY) {
            logInfo("Test mode enabled: "
                    + (MINING_SMITHING_BARS_TEST_ONLY ? "Bronze bars only" : "Mining/Smithing only"));
        }
        if (CRAFTING_TEST_ONLY) {
            logInfo("Test mode enabled: Crafting only");
        }
        logInfo(skillCaps.describeCaps());
        GoalManagerModule goalManagerModule = new GoalManagerModule(
                this::logInfo,
                stats,
                managedModules,
                fallbackModule
        );
        boolean lightweightTestMode = QUEST_TEST_ONLY || CRAFTING_TEST_ONLY;
        List<RuntimeController> runtimeModules = lightweightTestMode
                ? List.of(
                        new LoopWatchdogController(this::logInfo, stats, SCRIPT_VERSION, goalManagerModule::requestReroll),
                        new CameraZoomController(this::logInfo)
                )
                : List.of(
                        new LoopWatchdogController(this::logInfo, stats, SCRIPT_VERSION, goalManagerModule::requestReroll),
                        new CameraZoomController(this::logInfo),
                        BreakController.normal(this::logInfo, stats),
                        BreakController.micro(this::logInfo, stats),
                        new WorldHopController(this::logInfo),
                        new AntibanController(this::logInfo)
                );
        addTask(new ModuleTask(
                this::getAPIContext,
                this::logInfo,
                runtimeModules,
                List.of(goalManagerModule)
        ));
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (stats == null || event == null || event.getMessage() == null) {
            return;
        }

        String message = event.getMessage()
                .toLowerCase()
                .replace('\u2019', '\'');
        if (message.contains("can't light a fire here")) {
            stats.recordFiremakingBlockedMessage();
            logInfo("Detected blocked Firemaking message: " + event.getMessage());
        }
        if (message.contains("there is currently no ore available in this rock")) {
            stats.recordMiningNoOreMessage();
            logInfo("Detected depleted Mining rock message: " + event.getMessage());
        }
        if (message.contains("no ammo left in your quiver")) {
            stats.recordRangedNoAmmoMessage();
            logInfo("Detected empty Ranged quiver message: " + event.getMessage());
        }
        if (message.contains("already under attack") || message.contains("someone else is fighting")) {
            stats.recordAlreadyUnderAttackMessage();
            logInfo("Detected already-under-attack message: " + event.getMessage());
        }
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        if (stats == null || ctx == null) {
            return;
        }

        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 8;
        int width = 285;
        int height = 356;
        paint.fill(new Rectangle(x, y, width, height), new Color(18, 22, 28, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("F2P Goals " + SCRIPT_VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Task: " + shortText(stats.currentTask(), 32), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Phase: " + stats.internalPhase(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Next: " + shortText(stats.nextObjective(), 34), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Task left: " + stats.goalRemainingText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Kills: " + stats.kills(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Training: " + stats.trainingSkill(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Goal: " + stats.goalText(), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Caps: " + capText(ctx), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Melee: " + meleeCapText(ctx), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("GE: " + (stats.isGeRestricted() ? "restricted" : "available"), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("XP: " + stats.xpGained(ctx) + " (" + stats.xpPerHour(ctx) + "/h)", x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("GP est.: " + stats.estimatedGold() + " (" + stats.goldPerHour() + "/h)", x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Coins looted: " + stats.coinsLooted(), x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Cowhide: " + stats.cowhidesLooted() + " | Loots: " + stats.itemsLooted(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Feathers: " + stats.feathersLooted(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Bones buried: " + stats.bonesBuried(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Funding: " + shortText(stats.lastFundingReason(), 34), x + 12, line, new Color(245, 228, 160), 11);
        line += 16;
        paint.drawText("Last err: " + shortText(stats.lastRecoverableError(), 34), x + 12, line, new Color(255, 190, 180), 11);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status(), 36), x + 12, line, new Color(195, 210, 230), 11);
    }

    @Override
    protected void onStop() {
        clearClientInteractionState();
        getLogger().info("F2P Goals " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        clearClientInteractionState();
    }

    private void clearClientInteractionState() {
        APIContext ctx = getAPIContext();
        if (ctx == null) {
            return;
        }

        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only; stopping must not throw.
        }
    }

    private void logInfo(String message) {
        if (stats != null) {
            stats.recordRelevantLog(message);
        }
        getLogger().info(message);
    }

    private String capText(APIContext ctx) {
        if (skillCaps == null) {
            return "-";
        }

        return "WC " + ctx.skills().woodcutting().getRealLevel() + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.WOODCUTTING)
                + " Fsh " + ctx.skills().fishing().getRealLevel() + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.FISHING)
                + " Min " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.MINING).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.MINING)
                + " Sm " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.SMITHING).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.SMITHING)
                + " Cr " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.CRAFTING).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.CRAFTING);
    }

    private String meleeCapText(APIContext ctx) {
        if (skillCaps == null) {
            return "-";
        }

        return "A " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.ATTACK).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.ATTACK)
                + " S " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.STRENGTH).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.STRENGTH)
                + " D " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.DEFENCE).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.DEFENCE)
                + " R " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.RANGED).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.RANGED)
                + " M " + ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.MAGIC).getRealLevel()
                + "/" + skillCaps.capFor(com.epicbot.api.shared.model.Skill.Skills.MAGIC);
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }
}
