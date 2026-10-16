package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import org.example.core.AccessAwareScript;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.util.paint.PaintContext;
import org.example.core.ModuleTask;
import org.example.core.ScriptStats;
import org.example.core.runtime.DeathRecoveryController;
import org.example.modules.questing.IdesOfMilkQuestModule;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;

@ScriptManifest(name = "F2P The Ides of Milk", gameType = GameType.OS)
public final class IdesOfMilkScript extends AccessAwareScript {
    private static final String VERSION = "v0.4.400-dialogue-liveness";
    private ScriptStats stats;

    @Override public boolean onStart(String... args) {
        stats = new ScriptStats();
        log("The Ides of Milk " + VERSION + " started");
        addTask(new ModuleTask(this::getAPIContext, this::log,
                List.of(new DeathRecoveryController(this::log, stats)),
                List.of(new IdesOfMilkQuestModule(this::log, stats))));
        return true;
    }

    @Override protected void onPaint(PaintContext paint, APIContext ctx) {
        if (stats == null || ctx == null) return;
        paint.fill(new Rectangle(8, 8, 360, 75), new Color(18, 22, 28, 190));
        paint.drawText("The Ides of Milk " + VERSION, 18, 30, Color.WHITE, 13);
        paint.drawText(stats.status(), 18, 52, Color.WHITE, 11);
    }

    @Override protected void onStop() { log("The Ides of Milk " + VERSION + " stopped"); }
    private void log(String message) { getLogger().info(message); if (stats != null) stats.recordRelevantLog(message); }
}
