package org.example.core.runtime;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;

import java.util.function.Consumer;

public class AccountSkillCapController implements RuntimeController {
    private final SkillCapManager caps;
    private final ScriptStats stats;
    private final Consumer<String> logger;

    public AccountSkillCapController(SkillCapManager caps, ScriptStats stats, Consumer<String> logger) {
        this.caps = caps;
        this.stats = stats;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "runtime.account_skill_caps";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !caps.isInitializedForAccount(SkillCapManager.accountIdentifier(ctx));
    }

    @Override
    public void execute(APIContext ctx) {
        String account = SkillCapManager.accountIdentifier(ctx);
        if (account.isBlank()) {
            stats.setStatus("Waiting for account identity before selecting combat caps");
            Time.sleep(600, 900);
            return;
        }
        if (caps.initializeAccount(account)) {
            logger.accept(caps.describeCaps());
        }
    }
}
