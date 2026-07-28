package org.example.core;

import com.epicbot.api.shared.APIContext;

public interface ManagedF2PModule extends F2PModule {
    boolean isComplete(APIContext ctx);

    default int priority(APIContext ctx) {
        return 0;
    }
}
