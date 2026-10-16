package org.example.modules.questing;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;
import org.example.core.questing.QuestActionGuard;

import java.awt.event.KeyEvent;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Standalone X Marks the Spot route, using the quest varbit and inventory as the step source. */
public final class XMarksTheSpotQuestModule implements ManagedF2PModule {
    private static final IQuestAPI.Quest QUEST = IQuestAPI.Quest.X_MARKS_THE_SPOT;
    private static final int VARBIT = IQuestAPI.QuestVarbits.QUEST_X_MARKS_THE_SPOT.getId();
    private static final Tile VEOS_LUMBRIDGE = new Tile(3228, 3242, 0);
    private static final Tile VEOS_PUB_DOOR = new Tile(3225, 3240, 0);
    private static final Tile VEOS_PUB_OUTSIDE = new Tile(3224, 3240, 0);
    private static final Tile VEOS_PUB_INSIDE = new Tile(3227, 3240, 0);
    private static final Tile BOB_DIG = new Tile(3230, 3209, 0);
    private static final Tile CASTLE_DIG = new Tile(3203, 3212, 0);
    private static final Tile DRAYNOR_DIG = new Tile(3109, 3264, 0);
    private static final Tile PIG_PEN_DIG = new Tile(3078, 3259, 0);
    private static final Area PIG_PEN_GATE_AREA = new Area(3074, 3255, 3082, 3262);
    private static final Tile VEOS_SARIM = new Tile(3054, 3245, 0);
    private static final Tile LUMBRIDGE_STORE = new Tile(3212, 3248, 0);
    private static final Tile GE = new Tile(3165, 3486, 0);
    private static final Area LUMBRIDGE_STORE_AREA = new Area(3207, 3243, 3216, 3253);
    private static final Area VEOS_PUB_DOOR_AREA = new Area(0, 3225, 3240, 3225, 3240);
    private static final String[] LAMP_SKILLS = {
            "Attack", "Strength", "Ranged", "Magic", "Defence",
            "Hitpoints", "Prayer", "Mining", "Smithing", "Fishing",
            "Cooking", "Firemaking", "Woodcutting"
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final QuestActionGuard guard;
    private long nextActionAt;
    private long nextTravelLogAt;
    private long completionCloseAt;
    private long geOfferAt;
    private long nextGeCollectAt;
    private int lastStage = -1;
    private int lastX = -1;
    private int lastY = -1;
    private int stagnantTicks;
    private int storeFailures;
    private int emptyCoinBankChecks;
    private boolean triedNearbyBank;
    private boolean geFallback;
    private boolean completionClosed;
    private String selectedLampSkill;
    private boolean lampSkillClicked;
    private long nextLampLogAt;
    private long nextDialogueLogAt;
    private long nextFundingLogAt;
    private int exactDigWalkAttempts;
    private int exactDigBestDistance = Integer.MAX_VALUE;
    private long pigGateOpenedAt;
    private long nextPigGateLogAt;
    private long dialogueLoadingUntil;
    private long dialogueWaitUntil;
    private String lastDialogueObservation = "";
    private int dialogueActions;
    private int questItemBankCheckedStage = -1;
    private long dialogueLoadingSince;
    private long nextDialogueLoadingLogAt;
    private String unverifiedDialogueSnapshot = "";
    private long unverifiedDialogueSince;
    private int unverifiedDialogueRecoveryAttempts;

    public XMarksTheSpotQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
        this.guard = new QuestActionGuard(logger);
    }

