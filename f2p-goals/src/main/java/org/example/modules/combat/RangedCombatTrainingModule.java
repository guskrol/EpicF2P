package org.example.modules.combat;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;
import org.example.core.ManagedF2PModule;

public class RangedCombatTrainingModule implements ManagedF2PModule {
    private static final int MIN_DEFENCE_LEVEL = 15;

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
    public int priority(APIContext ctx) {
        return Math.max(0, LumbridgeCowCombatModule.RANGED_TRAINING_CAP
                - ctx.skills().get(Skill.Skills.RANGED).getRealLevel());
    }
}
