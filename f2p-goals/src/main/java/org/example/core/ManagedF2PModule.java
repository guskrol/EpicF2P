package org.example.core;

import com.epicbot.api.shared.APIContext;

public interface ManagedF2PModule extends F2PModule {
    default void executeWithContext(APIContext ctx) {
        if (org.example.core.navigation.AccessRecovery.mustYield(ctx))
            throw new java.util.concurrent.CancellationException("Access recovery pending");
        if (org.example.core.navigation.AccessRecovery.isScopeBlocked(ctx, name())) return;
        org.example.core.navigation.AccessRecovery.beginScope(ctx, name());
        execute(ctx);
    }

    boolean isComplete(APIContext ctx);

    default int priority(APIContext ctx) {
        return 0;
    }

    default boolean isProtectedSubphase(APIContext ctx) {
        return false;
    }

    default String protectedSubphaseName(APIContext ctx) {
        return "protected subphase";
    }
}
