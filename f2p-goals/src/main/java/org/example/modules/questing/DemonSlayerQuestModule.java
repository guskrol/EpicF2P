package org.example.modules.questing;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.NpcID;
import com.epicbot.api.gameval.ObjectID;
import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.Item;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.GroundItem;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
import com.epicbot.api.shared.methods.IQuestAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Spell;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.webwalking.model.WalkState;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;
import org.example.core.questing.QuestActionGuard;
import org.example.modules.combat.F2PCombatTargets;
import org.example.modules.combat.MobDefinition;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;
import com.epicbot.api.shared.util.time.Time;

/** Standalone Demon Slayer route. Quest Helper's varp/item/varbit state is checked on every tick. */
public final class DemonSlayerQuestModule implements ManagedF2PModule {
    private static final IQuestAPI.Quest QUEST = IQuestAPI.Quest.DEMON_SLAYER;
    private static final Area ARIS = new Area(3201, 3421, 3207, 3427);
    private static final Area PRYSIN = new Area(3200, 3469, 3207, 3476);
    private static final Area ROVIN = new Area(2, 3201, 3495, 3207, 3501);
    private static final Area TRAIBORN = new Area(1, 3109, 3159, 3117, 3166);
    private static final Area WIZARD_TOWER_GROUND = new Area(0, 3099, 3152, 3118, 3169);
    private static final Area GE = new Area(3160, 3478, 3175, 3490);
    private static final Area SEWER = new Area(3151, 9855, 3290, 9919);
    private static final Tile CASTLE_STAIRS = new Tile(3203, 3498, 0);
    private static final Tile LUMBRIDGE_CASTLE_STAIRS = new Tile(3205, 3208, 0);
    private static final Tile WIZARD_STAIRS = new Tile(3104, 3160, 0);
    private static final Tile WIZARD_TOWER_BRIDGE = new Tile(3102, 3171, 0);
    private static final Tile WIZARD_STAIR_ROOM_DOOR = new Tile(3107, 3162, 0);
    private static final int WIZARD_STAIR_ROOM_DOOR_ID = 23972;
    private static final Tile MANHOLE = new Tile(3237, 3458, 0);
    private static final Tile SEWER_LADDER = new Tile(3237, 9858, 0);
    private static final Tile DRAIN = new Tile(3225, 3496, 0);
    private static final Tile DRAIN_OUTSIDE_STAND = new Tile(3226, 3495, 0);
    private static final Tile SEWER_KEY = new Tile(3225, 9897, 0);
    private static final Tile DELRITH = new Tile(3227, 3370, 0);
    private static final String[] WORDS = {"Carlem", "Aber", "Camerinthum", "Purchai", "Gabindo"};
    private static final int[] WORD_VARBITS = {VarbitID.DELRITH_INCANTATION_1,
            VarbitID.DELRITH_INCANTATION_2, VarbitID.DELRITH_INCANTATION_3,
            VarbitID.DELRITH_INCANTATION_4, VarbitID.DELRITH_INCANTATION_5};
    private static final String[] FOOD = {"Trout", "Salmon", "Tuna", "Lobster", "Swordfish"};
    private static final MobDefinition[] BONE_SPOTS = {F2PCombatTargets.CHICKENS, F2PCombatTargets.GOBLINS};
    private static final Tile[] BONE_DESTINATIONS = {
            new Tile(3230, 3298, 0), new Tile(3250, 3236, 0)
    };

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private final QuestActionGuard actionGuard;
    private long nextActionAt;
    private long nextLogAt;
    private long expectedDialogueUntil;
    private long progressAt;
    private long offerPlacedAt;
    private long nextCollectAt;
    private int lastSignature = Integer.MIN_VALUE;
    private int recoveries;
    private int incantationIndex;
    private boolean incantationAwaitingTransition;
    private long incantationClickedAt;
    private int collectionAttempts;
    private String pendingBuy;
    private int buyQuantity;
    private int buyTarget;
    private int buyPrice;
    private boolean bankChecked;
    private boolean inventoryCleaned;
    private boolean inventoryPreparedForBones;
    private boolean combatSupplied;
    private int boneSpotIndex;
    private long lastBonePickupAt;
    private int lastBoneCount = -1;
    private boolean lumbridgeTeleportAttempted;
    private long lumbridgeTeleportStartedAt;
    private long traibornDialogueUntil;
    private long prysinHandoffWaitUntil;
    private boolean prysinHandoffActive;
    private boolean prysinHandoffDialogueSeen;
    private String ritualDialogueText = "";
    private long ritualDialogueWaitUntil;
    private long unverifiedIncantationWaitUntil;
    private int wizardStairSearchFailures;
    private NPC boneTarget;
    private Tile boneTargetTile;
    private boolean boneTargetEngaged;
    private long boneTargetStartedAt;
    private long boneCombatEndedAt;
    private long boneLootUntil;
    private int boneAttackAttempts;
    private int dialogueActions;
    private int dialogueExitAttempts;
    private String unverifiedDialogueSnapshot = "";
    private long unverifiedDialogueSince;
    private int unverifiedDialogueRecoveryAttempts;
    private Tile lastOpenedDoor;
    private long lastDoorActionAt;
    private long lastDrainAttemptAt;
    private int drainSearchFailures;

