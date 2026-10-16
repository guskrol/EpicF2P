package org.example.modules.questing;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;

import java.awt.event.KeyEvent;
import java.awt.Point;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Standalone quest executor. Stages and NPC/object locations follow Quest Helper. */
public final class RomeoJulietQuestModule implements ManagedF2PModule {
    private static final IQuestAPI.Quest QUEST = IQuestAPI.Quest.ROMEO_AND_JULIET;
    private static final Tile ROMEO = new Tile(3211, 3422, 0);
    private static final Tile JULIET = new Tile(3158, 3427, 1);
    private static final Tile STAIRS_DOWN = new Tile(3157, 3436, 0);
    private static final Tile JULIET_ENTRANCE = new Tile(3165, 3433, 0);
    private static final Tile JULIET_INSIDE = new Tile(3162, 3433, 0);
    private static final Tile STAIRS_UP = new Tile(3156, 3435, 1);
    private static final Tile JULIET_HALL_DOOR = new Tile(3157, 3430, 1);
    private static final Tile JULIET_ROOM_DOOR = new Tile(3158, 3426, 1);
    private static final Tile JULIET_HALL = new Tile(3157, 3428, 1);
    private static final Tile LAWRENCE = new Tile(3254, 3483, 0);
    private static final Tile APOTHECARY = new Tile(3195, 3405, 0);
    private static final Tile BERRIES = new Tile(3275, 3370, 0);
    private static final Area BERRY_AREA = new Area(3268, 3363, 3283, 3378);
    private static final Area JULIET_ROOM = new Area(1, 3147, 3425, 3166, 3443);
    private static final Area GE = new Area(3160, 3478, 3175, 3490);
    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long nextActionAt, dialogueUntil, dialogueWaitUntil, lastProgressAt, lastLogAt;
    private long offerPlacedAt, nextCollectAt, suspendedUntil;
    private long apothecaryHandoffUntil;
    private long completionCloseAt;
    private long dialogueLoadingSince;
    private long staircaseTransitionUntil;
    private long nextDialogueLoadingLogAt;
    private boolean completionCloseIssued;
    private int lastSignature = Integer.MIN_VALUE, sameActionAttempts, berryFailures, collectAttempts;
    private int lastStage = Integer.MIN_VALUE, lastPlane = Integer.MIN_VALUE;
    private String lastAction = "", lastDialogue = "";
    private boolean bankChecked, buyingBerry;
    private int dialogueActions;
    private int preQuestDialogueClearAttempts;
    private String unverifiedDialogueSnapshot = "";
    private long unverifiedDialogueSince;
    private int unverifiedDialogueRecoveryAttempts;

