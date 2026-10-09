package org.example;

import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID1;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.LocatableEntity;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.query.NPCQueryBuilder;
import com.epicbot.api.shared.query.SceneObjectQueryBuilder;
import com.epicbot.api.shared.query.result.LocatableEntityQueryResult;
import org.example.core.ScriptStats;
import org.example.core.runtime.DeathRecoveryController;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

    public static void main(String[] args) throws Exception {
        testOptionClassification();
        testUnconfirmedSelectionAndTransitions();
        testFirstDeathAndPortal();
        testStallAndUnrelatedDialogue();
        System.out.println("PASS: Death tutorial, confirmed selections, transitions, submenus, portal and stall guard");
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
        require(scenario.stoppedReason != null && scenario.stoppedReason.contains("90 seconds"),
                "An unchanged dialogue must stop with diagnostics instead of looping forever");
        require(scenario.logs.stream().anyMatch(log -> log.contains("dialogue=options:") && log.contains("confirmed=")),
                "Stall diagnostics must include the current options and confirmed topics");

        scenario = new Scenario();
        controller = scenario.controller();
        invoke(controller, "beginVisitIfNeeded");
        setField(controller, "visitStartedAt", System.currentTimeMillis() - 301_000L);
        scenario.root();
        controller.execute(scenario.ctx);
        require(scenario.stoppedReason != null && scenario.stoppedReason.contains("5 minutes"),
                "Cycling between different dialogue screens must also have a bounded recovery time");

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
        private boolean loading;
        private boolean rejectSelection;
        private boolean submenu;
        private String activeTopic;
        private String text = "";
        private List<String> options = List.of();
        private int page;
        private int deathTalks;
        private int portalUses;
        private int backSelections;
        private int keyboardSelections;
        private int widgetClicks;
        private int mouseClicks;
        private String stoppedReason;

        private Scenario() {
            ctx = proxy(APIContext.class, (object, method, arguments) ->
                    proxy(method.getReturnType(), (api, action, values) -> apiCall(method.getName(), action, values)));
        }

        private DeathRecoveryController controller() {
            return new DeathRecoveryController(logs::add, new ScriptStats());
        }

        private Object apiCall(String surface, Method method, Object[] args) {
            String action = method.getName();
            if (surface.equals("client") && action.equals("isLoggedIn")) return true;
            if (surface.equals("localPlayer") && action.equals("getLocation")) return new Tile(3200, 3200, 0);
            if (surface.equals("script") && action.equals("stop")) { stoppedReason = (String) args[0]; return null; }
            if (surface.equals("widgets") && action.equals("getAllChildren")) return List.of();
            if (surface.equals("dialogues")) {
                switch (action) {
                    case "isDialogueOpen": return dialogueOpen;
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
                    @Override public LocatableEntityQueryResult<SceneObject> results() {
                        return nearestResult(ctx, inOffice ? portal() : null);
                    }
                };
            }
            if (surface.equals("mouse") && action.equals("click")) {
                mouseClicks++;
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
                case "interact" -> { deathTalks++; dialogueOpen = true; text = "Let me explain the death mechanics."; yield true; }
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
                    require(explained.containsAll(ROOT_TOPICS) && explained.containsAll(SUB_TOPICS),
                            "Portal was used before all tutorial responses finished");
                    portalUses++; inOffice = false; yield true;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private void root() {
            submenu = false;
            dialogueOpen = true;
            text = "";
            List<String> menu = new ArrayList<>(ROOT_TOPICS);
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
                require(explained.containsAll(ROOT_TOPICS) && explained.containsAll(SUB_TOPICS),
                        "Final dismissal was selected before finishing the tutorial");
                dialogueOpen = false; options = List.of(); text = "";
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
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
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
