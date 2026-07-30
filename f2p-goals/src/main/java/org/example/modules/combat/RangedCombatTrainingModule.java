package org.example.modules.combat;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;
import org.example.core.ManagedF2PModule;

public class RangedCombatTrainingModule implements ManagedF2PModule {
    private static final int MIN_DEFENCE_LEVEL = 15;
    private static final int MIN_CATCH_UP_TARGET_LEVEL = 20;
    private static final int CATCH_UP_PRIORITY_BOOST = 90;

    private final LumbridgeCowCombatModule combatModule;

    public RangedCombatTrainingModule(LumbridgeCowCombatModule combatModule) {
        this.combatModule = combatModule;
    }

    @Override
    public String name() {
        return "combat.ranged";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx)
                && ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel() >= MIN_DEFENCE_LEVEL
                && combatModule.shouldExecute(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        combatModule.execute(ctx);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return ctx.skills().get(Skill.Skills.RANGED).getRealLevel()
                >= LumbridgeCowCombatModule.RANGED_TRAINING_CAP;
    }

    @Override
    public boolean isProtectedSubphase(APIContext ctx) {
        return combatModule.isProtectedFundingSubphase();
    }

    @Override
    public String protectedSubphaseName(APIContext ctx) {
        return combatModule.protectedFundingLabel();
    }

    @Override
    public int priority(APIContext ctx) {
        int rangedLevel = ctx.skills().get(Skill.Skills.RANGED).getRealLevel();
        int remaining = Math.max(0, LumbridgeCowCombatModule.RANGED_TRAINING_CAP - rangedLevel);
        int catchUpTarget = catchUpTarget(ctx);
        if (rangedLevel < catchUpTarget) {
            return CATCH_UP_PRIORITY_BOOST + Math.max(1, catchUpTarget - rangedLevel);
        }
        return remaining;
    }

    private int catchUpTarget(APIContext ctx) {
        int defenceLevel = ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel();
        if (defenceLevel < MIN_DEFENCE_LEVEL) {
            return 0;
        }

        int target = Math.max(MIN_CATCH_UP_TARGET_LEVEL, defenceLevel + 5);
        return Math.min(LumbridgeCowCombatModule.RANGED_TRAINING_CAP, target);
    }
}
