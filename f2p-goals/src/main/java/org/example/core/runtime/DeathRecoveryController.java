package org.example.core.runtime;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID1;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.methods.IDialogueAPI;
import com.epicbot.api.shared.model.InventoryId;
import com.epicbot.api.shared.model.ItemContainer;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.navigation.Navigation;
import org.example.core.navigation.CameraMovement;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final long DIALOGUE_TRANSITION_MILLIS = 2_500L;
    private static final long SILENT_CONTINUE_WINDOW_MILLIS = 12_000L;
    private static final long SILENT_CONTINUE_RETRY_MILLIS = 2_000L;
    private static final int MAX_SILENT_CONTINUE_ATTEMPTS = 4;
    private static final long RECOVERY_STALL_MILLIS = 90_000L;
    private static final long MAX_RECOVERY_MILLIS = 5 * 60_000L;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final Runnable onDeathDetected;
    private long visitStartedAt;
    private long lastDeathTalkAt;
    private long lastDialogueAt;
    private long dialogueLoadingSince;
    private long lastSilentContinueAt;
    private int silentContinueAttempts;
    private long nextPortalClickAt;
    private long nextRecoveryLogAt;
    private boolean spokeToDeathThisVisit;
    private boolean sawDialogueThisVisit;
    private boolean sawDeathNpcThisVisit;
    private boolean firstDeathTutorialActive;
    private boolean firstDeathTutorialFinished;
    private boolean tutorialExitChosen;
    private long nextCollectAt;
    private String tutorialAccount = "";
    private boolean tutorialKnownComplete;
    private Path tutorialDirectory = Path.of(System.getProperty("user.home"), "EpicBot", "f2p-goals", "death-tutorial");
    private final Set<String> completedDeathDialogueOptions = new HashSet<>();
    private final Set<String> discoveredDeathDialogueOptions = new HashSet<>();
    private boolean sawTopicMenuThisVisit;
    private String pendingDeathDialogueOption;
    private String pendingDeathDialogueSnapshot;
    private String lastDialogueSnapshot = "";
    private String lastAttemptSnapshot = "";
    private long lastRecoveryProgressAt;
    private int dialogueAttempts;
    private String pacedDialogueSnapshot = "";
    private long nextDialogueActionAt;
    private boolean paceDialogue = true;
    private boolean deathItemsTaken;
    private Map<Integer, Long> collectionInventoryBefore;
    private Map<Integer, Long> collectionExpectedItems;
    private boolean collectionWindowRefreshed;
    private int takeAllAttempts;
    private long nextTakeAllAt;
    private String lastTakeAllInventorySignature;
    private String lastStoredItemsSignature;
    private int deathWindowCloseAttempts;
    private boolean recoveryPending;
    private boolean retrievalDialogueActive;
    private boolean transferConversationStarted;
    private boolean transferCheckComplete;
    private boolean transferChoiceConfirmed;
    private String pendingTransferSnapshot;
    private long nextTransferTalkAt;
    private long nextEntranceAt;
    private long suppressOldGraveUntil;
    private static final Tile LUMBRIDGE_DEATH_APPROACH = new Tile(3240, 3200, 0);
    private static final int[] DEATH_ENTRANCES = {
            ObjectID1.DEATH_OFFICE_ACCESS_GRAVE, ObjectID1.DEATH_OFFICE_ACCESS_BROWNGRAVE,
            ObjectID1.DEATH_OFFICE_ACCESS_COFFIN, ObjectID1.DEATH_OFFICE_ACCESS_WHITECOFFIN,
            ObjectID1.FAI_DEATH_CRYPT_ENTRANCE
    };

    public DeathRecoveryController(Consumer<String> logger, ScriptStats stats) {
        this(logger, stats, () -> { });
    }

    public DeathRecoveryController(Consumer<String> logger, ScriptStats stats, Runnable onDeathDetected) {
        this.logger = logger;
        this.stats = stats;
        this.onDeathDetected = onDeathDetected;
    }

    @Override
    public String name() {
        return "runtime.death_recovery";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || !isLoggedIn(ctx)) {
            if (!recoveryPending) resetVisitState();
            return false;
        }
        loadTutorialStatus(ctx);

        boolean dead = isLocalPlayerDead(ctx);
        boolean grave = hasActiveGravestone(ctx) && System.currentTimeMillis() >= suppressOldGraveUntil;
        if ((dead || grave) && !recoveryPending) {
            beginVisitIfNeeded();
            recoveryPending = true;
            savePendingRecovery(true);
            Navigation.retryRoute(ctx);
            logger.accept("[DeathRecovery] Item recovery pending; retaining priority through respawn and travel");
        }

        NPC death = findDeathNpc(ctx);
        boolean deathContext = dead
                || hasDeathInterfaceOpen(ctx)
                || death != null
                || findOfficeExitPortal(ctx) != null
                || (visitStartedAt != 0L && hasDeathDialogueOption(ctx))
                || (visitStartedAt != 0L && System.currentTimeMillis() - lastDialogueAt < DIALOGUE_TRANSITION_MILLIS);
        if (!deathContext && (!recoveryPending || deathItemsTaken)) {
            if (recoveryPending && deathItemsTaken) {
                recoveryPending = false;
                savePendingRecovery(false);
                suppressOldGraveUntil = System.currentTimeMillis() + 30_000L;
                logger.accept("[DeathRecovery] Recovery confirmed and office exited; resuming normal runtime");
            }
            if (sawDeathNpcThisVisit && (spokeToDeathThisVisit || sawDialogueThisVisit)) {
                logger.accept("[DeathRecovery] Death's Office exited; returning to normal runtime");
            }
            resetVisitState();
            return false;
        }

        beginVisitIfNeeded();
        if (death != null) {
            sawDeathNpcThisVisit = true;
        }
        return true;
    }

    @Override
    public void execute(APIContext ctx) {
        setStatus("Death recovery: resolving Death's Office");
        clearInteractionState(ctx);
        observeDialogueProgress(ctx);
        if (recoverIfStalled(ctx)) {
            return;
        }

        if (isLocalPlayerDead(ctx)) {
            logThrottled("Death recovery waiting for respawn/death transition");
            Time.sleep(900, 1400);
            return;
        }

        if (recoveryPending && !deathItemsTaken && findDeathNpc(ctx) == null && findOfficeExitPortal(ctx) == null
                && !hasDeathInterfaceOpen(ctx)) {
            enterDeathOffice(ctx);
            return;
        }

        if (!transferCheckComplete && transferConversationStarted && transferChoiceConfirmed
                && !hasActiveGravestone(ctx)) confirmGraveTransfer();

        if (needsGraveTransfer(ctx) && hasDeathInterfaceOpen(ctx)) {
            if (transferConversationStarted && !hasActiveGravestone(ctx)) {
                confirmGraveTransfer();
            } else {
                deathItemsTaken = false;
                logger.accept("[DeathRecovery] Retrieval window is not proof of an empty grave; closing it to request item transfer");
                closeDeathWindow(ctx);
                return;
            }
        }

        if (handleDeathInterface(ctx)) {
            return;
        }

        if (handleDeathDialogue(ctx)) {
            return;
        }

        NPC death = findDeathNpc(ctx);
        if (death != null) {
            sawDeathNpcThisVisit = true;
        }
        if (death != null && !deathItemsTaken && needsGraveTransfer(ctx) && !firstDeathTutorialActive) {
            if (System.currentTimeMillis() < nextTransferTalkAt) {
                Time.sleep(350, 600);
                return;
            }
            retrievalDialogueActive = true;
            transferConversationStarted = true;
            nextTransferTalkAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(8000L, 12001L);
            logger.accept("[DeathRecovery] Asking Death to bring grave items before any collection");
            talkToDeath(ctx, death);
            return;
        }
        if (!deathItemsTaken && death != null && collectFromDeath(ctx, death)) {
            return;
        }

        if (canTryExitPortal(death) && exitDeathOffice(ctx)) {
            return;
        }

        if (!deathItemsTaken && shouldTalkToDeath(death) && talkToDeath(ctx, death)) {
            return;
        }

        if (!deathItemsTaken && firstDeathTutorialActive && death != null && retryTalkToDeath(ctx, death)) {
            return;
        }

        if (sawDialogueThisVisit && !allTopicsConfirmed()) {
            setStatus("Death recovery: waiting for the next dialogue page or question");
            logThrottled("Waiting for Death's next dialogue control; preserving confirmed topics");
            Time.sleep(650, 1000);
            return;
        }
        logThrottled("Death recovery could not progress yet; adjusting view and waiting");
        org.example.core.navigation.CameraMovement.turn(ctx, ctx.camera().getYawDeg(), 75, logger);
        Time.sleep(900, 1400);
    }

    private boolean handleDeathDialogue(APIContext ctx) {
        observeDialogueProgress(ctx);
        List<WidgetChild> options = visibleDialogueOptions(ctx);
        boolean canContinue = hasContinuePrompt(ctx);
        if (!firstDeathTutorialFinished && !firstDeathTutorialActive
                && isFirstDeathTutorialFrame(ctx, options)) {
            firstDeathTutorialActive = true;
            logger.accept("[DeathRecovery] First-death tutorial detected; completing it once");
        }
        long now = System.currentTimeMillis();
        boolean loading = ctx.dialogues().isLoading();
        if (loading && dialogueLoadingSince == 0L) dialogueLoadingSince = now;
        if (!loading) dialogueLoadingSince = 0L;
        if (loading && now - dialogueLoadingSince < 3500L) {
            Time.sleep(250, 450);
            return true;
        }
        if (loading && !canContinue && options.isEmpty()) {
            logThrottled("Death dialogue loading flag persisted without controls; checking NPC and exit");
            return false;
        }
        if (!ctx.dialogues().isDialogueOpen() && !canContinue && options.isEmpty()) {
            if (ctxStillInDialogueCooldown()) {
                Time.sleep(250, 450);
                return true;
            }
            if (tutorialExitChosen) {
                firstDeathTutorialFinished = true;
                firstDeathTutorialActive = false;
                markTutorialComplete();
                logger.accept("[DeathRecovery] First-death tutorial complete; later conversations will be skipped");
                return false;
            }
            if (continueSilentTransition(ctx, now)) return true;
            return false;
        }

        sawDialogueThisVisit = true;
        lastDialogueAt = System.currentTimeMillis();

        if (!firstDeathTutorialActive || firstDeathTutorialFinished) {
            WidgetChild request = firstDialogueOptionMatching(ctx, this::isReclaimRequestOption);
            if (request != null) {
                retrievalDialogueActive = true;
                if (waitForDialoguePace(ctx, dialogueSnapshot(ctx), true)) return true;
                return selectTransferOption(ctx, request, "Death item transfer request");
            }
            if (retrievalDialogueActive) {
                WidgetChild confirmation = firstDialogueOptionMatching(ctx, this::isReclaimConfirmationOption);
                if (confirmation != null) {
                    if (waitForDialoguePace(ctx, dialogueSnapshot(ctx), true)) return true;
                    return selectTransferOption(ctx, confirmation, "Death item transfer confirmation");
                }
            }
            // Continue only the recovery conversation opened by Collect.
            if (!(retrievalDialogueActive && canContinue && options.isEmpty())) {
                setStatus("Death recovery: skipping optional Death conversation");
                logThrottled("Optional Death dialogue is open; closing it to use Collect");
                ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
                ctx.widgets().closeInterface();
                Time.sleep(400, 700);
                return true;
            }
        }

        if (!options.isEmpty()) {
            if (waitForDialoguePace(ctx, dialogueSnapshot(ctx), true)) return true;
            handleFirstDeathDialogueOptions(ctx);
            return true;
        }

        if (canContinue) {
            setStatus("Death recovery: continuing Death dialogue");
            String snapshot = dialogueSnapshot(ctx);
            if (waitForDialoguePace(ctx, snapshot, false)) return true;
            int attempt = nextDialogueAttempt(snapshot);
            logger.accept("[DeathRecovery] Continuing Death dialogue (attempt " + (attempt + 1)
                    + "): " + normalize(ctx.dialogues().getText()));
            switch (attempt % 3) {
                case 0 -> {
                    if (!ctx.dialogues().selectContinue(true)) clickContinuePrompt(ctx);
                }
                case 1 -> {
                    if (!clickContinuePrompt(ctx)) ctx.dialogues().selectContinue(false);
                }
                default -> ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(1000, 1600, () -> !snapshot.equals(dialogueSnapshot(ctx)), 100);
            observeDialogueProgress(ctx);
            return true;
        }

        logThrottled("Death dialogue is transitioning; keeping the completed topics and waiting");
        Time.sleep(300, 500);
        return true;
    }

    private List<WidgetChild> visibleDialogueOptions(APIContext ctx) {
        // EpicBot sometimes includes both the speech text and "Click here to continue"
        // in getOptions(). Neither is a selectable question.
        if (isContinueFrame(ctx)) return List.of();
        return ctx.dialogues().getOptions().stream()
                .filter(this::isVisibleWidget)
                .filter(option -> !normalize(visibleText(option)).isBlank()
                        && !isContinuePrompt(visibleText(option)))
                .toList();
    }

    private boolean isContinueFrame(APIContext ctx) {
        List<WidgetChild> raw = ctx.dialogues().getOptions().stream()
                .filter(this::isVisibleWidget).toList();
        boolean prompt = raw.stream().anyMatch(option -> isContinuePrompt(visibleText(option)));
        if (prompt) {
            if (ctx.dialogues().getDialogueType() != IDialogueAPI.DialogueType.OPTION) return true;
            // The API's type can lag a frame behind the widgets. A prompt plus
            // the current spoken line is still a Continue page.
            List<WidgetChild> other = raw.stream()
                    .filter(option -> !isContinuePrompt(visibleText(option))).toList();
            return other.size() == 1 && normalize(visibleText(other.get(0)))
                    .equals(normalize(ctx.dialogues().getText()));
        }
        return ctx.dialogues().getDialogueType() == IDialogueAPI.DialogueType.CONTINUE
                && raw.size() <= 1 && !normalize(ctx.dialogues().getText()).isBlank();
    }

    private boolean hasContinuePrompt(APIContext ctx) {
        return ctx.dialogues().canContinue() || isContinueFrame(ctx)
                || findContinuePromptWidget(ctx) != null;
    }

    private boolean isContinuePrompt(String text) {
        return normalize(text).contains("click here to continue");
    }

    private boolean clickContinuePrompt(APIContext ctx) {
        WidgetChild prompt = findContinuePromptWidget(ctx);
        return prompt != null && (prompt.click(false) || clickWidgetCenter(ctx, prompt));
    }

    private WidgetChild findContinuePromptWidget(APIContext ctx) {
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (isVisibleWidget(option) && isContinuePrompt(visibleText(option))) return option;
        }
        int[] childIds = {
                childId(InterfaceID.Chatbox.MES_TEXT),
                childId(InterfaceID.Chatbox.MES_TEXT2),
                childId(InterfaceID.Chatbox.INPUT_CLICKAREA)
        };
        for (int child : childIds) {
            WidgetChild widget = ctx.widgets().get(InterfaceID.CHATBOX, child);
            if (isVisibleWidget(widget) && isContinuePrompt(visibleText(widget))) return widget;
        }
        return null;
    }

    private boolean continueSilentTransition(APIContext ctx, long now) {
        if (!sawDialogueThisVisit || now - lastRecoveryProgressAt > SILENT_CONTINUE_WINDOW_MILLIS
                || !lastDialogueSnapshot.startsWith("continue:")
                || silentContinueAttempts >= MAX_SILENT_CONTINUE_ATTEMPTS) return false;
        if (now - lastSilentContinueAt < SILENT_CONTINUE_RETRY_MILLIS) {
            Time.sleep(250, 450);
            return true;
        }
        silentContinueAttempts++;
        lastSilentContinueAt = now;
        setStatus("Death recovery: continuing a delayed dialogue page");
        logThrottled("Death dialogue control disappeared briefly; trying Space "
                + silentContinueAttempts + "/" + MAX_SILENT_CONTINUE_ATTEMPTS);
        ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
        Time.sleep(700, 1100, () -> !lastDialogueSnapshot.equals(dialogueSnapshot(ctx)), 100);
        observeDialogueProgress(ctx);
        return true;
    }

    private String continueText(APIContext ctx) {
        String text = normalize(ctx.dialogues().getText());
        if (!text.isBlank()) return text;
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            if (isVisibleWidget(option) && !isContinuePrompt(visibleText(option)))
                return normalize(visibleText(option));
        }
        return "";
    }

    private String dialogueSnapshot(APIContext ctx) {
        List<String> options = visibleDialogueOptions(ctx).stream()
                .map(option -> normalize(visibleText(option))).toList();
        if (!options.isEmpty()) {
            return "options:" + String.join("|", options);
        }
        if (hasContinuePrompt(ctx)) {
            return "continue:" + continueText(ctx);
        }
        return "";
    }

    private void observeDialogueProgress(APIContext ctx) {
        String snapshot = dialogueSnapshot(ctx);
        if (pendingTransferSnapshot != null && !snapshot.equals(pendingTransferSnapshot)
                && (!snapshot.isEmpty() || !hasActiveGravestone(ctx))) {
            transferChoiceConfirmed = true;
            pendingTransferSnapshot = null;
        }
        // A loading/empty frame is not proof that the server accepted a selection.
        if (snapshot.isEmpty() || snapshot.equals(lastDialogueSnapshot)) {
            return;
        }
        lastDialogueSnapshot = snapshot;
        lastRecoveryProgressAt = System.currentTimeMillis();
        silentContinueAttempts = 0;
        if (pendingDeathDialogueSnapshot != null && !snapshot.equals(pendingDeathDialogueSnapshot)) {
            if (pendingDeathDialogueOption != null) {
                completedDeathDialogueOptions.add(pendingDeathDialogueOption);
                logger.accept("[DeathRecovery] Confirmed dialogue topic: " + pendingDeathDialogueOption);
            }
            pendingDeathDialogueOption = null;
            pendingDeathDialogueSnapshot = null;
        }
        List<WidgetChild> choices = visibleDialogueOptions(ctx);
        if (!choices.isEmpty()) {
            sawTopicMenuThisVisit = true;
            for (WidgetChild option : choices) {
                String text = normalize(visibleText(option));
                if (isInformativeDeathDialogueOption(text))
                    discoveredDeathDialogueOptions.add(text);
            }
        }
    }

    private int nextDialogueAttempt(String snapshot) {
        if (!snapshot.equals(lastAttemptSnapshot)) {
            lastAttemptSnapshot = snapshot;
            dialogueAttempts = 0;
        }
        return dialogueAttempts++;
    }

    private boolean recoverIfStalled(APIContext ctx) {
        long now = System.currentTimeMillis();
        boolean timedOut = visitStartedAt != 0L && now - visitStartedAt >= MAX_RECOVERY_MILLIS;
        boolean stalled = lastRecoveryProgressAt != 0L && now - lastRecoveryProgressAt >= RECOVERY_STALL_MILLIS;
        if (!timedOut && !stalled) {
            return false;
        }
        String reason = timedOut
                ? "Death recovery still in Death's Office after 5 minutes; retrying dialogue"
                : "Death recovery stalled without confirmed progress for 90 seconds; retrying dialogue";
        logger.accept("[DeathRecovery] " + reason + "; location=" + ctx.localPlayer().getLocation()
                + "; dialogue=" + dialogueSnapshot(ctx)
                + "; pending=" + pendingDeathDialogueOption
                + "; confirmed=" + completedDeathDialogueOptions
                + "; remaining=" + remainingTopics());
        setStatus(reason);
        // Retain confirmed topics and the pending selection. Retry other input
        // methods on the next tick, without abandoning a recoverable death visit.
        lastAttemptSnapshot = "";
        dialogueAttempts = 0;
        lastRecoveryProgressAt = now;
        if (timedOut) visitStartedAt = now;
        return true;
    }

    private Set<String> remainingTopics() {
        Set<String> remaining = new HashSet<>(discoveredDeathDialogueOptions);
        remaining.removeAll(completedDeathDialogueOptions);
        return remaining;
    }

    private boolean allTopicsConfirmed() {
        return sawTopicMenuThisVisit && pendingDeathDialogueOption == null
                && remainingTopics().isEmpty();
    }

    private boolean handleDeathInterface(APIContext ctx) {
        if (!hasDeathInterfaceOpen(ctx)) {
            return false;
        }
        if (isDeathCofferOpen(ctx)) {
            markTutorialComplete();
            setStatus("Death recovery: closing Death's Coffer");
            logger.accept("[DeathRecovery] Death's Coffer is open; closing it before recovery actions");
            closeDeathWindow(ctx);
            return true;
        }
        if (deathItemsTaken) {
            setStatus("Death recovery: items received; closing retrieval window");
            logThrottled("Death items reached inventory; closing retrieval window without another Take all");
            closeDeathWindow(ctx);
            return true;
        }

        WidgetChild takeAll = ctx.widgets().get(InterfaceID.DEATH_OFFICE,
                childId(InterfaceID.DeathOffice.TAKEALL));
        if (isVisibleWidget(takeAll) && !isDangerousWidget(takeAll)) {
            retrievalDialogueActive = false;
            markTutorialComplete();
            if (finishConfirmedCollection(ctx)) return true;
            List<String> storedItems = readDeathOfficeItems(ctx);
            if (storedItems == null) {
                setStatus("Death recovery: waiting for the stored-item list");
                logThrottled("Death item list is not readable yet; no Take all input will be sent");
                Time.sleep(400, 700);
                return true;
            }
            if (storedItems.isEmpty()) {
                Time.sleep(350, 650);
                List<String> confirmedItems = readDeathOfficeItems(ctx);
                if (confirmedItems == null || !confirmedItems.isEmpty()) return true;
                deathItemsTaken = true;
                sawTopicMenuThisVisit = true;
                sawDialogueThisVisit = true;
                logger.accept("[DeathRecovery] Death's retrieval window has no stored items; skipping Take all");
                closeDeathWindow(ctx);
                return true;
            }
            String storedSignature = storedItems.toString();
            String currentInventory = inventorySignature(ctx);
            if (!storedSignature.equals(lastStoredItemsSignature)
                    || (lastTakeAllInventorySignature != null
                    && !currentInventory.equals(lastTakeAllInventorySignature))) {
                takeAllAttempts = 0;
                lastStoredItemsSignature = storedSignature;
                lastTakeAllInventorySignature = currentInventory;
            }
            // A restart may begin with Death's retrieval window already open.
            // The game only exposes it after the preceding dialogue, so resume
            // at item collection instead of restarting the tutorial.
            if (!sawTopicMenuThisVisit) {
                sawTopicMenuThisVisit = true;
                sawDialogueThisVisit = true;
            }
            long now = System.currentTimeMillis();
            if (now < nextTakeAllAt) {
                Time.sleep(350, 550);
                return true;
            }
            if (takeAllAttempts >= 3) {
                if (!collectionWindowRefreshed) {
                    collectionWindowRefreshed = true;
                    collectionInventoryBefore = null;
                    collectionExpectedItems = null;
                    takeAllAttempts = 0;
                    logger.accept("[DeathRecovery] Retrieval data unchanged; reopening the window once to refresh stored items");
                    closeDeathWindow(ctx);
                    return true;
                }
                setStatus("Death recovery: items remain; check fee or inventory space");
                logThrottled("Take all had no effect; stored items still present: " + storedSignature);
                Time.sleep(900, 1400);
                return true;
            }
            String before = inventorySignature(ctx);
            if (collectionInventoryBefore == null) {
                collectionInventoryBefore = inventoryQuantities(ctx);
                collectionExpectedItems = new HashMap<>();
                for (String stored : storedItems) {
                    String[] parts = stored.split(":");
                    collectionExpectedItems.merge(Integer.parseInt(parts[0]), Long.parseLong(parts[1]), Long::sum);
                }
            }
            lastTakeAllInventorySignature = before;
            takeAllAttempts++;
            nextTakeAllAt = now + 2_500L;
            if (clickKnownDeathButton(ctx, InterfaceID.DEATH_OFFICE,
                    childId(InterfaceID.DeathOffice.TAKEALL), "Death Office take all")) {
                Time.sleep(850, 1500, () -> !before.equals(inventorySignature(ctx))
                        || !storedItems.equals(readDeathOfficeItems(ctx)), 100);
                if (!before.equals(inventorySignature(ctx))) {
                    logger.accept("[DeathRecovery] Inventory changed after Take all; checking remaining stored items");
                }
                finishConfirmedCollection(ctx);
            }
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
            org.example.core.navigation.CameraMovement.focus(ctx, death, logger);
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

    private boolean hasActiveGravestone(APIContext ctx) {
        try {
            return ctx.vars() != null && ctx.vars().getVarbit(VarbitID.GRAVESTONE_VISIBLE) > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean needsGraveTransfer(APIContext ctx) {
        return !transferCheckComplete && (recoveryPending || hasActiveGravestone(ctx));
    }

    private void confirmGraveTransfer() {
        transferCheckComplete = true;
        pendingTransferSnapshot = null;
        logger.accept("[DeathRecovery] No active grave remains after the retrieval conversation; collection is now allowed");
    }

    private boolean selectTransferOption(APIContext ctx, WidgetChild option, String label) {
        transferConversationStarted = true;
        String text = normalize(visibleText(option));
        if (text.contains("bring") || text.contains("pay your fee") || isReclaimConfirmationOption(text))
            pendingTransferSnapshot = dialogueSnapshot(ctx);
        return selectDeathOption(ctx, option, label, false);
    }

    private void enterDeathOffice(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextEntranceAt) {
            Time.sleep(250, 450);
            return;
        }
        SceneObject entrance = ctx.objects().query().id(DEATH_ENTRANCES)
                .tileDistance(60).results().nearest();
        if (entrance == null || !entrance.isValid()) {
            setStatus("Death recovery: walking to Lumbridge graveyard entrance");
            logThrottled("Recovery is pending outside the office; locating the Death's Domain entrance");
            Navigation.walkToNoTeleports(ctx, LUMBRIDGE_DEATH_APPROACH);
            nextEntranceAt = System.currentTimeMillis() + 1000L;
            return;
        }
        if (entrance.getPlane() != ctx.localPlayer().getPlane() || entrance.tileDistanceTo(ctx) > 6) {
            setStatus("Death recovery: walking to Death's Domain");
            Navigation.walkToNoTeleports(ctx, entrance.getLocation());
            nextEntranceAt = System.currentTimeMillis() + 800L;
            return;
        }
        setStatus("Death recovery: entering Death's Domain");
        if (!entrance.isVisible() && !CameraMovement.focus(ctx, entrance, logger)) return;
        nextEntranceAt = System.currentTimeMillis() + 3000L;
        for (String action : new String[]{"Enter", "Use", "Open"}) {
            if (!entrance.hasAction(action)) continue;
            org.example.core.navigation.AccessRecovery.interaction(ctx, entrance, action);
            logger.accept("[DeathRecovery] Entering Death's Domain: " + action + " at " + entrance.getLocation());
            if (entrance.interact(action) || ctx.menu().interact(action, entrance, true)) {
                Time.sleep(1200, 2200, () -> findDeathNpc(ctx) != null || findOfficeExitPortal(ctx) != null, 100);
            }
            return;
        }
        logThrottled("Known Death's Domain entrance has no usable action: " + entrance.getActions());
    }

    private boolean collectFromDeath(APIContext ctx, NPC death) {
        if (death == null || !death.isValid() || firstDeathTutorialActive
                || System.currentTimeMillis() < nextCollectAt) return false;
        List<String> actions = death.getActions();
        if (actions == null) return false;
        for (String action : actions) {
            if (!isCollectAction(normalize(action))) continue;
            nextCollectAt = System.currentTimeMillis() + 4_000L;
            setStatus("Death recovery: collecting items from Death");
            logger.accept("[DeathRecovery] Using Death NPC action: " + action);
            if (ctx.menu().interact(action, death, true)
                    || death.interact(action)
                    || ctx.menu().interact(action, death, false)) {
                retrievalDialogueActive = true;
                Time.sleep(900, 1500, () -> hasDeathInterfaceOpen(ctx)
                        || ctx.dialogues().isDialogueOpen(), 100);
                return true;
            }
        }
        return false;
    }

    private boolean isCollectAction(String text) {
        return text.equals("collect") || text.startsWith("collect-")
                || text.equals("reclaim") || text.startsWith("reclaim-");
    }

    private boolean isFirstDeathTutorialFrame(APIContext ctx, List<WidgetChild> options) {
        if (tutorialKnownComplete) return false;
        String speech = normalize(ctx.dialogues().getText());
        if (speech.contains("so, mortal") || speech.contains("you have died, as all mortals do"))
            return true;
        NPC death = findDeathNpc(ctx);
        if (death != null && death.getActions() != null && death.getActions().stream()
                .anyMatch(action -> isCollectAction(normalize(action)))) return false;
        for (WidgetChild option : options) {
            String text = normalize(visibleText(option));
            if (text.contains("how do i know what will happen to my items")
                    || text.contains("how do i pay a gravestone fee")
                    || text.contains("how long do i have to return to my gravestone")
                    || text.equals("what happens when i die?")) return true;
        }
        return false;
    }

    private void loadTutorialStatus(APIContext ctx) {
        String account;
        try {
            account = SkillCapManager.accountIdentifier(ctx);
        } catch (RuntimeException ignored) {
            return;
        }
        if (account.isBlank() || account.equals(tutorialAccount)) return;
        if (!tutorialAccount.isBlank()) resetVisitState();
        suppressOldGraveUntil = 0L;
        tutorialAccount = account;
        tutorialKnownComplete = Files.exists(tutorialMarker(account));
        recoveryPending = Files.exists(pendingRecoveryMarker());
        if (recoveryPending)
            logger.accept("[DeathRecovery] Resuming the account's pending item recovery");
        if (tutorialKnownComplete)
            logger.accept("[DeathRecovery] First-death tutorial previously completed for this account");
    }

    private void markTutorialComplete() {
        if (tutorialKnownComplete || tutorialAccount.isBlank()) return;
        try {
            Path marker = tutorialMarker(tutorialAccount);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "completed\n", StandardCharsets.UTF_8);
            tutorialKnownComplete = true;
            logger.accept("[DeathRecovery] First-death tutorial completion saved for this account");
        } catch (IOException error) {
            logger.accept("[DeathRecovery] Could not save tutorial completion: " + error.getMessage());
        }
    }

    private Path pendingRecoveryMarker() {
        Path completed = tutorialMarker(tutorialAccount);
        return completed.resolveSibling(completed.getFileName().toString().replace(".complete", ".pending"));
    }

    private void savePendingRecovery(boolean pending) {
        if (tutorialAccount.isBlank()) return;
        try {
            Path marker = pendingRecoveryMarker();
            if (pending) {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "item-recovery-pending\n", StandardCharsets.UTF_8);
            } else Files.deleteIfExists(marker);
        } catch (IOException error) {
            logger.accept("[DeathRecovery] Could not save pending recovery state: " + error.getMessage());
        }
    }

    private Path tutorialMarker(String account) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(account.getBytes(StandardCharsets.UTF_8));
            return tutorialDirectory.resolve(HexFormat.of().formatHex(hash) + ".complete");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private boolean retryTalkToDeath(APIContext ctx, NPC death) {
        long now = System.currentTimeMillis();
        if (sawDialogueThisVisit && now - lastRecoveryProgressAt < SILENT_CONTINUE_WINDOW_MILLIS)
            return false;
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

        if (hasDeathInterfaceOpen(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            Time.sleep(300, 500);
        }

        SceneObject portal = findExitPortal(ctx);
        if (portal == null || !portal.isValid()) {
            logThrottled("Death recovery has no dialogue left but cannot see the exit portal yet");
            return false;
        }

        setStatus("Death recovery: leaving Death's Office");
        logger.accept("[DeathRecovery] Leaving Death's Office through portal");
        if (portal.tileDistanceTo(ctx) > 8) {
            org.example.core.navigation.CameraMovement.focus(ctx, portal, logger);
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
        SceneObject officePortal = findOfficeExitPortal(ctx);
        if (officePortal != null) {
            return officePortal;
        }
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

    private SceneObject findOfficeExitPortal(APIContext ctx) {
        SceneObject portal = ctx.objects().query()
                .id(ObjectID1.DEATH_OFFICE_EXITPORTAL)
                .tileDistance(35).results().nearest();
        return portal != null && portal.isValid() ? portal : null;
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

        return null;
    }

    private boolean shouldTalkToDeath(NPC death) {
        if (death == null) {
            return false;
        }
        return !tutorialKnownComplete && !firstDeathTutorialFinished
                && !spokeToDeathThisVisit && !sawDialogueThisVisit
                && (death.getActions() == null || death.getActions().stream()
                .noneMatch(action -> isCollectAction(normalize(action))));
    }

    private boolean canTryExitPortal(NPC death) {
        if (ctxStillInDialogueCooldown()) {
            return false;
        }
        return deathItemsTaken && (!firstDeathTutorialActive || firstDeathTutorialFinished
                || allTopicsConfirmed()) && (death == null || spokeToDeathThisVisit || sawDialogueThisVisit);
    }

    private boolean ctxStillInDialogueCooldown() {
        return sawDialogueThisVisit && System.currentTimeMillis() - lastDialogueAt < DIALOGUE_TRANSITION_MILLIS;
    }

    private boolean isInDeathRecoveryContext(APIContext ctx) {
        return isLocalPlayerDead(ctx)
                || hasDeathInterfaceOpen(ctx)
                || findDeathNpc(ctx) != null
                || findOfficeExitPortal(ctx) != null
                || (visitStartedAt != 0L && hasDeathDialogueOption(ctx));
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

        for (WidgetChild option : visibleDialogueOptions(ctx)) {
            String text = normalize(visibleText(option));
            if (text.contains("death")
                    || text.contains("gravestone")) {
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

        WidgetChild confirmation = firstDialogueOptionMatching(ctx, this::isConfirmationOption);
        if (confirmation != null) {
            return selectDeathOption(ctx, confirmation, "Death dialogue confirmation", false);
        }

        WidgetChild backOption = firstDialogueOptionMatching(ctx, this::isBackOption);
        if (backOption != null) {
            return selectDeathOption(ctx, backOption, "Death dialogue back option", false);
        }

        WidgetChild exitOption = firstDialogueOptionMatching(ctx, this::isExitDialogueOption);
        if (exitOption != null && allTopicsConfirmed()) {
            tutorialExitChosen = true;
            return selectDeathOption(ctx, exitOption, "Death dialogue final exit option", false);
        }

        logThrottled("No unvisited Death topic or navigation option; options=" + dialogueSnapshot(ctx));
        return false;
    }

    private WidgetChild firstUnvisitedInformativeDeathOption(APIContext ctx) {
        for (String priority : FIRST_DEATH_DIALOGUE_PRIORITY) {
            String needle = normalize(priority);
            for (WidgetChild option : visibleDialogueOptions(ctx)) {
                String text = normalize(visibleText(option));
                if (text.contains(needle)
                        && !isDangerousWidget(option)
                        && isInformativeDeathDialogueOption(text)
                        && !completedDeathDialogueOptions.contains(text)) {
                    return option;
                }
            }
        }

        for (WidgetChild option : visibleDialogueOptions(ctx)) {
            String text = normalize(visibleText(option));
            if (isInformativeDeathDialogueOption(text)
                    && !isDangerousWidget(option)
                    && !completedDeathDialogueOptions.contains(text)) {
                return option;
            }
        }

        return null;
    }

    private WidgetChild firstDialogueOptionMatching(APIContext ctx, DialogueOptionMatcher matcher) {
        for (WidgetChild option : visibleDialogueOptions(ctx)) {
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
        observeDialogueProgress(ctx);
        String text = normalize(visibleText(option));
        String snapshot = dialogueSnapshot(ctx);
        int attempt = nextDialogueAttempt(snapshot);
        pendingDeathDialogueOption = remember ? text : null;
        pendingDeathDialogueSnapshot = snapshot;
        setStatus("Death recovery: selecting Death dialogue");
        logger.accept("[DeathRecovery] Selecting " + label + " (attempt " + (attempt + 1)
                + "): " + visibleText(option));
        switch (attempt % 3) {
            case 0 -> ctx.dialogues().selectOption(candidate -> normalize(candidate).equals(text), true);
            case 1 -> option.click(false);
            default -> clickWidgetCenter(ctx, option);
        }
        Time.sleep(1000, 1600, () -> !snapshot.equals(dialogueSnapshot(ctx)), 100);
        observeDialogueProgress(ctx);
        return true;
    }

    private boolean isInformativeDeathDialogueOption(String text) {
        return !text.isBlank()
                && !isReclaimRequestOption(text)
                && !isReclaimConfirmationOption(text)
                && !isNegativeOption(text)
                && !isConfirmationOption(text)
                && !isBackOption(text)
                && !isExitDialogueOption(text);
    }

    private boolean waitForDialoguePace(APIContext ctx, String snapshot, boolean choice) {
        if (!paceDialogue || snapshot.isBlank()) return false;
        long now = System.currentTimeMillis();
        if (!snapshot.equals(pacedDialogueSnapshot)) {
            pacedDialogueSnapshot = snapshot;
            nextDialogueActionAt = now + ThreadLocalRandom.current().nextLong(
                    choice ? 1_650L : 1_350L, choice ? 3_250L : 2_850L);
        }
        if (now >= nextDialogueActionAt) return false;
        setStatus("Death recovery: reading Death dialogue");
        Time.sleep(250, 450);
        return true;
    }

    private String inventorySignature(APIContext ctx) {
        ItemContainer inventory = ctx.game().getItemContainer(InventoryId.INVENTORY);
        if (inventory != null && inventory.getItemIds() != null && inventory.getStackSizes() != null)
            return Arrays.toString(inventory.getItemIds()) + ":" + Arrays.toString(inventory.getStackSizes());
        List<ItemWidget> items = ctx.inventory().getItems();
        if (items == null) return "unavailable";
        return items.stream().filter(item -> item != null && item.getId() > 0)
                .map(item -> item.getId() + ":" + item.getStackSize())
                .sorted().toList().toString();
    }

    private Map<Integer, Long> inventoryQuantities(APIContext ctx) {
        Map<Integer, Long> quantities = new HashMap<>();
        ItemContainer inventory = ctx.game().getItemContainer(InventoryId.INVENTORY);
        if (inventory != null && inventory.getItemIds() != null && inventory.getStackSizes() != null) {
            int[] ids = inventory.getItemIds();
            int[] sizes = inventory.getStackSizes();
            for (int i = 0; i < Math.min(ids.length, sizes.length); i++) {
                if (ids[i] > 0 && sizes[i] > 0) quantities.merge(ids[i], (long) sizes[i], Long::sum);
            }
        } else {
            List<ItemWidget> items = ctx.inventory().getItems();
            if (items == null) return null;
            for (ItemWidget item : items) {
                if (item != null && item.getId() > 0 && item.getStackSize() > 0)
                    quantities.merge(item.getId(), (long) item.getStackSize(), Long::sum);
            }
        }
        return quantities;
    }

    private boolean finishConfirmedCollection(APIContext ctx) {
        if (collectionInventoryBefore == null || collectionExpectedItems == null || collectionExpectedItems.isEmpty())
            return false;
        Map<Integer, Long> current = inventoryQuantities(ctx);
        if (current == null) return false;
        for (Map.Entry<Integer, Long> expected : collectionExpectedItems.entrySet()) {
            long received = current.getOrDefault(expected.getKey(), 0L)
                    - collectionInventoryBefore.getOrDefault(expected.getKey(), 0L);
            if (received < expected.getValue()) return false;
        }
        deathItemsTaken = true;
        sawTopicMenuThisVisit = true;
        sawDialogueThisVisit = true;
        logger.accept("[DeathRecovery] All requested items confirmed in inventory; closing retrieval window");
        closeDeathWindow(ctx);
        return true;
    }

    private boolean isDeathCofferOpen(APIContext ctx) {
        WidgetGroup coffer = ctx.widgets().get(InterfaceID.DEATH_COFFER);
        return coffer != null && coffer.isValid() && coffer.isVisible();
    }

    private List<String> readDeathOfficeItems(APIContext ctx) {
        WidgetChild items = ctx.widgets().get(InterfaceID.DEATH_OFFICE,
                childId(InterfaceID.DeathOffice.ITEMS));
        // This is a structural container. It need not have screen bounds or be
        // visible itself; off-screen item slots must also count toward recovery.
        if (items == null) return null;
        List<WidgetChild> slots = items.getChildren();
        if (slots == null) return null;
        List<String> contents = new ArrayList<>();
        collectStoredItemSlots(slots, contents, 0);
        contents.sort(String::compareTo);
        return contents;
    }

    private void collectStoredItemSlots(List<WidgetChild> slots, List<String> contents, int depth) {
        if (slots == null || depth > 6) return;
        for (WidgetChild slot : slots) {
            if (slot == null) continue;
            // getId() is the component ID, not an item ID.
            int itemId = slot.getItemId();
            int quantity = slot.getStackSize();
            if (itemId > 0 && quantity > 0) contents.add(itemId + ":" + quantity);
            collectStoredItemSlots(slot.getChildren(), contents, depth + 1);
        }
    }

    private void closeDeathWindow(APIContext ctx) {
        deathWindowCloseAttempts++;
        ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
        Time.sleep(450, 750, () -> !hasDeathInterfaceOpen(ctx), 100);
        if (hasDeathInterfaceOpen(ctx)) {
            ctx.widgets().closeInterface();
            Time.sleep(450, 750, () -> !hasDeathInterfaceOpen(ctx), 100);
        }
        if (hasDeathInterfaceOpen(ctx)) {
            WidgetChild close = ctx.widgets().getAllChildren(widget ->
                    isVisibleWidget(widget) && widget.getGroup() != null
                            && widget.getGroup().getIndex() == InterfaceID.DEATH_OFFICE
                            && (normalize(visibleText(widget)).equals("x")
                            || widget.hasAction("Close"))).stream().findFirst().orElse(null);
            if (close != null) {
                close.click(false);
                Time.sleep(450, 750, () -> !hasDeathInterfaceOpen(ctx), 100);
            }
        }
        if (hasDeathInterfaceOpen(ctx))
            logThrottled("Death retrieval window still visible after close attempt " + deathWindowCloseAttempts);
        else logger.accept("[DeathRecovery] Death retrieval window closed; proceeding to exit");
    }

    private boolean isBackOption(String text) {
        return text.matches("(?:go )?back[.!]?|previous(?: options| menu)?[.!]?")
                || text.equals("back to the previous menu");
    }

    private boolean isConfirmationOption(String text) {
        return text.matches("(?:yes|ok|okay|i understand|understood|got it|continue|carry on)[.!]?");
    }

    private boolean isReclaimRequestOption(String text) {
        boolean item = text.contains("item") || text.contains("grave") || text.contains("belonging");
        boolean transfer = text.contains("bring") || text.contains("collect") || text.contains("retrieve")
                || text.contains("send") || text.contains("take") || text.contains("reclaim");
        return item && transfer && !text.startsWith("how ") && !text.startsWith("what ")
                && !text.startsWith("where ") && !text.startsWith("why ");
    }

    private boolean isReclaimConfirmationOption(String text) {
        return isConfirmationOption(text) || text.startsWith("yes,") || text.startsWith("yes ")
                || text.startsWith("please do") || text.startsWith("bring them")
                || text.startsWith("i'm sure") || text.startsWith("i am sure");
    }

    private boolean isExitDialogueOption(String text) {
        if (text.startsWith("how ") || text.startsWith("what ") || text.startsWith("where ")
                || text.startsWith("why ") || text.startsWith("when ")) {
            return false;
        }
        return text.matches("(?:leave|exit|return|goodbye|bye|nothing|no thanks)[.!]?")
                || text.contains("can i leave")
                || text.contains("i'd like to leave")
                || text.contains("i want to leave")
                || text.contains("i'm ready to leave")
                || text.startsWith("take me ")
                || text.startsWith("send me ")
                || text.contains("that's all")
                || text.contains("thats all")
                || text.contains("nothing else")
                || text.contains("i think i'm done here")
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
        dialogueLoadingSince = 0L;
        lastSilentContinueAt = 0L;
        silentContinueAttempts = 0;
        nextPortalClickAt = 0L;
        spokeToDeathThisVisit = false;
        sawDialogueThisVisit = false;
        sawDeathNpcThisVisit = false;
        firstDeathTutorialActive = false;
        firstDeathTutorialFinished = false;
        tutorialExitChosen = false;
        nextCollectAt = 0L;
        nextEntranceAt = 0L;
        retrievalDialogueActive = false;
        transferConversationStarted = false;
        transferCheckComplete = false;
        transferChoiceConfirmed = false;
        pendingTransferSnapshot = null;
        nextTransferTalkAt = 0L;
        completedDeathDialogueOptions.clear();
        discoveredDeathDialogueOptions.clear();
        sawTopicMenuThisVisit = false;
        pendingDeathDialogueOption = null;
        pendingDeathDialogueSnapshot = null;
        lastDialogueSnapshot = "";
        lastAttemptSnapshot = "";
        dialogueAttempts = 0;
        pacedDialogueSnapshot = "";
        nextDialogueActionAt = 0L;
        deathItemsTaken = false;
        collectionInventoryBefore = null;
        collectionExpectedItems = null;
        collectionWindowRefreshed = false;
        takeAllAttempts = 0;
        nextTakeAllAt = 0L;
        lastTakeAllInventorySignature = null;
        lastStoredItemsSignature = null;
        deathWindowCloseAttempts = 0;
        lastRecoveryProgressAt = visitStartedAt;
        logger.accept("[DeathRecovery] Death context detected; taking over runtime");
        onDeathDetected.run();
    }

    private void resetVisitState() {
        visitStartedAt = 0L;
        lastDeathTalkAt = 0L;
        lastDialogueAt = 0L;
        lastSilentContinueAt = 0L;
        silentContinueAttempts = 0;
        nextPortalClickAt = 0L;
        nextRecoveryLogAt = 0L;
        spokeToDeathThisVisit = false;
        sawDialogueThisVisit = false;
        sawDeathNpcThisVisit = false;
        firstDeathTutorialActive = false;
        firstDeathTutorialFinished = false;
        tutorialExitChosen = false;
        nextCollectAt = 0L;
        nextEntranceAt = 0L;
        retrievalDialogueActive = false;
        transferConversationStarted = false;
        transferCheckComplete = false;
        transferChoiceConfirmed = false;
        pendingTransferSnapshot = null;
        nextTransferTalkAt = 0L;
        completedDeathDialogueOptions.clear();
        discoveredDeathDialogueOptions.clear();
        sawTopicMenuThisVisit = false;
        pendingDeathDialogueOption = null;
        pendingDeathDialogueSnapshot = null;
        lastDialogueSnapshot = "";
        lastAttemptSnapshot = "";
        dialogueAttempts = 0;
        pacedDialogueSnapshot = "";
        nextDialogueActionAt = 0L;
        deathItemsTaken = false;
        collectionInventoryBefore = null;
        collectionExpectedItems = null;
        collectionWindowRefreshed = false;
        takeAllAttempts = 0;
        nextTakeAllAt = 0L;
        lastTakeAllInventorySignature = null;
        lastStoredItemsSignature = null;
        deathWindowCloseAttempts = 0;
        lastRecoveryProgressAt = 0L;
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
        return normalized.matches("(?:no|nope|cancel|never mind|not right now)[.!]?")
                || normalized.startsWith("cancel ")
                || normalized.startsWith("no, ");
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
                && widget.isVisible()
                && !widget.isHidden()
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
        if (text != null && !normalize(text).isBlank()) {
            return text.trim();
        }
        String rawText = widget.getRawText();
        return rawText == null ? "" : rawText.trim();
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