    public DemonSlayerQuestModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
        this.actionGuard = new QuestActionGuard(logger);
    }

    @Override public String name() { return "quests.demon_slayer.standalone"; }
    @Override public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || isComplete(ctx)) return false;
        if (ctx.quests().isStarted(QUEST)) return true;
        return ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.ATTACK).getRealLevel() >= 15
                && ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.STRENGTH).getRealLevel() >= 15
                && ctx.skills().get(com.epicbot.api.shared.model.Skill.Skills.DEFENCE).getRealLevel() >= 15;
    }
    @Override public boolean isComplete(APIContext ctx) {
        return ctx != null && ctx.quests().isCompleted(QUEST);
    }
    @Override public int priority(APIContext ctx) {
        return isComplete(ctx) ? 0 : ctx.quests().isStarted(QUEST) ? 92 : 58;
    }

    @Override public void execute(APIContext ctx) {
        if (ctx == null || System.currentTimeMillis() < nextActionAt) return;
        stats.setTrainingSkill("Questing");
        if (isComplete(ctx)) {
            status("quest complete; closing reward window");
            closeCompletion(ctx);
            return;
        }
        int stage = stage(ctx);
        int signature = stage * 100000 + ctx.inventory().getCount(ItemID.SILVERLIGHT_KEY_1) * 10000
                + ctx.inventory().getCount(ItemID.SILVERLIGHT_KEY_2) * 1000
                + ctx.inventory().getCount(ItemID.SILVERLIGHT_KEY_3) * 100
                + Math.min(25, usableBones(ctx)) * 2
                + ctx.vars().getVarbit(VarbitID.DELRITH_SILVERLIGHT_CASE)
                + ctx.vars().getVarbit(VarbitID.DELRITH_DRAIN_KEY) * 10;
        long now = System.currentTimeMillis();
        if (signature != lastSignature) {
            logger.accept("Demon Slayer: quest/item progress stage=" + stage + " signature=" + signature);
            lastSignature = signature;
            progressAt = now;
            recoveries = 0;
            actionGuard.reset();
        } else if (progressAt > 0 && now - progressAt > 180_000L
                && stage == 2 && ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_2)
                && ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_3)
                && !ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_1)) {
            logger.accept("Demon Slayer: still collecting bones; keeping current spot and bank state");
            progressAt = now;
        } else if (progressAt > 0 && now - progressAt > 180_000L) {
            if (++recoveries > 3) {
                status("stalled after three recoveries; inspect log and quest journal");
                nextActionAt = now + 60_000L;
                return;
            }
            logger.accept("Demon Slayer: no state progress for 3 minutes at stage " + stage
                    + "; bounded recovery " + recoveries + "/3, checking route and bank again");
            bankChecked = false;
            progressAt = now;
        }
        if (handleDialogue(ctx, stage)) return;
        if (ctx.grandExchange().isOpen() || pendingBuy != null) { buy(ctx); return; }
        if (stage == 0) { talk(ctx, NpcID.ARIS, ARIS, "Aris"); return; }
        if (stage == 1) { talk(ctx, NpcID.SIR_PRYSIN, PRYSIN, "Sir Prysin"); return; }
        if (stage != 2) {
            status("unexpected quest varp=" + stage + "; waiting for journal verification");
            nextActionAt = now + 10_000L;
            return;
        }
        if (ctx.vars().getVarbit(VarbitID.DELRITH_SILVERLIGHT_CASE) > 0
                || ctx.inventory().contains(ItemID.SILVERLIGHT) || ctx.equipment().contains("Silverlight")) {
            fight(ctx);
            return;
        }
        if (prysinHandoffActive && now < prysinHandoffWaitUntil) {
            status("waiting for Sir Prysin to process the three keys and give Silverlight");
            nextActionAt = now + 1100L;
            return;
        }
        if (!ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_2)) { rovinKey(ctx); return; }
        if (!ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_3)) { prysinKey(ctx); return; }
        if (!ctx.inventory().contains(ItemID.SILVERLIGHT_KEY_1)) { traibornKey(ctx); return; }
        if (leaveInterior(ctx)) return;
        talk(ctx, NpcID.SIR_PRYSIN, PRYSIN, "Sir Prysin for Silverlight");
    }

    private int stage(APIContext ctx) { return ctx.vars().getVarbit(VarbitID.DEMONSLAYER_MAIN); }
    private int usableBones(APIContext ctx) {
        return ctx.inventory().getCount(true, item -> item != null && item.getId() == ItemID.BONES && !noted(item));
    }
    private boolean noted(Item item) {
        return item != null && (item.isNoted()
                || (item.getId() == item.getNotedId() && item.getUnNotedId() != item.getId()));
    }

    private boolean handleDialogue(APIContext ctx, int stage) {
        List<WidgetChild> options = ctx.dialogues().getOptions();
        boolean hasOptions = options != null && !options.isEmpty();
        boolean continuePrompt = hasContinuePrompt(options);
        boolean dialogueVisible = ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen()
                || hasOptions || continuePrompt;
        if (incantationAwaitingTransition && (ctx.dialogues().isLoading()
                || !incantationDialogue(ctx, options))) {
            incantationAwaitingTransition = false;
            incantationIndex = Math.min(5, incantationIndex + 1);
            logger.accept("Demon Slayer: incantation menu changed; word "
                    + incantationIndex + "/5 confirmed");
            nextActionAt = System.currentTimeMillis() + dialogueDelay();
            return true;
        }
        if (prysinHandoffActive && PRYSIN.contains(ctx.localPlayer().getLocation())) {
            if (dialogueVisible) prysinHandoffDialogueSeen = true;
            else if (prysinHandoffDialogueSeen) {
                prysinHandoffDialogueSeen = false;
                schedulePrysinHandoffWait("dialogue closed");
            }
        }
        if (!dialogueVisible) {
            dialogueExitAttempts = 0;
            unverifiedDialogueSnapshot = "";
            unverifiedDialogueSince = 0L;
            unverifiedDialogueRecoveryAttempts = 0;
            return false;
        }
        Tile dialogueLocation = ctx.localPlayer().getLocation();
        if (stage == 2 && (inDelrithRitual(dialogueLocation)
                || clean(ctx.dialogues().getText()).contains("incantation"))) {
            String text = clean(ctx.dialogues().getText());
            if ((text.contains("wrong incantation")
                    || text.contains("now what was that incantation again"))
                    && incantationIndex != 0) {
                logger.accept("Demon Slayer: incantation restarted after ritual prompt; index "
                        + incantationIndex + " -> 0");
                incantationIndex = 0;
                incantationAwaitingTransition = false;
            }
            if (!text.isBlank() && !text.equals(ritualDialogueText)) {
                ritualDialogueText = text;
                long delay = ThreadLocalRandom.current().nextLong(2800, 5501);
                ritualDialogueWaitUntil = System.currentTimeMillis() + delay;
                logger.accept("Demon Slayer: ritual dialogue recognized; waiting " + delay
                        + " ms before responding: " + text);
            }
            if (System.currentTimeMillis() < ritualDialogueWaitUntil) {
                nextActionAt = System.currentTimeMillis() + 600L;
                return true;
            }
        }
        if (stage == 2 && incantationIndex >= WORD_VARBITS.length
                && (ctx.dialogues().canContinue() || continuePrompt)) {
            // After the fifth word, the remaining quest dialogue may occur in
            // another instance coordinate. Keep it eligible for Continue.
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
        }
        if (System.currentTimeMillis() > expectedDialogueUntil
                && !incantationDialogue(ctx, options)
                && !recognizableQuestDialogue(ctx, stage, options)) {
            String unverifiedText = clean(ctx.dialogues().getText());
            if (stage == 2 && unverifiedText.contains("now what was that incantation again")
                    && (ctx.dialogues().canContinue() || continuePrompt)) {
                if (unverifiedIncantationWaitUntil == 0L) {
                    long delay = ThreadLocalRandom.current().nextLong(2800, 5501);
                    unverifiedIncantationWaitUntil = System.currentTimeMillis() + delay;
                    logger.accept("Demon Slayer: unverified incantation prompt; waiting "
                            + delay + " ms before Continue");
                }
                if (System.currentTimeMillis() < unverifiedIncantationWaitUntil) {
                    nextActionAt = System.currentTimeMillis() + 400L;
                    return true;
                }
                if (ctx.dialogues().selectContinue() || clickContinuePrompt(options)) {
                    logger.accept("Demon Slayer: clicked Continue on incantation prompt");
                    unverifiedIncantationWaitUntil = 0L;
                    expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                    nextActionAt = System.currentTimeMillis() + dialogueDelay();
                } else {
                    nextActionAt = System.currentTimeMillis() + 1200L;
                }
                return true;
            }
            if (ctx.dialogues().canContinue() || hasOptions) {
                return waitOrDismissUnverifiedDialogue(ctx, stage, options);
            }
            return false;
        }
        unverifiedDialogueSnapshot = "";
        unverifiedDialogueSince = 0L;
        unverifiedDialogueRecoveryAttempts = 0;
        // The incantation menu can coexist with a stale continue prompt. Never let
        // the generic dialogue handler send a key while the five words are visible.
        if (incantationDialogue(ctx, options)) {
            if (!validIncantationOrder(ctx)) return true;
            if (incantationIndex == 0 && !incantationAwaitingTransition) {
                StringBuilder order = new StringBuilder();
                for (int varbit : WORD_VARBITS) {
                    if (order.length() > 0) order.append(" -> ");
                    order.append(WORDS[ctx.vars().getVarbit(varbit)]);
                }
                logger.accept("Demon Slayer: incantation order from quest varbits: " + order);
            }
            if (incantationAwaitingTransition) {
                if (System.currentTimeMillis() - incantationClickedAt < 5500L) {
                    status("waiting for incantation menu to change after word "
                            + (incantationIndex + 1));
                    nextActionAt = System.currentTimeMillis() + 450L;
                    return true;
                }
                logger.accept("Demon Slayer: incantation menu did not change after click; retrying word "
                        + (incantationIndex + 1));
                incantationAwaitingTransition = false;
            }
            if (incantationIndex >= WORD_VARBITS.length) {
                status("five incantation words selected; waiting for quest state");
                nextActionAt = System.currentTimeMillis() + 1200L;
                return true;
            }
            int word = ctx.vars().getVarbit(WORD_VARBITS[Math.min(incantationIndex, 4)]);
            if (word < 0 || word >= WORDS.length) {
                status("incantation varbit invalid: " + word);
                return true;
            }
            int selecting = incantationIndex + 1;
            if (selectIncantationWord(ctx, options, WORDS[word])) {
                logger.accept("Demon Slayer: incantation word " + selecting
                        + "/5 clicked: " + WORDS[word] + "; verifying menu transition");
            }
            return true;
        }
        if (stage == 2 && incantationIndex > 0
                && incantationIndex < WORD_VARBITS.length) {
            // A short gap between word menus must not fall through to the generic
            // dialogue handler, which may send a numeric key to the wrong widget.
            status("waiting for next incantation menu; word " + incantationIndex + "/5 selected");
            nextActionAt = System.currentTimeMillis() + 500L;
            return true;
        }
        if (ctx.dialogues().canContinue() || continuePrompt) {
            if (!permit("dialogue:continue", clean(ctx.dialogues().getText()), 1000L, 3)) return true;
            boolean keyboard = ++dialogueActions % 2 == 0;
            if (keyboard) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                logger.accept("Demon Slayer: continued dialogue by keyboard");
            } else {
                ctx.mouse().moveRandomly(35, 85);
                Time.sleep(180, 420);
                if (!ctx.dialogues().selectContinue() && !clickContinuePrompt(options))
                    ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
                logger.accept("Demon Slayer: continued dialogue by mouse/API");
            }
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            nextActionAt = System.currentTimeMillis() + dialogueDelay();
            return true;
        }
        if (!hasOptions) return false;
        if (stage == 2 && isPrysinInformationMenu(ctx, options)) {
            exitPrysinDialogue(ctx);
            return true;
        }
        if (stage == 2 && inDelrithRitual(dialogueLocation)
                && containsIncantationWord(options)) {
            status("incantation choices still loading; waiting for complete menu");
            nextActionAt = System.currentTimeMillis() + 850L;
            return true;
        }
        String[] wanted = switch (stage) {
            case 0 -> new String[]{"the demon slayer quest", "yes", "ok, here you go", "i'll kill him", "wally kill"};
            case 1 -> new String[]{"ok thanks. i'll do my best to stop the demon",
                    "what is the magical incantation", "aris said", "silverlight",
                    "he's back", "give me the keys", "give me your key"};
            default -> new String[]{"can you give me your key", "demon slayer", "sir prysin", "demon who wants", "yes, very",
                    "it's me", "give me the key", "one of the keys", "a key given", "keys knocking",
                    "bones", "silverlight", "yes"};
        };
        for (String phrase : wanted) if (clickOption(ctx, options, phrase)) return true;
        if (hasOptions && options.size() == 1 && chooseOption(ctx, options, 0)) {
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            nextActionAt = System.currentTimeMillis() + dialogueDelay();
            return true;
        }
        status("dialogue has no safe matching option: " + optionLabels(options));
        nextActionAt = System.currentTimeMillis() + 3000L;
        return true;
    }

    private boolean waitOrDismissUnverifiedDialogue(APIContext ctx, int stage, List<WidgetChild> options) {
        String snapshot = stage + ":" + clean(ctx.dialogues().getText()) + ":"
                + optionLabels(options == null ? List.of() : options);
        long now = System.currentTimeMillis();
        if (!snapshot.equals(unverifiedDialogueSnapshot)) {
            unverifiedDialogueSnapshot = snapshot;
            unverifiedDialogueSince = now;
            unverifiedDialogueRecoveryAttempts = 0;
        }
        if (now - unverifiedDialogueSince < 10000L) {
            status("unverified dialogue open; waiting for quest state: " + snapshot);
            nextActionAt = now + 1200L;
            return true;
        }
        if (unverifiedDialogueRecoveryAttempts++ < 2) {
            logger.accept("Demon Slayer: closing unchanged unverified dialogue before retrying quest stage " + stage);
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            nextActionAt = now + 1000L;
            return true;
        }
        status("unverified dialogue persisted; retrying current quest stage " + stage);
        return false;
    }

    private boolean recognizableQuestDialogue(APIContext ctx, int stage, List<WidgetChild> options) {
        if (stage < 0 || stage > 2) return false;
        String dialogueText = clean(ctx.dialogues().getText());
        Tile location = ctx.localPlayer().getLocation();
        if (stage == 2 && inDelrithRitual(location)
                && (dialogueText.contains("delrith") || dialogueText.contains("demon")
                || ctx.dialogues().canContinue() || hasContinuePrompt(options))) {
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            return true;
        }
        if (stage == 2 && ctx.vars().getVarbit(VarbitID.DELRITH_DRAIN_KEY) > 0
                && dialogueText.contains("washed the key down into the sewer")) {
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            logger.accept("Demon Slayer: resuming drain result dialogue from quest varbit and text");
            return true;
        }
        if (stage == 2 && isPrysinInformationMenu(ctx, options)) {
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            return true;
        }
        if (options != null) for (WidgetChild option : options) {
            if (option == null) continue;
            String label = clean(option.getText());
            if (label.contains("stop the demon") || label.contains("magical incantation")
                    || label.contains("where can i find silverlight")
                    || label.contains("the demon slayer quest")
                    || label.contains("aris said i should come")
                    || label.contains("talk about demon slayer")
                    || label.contains("key given to you by sir prysin")
                    || (stage == 2 && label.contains("can you give me your key"))) {
                logger.accept("Demon Slayer: resuming verified quest dialogue after script restart: " + label);
                expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                return true;
            }
        }
        Tile me = ctx.localPlayer().getLocation();
        if (options != null && !options.isEmpty() && !onlyContinuationWidgets(ctx, options)) return false;
        if ((!ctx.dialogues().canContinue() && !hasContinuePrompt(options)) || me == null) return false;
        boolean nearQuestNpc = (stage <= 1 && ARIS.contains(me))
                || (stage >= 1 && PRYSIN.contains(me))
                || (stage == 2 && (ROVIN.contains(me) || TRAIBORN.contains(me)))
                || (stage == 2 && ctx.vars().getVarbit(VarbitID.DELRITH_DRAIN_KEY) > 0
                && near(me, DRAIN, 5))
                || (stage == 2 && SEWER.contains(me) && near(me, SEWER_KEY, 7))
                || (stage == 2 && ctx.equipment().contains("Silverlight")
                && near(me, DELRITH, 9));
        if (nearQuestNpc) {
            logger.accept("Demon Slayer: resuming continuation near quest NPC after restart");
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
        }
        return nearQuestNpc;
    }

    private boolean hasContinuePrompt(List<WidgetChild> options) {
        if (options == null) return false;
        for (WidgetChild option : options)
            if (option != null && clean(option.getText()).contains("click here to continue")) return true;
        return false;
    }

    private boolean clickContinuePrompt(List<WidgetChild> options) {
        if (options == null) return false;
        for (WidgetChild option : options)
            if (option != null && clean(option.getText()).contains("click here to continue")
                    && option.click(false)) return true;
        return false;
    }

    private boolean onlyContinuationWidgets(APIContext ctx, List<WidgetChild> options) {
        String body = clean(ctx.dialogues().getText());
        if (options == null || options.isEmpty()) return true;
        for (WidgetChild option : options) {
            String label = option == null ? "" : clean(option.getText());
            if (!label.isBlank() && !label.contains("click here to continue")
                    && !label.equals(body)) return false;
        }
        return true;
    }

    private boolean isPrysinInformationMenu(APIContext ctx, List<WidgetChild> options) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null || !PRYSIN.contains(me) || options == null || options.isEmpty()) return false;
        boolean hasDrainQuestion = false;
        for (WidgetChild option : options) {
            String label = option == null ? "" : clean(option.getText());
            if (label.contains("can you give me your key") || label.contains("give me the keys")) return false;
            if (label.contains("so what does the drain lead to")) hasDrainQuestion = true;
            else if (!label.contains("where can i find captain rovin")
                    && !label.contains("where does the wizard live")) return false;
        }
        return hasDrainQuestion;
    }

    private void exitPrysinDialogue(APIContext ctx) {
        if (++dialogueExitAttempts > 3) {
            status("Prysin informational dialogue did not close after three attempts");
            nextActionAt = System.currentTimeMillis() + 8000L;
            return;
        }
        logger.accept("Demon Slayer: leaving Prysin informational dialogue, attempt "
                + dialogueExitAttempts + "/3");
        if (dialogueExitAttempts == 1) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
        else if (dialogueExitAttempts == 2) ctx.widgets().closeInterface();
        else ctx.walking().walkOnMap(new Tile(3205, 3477, 0));
        nextActionAt = System.currentTimeMillis() + dialogueDelay();
    }

    private boolean incantationDialogue(APIContext ctx, List<WidgetChild> options) {
        if (options == null || options.size() < 5 || stage(ctx) != 2) return false;
        int matches = 0;
        for (WidgetChild option : options) for (String word : WORDS)
            if (option != null && clean(option.getText()).contains(word.toLowerCase(Locale.ROOT))) { matches++; break; }
        return matches >= 4;
    }

    private boolean containsIncantationWord(List<WidgetChild> options) {
        if (options == null) return false;
        for (WidgetChild option : options) for (String word : WORDS)
            if (option != null && clean(option.getText()).equals(word.toLowerCase(Locale.ROOT)))
                return true;
        return false;
    }

    private boolean validIncantationOrder(APIContext ctx) {
        int seen = 0;
        for (int varbit : WORD_VARBITS) {
            int value = ctx.vars().getVarbit(varbit);
            if (value < 0 || value >= WORDS.length || (seen & (1 << value)) != 0) {
                status("incantation varbits are incomplete or duplicated; refusing to guess");
                nextActionAt = System.currentTimeMillis() + 1800L;
                return false;
            }
            seen |= 1 << value;
        }
        return true;
    }

    private boolean selectIncantationWord(APIContext ctx, List<WidgetChild> options, String word) {
        for (int index = 0; index < options.size(); index++) {
            WidgetChild option = options.get(index);
            if (option == null || !clean(option.getText()).equals(word.toLowerCase(Locale.ROOT))) continue;
            if (!clickIncantationOption(ctx, option, word)) {
                status("incantation option " + word + " not confirmed; keeping word index "
                        + (incantationIndex + 1));
                nextActionAt = System.currentTimeMillis() + 900L;
                return false;
            }
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            incantationAwaitingTransition = true;
            incantationClickedAt = System.currentTimeMillis();
            Time.sleep(150, 2400, () -> ctx.dialogues().isLoading()
                    || !incantationDialogue(ctx, ctx.dialogues().getOptions()), 50);
            if (ctx.dialogues().isLoading()
                    || !incantationDialogue(ctx, ctx.dialogues().getOptions())) {
                incantationAwaitingTransition = false;
                incantationIndex = Math.min(5, incantationIndex + 1);
                logger.accept("Demon Slayer: incantation menu transition observed; word "
                        + incantationIndex + "/5 confirmed");
            }
            nextActionAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(850, 1501);
            return true;
        }
        status("incantation word " + word + " missing from current menu");
        nextActionAt = System.currentTimeMillis() + 900L;
        return false;
    }

    private boolean clickIncantationOption(APIContext ctx, WidgetChild option, String word) {
        java.awt.Rectangle bounds = option.getBounds();
        if (bounds == null || bounds.width < 9 || bounds.height < 9) return false;
        int x = ThreadLocalRandom.current().nextInt(bounds.x + 3, bounds.x + bounds.width - 3);
        int y = ThreadLocalRandom.current().nextInt(bounds.y + 3, bounds.y + bounds.height - 3);
        Point point = new Point(x, y);
        ctx.mouse().move(point);
        Time.sleep(220, 480);
        if (!word.equalsIgnoreCase(clean(option.getText()))) return false;
        return ctx.mouse().click(point, false);
    }

    private boolean clickOption(APIContext ctx, List<WidgetChild> options, String wanted) {
        for (int index = 0; index < options.size(); index++) {
            WidgetChild option = options.get(index);
            if (option == null) continue;
            String label = clean(option.getText());
            if (!label.contains(wanted.toLowerCase(Locale.ROOT))) continue;
            if (chooseOption(ctx, options, index)) {
                logger.accept("Demon Slayer: dialogue option " + label);
                expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                nextActionAt = System.currentTimeMillis() + dialogueDelay();
                return true;
            }
            // A matched option is cooling down or stalled; do not choose a different answer.
            nextActionAt = System.currentTimeMillis() + 1200L;
            return true;
        }
        return false;
    }

    private boolean chooseOption(APIContext ctx, List<WidgetChild> options, int index) {
        if (index < 0 || index >= options.size() || options.get(index) == null) return false;
        WidgetChild option = options.get(index);
        String expected = clean(option.getText());
        if (expected.isBlank()) return false;
        if (!permit("dialogue:option:" + expected,
                stage(ctx) + ":" + clean(ctx.dialogues().getText()) + ":" + optionLabels(options),
                1200L, 3)) return false;
        boolean keyboard = ++dialogueActions % 3 == 0 && index < 9;
        if (keyboard) {
            // A numeric key selects the currently visible option; verify the same list first.
            List<WidgetChild> current = ctx.dialogues().getOptions();
            if (current != null && current.size() == options.size()
                    && expected.equals(clean(current.get(index).getText()))) {
                ctx.mouse().moveRandomly(25, 75);
                Time.sleep(240, 520);
                ctx.keyboard().sendKey(KeyEvent.VK_1 + index);
                logger.accept("Demon Slayer: selected dialogue option " + (index + 1) + " by keyboard");
                return true;
            }
        }
        java.awt.Rectangle bounds = option.getBounds();
        if (bounds != null && bounds.width > 8 && bounds.height > 8) {
            int marginX = Math.min(8, bounds.width / 4);
            int marginY = Math.min(5, bounds.height / 4);
            Point point = new Point(ThreadLocalRandom.current().nextInt(bounds.x + marginX,
                    bounds.x + bounds.width - marginX), ThreadLocalRandom.current().nextInt(bounds.y + marginY,
                    bounds.y + bounds.height - marginY));
            ctx.mouse().move(point);
            Time.sleep(220, 480);
            if (expected.equals(clean(option.getText())) && ctx.mouse().click(point, false)) {
                logger.accept("Demon Slayer: selected dialogue option by mouse");
                return true;
            }
        }
        return option.click(false);
    }

    private long dialogueDelay() {
        return System.currentTimeMillis() < traibornDialogueUntil
                ? ThreadLocalRandom.current().nextLong(2800, 4801)
                : ThreadLocalRandom.current().nextLong(1100, 2301);
    }
    private boolean inDelrithRitual(Tile location) {
        if (location == null) return false;
        // The ritual instance can place the player beyond the original small
        // rectangle (observed at 12422,4107 while the incantation was open).
        boolean summoningScene = location.getX() >= 12000 && location.getX() <= 12500
                && location.getY() >= 3700 && location.getY() <= 4300;
        boolean fightScene = location.getX() >= 10300 && location.getX() <= 10700
                && location.getY() >= 3900 && location.getY() <= 4300;
        return summoningScene || fightScene;
    }
    private void schedulePrysinHandoffWait(String reason) {
        long delay = ThreadLocalRandom.current().nextLong(9000, 16001);
        prysinHandoffWaitUntil = System.currentTimeMillis() + delay;
        logger.accept("Demon Slayer: " + reason + "; waiting " + delay
                + " ms for key handoff before another Prysin click");
    }
    private String clean(String text) { return text == null ? "" : text.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT); }
    private String optionLabels(List<WidgetChild> options) {
        StringBuilder out = new StringBuilder();
        for (WidgetChild option : options) if (option != null) out.append('[').append(clean(option.getText())).append(']');
        return out.toString();
    }

    private void rovinKey(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (me.getPlane() == 2 && ROVIN.contains(me)) {
            talk(ctx, NpcID.CAPTAIN_ROVIN, ROVIN, "Captain Rovin");
        } else if (me.getPlane() == 1 && near(me, new Tile(3203, 3498, 1), 12)) {
            object(ctx, ObjectID.VARROCK_SPIRALSTAIRS_MIDDLE_TALLER,
                    new Tile(3203, 3498, 1), "Climb-up", "castle stairs to Rovin");
        } else if (me.getPlane() == 0) {
            object(ctx, ObjectID.VARROCK_SPIRALSTAIRS_TALLER,
                    CASTLE_STAIRS, "Climb-up", "castle stairs to Rovin");
        } else leaveInterior(ctx);
    }

    private void prysinKey(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (SEWER.contains(me)) {
            object(ctx, ObjectID.QIP_DS_SEWER_KEY, SEWER_KEY, "Take", "rusty sewer key");
            return;
        }
        if (me.getPlane() > 0) { leaveInterior(ctx); return; }
        if (ctx.vars().getVarbit(VarbitID.DELRITH_DRAIN_KEY) > 0) {
            object(ctx, ObjectID.MANHOLEOPEN, MANHOLE, "Climb-down", "Varrock sewer manhole",
                    ObjectID.MANHOLECLOSED);
            return;
        }
        if (!ctx.inventory().contains(ItemID.BUCKET_WATER)) {
            if (prepareSupply(ctx, "Bucket of water", 1, 150)) return;
        }
        if (me.getX() < DRAIN.getX() || !near(me, DRAIN_OUTSIDE_STAND, 2)) {
            if (clearSelectedItem(ctx, "travel to castle drain")) return;
            if (!openRoute(ctx, DRAIN_OUTSIDE_STAND, "outside castle drain"))
                walk(ctx, DRAIN_OUTSIDE_STAND, "outside castle drain");
            return;
        }
        SceneObject drain = findDrain(ctx);
        if (drain == null || !drain.isValid()) {
            if (clearSelectedItem(ctx, "locate drain")) return;
            if (++drainSearchFailures <= 3) {
                logger.accept("Demon Slayer: drain not visible at " + me
                        + "; moving outside to " + DRAIN_OUTSIDE_STAND
                        + " (attempt " + drainSearchFailures + "/3)");
                ctx.walking().walkToOnScreen(DRAIN_OUTSIDE_STAND);
                nextActionAt = System.currentTimeMillis() + 1400L;
            } else {
                status("drain object not visible outside castle; waiting for route recovery");
                nextActionAt = System.currentTimeMillis() + 15_000L;
            }
            return;
        }
        drainSearchFailures = 0;
        if (!near(me, drain.getLocation(), 3)) {
            if (clearSelectedItem(ctx, "approach visible drain")) return;
            ctx.walking().walkToOnScreen(DRAIN_OUTSIDE_STAND);
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        if (ctx.inventory().getSelectedItemId() != ItemID.BUCKET_WATER) {
            selectWaterBucket(ctx);
            return;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            nextActionAt = System.currentTimeMillis() + 350L;
            return;
        }
        if (System.currentTimeMillis() - lastDrainAttemptAt < 2200L) return;
        lastDrainAttemptAt = System.currentTimeMillis();
        boolean clicked = ctx.mouse().click(drain, false);
        logger.accept("Demon Slayer: used selected bucket on visible drain id=" + drain.getId()
                + " at " + drain.getLocation() + " click=" + clicked);
        if (clicked) expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
        nextActionAt = System.currentTimeMillis() + 1600L;
    }

    private SceneObject findDrain(APIContext ctx) {
        SceneObject object = ctx.objects().query().id(ObjectID.QIP_DS_QUESTDRAIN_KEY,
                ObjectID.QIP_DS_QUESTDRAIN_NOKEY).results().nearest();
        if (object != null && object.isValid() && near(object.getLocation(), DRAIN, 3)) return object;
        Area aroundDrain = new Area(0, DRAIN.getX() - 3, DRAIN.getY() - 3,
                DRAIN.getX() + 3, DRAIN.getY() + 3);
        object = ctx.objects().query().nameContains("Drain").within(aroundDrain).results().nearest();
        return object != null && object.isValid() ? object : null;
    }

    private void selectWaterBucket(APIContext ctx) {
        if (!permit("item:select-bucket", ctx.localPlayer().getLocation() + ":"
                + ctx.inventory().getSelectedItemId() + ":" + ctx.menu().isOpen(),
                800L, 3)) return;
        if (ctx.menu().isOpen()) {
            if (ctx.menu().contains("Use", "Bucket of water")
                    && ctx.menu().interact("Use", "Bucket of water", true)) {
                logger.accept("Demon Slayer: chose Use on bucket menu");
                nextActionAt = System.currentTimeMillis() + 600L;
                return;
            }
            ctx.menu().closeMenu();
            nextActionAt = System.currentTimeMillis() + 350L;
            return;
        }
        boolean requested = ctx.inventory().selectItem(ItemID.BUCKET_WATER);
        Time.sleep(250, 600, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen() && ctx.menu().contains("Use", "Bucket of water")) {
            boolean used = ctx.menu().interact("Use", "Bucket of water", true);
            logger.accept("Demon Slayer: inventory selection opened menu; chose Use=" + used);
        } else {
            logger.accept("Demon Slayer: bucket selection requested=" + requested
                    + " selected=" + ctx.inventory().isItemSelected());
        }
        nextActionAt = System.currentTimeMillis() + 650L;
    }

    private void traibornKey(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (SEWER.contains(me) && me.getY() > 9000) {
            object(ctx, ObjectID.FAI_VARROCK_MANHOLE_LADDER, SEWER_LADDER,
                    "Climb-up", "leave Varrock sewer");
            return;
        }
        if (usableBones(ctx) < 25) {
            if (me.getPlane() > 0 && tryLumbridgeTeleportForBones(ctx, me, System.currentTimeMillis())) return;
            if (me.getPlane() > 0 && !TRAIBORN.contains(me)) { leaveInterior(ctx); return; }
            if (TRAIBORN.contains(me)) { leaveInterior(ctx); return; }
            if (prepareSupply(ctx, "Bones", 25, 0)) return;
        }
        if (me.getPlane() == 0) {
            object(ctx, ObjectID.FAI_WIZTOWER_SPIRALSTAIRS, WIZARD_STAIRS,
                    "Climb-up", "Wizards' Tower stairs");
            return;
        }
        talk(ctx, NpcID.TRAIBORN, TRAIBORN, "Wizard Traiborn");
    }

    private boolean prepareSupply(APIContext ctx, String name, int count, int guidePrice) {
        int carried = name.equals("Bones") ? usableBones(ctx) : ctx.inventory().getCount(name);
        if (carried >= count) return false;
        if (name.equals("Bones")) {
            if (ctx.bank().isOpen()) ctx.bank().close();
            collectBones(ctx);
            return true;
        }
        if (!bankChecked || ctx.bank().isOpen()) {
            if (!ctx.bank().isOpen()) { approachBank(ctx, name); return true; }
            if (name.equals("Bones") && !inventoryPreparedForBones) {
                boolean deposited = ctx.bank().depositAllExcept(item -> item != null
                        && (item.getId() == ItemID.SILVERLIGHT_KEY_2
                        || item.getId() == ItemID.SILVERLIGHT_KEY_3
                        || item.getId() == ItemID.BONES
                        || "Coins".equalsIgnoreCase(item.getName())));
                logger.accept("Demon Slayer: freed 25 bone slots; deposited non-key items=" + deposited);
                inventoryPreparedForBones = true;
                nextActionAt = System.currentTimeMillis() + 850L;
                return true;
            }
            if (!inventoryCleaned) {
                ctx.bank().depositAllExcept(item -> item != null && compatible(item.getName()));
                logger.accept("Demon Slayer: banked unrelated inventory items");
                inventoryCleaned = true;
                nextActionAt = System.currentTimeMillis() + 850L;
                return true;
            }
            if (name.equals("Bones") && ctx.inventory().getCount(true,
                    item -> item != null && "Bones".equalsIgnoreCase(item.getName()) && noted(item)) > 0) {
                ctx.bank().depositAll(item -> item != null && "Bones".equalsIgnoreCase(item.getName()) && noted(item));
                nextActionAt = System.currentTimeMillis() + 800L;
                return true;
            }
            if (name.equals("Bones") && ctx.inventory().getEmptySlotCount() < count - carried) {
                status("cannot free all 25 bone slots without depositing quest items; inspect inventory");
                nextActionAt = System.currentTimeMillis() + 30_000L;
                return true;
            }
            int available = ctx.bank().getCount(name);
            if (available > 0) {
                if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
                    ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
                    nextActionAt = System.currentTimeMillis() + 700L;
                    return true;
                }
                int quantity = Math.min(Math.min(available, count - carried), ctx.inventory().getEmptySlotCount());
                if (quantity > 0) ctx.bank().withdraw(quantity, name);
                else status("no free inventory slot for " + name);
                nextActionAt = System.currentTimeMillis() + 900L;
                return true;
            }
            bankChecked = true;
            ctx.bank().close();
            nextActionAt = System.currentTimeMillis() + 600L;
            return true;
        }
        if (name.equals("Bones")) {
            collectBones(ctx);
            return true;
        }
        pendingBuy = name;
        buyQuantity = count - carried;
        buyTarget = count;
        buyPrice = GePricing.quickBuyPrice(ctx, name, guidePrice);
        buy(ctx);
        return true;
    }

    private void collectBones(APIContext ctx) {
        long now = System.currentTimeMillis();
        int count = usableBones(ctx);
        if (count >= 25) return;
        if (count != lastBoneCount) {
            if (lastBoneCount >= 0 && count > lastBoneCount)
                logger.accept("Demon Slayer: collected bones " + count + "/25");
            if (lastBoneCount >= 0 && count > lastBoneCount && boneLootUntil > 0)
                clearBoneTarget();
            lastBoneCount = count;
            if (lastBonePickupAt > 0) lastBonePickupAt = now;
        }
        if (ctx.inventory().contains("Egg")
                && !ctx.localPlayer().isInCombat() && !ctx.localPlayer().isAttacking()) {
            boolean dropped = ctx.inventory().interactItem("Drop", "Egg");
            logger.accept("Demon Slayer: removed accidental Egg while gathering only Bones=" + dropped);
            nextActionAt = now + 900L;
            return;
        }
        if (ctx.inventory().getEmptySlotCount() < 25 - count) {
            if (ctx.localPlayer().isInCombat() || ctx.localPlayer().isAttacking()) {
                status("finishing combat before freeing Bone slots");
                nextActionAt = now + 850L;
                return;
            }
            if (ctx.inventory().contains("Egg")) {
                boolean dropped = ctx.inventory().interactItem("Drop", "Egg");
                logger.accept("Demon Slayer: dropping accidental Egg to reserve 25 Bone slots=" + dropped);
                nextActionAt = now + 900L;
                return;
            }
            status("need " + (25 - count) + " free slots for Bones, have "
                    + ctx.inventory().getEmptySlotCount() + "; collection paused, no bank trip");
            nextActionAt = now + 15_000L;
            return;
        }
        if (boneSpotIndex >= BONE_SPOTS.length) {
            status("no progress collecting bones at chicken or goblin spots; collection paused");
            nextActionAt = now + 30_000L;
            return;
        }
        MobDefinition spot = BONE_SPOTS[boneSpotIndex];
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (!spot.area().contains(me)) {
            lastBonePickupAt = 0;
            if (tryLumbridgeTeleportForBones(ctx, me, now)) return;
            walk(ctx, BONE_DESTINATIONS[boneSpotIndex], "bone spot " + spot.key());
            return;
        }
        if (lastBonePickupAt == 0) {
            lastBonePickupAt = now;
            logger.accept("Demon Slayer: arrived at " + spot.key() + " at " + me
                    + "; looking for normal Bones and attackable " + spot.primaryName());
        }
        if (boneTarget == null && boneLootUntil == 0 && now - lastBonePickupAt > 120_000L) {
            boneSpotIndex++;
            lastBonePickupAt = 0;
            logger.accept("Demon Slayer: no bone progress at " + spot.key()
                    + "; switching to " + (boneSpotIndex < BONE_SPOTS.length
                    ? BONE_SPOTS[boneSpotIndex].key() : "paused collection"));
            return;
        }
        if (clearSelectedItem(ctx, "collect bones")) return;
        if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
        if (handleBoneCombatAndLoot(ctx, spot, count, now)) return;
        NPC target = ctx.npcs().query().named(spot.primaryName()).actions("Attack")
                .notInCombat().within(spot.area()).reachable().results().nearest();
        if (target != null && target.isValid()) {
            boneTarget = target;
            boneTargetTile = target.getLocation();
            boneTargetStartedAt = now;
            boneAttackAttempts = 1;
            boolean attacked = target.interact("Attack");
            logger.accept("Demon Slayer: selected " + spot.primaryName() + " at "
                    + boneTargetTile + "; attack=" + attacked);
            nextActionAt = now + 1500L;
            return;
        }
        NPC blocked = ctx.npcs().query().named(spot.primaryName()).actions("Attack")
                .notInCombat().within(spot.area()).results().nearest();
        if (blocked != null && blocked.isValid() && blocked.getLocation() != null) {
            status("visible " + spot.primaryName() + " not reachable at " + blocked.getLocation());
            if (openRoute(ctx, blocked.getLocation(), "bone target " + spot.key())) return;
            if (blocked.tileDistanceTo(ctx) <= 12)
                ctx.walking().walkOnMap(blocked.getLocation());
            nextActionAt = now + 1800L;
            return;
        }
        status("waiting for " + spot.primaryName() + " or normal Bones at " + spot.key()
                + "; bones " + count + "/25");
        nextActionAt = now + 1800L;
    }

    private boolean handleBoneCombatAndLoot(APIContext ctx, MobDefinition spot, int count, long now) {
        boolean fighting = ctx.localPlayer().isInCombat() || ctx.localPlayer().isAttacking();
        if (boneLootUntil > 0) {
            if (fighting) {
                nextActionAt = now + 800L;
                return true;
            }
            if (now >= boneLootUntil) {
                logger.accept("Demon Slayer: no normal Bones found near defeated "
                        + spot.primaryName() + " at " + boneTargetTile + "; next target");
                clearBoneTarget();
                return true;
            }
            for (GroundItem bones : ctx.groundItems().query().named("Bones")
                    .within(spot.area()).results().nearestList()) {
                if (bones == null || !bones.isValid() || bones.getId() != ItemID.BONES
                        || !near(bones.getLocation(), boneTargetTile, 3)
                        || bones.tileDistanceTo(ctx) > 12) continue;
                if (!permit("bones:take:" + bones.getLocation(), count + ":"
                        + bones.getLocation(), 1800L, 3)) return true;
                if (ctx.menu().isOpen()) ctx.menu().closeMenu();
                boolean opened = ctx.mouse().click(bones, true);
                Time.sleep(150, 350, () -> ctx.menu().isOpen(), 50);
                boolean exactBonesOption = ctx.menu().isOpen()
                        && ctx.menu().contains("Take", "Bones");
                boolean taken = exactBonesOption
                        && ctx.menu().interact("Take", "Bones", true);
                if (!taken && ctx.menu().isOpen()) ctx.menu().closeMenu();
                logger.accept("Demon Slayer: right-click Bones at " + bones.getLocation()
                        + " opened=" + opened + " exactOption=" + exactBonesOption
                        + " selected=" + taken);
                nextActionAt = now + 1300L;
                return true;
            }
            status("waiting for Bones from defeated " + spot.primaryName());
            nextActionAt = now + 850L;
            return true;
        }
        if (boneTarget == null) {
            if (fighting) {
                status("waiting for current combat before selecting bone target");
                nextActionAt = now + 850L;
                return true;
            }
            return false;
        }
        if (boneTarget.isValid() && boneTarget.getLocation() != null)
            boneTargetTile = boneTarget.getLocation();
        if (fighting) {
            boneTargetEngaged = true;
            boneCombatEndedAt = 0;
            status("finishing selected " + spot.primaryName() + "; bones " + count + "/25");
            nextActionAt = now + 850L;
            return true;
        }
        if (!boneTarget.isValid() || boneTarget.isDead()) {
            boneLootUntil = now + 9000L;
            logger.accept("Demon Slayer: selected " + spot.primaryName()
                    + " disappeared after attack; checking only Bones near " + boneTargetTile);
            return true;
        }
        if (boneTargetEngaged) {
            if (boneCombatEndedAt == 0) boneCombatEndedAt = now;
            if (!boneTarget.isValid() || boneTarget.isDead() || now - boneCombatEndedAt > 1800L) {
                boneLootUntil = now + 9000L;
                logger.accept("Demon Slayer: selected " + spot.primaryName()
                        + " combat ended; collecting only Bones near " + boneTargetTile);
                return true;
            }
            nextActionAt = now + 450L;
            return true;
        }
        if (now - boneTargetStartedAt < 4500L) {
            nextActionAt = now + 850L;
            return true;
        }
        if (boneTarget.isValid() && boneAttackAttempts < 3) {
            boneAttackAttempts++;
            boolean attacked = boneTarget.interact("Attack");
            logger.accept("Demon Slayer: retry same " + spot.primaryName() + " attack "
                    + boneAttackAttempts + "/3=" + attacked);
            boneTargetStartedAt = now;
            nextActionAt = now + 1300L;
            return true;
        }
        logger.accept("Demon Slayer: selected " + spot.primaryName()
                + " never entered combat; releasing target after bounded retries");
        clearBoneTarget();
        return true;
    }

    private void clearBoneTarget() {
        boneTarget = null;
        boneTargetTile = null;
        boneTargetEngaged = false;
        boneTargetStartedAt = 0;
        boneCombatEndedAt = 0;
        boneLootUntil = 0;
        boneAttackAttempts = 0;
    }

    private boolean tryLumbridgeTeleportForBones(APIContext ctx, Tile me, long now) {
        if (Navigation.isInLumbridgeRegion(ctx)) return false;
        if (lumbridgeTeleportStartedAt > 0) {
            if (now - lumbridgeTeleportStartedAt < 13_000L) {
                status("waiting for Lumbridge teleport to finish");
                nextActionAt = now + 1200L;
                return true;
            }
            logger.accept("Demon Slayer: Lumbridge teleport did not arrive; using walker");
            lumbridgeTeleportStartedAt = 0;
        }
        if (lumbridgeTeleportAttempted) return false;
        lumbridgeTeleportAttempted = true;
        Spell spell = ctx.magic().canCast(Spell.Modern.LUMBRIDGE_TELEPORT)
                ? Spell.Modern.LUMBRIDGE_TELEPORT
                : ctx.magic().canCast(Spell.Modern.HOME_TELEPORT)
                ? Spell.Modern.HOME_TELEPORT : null;
        if (spell == null) {
            logger.accept("Demon Slayer: Lumbridge teleport unavailable from " + me + "; using walker");
            return false;
        }
        boolean cast = ctx.magic().cast(spell);
        logger.accept("Demon Slayer: cast " + spell.getName() + " for bones=" + cast);
        if (!cast) return false;
        lumbridgeTeleportStartedAt = now;
        nextActionAt = now + 1600L;
        return true;
    }

    private boolean compatible(String name) {
        if (name == null) return false;
        if (name.equalsIgnoreCase("Coins") || name.equalsIgnoreCase("Bones")
                || name.equalsIgnoreCase("Bucket of water") || name.equalsIgnoreCase("Silverlight")
                || name.toLowerCase(Locale.ROOT).contains("silverlight key")) return true;
        for (String food : FOOD) if (name.equalsIgnoreCase(food)) return true;
        return false;
    }

    private void buy(APIContext ctx) {
        if (pendingBuy == null) { ctx.grandExchange().close(); return; }
        int carried = ctx.inventory().getCount(pendingBuy);
        if (carried >= buyTarget) {
            logger.accept("Demon Slayer: acquired " + pendingBuy);
            pendingBuy = null; offerPlacedAt = 0; collectionAttempts = 0; bankChecked = false;
            ctx.grandExchange().close();
            return;
        }
        if (!GE.contains(ctx.localPlayer().getLocation())) { walk(ctx, GE.getCentralTile(), "Grand Exchange"); return; }
        if (!ctx.grandExchange().isOpen()) {
            ctx.grandExchange().open();
            nextActionAt = System.currentTimeMillis() + 1000L;
            return;
        }
        if (confirmHighPrice(ctx)) return;
        IGrandExchangeAPI.GrandExchangeScreen screen = ctx.grandExchange().getCurrentScreen();
        GrandExchangeSlot slot = null;
        List<GrandExchangeSlot> slots = ctx.grandExchange().getSlots();
        if (slots != null) for (GrandExchangeSlot candidate : slots) {
            if (candidate == null || candidate.getOffer() == null) continue;
            String item = candidate.getOffer().getItemName();
            if (item != null && item.equalsIgnoreCase(pendingBuy)
                    && (candidate.getState() == GrandExchangeOffer.OfferState.BUYING
                    || candidate.getState() == GrandExchangeOffer.OfferState.BOUGHT
                    || candidate.getState() == GrandExchangeOffer.OfferState.CANCELLED_BUY)) {
                slot = candidate; break;
            }
        }
        if (slot != null && slot.canCollect() && System.currentTimeMillis() >= nextCollectAt) {
            boolean collected = collectionAttempts < 3 ? ctx.grandExchange().collectToInventory()
                    : ctx.grandExchange().collectToBank();
            logger.accept("Demon Slayer: GE collect " + pendingBuy + "=" + collected
                    + " attempts=" + (++collectionAttempts));
            nextCollectAt = System.currentTimeMillis() + 3000L;
            nextActionAt = System.currentTimeMillis() + 950L;
            if (collected && collectionAttempts > 3) {
                pendingBuy = null; bankChecked = false; offerPlacedAt = 0;
                ctx.grandExchange().close();
            }
            return;
        }
        if (slot != null && slot.inUse()) {
            status("waiting for GE purchase " + pendingBuy);
            nextActionAt = System.currentTimeMillis() + 1800L;
            return;
        }
        if (offerPlacedAt > 0) {
            status("verifying existing GE offer before retry");
            nextActionAt = System.currentTimeMillis() + 2500L;
            return;
        }
        if (screen != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview();
            nextActionAt = System.currentTimeMillis() + 900L;
            return;
        }
        if (ctx.grandExchange().placeBuyOffer(pendingBuy, buyQuantity, buyPrice)) {
            logger.accept("Demon Slayer: placed GE buy offer " + buyQuantity + "x " + pendingBuy);
            offerPlacedAt = System.currentTimeMillis();
        } else status("GE buy offer not confirmed: " + pendingBuy);
        nextActionAt = System.currentTimeMillis() + 1100L;
    }

    private boolean confirmHighPrice(APIContext ctx) {
        WidgetChild warning = null, yes = null;
        for (WidgetChild child : ctx.widgets().getAllChildren(w -> w != null && w.isValid()
                && w.getWidth() > 0 && w.getHeight() > 0)) {
            String text = clean(child.getText());
            if (text.contains("much higher than the guide price")) warning = child;
            if (text.equals("yes")) yes = child;
        }
        if (warning == null || yes == null) return false;
        Point point = yes.getCentralPoint();
        if (point != null && ctx.mouse().click(point, false)) {
            offerPlacedAt = System.currentTimeMillis();
            logger.accept("Demon Slayer: confirmed GE price warning");
        }
        nextActionAt = System.currentTimeMillis() + 900L;
        return true;
    }

    private void fight(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        if (!combatSupplied) {
            int food = 0;
            for (String type : FOOD) food += ctx.inventory().getCount(type);
            if (food < 4 && prepareSupply(ctx, "Trout", 6, 150)) return;
            combatSupplied = true;
        }
        if (!ctx.equipment().contains("Silverlight")) {
            if (ctx.inventory().contains(ItemID.SILVERLIGHT)) {
                ctx.inventory().interactItem("Wield", ItemID.SILVERLIGHT);
                nextActionAt = System.currentTimeMillis() + 800L;
                return;
            }
            talk(ctx, NpcID.SIR_PRYSIN, PRYSIN, "Sir Prysin for replacement Silverlight");
            return;
        }
        if (ctx.localPlayer().getHealthPercent() < 55 && eat(ctx)) return;
        if (ctx.localPlayer().getHealthPercent() < 35 && !hasFood(ctx)) {
            status("low HP without food; retreating to bank");
            walk(ctx, GE.getCentralTile(), "safe bank area");
            return;
        }
        NPC demon = ctx.npcs().query().id(NpcID.DELRITH_WEAKENED, NpcID.DELRITH).results().nearest();
        if (demon != null && demon.isValid() && demon.tileDistanceTo(ctx) <= 10) {
            if (demon.getId() == NpcID.DELRITH_WEAKENED) {
                expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                if (demon.interact("Banish") || demon.interact("Attack")) {
                    incantationIndex = 0;
                    nextActionAt = System.currentTimeMillis() + 950L;
                }
                return;
            }
            if (demon.interact("Attack")) {
                status("attacking Delrith with Silverlight");
                nextActionAt = System.currentTimeMillis() + 1400L;
            }
            return;
        }
        walk(ctx, DELRITH, "Delrith ritual circle");
    }

    private boolean hasFood(APIContext ctx) {
        for (String food : FOOD) if (ctx.inventory().contains(food)) return true;
        return false;
    }
    private boolean eat(APIContext ctx) {
        for (String food : FOOD) if (ctx.inventory().contains(food)
                && ctx.inventory().interactItem("Eat", food)) {
            logger.accept("Demon Slayer: ate " + food + " at HP " + ctx.localPlayer().getHealthPercent() + "%");
            nextActionAt = System.currentTimeMillis() + 700L;
            return true;
        }
        return false;
    }

    private boolean leaveInterior(APIContext ctx) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return false;
        if (SEWER.contains(me) && me.getY() > 9000) {
            object(ctx, ObjectID.FAI_VARROCK_MANHOLE_LADDER, SEWER_LADDER,
                    "Climb-up", "leave sewer");
            return true;
        }
        if (me.getPlane() > 0 && me.getX() >= 3195 && me.getX() <= 3225
                && me.getY() >= 3200 && me.getY() <= 3240) {
            leaveLumbridgeCastle(ctx, me);
            return true;
        }
        if (me.getPlane() == 2 && me.getX() >= 3195 && me.getX() <= 3230
                && me.getY() >= 3480 && me.getY() <= 3520) {
            object(ctx, ObjectID.VARROCK_SPIRALSTAIRSTOP,
                    new Tile(3203, 3498, 2), "Climb-down", "descend castle");
            return true;
        }
        if (me.getPlane() == 1 && me.getX() >= 3195 && me.getX() <= 3230
                && me.getY() >= 3480 && me.getY() <= 3520) {
            object(ctx, ObjectID.VARROCK_SPIRALSTAIRS_MIDDLE_TALLER,
                    new Tile(3203, 3498, 1), "Climb-down", "descend castle");
            return true;
        }
        if (me.getPlane() == 1 && me.getX() >= 3100 && me.getX() <= 3120) {
            descendWizardTower(ctx, me);
            return true;
        }
        return false;
    }

    private void descendWizardTower(APIContext ctx, Tile me) {
        Tile stairs = new Tile(3104, 3160, 1);
        Area stairArea = new Area(1, 3100, 3156, 3109, 3165);
        SceneObject staircase = ctx.objects().query().actions("Climb-down")
                .within(stairArea).results().nearest();
        if (staircase != null && staircase.isValid()) {
            wizardStairSearchFailures = 0;
            if (openRoute(ctx, staircase.getLocation(), "Wizards' Tower exit")) return;
            if (staircase.tileDistanceTo(ctx) > 9) {
                walk(ctx, staircase.getLocation(), "Wizards' Tower exit");
                return;
            }
            boolean descended = staircase.interact("Climb-down")
                    || ctx.menu().interact("Climb-down", staircase, false);
            logger.accept("Demon Slayer: descend Wizards' Tower via " + staircase.getName()
                    + " id=" + staircase.getId() + " at " + staircase.getLocation()
                    + "=" + descended);
            nextActionAt = System.currentTimeMillis() + 1300L;
            return;
        }
        if (++wizardStairSearchFailures <= 3) {
            logger.accept("Demon Slayer: floor-1 stair not visible at " + me
                    + "; approaching " + stairs + " attempt " + wizardStairSearchFailures + "/3");
            if (openRoute(ctx, stairs, "Wizards' Tower exit")) return;
            ctx.walking().walkOnScreen(stairs);
            nextActionAt = System.currentTimeMillis() + 1400L;
            return;
        }
        status("Wizards' Tower floor-1 stair not found; waiting for scene to load");
        wizardStairSearchFailures = 0;
        nextActionAt = System.currentTimeMillis() + 9000L;
    }

    private void leaveLumbridgeCastle(APIContext ctx, Tile me) {
        Tile stairs = new Tile(LUMBRIDGE_CASTLE_STAIRS.getX(),
                LUMBRIDGE_CASTLE_STAIRS.getY(), me.getPlane());
        if (openRoute(ctx, stairs, "Lumbridge Castle exit")) return;
        SceneObject staircase = ctx.objects().query().named("Staircase")
                .actions("Climb-down")
                .within(new Area(me.getPlane(), 3199, 3205, 3212, 3224))
                .results().nearest();
        if (staircase != null && staircase.isValid() && staircase.tileDistanceTo(ctx) <= 9) {
            boolean descended = staircase.interact("Climb-down")
                    || ctx.menu().interact("Climb-down", staircase, false);
            logger.accept("Demon Slayer: descend Lumbridge Castle floor " + me.getPlane()
                    + " at " + staircase.getLocation() + "=" + descended);
            nextActionAt = System.currentTimeMillis() + 1200L;
            return;
        }
        walk(ctx, stairs, "Lumbridge Castle exit");
    }

    private void talk(APIContext ctx, int id, Area area, String label) {
        if (clearSelectedItem(ctx, "talk to " + label)) return;
        if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
        NPC npc = ctx.npcs().query().id(id).actions("Talk-to").within(area).reachable().results().nearest();
        if (npc != null && npc.isValid() && npc.tileDistanceTo(ctx) <= 8) {
            if (!permit("talk:" + id, stage(ctx) + ":" + ctx.localPlayer().getLocation()
                    + ":" + ctx.dialogues().getText(), 2200L, 3)) return;
            if (npc.interact("Talk-to")) {
                logger.accept("Demon Slayer: talked to " + label + " at " + npc.getLocation());
                if (id == NpcID.TRAIBORN) traibornDialogueUntil = System.currentTimeMillis() + 120_000L;
                if (id == NpcID.SIR_PRYSIN && label.contains("for Silverlight")) {
                    prysinHandoffActive = true;
                    prysinHandoffDialogueSeen = false;
                    schedulePrysinHandoffWait("key handoff started");
                }
                expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
                nextActionAt = System.currentTimeMillis() + 1000L;
            }
            return;
        }
        Tile destination = area.getCentralTile();
        if (openRoute(ctx, destination, label)) return;
        walk(ctx, destination, label);
    }

    private void object(APIContext ctx, int id, Tile tile, String action, String label, int... alternates) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null) return;
        boolean usingWaterOnDrain = id == ObjectID.QIP_DS_QUESTDRAIN_KEY
                || id == ObjectID.QIP_DS_QUESTDRAIN_NOKEY;
        if (!usingWaterOnDrain && clearSelectedItem(ctx, label)) return;
        if (!usingWaterOnDrain && me.getPlane() == tile.getPlane() && openRoute(ctx, tile, label)) return;
        if (me.getPlane() != tile.getPlane() || !near(me, tile, 10)) { walk(ctx, tile, label); return; }
        Area targetArea = new Area(tile.getPlane(), tile.getX() - 5, tile.getY() - 5,
                tile.getX() + 5, tile.getY() + 5);
        SceneObject obj = ctx.objects().query().id(id).within(targetArea).results().nearest();
        if (obj == null) for (int alt : alternates) {
            obj = ctx.objects().query().id(alt).within(targetArea).results().nearest();
            if (obj != null) break;
        }
        if (obj == null || !obj.isValid() || obj.tileDistanceTo(ctx) > 10) {
            if (usingWaterOnDrain && clearSelectedItem(ctx, "reposition for drain")) return;
            status("object not visible: " + label + "; repositioning");
            ctx.walking().walkOnMap(tile);
            nextActionAt = System.currentTimeMillis() + 950L;
            return;
        }
        String actualAction = action;
        if (action.startsWith("Climb") && !obj.hasAction(action) && obj.hasAction("Open"))
            actualAction = "Open";
        String actionKey = "object:" + label;
        String observation = stage(ctx) + ":" + me + ":" + obj.getId() + ":" + actualAction
                + ":" + ctx.inventory().getCount(ItemID.SILVERLIGHT_KEY_3);
        if (!permit(actionKey, observation, 1800L, 3)) return;
        if (obj.interact(actualAction) || ctx.menu().interact(actualAction, obj, false)) {
            logger.accept("Demon Slayer: " + actualAction + " " + label + " at " + obj.getLocation());
            expectedDialogueUntil = System.currentTimeMillis() + 45_000L;
            nextActionAt = System.currentTimeMillis() + 1100L;
        } else {
            status("interaction failed: " + actualAction + " " + label);
            nextActionAt = System.currentTimeMillis() + 1300L;
        }
    }

    private boolean openRoute(APIContext ctx, Tile destination, String label) {
        Tile me = ctx.localPlayer().getLocation();
        if (me == null || me.getPlane() != destination.getPlane() || !near(me, destination, 22)) return false;
        SceneObject door = ctx.walking().getBlockingObjectBetween(me, destination);
        return openDoor(ctx, door, label, destination);
    }
    private boolean openDoor(APIContext ctx, SceneObject door, String label, Tile destination) {
        if (door == null || !door.isValid() || !door.hasAction("Open")) return false;
        if (clearSelectedItem(ctx, "open door toward " + label)) return true;
        String name = door.getName();
        if (name == null || (!clean(name).contains("door") && !clean(name).contains("gate"))) return false;
        Tile me = ctx.localPlayer().getLocation();
        Tile tile = door.getLocation();
        if (me == null || tile == null || !near(me, tile, 10)) return false;
        org.example.core.navigation.AccessRecovery.interaction(ctx, door, "Open");
        if (lastOpenedDoor != null && near(lastOpenedDoor, tile, 0)
                && System.currentTimeMillis() - lastDoorActionAt < 4500L) {
            logger.accept("Demon Slayer: door action cooling down at " + tile
                    + "; walking toward " + destination + " to verify progress");
            return false;
        }
        if (!near(me, tile, 3)) {
            if (!permit("door:approach:" + tile, me.toString(), 1600L, 3)) return true;
            ctx.walking().walkOnScreen(tile);
        } else if (permit("door:open:" + tile, me + ":" + door.getId(), 1800L, 3)
                && (door.interact("Open") || ctx.menu().interact("Open", door, false))) {
            logger.accept("Demon Slayer: opened blocking " + name + " at " + tile
                    + " from=" + me + " toward=" + destination + " (" + label + ")");
            lastOpenedDoor = tile;
            lastDoorActionAt = System.currentTimeMillis();
        }
        nextActionAt = System.currentTimeMillis() + 1000L;
        return true;
    }

    private void approachBank(APIContext ctx, String item) {
        if (clearSelectedItem(ctx, "bank for " + item)) return;
        if (leaveInterior(ctx)) return;
        status("bank for " + item);
        if (Navigation.isBankReachable(ctx)) Navigation.openBank(ctx);
        else Navigation.walkToBank(ctx);
        nextActionAt = System.currentTimeMillis() + 900L;
    }
    private void walk(APIContext ctx, Tile target, String label) {
        if (clearSelectedItem(ctx, "walk to " + label)) return;
        if (ctx.bank().isOpen()) { ctx.bank().close(); return; }
        Tile startingAt = ctx.localPlayer().getLocation();
        if (startingAt != null && WIZARD_TOWER_GROUND.contains(startingAt)
                && !WIZARD_TOWER_GROUND.contains(target)
                && leaveWizardTowerGround(ctx, startingAt)) return;
        if (!permit("walk:" + label + ":" + target, String.valueOf(startingAt), 2200L, 3)) return;
        status("travel to " + label);
        WalkState result = Navigation.walkToNoTeleports(ctx, target);
        if (result != WalkState.SUCCESS) {
            Tile me = ctx.localPlayer().getLocation();
            logger.accept("Demon Slayer: route failed " + result + " from=" + me + " to=" + target);
            if (me != null && me.getPlane() == target.getPlane() && near(me, target, 18)) {
                if (!openRoute(ctx, target, label)) ctx.walking().walkOnMap(target);
            }
        }
        nextActionAt = System.currentTimeMillis() + 900L;
    }

    private boolean leaveWizardTowerGround(APIContext ctx, Tile me) {
        if (me.getX() <= WIZARD_STAIR_ROOM_DOOR.getX()
                && me.getY() <= WIZARD_STAIR_ROOM_DOOR.getY()
                && near(me, WIZARD_STAIR_ROOM_DOOR, 8)) {
            SceneObject stairRoomDoor = ctx.objects().query().id(WIZARD_STAIR_ROOM_DOOR_ID)
                    .within(new Area(0, 3106, 3161, 3108, 3163)).results().nearest();
            if (stairRoomDoor != null && stairRoomDoor.isValid()
                    && stairRoomDoor.hasAction("Open")) {
                logger.accept("Demon Slayer: stair-room door 23972 blocks tower exit from " + me);
                if (openDoor(ctx, stairRoomDoor, "Wizards' Tower stair-room door",
                        WIZARD_TOWER_BRIDGE)) return true;
            }
        }
        SceneObject blocker = ctx.walking().getBlockingObjectBetween(me, WIZARD_TOWER_BRIDGE);
        if (openDoor(ctx, blocker, "Wizards' Tower bridge", WIZARD_TOWER_BRIDGE)) return true;
        WalkState route = Navigation.walkToNoTeleports(ctx, WIZARD_TOWER_BRIDGE);
        if (route != WalkState.SUCCESS) {
            SceneObject nearbyDoor = ctx.objects().query().actions("Open")
                    .nameContains("Door").within(WIZARD_TOWER_GROUND).results().nearest();
            if (openDoor(ctx, nearbyDoor, "Wizards' Tower exit", WIZARD_TOWER_BRIDGE)) return true;
            logger.accept("Demon Slayer: tower exit route=" + route + " from=" + me
                    + " bridge=" + WIZARD_TOWER_BRIDGE + "; no usable blocking door found");
        }
        status("leaving Wizards' Tower through its doors");
        nextActionAt = System.currentTimeMillis() + 1100L;
        return true;
    }
    private boolean permit(String action, String observation, long retryAfterMillis, int maxAttempts) {
        if (actionGuard.allow(action, observation, retryAfterMillis, maxAttempts)) return true;
        if (actionGuard.isStalled(action)) {
            stats.setStatus("Demon Slayer: stalled action " + action + "; waiting for state change");
            nextActionAt = System.currentTimeMillis() + 5000L;
        } else nextActionAt = System.currentTimeMillis() + 400L;
        return false;
    }
    private boolean clearSelectedItem(APIContext ctx, String reason) {
        if (!ctx.inventory().isItemSelected()) return false;
        int selected = ctx.inventory().getSelectedItemId();
        boolean cleared = ctx.inventory().deselectItem();
        logger.accept("Demon Slayer: deselected item id=" + selected + " before " + reason
                + ", success=" + cleared);
        if (!cleared) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
        nextActionAt = System.currentTimeMillis() + 500L;
        return true;
    }
    private boolean near(Tile a, Tile b, int range) {
        return a != null && b != null && a.getPlane() == b.getPlane()
                && Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) <= range;
    }
    private void status(String detail) {
        stats.setStatus("Demon Slayer: " + detail);
        long now = System.currentTimeMillis();
        if (now >= nextLogAt) {
            logger.accept("Demon Slayer: " + detail);
            nextLogAt = now + 8_000L;
        }
    }
    private void closeCompletion(APIContext ctx) {
        boolean completionVisible = false;
        for (WidgetChild child : ctx.widgets().getAllChildren(w -> w != null && w.isValid()
                && w.getWidth() > 0 && w.getHeight() > 0)) {
            String text = clean(child.getText());
            if (text.contains("quest complete") || text.contains("you have completed"))
                completionVisible = true;
            if (text.equals("close") && child.click(false)) {
                logger.accept("Demon Slayer: closed quest completion widget");
                nextActionAt = System.currentTimeMillis() + 1200L;
                return;
            }
        }
        if (completionVisible) {
            if (!ctx.widgets().closeInterface()) ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            logger.accept("Demon Slayer: closed quest completion interface");
        }
        nextActionAt = System.currentTimeMillis() + 5000L;
    }
}
