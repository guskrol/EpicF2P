package org.example.modules.moneymaking;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.example.core.ManagedF2PModule;
import org.example.core.ScriptStats;
import org.example.core.items.GePricing;
import org.example.core.navigation.Navigation;
import org.example.core.navigation.ViewRecovery;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class BeerGlassCollectorModule implements ManagedF2PModule {
    private static final String BEER_GLASS = "Beer glass";
    private static final String COINS = "Coins";
    private static final int SORCERESS_DOOR_CLOSED_ID = 1535;
    private static final int SORCERESS_DOOR_OPEN_ID = 1536;
    private static final int[] BEER_GLASS_SHELVES_IDS = {21794, 21799, 21789, 21791};
    private static final Tile BEER_GLASS_STAND_TILE = new Tile(3324, 3137, 0);
    private static final Tile SORCERESS_DOOR_TILE = new Tile(3321, 3142, 0);
    private static final int SORCERESS_MIN_X = 3318;
    private static final int SORCERESS_MIN_Y = 3133;
    private static final int SORCERESS_MAX_X = 3329;
    private static final int SORCERESS_MAX_Y = 3143;
    private static final Area SORCERESS_SHELVES_AREA = new Area(SORCERESS_MIN_X, SORCERESS_MIN_Y, SORCERESS_MAX_X, SORCERESS_MAX_Y);
    private static final Area AL_KHARID_BANK_AREA = new Area(3268, 3161, 3274, 3173);
    private static final Tile[] AL_KHARID_BANK_TILES = {
            new Tile(3270, 3167, 0),
            new Tile(3271, 3167, 0),
            new Tile(3272, 3166, 0)
    };
    private static final long SHELVES_DEBUG_INTERVAL_MILLIS = 12_000L;
    private static final long SHELVES_CAMERA_TURN_COOLDOWN_MILLIS = 20_000L;
    private static final long SHELVES_VIEW_RECOVERY_COOLDOWN_MIN_MILLIS = 12_000L;
    private static final long SHELVES_VIEW_RECOVERY_COOLDOWN_MAX_MILLIS = 18_000L;
    private static final int SHELVES_SEARCH_DISTANCE = 8;
    private static final int MAX_EXACT_TILE_APPROACH_ATTEMPTS = 6;

    private final Consumer<String> logger;
    private final ScriptStats stats;
    private long nextShelvesDebugAt;
    private long nextShelvesViewRecoverAt;
    private long nextShelvesCameraTurnAt;
    private long lastBeerGlassSearchClickAt;
    private int lastBeerGlassCount = -1;
    private int consecutiveSearchFailures;
    private int consecutiveExactTileApproaches;

    public BeerGlassCollectorModule(Consumer<String> logger, ScriptStats stats) {
        this.logger = logger;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "money.beer_glass";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return true;
    }

    @Override
    public boolean isComplete(APIContext ctx) {
        return false;
    }

    @Override
    public int priority(APIContext ctx) {
        return 1;
    }

    @Override
    public void execute(APIContext ctx) {
        stats.startExperienceIfNeeded(ctx);
        stats.setTrainingSkill("Beer glass");

        if (ctx.grandExchange().isOpen()) {
            log("Closing GE; Beer glass test is banking-only");
            ctx.grandExchange().close();
            Time.sleep(600, 900);
            return;
        }

        if (ctx.bank().isOpen()) {
            handleOpenBank(ctx);
            return;
        }

        if (inventoryHasUnwantedItems(ctx)) {
            if (!openBank(ctx, "cleaning inventory for Beer glass test")) {
                return;
            }
            return;
        }

        int glasses = beerGlassCount(ctx);
        if (ctx.inventory().isFull() && glasses > 0) {
            openBank(ctx, "banking full Beer glass inventory");
            return;
        }

        collectBeerGlasses(ctx);
    }

    private void collectBeerGlasses(APIContext ctx) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            Time.sleep(500, 800);
            return;
        }

        if (recoverFromUpstairs(ctx)) {
            return;
        }

        SceneObject shelves = findBeerGlassShelves(ctx);
        if (shelves != null && shelves.isValid() && canSearchShelvesFromHere(ctx, shelves)) {
            consecutiveExactTileApproaches = 0;
            handleBeerGlassShelves(ctx, shelves);
            return;
        }

        if (!isAtBeerGlassStandTile(ctx)) {
            walkToBeerGlassStandTile(ctx);
            consecutiveExactTileApproaches++;
            if (consecutiveExactTileApproaches >= MAX_EXACT_TILE_APPROACH_ATTEMPTS) {
                log("Beer glass exact tile approach loop; trying shelves search recovery");
                debugNearbySearchObjects(ctx);
                ViewRecovery.recover(ctx, BEER_GLASS_STAND_TILE, "Beer glass shelves approach", this::log);
                consecutiveExactTileApproaches = 0;
            }
            return;
        }

        if (shelves == null || !shelves.isValid()) {
            debugNearbySearchObjects(ctx);
            recoverShelvesView(ctx);
            Time.sleep(900, 1400);
            return;
        }

        consecutiveExactTileApproaches = 0;
        handleBeerGlassShelves(ctx, shelves);
    }

    private void handleBeerGlassShelves(APIContext ctx, SceneObject shelves) {
        int before = beerGlassCount(ctx);
        trackBeerGlassProgress(before);

        log("Searching shelves for Beer glass: " + before + "/28");
        if (searchBeerGlassShelves(ctx, shelves, before)) {
            Time.sleep(
                    900,
                    1600,
                    () -> beerGlassCount(ctx) > before || ctx.inventory().isFull(),
                    100
            );
            int gained = Math.max(0, beerGlassCount(ctx) - before);
            if (gained > 0) {
                consecutiveSearchFailures = 0;
                trackBeerGlassProgress(beerGlassCount(ctx));
                stats.recordLoot(BEER_GLASS, gained, (long) sellPriceFor(ctx, BEER_GLASS) * gained);
                return;
            }
        }

        consecutiveSearchFailures++;
        if (consecutiveSearchFailures >= 3) {
            log("Beer glass search stalled at shelves; repositioning and recovering view");
            walkToBeerGlassStandTile(ctx);
            ViewRecovery.recover(ctx, BEER_GLASS_STAND_TILE, "Beer glass shelves search", this::log);
            consecutiveSearchFailures = 0;
        }
    }

    private boolean canSearchShelvesFromHere(APIContext ctx, SceneObject shelves) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null
                && location.getPlane() == 0
                && isInsideSorceressShelvesXY(location)
                && shelves.tileDistanceTo(ctx) <= SHELVES_SEARCH_DISTANCE;
    }

    private boolean searchBeerGlassShelves(APIContext ctx, SceneObject shelves, int beforeCount) {
        clearInteractionState(ctx);

        boolean staleAtShelf = lastBeerGlassCount == beforeCount
                && System.currentTimeMillis() - lastBeerGlassSearchClickAt > 8_000L;
        boolean shouldRetryView = staleAtShelf || consecutiveSearchFailures > 0;

        lastBeerGlassSearchClickAt = System.currentTimeMillis();
        boolean clicked = clickShelvesSearch(ctx, shelves);
        if (!clicked && shouldRetryView && canTurnCameraToShelves()) {
            log("Beer glass shelves Search not clickable; turning camera once");
            ctx.camera().turnTo(shelves);
            Time.sleep(250, 500);
            clicked = clickShelvesSearch(ctx, shelves);
        }

        if (!clicked && consecutiveSearchFailures >= 1 && canRecoverShelvesViewNow()) {
            log("Beer glass shelves Search click failed; recovering view");
            ViewRecovery.recover(ctx, shelves, "Beer glass shelves", this::log);
        }
        return clicked;
    }

    private boolean clickShelvesSearch(APIContext ctx, SceneObject shelves) {
        return shelves.interact("Search")
                || ctx.menu().interact("Search", shelves, false)
                || ctx.menu().interact("Search", shelves, true);
    }

    private boolean canTurnCameraToShelves() {
        long now = System.currentTimeMillis();
        if (now < nextShelvesCameraTurnAt) {
            return false;
        }

        nextShelvesCameraTurnAt = now + SHELVES_CAMERA_TURN_COOLDOWN_MILLIS;
        return true;
    }

    private boolean canRecoverShelvesViewNow() {
        long now = System.currentTimeMillis();
        if (now < nextShelvesViewRecoverAt) {
            return false;
        }

        nextShelvesViewRecoverAt = now + ThreadLocalRandom.current().nextLong(
                SHELVES_VIEW_RECOVERY_COOLDOWN_MIN_MILLIS,
                SHELVES_VIEW_RECOVERY_COOLDOWN_MAX_MILLIS + 1
        );
        return true;
    }

    private void trackBeerGlassProgress(int currentCount) {
        if (currentCount != lastBeerGlassCount) {
            lastBeerGlassCount = currentCount;
            lastBeerGlassSearchClickAt = System.currentTimeMillis();
        }
    }

    private SceneObject findBeerGlassShelves(APIContext ctx) {
        for (int id : BEER_GLASS_SHELVES_IDS) {
            SceneObject shelves = ctx.objects()
                    .query()
                    .id(id)
                    .actions("Search")
                    .within(SORCERESS_SHELVES_AREA)
                    .reachable()
                    .results()
                    .nearest();
            if (shelves != null && shelves.isValid()) {
                return shelves;
            }
        }

        SceneObject shelves = ctx.objects()
                .query()
                .nameContains("Shel")
                .actions("Search")
                .within(SORCERESS_SHELVES_AREA)
                .reachable()
                .results()
                .nearest();
        if (shelves != null && shelves.isValid()) {
            return shelves;
        }

        shelves = ctx.objects()
                .query()
                .nameContains("Shel")
                .actions("Search")
                .within(SORCERESS_SHELVES_AREA)
                .results()
                .nearest();
        if (shelves != null && shelves.isValid()) {
            return shelves;
        }

        return null;
    }

    private boolean isAtBeerGlassStandTile(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        return location != null
                && location.getX() == BEER_GLASS_STAND_TILE.getX()
                && location.getY() == BEER_GLASS_STAND_TILE.getY()
                && location.getPlane() == BEER_GLASS_STAND_TILE.getPlane();
    }

    private void walkToBeerGlassStandTile(APIContext ctx) {
        clearInteractionState(ctx);

        SceneObject blocker = ctx.walking().getBlockingObjectBetween(
                ctx.localPlayer().getLocation(),
                BEER_GLASS_STAND_TILE
        );
        if (tryOpenSorceressDoor(ctx, blocker, "blocking door")) {
            return;
        }

        SceneObject closedDoor = findSorceressClosedDoor(ctx);
        if (closedDoor != null && closedDoor.isValid()) {
            if (closedDoor.tileDistanceTo(ctx) > 2) {
                log("Walking locally to Sorceress house door");
                walkLocallyTo(ctx, closedDoor, "Sorceress house door");
                Time.sleep(800, 1200);
                return;
            }
            if (tryOpenSorceressDoor(ctx, closedDoor, "Sorceress house door")) {
                return;
            }
        }

        if (BEER_GLASS_STAND_TILE.tileDistanceTo(ctx) <= 25) {
            log("Walking locally to exact Beer glass shelves tile");
            if (walkLocallyTo(ctx, BEER_GLASS_STAND_TILE, "Beer glass shelves exact tile")) {
                Time.sleep(800, 1200);
                return;
            }
        }

        if (!SORCERESS_SHELVES_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Al Kharid Beer glass shelves");
            Navigation.walkToNoTeleports(ctx, SORCERESS_DOOR_TILE);
            Time.sleep(1200, 1800);
            return;
        }

        log("Beer glass exact tile not reached; waiting for scene/path update");
        Time.sleep(900, 1400);
    }

    private boolean recoverFromUpstairs(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || location.getPlane() == 0 || !isInsideSorceressShelvesXY(location)) {
            return false;
        }

        SceneObject stairs = findClimbDownObject(ctx);
        if (stairs != null && stairs.isValid()) {
            log("Beer glass upstairs recovery: climbing down before searching shelves");
            boolean interacted = stairs.interact("Climb-down")
                    || stairs.interact("Climb down")
                    || stairs.interact("Down")
                    || ctx.menu().interact("Climb-down", stairs, false)
                    || ctx.menu().interact("Climb down", stairs, false)
                    || ctx.menu().interact("Down", stairs, false)
                    || ctx.menu().interact("Climb-down", stairs, true)
                    || ctx.menu().interact("Climb down", stairs, true)
                    || ctx.menu().interact("Down", stairs, true);
            if (interacted) {
                Time.sleep(1200, 2000, () -> {
                    Tile current = ctx.localPlayer().getLocation();
                    return current != null && current.getPlane() == 0;
                }, 100);
                return true;
            }
        }

        log("Beer glass upstairs recovery: walking back to ground floor route");
        Navigation.walkToNoTeleports(ctx, SORCERESS_DOOR_TILE);
        Time.sleep(1200, 1800);
        return true;
    }

    private SceneObject findClimbDownObject(APIContext ctx) {
        SceneObject object = ctx.objects()
                .query()
                .actions("Climb-down")
                .tileDistance(12)
                .results()
                .nearest();
        if (object != null && object.isValid()) {
            return object;
        }

        object = ctx.objects()
                .query()
                .actions("Climb down")
                .tileDistance(12)
                .results()
                .nearest();
        if (object != null && object.isValid()) {
            return object;
        }

        object = ctx.objects()
                .query()
                .actions("Down")
                .tileDistance(12)
                .results()
                .nearest();
        if (object != null && object.isValid()) {
            return object;
        }

        return ctx.objects()
                .query()
                .nameContains("Stair")
                .tileDistance(12)
                .results()
                .nearest();
    }

    private SceneObject findSorceressClosedDoor(APIContext ctx) {
        SceneObject door = ctx.objects()
                .query()
                .id(SORCERESS_DOOR_CLOSED_ID)
                .actions("Open")
                .within(SORCERESS_SHELVES_AREA)
                .results()
                .nearest();
        if (door != null && door.isValid()) {
            return door;
        }

        return ctx.objects()
                .query()
                .named("Door")
                .actions("Open")
                .within(SORCERESS_SHELVES_AREA)
                .results()
                .nearest();
    }

    private boolean tryOpenSorceressDoor(APIContext ctx, SceneObject object, String label) {
        if (object == null || !object.isValid()) {
            return false;
        }
        if (object.getId() != SORCERESS_DOOR_CLOSED_ID
                && object.getId() != SORCERESS_DOOR_OPEN_ID
                && !object.hasAction("Open", "Enter", "Walk-through")) {
            return false;
        }
        if (object.getId() == SORCERESS_DOOR_OPEN_ID && !object.hasAction("Open")) {
            return false;
        }

        log("Opening " + label + " before Beer glass shelves: id=" + object.getId());
        boolean interacted = object.interact("Open")
                || object.interact("Enter")
                || object.interact("Walk-through")
                || ctx.menu().interact("Open", object, false)
                || ctx.menu().interact("Enter", object, false);
        if (interacted) {
            Time.sleep(900, 1600, () -> findSorceressClosedDoor(ctx) == null, 100);
        }
        return interacted;
    }

    private boolean walkLocallyTo(APIContext ctx, Locatable destination, String label) {
        log("Walking locally to " + label);
        boolean walked = ctx.walking().walkOnScreen(destination)
                || ctx.walking().walkToOnScreen(destination)
                || ctx.walking().walkOnMap(destination)
                || ctx.walking().walkTo(destination);
        if (walked) {
            Time.sleep(700, 1200, () -> ctx.localPlayer().isMoving(), 100);
        }
        return walked;
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

    private void handleOpenBank(APIContext ctx) {
        if (inventoryHasUnwantedItems(ctx)) {
            log("Depositing non Beer glass inventory before test");
            ctx.bank().depositAllExcept(COINS, BEER_GLASS);
            Time.sleep(600, 900);
            return;
        }

        int glasses = beerGlassCount(ctx);
        if (glasses > 0) {
            log("Banking " + glasses + "x Beer glass at Al Kharid");
            ctx.bank().depositAll(BEER_GLASS);
            Time.sleep(600, 900);
            return;
        }

        ctx.bank().close();
        Time.sleep(500, 800);
    }

    private boolean openBank(APIContext ctx, String reason) {
        if (ctx.bank().isOpen()) {
            return true;
        }

        if (!AL_KHARID_BANK_AREA.contains(ctx.localPlayer().getLocation())) {
            log("Walking to Al Kharid bank: " + reason);
            Navigation.walkToNoTeleports(ctx, randomAlKharidBankTile());
            Time.sleep(1200, 1800);
            return false;
        }

        log("Opening Al Kharid bank: " + reason);
        Navigation.openBank(ctx);
        Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
        if (ctx.bank().isOpen()) {
            return true;
        }

        SceneObject bankObject = ctx.objects()
                .query()
                .actions("Bank")
                .within(AL_KHARID_BANK_AREA)
                .reachable()
                .results()
                .nearest();
        if (bankObject != null && bankObject.isValid()) {
            log("Opening Al Kharid bank object: " + bankObject.getName());
            bankObject.interact("Bank");
            Time.sleep(1200, 1800, () -> ctx.bank().isOpen(), 100);
            return ctx.bank().isOpen();
        }

        log("Al Kharid bank not clickable yet; repositioning");
        Navigation.walkToNoTeleports(ctx, randomAlKharidBankTile());
        Time.sleep(900, 1400);
        return false;
    }

    private boolean inventoryHasUnwantedItems(APIContext ctx) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (!namesMatch(item.getName(), COINS) && !namesMatch(item.getName(), BEER_GLASS)) {
                return true;
            }
        }
        return false;
    }

    private int beerGlassCount(APIContext ctx) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && namesMatch(item.getName(), BEER_GLASS)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private int sellPriceFor(APIContext ctx, String itemName) {
        return GePricing.quickSellPrice(ctx, itemName, 20L);
    }

    private void debugNearbySearchObjects(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextShelvesDebugAt) {
            return;
        }

        List<SceneObject> candidates = ctx.objects()
                .query()
                .actions("Search")
                .tileDistance(12)
                .results()
                .nearestList();
        StringBuilder message = new StringBuilder("Beer glass Search object candidates=");
        int inspected = 0;
        for (SceneObject candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            message.append(" [id=")
                    .append(candidate.getId())
                    .append(", name=")
                    .append(candidate.getName())
                    .append(", tile=")
                    .append(candidate.getX())
                    .append(',')
                    .append(candidate.getY())
                    .append(']');
            inspected++;
            if (inspected >= 5) {
                break;
            }
        }
        if (inspected == 0) {
            message.append(" none");
        }
        log(message.toString());
        nextShelvesDebugAt = now + SHELVES_DEBUG_INTERVAL_MILLIS;
    }

    private void recoverShelvesView(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextShelvesViewRecoverAt || ctx.localPlayer().isMoving()) {
            log("No Beer glass shelves found near Sorceress house");
            return;
        }

        nextShelvesViewRecoverAt = now + ThreadLocalRandom.current().nextLong(
                SHELVES_VIEW_RECOVERY_COOLDOWN_MIN_MILLIS,
                SHELVES_VIEW_RECOVERY_COOLDOWN_MAX_MILLIS + 1
        );
        log("No Beer glass shelves found near Sorceress house; adjusting view");
        ViewRecovery.recover(ctx, BEER_GLASS_STAND_TILE, "Beer glass shelves", this::log);
    }

    private boolean isInsideSorceressShelvesXY(Tile tile) {
        return tile != null
                && tile.getX() >= SORCERESS_MIN_X
                && tile.getX() <= SORCERESS_MAX_X
                && tile.getY() >= SORCERESS_MIN_Y
                && tile.getY() <= SORCERESS_MAX_Y;
    }

    private Tile randomAlKharidBankTile() {
        return AL_KHARID_BANK_TILES[ThreadLocalRandom.current().nextInt(AL_KHARID_BANK_TILES.length)];
    }

    private boolean namesMatch(String left, String right) {
        return normalizedName(left).equals(normalizedName(right));
    }

    private String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void log(String message) {
        stats.setStatus(message);
        logger.accept(message);
    }
}
