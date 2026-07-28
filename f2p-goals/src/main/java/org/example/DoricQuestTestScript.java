package org.example;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.util.paint.PaintContext;
import org.example.core.F2PModule;
import org.example.core.ManagedF2PModule;
import org.example.core.ModuleTask;
import org.example.core.ScriptStats;
import org.example.core.runtime.CameraZoomController;
import org.example.core.runtime.LoopWatchdogController;
import org.example.core.runtime.RuntimeController;
import org.example.modules.GoalManagerModule;
import org.example.modules.questing.DoricQuestModule;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.List;

@ScriptManifest(name = "F2P Doric Test", gameType = GameType.OS)
public class DoricQuestTestScript extends Script {
    private static final String SCRIPT_VERSION = "v0.4.198-doric-direct-talk";

    private ScriptStats stats;

    @Override
    public boolean onStart(String... args) {
        stats = new ScriptStats();
        DoricQuestModule doricQuestModule = new DoricQuestModule(this::logInfo, stats);
        List<ManagedF2PModule> managedModules = List.of(doricQuestModule);
        F2PModule fallbackModule = doricQuestModule;
        List<RuntimeController> runtimeModules = List.of(
                new LoopWatchdogController(this::logInfo, stats, SCRIPT_VERSION),
                new CameraZoomController(this::logInfo)
        );

        logInfo("F2P Doric Test " + SCRIPT_VERSION + " started");
        logInfo("Test mode enabled: Doric's Quest only");
        addTask(new ModuleTask(
                this::getAPIContext,
                this::logInfo,
                runtimeModules,
                List.of(new GoalManagerModule(
                        this::logInfo,
                        stats,
                        managedModules,
                        fallbackModule
                ))
        ));
        return true;
    }

    @Override
    protected void onPaint(PaintContext paint, APIContext ctx) {
        if (stats == null || ctx == null) {
            return;
        }

        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 8;
        int width = 285;
        int height = 170;
        paint.fill(new Rectangle(x, y, width, height), new Color(18, 22, 28, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("F2P Doric Test " + SCRIPT_VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Task: " + shortText(stats.currentTask(), 32), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Phase: " + shortText(stats.internalPhase(), 32), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Next: " + shortText(stats.nextObjective(), 32), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status(), 36), x + 12, line, new Color(220, 235, 255), 12);
    }

    @Override
    protected void onStop() {
        if (stats != null) {
            stats.setStatus("F2P Doric Test stopped");
        }
        getLogger().info("F2P Doric Test " + SCRIPT_VERSION + " stopped");
    }

    private void logInfo(String message) {
        getLogger().info(message);
        if (stats != null) {
            stats.recordRelevantLog(message);
        }
    }

    private String shortText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