    @Override public String name() { return "quests.x_marks_the_spot"; }
    @Override public boolean shouldExecute(APIContext ctx) { return ctx != null && !isComplete(ctx); }
    @Override public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(QUEST) && rewardLamp(ctx) == null;
    }
    @Override public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0 : ctx.quests().isStarted(QUEST) ? 92 : 62;
    }
    @Override public boolean isProtectedSubphase(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(QUEST) && rewardLamp(ctx) != null;
    }
    @Override public String protectedSubphaseName(APIContext ctx) {
        return "selecting X Marks the Spot lamp reward";
    }

    @Override public void execute(APIContext ctx) {
        if (ctx == null || ctx.localPlayer() == null || System.currentTimeMillis() < nextActionAt) return;
        stats.setTrainingSkill("Questing");
        if (ctx.quests().isCompleted(QUEST)) { handleCompletedQuest(ctx); return; }

        int stage = stage(ctx);
        Tile here = ctx.localPlayer().getLocation();
        if (stage != lastStage) {
            log("X Marks the Spot: quest stage " + lastStage + " -> " + stage);
            lastStage = stage;
            guard.reset();
            stagnantTicks = 0;
            exactDigWalkAttempts = 0;
            exactDigBestDistance = Integer.MAX_VALUE;
            pigGateOpenedAt = 0L;
            questItemBankCheckedStage = -1;
        }
        if (here == null) return;
        if (here.getX() != lastX || here.getY() != lastY) {
            lastX = here.getX();
            lastY = here.getY();
            stagnantTicks = 0;
        }

        if (handleDialogue(ctx, stage)) return;
        if (ctx.grandExchange().isOpen() && !geFallback) { ctx.grandExchange().close(); pause(650, 1050); return; }
        if (ctx.store().isOpen() && usableSpade(ctx)) { ctx.store().close(); pause(500, 850); return; }

        if (restoreBankedQuestItem(ctx, stage)) return;
        if (stage >= 2 && stage <= 6 && !usableSpade(ctx)) {
            acquireSpade(ctx);
            return;
        }
        if (ctx.bank().isOpen()) { ctx.bank().close(); pause(500, 850); return; }
        if (ctx.store().isOpen()) { ctx.store().close(); pause(500, 850); return; }

        switch (stage) {
            case 0, 1 -> talkToVeos(ctx, VEOS_LUMBRIDGE, stage);
            case 2 -> dig(ctx, BOB_DIG, stage, "north of Bob's Brilliant Axes");
            case 3 -> dig(ctx, CASTLE_DIG, stage, "behind Lumbridge Castle kitchen");
            case 4 -> dig(ctx, DRAYNOR_DIG, stage, "north-west of Draynor jail");
            case 5 -> dig(ctx, PIG_PEN_DIG, stage, "Draynor pig pen");
            case 6 -> {
                if (ctx.inventory().contains(ItemID.CLUEQUEST_CASKET)) talkToVeos(ctx, VEOS_SARIM, stage);
                else dig(ctx, PIG_PEN_DIG, stage, "recover missing casket in pig pen");
            }
            case 7 -> talkToVeos(ctx, VEOS_SARIM, stage);
            default -> {
                status("unrecognized quest stage " + stage + "; waiting for verified state");
                pause(1800, 3000);
            }
        }
    }

    private int stage(APIContext ctx) {
        int value = ctx.vars().getVarbit(VARBIT);
        if (value <= 1 && ctx.quests().isStarted(QUEST)) value = 2;
        if (ctx.inventory().contains(ItemID.CLUEQUEST_CASKET)) value = Math.max(value, 6);
        else if (ctx.inventory().contains(ItemID.CLUEQUEST_CLUE4)) value = Math.max(value, 5);
        else if (ctx.inventory().contains(ItemID.CLUEQUEST_CLUE3)) value = Math.max(value, 4);
        else if (ctx.inventory().contains(ItemID.CLUEQUEST_CLUE2)) value = Math.max(value, 3);
        else if (ctx.inventory().contains(ItemID.CLUEQUEST_CLUE1)) value = Math.max(value, 2);
        return value;
    }

    private boolean restoreBankedQuestItem(APIContext ctx, int stage) {
        int itemId = switch (stage) {
            case 2 -> ItemID.CLUEQUEST_CLUE1;
            case 3 -> ItemID.CLUEQUEST_CLUE2;
            case 4 -> ItemID.CLUEQUEST_CLUE3;
            case 5 -> ItemID.CLUEQUEST_CLUE4;
            case 6 -> ItemID.CLUEQUEST_CASKET;
            default -> -1;
        };
        if (itemId < 0 || ctx.inventory().contains(itemId)) return false;
        if (questItemBankCheckedStage == stage) return false;
        if (!ctx.bank().isOpen()) {
            status("checking bank for the current X Marks the Spot clue");
            if (Navigation.isBankReachable(ctx)) Navigation.openBank(ctx);
            else Navigation.walkToBank(ctx);
            pause(850, 1400);
            return true;
        }
        if (!ctx.bank().contains(itemId)) {
            questItemBankCheckedStage = stage;
            log("X Marks the Spot: stage " + stage + " quest item is not banked; following quest state");
            ctx.bank().close();
            pause(550, 900);
            return true;
        }
        if (ctx.inventory().isFull()) {
            ctx.bank().depositAllExcept(item -> item != null && isQuestInventoryItem(item.getName()));
            pause(650, 1050);
            return true;
        }
        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            pause(550, 850);
            return true;
        }
        if (!guard.allow("restore-quest-item:" + stage,
                stage + ":" + ctx.inventory().getCount(itemId), 1300L, 3)) {
            if (guard.isStalled("restore-quest-item:" + stage))
                status("banked quest item withdrawal stalled at stage " + stage);
            pause(1400, 2200);
            return true;
        }
        boolean withdrawn = ctx.bank().withdraw(1, itemId);
        log("X Marks the Spot: restored stage " + stage + " quest item from bank=" + withdrawn);
        pause(750, 1200);
        return true;
    }

    private void dig(APIContext ctx, Tile target, int stage, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (!sameTile(here, target)) {
            if (distance(here, target) <= 10) {
                if (sameTile(target, PIG_PEN_DIG) && !target.canReach(ctx)
                        && System.currentTimeMillis() - pigGateOpenedAt >= 5000L
                        && openPigPenGate(ctx, here, target)) return;
                walkToExactDigTile(ctx, here, target);
                return;
            }
            walk(ctx, target, label);
            return;
        }
        exactDigWalkAttempts = 0;
        ItemWidget spade = ctx.inventory().getItem(ItemID.SPADE);
        if (spade == null || spade.isNoted()) { acquireSpade(ctx); return; }
        if (guard.allow("dig-arrival:" + stage, stage + ":" + here, 0L, 1)) {
            log("X Marks the Spot: standing on exact dig tile " + here
                    + "; usable spade confirmed");
        }
        String observation = stage + ":" + here + ":" + questItemSignature(ctx);
        if (!guard.allow("dig:" + stage, observation, 2500L, 3)) {
            if (guard.isStalled("dig:" + stage)) status("dig stalled at " + target + "; check quest stage and tile");
            pause(900, 1400);
            return;
        }
        boolean clicked = spade.click(false);
        log("X Marks the Spot: left-clicked spade on exact dig tile " + target
                + " (" + label + ")=" + clicked);
        pause(1600, 2700);
    }

    private boolean openPigPenGate(APIContext ctx, Tile here, Tile target) {
        SceneObject gate = ctx.walking().getBlockingObjectBetween(here, target);
        if (gate == null || !gate.isValid() || !gate.hasAction("Open")
                || gate.getName() == null || !gate.getName().toLowerCase(Locale.ROOT).contains("gate")) {
            gate = ctx.objects().query().nameContains("Gate").actions("Open")
                    .within(PIG_PEN_GATE_AREA).results().nearest();
        }
        if (gate == null || !gate.isValid()) {
            if (System.currentTimeMillis() >= nextPigGateLogAt) {
                log("X Marks the Spot: pig pen dig tile " + target
                        + " is unreachable from " + here + "; waiting for its gate to be visible");
                nextPigGateLogAt = System.currentTimeMillis() + 10000L;
            }
            status("pig pen gate required before digging");
            pause(900, 1400);
            return true;
        }
        if (gate.tileDistanceTo(ctx) > 3) {
            status("approaching pig pen gate");
            ctx.walking().walkOnMap(gate.getLocation());
            pause(800, 1300);
            return true;
        }
        String observation = here + ":" + gate.getLocation() + ":" + gate.getId();
        if (!guard.allow("pig-pen-gate", observation, 1700L, 4)) {
            if (guard.isStalled("pig-pen-gate")) status("pig pen gate did not open; waiting for changed state");
            pause(850, 1300);
            return true;
        }
        org.example.core.navigation.AccessRecovery.interaction(ctx, gate, "Open");
        boolean opened = gate.interact("Open") || ctx.menu().interact("Open", gate, false);
        log("X Marks the Spot: opening pig pen gate id=" + gate.getId()
                + " at " + gate.getLocation() + "=" + opened);
        if (opened) {
            pigGateOpenedAt = System.currentTimeMillis();
            exactDigWalkAttempts = 0;
        }
        pause(1000, 1600);
        return true;
    }

    private void walkToExactDigTile(APIContext ctx, Tile here, Tile target) {
        int currentDistance = distance(here, target);
        if (currentDistance < exactDigBestDistance) {
            exactDigBestDistance = currentDistance;
            exactDigWalkAttempts = 0;
        }
        if (ctx.localPlayer().isMoving()) { pause(450, 750); return; }
        if (exactDigWalkAttempts >= 4) {
            status("cannot reach exact dig tile " + target + " from " + here
                    + "; direct clicks stopped after four attempts");
            pause(3000, 4200);
            return;
        }
        int attempt = ++exactDigWalkAttempts;
        boolean accepted;
        String method;
        switch (attempt) {
            case 1 -> { method = "left-click exact tile on screen"; accepted = target.click(false); }
            case 2 -> { method = "walk on screen"; accepted = ctx.walking().walkOnScreen(target); }
            case 3 -> { method = "walk on minimap"; accepted = ctx.walking().walkOnMap(target); }
            default -> { method = "Walk here interaction"; accepted = target.interact("Walk here"); }
        }
        log("X Marks the Spot: exact dig tile " + target + " input attempt=" + attempt + " method=" + method
                + " accepted=" + accepted + " from=" + here
                + " visible=" + target.isVisible() + " reachable=" + target.canReach(ctx));
        pause(1700, 2500);
    }

    private void talkToVeos(APIContext ctx, Tile destination, int stage) {
        if (distance(ctx.localPlayer().getLocation(), destination) > 8) {
            walk(ctx, destination, "Veos");
            return;
        }
        NPC veos = ctx.npcs().query().id(NpcID.VEOS_VISIBLE, NpcID.VEOS_VISIBLE_TRAVEL)
                .actions("Talk-to").results().nearest();
        if (veos == null || !veos.isValid() || veos.tileDistanceTo(ctx) > 10) {
            walk(ctx, destination, "Veos position");
            return;
        }
        if (stage <= 1 && openVeosPubEntrance(ctx, veos)) return;
        if (!veos.canReach(ctx, 1) && openRouteDoor(ctx, veos.getLocation())) return;
        String observation = stage + ":" + ctx.localPlayer().getLocation() + ":" + questItemSignature(ctx);
        if (!guard.allow("talk:" + stage, observation, 3000L, 3)) {
            if (guard.isStalled("talk:" + stage)) status("Veos dialogue did not advance; waiting for verified state");
            pause(900, 1400);
            return;
        }
        boolean clicked = veos.interact("Talk-to");
        if (clicked) dialogueLoadingUntil = System.currentTimeMillis() + 1800L;
        log("X Marks the Spot: talk to Veos at " + veos.getLocation() + "=" + clicked);
        pause(1400, 2500);
    }

    private boolean openVeosPubEntrance(APIContext ctx, NPC veos) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || veos == null || distance(here, VEOS_PUB_DOOR) > 10) return false;
        if (here.getX() > VEOS_PUB_DOOR.getX() && distance(here, veos.getLocation()) <= 3) return false;
        // EpicBot Objects inspector: closed Door id=1535 at [3225,3240,0].
        SceneObject door = null;
        for (SceneObject object : ctx.objects().getAt(VEOS_PUB_DOOR)) {
            if (object != null && object.isValid() && object.getId() == 1535) {
                door = object;
                break;
            }
        }
        if (door == null) door = ctx.objects().query().id(1535)
                .within(VEOS_PUB_DOOR_AREA).results().nearest();
        if (!isClosedDoor(door)) {
            if (here.getX() <= VEOS_PUB_DOOR.getX()) {
                String observation = stage(ctx) + ":" + here + ":door-open";
                if (guard.allow("veos-pub-cross", observation, 1400L, 3)) {
                    boolean walking = ctx.walking().walkOnMap(VEOS_PUB_INSIDE);
                    log("X Marks the Spot: crossing open pub doorway to " + VEOS_PUB_INSIDE + "=" + walking);
                } else if (guard.isStalled("veos-pub-cross")) {
                    status("pub doorway opened but crossing did not progress");
                }
                pause(800, 1350);
                return true;
            }
            return false;
        }
        if (door.tileDistanceTo(ctx) > 3) {
            status("approaching The Sheared Ram entrance");
            ctx.walking().walkOnMap(VEOS_PUB_OUTSIDE);
            pause(750, 1200);
            return true;
        }
        String observation = stage(ctx) + ":" + here + ":" + door.getLocation() + ":closed";
        if (!guard.allow("veos-pub-door", observation, 1700L, 3)) {
            if (guard.isStalled("veos-pub-door")) status("pub entrance did not open after three attempts");
            pause(850, 1300);
            return true;
        }
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        boolean opened = door.interact("Open") || ctx.menu().interact("Open", door, false);
        log("X Marks the Spot: opening The Sheared Ram entrance at " + door.getLocation()
                + " before talking to Veos=" + opened);
        pause(950, 1600);
        return true;
    }

    private static boolean isClosedDoor(SceneObject object) {
        return object != null && object.isValid() && object.hasAction("Open")
                && object.getName() != null
                && object.getName().toLowerCase(Locale.ROOT).contains("door");
    }

    private boolean handleDialogue(APIContext ctx, int stage) {
        long now = System.currentTimeMillis();
        boolean loading = ctx.dialogues().isLoading();
        if (loading && dialogueLoadingSince == 0L) dialogueLoadingSince = now;
        if (!loading) dialogueLoadingSince = 0L;
        boolean open = ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen();
        if (!open) {
            unverifiedDialogueSnapshot = "";
            unverifiedDialogueSince = 0L;
            unverifiedDialogueRecoveryAttempts = 0;
            return false;
        }
        List<WidgetChild> options = ctx.dialogues().getOptions();
        options = options == null ? List.of() : options.stream()
                .filter(option -> option != null && option.isValid() && !option.isHidden()).toList();
        String dialogueText = clean(ctx.dialogues().getText());
        if (loading && now - dialogueLoadingSince < 3500L) {
            pause(350, 650);
            return true;
        }
        if (loading && now >= nextDialogueLoadingLogAt) {
            logger.accept("X Marks the Spot: dialogue loading persisted; checking visible options at stage " + stage);
            nextDialogueLoadingLogAt = now + 12000L;
        }
        WidgetChild continueWidget = options.stream()
                .filter(option -> option != null && (clean(option.getText()).contains("click here to continue")
                        || clean(option.getRawText()).contains("click here to continue")))
                .findFirst().orElse(null);
        boolean hasChoices = options.stream().anyMatch(option -> {
            String label = clean(option.getText());
            return !label.isBlank() && !label.equals("all") && !label.equals("click here to continue");
        });
        boolean canContinue = continueWidget != null || ctx.dialogues().canContinue()
                && (hasChoices || !dialogueText.isBlank() && !dialogueText.equals("all"));
        if (!hasChoices && !canContinue) {
            if (System.currentTimeMillis() < dialogueLoadingUntil) {
                pause(350, 650);
                return true;
            }
            lastDialogueObservation = "";
            return false;
        }
        String observation = stage + ":" + dialogueText + ":" + options.stream()
                .map(option -> clean(option.getText())).toList();
        if (!observation.equals(lastDialogueObservation)) {
            lastDialogueObservation = observation;
            dialogueWaitUntil = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1050, 2301);
            logger.accept("X Marks the Spot: dialogue stage=" + stage + " text='" + dialogueText
                    + "' options=" + options.stream().map(option -> clean(option.getText())).toList());
        }
        if (System.currentTimeMillis() < dialogueWaitUntil) { pause(250, 450); return true; }
        // EpicBot can expose the continue widget through getOptions(), even when canContinue() is false.
        if (canContinue) {
            unverifiedDialogueSnapshot = "";
            if (guard.allow("continue:" + stage, observation, 900L, 12)) {
                ctx.mouse().moveRandomly(25, 80);
                Time.sleep(170, 420);
                if (++dialogueActions % 2 == 0) {
                    ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                    log("X Marks the Spot: continued dialogue by keyboard at stage " + stage);
                } else {
                    boolean advanced = ctx.dialogues().selectContinue();
                    if (!advanced && continueWidget != null) advanced = continueWidget.click(false);
                    if (!advanced) ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                    log("X Marks the Spot: continued dialogue by mouse/API at stage " + stage);
                }
                dialogueLoadingUntil = System.currentTimeMillis() + 1300L;
            }
            pause(800, 1600);
            return true;
        }
        if (options != null && !options.isEmpty()) {
            String[] expected = stage <= 2
                    ? new String[]{"i'm looking for a quest", "sounds good, what should i do", "can i help", "yes", "okay, thanks veos"}
                    : new String[]{"yes", "give", "casket", "treasure"};
            for (String phrase : expected) {
                for (WidgetChild option : options) {
                    String text = clean(option.getText());
                    if (text.isEmpty()) text = clean(option.getRawText());
                    if (!text.contains(phrase)) continue;
                    unverifiedDialogueSnapshot = "";
                    if (guard.allow("dialogue:" + stage + ":" + phrase, observation, 1700L, 3)) {
                        boolean selected = clickDialogueOption(ctx, option, text);
                        if (!selected) selected = ctx.dialogues().selectOption(s -> clean(s).contains(phrase));
                        log("X Marks the Spot: selected dialogue '" + text + "'=" + selected);
                        if (selected) dialogueLoadingUntil = System.currentTimeMillis() + 1300L;
                    }
                    pause(850, 1700);
                    return true;
                }
            }
            String visible = options.stream().filter(w -> w != null)
                    .map(w -> clean(w.getText()).isEmpty() ? clean(w.getRawText()) : clean(w.getText()))
                    .toList().toString();
            return recoverUnverifiedDialogue(ctx, stage, dialogueText + ":" + visible);
        }
        return false;
    }

    private boolean recoverUnverifiedDialogue(APIContext ctx, int stage, String snapshot) {
        long now = System.currentTimeMillis();
        if (!snapshot.equals(unverifiedDialogueSnapshot)) {
            unverifiedDialogueSnapshot = snapshot;
            unverifiedDialogueSince = now;
            unverifiedDialogueRecoveryAttempts = 0;
        }
        if (now - unverifiedDialogueSince < 10000L) {
            status("unverified Veos dialogue at stage " + stage + ": " + snapshot);
            if (now >= nextDialogueLogAt) {
                logger.accept("X Marks the Spot: unverified dialogue stage=" + stage + " " + snapshot);
                nextDialogueLogAt = now + 10000L;
            }
            pause(900, 1500);
            return true;
        }
        if (unverifiedDialogueRecoveryAttempts < 2) {
            unverifiedDialogueRecoveryAttempts++;
            log("X Marks the Spot: closing unchanged unverified dialogue before retrying stage " + stage);
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            pause(800, 1300);
            return true;
        }
        status("unverified dialogue persisted; retrying quest stage " + stage);
        return false;
    }

    private boolean clickDialogueOption(APIContext ctx, WidgetChild option, String expected) {
        java.awt.Rectangle bounds = option.getBounds();
        if (bounds != null && bounds.width > 12 && bounds.height > 10) {
            int x = ThreadLocalRandom.current().nextInt(bounds.x + 4, bounds.x + bounds.width - 4);
            int y = ThreadLocalRandom.current().nextInt(bounds.y + 3, bounds.y + bounds.height - 3);
            Point point = new Point(x, y);
            ctx.mouse().move(point);
            Time.sleep(190, 460);
            if (!expected.equals(clean(option.getText())) && !expected.equals(clean(option.getRawText()))) return false;
            if (ctx.mouse().click(point, false)) return true;
        }
        return option.click(false);
    }

    private void acquireSpade(APIContext ctx) {
        status("obtaining a usable spade");
        if (System.currentTimeMillis() >= nextFundingLogAt) {
            logger.accept("X Marks the Spot: spade funding inventoryCoins="
                    + ctx.inventory().getCount(true, "Coins") + " bankOpen=" + ctx.bank().isOpen()
                    + (ctx.bank().isOpen() ? " bankCoins=" + ctx.bank().getCount("Coins") : "")
                    + " geFallback=" + geFallback);
            nextFundingLogAt = System.currentTimeMillis() + 12_000L;
        }
        ItemWidget noted = ctx.inventory().getItem(item -> item != null
                && "Spade".equalsIgnoreCase(item.getName()) && item.isNoted());
        if (noted != null && noted.isNoted()) triedNearbyBank = false;
        if (ctx.inventory().getCount(true, "Coins") >= 20 && !geFallback && noted == null
                && distance(ctx.localPlayer().getLocation(), GE) <= 12) {
            geFallback = true;
            log("X Marks the Spot: already at GE; buying spade here");
        }
        if (ctx.bank().isOpen()) {
            if (noted != null && noted.isNoted()) {
                ctx.bank().depositAll(item -> item != null
                        && "Spade".equalsIgnoreCase(item.getName()) && item.isNoted());
                pause(650, 1050);
                return;
            }
            if (ctx.inventory().isFull()) {
                ctx.bank().depositAllExcept(item -> item != null && isQuestInventoryItem(item.getName()));
                pause(700, 1150);
                return;
            }
            if (ctx.bank().contains(ItemID.SPADE)) {
                if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                    ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                    pause(600, 950);
                    return;
                }
                boolean withdrawn = ctx.bank().withdraw(1, ItemID.SPADE);
                log("X Marks the Spot: withdrew spade from bank=" + withdrawn);
                pause(800, 1400);
                return;
            }
            if (ctx.inventory().getCount(true, "Coins") < 20 && ctx.bank().contains("Coins")) {
                ctx.bank().withdrawAll("Coins");
                emptyCoinBankChecks = 0;
                pause(750, 1200);
                return;
            }
            if (ctx.inventory().getCount(true, "Coins") < 20 && !ctx.bank().contains("Coins")) emptyCoinBankChecks++;
            triedNearbyBank = true;
            ctx.bank().close();
            pause(550, 900);
            return;
        }
        if (geFallback) { buySpadeAtGe(ctx); return; }
        if (!triedNearbyBank && ctx.inventory().getCount(true, "Coins") < 5
                && Navigation.isBankReachable(ctx)) {
            Navigation.openBank(ctx);
            pause(900, 1500);
            return;
        }
        if (ctx.inventory().getCount(true, "Coins") < 5) {
            if (emptyCoinBankChecks >= 2) {
                status("no spade or coins found in bank; waiting for supplies");
                pause(5000, 8000);
                return;
            }
            geFallback = true;
            triedNearbyBank = false;
            status("no coins for Lumbridge spade; checking bank before GE");
            if (!Navigation.isBankReachable(ctx)) Navigation.walkToBank(ctx);
            else Navigation.openBank(ctx);
            pause(900, 1500);
            return;
        }
        if (ctx.store().isOpen()) {
            if (ctx.store().contains(ItemID.SPADE)) {
                boolean bought = ctx.store().buyOne(ItemID.SPADE);
                log("X Marks the Spot: bought spade from Lumbridge store=" + bought);
                if (!bought && ++storeFailures >= 3) geFallback = true;
                pause(850, 1400);
                return;
            }
            log("X Marks the Spot: Lumbridge store has no spade; using GE fallback");
            geFallback = true;
            ctx.store().close();
            pause(600, 950);
            return;
        }
        if (distance(ctx.localPlayer().getLocation(), LUMBRIDGE_STORE) > 8) {
            walk(ctx, LUMBRIDGE_STORE, "Lumbridge General Store for spade");
            return;
        }
        NPC keeper = ctx.npcs().query().nameContains("Shop keeper").within(LUMBRIDGE_STORE_AREA)
                .actions("Trade").results().nearest();
        if (keeper == null) keeper = ctx.npcs().query().nameContains("Shop assistant")
                .within(LUMBRIDGE_STORE_AREA).actions("Trade").results().nearest();
        if (keeper != null && keeper.isValid()) {
            if (!keeper.canReach(ctx, 1) && openRouteDoor(ctx, keeper.getLocation())) return;
            boolean opened = keeper.interact("Trade");
            log("X Marks the Spot: opening Lumbridge store=" + opened);
            if (!opened && ++storeFailures >= 3) geFallback = true;
            pause(1000, 1700);
        } else {
            if (++storeFailures >= 3) geFallback = true;
            walk(ctx, LUMBRIDGE_STORE, "Lumbridge shop keeper");
        }
    }

    private void buySpadeAtGe(APIContext ctx) {
        if (usableSpade(ctx)) { geFallback = false; geOfferAt = 0; return; }
        if (ctx.store().isOpen()) { ctx.store().close(); pause(500, 850); return; }
        if (ctx.bank().isOpen()) {
            if (ctx.bank().contains(ItemID.SPADE)) {
                geFallback = false;
                acquireSpade(ctx);
                return;
            }
            if (ctx.inventory().getCount(true, "Coins") < 20 && ctx.bank().contains("Coins")) {
                ctx.bank().withdrawAll("Coins"); pause(700, 1100); return;
            }
            ctx.bank().close(); pause(500, 850); return;
        }
        if (ctx.inventory().getCount(true, "Coins") < 20) {
            if (emptyCoinBankChecks >= 2) {
                status("spade GE fallback needs coins; waiting for supplies");
                pause(5000, 8000);
                return;
            }
            status("spade unavailable: checking bank for coins");
            if (Navigation.isBankReachable(ctx)) Navigation.openBank(ctx);
            else Navigation.walkToBank(ctx);
            pause(1100, 1800);
            return;
        }
        if (distance(ctx.localPlayer().getLocation(), GE) > 12) { walk(ctx, GE, "GE spade fallback"); return; }
        if (!ctx.grandExchange().isOpen()) { ctx.grandExchange().open(); pause(1000, 1600); return; }
        if (confirmGePriceWarning(ctx)) return;
        List<GrandExchangeSlot> slots = ctx.grandExchange().getSlots();
        GrandExchangeSlot slot = (slots == null ? List.<GrandExchangeSlot>of() : slots).stream()
                .filter(s -> s != null && s.getOffer() != null
                        && "Spade".equalsIgnoreCase(s.getOffer().getItemName())).findFirst().orElse(null);
        if (slot != null && slot.canCollect() && System.currentTimeMillis() >= nextGeCollectAt) {
            boolean collected = ctx.grandExchange().collectToInventory();
            log("X Marks the Spot: collected GE spade=" + collected);
            nextGeCollectAt = System.currentTimeMillis() + 2500L;
            pause(850, 1400);
            return;
        }
        if (slot != null && slot.inUse()) { status("waiting for existing GE spade offer"); pause(1800, 3000); return; }
        if (geOfferAt > 0 && ctx.grandExchange().getCurrentScreen()
                == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER) {
            if (System.currentTimeMillis() - geOfferAt > 5000L) ctx.grandExchange().backToOverview();
            pause(850, 1350);
            return;
        }
        if (geOfferAt > 0) { status("verifying GE spade offer"); pause(2200, 3200); return; }
        if (ctx.grandExchange().getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview(); pause(650, 1000); return;
        }
        int price = GePricing.quickBuyPrice(ctx, "Spade", 100);
        boolean placed = ctx.grandExchange().placeBuyOffer("Spade", 1, price);
        log("X Marks the Spot: GE spade offer at " + price + "=" + placed);
        if (placed) geOfferAt = System.currentTimeMillis();
        pause(1200, 1900);
    }

    private boolean confirmGePriceWarning(APIContext ctx) {
        WidgetChild warning = null, yes = null;
        for (WidgetChild child : ctx.widgets().getAllChildren(w -> w != null && w.isValid()
                && w.getWidth() > 0 && w.getHeight() > 0)) {
            String text = clean(child.getText());
            if (text.contains("much higher than the guide price")) warning = child;
            if (text.equals("yes")) yes = child;
        }
        if (warning == null || yes == null) return false;
        if (yes.click(false)) {
            geOfferAt = System.currentTimeMillis();
            log("X Marks the Spot: confirmed GE spade price warning");
        }
        pause(850, 1350);
        return true;
    }

    private void walk(APIContext ctx, Tile destination, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return;
        if (openRouteDoor(ctx, destination)) return;
        status("walking to " + label);
        if (System.currentTimeMillis() >= nextTravelLogAt) {
            log("X Marks the Spot: route " + here + " -> " + destination + " (" + label + ")");
            nextTravelLogAt = System.currentTimeMillis() + 12_000L;
        }
        WalkState result = Navigation.walkToNoTeleports(ctx, destination);
        if (result != WalkState.SUCCESS && distance(here, destination) <= 18) {
            if (++stagnantTicks <= 3) ctx.walking().walkOnMap(destination);
            else status("route stalled: " + result + " toward " + destination + "; limited recovery exhausted");
        }
        pause(650, 1050);
    }

    private boolean openRouteDoor(APIContext ctx, Tile destination) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || destination == null || distance(here, destination) > 15) return false;
        SceneObject door = ctx.walking().getBlockingObjectBetween(here, destination);
        if (door == null || !door.isValid() || !door.hasAction("Open") || door.tileDistanceTo(ctx) > 6) return false;
        String name = door.getName() == null ? "" : door.getName().toLowerCase(Locale.ROOT);
        if (!name.contains("door") && !name.contains("gate")) return false;
        String observation = here + ":" + door.getLocation() + ":" + stage(ctx);
        if (!guard.allow("door:" + door.getLocation(), observation, 1500L, 3)) return true;
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        boolean opened = door.interact("Open") || ctx.menu().interact("Open", door, false);
        log("X Marks the Spot: opening route " + name + " at " + door.getLocation() + "=" + opened);
        pause(800, 1400);
        return true;
    }

    private void handleCompletedQuest(APIContext ctx) {
        if (!completionClosed) { closeCompletion(ctx); return; }
        if (rewardLamp(ctx) != null) { redeemRewardLamp(ctx); return; }
        status("quest complete; reward lamp redeemed");
    }

    private ItemWidget rewardLamp(APIContext ctx) {
        ItemWidget lamp = ctx.inventory().getItem(ItemID.THOSF_REWARD_LAMP);
        return lamp != null ? lamp : ctx.inventory().getItem(ItemID.CLUEQUEST_LAMP);
    }

    private void redeemRewardLamp(APIContext ctx) {
        ItemWidget lamp = rewardLamp(ctx);
        if (lamp == null) return;
        if (!ctx.widgets().isInterfaceOpen() && !ctx.dialogues().isDialogueOpen()) {
            lampSkillClicked = false;
            String action = lamp.hasAction("Rub") ? "Rub" : "Use";
            boolean opened = lamp.interact(action);
            log("X Marks the Spot: opening 300 XP reward lamp=" + opened);
            pause(1000, 1800);
            return;
        }

        if (ctx.dialogues().canContinue()) {
            if (!ctx.dialogues().selectContinue()) ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            pause(1000, 1900);
            return;
        }
        if (selectedLampSkill == null) {
            List<String> available = new ArrayList<>();
            for (String skill : LAMP_SKILLS) {
                if (visibleRewardWidget(ctx, skill) != null) available.add(skill);
            }
            if (!available.isEmpty()) {
                selectedLampSkill = available.get(ThreadLocalRandom.current().nextInt(available.size()));
                log("X Marks the Spot: randomly selected " + selectedLampSkill
                        + " from " + available.size() + " available reward skills");
            }
        }
        if (selectedLampSkill == null) {
            lampWait("waiting for visible reward skill choices");
            return;
        }
        if (!lampSkillClicked) {
            WidgetChild skill = visibleRewardWidget(ctx, selectedLampSkill);
            if (skill != null && guard.allow("lamp:skill", selectedLampSkill + ":" + skill.getText(), 1200L, 3)) {
                boolean clicked = clickRewardWidget(ctx, skill);
                if (clicked) lampSkillClicked = true;
                log("X Marks the Spot: selected lamp skill " + selectedLampSkill + "=" + clicked);
                pause(950, 1650);
                return;
            }
            lampWait("waiting for selected lamp skill " + selectedLampSkill);
            return;
        }
        WidgetChild confirm = visibleRewardWidget(ctx, "Confirm");
        if (confirm != null && guard.allow("lamp:confirm", selectedLampSkill + ":" + confirm.getText(), 1400L, 3)) {
            boolean clicked = clickRewardWidget(ctx, confirm);
            log("X Marks the Spot: confirmed " + selectedLampSkill + " XP reward=" + clicked);
            pause(1200, 2000);
            return;
        }
        lampWait("waiting for reward confirmation for " + selectedLampSkill);
    }

    private WidgetChild visibleRewardWidget(APIContext ctx, String label) {
        List<WidgetChild> dialogueOptions = ctx.dialogues().getOptions();
        if (dialogueOptions != null) {
            for (WidgetChild option : dialogueOptions) {
                if (option != null && option.isValid() && matchesRewardWidget(option, label)) return option;
            }
        }
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (candidate == null || !candidate.isValid() || candidate.isHidden()
                    || candidate.getWidth() <= 0 || candidate.getHeight() <= 0) return false;
            return matchesRewardWidget(candidate, label);
        })) return widget;
        return null;
    }

    private boolean matchesRewardWidget(WidgetChild widget, String label) {
        if (label.equalsIgnoreCase(clean(widget.getText()))
                || label.equalsIgnoreCase(clean(widget.getRawText()))) return true;
        List<String> actions = widget.getActions();
        if (actions == null) return false;
        for (String action : actions) {
            if (label.equalsIgnoreCase(clean(action))
                    || ("select " + label).equalsIgnoreCase(clean(action))) return true;
        }
        return false;
    }

    private boolean clickRewardWidget(APIContext ctx, WidgetChild widget) {
        Point center = widget.getCentralPoint();
        return center != null && (ctx.mouse().click(center, false) || widget.click(false));
    }

    private void lampWait(String reason) {
        if (System.currentTimeMillis() >= nextLampLogAt) {
            log("X Marks the Spot: " + reason);
            nextLampLogAt = System.currentTimeMillis() + 10_000L;
        } else status(reason);
        pause(650, 1100);
    }

    private void closeCompletion(APIContext ctx) {
        if (completionClosed) { status("quest complete"); return; }
        if (completionCloseAt == 0) {
            completionCloseAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(4500L, 8001L);
            log("X Marks the Spot: completed; waiting before closing reward window");
        }
        if (System.currentTimeMillis() < completionCloseAt) { status("waiting for completion widget"); return; }
        boolean visible = false;
        for (WidgetChild child : ctx.widgets().getAllChildren(w -> w != null && w.isValid()
                && w.getWidth() > 0 && w.getHeight() > 0)) {
            String text = clean(child.getText());
            if (text.contains("quest complete") || text.contains("you have completed")) visible = true;
        }
        if (visible) {
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            log("X Marks the Spot: closed quest completion window");
            pause(900, 1400);
            return;
        }
        completionClosed = true;
        log("X Marks the Spot: completion widget closed; checking reward lamp");
    }

    private boolean usableSpade(APIContext ctx) {
        ItemWidget spade = ctx.inventory().getItem(ItemID.SPADE);
        return spade != null && !spade.isNoted();
    }
    private boolean isQuestInventoryItem(String name) {
        if (name == null) return false;
        String clean = clean(name);
        return clean.equals("spade") || clean.equals("coins") || clean.equals("treasure scroll")
                || clean.equals("mysterious orb") || clean.equals("ancient casket");
    }
    private int questItemSignature(APIContext ctx) {
        return ctx.inventory().getCount(ItemID.CLUEQUEST_CLUE1) * 10000
                + ctx.inventory().getCount(ItemID.CLUEQUEST_CLUE2) * 1000
                + ctx.inventory().getCount(ItemID.CLUEQUEST_CLUE3) * 100
                + ctx.inventory().getCount(ItemID.CLUEQUEST_CLUE4) * 10
                + ctx.inventory().getCount(ItemID.CLUEQUEST_CASKET);
    }
    private static boolean sameTile(Tile a, Tile b) {
        return a != null && b != null && a.getX() == b.getX()
                && a.getY() == b.getY() && a.getPlane() == b.getPlane();
    }
    private static int distance(Tile a, Tile b) {
        return a == null || b == null || a.getPlane() != b.getPlane()
                ? Integer.MAX_VALUE : Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
    private static String clean(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }
    private void pause(long min, long max) {
        nextActionAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(min, max + 1L);
    }
    private void status(String text) { stats.setStatus("X Marks the Spot: " + text); }
    private void log(String text) { status(text); logger.accept(text); }
}
