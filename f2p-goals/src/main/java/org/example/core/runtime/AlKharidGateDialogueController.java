package org.example.core.runtime;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IDialogueAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.navigation.Navigation;
import org.example.core.navigation.AccessRecovery;

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
    private static final long FALLBACK_DURATION_MILLIS = 120_000L;
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
    private Tile fallbackProgressTile;
    private long fallbackProgressAt;
    private int fallbackRetries;

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
        if (fallbackUntil > 0L) {
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
                || (now < gateDialogueSeenUntil
                && (firstNegativeOption(ctx) != null
                || findContinueTextWidget(ctx) != null));
    }

    @Override
    public void execute(APIContext ctx) {
        if (fallbackUntil > 0L) {
            executeFallback(ctx);
            return;
        }

        setStatus("Al Kharid gate: cancelling toll dialogue");
        clearInteractionState(ctx);
        if (continueLoopExceeded(ctx, "gate dialogue")) return;

        WidgetChild negativeOption = firstNegativeOption(ctx);
        if (negativeOption != null) {
            logThrottled("Cancelling Al Kharid gate option: " + visibleText(negativeOption));
            if (clickWidgetCenter(ctx, negativeOption)
                    || negativeOption.click(false)
                    || ctx.dialogues().selectOption(text -> isNegativeOption(text))) {
                // Do not release the route back to the same toll immediately.
                prepareFallback(ctx, "toll declined");
                Time.sleep(700, 1100);
                return;
            }
        }

        if (ctx.dialogues().canContinue() && findContinueTextWidget(ctx) != null) {
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
            logThrottled("Clicking Al Kharid gate continue widget before cancelling");
            if (clickWidgetCenter(ctx, continueWidget) || continueWidget.click(false)) {
                Time.sleep(650, 1000);
                return;
            }
        }

        // Escape can toggle the inventory when the API retains a stale chat flag.
        // A walk cancels the conversation and also advances the intended bypass.
        startFallback(ctx, "no actionable toll dialogue widget");
    }

    private boolean isNearGate(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null && AL_KHARID_TOLL_GATE_AREA.contains(location);
    }

    private WidgetChild firstNegativeOption(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (isVisibleWidget(option) && isNegativeOption(visibleText(option))) {
                return option;
            }
        }
        return null;
    }

    private boolean isNegativeOption(String text) {
        String normalized = normalize(text);
        return normalized.equals("no")
                || normalized.startsWith("no,")
                || normalized.startsWith("no.")
                || normalized.startsWith("no ")
                || normalized.equals("cancel")
                || normalized.contains("never mind")
                || normalized.contains("don't")
                || normalized.contains("do not")
                || normalized.contains("not right now");
    }

    private boolean hasGateTextWidget(APIContext ctx) {
        return findVisibleWidgetTextContaining(ctx, "toll") != null
                || findVisibleWidgetTextContaining(ctx, "10 coins") != null
                || findVisibleWidgetTextContaining(ctx, "10 gold") != null;
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
                isVisibleWidget(candidate) && isDialogueWidget(candidate)
                        && normalize(visibleText(candidate)).contains(needle))) {
            return widget;
        }
        return null;
    }

    private boolean isDialogueWidget(WidgetChild widget) {
        int group = widget.getGroup() == null ? widget.getParentId() : widget.getGroup().getIndex();
        for (int dialogueGroup : IDialogueAPI.DIALOG_GROUP_IDS) {
            if (group == dialogueGroup) return true;
        }
        // Only the current message fields, never the chat history or item tooltip.
        return group == InterfaceID.CHATBOX
                && (widget.getChildId() == childId(InterfaceID.Chatbox.MES_TEXT)
                || widget.getChildId() == childId(InterfaceID.Chatbox.MES_TEXT2));
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
        prepareFallback(ctx, reason);
        executeFallback(ctx);
    }

    private void prepareFallback(APIContext ctx, String reason) {
        fallbackUntil = System.currentTimeMillis() + FALLBACK_DURATION_MILLIS;
        fallbackProgressTile = ctx.localPlayer().getLocation();
        fallbackProgressAt = System.currentTimeMillis();
        fallbackRetries = 0;
        resetDialogueTracking();
        Navigation.retryRoute(ctx);
        logImmediate("Gate dialogue fallback activated: " + reason + "; walking north bypass");
    }

    private void executeFallback(APIContext ctx) {
        setStatus("Al Kharid gate: fallback north bypass");
        clearInteractionState(ctx);

        if (isNorthBypassSafe(ctx)) {
            fallbackUntil = 0L;
            Navigation.retryRoute(ctx);
            logImmediate("North bypass reached; resuming the task route");
            return;
        }

        long now = System.currentTimeMillis();
        Tile here = ctx.localPlayer().getLocation();
        if (here != null && (fallbackProgressTile == null
                || here.getX() != fallbackProgressTile.getX() || here.getY() != fallbackProgressTile.getY()
                || here.getPlane() != fallbackProgressTile.getPlane())) {
            fallbackProgressTile = here;
            fallbackProgressAt = now;
        }
        if (now >= fallbackUntil || now - fallbackProgressAt >= 20_000L) {
            if (now >= fallbackUntil || ++fallbackRetries >= 3) {
                fallbackUntil = 0L;
                AccessRecovery.rejectCurrentAction(ctx, "Al Kharid north bypass made no progress");
                return;
            }
            Navigation.retryRoute(ctx);
            fallbackProgressAt = now;
            logImmediate("North bypass stalled; rebuilding route, attempt " + fallbackRetries);
        }

        Tile target = nextNorthBypassTile(ctx);
        logThrottled("Walking north away from Al Kharid gate dialogue to " + target);
        Navigation.walkToNoTeleports(ctx, target);
        Time.sleep(250, 450);
    }

    private Tile nextNorthBypassTile(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return NORTH_BYPASS_TILES[NORTH_BYPASS_TILES.length - 1];
        }
        if (location.getX() >= 3267) {
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
        Tile exit = NORTH_BYPASS_TILES[NORTH_BYPASS_TILES.length - 1];
        return location != null && location.getPlane() == exit.getPlane()
                && Math.max(Math.abs(location.getX() - exit.getX()),
                        Math.abs(location.getY() - exit.getY())) <= 2;
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
                && widget.isVisible()
                && !widget.isHidden()
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
        return (text.isBlank() ? rawText : text).trim();
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
