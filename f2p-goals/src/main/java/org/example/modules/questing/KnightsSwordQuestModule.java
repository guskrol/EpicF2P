package org.example.modules.questing;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
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
import java.util.function.Consumer;

/** Quest stages and locations follow The Knight's Sword quest journal/Quest Helper. */
public final class KnightsSwordQuestModule implements ManagedF2PModule {
    private static final IQuestAPI.Quest QUEST = IQuestAPI.Quest.THE_KNIGHTS_SWORD;
    private static final Area SQUIRE = new Area(2974, 3337, 2982, 3345);
    private static final Area RELDO = new Area(3207, 3490, 3215, 3498);
    private static final Area THURGO = new Area(2996, 3141, 3004, 3149);
    private static final Area GE = new Area(3160, 3478, 3175, 3490);
    private static final Tile FALADOR_BANK = new Tile(3012, 3355, 0);
    private static final Area BEDROOM = new Area(2, 2981, 3331, 2986, 3336);
    private static final Area DUNGEON = new Area(2979, 9538, 3069, 9602);
    private static final Tile CASTLE_LADDER = new Tile(2994, 3341, 0);
    private static final Tile CASTLE_STAIRS = new Tile(2985, 3338, 1);
    private static final Tile CASTLE_STAIRS_UPPER = new Tile(2985, 3338, 2);
    private static final Tile CASTLE_LADDER_UPPER = new Tile(2994, 3341, 1);
    private static final Tile CUPBOARD = new Tile(2985, 3336, 2);
    private static final Tile CAVE_ENTRANCE = new Tile(3008, 3150, 0);
    private static final Tile CAVE_EXIT = new Tile(3008, 9550, 0);
    private static final Tile BLURITE = new Tile(3049, 9566, 0);
    private static final String[] PICKAXES = {"Bronze pickaxe", "Iron pickaxe", "Steel pickaxe",
            "Black pickaxe", "Mithril pickaxe", "Adamant pickaxe", "Rune pickaxe", "Dragon pickaxe"};
    private static final String[] FOOD = {"Trout", "Salmon", "Lobster", "Tuna", "Swordfish"};

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private int lastStage = -1;
    private String pendingBuy;
    private int pendingQuantity;
    private int pendingTargetQuantity;
    private int pendingPrice;
    private long offerPlacedAt;
    private long nextGeCollectAt;
    private int geCollectionAttempts;
    private long nextLogAt;
    private long nextActionAt;
    private long noProgressSince;
    private long suspendedUntil;
    private int lastInventorySignature;
    private boolean bankChecked;
    private boolean questBankChecked;
    private boolean supplyBankChecked;
    private boolean inventoryPrepared;
    private long expectedDialogueUntil;
    private int expectedDialogueStage = -1;
    private String unverifiedDialogueSnapshot = "";
    private long unverifiedDialogueSince;
    private int unverifiedDialogueRecoveryAttempts;

    public KnightsSwordQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override public String name() { return "quests.the_knights_sword"; }

