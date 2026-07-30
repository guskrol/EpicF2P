package org.example;

import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.model.Skill;
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
    private static final String SCRIPT_VERSION = "v0.4.228-beer-glass-flex-search";
    private static final boolean QUEST_TEST_ONLY = false;
    private static final boolean RANGED_TEST_ONLY = false;
    private static final boolean MAGIC_TEST_ONLY = false;
    private static final boolean BEER_GLASS_TEST_ONLY = false;
    private static final boolean FISHING_COOKING_TEST_ONLY = false;
    private static final boolean MINING_SMITHING_TEST_ONLY = false;
    private static final boolean MINING_SMITHING_BARS_TEST_ONLY = false;
    private static final boolean CRAFTING_TEST_ONLY = false;

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

        boolean hasFunding = hasValue(stats.lastFundingReason());
        boolean hasError = hasValue(stats.lastRecoverableError());
        boolean hasStatus = hasValue(stats.status());
        int x = 8;
        int y = 8;
        int width = 318;
        int height = 229
                + (hasFunding ? 15 : 0)
                + (hasError ? 15 : 0)
                + (hasStatus ? 15 : 0);
        Color panel = new Color(12, 15, 20, 188);
        Color header = new Color(24, 34, 43, 220);
        Color border = new Color(160, 178, 194, 190);
        Color primary = new Color(236, 242, 248);
        Color muted = new Color(177, 195, 212);
        Color accent = new Color(96, 190, 170);
        Color gold = new Color(241, 211, 118);
        Color danger = new Color(255, 166, 150);

        paint.fill(new Rectangle(x, y, width, height), panel);
        paint.fill(new Rectangle(x, y, width, 30), header);
        paint.draw(new Rectangle(x, y, width, height), border, 1);

        int line = y + 20;
        paint.drawText("F2P Goals", x + 12, line, Color.WHITE, 14);
        paint.drawText(SCRIPT_VERSION, x + 108, line, muted, 10);

        line = y + 48;
        paint.drawText(shortText(stats.currentTask() + " | " + stats.internalPhase(), 43), x + 12, line, primary, 12);
        line += 15;
        paint.drawText("Next: " + shortText(stats.nextObjective(), 43), x + 12, line, muted, 11);
        line += 15;
        paint.drawText("Time: run " + stats.runtimeText()
                + " | left " + stats.goalRemainingText(), x + 12, line, muted, 11);

        line += 20;
        paint.drawText("Skills", x + 12, line, accent, 11);
        line += 15;
        paint.drawText("Combat " + combatCapText(ctx), x + 12, line, primary, 11);
        line += 15;
        paint.drawText("Gather " + gatheringCapText(ctx), x + 12, line, primary, 11);
        line += 15;
        paint.drawText("Make " + productionCapText(ctx), x + 12, line, primary, 11);
        line += 15;
        paint.drawText("Training " + stats.trainingSkill()
                + " | Kills " + stats.kills(), x + 12, line, muted, 11);

        line += 20;
        paint.drawText("Economy", x + 12, line, accent, 11);
        line += 15;
        paint.drawText("GE " + (stats.isGeRestricted() ? "restricted" : "available")
                + " | XP " + stats.xpGained(ctx) + " (" + stats.xpPerHour(ctx) + "/h)", x + 12, line, primary, 11);
        line += 15;
        paint.drawText("Gold " + stats.estimatedGold() + " (" + stats.goldPerHour() + "/h)"
                + " | Coins +" + stats.coinsLooted(), x + 12, line, gold, 11);
        line += 15;
        paint.drawText("Loot hides " + stats.cowhidesLooted()
                + " | items " + stats.itemsLooted()
                + " | bones " + stats.bonesBuried(), x + 12, line, muted, 11);

        if (hasFunding || hasError || hasStatus) {
            line += 20;
            paint.drawText("Alerts", x + 12, line, accent, 11);
        }
        if (hasFunding) {
            line += 15;
            paint.drawText("Funding: " + shortText(stats.lastFundingReason(), 42), x + 12, line, gold, 11);
        }
        if (hasError) {
            line += 15;
            paint.drawText("Last err: " + shortText(stats.lastRecoverableError(), 42), x + 12, line, danger, 11);
        }
        if (hasStatus) {
            line += 15;
            paint.drawText("Status: " + shortText(stats.status(), 43), x + 12, line, muted, 11);
        }
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

    private String gatheringCapText(APIContext ctx) {
        if (skillCaps == null) {
            return "-";
        }

        return "WC " + levelCap(ctx, Skill.Skills.WOODCUTTING)
                + " | Fish " + levelCap(ctx, Skill.Skills.FISHING)
                + " | Mine " + levelCap(ctx, Skill.Skills.MINING);
    }

    private String productionCapText(APIContext ctx) {
        if (skillCaps == null) {
            return "-";
        }

        return "FM " + levelCap(ctx, Skill.Skills.FIREMAKING)
                + " | Cook " + levelCap(ctx, Skill.Skills.COOKING)
                + " | Smith " + levelCap(ctx, Skill.Skills.SMITHING)
                + " | Cr " + levelCap(ctx, Skill.Skills.CRAFTING);
    }

    private String combatCapText(APIContext ctx) {
        if (skillCaps == null) {
            return "-";
        }

        return "A " + levelCap(ctx, Skill.Skills.ATTACK)
                + " | S " + levelCap(ctx, Skill.Skills.STRENGTH)
                + " | D " + levelCap(ctx, Skill.Skills.DEFENCE)
                + " | R " + levelCap(ctx, Skill.Skills.RANGED)
                + " | M " + levelCap(ctx, Skill.Skills.MAGIC);
    }

    private String levelCap(APIContext ctx, Skill.Skills skill) {
        return ctx.skills().get(skill).getRealLevel() + "/" + skillCaps.capFor(skill);
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank() && !"-".equals(value.trim());
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