    public RomeoJulietQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override public String name() { return "quests.romeo_and_juliet"; }
    @Override public boolean shouldExecute(APIContext ctx) { return ctx != null && !isComplete(ctx); }
    @Override public boolean isComplete(APIContext ctx) { return ctx != null && ctx.quests().isCompleted(QUEST); }
    public boolean completionHandled() { return completionCloseIssued; }
    @Override public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0 : ctx.quests().isStarted(QUEST) ? 92 : 62;
    }

    @Override public void execute(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (ctx == null || now < nextActionAt || now < suspendedUntil) return;
        stats.setTrainingSkill("Questing");
        if (isComplete(ctx)) { closeCompletion(ctx); return; }
        int stage = stage(ctx);
        Tile current = ctx.localPlayer().getLocation();
        int plane = current == null ? Integer.MIN_VALUE : current.getPlane();
        if ((lastStage != Integer.MIN_VALUE && stage != lastStage)
                || (lastPlane != Integer.MIN_VALUE && plane != lastPlane)) {
            // Juliet's cutscene changes both the quest stage and the scene/plane.
            // The previous Juliet-room route must not own later access feedback.
            Navigation.abandonTrip(ctx);
            org.example.core.navigation.AccessRecovery.clearIntent(ctx);
            staircaseTransitionUntil = 0L;
            logger.accept("Romeo & Juliet: cleared previous route after stage/plane transition "
                    + lastStage + "/" + lastPlane + " -> " + stage + "/" + plane);
        }
        lastStage = stage;
        lastPlane = plane;
        int signature = stage * 10000 + usable(ctx, ItemID.JULIETMESSAGE) * 1000
                + usable(ctx, ItemID.CADAVABERRIES) * 100
                + (hasCadavaPotion(ctx) ? 10 : 0)
                + (ctx.localPlayer().getLocation() == null ? 0 : ctx.localPlayer().getLocation().getPlane());
        if (signature != lastSignature) {
            logger.accept("Romeo & Juliet: progress stage=" + stage + " signature=" + signature);
            lastSignature = signature;
            lastProgressAt = now;
            sameActionAttempts = 0;
            lastAction = "";
        }
        if (lastProgressAt > 0 && now - lastProgressAt > 120_000L) {
            status("no material progress for 2 minutes; checking current stage and inventory");
            lastProgressAt = now;
            bankChecked = false;
        }
        if (stage == 50 && handlePotionWidget(ctx)) return;
        if (handleDialogue(ctx, stage)) return;
        if (stage == 50 && now < apothecaryHandoffUntil) {
            status("waiting for Apothecary potion handoff");
            nextActionAt = now + 500L;
            return;
        }
        if ((stage == 50 || stage == 60) && isInstancedScene(current)) {
            // The Juliet scene uses remapped coordinates such as 10509,13129.
            // World-map Juliet/Stair tiles cannot be walked to in this scene.
            status("waiting for Juliet scene to return to the world map");
            nextActionAt = now + 750L;
            return;
        }
        if (ctx.inventory().isFull() && clearInventorySpace(ctx)) return;
        if (buyingBerry || ctx.grandExchange().isOpen()) { buyBerry(ctx); return; }
        if (stage == 40 && !hasCadavaPotion(ctx)
                && !hasUsable(ctx, ItemID.CADAVABERRIES) && recoverBerry(ctx)) return;
        switch (stage) {
            case 0 -> talk(ctx, NpcID.ROMEO, ROMEO, "Romeo");
            case 10 -> visitJuliet(ctx);
            case 20 -> {
                if (hasUsable(ctx, ItemID.JULIETMESSAGE)) talk(ctx, NpcID.ROMEO, ROMEO, "Romeo with letter");
                else visitJuliet(ctx);
            }
            case 30 -> talk(ctx, NpcID.FATHER_LAWRENCE, LAWRENCE, "Father Lawrence");
            case 40 -> talk(ctx, NpcID.APOTHECARY, APOTHECARY, "Apothecary");
            case 50 -> {
                if (hasCadavaPotion(ctx)) visitJuliet(ctx);
                else talk(ctx, NpcID.APOTHECARY, APOTHECARY, "Apothecary for potion");
            }
            case 60 -> talk(ctx, NpcID.ROMEO, ROMEO, "Romeo for final scene");
            default -> suspend("unknown quest stage " + stage);
        }
    }

    private int stage(APIContext ctx) { return ctx.vars().getVarp(QUEST.getVarPlayer().getId()); }
    private static boolean isInstancedScene(Tile tile) {
        return tile != null && (tile.getX() > 4096 || tile.getY() > 4096);
    }
    private int usable(APIContext ctx, int id) {
        return ctx.inventory().getCount(true, item -> item != null && item.getId() == id && !noted(item));
    }
    private boolean hasUsable(APIContext ctx, int id) { return usable(ctx, id) > 0; }
    private boolean hasCadavaPotion(APIContext ctx) {
        return hasUsable(ctx, ItemID.CADAVA) || ctx.inventory().getCount(true,
                item -> item != null && "Cadava potion".equalsIgnoreCase(item.getName())
                        && !noted(item)) > 0;
    }
    private boolean noted(Item item) {
        return item.isNoted() || item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId();
    }

    private boolean handlePotionWidget(APIContext ctx) {
        List<WidgetChild> visible = ctx.widgets().getAllChildren(child -> child != null
                && child.isValid() && child.getWidth() > 0 && child.getHeight() > 0);
        WidgetChild cadava = null;
        boolean genericRecipe = false;
        for (WidgetChild child : visible) {
            String label = clean(child.getText());
            if (label.equals("cadava potion")) cadava = child;
            if (label.contains("limpwurt root") || label.contains("red spiders' eggs")) genericRecipe = true;
        }
        if (cadava != null && !hasCadavaPotion(ctx)) {
            if (!guard("widget:cadava-potion")) return true;
            if (cadava.click(false)) logger.accept("Romeo & Juliet: selected Cadava potion widget");
            nextActionAt = System.currentTimeMillis() + 1100L;
            return true;
        }
        if (genericRecipe) {
            if (!guard("widget:close-generic-potion")) return true;
            logger.accept("Romeo & Juliet: generic potion recipe requires limpwurt root; closing it to use quest dialogue");
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            nextActionAt = System.currentTimeMillis() + 1000L;
            return true;
        }
        return false;
    }

    private boolean handleDialogue(APIContext ctx, int stage) {
        long now = System.currentTimeMillis();
        boolean loading = ctx.dialogues().isLoading();
        if (loading && dialogueLoadingSince == 0L) dialogueLoadingSince = now;
        if (!loading) dialogueLoadingSince = 0L;
        List<WidgetChild> options = ctx.dialogues().getOptions();
        // EpicBot can leave isChatOpen/isDialogueOpen true with no usable widget.
        // Such a stale flag must not block navigation before the first NPC click.
        String text = clean(ctx.dialogues().getText());
        boolean hasWidgetText = options != null && options.stream().anyMatch(option -> option != null
                && !clean(option.getText()).isBlank());
        boolean actionable = hasWidgetText || !text.isBlank();
        boolean visible = actionable && (ctx.dialogues().isDialogueOpen()
                || ctx.dialogues().isChatOpen() || options != null && !options.isEmpty());
        if (loading && visible && now - dialogueLoadingSince < 3500L) {
            nextActionAt = now + 450L;
            return true;
        }
        if (loading && now - dialogueLoadingSince >= 3500L && now >= nextDialogueLoadingLogAt) {
            logger.accept("Romeo & Juliet: dialogue loading persisted; actionable=" + visible
                    + " stage=" + stage + "; checking navigation instead of waiting indefinitely");
            nextDialogueLoadingLogAt = now + 12000L;
        }
        if (!visible) {
            lastDialogue = "";
            dialogueWaitUntil = 0;
            preQuestDialogueClearAttempts = 0;
            unverifiedDialogueSnapshot = "";
            unverifiedDialogueSince = 0L;
            unverifiedDialogueRecoveryAttempts = 0;
            return false;
        }
        Tile here = ctx.localPlayer().getLocation();
        if (stage == 0 && dialogueUntil == 0L && here != null && distance(here, ROMEO) > 8) {
            if (preQuestDialogueClearAttempts >= 3) {
                status("pre-quest dialogue did not close; trying route to Romeo");
                return false;
            }
            preQuestDialogueClearAttempts++;
            boolean cleared = ctx.dialogues().canContinue() && ctx.dialogues().selectContinue();
            if (!cleared) cleared = ctx.widgets().closeInterface();
            if (!cleared) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            logger.accept("Romeo & Juliet: clearing pre-quest dialogue away from Romeo=" + cleared);
            nextActionAt = now + 900L;
            return true;
        }
        // Romeo can keep his information menu open after the quest varp moves
        // from 0 to 10. Close that verified menu before walking to Juliet.
        boolean romeoInformationMenu = (stage == 10 || stage == 30) && options != null
                && options.stream().anyMatch(option -> option != null
                && (clean(option.getText()).contains("where can i find juliet")
                || clean(option.getText()).contains("have you heard anything from juliet")))
                && options.stream().anyMatch(option -> option != null
                && clean(option.getText()).equals("ok, thanks."));
        if (romeoInformationMenu) dialogueUntil = System.currentTimeMillis() + 45_000L;
        boolean apothecaryDialogue = (stage == 40 || stage == 50) && here != null
                && here.getPlane() == 0 && distance(here, APOTHECARY) <= 9
                && (text.contains("cadava") || text.contains("apothecary")
                || text.contains("berries") || text.contains("potion")
                || text.contains("romeo") || text.contains("juliet")
                || options != null && options.stream().anyMatch(option -> option != null
                && (clean(option.getText()).contains("talk about something else")
                || clean(option.getText()).contains("talk about romeo & juliet"))));
        if (stage == 50 && hasUsable(ctx, ItemID.CADAVABERRIES)
                && text.contains("well done. you have the berries")) apothecaryDialogue = true;
        if (apothecaryDialogue && System.currentTimeMillis() > dialogueUntil) {
            logger.accept("Romeo & Juliet: resuming Apothecary dialogue at stage " + stage
                    + " text=" + text + " options=" + labels(options == null ? List.of() : options));
            dialogueUntil = System.currentTimeMillis() + 45_000L;
        }
        if (System.currentTimeMillis() > dialogueUntil) {
            return recoverUnverifiedDialogue(ctx, stage, text, options);
        }
        if (!text.equals(lastDialogue)) {
            lastDialogue = text;
            dialogueWaitUntil = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1100, 2301);
            logger.accept("Romeo & Juliet: dialogue stage=" + stage + " text=" + text
                    + " options=" + labels(options == null ? List.of() : options));
        }
        if (System.currentTimeMillis() < dialogueWaitUntil) {
            nextActionAt = System.currentTimeMillis() + 300L;
            return true;
        }
        WidgetChild continueWidget = options == null ? null : options.stream()
                .filter(option -> option != null && clean(option.getText()).contains("click here to continue"))
                .findFirst().orElse(null);
        if (ctx.dialogues().canContinue() || continueWidget != null) {
            if (!guard("continue:" + stage + ":" + text)) return true;
            ctx.mouse().moveRandomly(25, 80);
            Time.sleep(180, 450);
            if (++dialogueActions % 2 == 0) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                logger.accept("Romeo & Juliet: continued dialogue by keyboard");
            } else {
                if (!ctx.dialogues().selectContinue()
                        && (continueWidget == null || !continueWidget.click(false)))
                    ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                logger.accept("Romeo & Juliet: continued dialogue by mouse/API");
            }
            dialogueUntil = System.currentTimeMillis() + 45_000L;
            if (stage == 50 && text.contains("you hand over the berries")) {
                long delay = ThreadLocalRandom.current().nextLong(4000, 7001);
                apothecaryHandoffUntil = System.currentTimeMillis() + delay;
                logger.accept("Romeo & Juliet: waiting " + delay
                        + " ms for Cadava potion after berry handoff");
            }
            nextActionAt = System.currentTimeMillis() + randomDelay();
            return true;
        }
        if (options == null || options.isEmpty()) return false;
        String[] preferred = switch (stage) {
            case 0 -> new String[]{"perhaps i could help to find her", "yes, i have seen her", "yes, ok, i'll let her know", "yes"};
            case 10 -> new String[]{"ok, thanks.", "yes, i have seen romeo", "romeo", "yes"};
            case 20 -> new String[]{"i have a message", "juliet", "letter", "message"};
            case 30 -> new String[]{"ok, thanks"};
            case 40 -> new String[]{"talk about something else", "talk about romeo & juliet", "romeo & juliet", "cadava"};
            case 50 -> new String[]{"talk about romeo & juliet", "talk about something else", "cadava"};
            case 60 -> new String[]{"juliet", "crypt", "yes"};
            default -> new String[0];
        };
        for (String wanted : preferred) for (WidgetChild option : options) {
            if (option == null || !clean(option.getText()).contains(wanted)) continue;
            if (!guard("option:" + stage + ":" + wanted)) return true;
            if (clickDialogueOption(ctx, option, clean(option.getText()))) {
                logger.accept("Romeo & Juliet: selected dialogue option " + clean(option.getText()));
                dialogueUntil = System.currentTimeMillis() + 45_000L;
                nextActionAt = System.currentTimeMillis() + randomDelay();
            }
            return true;
        }
        return recoverUnverifiedDialogue(ctx, stage, text, options);
    }

    private boolean recoverUnverifiedDialogue(APIContext ctx, int stage, String text, List<WidgetChild> options) {
        String snapshot = stage + ":" + text + ":" + labels(options == null ? List.of() : options);
        long now = System.currentTimeMillis();
        if (!snapshot.equals(unverifiedDialogueSnapshot)) {
            unverifiedDialogueSnapshot = snapshot;
            unverifiedDialogueSince = now;
            unverifiedDialogueRecoveryAttempts = 0;
        }
        if (now - unverifiedDialogueSince < 10000L) {
            status("unverified dialogue; waiting for quest state: " + snapshot);
            nextActionAt = now + 1200L;
            return true;
        }
        if (unverifiedDialogueRecoveryAttempts < 2) {
            unverifiedDialogueRecoveryAttempts++;
            logger.accept("Romeo & Juliet: closing unchanged dialogue before retrying stage " + stage);
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            nextActionAt = now + 1000L;
            return true;
        }
        status("unverified dialogue persisted; retrying quest route at stage " + stage);
        return false;
    }

    private boolean clickDialogueOption(APIContext ctx, WidgetChild option, String expected) {
        java.awt.Rectangle bounds = option.getBounds();
        if (bounds != null && bounds.width > 12 && bounds.height > 10) {
            int x = ThreadLocalRandom.current().nextInt(bounds.x + 4, bounds.x + bounds.width - 4);
            int y = ThreadLocalRandom.current().nextInt(bounds.y + 3, bounds.y + bounds.height - 3);
            Point point = new Point(x, y);
            ctx.mouse().move(point);
            Time.sleep(200, 470);
            if (!expected.equals(clean(option.getText()))) return false;
            if (ctx.mouse().click(point, false)) return true;
        }
        return expected.equals(clean(option.getText())) && option.click(false);
    }

    private void visitJuliet(APIContext ctx) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return;
        if (here.getPlane() == 0) {
            if (enterJulietHouse(ctx, here)) return;
            if (distance(here, STAIRS_DOWN) > 8) { walkDirect(ctx, STAIRS_DOWN, "Juliet staircase"); return; }
            SceneObject stairs = ctx.objects().query().id(ObjectID.FAI_VARROCK_STAIRS_TALLER).results().nearest();
            if (stairs == null || stairs.tileDistanceTo(ctx) > 10)
                stairs = ctx.objects().query().actions("Climb-up").nameContains("Stair").results().nearest();
            if (stairs != null && stairs.interact("Climb-up")) {
                logger.accept("Romeo & Juliet: climbed Juliet staircase");
                nextActionAt = System.currentTimeMillis() + 1100L;
            } else { status("staircase not reachable after entrance; repositioning"); ctx.walking().walkOnMap(STAIRS_DOWN); }
            return;
        }
        if (!JULIET_ROOM.contains(here)) { walk(ctx, JULIET, "Juliet room"); return; }
        if (openJulietUpperDoors(ctx, here)) return;
        talk(ctx, NpcID.JULIET, JULIET, "Juliet");
    }

    private boolean enterJulietHouse(APIContext ctx, Tile here) {
        // The east entrance is the only ground-floor door needed to reach the stairs.
        if (here.getX() < JULIET_ENTRANCE.getX()) return false;
        if (distance(here, JULIET_ENTRANCE) > 9) {
            walkDirect(ctx, JULIET_ENTRANCE, "Juliet house entrance");
            return true;
        }
        Area exact = new Area(0, JULIET_ENTRANCE.getX(), JULIET_ENTRANCE.getY(),
                JULIET_ENTRANCE.getX(), JULIET_ENTRANCE.getY());
        SceneObject door = ctx.objects().query().id(11773).actions("Open").within(exact).results().nearest();
        if (door != null && door.isValid()) {
            if (door.tileDistanceTo(ctx) > 3) {
                ctx.walking().walkOnMap(JULIET_ENTRANCE);
            } else if (guard("juliet-entrance:" + JULIET_ENTRANCE)) {
                org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
                if (door.interact("Open") || ctx.menu().interact("Open", door, false))
                    logger.accept("Romeo & Juliet: opened house entrance id=11773 at " + JULIET_ENTRANCE);
            }
            nextActionAt = System.currentTimeMillis() + 900L;
            return true;
        }
        ctx.walking().walkOnMap(JULIET_INSIDE);
        nextActionAt = System.currentTimeMillis() + 900L;
        return true;
    }

    private boolean openJulietUpperDoors(APIContext ctx, Tile here) {
        // These are the two actual upstairs doors reported by EpicBot Objects:
        // id 11773 at 3157,3430,1 followed by 3158,3426,1.
        if (here.getY() >= JULIET_HALL_DOOR.getY()) {
            if (openJulietDoor(ctx, JULIET_HALL_DOOR, "hall door")) return true;
            status("crossing first Juliet doorway");
            ctx.walking().walkOnMap(JULIET_HALL);
            nextActionAt = System.currentTimeMillis() + 850L;
            return true;
        }
        return openJulietDoor(ctx, JULIET_ROOM_DOOR, "Juliet room door");
    }

    private boolean openJulietDoor(APIContext ctx, Tile tile, String label) {
        Area exact = new Area(1, tile.getX(), tile.getY(), tile.getX(), tile.getY());
        SceneObject door = ctx.objects().query().id(11773).actions("Open").within(exact).results().nearest();
        if (door == null || !door.isValid()) return false;
        if (door.tileDistanceTo(ctx) > 3) {
            status("approaching " + label);
            ctx.walking().walkOnMap(tile);
            nextActionAt = System.currentTimeMillis() + 850L;
            return true;
        }
        if (!guard("juliet-door:" + tile)) return true;
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        if (door.interact("Open") || ctx.menu().interact("Open", door, false))
            logger.accept("Romeo & Juliet: opened " + label + " id=11773 at " + tile);
        nextActionAt = System.currentTimeMillis() + 1000L;
        return true;
    }

    private void descendJuliet(APIContext ctx) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || here.getPlane() == 0) return;
        if (System.currentTimeMillis() < staircaseTransitionUntil) {
            status("waiting for Juliet staircase transition");
            nextActionAt = System.currentTimeMillis() + 500L;
            return;
        }
        if (openRoute(ctx, STAIRS_UP, "Juliet house exit")) return;
        if (distance(here, STAIRS_UP) > 8) { ctx.walking().walkOnMap(STAIRS_UP); nextActionAt = System.currentTimeMillis() + 900L; return; }
        SceneObject stairs = ctx.objects().query().id(ObjectID.FAI_VARROCK_STAIRS_TOP).results().nearest();
        if (stairs == null || stairs.tileDistanceTo(ctx) > 10)
            stairs = ctx.objects().query().actions("Climb-down").nameContains("Stair").results().nearest();
        if (stairs != null)
            org.example.core.navigation.AccessRecovery.interaction(ctx, stairs, "Climb-down");
        if (stairs != null && stairs.interact("Climb-down")) {
            logger.accept("Romeo & Juliet: descended Juliet staircase");
            staircaseTransitionUntil = System.currentTimeMillis() + 3_000L;
            nextActionAt = System.currentTimeMillis() + 1000L;
        } else status("upstairs exit blocked; checking door before staircase");
    }

    private void talk(APIContext ctx, int npcId, Tile destination, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return;
        if (here.getPlane() > destination.getPlane()) { descendJuliet(ctx); return; }
        NPC npc = ctx.npcs().query().id(npcId).actions("Talk-to").reachable().results().nearest();
        if (npc != null && npc.isValid() && npc.tileDistanceTo(ctx) <= 8) {
            if (!guard("talk:" + stage(ctx) + ":" + label)) return;
            if (npc.interact("Talk-to")) {
                logger.accept("Romeo & Juliet: clicked " + label + " at stage " + stage(ctx));
                dialogueUntil = System.currentTimeMillis() + 45_000L;
                nextActionAt = System.currentTimeMillis() + 1200L;
            }
            return;
        }
        if (npcId != NpcID.ROMEO && openRoute(ctx, destination, label)) return;
        walk(ctx, destination, label);
    }

    private boolean openRoute(APIContext ctx, Tile destination, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || here.getPlane() != destination.getPlane() || distance(here, destination) > 10) return false;
        SceneObject door = ctx.walking().getBlockingObjectBetween(here, destination);
        if (door == null || !door.isValid() || !door.hasAction("Open")) return false;
        String name = clean(door.getName());
        if (!name.contains("door") && !name.contains("gate")) return false;
        if (door.tileDistanceTo(ctx) > 3) { ctx.walking().walkOnMap(door.getLocation()); nextActionAt = System.currentTimeMillis() + 850L; return true; }
        if (!guard("door:" + door.getLocation())) return true;
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        if (door.interact("Open") || ctx.menu().interact("Open", door, false))
            logger.accept("Romeo & Juliet: opened " + door.getName() + " toward " + label);
        nextActionAt = System.currentTimeMillis() + 1000L;
        return true;
    }

    private boolean recoverBerry(APIContext ctx) {
        if (!bankChecked || ctx.bank().isOpen() || notedBerry(ctx)) {
            if (!ctx.bank().isOpen()) {
                if (!Navigation.openBank(ctx)) Navigation.walkToBank(ctx);
                nextActionAt = System.currentTimeMillis() + 900L;
                return true;
            }
            if (notedBerry(ctx)) {
                ctx.bank().depositAll(item -> item != null
                        && "Cadava berries".equalsIgnoreCase(item.getName()) && noted(item));
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
            if (ctx.bank().getCount("Cadava berries") > 0) {
                if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM))
                    ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                else ctx.bank().withdraw(1, "Cadava berries");
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
            bankChecked = true;
            ctx.bank().close();
            nextActionAt = System.currentTimeMillis() + 600L;
            return true;
        }
        if (berryFailures >= 4) { buyingBerry = true; buyBerry(ctx); return true; }
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || !BERRY_AREA.contains(here)) { walk(ctx, BERRIES, "Cadava berry bush"); return true; }
        GroundItem item = ctx.groundItems().query().id(ItemID.CADAVABERRIES).results().nearest();
        if (item != null && item.tileDistanceTo(ctx) <= 9 && guard("take:cadava:" + item.getLocation())) {
            if (item.interact("Take")) logger.accept("Romeo & Juliet: taking Cadava berries");
            nextActionAt = System.currentTimeMillis() + 1100L;
            return true;
        }
        SceneObject bush = ctx.objects().query().nameContains("Cadava").results().nearest();
        if (bush != null && bush.tileDistanceTo(ctx) <= 10 && guard("pick:cadava:" + bush.getLocation())) {
            if (bush.interact("Pick-from") || bush.interact("Pick"))
                logger.accept("Romeo & Juliet: picking Cadava berries from bush");
            nextActionAt = System.currentTimeMillis() + 1100L;
            return true;
        }
        berryFailures++;
        status("Cadava berries unavailable near mine; retry " + berryFailures + "/4");
        nextActionAt = System.currentTimeMillis() + 2000L;
        return true;
    }

    private boolean notedBerry(APIContext ctx) {
        return ctx.inventory().getCount(true, item -> item != null
                && "Cadava berries".equalsIgnoreCase(item.getName()) && noted(item)) > 0;
    }

    private boolean clearInventorySpace(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            status("inventory full; going to bank before quest item pickup");
            if (!Navigation.openBank(ctx)) Navigation.walkToBank(ctx);
            nextActionAt = System.currentTimeMillis() + 900L;
            return true;
        }
        boolean deposited = ctx.bank().depositAllExcept(item -> item != null
                && ("Coins".equalsIgnoreCase(item.getName())
                || "Message".equalsIgnoreCase(item.getName())
                || "Cadava berries".equalsIgnoreCase(item.getName())
                || "Cadava potion".equalsIgnoreCase(item.getName())));
        logger.accept("Romeo & Juliet: deposited unrelated inventory items=" + deposited);
        ctx.bank().close();
        nextActionAt = System.currentTimeMillis() + 900L;
        return true;
    }

    private void buyBerry(APIContext ctx) {
        if (hasUsable(ctx, ItemID.CADAVABERRIES)) {
            buyingBerry = false; offerPlacedAt = 0; collectAttempts = 0;
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if (notedBerry(ctx)) {
            buyingBerry = false; bankChecked = false;
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if (!GE.contains(ctx.localPlayer().getLocation())) { walk(ctx, GE.getCentralTile(), "Grand Exchange berry fallback"); return; }
        if (ctx.inventory().getCount(true, "Coins") < 200) {
            buyingBerry = false;
            berryFailures = 0;
            suspend("insufficient coins for GE berry fallback; retrying local berry source");
            return;
        }
        if (!ctx.grandExchange().isOpen()) { ctx.grandExchange().open(); nextActionAt = System.currentTimeMillis() + 900L; return; }
        if (confirmBuyWarning(ctx)) return;
        List<GrandExchangeSlot> slots = ctx.grandExchange().getSlots();
        GrandExchangeSlot slot = null;
        if (slots != null) for (GrandExchangeSlot s : slots) {
            if (s != null && s.getOffer() != null && "Cadava berries".equalsIgnoreCase(s.getOffer().getItemName())
                    && (s.getState() == GrandExchangeOffer.OfferState.BUYING
                    || s.getState() == GrandExchangeOffer.OfferState.BOUGHT)) { slot = s; break; }
        }
        if (slot != null && slot.canCollect() && System.currentTimeMillis() >= nextCollectAt) {
            boolean ok = collectAttempts < 3 ? ctx.grandExchange().collectToInventory() : ctx.grandExchange().collectToBank();
            logger.accept("Romeo & Juliet: GE collect Cadava berries=" + ok);
            collectAttempts++;
            nextCollectAt = System.currentTimeMillis() + 3000L;
            nextActionAt = System.currentTimeMillis() + 950L;
            if (ok && collectAttempts > 3) { buyingBerry = false; bankChecked = false; ctx.grandExchange().close(); }
            return;
        }
        if (slot != null && slot.inUse()) { status("waiting for Cadava berry buy offer"); nextActionAt = System.currentTimeMillis() + 1600L; return; }
        if (offerPlacedAt > 0) { status("checking existing Cadava berry offer"); nextActionAt = System.currentTimeMillis() + 1800L; return; }
        if (ctx.grandExchange().getCurrentScreen() != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview(); nextActionAt = System.currentTimeMillis() + 900L; return;
        }
        if (ctx.grandExchange().placeBuyOffer("Cadava berries", 1,
                GePricing.quickBuyPrice(ctx, "Cadava berries", 200))) {
            offerPlacedAt = System.currentTimeMillis();
            logger.accept("Romeo & Juliet: placed one Cadava berry buy offer");
        }
        nextActionAt = System.currentTimeMillis() + 1100L;
    }

    private boolean confirmBuyWarning(APIContext ctx) {
        WidgetChild warning = null, yes = null;
        for (WidgetChild child : ctx.widgets().getAllChildren(w -> w != null && w.isValid()
                && w.getWidth() > 0 && w.getHeight() > 0)) {
            String text = clean(child.getText());
            if (text.contains("much higher than the guide price")) warning = child;
            if (text.equals("yes")) yes = child;
        }
        if (warning == null || yes == null) return false;
        if (yes.click(false)) {
            offerPlacedAt = System.currentTimeMillis();
            logger.accept("Romeo & Juliet: confirmed GE buy price warning");
        }
        nextActionAt = System.currentTimeMillis() + 900L;
        return true;
    }

    private void walk(APIContext ctx, Tile destination, String label) {
        walkDirect(ctx, destination, label);
    }

    private void walkDirect(APIContext ctx, Tile destination, String label) {
        status("walking to " + label);
        WalkState result = Navigation.walkToNoTeleports(ctx, destination);
        if (result != WalkState.SUCCESS) {
            logger.accept("Romeo & Juliet: route failed " + result + " from="
                    + ctx.localPlayer().getLocation() + " to=" + destination);
            Tile here = ctx.localPlayer().getLocation();
            if (here != null && here.getPlane() == destination.getPlane() && distance(here, destination) <= 18)
                ctx.walking().walkOnMap(destination);
        }
        nextActionAt = System.currentTimeMillis() + 850L;
    }

    private void closeCompletion(APIContext ctx) {
        if (completionCloseIssued && System.currentTimeMillis() < nextActionAt) return;
        if (completionCloseAt == 0L) {
            long delay = ThreadLocalRandom.current().nextLong(6000, 10001);
            completionCloseAt = System.currentTimeMillis() + delay;
            logger.accept("Romeo & Juliet: quest complete; waiting " + delay
                    + " ms for completion widget before closing");
        }
        if (System.currentTimeMillis() < completionCloseAt) {
            status("waiting for quest completion widget");
            nextActionAt = System.currentTimeMillis() + 500L;
            return;
        }
        WidgetChild close = null;
        boolean completionVisible = false;
        for (WidgetChild w : ctx.widgets().getAllChildren(child -> child != null && child.isValid()
                && child.getWidth() > 0 && child.getHeight() > 0)) {
            String text = clean(w.getText());
            if (text.contains("quest complete") || text.contains("you have completed"))
                completionVisible = true;
            if (text.equals("close")) close = w;
        }
        if (close != null && close.click(false)) {
            logger.accept("Romeo & Juliet: clicked Close on quest completion widget");
            completionCloseIssued = true;
            nextActionAt = System.currentTimeMillis() + 1200L;
            return;
        }
        if (completionVisible) {
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            logger.accept("Romeo & Juliet: closed quest completion interface");
            completionCloseIssued = true;
            nextActionAt = System.currentTimeMillis() + 1200L;
            return;
        }
        completionCloseIssued = true;
        status("quest complete");
    }

    private boolean guard(String action) {
        if (!action.equals(lastAction)) { lastAction = action; sameActionAttempts = 0; }
        if (sameActionAttempts >= 3) {
            suspend("action did not change state after 3 attempts: " + action);
            return false;
        }
        sameActionAttempts++;
        return true;
    }
    private void suspend(String reason) {
        logger.accept("Romeo & Juliet: " + reason + "; recovery pause 20 seconds");
        stats.setStatus("Romeo & Juliet: " + reason);
        suspendedUntil = System.currentTimeMillis() + 20_000L;
        sameActionAttempts = 0;
    }
    private void status(String message) {
        stats.setStatus("Romeo & Juliet: " + message);
        if (System.currentTimeMillis() >= lastLogAt) {
            logger.accept("Romeo & Juliet: " + message);
            lastLogAt = System.currentTimeMillis() + 5000L;
        }
    }
    private long randomDelay() { return ThreadLocalRandom.current().nextLong(800, 1601); }
    private int distance(Tile a, Tile b) { return Math.max(Math.abs(a.getX()-b.getX()), Math.abs(a.getY()-b.getY())); }
    private String clean(String value) { return value == null ? "" : value.replaceAll("<[^>]+>", "").trim().toLowerCase(Locale.ROOT); }
    private String labels(List<WidgetChild> options) {
        StringBuilder out = new StringBuilder();
        for (WidgetChild option : options) if (option != null) out.append('[').append(clean(option.getText())).append(']');
        return out.toString();
    }
}
