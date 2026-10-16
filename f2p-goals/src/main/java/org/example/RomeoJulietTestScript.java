package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import org.example.core.AccessAwareScript;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.util.paint.PaintContext;
import org.example.core.F2PModule;
import org.example.core.ModuleTask;
import org.example.core.ScriptStats;
import org.example.core.runtime.CameraZoomController;
import org.example.core.runtime.DeathRecoveryController;
import org.example.core.runtime.LoopWatchdogController;
import org.example.modules.questing.RomeoJulietQuestModule;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;

@ScriptManifest(name = "F2P Romeo & Juliet Test", gameType = GameType.OS)
public final class RomeoJulietTestScript extends AccessAwareScript {
    private static final String VERSION = "v0.4.400-dialogue-liveness";
    private ScriptStats stats;

    @Override public boolean onStart(String... args) {
        stats = new ScriptStats();
        RomeoJulietQuestModule quest = new RomeoJulietQuestModule(this::log, stats);
        F2PModule standalone = new F2PModule() {
            @Override public String name() { return quest.name(); }
            @Override public boolean shouldExecute(APIContext ctx) { return true; }
            @Override public void execute(APIContext ctx) { quest.execute(ctx); }
        };
        log("Romeo & Juliet " + VERSION + " started");
        addTask(new ModuleTask(this::getAPIContext, this::log,
                List.of(new DeathRecoveryController(this::log, stats),
                        new LoopWatchdogController(this::log, stats, VERSION),
                        new CameraZoomController(this::log)), List.of(standalone)));
        return true;
    }

    @Override protected void onPaint(PaintContext paint, APIContext ctx) {
        if (stats == null || ctx == null) return;
        paint.fill(new Rectangle(8, 8, 460, 82), new Color(18, 22, 28, 190));
        paint.drawText("Romeo & Juliet " + VERSION, 18, 30, Color.WHITE, 13);
        paint.drawText(stats.status(), 18, 55, Color.WHITE, 11);
    }

    @Override protected void onStop() { log("Romeo & Juliet " + VERSION + " stopped"); }
    private void log(String message) {
        getLogger().info(message);
        if (stats != null) stats.recordRelevantLog(message);
    }
}











