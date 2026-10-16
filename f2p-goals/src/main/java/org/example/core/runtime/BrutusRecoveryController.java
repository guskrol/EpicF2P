package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import org.example.modules.combat.LumbridgeCowCombatModule;

/** Keeps grave retrieval and the immediate return to Brutus ahead of goal rerolls. */
public final class BrutusRecoveryController implements RuntimeController {
    private final LumbridgeCowCombatModule combat;

    public BrutusRecoveryController(LumbridgeCowCombatModule combat) {
        this.combat = combat;
    }

    @Override public String name() { return "runtime.brutus_recovery"; }

    @Override public boolean shouldExecute(APIContext ctx) {
        return ctx != null && combat.needsBrutusRecoveryPriority();
    }

    @Override public void execute(APIContext ctx) {
        combat.execute(ctx);
    }
}
