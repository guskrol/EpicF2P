package org.example.core;

import com.epicbot.api.shared.APIContext;

public interface F2PModule {
    String name();

    boolean shouldExecute(APIContext ctx);

    void execute(APIContext ctx);
}