    @Override public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(QUEST);
    }

    @Override public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || isComplete(ctx)) return false;
        return stage(ctx) > 0 || (ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.MINING).getRealLevel() >= 10
                && ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.HITPOINTS).getRealLevel() >= 25);
    }

    @Override public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0 : stage(ctx) > 0 ? 82 : 54;
    }

    @Override public boolean isProtectedSubphase(APIContext ctx) {
        return false;
    }

    @Override public String protectedSubphaseName(APIContext ctx) {
        return "The Knight's Sword in progress";
    }

    @Override public void execute(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextActionAt) return;
        if (now < suspendedUntil) {
            status("waiting before retrying quest step", 15_000L);
            nextActionAt = now + 1_000L;
            return;
        }
        stats.setTrainingSkill("Questing");
        int stage = stage(ctx);
        if (isComplete(ctx)) {
            stats.setStatus("The Knight's Sword complete");
            return;
        }
        int inventorySignature = ctx.inventory().getCount(ItemID.KNIGHTS_PORTRAIT) * 1000
                + ctx.inventory().getCount(ItemID.BLURITE_ORE) * 100
                + ctx.inventory().getCount(ItemID.FALADIAN_SWORD) * 10
                + ctx.inventory().getCount(ItemID.REDBERRY_PIE);
        if (stage != lastStage || inventorySignature != lastInventorySignature) {
            logger.accept("Knight's Sword: stage " + lastStage + " -> " + stage);
            lastStage = stage;
            lastInventorySignature = inventorySignature;
            noProgressSince = now;
        }
        if (noProgressSince == 0) noProgressSince = now;
        if (now - noProgressSince > 4 * 60_000L) {
            logger.accept("Knight's Sword: no quest progress for 4 minutes at stage " + stage
                    + "; retrying the current route");
            noProgressSince = now;
        }
        if (surviveCave(ctx)) return;
        if (handleDialogue(ctx, stage)) return;
        if (prepareQuestInventory(ctx)) return;
        Tile location = ctx.localPlayer().getLocation();
        if (stage >= 5 && location != null && location.getPlane() > 0
                && (stage >= 6 || ctx.inventory().contains(ItemID.KNIGHTS_PORTRAIT)
                || ctx.inventory().contains(ItemID.FALADIAN_SWORD))) {
            descendCastle(ctx, location);
            return;
        }
        if (ctx.grandExchange().isOpen() || pendingBuy != null) {
            handleBuy(ctx);
            return;
        }
        if (stage >= 5 && recoverBankedQuestItem(ctx, stage)) return;
        switch (stage) {
            case 0 -> talk(ctx, NpcID.SQUIRE, "Squire", SQUIRE, "start the quest");
            case 1 -> talk(ctx, NpcID.RELDO_NORMAL, "Reldo", RELDO, "ask about Imcando dwarves");
            case 2 -> {
                if (prepareItem(ctx, "Redberry pie", 1, 1000)) return;
                talk(ctx, NpcID.THURGO, "Thurgo", THURGO, "give redberry pie");
            }
            case 3 -> talk(ctx, NpcID.THURGO, "Thurgo", THURGO, "ask for a replacement sword");
            case 4 -> talk(ctx, NpcID.SQUIRE, "Squire", SQUIRE, "ask about the portrait");
            case 5 -> {
                if (ctx.inventory().contains(ItemID.FALADIAN_SWORD)) {
                    talk(ctx, NpcID.SQUIRE, "Squire", SQUIRE, "return the banked sword");
                } else if (ctx.inventory().contains(ItemID.KNIGHTS_PORTRAIT)) {
                    if (prepareDungeonSupplies(ctx)) return;
                    talk(ctx, NpcID.THURGO, "Thurgo", THURGO, "deliver portrait");
                } else {
                    getPortrait(ctx);
                }
            }
            case 6 -> makeSword(ctx);
            default -> suspend("unknown quest stage " + stage);
        }
    }

    private int stage(APIContext ctx) {
        return ctx.vars().getVarp(QUEST.getVarPlayer().getId());
    }

    private boolean handleDialogue(APIContext ctx, int stage) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            unverifiedDialogueSnapshot = "";
            unverifiedDialogueSince = 0L;
            unverifiedDialogueRecoveryAttempts = 0;
            return false;
        }
        boolean verified = System.currentTimeMillis() <= expectedDialogueUntil
                && Math.abs(stage - expectedDialogueStage) <= 1;
        if (!verified) {
            List<WidgetChild> unknownOptions = ctx.dialogues().getOptions();
            if (ctx.dialogues().canContinue() || (unknownOptions != null && !unknownOptions.isEmpty())) {
                String snapshot = stage + ":" + ctx.dialogues().getText() + ":"
                        + (unknownOptions == null ? List.of() : unknownOptions.stream()
                        .map(option -> option == null ? "" : option.getText()).toList());
                long now = System.currentTimeMillis();
                if (!snapshot.equals(unverifiedDialogueSnapshot)) {
                    unverifiedDialogueSnapshot = snapshot;
                    unverifiedDialogueSince = now;
                    unverifiedDialogueRecoveryAttempts = 0;
                }
                if (now - unverifiedDialogueSince < 10000L) {
                    status("unverified dialogue; waiting briefly for quest state", 5_000L);
                    return true;
                }
                if (unverifiedDialogueRecoveryAttempts++ < 2) {
                    logger.accept("Knight's Sword: closing unchanged unverified dialogue before retrying route");
                    if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
                    nextActionAt = now + 1000L;
                    return true;
                }
                status("unverified dialogue persisted; retrying current quest route", 5_000L);
                return false;
            }
            status("ignoring stale dialogue flag; continuing quest step", 5_000L);
            return false;
        }
        if (ctx.dialogues().canContinue()) {
            unverifiedDialogueSnapshot = "";
            if (!ctx.dialogues().selectContinue()) ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            expectedDialogueStage = stage;
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            nextActionAt = System.currentTimeMillis() + 650L;
            return true;
        }
        String[] preferred = switch (stage) {
            case 0 -> new String[]{"how is life as a squire", "i can make a new sword", "would these dwarves", "yes"};
            case 1 -> new String[]{"what do you know about the imcando", "imcando"};
            case 2 -> new String[]{"would you like a redberry pie", "redberry pie"};
            case 3 -> new String[]{"can you make a special sword", "special sword"};
            case 5 -> new String[]{"about that sword"};
            case 6 -> new String[]{"can you make that replacement sword", "replacement sword"};
            default -> new String[0];
        };
        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options != null) for (String wanted : preferred) {
            for (WidgetChild option : options) {
                String label = option == null ? null : option.getText();
                if (label == null || !label.toLowerCase(Locale.ROOT).contains(wanted)) continue;
                if (option.click(false)) {
                    unverifiedDialogueSnapshot = "";
                    logger.accept("Knight's Sword: clicked quest dialogue option '" + label + "'");
                    expectedDialogueStage = stage;
                    expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                    nextActionAt = System.currentTimeMillis() + 800L;
                    return true;
                }
            }
        }
        if (options != null && options.size() == 1 && options.get(0).click(false)) {
            unverifiedDialogueSnapshot = "";
            logger.accept("Knight's Sword: advanced single-choice dialogue at stage " + stage);
            expectedDialogueStage = stage;
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            nextActionAt = System.currentTimeMillis() + 800L;
            return true;
        }
        if (options != null && !options.isEmpty()) {
            return recoverUnverifiedDialogue(ctx, stage, options);
        }
        if (stage == 5 && expectedDialogueStage == 5
                && System.currentTimeMillis() <= expectedDialogueUntil) {
            if (!ctx.dialogues().selectContinue(false)) ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            logger.accept("Knight's Sword: advanced portrait cupboard chatbox");
            nextActionAt = System.currentTimeMillis() + 900L;
            return true;
        }
        status("ignoring stale dialogue flag; continuing quest step", 5_000L);
        return false;
    }

    private boolean recoverUnverifiedDialogue(APIContext ctx, int stage, List<WidgetChild> options) {
        String snapshot = stage + ":" + ctx.dialogues().getText() + ":"
                + options.stream().map(option -> option == null ? "" : option.getText()).toList();
        long now = System.currentTimeMillis();
        if (!snapshot.equals(unverifiedDialogueSnapshot)) {
            unverifiedDialogueSnapshot = snapshot;
            unverifiedDialogueSince = now;
            unverifiedDialogueRecoveryAttempts = 0;
            logger.accept("Knight's Sword: dialogue choice needs verification: " + snapshot);
        }
        if (now - unverifiedDialogueSince < 10000L) {
            status("unverified quest dialogue; waiting for state change", 5_000L);
            nextActionAt = now + 1200L;
            return true;
        }
        if (unverifiedDialogueRecoveryAttempts < 2) {
            unverifiedDialogueRecoveryAttempts++;
            logger.accept("Knight's Sword: closing unchanged dialogue before retrying stage " + stage);
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            nextActionAt = now + 1000L;
            return true;
        }
        status("unverified dialogue persisted; retrying current quest route", 5_000L);
        return false;
    }

    private void makeSword(APIContext ctx) {
        if (ctx.inventory().contains(ItemID.FALADIAN_SWORD)) {
            talk(ctx, NpcID.SQUIRE, "Squire", SQUIRE, "return the blurite sword");
            return;
        }
        if (ctx.inventory().contains(ItemID.KNIGHTS_PORTRAIT)) {
            talk(ctx, NpcID.THURGO, "Thurgo", THURGO, "show the portrait");
            return;
        }
        if (ctx.inventory().contains(ItemID.BLURITE_ORE)) {
            if (DUNGEON.contains(ctx.localPlayer().getLocation())) {
                leaveCave(ctx);
                return;
            }
            if (prepareDungeonSupplies(ctx)) return;
            talk(ctx, NpcID.THURGO, "Thurgo", THURGO, "forge the sword");
            return;
        }
        if (!DUNGEON.contains(ctx.localPlayer().getLocation())) {
            if (prepareDungeonSupplies(ctx)) return;
            if (ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.HITPOINTS).getRealLevel() < 25) {
                suspend("need 25 Hitpoints to enter the ice dungeon safely");
                return;
            }
            if (ctx.localPlayer().getHealthPercent() < 75) {
                if (eatFood(ctx)) return;
                status("waiting for HP before entering ice dungeon", 8_000L);
                return;
            }
            interactObject(ctx, ObjectID.FAI_TRAPDOOR, CAVE_ENTRANCE,
                    new String[]{"Climb-down", "Climb down", "Open"}, "enter ice dungeon");
            return;
        }
        if (foodCount(ctx) == 0 && ctx.localPlayer().getHealthPercent() < 80) {
            status("out of food; leaving ice dungeon", 3_000L);
            leaveCave(ctx);
            return;
        }
        interactObject(ctx, ObjectID.BLURITE_ROCK_1, BLURITE,
                new String[]{"Mine"}, "mine blurite ore", ObjectID.BLURITE_ROCK_2);
    }

    private void getPortrait(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (me.getPlane() == 0) {
            interactObject(ctx, ObjectID.FAI_FALADOR_CASTLE_LADDER_UP, CASTLE_LADDER,
                    new String[]{"Climb-up", "Climb up"}, "climb castle ladder");
            return;
        }
        if (me.getPlane() == 1) {
            interactObject(ctx, ObjectID.FAI_FALADOR_CASTLE_STAIRS, CASTLE_STAIRS,
                    new String[]{"Climb-up", "Climb up"}, "climb castle staircase");
            return;
        }
        if (!BEDROOM.contains(me) && distance(me, CUPBOARD) > 7) {
            if (openRoute(ctx, CUPBOARD, "Sir Vyvin's room")) return;
            walk(ctx, CUPBOARD, "Sir Vyvin's room");
            return;
        }
        NPC vyvin = ctx.npcs().query().named("Sir Vyvin").within(BEDROOM).results().nearest();
        if (vyvin != null && vyvin.isValid() && vyvin.isFacingMe()) {
            status("waiting until Sir Vyvin leaves or turns away", 4_000L);
            return;
        }
        interactObject(ctx, ObjectID.VYVINCUPBOARDOPEN, CUPBOARD,
                new String[]{"Search", "Open"}, "search portrait cupboard", ObjectID.VYVINCUPBOARDSHUT);
    }

    private void leaveCave(APIContext ctx) {
        if (DUNGEON.contains(ctx.localPlayer().getLocation())) {
            interactObject(ctx, -1, CAVE_EXIT,
                    new String[]{"Climb-up", "Climb up"}, "leave ice dungeon", -2);
        }
    }

    private boolean surviveCave(APIContext ctx) {
        if (!DUNGEON.contains(ctx.localPlayer().getLocation())) return false;
        if (ctx.localPlayer().getHealthPercent() >= 65) return false;
        if (eatFood(ctx)) return true;
        status("low HP without food; leaving dungeon", 3_000L);
        leaveCave(ctx);
        return true;
    }

    private boolean eatFood(APIContext ctx) {
        for (String food : FOOD) {
            ItemWidget item = ctx.inventory().getItem(food);
            if (item != null && item.interact("Eat")) {
                logger.accept("Knight's Sword: eating " + food);
                nextActionAt = System.currentTimeMillis() + 550L;
                return true;
            }
        }
        return false;
    }

    private int foodCount(APIContext ctx) {
        int count = 0;
        for (String name : FOOD) count += ctx.inventory().getCount(name);
        return count;
    }

    private boolean hasPickaxe(APIContext ctx) {
        for (String name : PICKAXES)
            if (ctx.inventory().contains(name) || ctx.equipment().contains(name)) return true;
        return false;
    }

    private boolean prepareItem(APIContext ctx, String name, int quantity, int price) {
        if (ctx.inventory().getCount(name) >= quantity) return false;
        if (!bankChecked || ctx.bank().isOpen()) {
            if (!ctx.bank().isOpen()) {
                status("opening bank for " + name, 4_000L);
                approachNearestBank(ctx, name);
                return true;
            }
            int available = ctx.bank().getCount(name);
            if (available > 0) {
                int needed = Math.min(available, quantity - ctx.inventory().getCount(name));
                ctx.bank().withdraw(needed, name);
                nextActionAt = System.currentTimeMillis() + 900L;
                return true;
            }
            bankChecked = true;
            ctx.bank().close();
            nextActionAt = System.currentTimeMillis() + 600L;
            return true;
        }
        pendingBuy = name;
        pendingQuantity = quantity - ctx.inventory().getCount(name);
        pendingTargetQuantity = quantity;
        pendingPrice = GePricing.quickBuyPrice(ctx, name, price);
        handleBuy(ctx);
        return true;
    }

    private boolean recoverBankedQuestItem(APIContext ctx, int stage) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || location.getPlane() != 0 || DUNGEON.contains(location) || questBankChecked
                || ctx.inventory().contains(ItemID.FALADIAN_SWORD)) return false;
        if (!ctx.bank().isOpen()) {
            approachNearestBank(ctx, "quest items and dungeon supplies");
            return true;
        }
        String[] items = stage == 5
                ? new String[]{"Blurite sword", "Portrait"}
                : new String[]{"Blurite sword", "Portrait", "Blurite ore"};
        for (String item : items) {
            if (ctx.inventory().getCount(item) == 0 && ctx.bank().contains(item)) {
                logger.accept("Knight's Sword: recovering " + item + " from bank");
                ctx.bank().withdraw(1, item);
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
        }
        if (!supplyBankChecked && withdrawDungeonSupplies(ctx)) return true;
        questBankChecked = true;
        ctx.bank().close();
        nextActionAt = System.currentTimeMillis() + 600L;
        return true;
    }

    private boolean prepareDungeonSupplies(APIContext ctx) {
        if (notedIronBars(ctx) > 0) supplyBankChecked = false;
        if (!supplyBankChecked) {
            if (!ctx.bank().isOpen()) {
                approachNearestBank(ctx, "dungeon supplies");
                return true;
            }
            if (withdrawDungeonSupplies(ctx)) return true;
            ctx.bank().close();
            nextActionAt = System.currentTimeMillis() + 600L;
            return true;
        }
        if (unnotedIronBars(ctx) < 2) {
            queueBuy(ctx, "Iron bar", 2, 500);
            return true;
        }
        if (!hasPickaxe(ctx)) {
            queueBuy(ctx, "Bronze pickaxe", 1, 300);
            return true;
        }
        if (foodCount(ctx) < 6) {
            queueBuy(ctx, "Trout", 8, 150);
            return true;
        }
        return false;
    }

    private static boolean isIronBar(Item item) {
        return item != null && "Iron bar".equalsIgnoreCase(item.getName());
    }

    private static boolean isNoted(Item item) {
        return item != null && (item.isNoted()
                || (item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId()));
    }

    private static int unnotedIronBars(APIContext ctx) {
        return ctx.inventory().getCount(true, item -> isIronBar(item) && !isNoted(item));
    }

    private static int notedIronBars(APIContext ctx) {
        return ctx.inventory().getCount(true, item -> isIronBar(item) && isNoted(item));
    }

    private static int usableCount(APIContext ctx, String name) {
        return "Iron bar".equals(name) ? unnotedIronBars(ctx) : ctx.inventory().getCount(name);
    }

    private boolean withdrawDungeonSupplies(APIContext ctx) {
        if (notedIronBars(ctx) > 0) {
            boolean deposited = ctx.bank().depositAll(item -> isIronBar(item) && isNoted(item));
            logger.accept("Knight's Sword: depositing noted iron bars for item withdrawal=" + deposited);
            nextActionAt = System.currentTimeMillis() + 800L;
            return true;
        }
        int bars = 2 - unnotedIronBars(ctx);
        if (bars > 0 && ctx.bank().getCount("Iron bar") > 0) {
            if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                boolean selected = ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                logger.accept("Knight's Sword: selected bank item withdrawal mode=" + selected);
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
            ctx.bank().withdraw(Math.min(bars, ctx.bank().getCount("Iron bar")), "Iron bar");
            nextActionAt = System.currentTimeMillis() + 800L;
            return true;
        }
        if (!hasPickaxe(ctx)) for (String pickaxe : PICKAXES) {
            if (ctx.bank().getCount(pickaxe) > 0) {
                ctx.bank().withdraw(1, pickaxe);
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
        }
        if (foodCount(ctx) < 6) for (String food : FOOD) {
            int available = ctx.bank().getCount(food);
            if (available > 0) {
                ctx.bank().withdraw(Math.min(8 - foodCount(ctx), available), food);
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
        }
        supplyBankChecked = true;
        logger.accept("Knight's Sword: checked nearby bank for sword, portrait and dungeon supplies");
        return false;
    }

    private void queueBuy(APIContext ctx, String name, int quantity, int price) {
        pendingBuy = name;
        pendingQuantity = quantity - usableCount(ctx, name);
        pendingTargetQuantity = quantity;
        pendingPrice = GePricing.quickBuyPrice(ctx, name, price);
        handleBuy(ctx);
    }

    private void approachNearestBank(APIContext ctx, String reason) {
        Tile location = ctx.localPlayer().getLocation();
        if (location != null && location.getPlane() > 0) {
            descendCastle(ctx, location);
            return;
        }
        status("going to nearest bank for " + reason, 5_000L);
        if (Navigation.isBankReachable(ctx)) {
            Navigation.openBank(ctx);
        } else {
            WalkState result = Navigation.walkToBank(ctx);
            if (result != WalkState.SUCCESS && ctx.localPlayer().getLocation() != null
                    && ctx.localPlayer().getLocation().getX() < 3100)
                Navigation.walkToNoTeleports(ctx, FALADOR_BANK);
        }
        nextActionAt = System.currentTimeMillis() + 800L;
    }

    private boolean prepareQuestInventory(APIContext ctx) {
        if (inventoryPrepared || DUNGEON.contains(ctx.localPlayer().getLocation())) return false;
        List<ItemWidget> carried = ctx.inventory().getItems();
        if (carried == null || carried.stream().noneMatch(item -> item != null
                && !isQuestCompatibleItem(item.getName()))) {
            inventoryPrepared = true;
            return false;
        }
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || location.getPlane() != 0) return false;
        if (!ctx.bank().isOpen()) {
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            approachNearestBank(ctx, "quest inventory cleanup");
            return true;
        }
        boolean deposited = ctx.bank().depositAllExcept(item -> item != null
                && isQuestCompatibleItem(item.getName()));
        logger.accept("Knight's Sword: depositing inventory items unrelated to the quest: " + deposited);
        nextActionAt = System.currentTimeMillis() + 850L;
        return true;
    }

    private boolean isQuestCompatibleItem(String name) {
        if (name == null) return false;
        if (name.equalsIgnoreCase("Coins") || name.equalsIgnoreCase("Redberry pie")
                || name.equalsIgnoreCase("Iron bar") || name.equalsIgnoreCase("Blurite ore")
                || name.equalsIgnoreCase("Blurite sword") || name.equalsIgnoreCase("Portrait")) return true;
        for (String food : FOOD) if (name.equalsIgnoreCase(food)) return true;
        for (String pickaxe : PICKAXES) if (name.equalsIgnoreCase(pickaxe)) return true;
        return false;
    }

    private void descendCastle(APIContext ctx, Tile location) {
        Tile target = location.getPlane() >= 2 ? CASTLE_STAIRS_UPPER : CASTLE_LADDER_UPPER;
        if (openRoute(ctx, target, "castle exit")) return;
        if (distance(location, target) > 9) {
            walk(ctx, target, "castle exit staircase");
            return;
        }
        Area nearby = new Area(location.getPlane(), target.getX() - 5, target.getY() - 5,
                target.getX() + 5, target.getY() + 5);
        SceneObject exit = ctx.objects().query().actions("Climb-down").within(nearby).results().nearest();
        if (exit != null && exit.isValid() && exit.interact("Climb-down")) {
            logger.accept("Knight's Sword: descending castle from plane " + location.getPlane());
            nextActionAt = System.currentTimeMillis() + 1100L;
            return;
        }
        status("approaching castle exit staircase on plane " + location.getPlane(), 5_000L);
        ctx.walking().walkOnMap(target);
        nextActionAt = System.currentTimeMillis() + 850L;
    }

    private void handleBuy(APIContext ctx) {
        if (pendingBuy == null) {
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if ("Iron bar".equals(pendingBuy) && notedIronBars(ctx) > 0) {
            logger.accept("Knight's Sword: GE iron bars are noted; converting them at bank");
            pendingBuy = null;
            offerPlacedAt = 0;
            geCollectionAttempts = 0;
            bankChecked = false;
            supplyBankChecked = false;
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if (usableCount(ctx, pendingBuy) >= pendingTargetQuantity) {
            logger.accept("Knight's Sword: acquired " + pendingBuy);
            pendingBuy = null;
            offerPlacedAt = 0;
            geCollectionAttempts = 0;
            bankChecked = false;
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if (!GE.contains(ctx.localPlayer().getLocation())) {
            walk(ctx, GE.getRandomTile(), "Grand Exchange for " + pendingBuy);
            return;
        }
        if (!ctx.grandExchange().isOpen()) {
            ctx.grandExchange().open();
            nextActionAt = System.currentTimeMillis() + 1000L;
            return;
        }
        if (confirmHighPrice(ctx)) return;
        IGrandExchangeAPI.GrandExchangeScreen screen = ctx.grandExchange().getCurrentScreen();
        if (screen == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER && offerPlacedAt > 0) {
            if (System.currentTimeMillis() - offerPlacedAt > 5_000L)
                ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 1200L;
            return;
        }
        GrandExchangeSlot slot = findBuySlot(ctx, pendingBuy);
        if (geCollectionAttempts >= 3 && slot != null && slot.canCollect()
                && System.currentTimeMillis() >= nextGeCollectAt) {
            boolean bankCollected = ctx.grandExchange().collectToBank();
            logger.accept("Knight's Sword: GE inventory collection stalled; collect-to-bank "
                    + pendingBuy + "=" + bankCollected);
            nextGeCollectAt = System.currentTimeMillis() + 8_000L;
            nextActionAt = System.currentTimeMillis() + 1000L;
            if (bankCollected) {
                pendingBuy = null;
                offerPlacedAt = 0;
                bankChecked = false;
                supplyBankChecked = false;
                geCollectionAttempts = 0;
                ctx.grandExchange().close();
            }
            return;
        }
        if (System.currentTimeMillis() >= nextGeCollectAt
                && (slot != null && slot.canCollect()
                || screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER)) {
            boolean collected = ctx.grandExchange().collectToInventory();
            logger.accept("Knight's Sword: collect-to-inventory " + pendingBuy + "=" + collected
                    + ", screen=" + screen + ", slot=" + (slot == null ? "unknown" : slot.getState()));
            nextGeCollectAt = System.currentTimeMillis() + 2_500L;
            nextActionAt = System.currentTimeMillis() + 900L;
            geCollectionAttempts++;
            return;
        }
        if (slot != null && slot.canCollect()) {
            nextActionAt = nextGeCollectAt;
            return;
        }
        if (screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        if (slot != null && slot.inUse()) {
            int filled = slot.getOffer() == null ? 0 : slot.getOffer().getCurrentQuantity();
            status("GE offer for " + pendingBuy + " filled " + filled + "/" + pendingQuantity, 8_000L);
            nextActionAt = System.currentTimeMillis() + 1500L;
            return;
        }
        if (offerPlacedAt > 0) {
            status("verifying GE offer for " + pendingBuy + " before any retry", 8_000L);
            nextActionAt = System.currentTimeMillis() + 2_500L;
            return;
        }
        if (screen != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        offerPlacedAt = 0;
        if (ctx.grandExchange().placeBuyOffer(pendingBuy, pendingQuantity, pendingPrice)) {
            offerPlacedAt = System.currentTimeMillis();
            logger.accept("Knight's Sword: placed buy offer for " + pendingQuantity + "x " + pendingBuy);
            nextActionAt = offerPlacedAt + 1200L;
        } else if (!confirmHighPrice(ctx)) status("unable to place GE offer for " + pendingBuy, 4_000L);
    }

    private GrandExchangeSlot findBuySlot(APIContext ctx, String itemName) {
        List<GrandExchangeSlot> slots = ctx.grandExchange().getSlots();
        if (slots == null) return null;
        for (GrandExchangeSlot slot : slots) {
            if (slot == null || slot.getOffer() == null) continue;
            GrandExchangeOffer.OfferState state = slot.getState();
            if (state != GrandExchangeOffer.OfferState.BUYING
                    && state != GrandExchangeOffer.OfferState.BOUGHT
                    && state != GrandExchangeOffer.OfferState.CANCELLED_BUY) continue;
            String name = slot.getOffer().getItemName();
            if ((name != null && name.equalsIgnoreCase(itemName))
                    || slot.getOffer().getItemId() == buyItemId(itemName)) return slot;
        }
        return null;
    }

    private int buyItemId(String itemName) {
        return switch (itemName) {
            case "Redberry pie" -> ItemID.REDBERRY_PIE;
            case "Iron bar" -> ItemID.IRON_BAR;
            case "Bronze pickaxe" -> ItemID.BRONZE_PICKAXE;
            case "Trout" -> ItemID.TROUT;
            default -> -1;
        };
    }

    private boolean confirmHighPrice(APIContext ctx) {
        WidgetChild warning = null;
        WidgetChild yes = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(child -> child != null && child.isValid()
                && child.getWidth() > 0 && child.getHeight() > 0)) {
            String text = widget.getText() == null ? "" : widget.getText().toLowerCase(Locale.ROOT);
            if (text.contains("much higher than the guide price")) warning = widget;
            if (text.equals("yes")) yes = widget;
        }
        if (warning == null || yes == null) return false;
        Point point = yes.getCentralPoint();
        if (point != null && ctx.mouse().click(point, false)) {
            logger.accept("Knight's Sword: confirmed GE high-price warning");
            offerPlacedAt = System.currentTimeMillis();
            nextActionAt = offerPlacedAt + 1100L;
        }
        return true;
    }

    private void talk(APIContext ctx, int npcId, String name, Area area, String reason) {
        if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
        NPC npc = ctx.npcs().query().id(npcId).actions("Talk-to").within(area).results().nearest();
        NPC reachable = ctx.npcs().query().id(npcId).actions("Talk-to").within(area)
                .reachable().results().nearest();
        if (reachable != null && reachable.isValid() && reachable.tileDistanceTo(ctx) <= 8) {
            status("talking to " + name + " to " + reason, 2_000L);
            if (reachable.interact("Talk-to")) {
                logger.accept("Knight's Sword: clicked " + name + " id=" + reachable.getId()
                        + " at " + reachable.getLocation() + " for stage " + stage(ctx));
                expectedDialogueStage = stage(ctx);
                expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                nextActionAt = System.currentTimeMillis() + 1000L;
            }
            return;
        }
        Tile destination = npc != null && npc.isValid() ? npc.getLocation() : area.getCentralTile();
        if (openRoute(ctx, destination, name)) return;
        if (npc != null && npc.isValid() && npc.tileDistanceTo(ctx) <= 8) {
            status("positioning to reach " + name, 3_000L);
            ctx.walking().walkOnMap(destination);
            nextActionAt = System.currentTimeMillis() + 800L;
            return;
        }
        walk(ctx, area.getRandomTile(), name);
    }

    private boolean openRoute(APIContext ctx, Tile destination, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || destination == null || here.getPlane() != destination.getPlane()
                || distance(here, destination) > 22) return false;
        SceneObject blocker = ctx.walking().getBlockingObjectBetween(here, destination);
        if (tryOpenDoor(ctx, blocker, label)) return true;
        Area search = new Area(destination.getPlane(), destination.getX() - 12, destination.getY() - 12,
                destination.getX() + 12, destination.getY() + 12);
        SceneObject door = ctx.objects().query().actions("Open")
                .nameContains("Door", "Gate").within(search).results().nearest();
        return tryOpenDoor(ctx, door, label);
    }

    private boolean tryOpenDoor(APIContext ctx, SceneObject door, String label) {
        if (door == null || !door.isValid() || !door.hasAction("Open")) return false;
        String name = door.getName();
        if (name == null || (!name.toLowerCase(Locale.ROOT).contains("door")
                && !name.toLowerCase(Locale.ROOT).contains("gate"))) return false;
        Tile tile = door.getLocation();
        Tile here = ctx.localPlayer().getLocation();
        if (tile == null || here == null) return false;
        if (distance(here, tile) > 15) {
            status("walking toward door to " + label, 3_000L);
            Navigation.walkToNoTeleports(ctx, tile);
            nextActionAt = System.currentTimeMillis() + 850L;
            return true;
        }
        if (distance(here, tile) > 3) {
            status("approaching door to " + label, 3_000L);
            if (ctx.walking().walkOnMap(tile) || ctx.walking().walkTo(tile)) {
                nextActionAt = System.currentTimeMillis() + 850L;
                return true;
            }
            return false;
        }
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        if (door.interact("Open") || ctx.menu().interact("Open", door, false)) {
            logger.accept("Knight's Sword: opened " + door.getName() + " at " + tile
                    + " on route to " + label);
            nextActionAt = System.currentTimeMillis() + 1100L;
            return true;
        }
        status("door interaction failed on route to " + label, 4_000L);
        return false;
    }

    private void interactObject(APIContext ctx, int id, Tile place, String[] actions,
                                String label, int... alternateIds) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (me.getPlane() == place.getPlane() && openRoute(ctx, place, label)) return;
        if (me.getPlane() != place.getPlane() || distance(me, place) > 10) {
            walk(ctx, place, label);
            return;
        }
        SceneObject object = null;
        if (id >= 0) object = ctx.objects().query().id(id).results().nearest();
        if (object == null) for (int alternate : alternateIds) {
            if (alternate >= 0) {
                object = ctx.objects().query().id(alternate).results().nearest();
                if (object != null) break;
            }
        }
        if (object == null && id < 0)
            object = ctx.objects().query().named("Ladder").results().nearest();
        if (object == null && id == ObjectID.FAI_TRAPDOOR)
            object = ctx.objects().query().named("Trapdoor").results().nearest();
        if (object == null || object.tileDistanceTo(ctx) > 10) {
            status("locating " + label, 5_000L);
            walk(ctx, place, label);
            return;
        }
        for (String action : actions) {
            if (object.interact(action)) {
                status(label, 1_500L);
                if (id == ObjectID.VYVINCUPBOARDOPEN) {
                    expectedDialogueStage = stage(ctx);
                    expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                    logger.accept("Knight's Sword: accepting portrait cupboard dialogue");
                }
                nextActionAt = System.currentTimeMillis() + 1100L;
                return;
            }
        }
        status("interaction failed: " + label, 5_000L);
    }

    private void interactObject(APIContext ctx, int id, Tile place, String[] actions, String label) {
        interactObject(ctx, id, place, actions, label, new int[0]);
    }

    private void walk(APIContext ctx, Tile tile, String label) {
        status("walking to " + label, 5_000L);
        WalkState result = Navigation.walkToNoTeleports(ctx, tile);
        if (result != WalkState.SUCCESS) {
            Tile current = ctx.localPlayer().getLocation();
            logger.accept("Knight's Sword: route to " + label + " failed: " + result
                    + "; from=" + current + " target=" + tile);
            if (current != null && current.getPlane() == tile.getPlane()
                    && distance(current, tile) <= 18) {
                ctx.walking().walkOnMap(tile);
            }
        }
        nextActionAt = System.currentTimeMillis() + 750L;
    }

    private void status(String message, long logInterval) {
        stats.setStatus("Knight's Sword: " + message);
        long now = System.currentTimeMillis();
        if (now >= nextLogAt) {
            logger.accept("Knight's Sword: " + message);
            nextLogAt = now + logInterval;
        }
    }

    private int distance(Tile a, Tile b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private void suspend(String reason) {
        logger.accept("Knight's Sword: " + reason + "; retrying in five minutes");
        stats.setStatus("Knight's Sword: " + reason);
        suspendedUntil = System.currentTimeMillis() + 5 * 60_000L;
    }
}
