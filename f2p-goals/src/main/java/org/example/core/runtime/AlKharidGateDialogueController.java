package org.example.core.runtime;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.function.Consumer;

public class AlKharidGateDialogueController implements RuntimeController {
    private static final Area AL_KHARID_TOLL_GATE_AREA = new Area(3258, 3216, 3276, 3238);
    private static final long LOG_INTERVAL_MILLIS = 6_000L;
    private static final long GATE_DIALOGUE_CONTEXT_MILLIS = 15_000L;
    private static final long CONTINUE_LOOP_WINDOW_MILLIS = 30_000L;
    private static final int MAX_CONTINUE_ATTEMPTS_BEFORE_FALLBACK = 5;
    private static final long FALLBACK_DURATION_MILLIS = 18_000L;
    private static final Tile[] NORTH_BYPASS_TILES = {
            new Tile(3263, 3249, 0),
            new Tile(3262, 3265, 0),
            new Tile(3269, 3278, 0)
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long nextLogAt;
    private long gateDialogueSeenUntil;
    private long firstContinueAttemptAt;
    private long fallbackUntil;
    private int continueAttempts;

    public AlKharidGateDialogueController(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "runtime.al_kharid_gate_avoidance";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now < fallbackUntil) {
            return true;
        }

        if (!isNearGate(ctx)) {
            resetDialogueTracking();
            return false;
        }

        boolean dialogueOpen = ctx.dialogues().isDialogueOpen()
                || ctx.dialogues().isChatOpen()
                || ctx.dialogues().canContinue()
                || !ctx.dialogues().getOptions().isEmpty();
        if (!dialogueOpen) {
            resetDialogueTracking();
            return false;
        }

        boolean gateTextVisible = hasGateTextWidget(ctx);
        if (gateTextVisible) {
            gateDialogueSeenUntil = now + GATE_DIALOGUE_CONTEXT_MILLIS;
        }

        return gateTextVisible
                || firstNegativeOption(ctx) != null
                || (now < gateDialogueSeenUntil
                && (ctx.dialogues().canContinue()
                || !ctx.dialogues().getOptions().isEmpty()
                || findContinueTextWidget(ctx) != null));
    }

    @Override
    public void execute(APIContext ctx) {
        if (System.currentTimeMillis() < fallbackUntil) {
            executeFallback(ctx);
            return;
        }

        setStatus("Al Kharid gate: cancelling toll dialogue");
        clearInteractionState(ctx);

        WidgetChild negativeOption = firstNegativeOption(ctx);
        if (negativeOption != null) {
            logThrottled("Cancelling Al Kharid gate option: " + visibleText(negativeOption));
            if (clickWidgetCenter(ctx, negativeOption)
                    || negativeOption.click(false)
                    || ctx.dialogues().selectOption(text -> isNegativeOption(text))) {
                resetDialogueTracking();
                Time.sleep(700, 1100);
                return;
            }
        }

        if (ctx.dialogues().canContinue()) {
            if (continueLoopExceeded(ctx, "dialogue continue")) {
                return;
            }
            logThrottled("Continuing Al Kharid gate dialogue until cancel option appears");
            if (!ctx.dialogues().selectContinue()) {
                clickContinueWidget(ctx);
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(650, 1000);
            return;
        }

        WidgetChild continueWidget = findContinueTextWidget(ctx);
        if (continueWidget != null) {
            if (continueLoopExceeded(ctx, "continue widget")) {
                return;
            }
            logThrottled("Clicking Al Kharid gate continue widget before cancelling");
            if (clickWidgetCenter(ctx, continueWidget) || continueWidget.click(false)) {
                Time.sleep(650, 1000);
                return;
            }
        }

        logThrottled("Al Kharid gate dialogue detected; closing it without paying");
        ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
        Time.sleep(650, 1000);
    }

    private boolean isNearGate(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null && AL_KHARID_TOLL_GATE_AREA.contains(location);
    }

    private WidgetChild firstNegativeOption(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (isNegativeOption(visibleText(option))) {
                return option;
            }
        }
        return null;
    }

    private boolean isNegativeOption(String text) {
        String normalized = normalize(text);
        return normalized.contains("no")
                || normalized.contains("cancel")
                || normalized.contains("never mind")
                || normalized.contains("don't")
                || normalized.contains("do not")
                || normalized.contains("not right now");
    }

    private boolean hasGateTextWidget(APIContext ctx) {
        return findVisibleWidgetTextContaining(ctx, "toll") != null
                || findVisibleWidgetTextContaining(ctx, "10 coins") != null;
    }

