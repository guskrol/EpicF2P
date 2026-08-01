package org.example.core.runtime;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class DeathRecoveryController implements RuntimeController {
    private static final int[] DEATH_INTERFACE_GROUPS = {
            InterfaceID.DEATHKEEP,
            InterfaceID.DEATH_OFFICE,
            InterfaceID.DEATH_COFFER,
            InterfaceID.DEATH_COFFER_SIDE,
            InterfaceID.GRAVESTONE_RETRIEVAL,
            InterfaceID.GRAVESTONE_GENERIC
    };
    private static final String[] DEATH_WIDGET_MARKERS = {
            "death's office",
            "death office",
            "death's domain",
            "items kept on death",
            "items lost on death",
            "gravestone",
            "grave timer",
            "reclaim your items",
            "lost items",
            "death will hold"
    };
    private static final String[] DIALOGUE_OPTION_PRIORITY = {
            "i understand",
            "understand",
            "continue",
            "carry on",
            "yes",
            "ok",
            "okay",
            "leave",
            "return",
            "take me",
            "send me",
            "reclaim",
            "collect",
            "items",
            "grave",
            "gravestone",
            "what happened",
            "where am i",
            "how do i",
            "death"
    };
    private static final String[] WIDGET_BUTTON_PRIORITY = {
            "take all",
            "reclaim",
            "retrieve",
            "collect",
            "confirm",
            "continue",
            "return",
            "leave",
            "close",
            "ok",
            "okay"
    };
    private static final String[] EXIT_OBJECT_NAME_MARKERS = {
            "portal",
            "exit"
    };
    private static final String[] EXIT_ACTION_PRIORITY = {
            "Exit",
            "Enter",
            "Use",
            "Return",
            "Leave",
            "Teleport"
    };
    private static final long DEATH_TALK_RETRY_MILLIS = 35_000L;
    private static final long PORTAL_CLICK_RETRY_MILLIS = 4_000L;
    private static final long RECOVERY_LOG_INTERVAL_MILLIS = 8_000L;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long visitStartedAt;
    private long lastDeathTalkAt;
    private long lastDialogueAt;
    private long nextPortalClickAt;
    private long nextRecoveryLogAt;
    private boolean spokeToDeathThisVisit;
    private boolean sawDialogueThisVisit;

    public DeathRecoveryController(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "runtime.death_recovery";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || !isLoggedIn(ctx)) {
            resetVisitState();
            return false;
        }

        boolean deathContext = isLocalPlayerDead(ctx)
                || hasDeathInterfaceOpen(ctx)
                || findDeathNpc(ctx) != null
                || hasDeathContextWidgetText(ctx);
        if (!deathContext) {
            resetVisitState();
            return false;
        }

        beginVisitIfNeeded();
        return true;
    }

    @Override
    public void execute(APIContext ctx) {
        setStatus("Death recovery: resolving Death's Office");
        clearInteractionState(ctx);

        if (isLocalPlayerDead(ctx)) {
            logThrottled("Death recovery waiting for respawn/death transition");
            Time.sleep(900, 1400);
            return;
        }

        if (handleDeathDialogue(ctx)) {
            return;
        }

        if (handleDeathInterface(ctx)) {
            return;
        }

        NPC death = findDeathNpc(ctx);
        if (shouldTalkToDeath(death) && talkToDeath(ctx, death)) {
            return;
        }

        if (canTryExitPortal(death) && exitDeathOffice(ctx)) {
            return;
        }

        if (death != null && retryTalkToDeath(ctx, death)) {
            return;
        }

        logThrottled("Death recovery could not progress yet; adjusting view and waiting");
        ctx.camera().setPitch(380, true);
        Time.sleep(900, 1400);
    }

    private boolean handleDeathDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }

        sawDialogueThisVisit = true;
        lastDialogueAt = System.currentTimeMillis();

        if (ctx.dialogues().canContinue()) {
            setStatus("Death recovery: continuing Death dialogue");
            logger.accept("[DeathRecovery] Continuing Death dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(550, 900);
            return true;
        }

        for (String option : DIALOGUE_OPTION_PRIORITY) {
            if (selectDialogueOptionContaining(ctx, option)) {
                return true;
            }
        }

        if (!ctx.dialogues().getOptions().isEmpty()) {
            setStatus("Death recovery: selecting safe Death dialogue option");
            logger.accept("[DeathRecovery] Selecting fallback non-negative Death dialogue option");
            if (!ctx.dialogues().selectOption(text -> text != null && !isNegativeOption(text))) {
                clickFirstNonNegativeDialogueOption(ctx);
            }
            Time.sleep(650, 1000);
            return true;
        }

        logThrottled("Death recovery dialogue is open but no selectable action was found");
        Time.sleep(650, 1000);
        return true;
    }

    private boolean handleDeathInterface(APIContext ctx) {
        if (!hasDeathInterfaceOpen(ctx)) {
            return false;
        }

        if (clickKnownDeathButton(ctx, InterfaceID.DEATH_OFFICE, childId(InterfaceID.DeathOffice.TAKEALL),
                "Death Office take all")) {
            return true;
        }
        if (clickKnownDeathButton(ctx, InterfaceID.GRAVESTONE_RETRIEVAL,
                childId(InterfaceID.GravestoneRetrieval.BUTTON), "Gravestone retrieve")) {
            return true;
        }
        if (clickKnownDeathButton(ctx, InterfaceID.GRAVESTONE_GENERIC,
                childId(InterfaceID.GravestoneGeneric.FREEBUTTON), "Free gravestone retrieve")) {
            return true;
        }
        if (clickKnownDeathButton(ctx, InterfaceID.GRAVESTONE_GENERIC,
                childId(InterfaceID.GravestoneGeneric.PAYBUTTON), "Paid gravestone retrieve")) {
            return true;
        }

        WidgetChild actionButton = findDeathWidgetButton(ctx);
        if (actionButton != null) {
            setStatus("Death recovery: clicking death interface action");
            logger.accept("[DeathRecovery] Clicking death interface widget: " + visibleText(actionButton));
            if (clickWidgetCenter(ctx, actionButton) || actionButton.click(false) || actionButton.click()) {
                Time.sleep(800, 1300);
                return true;
            }
        }

        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            setStatus("Death recovery: closing death interface");
            logger.accept("[DeathRecovery] Closing death interface after no reclaim button was found");
            ctx.widgets().closeInterface();
            Time.sleep(600, 950);
            return true;
        }

        return false;
    }

    private boolean talkToDeath(APIContext ctx, NPC death) {
        if (death == null || !death.isValid()) {
            return false;
        }

        setStatus("Death recovery: talking to Death");
        logger.accept("[DeathRecovery] Talking to Death before trying portal");
        if (death.tileDistanceTo(ctx) > 8) {
            ctx.camera().turnTo(death);
            Time.sleep(250, 450);
        }

        boolean interacted = death.interact("Talk-to")
                || ctx.menu().interact("Talk-to", death, false)
                || ctx.menu().interact("Talk-to", death, true);
        if (interacted) {
            spokeToDeathThisVisit = true;
            lastDeathTalkAt = System.currentTimeMillis();
            Time.sleep(1000, 1800,
                    () -> ctx.dialogues().isDialogueOpen()
                            || ctx.dialogues().isChatOpen()
                            || hasDeathInterfaceOpen(ctx),
                    100);
            return true;
        }

        return false;
    }

    private boolean retryTalkToDeath(APIContext ctx, NPC death) {
        long now = System.currentTimeMillis();
        if (now - lastDeathTalkAt < DEATH_TALK_RETRY_MILLIS) {
            return false;
        }
        return talkToDeath(ctx, death);
    }

    private boolean exitDeathOffice(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextPortalClickAt) {
            return false;
        }

        SceneObject portal = findExitPortal(ctx);
        if (portal == null || !portal.isValid()) {
            logThrottled("Death recovery has no dialogue left but cannot see the exit portal yet");
            return false;
        }

        setStatus("Death recovery: leaving Death's Office");
        logger.accept("[DeathRecovery] Leaving Death's Office through portal");
        if (portal.tileDistanceTo(ctx) > 8) {
            ctx.camera().turnTo(portal);
            Time.sleep(250, 450);
        }

        nextPortalClickAt = now + PORTAL_CLICK_RETRY_MILLIS;
        for (String action : EXIT_ACTION_PRIORITY) {
            if (portal.hasAction(action)
                    && (portal.interact(action)
                    || ctx.menu().interact(action, portal, false)
                    || ctx.menu().interact(action, portal, true))) {
                Time.sleep(1400, 2400, () -> !isInDeathRecoveryContext(ctx), 100);
                return true;
            }
        }

        return false;
    }

    private boolean selectDialogueOptionContaining(APIContext ctx, String option) {
        String needle = normalize(option);
        for (WidgetChild widget : ctx.dialogues().getOptions()) {
            String text = normalize(visibleText(widget));
            if (!text.contains(needle) || isNegativeOption(text)) {
                continue;
            }

            setStatus("Death recovery: selecting dialogue option");
            logger.accept("[DeathRecovery] Selecting Death dialogue option: " + visibleText(widget));
            if (clickWidgetCenter(ctx, widget)
                    || widget.click(false)
                    || ctx.dialogues().selectOption(candidate -> normalize(candidate).contains(needle))) {
                Time.sleep(650, 1000);
                return true;
            }
        }

        return false;
    }

    private boolean clickFirstNonNegativeDialogueOption(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (option == null || isNegativeOption(visibleText(option))) {
                continue;
            }
            return clickWidgetCenter(ctx, option) || option.click(false);
        }
        return false;
    }

    private boolean clickKnownDeathButton(APIContext ctx, int groupId, int childId, String label) {
        WidgetChild widget = ctx.widgets().get(groupId, childId);
        if (!isVisibleWidget(widget) || isDangerousWidget(widget)) {
            return false;
        }

        setStatus("Death recovery: " + label);
        logger.accept("[DeathRecovery] Clicking " + label);
        if (clickWidgetCenter(ctx, widget)
                || widget.interact("Take all")
                || widget.interact("Reclaim")
                || widget.interact("Retrieve")
                || widget.interact("Continue")
                || widget.click(false)
                || widget.click()) {
            Time.sleep(800, 1300);
            return true;
        }

        return false;
    }

    private WidgetChild findDeathWidgetButton(APIContext ctx) {
        for (String buttonText : WIDGET_BUTTON_PRIORITY) {
            String needle = normalize(buttonText);
            for (WidgetChild widget : visibleDeathWidgets(ctx)) {
                String text = normalize(visibleText(widget));
                if (text.contains(needle) && !isDangerousWidget(widget)) {
                    return widget;
                }
            }
        }

        return null;
    }

    private SceneObject findExitPortal(APIContext ctx) {
        for (String name : EXIT_OBJECT_NAME_MARKERS) {
            SceneObject object = ctx.objects()
                    .query()
                    .nameContains(name)
                    .tileDistance(35)
                    .results()
                    .nearest();
            if (isExitObject(object)) {
                return object;
            }
        }

        for (String action : EXIT_ACTION_PRIORITY) {
            SceneObject object = ctx.objects()
                    .query()
                    .actions(action)
                    .tileDistance(35)
                    .results()
                    .nearest();
            if (isExitObject(object)) {
                return object;
            }
        }

        return null;
    }

    private boolean isExitObject(SceneObject object) {
        if (object == null || !object.isValid()) {
            return false;
        }

        String name = normalize(object.getName());
        boolean exitName = name.contains("portal") || name.contains("exit");
        boolean exitAction = false;
        for (String action : EXIT_ACTION_PRIORITY) {
            if (object.hasAction(action)) {
                exitAction = true;
                break;
            }
        }
        return exitName && exitAction;
    }

    private NPC findDeathNpc(APIContext ctx) {
        NPC death = ctx.npcs()
                .query()
                .id(NpcID.DEATH_OFFICE_DEATH)
                .tileDistance(45)
                .results()
                .nearest();
        if (death != null && death.isValid()) {
            return death;
        }

        death = ctx.npcs()
                .query()
                .named("Death")
                .tileDistance(45)
                .results()
                .nearest();
        if (death != null && death.isValid()) {
            return death;
        }

        death = ctx.npcs()
                .query()
                .nameContains("Death")
                .tileDistance(45)
                .results()
                .nearest();
        return death != null && death.isValid() ? death : null;
    }

    private boolean shouldTalkToDeath(NPC death) {
        if (death == null) {
            return false;
        }
        if (!spokeToDeathThisVisit) {
            return true;
        }
        return System.currentTimeMillis() - visitStartedAt > 60_000L
                && System.currentTimeMillis() - lastDialogueAt > 12_000L
                && System.currentTimeMillis() - lastDeathTalkAt > DEATH_TALK_RETRY_MILLIS;
    }

    private boolean canTryExitPortal(NPC death) {
        if (ctxStillInDialogueCooldown()) {
            return false;
        }
        return death == null || spokeToDeathThisVisit || sawDialogueThisVisit;
    }

    private boolean ctxStillInDialogueCooldown() {
        return sawDialogueThisVisit && System.currentTimeMillis() - lastDialogueAt < 1200L;
    }

    private boolean isInDeathRecoveryContext(APIContext ctx) {
        return isLocalPlayerDead(ctx)
                || hasDeathInterfaceOpen(ctx)
                || findDeathNpc(ctx) != null
                || hasDeathContextWidgetText(ctx);
    }

    private boolean hasDeathInterfaceOpen(APIContext ctx) {
        for (int groupId : DEATH_INTERFACE_GROUPS) {
            try {
                WidgetGroup group = ctx.widgets().get(groupId);
                if (group != null && group.isValid() && group.isVisible()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Widget groups can disappear between game ticks.
            }
        }
        return false;
    }

    private boolean hasDeathContextWidgetText(APIContext ctx) {
        for (String marker : DEATH_WIDGET_MARKERS) {
            String needle = normalize(marker);
            for (WidgetChild widget : ctx.widgets().getAllChildren(candidate ->
                    isVisibleWidget(candidate) && normalize(visibleText(candidate)).contains(needle))) {
                return true;
            }
        }
        return false;
    }

    private List<WidgetChild> visibleDeathWidgets(APIContext ctx) {
        return ctx.widgets().getAllChildren(widget -> {
            if (!isVisibleWidget(widget) || widget.getGroup() == null) {
                return false;
            }
            int groupId = widget.getGroup().getIndex();
            for (int deathGroup : DEATH_INTERFACE_GROUPS) {
                if (groupId == deathGroup) {
                    return true;
                }
            }
            return hasDeathMarker(visibleText(widget));
        });
    }

    private boolean hasDeathMarker(String text) {
        String normalized = normalize(text);
        for (String marker : DEATH_WIDGET_MARKERS) {
            if (normalized.contains(normalize(marker))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalPlayerDead(APIContext ctx) {
        try {
            return ctx.localPlayer().get() != null && ctx.localPlayer().get().isDead();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isLoggedIn(APIContext ctx) {
        try {
            return ctx.client().isLoggedIn();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void beginVisitIfNeeded() {
        if (visitStartedAt != 0L) {
            return;
        }

        visitStartedAt = System.currentTimeMillis();
        lastDeathTalkAt = 0L;
        lastDialogueAt = 0L;
        nextPortalClickAt = 0L;
        spokeToDeathThisVisit = false;
        sawDialogueThisVisit = false;
        logger.accept("[DeathRecovery] Death context detected; taking over runtime");
    }

    private void resetVisitState() {
        visitStartedAt = 0L;
        lastDeathTalkAt = 0L;
        lastDialogueAt = 0L;
        nextPortalClickAt = 0L;
        nextRecoveryLogAt = 0L;
        spokeToDeathThisVisit = false;
        sawDialogueThisVisit = false;
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

    private boolean isNegativeOption(String text) {
        String normalized = normalize(text);
        return normalized.contains("no")
                || normalized.contains("cancel")
                || normalized.contains("never mind")
                || normalized.contains("not right now")
                || normalized.contains("don't")
                || normalized.contains("do not")
                || normalized.contains("nothing");
    }

    private boolean isDangerousWidget(WidgetChild widget) {
        String text = normalize(visibleText(widget));
        return text.contains("discard")
                || text.contains("destroy")
                || text.contains("incinerate")
                || text.contains("delete");
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
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
        if (now < nextRecoveryLogAt) {
            return;
        }
        logger.accept("[DeathRecovery] " + message);
        nextRecoveryLogAt = now + RECOVERY_LOG_INTERVAL_MILLIS;
    }

    private static int childId(int packedWidgetId) {
        return packedWidgetId & 0xFFFF;
    }
}
