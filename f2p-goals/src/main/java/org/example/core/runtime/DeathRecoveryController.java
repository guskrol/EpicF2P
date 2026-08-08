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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final String[] FIRST_DEATH_DIALOGUE_PRIORITY = {
            "what happened",
            "where am i",
            "where are my items",
            "items",
            "reclaim",
            "grave",
            "gravestone",
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
    private boolean sawDeathNpcThisVisit;
    private boolean sawDeathDialogueOptionsThisVisit;
    private boolean firstDeathDialogueCompleteThisVisit;
    private final Set<String> completedDeathDialogueOptions = new HashSet<>();

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

        NPC death = findDeathNpc(ctx);
        if (death != null) {
            sawDeathNpcThisVisit = true;
        }

        boolean deathContext = isLocalPlayerDead(ctx)
                || hasDeathInterfaceOpen(ctx)
                || death != null
                || hasDeathDialogueOption(ctx);
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
        if (death != null) {
            sawDeathNpcThisVisit = true;
        }
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
        if (!ctx.dialogues().isDialogueOpen()
                && !ctx.dialogues().canContinue()
                && ctx.dialogues().getOptions().isEmpty()) {
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

        if (!ctx.dialogues().getOptions().isEmpty()) {
            sawDeathDialogueOptionsThisVisit = true;
            if (handleFirstDeathDialogueOptions(ctx)) {
                return true;
            }
        }

        if (firstDeathDialogueCompleteThisVisit) {
            for (String option : DIALOGUE_OPTION_PRIORITY) {
                if (selectDialogueOptionContaining(ctx, option)) {
                    return true;
                }
            }
        }

        logThrottled("Death recovery saw an empty dialogue state; releasing runtime until a real death context appears");
        resetVisitState();
        Time.sleep(300, 500);
        return false;
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

        if (!ctx.dialogues().isDialogueOpen()
                && !ctx.dialogues().canContinue()
                && ctx.dialogues().getOptions().isEmpty()) {
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
        if (!firstDeathDialogueCompleteThisVisit) {
            return System.currentTimeMillis() - lastDeathTalkAt > 2_500L;
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
        if (sawDeathNpcThisVisit && !firstDeathDialogueCompleteThisVisit) {
            logThrottled("Death recovery is waiting to finish the first Death dialogue before using the portal");
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
                || hasDeathDialogueOption(ctx);
    }

    private boolean hasDeathInterfaceOpen(APIContext ctx) {
        for (int groupId : DEATH_INTERFACE_GROUPS) {
            try {
                WidgetGroup group = ctx.widgets().get(groupId);
                if (group != null
                        && group.isValid()
                        && group.isVisible()
                        && hasDeathInterfaceContent(ctx, groupId)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Widget groups can disappear between game ticks.
            }
        }
        return false;
    }

    private boolean hasDeathInterfaceContent(APIContext ctx, int groupId) {
        if (hasKnownDeathActionButton(ctx, groupId)) {
            return true;
        }

        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate ->
                isVisibleWidget(candidate)
                        && candidate.getGroup() != null
                        && candidate.getGroup().getIndex() == groupId)) {
            if (hasDeathMarker(visibleText(widget))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKnownDeathActionButton(APIContext ctx, int groupId) {
        if (groupId == InterfaceID.DEATH_OFFICE
                && isVisibleWidget(ctx.widgets().get(groupId, childId(InterfaceID.DeathOffice.TAKEALL)))) {
            return true;
        }
        if (groupId == InterfaceID.GRAVESTONE_RETRIEVAL
                && isVisibleWidget(ctx.widgets().get(groupId, childId(InterfaceID.GravestoneRetrieval.BUTTON)))) {
            return true;
        }
        if (groupId == InterfaceID.GRAVESTONE_GENERIC
                && (isVisibleWidget(ctx.widgets().get(groupId, childId(InterfaceID.GravestoneGeneric.FREEBUTTON)))
                || isVisibleWidget(ctx.widgets().get(groupId, childId(InterfaceID.GravestoneGeneric.PAYBUTTON))))) {
            return true;
        }
        return false;
    }

    private boolean hasDeathDialogueOption(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && ctx.dialogues().getOptions().isEmpty()) {
            return false;
        }

        for (WidgetChild option : ctx.dialogues().getOptions()) {
            String text = normalize(visibleText(option));
            if (text.contains("death")
                    || text.contains("grave")
                    || text.contains("reclaim")
                    || text.contains("items")
                    || text.contains("what happened")
                    || text.contains("where am i")) {
                return true;
            }
        }
        return false;
    }

    private boolean handleFirstDeathDialogueOptions(APIContext ctx) {
        WidgetChild nextTopic = firstUnvisitedInformativeDeathOption(ctx);
        if (nextTopic != null) {
            return selectAndRememberDeathOption(ctx, nextTopic, "Death first-dialogue topic");
        }

        WidgetChild backOption = firstDialogueOptionMatching(ctx, this::isBackOption);
        if (backOption != null) {
            return selectDeathOption(ctx, backOption, "Death dialogue back option", false);
        }

        if (!firstDeathDialogueCompleteThisVisit) {
            firstDeathDialogueCompleteThisVisit = true;
            logger.accept("[DeathRecovery] First Death dialogue options completed");
        }

        WidgetChild exitOption = firstDialogueOptionMatching(ctx, this::isExitDialogueOption);
        if (exitOption != null) {
            return selectDeathOption(ctx, exitOption, "Death dialogue final exit option", true);
        }

        return false;
    }

    private WidgetChild firstUnvisitedInformativeDeathOption(APIContext ctx) {
        for (String priority : FIRST_DEATH_DIALOGUE_PRIORITY) {
            String needle = normalize(priority);
            for (WidgetChild option : ctx.dialogues().getOptions()) {
                String text = normalize(visibleText(option));
                if (text.contains(needle)
                        && isInformativeDeathDialogueOption(text)
                        && !completedDeathDialogueOptions.contains(text)) {
                    return option;
                }
            }
        }

        for (WidgetChild option : ctx.dialogues().getOptions()) {
            String text = normalize(visibleText(option));
            if (isInformativeDeathDialogueOption(text)
                    && !completedDeathDialogueOptions.contains(text)) {
                return option;
            }
        }

        return null;
    }

    private WidgetChild firstDialogueOptionMatching(APIContext ctx, DialogueOptionMatcher matcher) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (matcher.matches(normalize(visibleText(option)))) {
                return option;
            }
        }
        return null;
    }

    private boolean selectAndRememberDeathOption(APIContext ctx, WidgetChild option, String label) {
        return selectDeathOption(ctx, option, label, true);
    }

    private boolean selectDeathOption(APIContext ctx, WidgetChild option, String label, boolean remember) {
        String text = normalize(visibleText(option));
        setStatus("Death recovery: selecting Death dialogue");
        logger.accept("[DeathRecovery] Selecting " + label + ": " + visibleText(option));
        boolean selected = clickWidgetCenter(ctx, option)
                || option.click(false)
                || ctx.dialogues().selectOption(candidate -> normalize(candidate).equals(text));
        if (selected) {
            if (remember && !text.isBlank()) {
                completedDeathDialogueOptions.add(text);
            }
            Time.sleep(650, 1000);
            return true;
        }
        return false;
    }

    private boolean isInformativeDeathDialogueOption(String text) {
        return !text.isBlank()
                && !isNegativeOption(text)
                && !isBackOption(text)
                && !isExitDialogueOption(text);
    }

    private boolean isBackOption(String text) {
        return text.equals("back")
                || text.contains("go back")
                || text.contains("previous");
    }

    private boolean isExitDialogueOption(String text) {
        return text.contains("leave")
                || text.contains("return")
                || text.contains("take me")
                || text.contains("send me")
                || text.contains("can i leave")
                || text.contains("goodbye")
                || text.contains("that's all")
                || text.contains("thats all")
                || text.contains("nothing else")
                || text.contains("no more");
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
            return false;
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
        sawDeathNpcThisVisit = false;
        sawDeathDialogueOptionsThisVisit = false;
        firstDeathDialogueCompleteThisVisit = false;
        completedDeathDialogueOptions.clear();
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
        sawDeathNpcThisVisit = false;
        sawDeathDialogueOptionsThisVisit = false;
        firstDeathDialogueCompleteThisVisit = false;
        completedDeathDialogueOptions.clear();
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

    private interface DialogueOptionMatcher {
        boolean matches(String normalizedText);
    }
}