    private boolean clickContinueWidget(APIContext ctx) {
        WidgetChild widget = findContinueTextWidget(ctx);
        return widget != null && (clickWidgetCenter(ctx, widget) || widget.click(false));
    }

    private WidgetChild findContinueTextWidget(APIContext ctx) {
        WidgetChild[] candidates = {
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.MES_TEXT)),
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.MES_TEXT2)),
                ctx.widgets().get(InterfaceID.CHATBOX, childId(InterfaceID.Chatbox.INPUT_CLICKAREA))
        };

        for (WidgetChild widget : candidates) {
            if (!isVisibleWidget(widget)) {
                continue;
            }
            String text = normalize(visibleText(widget));
            if (text.contains("click here to continue") || text.contains("continue")) {
                return widget;
            }
        }

        return findVisibleWidgetTextContaining(ctx, "click here to continue");
    }

    private WidgetChild findVisibleWidgetTextContaining(APIContext ctx, String text) {
        String needle = normalize(text);
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate ->
                isVisibleWidget(candidate) && normalize(visibleText(candidate)).contains(needle))) {
            return widget;
        }
        return null;
    }

    private void clearInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private boolean continueLoopExceeded(APIContext ctx, String action) {
        long now = System.currentTimeMillis();
        if (firstContinueAttemptAt <= 0L || now - firstContinueAttemptAt > CONTINUE_LOOP_WINDOW_MILLIS) {
            firstContinueAttemptAt = now;
            continueAttempts = 0;
        }

        continueAttempts++;
        if (continueAttempts < MAX_CONTINUE_ATTEMPTS_BEFORE_FALLBACK) {
            return false;
        }

        startFallback(ctx, action + " repeated " + continueAttempts + " times");
        return true;
    }

    private void startFallback(APIContext ctx, String reason) {
        fallbackUntil = System.currentTimeMillis() + FALLBACK_DURATION_MILLIS;
        resetDialogueTracking();
        logImmediate("Gate dialogue fallback activated: " + reason + "; walking north bypass");
        executeFallback(ctx);
    }

    private void executeFallback(APIContext ctx) {
        setStatus("Al Kharid gate: fallback north bypass");
        clearInteractionState(ctx);

        if (isNorthBypassSafe(ctx) && !hasOpenDialogue(ctx)) {
            fallbackUntil = 0L;
            return;
        }

        if (hasOpenDialogue(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            Time.sleep(600, 900);
            return;
        }

        if (ctx.localPlayer().isMoving()) {
            Time.sleep(700, 1100);
            return;
        }

        Tile target = nextNorthBypassTile(ctx);
        logThrottled("Walking north away from Al Kharid gate dialogue to " + target);
        if (!ctx.walking().walkOnMap(target)) {
            ctx.walking().walkTo(target);
        }
        Time.sleep(900, 1400);
    }

    private boolean hasOpenDialogue(APIContext ctx) {
        return ctx.dialogues().isDialogueOpen()
                || ctx.dialogues().isChatOpen()
                || ctx.dialogues().canContinue()
                || !ctx.dialogues().getOptions().isEmpty()
                || findContinueTextWidget(ctx) != null;
    }

    private Tile nextNorthBypassTile(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return NORTH_BYPASS_TILES[NORTH_BYPASS_TILES.length - 1];
        }
        if (location.getY() < 3248) {
            return NORTH_BYPASS_TILES[0];
        }
        if (location.getY() < 3265) {
            return NORTH_BYPASS_TILES[1];
        }
        return NORTH_BYPASS_TILES[2];
    }

    private boolean isNorthBypassSafe(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null && location.getY() >= 3265;
    }

    private void resetDialogueTracking() {
        gateDialogueSeenUntil = 0L;
        firstContinueAttemptAt = 0L;
        continueAttempts = 0;
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }
        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private String visibleText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        String text = widget.getText();
        String rawText = widget.getRawText();
        if (text == null) {
            text = "";
        }
        if (rawText == null) {
            rawText = "";
        }
        return (text + " " + rawText).trim();
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace("<br>", " ")
                .replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void setStatus(String message) {
        if (stats != null) {
            stats.setStatus(message);
        }
    }

    private void logThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now < nextLogAt) {
            return;
        }
        logger.accept("[AlKharidGate] " + message);
        nextLogAt = now + LOG_INTERVAL_MILLIS;
    }

    private void logImmediate(String message) {
        logger.accept("[AlKharidGate] " + message);
        nextLogAt = System.currentTimeMillis() + LOG_INTERVAL_MILLIS;
    }

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }
}
