package org.example.modules.skilling;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ScriptStats;
import org.example.core.SkillCapManager;
import org.example.core.navigation.Navigation;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class WoodcuttingFiremakingModule extends AbstractSkillingModule {
    private static final Area LUMBRIDGE_TREES = new Area(3221, 3231, 3233, 3246);
    private static final Area LUMBRIDGE_FIREMAKING = new Area(3222, 3234, 3231, 3245);
    private static final Area SAFE_OAKS = new Area(3143, 3218, 3154, 3231);
    private static final int MAX_TREE_CANDIDATES_TO_INSPECT = 16;
    private static final int MAX_FIREMAKING_FAILURES_BEFORE_RELOCATE = 2;
    private static final String[] AXES = {
            "Mithril axe",
            "Steel axe",
            "Iron axe",
            "Bronze axe"
    };

    private final RandomBatchGate firemakingBatch = new RandomBatchGate(1, 27);
    private boolean burningBatch;
    private int lastTreeX = -1;
    private int lastTreeY = -1;
    private int lastTreePlane = -1;
    private long avoidLastTreeUntil;
    private long nextTreeViewAdjustAt;
    private int consecutiveFiremakingFailures;
    private TreeTarget activeTreeTarget;
    private long activeTreeTargetUntil;
    private int activeTreeTargetMisses;
    private long oakTargetCooldownUntil;

    public WoodcuttingFiremakingModule(Consumer<String> logger, ScriptStats stats, SkillCapManager caps) {
        super(logger, stats, caps);
    }

    @Override
    public String name() {
        return "skills.woodcutting_firemaking";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !isComplete(ctx);
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return complete(ctx, Skill.Skills.WOODCUTTING) && complete(ctx, Skill.Skills.FIREMAKING);
    }

    @Override
    public int priority(APIContext ctx) {
        return caps.levelsRemaining(ctx, Skill.Skills.WOODCUTTING)
                + caps.levelsRemaining(ctx, Skill.Skills.FIREMAKING);
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);

        if (ensureCleanInventory(ctx, "preparing Woodcutting/Firemaking inventory", skillingItemsToKeep())) {
            return;
        }

        if (burnableLogCount(ctx) == 0 && ensureToolUpgrade(ctx, "woodcutting axe", desiredAxeUpgrade(ctx))) {
            return;
        }

        if (!ensureAnyTool(ctx, "woodcutting axe", AXES)) {
            return;
        }

        if (!complete(ctx, Skill.Skills.FIREMAKING)
                && !ensureAnyTool(ctx, "tinderbox", "Tinderbox")) {
            return;
        }

        if (isBankOpen(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        int burnableLogs = burnableLogCount(ctx);
        if (!complete(ctx, Skill.Skills.FIREMAKING)
                && burnableLogs > 0
                && (burningBatch || firemakingBatch.shouldProcess(burnableLogs, ctx.inventory().isFull()))) {
            burningBatch = true;
            burnLogs(ctx);
            return;
        }

        if (complete(ctx, Skill.Skills.WOODCUTTING)) {
            if (!complete(ctx, Skill.Skills.FIREMAKING) && hasBurnableLogs(ctx)) {
                burningBatch = true;
                burnLogs(ctx);
                return;
            }

            if (!ctx.inventory().isEmpty()) {
                bankAllExcept(ctx, "woodcutting cap reached", toolsToKeep());
                return;
            }

            log("Woodcutting/Firemaking caps reached");
            Time.sleep(1200, 1800);
            return;
        }

        if (ctx.inventory().isFull()) {
            if (!complete(ctx, Skill.Skills.FIREMAKING)) {
                burningBatch = true;
                burnLogs(ctx);
            } else {
                bankAllExcept(ctx, "banking logs after Firemaking cap", toolsToKeep());
            }
            return;
        }

        chopTree(ctx);
    }

    private void chopTree(APIContext ctx) {
        stats.setTrainingSkill("Woodcutting");

        if (waitIfBusy(ctx)) {
            return;
        }

        if (closeInventoryForWoodcutting(ctx)) {
            return;
        }

        TreeTarget target = treeTarget(ctx);
        if (walkTo(ctx, target.area, target.areaLabel)) {
            return;
        }

        SceneObject tree = findTree(ctx, target);

        if (tree == null || !tree.isValid()) {
            log("No clickable " + target.logName + " tree found");
            handleMissingTree(ctx, target);
            Time.sleep(1000, 1600);
            return;
        }

        int beforeLogs = ctx.inventory().getCount(target.logName);
        log("Chopping " + tree.getName() + " until WC " + caps.capFor(Skill.Skills.WOODCUTTING));
        if (tree.interact("Chop down")) {
            Time.sleep(
                    1800,
                    2800,
                    () -> ctx.localPlayer().isAnimating()
                            || ctx.inventory().getCount(target.logName) > beforeLogs
                            || ctx.inventory().isFull(),
                    100
            );
            Time.sleep(
                    1200,
                    6500,
                    () -> ctx.inventory().getCount(target.logName) > beforeLogs
                            || ctx.inventory().isFull()
                            || (!ctx.localPlayer().isAnimating() && !ctx.localPlayer().isMoving()),
                    100
            );
            if (ctx.inventory().getCount(target.logName) > beforeLogs) {
                activeTreeTargetMisses = 0;
                rememberTreeToAvoid(tree);
            }
        } else {
            handleMissingTree(ctx, target);
        }
    }

    private void handleMissingTree(APIContext ctx, TreeTarget target) {
        activeTreeTargetMisses++;
        if (activeTreeTargetMisses < 3) {
            return;
        }

        if (target.oakTarget) {
            oakTargetCooldownUntil = System.currentTimeMillis()
                    + ThreadLocalRandom.current().nextLong(4 * 60_000L, 8 * 60_001L);
            log("Oak trees unavailable/click-blocked; using regular trees for a while");
        } else {
            adjustTreeView(ctx, target.area);
        }
        activeTreeTarget = null;
        activeTreeTargetUntil = 0;
        activeTreeTargetMisses = 0;
    }

    private SceneObject findTree(APIContext ctx, TreeTarget target) {
        List<SceneObject> trees = ctx.objects()
                .query()
                .filter(tree -> tree != null && isTargetTreeName(tree, target))
                .actions("Chop down")
                .within(target.area)
                .reachable()
                .results()
                .nearestList();

        if (trees.isEmpty()) {
            trees = ctx.objects()
                    .query()
                    .filter(tree -> tree != null && isTargetTreeName(tree, target))
                    .actions("Chop down")
                    .within(target.area)
                    .results()
                    .nearestList();
        }

        if (trees.isEmpty()) {
            return null;
        }

        SceneObject fallback = null;
        List<SceneObject> candidates = new ArrayList<>();
        boolean sawCoveredTree = false;
        int inspected = 0;
        for (SceneObject tree : trees) {
            if (tree == null || !tree.isValid()) {
                continue;
            }
            inspected++;
            if (fallback == null) {
                fallback = tree;
            }
            if (!isTreeClickSafe(ctx, tree)) {
                sawCoveredTree = true;
                if (inspected >= MAX_TREE_CANDIDATES_TO_INSPECT) {
                    break;
                }
                continue;
            }
            if (!isRecentlyCutTree(tree)) {
                candidates.add(tree);
            }
            if (inspected >= MAX_TREE_CANDIDATES_TO_INSPECT) {
                break;
            }
        }

        if (!candidates.isEmpty()) {
            int limit = Math.min(3, candidates.size());
            return candidates.get(ThreadLocalRandom.current().nextInt(limit));
        }

        if (fallback != null && isTreeClickSafe(ctx, fallback)) {
            return fallback;
        }

        if (sawCoveredTree) {
            adjustTreeView(ctx, target.area);
        }

        return null;
    }

    private boolean isTargetTreeName(SceneObject tree, TreeTarget target) {
        String treeName = normalizedName(tree.getName());
        for (String objectName : target.objectNames) {
            if (treeName.equals(normalizedName(objectName))) {
                return true;
            }
        }
        return false;
    }

    private boolean isTreeClickSafe(APIContext ctx, SceneObject tree) {
        if (!tree.isVisible()) {
            return false;
        }

        Point point = tree.getCentralPoint();
        if (point == null) {
            point = tree.getRealCentralPoint();
        }
        if (point == null) {
            return false;
        }

        Rectangle viewport = ctx.game().getViewport();
        if (viewport != null && !viewport.contains(point)) {
            return false;
        }

        boolean inventoryCoversPoint = ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)
                && inventoryPanelBounds(ctx).contains(point);

        return !inventoryCoversPoint
                && !bottomChatBounds(ctx).contains(point);
    }

    private Rectangle inventoryPanelBounds(APIContext ctx) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean found = false;

        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getWidth() <= 0 || item.getHeight() <= 0) {
                continue;
            }

            Rectangle bounds = item.getBounds();
            minX = Math.min(minX, bounds.x);
            minY = Math.min(minY, bounds.y);
            maxX = Math.max(maxX, bounds.x + bounds.width);
            maxY = Math.max(maxY, bounds.y + bounds.height);
            found = true;
        }

        int canvasWidth = Math.max(1, ctx.client().getCanvasWidth());
        int canvasHeight = Math.max(1, ctx.client().getCanvasHeight());
        if (!found) {
            return new Rectangle(Math.max(0, canvasWidth - 260), 150, 260, Math.max(1, canvasHeight - 150));
        }

        int left = Math.max(0, minX - 24);
        int top = Math.max(0, minY - 56);
        int right = Math.min(canvasWidth, Math.max(maxX + 96, canvasWidth));
        int bottom = Math.min(canvasHeight, maxY + 72);
        return new Rectangle(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private Rectangle bottomChatBounds(APIContext ctx) {
        int canvasWidth = Math.max(1, ctx.client().getCanvasWidth());
        int canvasHeight = Math.max(1, ctx.client().getCanvasHeight());
        return new Rectangle(0, Math.max(0, canvasHeight - 170), canvasWidth, 170);
    }

    private void adjustTreeView(APIContext ctx, Area area) {
        if (System.currentTimeMillis() < nextTreeViewAdjustAt || ctx.localPlayer().isMoving()) {
            return;
        }

        nextTreeViewAdjustAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(2500L, 4501L);
        if (ThreadLocalRandom.current().nextInt(100) < 65) {
            int yaw = (ctx.camera().getYawDeg() + ThreadLocalRandom.current().nextInt(70, 141)) % 360;
            log("Tree click covered by UI; rotating camera");
            ctx.camera().setYawDeg(yaw);
            Time.sleep(600, 1100);
            return;
        }

        Tile tile = area.getRandomTile();
        log("Tree click covered by UI; repositioning by map");
        if (!ctx.walking().walkOnMap(tile)) {
            ctx.walking().walkTo(tile);
        }
        Time.sleep(800, 1400);
    }

    private boolean isRecentlyCutTree(SceneObject tree) {
        return System.currentTimeMillis() < avoidLastTreeUntil
                && tree.getX() == lastTreeX
                && tree.getY() == lastTreeY
                && tree.getPlane() == lastTreePlane;
    }

    private void rememberTreeToAvoid(SceneObject tree) {
        if (ThreadLocalRandom.current().nextInt(100) >= 75) {
            return;
        }

        lastTreeX = tree.getX();
        lastTreeY = tree.getY();
        lastTreePlane = tree.getPlane();
        avoidLastTreeUntil = System.currentTimeMillis()
                + ThreadLocalRandom.current().nextLong(12_000L, 28_001L);
    }

    private void burnLogs(APIContext ctx) {
        stats.setTrainingSkill("Firemaking");

        if (waitIfBusy(ctx)) {
            return;
        }

        if (stats.consumeRecentFiremakingBlockedMessage()) {
            log("Firemaking rejected by game; returning to tree area");
            pauseFiremakingBatch(ctx);
            walkBackToFiremakingArea(ctx);
            return;
        }

        if (!ensureInFiremakingArea(ctx)) {
            return;
        }

        if (!openInventoryForFiremaking(ctx)) {
            return;
        }

        String logName = bestLogInInventory(ctx);
        if (logName == null) {
            finishFiremakingBatch(ctx);
            return;
        }

        if (!ensureClearFiremakingTile(ctx)) {
            return;
        }

        int before = ctx.inventory().getCount(logName);
        log("Lighting " + logName + " batch " + burnableLogCount(ctx) + "/" + firemakingBatch.targetAmount());
        boolean interacted = lightLog(ctx, logName);

        if (interacted) {
            Time.sleep(
                    1400,
                    2600,
                    () -> ctx.inventory().getCount(logName) < before || ctx.localPlayer().isAnimating(),
                    100
            );
            Time.sleep(
                    800,
                    4200,
                    () -> ctx.inventory().getCount(logName) < before
                            || (!ctx.localPlayer().isAnimating() && !ctx.localPlayer().isMoving()),
                    100
            );
            if (ThreadLocalRandom.current().nextInt(100) < 8) {
                Time.sleep(600, 1200);
            }
            boolean consumedLog = ctx.inventory().getCount(logName) < before;
            if (consumedLog) {
                consecutiveFiremakingFailures = 0;
            } else if (!ctx.localPlayer().isAnimating()) {
                consecutiveFiremakingFailures++;
                handleFiremakingFailure(ctx, "Firemaking tile blocked");
                return;
            }
            if (burnableLogCount(ctx) == 0 || complete(ctx, Skill.Skills.FIREMAKING)) {
                finishFiremakingBatch(ctx);
            }
            return;
        }

        log("Could not light " + logName);
        consecutiveFiremakingFailures++;
        handleFiremakingFailure(ctx, "Could not light " + logName);
        Time.sleep(1000, 1600);
    }

    private boolean lightLog(APIContext ctx, String logName) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 55) {
            return useTinderboxOnLog(ctx, logName)
                    || useLogOnTinderbox(ctx, logName);
        }
        return useLogOnTinderbox(ctx, logName)
                || useTinderboxOnLog(ctx, logName);
    }

    private boolean useTinderboxOnLog(APIContext ctx, String logName) {
        return selectInventoryItemForUse(ctx, "Tinderbox")
                && useSelectedItemOnInventoryItem(ctx, logName);
    }

    private boolean useLogOnTinderbox(APIContext ctx, String logName) {
        return selectInventoryItemForUse(ctx, logName)
                && useSelectedItemOnInventoryItem(ctx, "Tinderbox");
    }

    private boolean selectInventoryItemForUse(APIContext ctx, String itemName) {
        clearInventoryInteractionState(ctx);

        if (ctx.inventory().selectItem(itemName)) {
            Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
            if (ctx.inventory().isItemSelected()) {
                return true;
            }
        }

        if (ctx.menu().isOpen() && selectUseFromOpenMenu(ctx, itemName)) {
            return true;
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return selectUseFromOpenMenu(ctx, itemName);
        }
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean useSelectedItemOnInventoryItem(APIContext ctx, String itemName) {
        if (!ctx.inventory().isItemSelected()) {
            return false;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }

        ItemWidget item = ctx.inventory().getItem(itemName);
        if (item == null || !item.isValid()) {
            return false;
        }

        Point point = inventoryItemCenter(item);
        boolean clicked = point != null && ctx.mouse().click(point, false);
        Time.sleep(350, 1000, () -> !ctx.inventory().isItemSelected() || ctx.menu().isOpen(), 50);
        if (ctx.menu().isOpen()) {
            return clickUseFromOpenMenu(ctx, itemName);
        }
        return clicked;
    }

    private boolean selectUseFromOpenMenu(APIContext ctx, String itemName) {
        boolean clicked = clickUseFromOpenMenu(ctx, itemName);
        Time.sleep(250, 700, () -> ctx.inventory().isItemSelected() || !ctx.menu().isOpen(), 50);
        return clicked && ctx.inventory().isItemSelected();
    }

    private boolean clickUseFromOpenMenu(APIContext ctx, String itemName) {
        if (!ctx.menu().isOpen()) {
            return false;
        }

        boolean clicked = false;
        if (ctx.menu().contains("Use", itemName)) {
            clicked = ctx.menu().interact("Use", itemName, true);
        }
        if (!clicked && ctx.menu().contains("Use")) {
            clicked = ctx.menu().interact("Use", true);
        }
        Time.sleep(250, 700, () -> !ctx.menu().isOpen(), 50);
        return clicked;
    }

    private Point inventoryItemCenter(ItemWidget item) {
        Rectangle bounds = item.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private void clearInventoryInteractionState(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
        }
    }

    private boolean ensureClearFiremakingTile(APIContext ctx) {
        if (!hasFireOnTile(ctx, ctx.localPlayer().getLocation()) && consecutiveFiremakingFailures <= 0) {
            return true;
        }

        log("Standing on blocked firemaking tile; moving");
        return moveToClearFiremakingTile(ctx);
    }

    private void handleFiremakingFailure(APIContext ctx, String reason) {
        if (stats.consumeRecentFiremakingBlockedMessage()
                || consecutiveFiremakingFailures >= MAX_FIREMAKING_FAILURES_BEFORE_RELOCATE
                || !isInFiremakingArea(ctx)) {
            log(reason + "; relocating before retry");
            pauseFiremakingBatch(ctx);
            walkBackToFiremakingArea(ctx);
            return;
        }

        log(reason + "; moving before retry");
        moveToClearFiremakingTile(ctx);
    }

    private boolean ensureInFiremakingArea(APIContext ctx) {
        if (isInFiremakingArea(ctx)) {
            return true;
        }

        log("Firemaking area invalid; walking back to trees");
        pauseFiremakingBatch(ctx);
        walkBackToFiremakingArea(ctx);
        return false;
    }

    private boolean isInFiremakingArea(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return LUMBRIDGE_FIREMAKING.contains(location) || SAFE_OAKS.contains(location);
    }

    private void walkBackToFiremakingArea(APIContext ctx) {
        if (isBankOpen(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 800);
            return;
        }

        TreeTarget target = firemakingTarget(ctx);
        log("Walking back to " + target.areaLabel + " for Firemaking");
        Navigation.walkTo(ctx, target.firemakingArea.getRandomTile());
        Time.sleep(1200, 1800);
    }

    private boolean moveToClearFiremakingTile(APIContext ctx) {
        if (!isInFiremakingArea(ctx)) {
            log("Firemaking area invalid while moving; returning to trees");
            pauseFiremakingBatch(ctx);
            walkBackToFiremakingArea(ctx);
            return false;
        }

        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(200, 400);
        }
        openNeutralTab(ctx);

        List<Tile> destinations = findClearFiremakingTiles(ctx);
        if (destinations.isEmpty()) {
            log("No nearby clear tile for Firemaking");
            pauseFiremakingBatch(ctx);
            Time.sleep(800, 1400);
            return false;
        }

        Tile start = ctx.localPlayer().getLocation();
        Tile destination = destinations.get(ThreadLocalRandom.current().nextInt(Math.min(4, destinations.size())));
        String method = walkMethodName();
        log("Moving off fire tile via " + method + " to " + destination.getX() + "," + destination.getY());
        if (tryWalkToFiremakingTile(ctx, destination, method)) {
            Time.sleep(
                    500,
                    1200,
                    () -> !ctx.localPlayer().getLocation().equals(start) || ctx.localPlayer().isMoving(),
                    100
            );
            Time.sleep(
                    500,
                    1400,
                    () -> !ctx.localPlayer().isMoving(),
                    100
            );
            if (!hasFireOnTile(ctx, ctx.localPlayer().getLocation())) {
                consecutiveFiremakingFailures = 0;
                return true;
            }
        }

        log("Still on blocked fire tile; pausing Firemaking batch");
        pauseFiremakingBatch(ctx);
        Time.sleep(500, 900);
        return false;
    }

    private List<Tile> findClearFiremakingTiles(APIContext ctx) {
        List<Tile> candidates = new ArrayList<>();
        Tile origin = ctx.localPlayer().getLocation();
        int[][] offsets = ThreadLocalRandom.current().nextBoolean()
                ? new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-2, 0}, {2, 0}, {0, -2}, {0, 2}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {-3, 0}, {3, 0}, {0, -3}, {0, 3}}
                : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};

        for (int[] offset : offsets) {
            Tile tile = new Tile(origin.getX() + offset[0], origin.getY() + offset[1], origin.getPlane());
            if (tile.isValid()
                    && firemakingTarget(ctx).firemakingArea.contains(tile)
                    && tile.canReach(ctx)
                    && !hasFireOnTile(ctx, tile)) {
                candidates.add(tile);
            }
        }

        return candidates;
    }

    private String walkMethodName() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 35) {
            return "map";
        }
        if (roll < 65) {
            return "screen";
        }
        if (roll < 85) {
            return "walk";
        }
        return "tile";
    }

    private boolean tryWalkToFiremakingTile(APIContext ctx, Tile destination, String method) {
        if ("map".equals(method)) {
            return ctx.walking().walkOnMap(destination);
        }
        if ("screen".equals(method)) {
            return ctx.walking().walkOnScreen(destination) || destination.click(true);
        }
        if ("walk".equals(method)) {
            return ctx.walking().walkTo(destination);
        }
        return destination.interact("Walk here") || destination.click(true);
    }

    private void finishFiremakingBatch(APIContext ctx) {
        burningBatch = false;
        firemakingBatch.reset();
        consecutiveFiremakingFailures = 0;
        openNeutralTab(ctx);
    }

    private void pauseFiremakingBatch(APIContext ctx) {
        burningBatch = false;
        consecutiveFiremakingFailures = 0;
        openNeutralTab(ctx);
    }

    private boolean closeInventoryForWoodcutting(APIContext ctx) {
        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            return false;
        }

        log("Closing inventory for Woodcutting");
        openNeutralTab(ctx);
        Time.sleep(350, 650);
        return true;
    }

    private boolean openInventoryForFiremaking(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY)) {
            return true;
        }

        log("Opening inventory for Firemaking");
        ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
        Time.sleep(350, 650, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY), 100);
        return ctx.tabs().isOpen(ITabsAPI.Tabs.INVENTORY);
    }

    private void openNeutralTab(APIContext ctx) {
        if (ctx.tabs().isOpen(ITabsAPI.Tabs.COMBAT_OPTIONS)) {
            return;
        }
        if (ctx.tabs().open(ITabsAPI.Tabs.COMBAT_OPTIONS)) {
            return;
        }
        if (!ctx.tabs().isDisabled(ITabsAPI.Tabs.SKILLS)) {
            ctx.tabs().open(ITabsAPI.Tabs.SKILLS);
        }
    }

    private boolean hasFireOnTile(APIContext ctx, Tile tile) {
        for (SceneObject object : ctx.objects().getAt(tile)) {
            if (object != null && object.isValid() && "Fire".equals(object.getName())) {
                return true;
            }
        }
        return false;
    }

    private TreeTarget treeTarget(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (activeTreeTarget != null && now < activeTreeTargetUntil) {
            return activeTreeTarget;
        }

        boolean canUseOak = level(ctx, Skill.Skills.WOODCUTTING) >= 15 && now >= oakTargetCooldownUntil;
        TreeTarget selected = canUseOak
                ? oakTreeTarget()
                : regularTreeTarget();
        activeTreeTarget = selected;
        activeTreeTargetUntil = now + ThreadLocalRandom.current().nextLong(4 * 60_000L, 9 * 60_001L);
        activeTreeTargetMisses = 0;
        return selected;
    }

    private TreeTarget firemakingTarget(APIContext ctx) {
        if (ctx.inventory().contains("Oak logs") && level(ctx, Skill.Skills.WOODCUTTING) >= 15) {
            return oakTreeTarget();
        }
        return regularTreeTarget();
    }

    private TreeTarget regularTreeTarget() {
        return new TreeTarget(
                LUMBRIDGE_TREES,
                LUMBRIDGE_FIREMAKING,
                "Lumbridge trees",
                "Logs",
                new String[]{"Tree", "Dead tree"},
                false
        );
    }

    private TreeTarget oakTreeTarget() {
        return new TreeTarget(
                SAFE_OAKS,
                SAFE_OAKS,
                "safe oaks west of Lumbridge swamp",
                "Oak logs",
                new String[]{"Oak", "Oak tree"},
                true
        );
    }

    private boolean hasBurnableLogs(APIContext ctx) {
        return bestLogInInventory(ctx) != null;
    }

    private int burnableLogCount(APIContext ctx) {
        return ctx.inventory().getCount("Oak logs") + ctx.inventory().getCount("Logs");
    }

    private String bestLogInInventory(APIContext ctx) {
        if (ctx.inventory().contains("Oak logs")) {
            return "Oak logs";
        }
        if (ctx.inventory().contains("Logs")) {
            return "Logs";
        }
        return null;
    }

    private String[] toolsToKeep() {
        return new String[]{
                "Tinderbox",
                "Mithril axe",
                "Steel axe",
                "Iron axe",
                "Bronze axe"
        };
    }

    private String[] skillingItemsToKeep() {
        return new String[]{
                "Tinderbox",
                "Mithril axe",
                "Steel axe",
                "Iron axe",
                "Bronze axe",
                "Logs",
                "Oak logs"
        };
    }

    private String desiredAxeUpgrade(APIContext ctx) {
        int woodcuttingLevel = level(ctx, Skill.Skills.WOODCUTTING);
        if (woodcuttingLevel >= 21) {
            return "Mithril axe";
        }
        if (woodcuttingLevel >= 6) {
            return "Steel axe";
        }
        return null;
    }

    private static class TreeTarget {
        private final Area area;
        private final Area firemakingArea;
        private final String areaLabel;
        private final String logName;
        private final String[] objectNames;
        private final boolean oakTarget;

        private TreeTarget(Area area, Area firemakingArea, String areaLabel, String logName, String[] objectNames, boolean oakTarget) {
            this.area = area;
            this.firemakingArea = firemakingArea;
            this.areaLabel = areaLabel;
            this.logName = logName;
            this.objectNames = objectNames;
            this.oakTarget = oakTarget;
        }
    }
}
