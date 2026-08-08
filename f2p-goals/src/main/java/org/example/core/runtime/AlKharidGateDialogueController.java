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
    private static final String[] PAY_OPTION_MARKERS = {
            "pay",
            "okay",
            "ok",
            "yes",
            "pass",
            "come through",
            "go through",
            "open"
    };
    private static final long LOG_INTERVAL_MILLIS = 6_000L;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long nextLogAt;

    public AlKharidGateDialogueController(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "runtime.al_kharid_gate_dialogue";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || !isNearGate(ctx)) {
            return false;
        }

        return ctx.dialogues().canContinue()
                || !ctx.dialogues().getOptions().isEmpty()
                || findContinueTextWidget(ctx) != null
                || hasGateTextWidget(ctx);
    }

    @Override
    public void execute(APIContext ctx) {
        setStatus("Al Kharid gate: handling toll dialogue");
        clearInteractionState(ctx);

        WidgetChild payOption = findPayOption(ctx);
        if (payOption != null) {
            logThrottled("Selecting Al Kharid gate pay/pass option: " + visibleText(payOption));
            if (clickWidgetCenter(ctx, payOption)
                    || payOption.click(false)
                    || ctx.dialogues().selectOption(text -> isPayOption(text))) {
                Time.sleep(700, 1100);
                return;
            }
        }

        if (ctx.dialogues().canContinue()) {
            logThrottled("Continuing Al Kharid gate dialogue");
            if (!ctx.dialogues().selectContinue()) {
                clickContinueWidget(ctx);
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(650, 1000);
            return;
        }

        WidgetChild fallbackOption = firstNonNegativeOption(ctx);
        if (fallbackOption != null) {
            logThrottled("Selecting fallback Al Kharid gate option: " + visibleText(fallbackOption));
            if (clickWidgetCenter(ctx, fallbackOption)
                    || fallbackOption.click(false)
                    || ctx.dialogues().selectOption(text -> !isNegativeOption(text))) {
                Time.sleep(700, 1100);
                return;
            }
        }

        WidgetChild continueWidget = findContinueTextWidget(ctx);
        if (continueWidget != null) {
            logThrottled("Clicking Al Kharid gate continue widget");
            if (clickWidgetCenter(ctx, continueWidget) || continueWidget.click(false)) {
                Time.sleep(650, 1000);
                return;
            }
        }

        logThrottled("Al Kharid gate dialogue detected but no widget clicked; sending space");
        ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
        Time.sleep(650, 1000);
    }

    private boolean isNearGate(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null && AL_KHARID_TOLL_GATE_AREA.contains(location);
    }

    private WidgetChild findPayOption(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (isPayOption(visibleText(option))) {
                return option;
            }
        }
        return null;
    }

    private WidgetChild firstNonNegativeOption(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (!isNegativeOption(visibleText(option))) {
                return option;
            }
        }
        return null;
    }

    private boolean isPayOption(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank() || isNegativeOption(normalized)) {
            return false;
        }
        for (String marker : PAY_OPTION_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
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
                || findVisibleWidgetTextContaining(ctx, "10 coins") != null
                || findVisibleWidgetTextContaining(ctx, "al kharid") != null;
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

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }
}
