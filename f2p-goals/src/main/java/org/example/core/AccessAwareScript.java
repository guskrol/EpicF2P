package org.example.core;

import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.script.Script;
import org.example.core.navigation.AccessRecovery;

/** All project entry points feed the same refusal detector, including standalones. */
public abstract class AccessAwareScript extends Script {
    @Override protected void onChatMessage(ChatMessageEvent event) {
        AccessRecovery.onChatMessage(getAPIContext(), event);
    }
}
