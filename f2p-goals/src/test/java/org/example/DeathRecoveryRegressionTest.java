package org.example;

import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID1;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.LocatableEntity;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.Player;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ItemContainer;
import com.epicbot.api.shared.query.NPCQueryBuilder;
import com.epicbot.api.shared.query.SceneObjectQueryBuilder;
import com.epicbot.api.shared.query.result.LocatableEntityQueryResult;
import org.example.core.ScriptStats;
import org.example.core.runtime.DeathRecoveryController;
import com.epicbot.api.shared.methods.IDialogueAPI;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class DeathRecoveryRegressionTest {
    private static final List<String> ROOT_TOPICS = List.of(
            "What happens when I die?",
            "How do I return to my gravestone?",
            "I don't know how item fees work.");
    private static final List<String> SUB_TOPICS = List.of("Where is my grave?", "What is the grave timer?");
    private static final String RECLAIM_REQUEST = "Can you bring my items here?";

    public static void main(String[] args) throws Exception {
        testOptionClassification();
        testSpeechWidgetsAreNotQuestions();
        testSilentContinueTransition();
        testTakeAllStopsAfterInventoryChanges();
        testStaleStoredListClosesAfterVerifiedReceipt();
        testUnrelatedInventoryChangeDoesNotFinishCollection();
        testEmptyDeathOfficeSkipsTakeAll();
        testCofferDoesNotTriggerTakeAll();
        testEmptyDecorativeChildrenExitWithEscape();
        testUnknownContentsNeverClicksTakeAll();
        testPartialCollectionKeepsRemainingItems();
        testUnchangedItemsDoNotCountAsEmpty();
        testLaterDeathSkipsConversationAndCollects();
        testTutorialCompletionPersistsByAccount();
        testRecoveryRetainsControlAfterRespawn();
        testPendingRecoverySurvivesRestart();
        testActiveGraveRestoresMissedRecovery();
        testRecoveryConfirmationIsNotOptionalTutorial();
        testGraveTransferPrecedesCollectionAndEmptyExit();
        testDialoguePaceIsRandomized();
        testUnconfirmedSelectionAndTransitions();
        testFirstDeathAndPortal();
        testStallAndUnrelatedDialogue();
        System.out.println("PASS: Death tutorial, confirmed selections, transitions, submenus, portal and stall guard");
    }

    private static void testSpeechWidgetsAreNotQuestions() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.dialogueOpen = true;
        scenario.dialogueType = IDialogueAPI.DialogueType.OPTION;
        scenario.text = "So, mortal... you have died, as all mortals do.";
        scenario.options = List.of("Click here to continue", scenario.text);
        require(controller.shouldExecute(scenario.ctx), "Death NPC activates the recovery controller");
        require(((String) invoke(controller, "dialogueSnapshot", scenario.ctx)).startsWith("continue:"),
                "speech and continue widgets form a dialogue page, not a question menu");
        controller.execute(scenario.ctx);
        require(scenario.options.containsAll(ROOT_TOPICS),
                "continue action advances to the actual Death questions");
        require(scenario.logs.stream().noneMatch(log -> log.contains("Selecting Death first-dialogue topic")
                        && log.contains("So, mortal")),
                "spoken text must never be selected as a topic");
        require(!(boolean) invoke(controller, "canTryExitPortal", scenario.death()),
                "visible unanswered Death topics block the portal");
    }

    private static void testSilentContinueTransition() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.dialogueOpen = true;
        scenario.text = "A gravestone appears on the ground near where you died.";
        scenario.options = List.of("Click here to continue", scenario.text);
        require(controller.shouldExecute(scenario.ctx), "Death dialogue should activate recovery");
        invoke(controller, "beginVisitIfNeeded");
        invoke(controller, "observeDialogueProgress", scenario.ctx);
        setField(controller, "sawDialogueThisVisit", true);
        setField(controller, "firstDeathTutorialActive", true);
        scenario.dialogueOpen = false;
        scenario.text = "";
        scenario.options = List.of();
        scenario.spaceContinues = true;
        setField(controller, "lastDialogueAt", System.currentTimeMillis() - 3_000L);
        controller.execute(scenario.ctx);
        require(scenario.spacePresses > 0, "Temporary missing dialogue controls should retry Continue with Space");
        require(scenario.deathTalks == 0, "A temporary gap must not restart the Death conversation");
        require(scenario.options.containsAll(ROOT_TOPICS), "Space should reveal the next question menu");
    }

    private static void testTakeAllStopsAfterInventoryChanges() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.storedItemsInOffice = true;
        scenario.containerNotVisible = true;
        scenario.nativeInventory = true;
        require(controller.shouldExecute(scenario.ctx), "Death retrieval window activates recovery");
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 1, "Take all should be clicked once");
        require(scenario.logs.stream().anyMatch(log -> log.contains("Inventory changed after Take all")),
                "Native inventory container changes must be observed even when its widgets are hidden");
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 1, "Inventory change must prevent a second Take all click");
        require(!scenario.deathTakeAllVisible, "Retrieval window should close after items enter inventory");
    }

    private static void testEmptyDeathOfficeSkipsTakeAll() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        require(controller.shouldExecute(scenario.ctx), "An empty Death window still activates recovery");
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 0 && !scenario.deathTakeAllVisible,
                "An empty item container must close without clicking Take all");
    }

    private static void testStaleStoredListClosesAfterVerifiedReceipt() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.storedItemsInOffice = true;
        scenario.takeAllLeavesItems = true;
        scenario.staleStoredList = true;
        scenario.nativeInventory = true;
        controller.shouldExecute(scenario.ctx);
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 1 && scenario.escapePresses == 1
                        && !scenario.deathTakeAllVisible && (boolean) getField(controller, "deathItemsTaken"),
                "Verified item quantities in inventory must close a stale retrieval window immediately");
    }

    private static void testUnrelatedInventoryChangeDoesNotFinishCollection() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.storedItemsInOffice = true;
        scenario.takeAllLeavesItems = true;
        scenario.staleStoredList = true;
        scenario.nativeInventory = true;
        scenario.unrelatedInventoryChange = true;
        controller.shouldExecute(scenario.ctx);
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(!(boolean) getField(controller, "deathItemsTaken") && scenario.deathTakeAllVisible,
                "An unrelated item entering inventory cannot confirm receipt of the stored items");
    }

    private static void testCofferDoesNotTriggerTakeAll() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.deathCofferVisible = true;
        require(controller.shouldExecute(scenario.ctx), "Death's Coffer activates recovery");
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 0 && !scenario.deathCofferVisible,
                "Death's Coffer must close without clicking a stale Take all widget");
    }

    private static void testEmptyDecorativeChildrenExitWithEscape() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.decorativeChild = true;
        scenario.containerNotVisible = true;
        scenario.tutorialDone = true;
        scenario.itemsBrought = true;
        require(controller.shouldExecute(scenario.ctx), "Stale retrieval window activates recovery");
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 0 && !scenario.deathTakeAllVisible && scenario.escapePresses == 1,
                "Widget IDs and decoration must not count as items; an empty window closes with Escape");
        require((boolean) getField(controller, "deathItemsTaken"),
                "Closing an empty window must release the exit flow");
        controller.execute(scenario.ctx);
        require(scenario.portalUses == 1, "A closed empty window must lead to the exit portal");
    }

    private static void testUnknownContentsNeverClicksTakeAll() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        controller.shouldExecute(scenario.ctx);
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 0 && scenario.deathTakeAllVisible,
                "An unreadable container must never trigger a blind Take all or be treated as empty");
    }

    private static void testPartialCollectionKeepsRemainingItems() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.storedItemsInOffice = true;
        scenario.takeAllLeavesItems = true;
        controller.shouldExecute(scenario.ctx);
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(!(boolean) getField(controller, "deathItemsTaken") && scenario.deathTakeAllVisible,
                "Inventory movement alone does not mean all stored items were collected");
        setField(controller, "nextTakeAllAt", 0L);
        invoke(controller, "handleDeathInterface", scenario.ctx);
        require(scenario.takeAllClicks == 2 && scenario.escapePresses == 0,
                "Remaining stored items keep recovery active instead of exiting");
    }

    private static void testUnchangedItemsDoNotCountAsEmpty() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        scenario.storedItemsInOffice = true;
        scenario.takeAllNoEffect = true;
        controller.shouldExecute(scenario.ctx);
        for (int tick = 0; tick < 4; tick++) {
            setField(controller, "nextTakeAllAt", 0L);
            invoke(controller, "handleDeathInterface", scenario.ctx);
        }
        require(scenario.takeAllClicks == 3 && !scenario.deathTakeAllVisible && scenario.escapePresses == 1,
                "Unchanged retrieval data must refresh once without assuming collection succeeded");
        require(!(boolean) getField(controller, "deathItemsTaken"),
                "Recovery cannot finish while the API still reports stored items");
        scenario.deathTakeAllVisible = true;
        for (int tick = 0; tick < 4; tick++) {
            setField(controller, "nextTakeAllAt", 0L);
            invoke(controller, "handleDeathInterface", scenario.ctx);
        }
        require(scenario.takeAllClicks == 6 && scenario.deathTakeAllVisible && scenario.escapePresses == 1,
                "Refreshing must be bounded rather than repeatedly reopening or clicking Take all");
    }

    private static void testLaterDeathSkipsConversationAndCollects() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.tutorialDone = true;
        scenario.root();
        require(controller.shouldExecute(scenario.ctx), "Death's Office activates recovery");
        controller.execute(scenario.ctx);
        require(scenario.options.isEmpty() && scenario.deathTalks == 0,
                "Later visits must dismiss optional dialogue without selecting its topics");
        setField(controller, "lastDialogueAt", 0L);
        controller.execute(scenario.ctx);
        require(scenario.deathTakeAllVisible && scenario.deathTalks == 0,
                "Later visits must use Death's Collect NPC action directly");
    }

    private static void testTutorialCompletionPersistsByAccount() throws Exception {
        Path directory = Files.createTempDirectory("death-tutorial-test-");
        try {
            Scenario scenario = new Scenario();
            scenario.username = "Tutorial Tester";
            DeathRecoveryController first = scenario.controller();
            setField(first, "tutorialDirectory", directory);
            require(first.shouldExecute(scenario.ctx), "Death context loads the account profile");
            invoke(first, "markTutorialComplete");
            DeathRecoveryController restarted = scenario.controller();
            setField(restarted, "tutorialDirectory", directory);
            require(restarted.shouldExecute(scenario.ctx), "Restart loads the same account profile");
            require((boolean) getField(restarted, "tutorialKnownComplete"),
                    "A completed tutorial must remain completed after script restart");
        } finally {
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void testRecoveryRetainsControlAfterRespawn() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.inOffice = false;
        scenario.dead = true;
        scenario.tutorialDone = true;
        scenario.entranceVisible = true;
        require(controller.shouldExecute(scenario.ctx), "Death must activate recovery outside the office");
        controller.execute(scenario.ctx);
        scenario.dead = false;
        require(controller.shouldExecute(scenario.ctx), "Respawn must not release normal combat or gear purchases");
        controller.execute(scenario.ctx);
        require(scenario.entranceUses == 1 && scenario.inOffice, "Recovery must enter Death's Domain after respawn");
        for (int tick = 0; tick < 14 && scenario.inOffice; tick++) {
            controller.execute(scenario.ctx);
            if (!scenario.dialogueOpen) setField(controller, "lastDialogueAt", 0L);
        }
        require(scenario.itemsBrought && !scenario.inOffice && scenario.deathTalks == 1 && scenario.explained.isEmpty(),
                "Later deaths must ask for recovery, collect and leave without repeating tutorial topics");
        require(!controller.shouldExecute(scenario.ctx), "Normal runtime resumes only after recovery and exit");
    }

    private static void testPendingRecoverySurvivesRestart() throws Exception {
        Path directory = Files.createTempDirectory("pending-death-test-");
        try {
            Scenario scenario = new Scenario();
            scenario.username = "Recovery Tester";
            scenario.inOffice = false;
            scenario.dead = true;
            scenario.tutorialDone = true;
            scenario.entranceVisible = true;
            DeathRecoveryController first = scenario.controller();
            setField(first, "tutorialDirectory", directory);
            require(first.shouldExecute(scenario.ctx), "Death must save pending recovery");
            scenario.dead = false;
            DeathRecoveryController restarted = scenario.controller();
            setField(restarted, "tutorialDirectory", directory);
            require(restarted.shouldExecute(scenario.ctx), "Pending recovery must survive a restart after respawn");
            restarted.execute(scenario.ctx);
            require(scenario.entranceUses == 1, "Restarted recovery must enter Death's Domain instead of resuming tasks");
            for (int tick = 0; tick < 14 && scenario.inOffice; tick++) {
                restarted.execute(scenario.ctx);
                if (!scenario.dialogueOpen) setField(restarted, "lastDialogueAt", 0L);
            }
            require(!restarted.shouldExecute(scenario.ctx), "Verified recovery and exit must clear pending state");
            DeathRecoveryController completed = scenario.controller();
            setField(completed, "tutorialDirectory", directory);
            require(!completed.shouldExecute(scenario.ctx), "Completed recovery must not restart on the next script launch");
        } finally {
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void testActiveGraveRestoresMissedRecovery() throws Exception {
        Scenario scenario = new Scenario();
        scenario.inOffice = false;
        scenario.activeGrave = true;
        scenario.entranceVisible = true;
        DeathRecoveryController controller = scenario.controller();
        require(controller.shouldExecute(scenario.ctx), "A live gravestone must restore recovery even if death was missed");
        controller.execute(scenario.ctx);
        require(scenario.entranceUses == 1, "The gravestone varbit must lead to Death's Office");
    }

    private static void testRecoveryConfirmationIsNotOptionalTutorial() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tutorialDone = true;
        scenario.dialogueOpen = true;
        scenario.options = List.of(RECLAIM_REQUEST, "What is this place?");
        DeathRecoveryController controller = scenario.controller();
        controller.shouldExecute(scenario.ctx);
        controller.execute(scenario.ctx);
        controller.execute(scenario.ctx);
        require(scenario.itemsBrought && scenario.explained.isEmpty(),
                "Required item-transfer confirmation must be handled without selecting optional tutorial topics");
    }

    private static void testGraveTransferPrecedesCollectionAndEmptyExit() throws Exception {
        Scenario scenario = new Scenario();
        scenario.tutorialDone = true;
        scenario.activeGrave = true;
        scenario.deathTakeAllVisible = true;
        scenario.knownStoredItems = true;
        DeathRecoveryController controller = scenario.controller();
        controller.shouldExecute(scenario.ctx);
        controller.execute(scenario.ctx);
        require(!scenario.deathTakeAllVisible && scenario.takeAllClicks == 0
                        && !(boolean) getField(controller, "deathItemsTaken"),
                "An empty office window must not finish recovery while grave items have not been brought back");
        for (int tick = 0; tick < 16 && scenario.inOffice; tick++) {
            controller.execute(scenario.ctx);
            if (!scenario.dialogueOpen) setField(controller, "lastDialogueAt", 0L);
        }
        require(scenario.deathTalks == 1 && scenario.takeAllClicks == 1 && !scenario.activeGrave
                        && !scenario.inOffice && scenario.explained.isEmpty(),
                "Talk-to and grave transfer must precede Take all, empty verification and portal exit");
    }

    private static void testDialoguePaceIsRandomized() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = new DeathRecoveryController(scenario.logs::add, new ScriptStats());
        scenario.root();
        invoke(controller, "beginVisitIfNeeded");
        long before = System.currentTimeMillis();
        invoke(controller, "handleDeathDialogue", scenario.ctx);
        long delay = (long) getField(controller, "nextDialogueActionAt") - before;
        require(delay >= 1_650L && delay < 3_700L, "Question menu should have a randomized reading delay");
        require(scenario.keyboardSelections == 0 && scenario.widgetClicks == 0,
                "Dialogue must not be selected on its first visible tick");
    }

    private static void testOptionClassification() throws Exception {
        DeathRecoveryController controller = new DeathRecoveryController(message -> {}, new ScriptStats());
        Scenario scenario = new Scenario();
        WidgetChild back = scenario.widget("Back", false);
        require(invoke(controller, "visibleText", back).equals("Back"), "Text and raw text must not be duplicated");
        require((boolean) invoke(controller, "isBackOption", "back"), "Back must navigate, not become a topic");
        require(!(boolean) invoke(controller, "isNegativeOption", "i don't know how item fees work."),
                "Required topics containing know/no must not be discarded");
        require(!(boolean) invoke(controller, "isExitDialogueOption", "how do i return to my gravestone?"),
                "A question about returning to a grave is not an exit");
        require((boolean) invoke(controller, "isExitDialogueOption", "nothing else, thanks."),
                "The final dismissal must be selectable even though it contains nothing");
        require(!(boolean) invoke(controller, "isVisibleWidget", scenario.widget("Back", true)),
                "Hidden/stale widgets must not receive clicks");
    }

    private static void testUnconfirmedSelectionAndTransitions() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.root();
        scenario.rejectSelection = true;
        invoke(controller, "beginVisitIfNeeded");
        String snapshot = (String) invoke(controller, "dialogueSnapshot", scenario.ctx);
        for (int attempt = 0; attempt < 3; attempt++) {
            invoke(controller, "handleDeathDialogue", scenario.ctx);
            require(confirmed(controller).isEmpty(), "Successful input without a server transition must not skip a topic");
            require(snapshot.equals(invoke(controller, "dialogueSnapshot", scenario.ctx)), "Rejected input changed the fixture");
        }
        require(scenario.keyboardSelections > 0 && scenario.widgetClicks > 0 && scenario.mouseClicks > 0,
                "Stalled input must retry using keyboard, widget and mouse paths");

        scenario.rejectSelection = false;
        invoke(controller, "handleDeathDialogue", scenario.ctx);
        require(!confirmed(controller).isEmpty(), "An actual new dialogue must confirm the accepted topic");
        Set<String> beforeTransition = Set.copyOf(confirmed(controller));
        scenario.options = List.of();
        scenario.text = "";
        scenario.dialogueOpen = true;
        scenario.loading = true;
        invoke(controller, "handleDeathDialogue", scenario.ctx);
        scenario.loading = false;
        invoke(controller, "handleDeathDialogue", scenario.ctx);
        require(confirmed(controller).equals(beforeTransition), "Loading/empty frames must preserve completed topics");
        require((long) getField(controller, "visitStartedAt") != 0L, "A transition must not reset the visit");
        scenario.dialogueOpen = false;
        require((boolean) invoke(controller, "handleDeathDialogue", scenario.ctx), "Brief closure must wait before a portal click");
    }

    private static void testFirstDeathAndPortal() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        require(controller.shouldExecute(scenario.ctx), "Death's NPC/portal must activate recovery");
        for (int tick = 0; tick < 40 && scenario.inOffice; tick++) {
            controller.execute(scenario.ctx);
            if (!scenario.dialogueOpen) {
                setField(controller, "lastDialogueAt", 0L);
            }
        }
        require(!scenario.inOffice && scenario.portalUses == 1,
                "The first-death flow must leave through Use on the portal; explained=" + scenario.explained
                        + "; dialogue=" + scenario.text + "; options=" + scenario.options
                        + "; logs=" + scenario.logs);
        require(scenario.explained.containsAll(ROOT_TOPICS) && scenario.explained.containsAll(SUB_TOPICS),
                "All required topics and submenu topics must finish before leaving");
        require(scenario.itemsBrought, "Death must bring the gravestone items before the portal is used");
        require(scenario.backSelections == 1, "Back must return from the submenu without being remembered as a topic");
        require(scenario.deathTalks == 1, "Recovery must not restart the NPC conversation between dialogue pages");
        require(scenario.stoppedReason == null, "A progressing tutorial must not trigger the stall guard");
        require(!controller.shouldExecute(scenario.ctx), "Recovery must release control after leaving the office");
        require(confirmed(controller).isEmpty(), "The next visit must not inherit this visit's topics");

        scenario.inOffice = true;
        scenario.npcVisible = false;
        require(controller.shouldExecute(scenario.ctx), "The known exit portal must retain context while Death is out of view");
    }

    private static void testStallAndUnrelatedDialogue() throws Exception {
        Scenario scenario = new Scenario();
        DeathRecoveryController controller = scenario.controller();
        scenario.root();
        invoke(controller, "beginVisitIfNeeded");
        invoke(controller, "observeDialogueProgress", scenario.ctx);
        setField(controller, "lastRecoveryProgressAt", System.currentTimeMillis() - 91_000L);
        controller.execute(scenario.ctx);
        require(scenario.stoppedReason == null,
                "An unchanged dialogue must retain control and retry instead of stopping the script");
        require(scenario.logs.stream().anyMatch(log -> log.contains("dialogue=options:") && log.contains("confirmed=")),
                "Stall diagnostics must include the current options and confirmed topics");

        scenario = new Scenario();
        controller = scenario.controller();
        invoke(controller, "beginVisitIfNeeded");
        setField(controller, "visitStartedAt", System.currentTimeMillis() - 301_000L);
        scenario.root();
        controller.execute(scenario.ctx);
        require(scenario.stoppedReason == null
                        && scenario.logs.stream().anyMatch(log -> log.contains("after 5 minutes; retrying dialogue")),
                "the five-minute limit must diagnose and retry without abandoning Death's Office");

        scenario = new Scenario();
        controller = scenario.controller();
        scenario.inOffice = false;
        scenario.options = List.of("Where are my items?", "Tell me about death.", "Nothing else, thanks.");
        scenario.dialogueOpen = true;
        require(!controller.shouldExecute(scenario.ctx), "Generic items dialogue outside the office must not be hijacked");
    }

    private static final class Scenario {
        private final List<String> logs = new ArrayList<>();
        private final Set<String> explained = new HashSet<>();
        private final APIContext ctx;
        private boolean inOffice = true;
        private boolean npcVisible = true;
        private boolean dialogueOpen;
        private IDialogueAPI.DialogueType dialogueType;
        private boolean loading;
        private boolean rejectSelection;
        private boolean submenu;
        private boolean itemsBrought;
        private boolean tutorialDone;
        private boolean recoveryTalk;
        private boolean dead;
        private boolean activeGrave;
        private boolean entranceVisible;
        private int entranceUses;
        private String activeTopic;
        private String text = "";
        private List<String> options = List.of();
        private int page;
        private int deathTalks;
        private int portalUses;
        private int backSelections;
        private int keyboardSelections;
        private int spacePresses;
        private boolean spaceContinues;
        private boolean deathTakeAllVisible;
        private boolean deathCofferVisible;
        private boolean knownStoredItems;
        private boolean storedItemsInOffice;
        private boolean takeAllNoEffect;
        private boolean takeAllLeavesItems;
        private boolean staleStoredList;
        private boolean unrelatedInventoryChange;
        private boolean decorativeChild;
        private boolean containerNotVisible;
        private boolean nativeInventory;
        private int escapePresses;
        private int takeAllClicks;
        private int widgetClicks;
        private int mouseClicks;
        private String stoppedReason;
        private String username = "";

        private Scenario() {
            ctx = proxy(APIContext.class, (object, method, arguments) ->
                    proxy(method.getReturnType(), (api, action, values) -> apiCall(method.getName(), action, values)));
        }

        private DeathRecoveryController controller() {
            DeathRecoveryController controller = new DeathRecoveryController(logs::add, new ScriptStats());
            try { setField(controller, "paceDialogue", false); }
            catch (Exception error) { throw new IllegalStateException(error); }
            return controller;
        }

        private Object apiCall(String surface, Method method, Object[] args) {
            String action = method.getName();
            if (surface.equals("client") && action.equals("isLoggedIn")) return true;
            if (surface.equals("client") && action.equals("getUsername")) return username;
            if (surface.equals("localPlayer") && action.equals("getLocation")) return new Tile(3200, 3200, 0);
            if (surface.equals("localPlayer") && action.equals("getPlane")) return 0;
            if (surface.equals("localPlayer") && action.equals("get"))
                return proxy(Player.class, (object, called, values) -> called.getName().equals("isDead")
                        ? dead : defaultValue(called.getReturnType()));
            if (surface.equals("vars") && action.equals("getVarbit"))
                return ((Number) args[0]).intValue() == VarbitID.GRAVESTONE_VISIBLE && activeGrave ? 1 : 0;
            if (surface.equals("script") && action.equals("stop")) { stoppedReason = (String) args[0]; return null; }
            if (surface.equals("widgets") && action.equals("getAllChildren")) return List.of();
            if (surface.equals("widgets") && action.equals("get")) {
                int group = ((Number) args[0]).intValue();
                if (args.length == 1 && group == InterfaceID.DEATH_COFFER && deathCofferVisible)
                    return proxy(WidgetGroup.class, (object, called, values) -> switch (called.getName()) {
                        case "isValid", "isVisible" -> true;
                        default -> defaultValue(called.getReturnType());
                    });
                if (!deathTakeAllVisible || group != InterfaceID.DEATH_OFFICE) return null;
                if (args.length == 1) return proxy(WidgetGroup.class, (object, called, values) -> switch (called.getName()) {
                    case "isValid", "isVisible" -> true;
                    default -> defaultValue(called.getReturnType());
                });
                if (((Number) args[1]).intValue() == (InterfaceID.DeathOffice.ITEMS & 0xffff)
                        && knownStoredItems)
                    return proxy(WidgetChild.class, (object, called, values) -> switch (called.getName()) {
                        case "isValid" -> true;
                        case "isVisible" -> !containerNotVisible;
                        case "isHidden" -> false;
                        case "getWidth", "getHeight" -> containerNotVisible ? 0 : 20;
                        case "getChildren" -> storedItemsInOffice
                                ? List.of(proxy(WidgetChild.class, (slot, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                    case "isValid" -> true;
                                    case "getItemId" -> 995;
                                    case "getStackSize" -> 100;
                                    case "getId" -> InterfaceID.DeathOffice.ITEMS;
                                    default -> defaultValue(slotMethod.getReturnType());
                                })) : decorativeChild ? List.of(proxy(WidgetChild.class, (slot, slotMethod, slotArgs) -> switch (slotMethod.getName()) {
                                    case "isValid" -> true;
                                    case "getId" -> InterfaceID.DeathOffice.ITEMS;
                                    case "getItemId" -> -1;
                                    case "getStackSize" -> 0;
                                    default -> defaultValue(slotMethod.getReturnType());
                                })) : List.of();
                        default -> defaultValue(called.getReturnType());
                    });
                return widget("Take all", false);
            }
            if (surface.equals("widgets") && action.equals("closeInterface")) {
                deathTakeAllVisible = false;
                deathCofferVisible = false;
                dialogueOpen = false;
                options = List.of();
                text = "";
                return defaultValue(method.getReturnType());
            }
            if (surface.equals("inventory") && action.equals("getItems")) {
                if (takeAllClicks == 0 || takeAllNoEffect || nativeInventory) return List.of();
                return List.of(proxy(ItemWidget.class, (object, called, values) -> switch (called.getName()) {
                    case "getId" -> 995;
                    case "getStackSize" -> takeAllLeavesItems && !staleStoredList ? 50 : 100;
                    default -> defaultValue(called.getReturnType());
                }));
            }
            if (surface.equals("game") && action.equals("getItemContainer") && nativeInventory)
                return proxy(ItemContainer.class, (object, called, values) -> switch (called.getName()) {
                    case "getItemIds" -> new int[]{takeAllClicks > 0 && unrelatedInventoryChange ? 526 : 995};
                    case "getStackSizes" -> takeAllClicks == 0 ? new int[]{50}
                            : new int[]{takeAllLeavesItems && !staleStoredList ? 100 : 150};
                    default -> defaultValue(called.getReturnType());
                });
            if (surface.equals("keyboard") && action.equals("sendKey")) {
                if (((Number) args[0]).intValue() == java.awt.event.KeyEvent.VK_SPACE) {
                    spacePresses++;
                    if (spaceContinues) root();
                }
                if (((Number) args[0]).intValue() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    escapePresses++;
                    deathTakeAllVisible = false;
                    deathCofferVisible = false;
                    dialogueOpen = false;
                    options = List.of();
                    text = "";
                }
                return defaultValue(method.getReturnType());
            }
            if (surface.equals("dialogues")) {
                switch (action) {
                    case "isDialogueOpen": return dialogueOpen;
                    case "getDialogueType": return dialogueType;
                    case "isLoading": return loading;
                    case "canContinue": return dialogueOpen && !text.isEmpty();
                    case "getText": return text;
                    case "getOptions": return options.stream().map(option -> widget(option, false)).toList();
                    case "selectContinue": advance(); return true;
                    case "selectOption":
                        @SuppressWarnings("unchecked") Predicate<String> match = (Predicate<String>) args[0];
                        if (args.length == 2 && Boolean.TRUE.equals(args[1])) keyboardSelections++;
                        for (String option : options) {
                            if (match.test(option)) { select(option); return true; }
                        }
                        return false;
                }
            }
            if (surface.equals("npcs") && action.equals("query")) {
                return new NPCQueryBuilder(ctx) {
                    @Override public LocatableEntityQueryResult<NPC> results() {
                        return nearestResult(ctx, inOffice && npcVisible ? death() : null);
                    }
                };
            }
            if (surface.equals("objects") && action.equals("query")) {
                return new SceneObjectQueryBuilder(ctx) {
                    private boolean entranceQuery;
                    @Override public SceneObjectQueryBuilder id(int... ids) {
                        entranceQuery = java.util.Arrays.stream(ids).anyMatch(id -> id == ObjectID1.DEATH_OFFICE_ACCESS_GRAVE);
                        return this;
                    }
                    @Override public LocatableEntityQueryResult<SceneObject> results() {
                        return nearestResult(ctx, entranceQuery
                                ? (!inOffice && entranceVisible ? entrance() : null)
                                : (inOffice ? portal() : null));
                    }
                };
            }
            if (surface.equals("mouse") && action.equals("click")) {
                mouseClicks++;
                if (deathTakeAllVisible) {
                    takeAllClicks++;
                    if (!takeAllLeavesItems && !takeAllNoEffect) storedItemsInOffice = false;
                    activeGrave = false;
                    itemsBrought = true;
                    return true;
                }
                if (!options.isEmpty()) select(options.get(0));
                return true;
            }
            return defaultValue(method.getReturnType());
        }

        private WidgetChild widget(String value, boolean hidden) {
            return proxy(WidgetChild.class, (object, method, args) -> switch (method.getName()) {
                case "getText", "getRawText" -> value;
                case "isValid", "isVisible" -> true;
                case "isHidden" -> hidden;
                case "getWidth", "getHeight" -> 20;
                case "getCentralPoint" -> new Point(10, 10);
                case "click" -> { widgetClicks++; select(value); yield true; }
                default -> defaultValue(method.getReturnType());
            });
        }

        private NPC death() {
            return proxy(NPC.class, (object, method, args) -> switch (method.getName()) {
                case "getId" -> NpcID.DEATH_OFFICE_DEATH;
                case "getName" -> "Death";
                case "isValid" -> true;
                case "getActions" -> tutorialDone ? List.of("Talk-to", "Collect") : List.of("Talk-to");
                case "interact" -> {
                    String action = args[0] instanceof String[] actions ? actions[0] : String.valueOf(args[0]);
                    if (action.equals("Collect")) {
                        deathTakeAllVisible = true;
                        knownStoredItems = true;
                        storedItemsInOffice = true;
                        dialogueOpen = false;
                        options = List.of();
                        text = "";
                    } else {
                        deathTalks++;
                        dialogueOpen = true;
                        recoveryTalk = tutorialDone;
                        text = tutorialDone ? "Hello mortal. Have you come to retrieve something you lost when you died?"
                                : "So, mortal... you have died, as all mortals do.";
                    }
                    yield true;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private SceneObject portal() {
            return proxy(SceneObject.class, (object, method, args) -> switch (method.getName()) {
                case "getId" -> ObjectID1.DEATH_OFFICE_EXITPORTAL;
                case "getName" -> "Portal";
                case "isValid" -> true;
                case "hasAction" -> java.util.Arrays.asList((String[]) args[0]).contains("Use");
                case "interact" -> {
                    require(args[0].equals("Use"), "The office portal action is Use");
                    require(tutorialDone || (explained.containsAll(ROOT_TOPICS) && explained.containsAll(SUB_TOPICS)),
                            "Portal was used before all tutorial responses finished");
                    require(itemsBrought, "Portal was used before requesting lost items");
                    portalUses++; inOffice = false; yield true;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private SceneObject entrance() {
            return proxy(SceneObject.class, (object, method, args) -> switch (method.getName()) {
                case "getId" -> ObjectID1.DEATH_OFFICE_ACCESS_GRAVE;
                case "getName" -> "Death's Domain";
                case "getLocation" -> new Tile(3240, 3200, 0);
                case "getPlane" -> 0;
                case "tileDistanceTo" -> 1;
                case "isValid", "isVisible" -> true;
                case "hasAction" -> java.util.Arrays.asList((String[]) args[0]).contains("Enter");
                case "interact" -> { entranceUses++; inOffice = true; yield true; }
                default -> defaultValue(method.getReturnType());
            });
        }

        private void root() {
            submenu = false;
            dialogueOpen = true;
            text = "";
            List<String> menu = new ArrayList<>(ROOT_TOPICS);
            if (explained.containsAll(ROOT_TOPICS) && explained.containsAll(SUB_TOPICS) && !itemsBrought)
                menu.add(RECLAIM_REQUEST);
            menu.add("Can I leave now?");
            options = List.copyOf(menu);
        }

        private void subMenu() {
            submenu = true;
            dialogueOpen = true;
            text = "";
            List<String> menu = new ArrayList<>(SUB_TOPICS);
            menu.add("Back");
            options = List.copyOf(menu);
        }

        private void select(String option) {
            if (rejectSelection) return;
            if (option.equals("Can I leave now?")) {
                tutorialDone = true;
                dialogueOpen = false; options = List.of(); text = "";
            } else if (option.equals(RECLAIM_REQUEST)) {
                dialogueOpen = true; options = List.of("Yes, bring them here.", "No thanks."); text = "";
            } else if (option.equals("Yes, bring them here.") || option.equals("Yes, please.")) {
                itemsBrought = true; dialogueOpen = false; options = List.of(); text = "";
                recoveryTalk = false;
                activeGrave = false;
                deathTakeAllVisible = true;
                knownStoredItems = true;
                storedItemsInOffice = true;
            } else if (option.equals("Back")) {
                backSelections++;
                explained.add(ROOT_TOPICS.get(1));
                root();
            } else if (option.equals(ROOT_TOPICS.get(1))) {
                subMenu();
            } else {
                activeTopic = option;
                page = 0;
                options = List.of();
                text = "Explanation of " + option + " page 1";
            }
        }

        private void advance() {
            if (recoveryTalk) {
                text = "";
                options = activeGrave ? List.of(RECLAIM_REQUEST, "What is this place?")
                        : List.of("Yes, please.", "What is this place?");
                return;
            }
            if (activeTopic == null) { root(); return; }
            if (page++ == 0) {
                text = "Explanation of " + activeTopic + " page 2";
                return;
            }
            explained.add(activeTopic);
            activeTopic = null;
            if (submenu) subMenu(); else root();
        }
    }

    private static <T extends LocatableEntity> LocatableEntityQueryResult<T> nearestResult(APIContext ctx, T entity) {
        return new LocatableEntityQueryResult<T>(new ArrayList<>(), ctx, null) {
            @Override public T nearest() { return entity; }
        };
    }

    @SuppressWarnings("unchecked")
    private static Set<String> confirmed(DeathRecoveryController controller) throws Exception {
        return (Set<String>) getField(controller, "completedDeathDialogueOptions");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("toString")) return type.getSimpleName();
            return handler.invoke(object, method, args);
        }));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Method method = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
