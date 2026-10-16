package org.example.modules.questing;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID1;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.ICombatAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.navigation.Navigation;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.awt.event.KeyEvent;
import java.awt.Point;
import java.util.function.Consumer;

/** The quest is driven by the game's progress varbit, so a restart resumes at the real step. */
public final class IdesOfMilkQuestModule implements ManagedF2PModule {
    private static final Tile CASSIUS = new Tile(3171, 3277, 0);
    private static final Tile GILLIE = new Tile(3253, 3270, 0);
    private static final Tile SETH = new Tile(3228, 3291, 0);
    private static final Tile SHELF = new Tile(3227, 3287, 0);
    private static final Tile CASTLE_STAIRS = new Tile(3205, 3208, 0);
    private static final Tile DUKE = new Tile(3210, 3220, 1);
    private static final Tile BULL_GATE = new Tile(3262, 3294, 0);
    private static final Tile BULL_GATE_APPROACH = new Tile(3261, 3292, 0);
    private static final Tile COW_FIELD_EXIT = new Tile(3253, 3265, 0);
    private static final Area COW_FIELD_INTERIOR = new Area(3249, 3268, 3264, 3295);
    private static final Area COW_FIELD_GATE = new Area(3251, 3265, 3255, 3269);
    private static final Tile GE = new Tile(3165, 3488, 0);
    private static final String[] FOOD = {"Lobster", "Swordfish", "Salmon", "Trout", "Tuna", "Cake", "Bread", "Shrimps"};
    private static final String[] MELEE_WEAPONS = {
            "Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Black scimitar",
            "Steel scimitar", "Iron scimitar", "Bronze scimitar",
            "Rune sword", "Adamant sword", "Mithril sword", "Black sword",
            "Steel sword", "Iron sword", "Bronze sword"
    };
    private static final String[] LAMP_SKILLS = {
            "Attack", "Strength", "Defence", "Ranged", "Magic", "Prayer", "Hitpoints"
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private int lastStage = -1;
    private long nextActionAt;
    private String lastBossWarning = "";
    private long lastDodgeAt;
    private long lastStallLogAt;
    private boolean buyingFood;
    private boolean foodOfferPlaced;
    private boolean noFoodFunds;
    private boolean buyingMeleeWeapon;
    private boolean meleeWeaponOfferPlaced;
    private long lastSupplyNavigationAt;
    private long nextSupplyLogAt;
    private long nextGateLogAt;
    private long lastGateAttemptAt;
    private Tile lastWalkTarget;
    private Tile lastWalkLocation;
    private int stalledWalks;
    private long lastPathAttemptAt;
    private int lastTalkId = -1;
    private int lastTalkStage = -1;
    private int repeatedTalks;
    private long nextStaleDialogueLogAt;
    private long emptyDialogueSince;
    private String unverifiedDialogueSnapshot = "";
    private long unverifiedDialogueSince;
    private int unverifiedDialogueRecoveryAttempts;
    private String selectedLampSkill;
    private boolean lampSkillClicked;
    private long nextLampLogAt;

    public IdesOfMilkQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override public String name() { return "quests.ides_of_milk"; }
    @Override public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || isComplete(ctx)) return false;
        int stage = ctx.vars().getVarbit(VarbitID.COWQUEST);
        return stage > 0 || meleeLevelsReady(ctx);
    }
    @Override public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.vars().getVarbit(VarbitID.COWQUEST) >= 22
                && ctx.vars().getVarbit(VarbitID.COWQUEST_REWARD) > 0
                && !ctx.inventory().contains(ItemID.COWBOSS_REWARD_LAMP);
    }
    @Override public int priority(APIContext ctx) {
        if (ctx == null || isComplete(ctx)) return 0;
        return ctx.vars().getVarbit(VarbitID.COWQUEST) > 0 ? 85 : 55;
    }
    @Override public boolean isProtectedSubphase(APIContext ctx) {
        return false;
    }
    @Override public String protectedSubphaseName(APIContext ctx) { return "The Ides of Milk in progress"; }

    @Override public void execute(APIContext ctx) {
        int stage = ctx.vars().getVarbit(VarbitID.COWQUEST);
        if (stage != lastStage) {
            log("Ides of Milk: quest stage " + lastStage + " -> " + stage);
            lastStage = stage;
            nextActionAt = 0;
            repeatedTalks = 0;
        }
        if (System.currentTimeMillis() < nextActionAt) return;
        if (stage >= 22) {
            if (isComplete(ctx)) {
                stats.setStatus("The Ides of Milk complete; lamp redeemed");
                return;
            }
            if (handleRewardLamp(ctx)) return;
            if (handleDialogue(ctx, stage)) return;
            if (ctx.vars().getVarbit(VarbitID.COWQUEST_REWARD) == 0)
                talk(ctx, NpcID.GILLIE_THE_MILKMAID, "Gillie Groats for quest rewards", GILLIE);
            else stall("Gillie reward claimed; waiting for lamp to appear", ctx);
            return;
        }
        if (handleDialogue(ctx, stage)) return;
        if (ctx.bank().isOpen() && stage != 18) { ctx.bank().close(); return; }
        if (ctx.grandExchange().isOpen() && stage != 18) { ctx.grandExchange().close(); return; }

        switch (stage) {
            case 0, 1, 2 -> talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
            case 3 -> {
                if (ctx.vars().getVarbit(VarbitID.COWQUEST_GILLIE_INFORMATION) == 0)
                    talk(ctx, NpcID.GILLIE_THE_MILKMAID, "Gillie Groats", GILLIE);
                else if (!exitCowField(ctx))
                    talk(ctx, NpcID.FAVOUR_SETH_GROATS, "Seth Groats", SETH);
            }
            case 4 -> {
                if (ctx.inventory().contains(ItemID.COWQUEST_HUSBANDRY_BOOK))
                    talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
                else interactObject(ctx, "Shelves", "Search", SHELF);
            }
            case 5 -> talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
            case 6 -> useItem(ctx, ItemID.COWQUEST_MILK_SAMPLE_1, "Drink", "first milk sample");
            case 8 -> talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
            case 10 -> visitDuke(ctx);
            case 12 -> talk(ctx, NpcID.GILLIE_THE_MILKMAID, "Gillie Groats", GILLIE);
            case 14 -> useItem(ctx, ItemID.COWQUEST_MILK_SAMPLE_2, "Drink", "second milk sample");
            case 16 -> talk(ctx, NpcID.GILLIE_THE_MILKMAID, "Gillie Groats", GILLIE);
            case 18 -> fightBull(ctx);
            case 20 -> talk(ctx, NpcID.GILLIE_THE_MILKMAID, "Gillie Groats", GILLIE);
            case 21 -> talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
            default -> stall("Unknown quest stage " + stage + "; waiting for game state", ctx);
        }
    }

    private boolean handleRewardLamp(APIContext ctx) {
        ItemWidget lamp = ctx.inventory().getItem(ItemID.COWBOSS_REWARD_LAMP);
        if (lamp == null) return false;
        if (selectedLampSkill == null) {
            selectedLampSkill = LAMP_SKILLS[ThreadLocalRandom.current().nextInt(LAMP_SKILLS.length)];
            log("Ides of Milk: randomly selected " + selectedLampSkill + " for the 1,000 XP lamp");
        }
        List<WidgetChild> choices = ctx.dialogues().getOptions();
        if (choices != null && !choices.isEmpty()) {
            for (WidgetChild choice : choices) {
                String label = cleanWidgetText(choice.getText());
                if (selectedLampSkill.equalsIgnoreCase(label) && choice.click(false)) {
                    lampSkillClicked = true;
                    log("Ides of Milk: selected lamp skill " + selectedLampSkill + " in dialogue");
                    pauseDialogue();
                    return true;
                }
                if (lampSkillClicked && "Confirm".equalsIgnoreCase(label) && choice.click(false)) {
                    log("Ides of Milk: confirmed " + selectedLampSkill + " XP reward");
                    pauseDialogue();
                    return true;
                }
            }
            return handleDialogue(ctx, 22);
        }
        if (ctx.dialogues().canContinue()) {
            handleDialogue(ctx, 22);
            return true;
        }
        if (!ctx.widgets().isInterfaceOpen()) {
            if (lamp.interact("Rub")) {
                log("Ides of Milk: opened Gillie's XP lamp");
                pause(900);
            } else stall("Unable to open Gillie's XP lamp", ctx);
            return true;
        }
        if (!lampSkillClicked) {
            WidgetChild skill = visibleRewardWidget(ctx, selectedLampSkill);
            if (skill != null && clickRewardWidget(ctx, skill)) {
                lampSkillClicked = true;
                log("Ides of Milk: selected lamp skill " + selectedLampSkill);
                pause(700);
                return true;
            }
        } else {
            WidgetChild confirm = visibleRewardWidget(ctx, "Confirm");
            if (confirm != null && clickRewardWidget(ctx, confirm)) {
                log("Ides of Milk: confirmed " + selectedLampSkill + " XP reward");
                pause(1100);
                return true;
            }
        }
        if (System.currentTimeMillis() >= nextLampLogAt) {
            logger.accept("Ides of Milk: waiting for lamp skill/confirmation widget; target="
                    + selectedLampSkill + ", skillClicked=" + lampSkillClicked);
            nextLampLogAt = System.currentTimeMillis() + 10_000L;
        }
        pause(600);
        return true;
    }

    private WidgetChild visibleRewardWidget(APIContext ctx, String label) {
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> {
            if (candidate == null || !candidate.isValid() || candidate.isHidden()
                    || candidate.getWidth() <= 0 || candidate.getHeight() <= 0) return false;
            String text = candidate.getText();
            String raw = candidate.getRawText();
            if (label.equalsIgnoreCase(cleanWidgetText(text))
                    || label.equalsIgnoreCase(cleanWidgetText(raw))) return true;
            List<String> actions = candidate.getActions();
            if (actions == null) return false;
            for (String action : actions) {
                if (label.equalsIgnoreCase(cleanWidgetText(action))
                        || ("Select " + label).equalsIgnoreCase(cleanWidgetText(action))) return true;
            }
            return false;
        })) return widget;
        return null;
    }

    private String cleanWidgetText(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
    }

    private boolean clickRewardWidget(APIContext ctx, WidgetChild widget) {
        Point center = widget.getCentralPoint();
        return center != null && (ctx.mouse().click(center, false) || widget.click(false));
    }

    private boolean handleDialogue(APIContext ctx, int stage) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            emptyDialogueSince = 0;
            unverifiedDialogueSnapshot = "";
            unverifiedDialogueSince = 0L;
            unverifiedDialogueRecoveryAttempts = 0;
            return false;
        }
        if (ctx.dialogues().canContinue()) {
            emptyDialogueSince = 0;
            unverifiedDialogueSnapshot = "";
            if (!ctx.dialogues().selectContinue()) ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            pauseDialogue();
            return true;
        }
        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options == null || options.isEmpty()) {
            if (emptyDialogueSince == 0) emptyDialogueSince = System.currentTimeMillis();
            if (ctx.dialogues().isLoading()
                    && System.currentTimeMillis() - emptyDialogueSince < 3_000L) {
                pause(650);
                return true;
            }
            if (System.currentTimeMillis() >= nextStaleDialogueLogAt) {
                logger.accept("Ides of Milk: ignoring stale dialogue flag; resuming stage " + stage);
                nextStaleDialogueLogAt = System.currentTimeMillis() + 10_000L;
            }
            return false;
        }
        emptyDialogueSince = 0;
        String[] wanted = stage >= 22 ? new String[]{"reward", "amulet", "lamp", "yes"} : switch (stage) {
            case 0, 1, 2, 18 -> new String[]{"yes", "start the ides of milk", "fight", "enter"};
            case 3 -> new String[]{"what makes your cows", "what makes", "productive", "cassius"};
            case 10 -> new String[]{"permit", "milk"};
            case 12, 16 -> new String[]{"sample", "permit", "bull", "help"};
            default -> new String[]{"yes", "milk", "book", "quest"};
        };
        for (String word : wanted) {
            for (WidgetChild option : options) {
                String label = option.getText() == null ? "" : option.getText().toLowerCase(Locale.ROOT);
                if (label.contains(word) && option.click(false)) {
                    log("Ides of Milk: dialogue option " + label);
                    pauseDialogue();
                    return true;
                }
            }
        }
        if (options.size() == 1 && options.get(0).click(false)) {
            pauseDialogue();
            return true;
        }
        return recoverUnverifiedDialogue(ctx, stage, options);
    }

    private boolean recoverUnverifiedDialogue(APIContext ctx, int stage, List<WidgetChild> options) {
        String snapshot = stage + ":" + cleanWidgetText(ctx.dialogues().getText()) + ":"
                + options.stream().map(option -> option == null ? "" : cleanWidgetText(option.getText())).toList();
        long now = System.currentTimeMillis();
        if (!snapshot.equals(unverifiedDialogueSnapshot)) {
            unverifiedDialogueSnapshot = snapshot;
            unverifiedDialogueSince = now;
            unverifiedDialogueRecoveryAttempts = 0;
        }
        if (now - unverifiedDialogueSince < 10000L) {
            stall("Dialogue option needs identification", ctx);
            return true;
        }
        if (unverifiedDialogueRecoveryAttempts < 2) {
            unverifiedDialogueRecoveryAttempts++;
            log("Ides of Milk: closing unchanged unverified dialogue before retrying stage " + stage);
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            pause(1000);
            return true;
        }
        stall("Unverified dialogue persisted; retrying current stage", ctx);
        return false;
    }

    private void talk(APIContext ctx, int id, String name, Tile destination) {
        NPC visible = ctx.npcs().query().id(id).actions("Talk-to").results().nearest();
        NPC npc = ctx.npcs().query().id(id).actions("Talk-to").reachable().results().nearest();
        if (npc == null || !npc.isValid()) {
            if (visible != null && visible.isValid() && openRoute(ctx, visible.getLocation(), destination)) return;
            walk(ctx, destination, name);
            return;
        }
        repeatedTalks = id == lastTalkId && lastStage == lastTalkStage ? repeatedTalks + 1 : 0;
        lastTalkId = id;
        lastTalkStage = lastStage;
        if (repeatedTalks >= 2 && openRoute(ctx, npc.getLocation(), destination)) {
            repeatedTalks = 0;
            return;
        }
        if (npc.interact("Talk-to")) {
            log("Ides of Milk: talking to " + name);
            Time.sleep(800, 1800, () -> ctx.dialogues().isDialogueOpen()
                    || ctx.dialogues().isChatOpen()
                    || ctx.vars().getVarbit(VarbitID.COWQUEST) != lastStage, 100);
            pause(ThreadLocalRandom.current().nextLong(1200, 2401));
        } else walk(ctx, destination, name);
    }

    private void interactObject(APIContext ctx, String name, String action, Tile destination) {
        SceneObject object = ctx.objects().query().named(name).actions(action).results().nearest();
        if (object != null && object.isValid() && object.getLocation() != null
                && object.getLocation().tileDistanceTo(ctx) <= 9 && object.interact(action)) {
            log("Ides of Milk: " + action + " " + name);
            pause(850);
            return;
        }
        if (object != null && object.isValid() && openRoute(ctx, object.getLocation(), destination)) return;
        walk(ctx, destination, name);
    }

    private void useItem(APIContext ctx, int id, String action, String label) {
        ItemWidget item = ctx.inventory().getItem(id);
        if (item == null) {
            stall("Missing " + label + "; returning to Cassius", ctx);
            talk(ctx, NpcID.COWBOSS_FARMER, "Cassius", CASSIUS);
            return;
        }
        if (item.interact(action)) {
            log("Ides of Milk: " + action + " " + label);
            pause(850);
        }
    }

    private void visitDuke(APIContext ctx) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return;
        if (here.getPlane() == 0) {
            if (CASTLE_STAIRS.tileDistanceTo(ctx) > 8) {
                walk(ctx, CASTLE_STAIRS, "Lumbridge Castle stairs");
                return;
            }
            interactObject(ctx, "Staircase", "Climb-up", CASTLE_STAIRS);
            return;
        }
        talk(ctx, NpcID.DUKE_OF_LUMBRIDGE, "Duke Horacio", DUKE);
    }

    private void fightBull(APIContext ctx) {
        NPC bull = ctx.npcs().query().id(NpcID.COWBOSS).results().nearest();
        if (bull == null || !bull.isValid()) {
            if (!readyForFight(ctx)) return;
            if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
            if (ctx.grandExchange().isOpen()) { ctx.grandExchange().close(); return; }
            if (ctx.menu().isOpen()) {
                if (selectReleaseFromMenu(ctx)) return;
                ctx.menu().closeMenu();
                pause(400);
                return;
            }
            if (BULL_GATE.tileDistanceTo(ctx) > 10) {
                walk(ctx, BULL_GATE_APPROACH, "Brutus gate approach");
                return;
            }
            SceneObject gate = ctx.objects().query()
                    .id(ObjectID1.FENCEGATE_L_COWBOSS_START,
                            ObjectID1.FENCEGATE_R_COWBOSS_START)
                    .results().nearest();
            if (gate == null || !gate.isValid()) {
                gate = ctx.objects().query().nameContains("Gate")
                        .within(new Area(3260, 3292, 3264, 3296)).results().nearest();
            }
            if (gate != null && gate.isValid()) {
                if (System.currentTimeMillis() >= nextGateLogAt) {
                    logger.accept("Ides of Milk: Brutus gate id=" + gate.getId()
                            + " actions=" + gate.getActions() + " location=" + gate.getLocation());
                    nextGateLogAt = System.currentTimeMillis() + 10_000L;
                }
                if (System.currentTimeMillis() - lastGateAttemptAt < 2_000L) {
                    pause(500);
                    return;
                }
                lastGateAttemptAt = System.currentTimeMillis();
                if (gate.hasAction("Release") && (gate.interact("Release")
                        || ctx.menu().interact("Release", gate, true))) {
                    if (ctx.menu().isOpen()) selectReleaseFromMenu(ctx);
                    log("Ides of Milk: selected Release on Brutus gate id=" + gate.getId());
                    pause(ThreadLocalRandom.current().nextLong(1200, 2001));
                    return;
                }
                if (ctx.menu().isOpen() && selectReleaseFromMenu(ctx)) return;
            }
            if (BULL_GATE_APPROACH.tileDistanceTo(ctx) > 2)
                walk(ctx, BULL_GATE_APPROACH, "Brutus gate approach");
            else stall("Brutus gate visible but no interaction succeeded", ctx);
            return;
        }
        int hp = ctx.localPlayer().getHealthPercent();
        if (hp <= 55 && eatFood(ctx)) return;
        if (hp <= 30) {
            stall("Low health in bull fight; escaping to gate", ctx);
            ctx.walking().walkTo(new Tile(3258, 3290, ctx.localPlayer().getLocation().getPlane()));
            return;
        }
        String warning = bull.getMessage();
        if (warning != null) {
            String lower = warning.toLowerCase(Locale.ROOT);
            if ((lower.contains("snort") || lower.contains("growl"))
                    && (!lower.equals(lastBossWarning) || System.currentTimeMillis() - lastDodgeAt > 4500)) {
                lastBossWarning = lower;
                lastDodgeAt = System.currentTimeMillis();
                dodge(ctx, bull);
                return;
            }
        } else lastBossWarning = "";
        if (!ctx.localPlayer().isInCombat() && bull.interact("Attack")) {
            log("Ides of Milk: attacking the bull");
            pause(400);
        }
    }

    private boolean selectReleaseFromMenu(APIContext ctx) {
        if (!ctx.menu().isOpen()) return false;
        if (!ctx.menu().contains("Release")) {
            logger.accept("Ides of Milk: Brutus gate menu has no Release; actions="
                    + ctx.menu().getActions() + ", options=" + ctx.menu().getOptions());
            return false;
        }
        boolean selected = ctx.menu().interact("Release", true);
        if (!selected) {
            Point option = ctx.menu().getLocationOf("Release");
            selected = option != null && ctx.mouse().click(option, false);
        }
        if (selected) {
            log("Ides of Milk: chose Release from Brutus gate menu");
            pause(ThreadLocalRandom.current().nextLong(1200, 2001));
        }
        return selected;
    }

    private boolean readyForFight(APIContext ctx) {
        if (ctx.localPlayer().getHealthPercent() < 75 && eatFood(ctx)) return false;
        if (foodCount(ctx) < 5) {
            supplyFood(ctx);
            return false;
        }
        if (!hasMeleeWeapon(ctx)) {
            supplyMeleeWeapon(ctx);
            return false;
        }
        ICombatAPI.AttackStyle style = ctx.combat().hasOption(ICombatAPI.AttackStyle.CONTROLLED)
                ? ICombatAPI.AttackStyle.CONTROLLED : ICombatAPI.AttackStyle.ACCURATE;
        if (ctx.combat().getAttackStyle() != style && ctx.combat().hasOption(style)) {
            if (ctx.combat().toggleAttackStyle(style)) {
                log("Ides of Milk: set melee combat style " + style);
                pause(600);
            }
            return false;
        }
        return true;
    }

    private boolean meleeLevelsReady(APIContext ctx) {
        return ctx.skills().get(Skill.Skills.ATTACK).getRealLevel() >= 15
                && ctx.skills().get(Skill.Skills.STRENGTH).getRealLevel() >= 15
                && ctx.skills().get(Skill.Skills.DEFENCE).getRealLevel() >= 15;
    }

    private boolean hasMeleeWeapon(APIContext ctx) {
        ItemWidget weapon = ctx.equipment().getItem(IEquipmentAPI.Slot.WEAPON);
        if (weapon == null || weapon.getName() == null) return false;
        String name = weapon.getName().toLowerCase(Locale.ROOT);
        return name.contains("scimitar") || name.contains("sword") || name.contains("mace")
                || name.contains("dagger") || name.contains("longsword");
    }

    private int requiredAttackLevel(String name) {
        if (name.startsWith("Rune ")) return 40;
        if (name.startsWith("Adamant ")) return 30;
        if (name.startsWith("Mithril ")) return 20;
        if (name.startsWith("Black ")) return 10;
        if (name.startsWith("Steel ")) return 5;
        return 1;
    }

    private void supplyMeleeWeapon(APIContext ctx) {
        int attack = ctx.skills().get(Skill.Skills.ATTACK).getRealLevel();
        for (String name : MELEE_WEAPONS) {
            if (attack < requiredAttackLevel(name)) continue;
            ItemWidget weapon = ctx.inventory().getItem(name);
            if (weapon == null) continue;
            if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
            if (ctx.grandExchange().isOpen()) { ctx.grandExchange().close(); return; }
            if (weapon.interact("Wield")) {
                log("Ides of Milk: equipping " + name + " for melee against Brutus");
                pause(600);
            }
            return;
        }
        if (exitCowField(ctx)) return;
        if (!buyingMeleeWeapon && ctx.bank().isOpen()) {
            for (String name : MELEE_WEAPONS) {
                if (attack >= requiredAttackLevel(name) && ctx.bank().getCount(name) > 0) {
                    ctx.bank().withdraw(1, name);
                    pause(650);
                    return;
                }
            }
            buyingMeleeWeapon = true;
        }
        if (ctx.bank().isOpen()) {
            if (ctx.inventory().getCount("Coins") < 1000 && ctx.bank().getCount("Coins") > 0) {
                ctx.bank().withdraw(Math.min(1000, ctx.bank().getCount("Coins")), "Coins");
                pause(650);
                return;
            }
            ctx.bank().close();
            return;
        }
        if (!buyingMeleeWeapon || ctx.inventory().getCount("Coins") < 1000) {
            if (buyingMeleeWeapon && ctx.inventory().getCount("Coins") < 1000) {
                stall("Need a melee weapon or 1,000 coins for Brutus", ctx);
                return;
            }
            if (System.currentTimeMillis() - lastSupplyNavigationAt >= 4_000L) {
                lastSupplyNavigationAt = System.currentTimeMillis();
                if (Navigation.isBankReachable(ctx)) Navigation.openBank(ctx);
                else Navigation.walkToBank(ctx);
            }
            pause(900);
            return;
        }
        if (ctx.grandExchange().isOpen()) {
            if (meleeWeaponOfferPlaced) {
                ctx.grandExchange().collectToInventory();
                pause(1000);
            } else if (ctx.grandExchange().placeBuyOffer("Steel scimitar", 1, 1000)) {
                meleeWeaponOfferPlaced = true;
                log("Ides of Milk: placed Steel scimitar buy offer");
                pause(1200);
            } else pause(1000);
            return;
        }
        if (GE.tileDistanceTo(ctx) > 12) walk(ctx, GE, "Grand Exchange for melee weapon");
        else { ctx.grandExchange().open(); pause(900); }
    }

    private void supplyFood(APIContext ctx) {
        if (foodCount(ctx) >= 5) {
            buyingFood = false;
            foodOfferPlaced = false;
            if (ctx.grandExchange().isOpen()) ctx.grandExchange().close();
            return;
        }
        if (System.currentTimeMillis() >= nextSupplyLogAt) {
            logger.accept("Ides of Milk: preparing for bull; food=" + foodCount(ctx)
                    + ", coins=" + ctx.inventory().getCount("Coins")
                    + ", location=" + ctx.localPlayer().getLocation());
            nextSupplyLogAt = System.currentTimeMillis() + 10_000L;
        }
        if (exitCowField(ctx)) return;
        if (!buyingFood && ctx.bank().isOpen()) {
            for (String name : FOOD) {
                if (ctx.bank().getCount(name) > 0) {
                    ctx.bank().withdraw(Math.min(12, ctx.bank().getCount(name)), name);
                    pause(700);
                    return;
                }
            }
            buyingFood = true;
        }
        if (ctx.bank().isOpen()) {
            if (ctx.bank().getCount("Coins") > 0 && ctx.inventory().getCount("Coins") < 300) {
                ctx.bank().withdraw(Math.min(1000, ctx.bank().getCount("Coins")), "Coins");
                pause(700);
                return;
            }
            if (ctx.inventory().getCount("Coins") < 300) noFoodFunds = true;
            ctx.bank().close();
            return;
        }
        if (!buyingFood || ctx.inventory().getCount("Coins") < 300) {
            if (noFoodFunds) {
                stall("Need coins for combat food", ctx);
                return;
            }
            if (System.currentTimeMillis() - lastSupplyNavigationAt >= 4_000L) {
                lastSupplyNavigationAt = System.currentTimeMillis();
                if (Navigation.isBankReachable(ctx)) Navigation.openBank(ctx);
                else Navigation.walkToBank(ctx);
            }
            pause(900);
            return;
        }
        if (ctx.grandExchange().isOpen()) {
            if (foodOfferPlaced) {
                ctx.grandExchange().collectToInventory();
                pause(1000);
            } else if (ctx.grandExchange().placeBuyOffer("Trout", 12, 50)) {
                foodOfferPlaced = true;
                log("Ides of Milk: placed food buy offer");
                pause(1200);
            } else pause(1000);
            return;
        }
        if (GE.tileDistanceTo(ctx) > 12) walk(ctx, GE, "Grand Exchange for food");
        else { ctx.grandExchange().open(); pause(900); }
    }

    private int foodCount(APIContext ctx) {
        int total = 0;
        for (String name : FOOD) total += ctx.inventory().getCount(name);
        return total;
    }

    private boolean eatFood(APIContext ctx) {
        for (String name : FOOD) {
            ItemWidget food = ctx.inventory().getItem(name);
            if (food != null && food.interact("Eat")) {
                log("Ides of Milk: eating " + name);
                pause(450);
                return true;
            }
        }
        return false;
    }

    private void dodge(APIContext ctx, NPC bull) {
        Tile me = ctx.localPlayer().getLocation();
        Tile enemy = bull.getLocation();
        if (me == null || enemy == null) return;
        int dx = me.getX() - enemy.getX();
        int dy = me.getY() - enemy.getY();
        boolean alongX = Math.abs(dx) >= Math.abs(dy);
        Tile[] candidates = alongX
                ? new Tile[]{new Tile(me.getX(), me.getY() + 2, me.getPlane()),
                    new Tile(me.getX(), me.getY() - 2, me.getPlane()),
                    new Tile(me.getX() + (dx >= 0 ? 1 : -1), me.getY(), me.getPlane())}
                : new Tile[]{new Tile(me.getX() + 2, me.getY(), me.getPlane()),
                    new Tile(me.getX() - 2, me.getY(), me.getPlane()),
                    new Tile(me.getX(), me.getY() + (dy >= 0 ? 1 : -1), me.getPlane())};
        for (Tile candidate : candidates) {
            if (ctx.walking().walkToOnScreen(candidate) || ctx.walking().walkOnScreen(candidate)) {
                log("Ides of Milk: dodging " + lastBossWarning + " from " + me + " to " + candidate);
                pause(350);
                return;
            }
        }
        log("Ides of Milk: no reachable dodge tile from " + me);
        pause(350);
    }

    private void walk(APIContext ctx, Tile destination, String label) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null) return;
        if (!COW_FIELD_INTERIOR.contains(destination) && exitCowField(ctx)) return;
        boolean sameTarget = destination.equals(lastWalkTarget);
        if (sameTarget && here.equals(lastWalkLocation)) stalledWalks++;
        else stalledWalks = 0;
        lastWalkTarget = destination;
        lastWalkLocation = here;
        if (stalledWalks >= 2 && openRoute(ctx, destination, destination)) {
            stalledWalks = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (sameTarget && now - lastPathAttemptAt < 3_500L) {
            pause(800);
            return;
        }
        stats.setStatus("The Ides of Milk: travelling to " + label);
        lastPathAttemptAt = now;
        Navigation.walkToNoTeleports(ctx, destination);
        pause(1000);
    }

    private boolean exitCowField(APIContext ctx) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || !COW_FIELD_INTERIOR.contains(here)) return false;
        SceneObject gate = ctx.objects().query().actions("Open")
                .nameContains("Gate").within(COW_FIELD_GATE).results().nearest();
        if (tryOpen(ctx, gate)) return true;
        stats.setStatus("The Ides of Milk: leaving the cow field");
        if (System.currentTimeMillis() - lastPathAttemptAt >= 2_500L) {
            logger.accept("Ides of Milk: leaving cow field through south gate from " + here);
            lastPathAttemptAt = System.currentTimeMillis();
            ctx.walking().walkTo(COW_FIELD_EXIT);
        }
        pause(700);
        return true;
    }

    private boolean openRoute(APIContext ctx, Tile target, Tile destination) {
        Tile here = ctx.localPlayer().getLocation();
        if (here == null || target == null || distance(here, target) > 20) return false;
        SceneObject blocker = ctx.walking().getBlockingObjectBetween(here, target);
        if (tryOpen(ctx, blocker)) return true;
        if (destination == null || distance(here, destination) > 20) return false;
        Area search = new Area(destination.getX() - 12, destination.getY() - 12,
                destination.getX() + 12, destination.getY() + 12);
        SceneObject entrance = ctx.objects().query().actions("Open")
                .nameContains("Gate", "Door").within(search).results().nearest();
        return tryOpen(ctx, entrance);
    }

    private boolean tryOpen(APIContext ctx, SceneObject object) {
        if (object == null || !object.isValid() || !object.hasAction("Open")) return false;
        Tile tile = object.getLocation();
        if (tile == null) return false;
        if (tile.tileDistanceTo(ctx) > 3) {
            stats.setStatus("The Ides of Milk: approaching " + object.getName());
            ctx.walking().walkTo(tile);
            pause(750);
            return true;
        }
        org.example.core.navigation.AccessRecovery.interaction(ctx, object, "Open");
        if (object.interact("Open") || ctx.menu().interact("Open", object, false)) {
            log("Ides of Milk: opening " + object.getName() + " at " + tile);
            pause(ThreadLocalRandom.current().nextLong(1100, 1901));
            return true;
        }
        return false;
    }

    private void stall(String message, APIContext ctx) {
        stats.setStatus("The Ides of Milk: " + message);
        if (System.currentTimeMillis() >= lastStallLogAt + 15_000) {
            logger.accept("Ides of Milk: " + message + "; stage=" + lastStage);
            lastStallLogAt = System.currentTimeMillis();
        }
        pause(1000);
    }

    private void pause(long millis) { nextActionAt = System.currentTimeMillis() + millis; }
    private void pauseDialogue() { pause(ThreadLocalRandom.current().nextLong(1700, 3301)); }
    private int distance(Tile first, Tile second) {
        return first.getPlane() == second.getPlane()
                ? Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getY() - second.getY()))
                : Integer.MAX_VALUE;
    }
    private void log(String message) { stats.setStatus(message); logger.accept(message); }
}
