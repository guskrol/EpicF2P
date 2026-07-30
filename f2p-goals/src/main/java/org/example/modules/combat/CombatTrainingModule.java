package org.example.modules.combat;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;
import org.example.core.ManagedF2PModule;
import org.example.core.SkillCapManager;

public class CombatTrainingModule implements ManagedF2PModule {
    private final LumbridgeCowCombatModule combatModule;
    private final SkillCapManager caps;

    public CombatTrainingModule(LumbridgeCowCombatModule combatModule, SkillCapManager caps) {
        this.combatModule = combatModule;
        this.caps = caps;
    }

    @Override
    public String name() {
        return "combat.melee";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx) && combatModule.shouldExecute(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        combatModule.execute(ctx);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return caps.isComplete(ctx, Skill.Skills.ATTACK)
                && caps.isComplete(ctx, Skill.Skills.STRENGTH)
                && caps.isComplete(ctx, Skill.Skills.DEFENCE);
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
        return caps.levelsRemaining(ctx, Skill.Skills.ATTACK)
                + caps.levelsRemaining(ctx, Skill.Skills.STRENGTH)
                + caps.levelsRemaining(ctx, Skill.Skills.DEFENCE);
    }
}
