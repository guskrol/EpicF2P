package org.example.core.navigation;

import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.webwalking.model.WebPath;
import com.epicbot.api.shared.webwalking.model.PathStatus;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/** Clicks points from the API route; never invents a replacement route between towns. */
final class MixedRoute {
    final List<Tile> tiles;
    final Tile goal;
    final boolean bank;
    private int index;
    private Step step;
    private Boolean lastScreen;
    private int sameMethodCount;

    MixedRoute(List<Tile> tiles, Tile goal, boolean bank) {
        this.tiles = List.copyOf(tiles);
        this.goal = goal;
        this.bank = bank;
    }

    static MixedRoute from(WebPath path, Tile requested, boolean bank) {
        if (path == null || path.getPathStatus() != PathStatus.SUCCESS
                || path.getPath() == null || path.getPath().size() < 2) return null;
        List<Tile> tiles = path.getPath().stream().map(point -> point.toPosition()).toList();
        return new MixedRoute(tiles, requested, bank);
    }

    boolean matches(Tile requested, boolean toBank) {
        return bank == toBank && distance(goal, requested) <= 3;
    }

    Step next(Tile here, long now, Predicate<Tile> reachable, RandomGenerator random) {
        if (step != null) {
            int nearestAhead = nearestPathIndex(here);
            if (distance(here, step.target) <= 2
                    || (nearestAhead > step.pathIndex && distance(here, tiles.get(nearestAhead)) <= 2)) {
                index = Math.max(index, nearestAhead);
                step = null;
            } else {
                return step;
            }
        }
        int nearest = nearestPathIndex(here);
        int nearestDistance = distance(here, tiles.get(nearest));
        if (nearestDistance > 20) return null;
        index = nearest;
        boolean screen = lastScreen != null && sameMethodCount >= 2
                ? !lastScreen : random.nextInt(100) < 60;
        int maximum = screen ? random.nextInt(7, 11) : random.nextInt(12, 18);
        List<Integer> candidates = new ArrayList<>();
        for (int i = index + 1; i < tiles.size(); i++) {
            Tile candidate = tiles.get(i);
            // A stair, ladder or other scene transition belongs to the existing web walker.
            if (candidate.getPlane() != here.getPlane()
                    || distance(tiles.get(i - 1), candidate) > 32) break;
            int distance = distance(here, candidate);
            if (distance > maximum) break;
            if (distance >= (screen ? 3 : 5) || i == tiles.size() - 1) candidates.add(i);
        }
        int selected = selectCandidate(candidates, reachable, screen);
        if (selected < 0 && screen) {
            screen = false;
            maximum = random.nextInt(12, 18);
            candidates.clear();
            for (int i = index + 1; i < tiles.size(); i++) {
                Tile candidate = tiles.get(i);
                if (candidate.getPlane() != here.getPlane()
                        || distance(tiles.get(i - 1), candidate) > 32) break;
                int distance = distance(here, candidate);
                if (distance > maximum) break;
                if (distance >= 5 || i == tiles.size() - 1) candidates.add(i);
            }
            selected = selectCandidate(candidates, reachable, false);
        }
        if (selected < 0) return null;
        Tile target = tiles.get(selected);
        step = new Step(selected, target, new LocalWalkIntent(target, here, now, screen,
                random.nextLong(1800L, 2801L)));
        return step;
    }

    private int selectCandidate(List<Integer> candidates, Predicate<Tile> reachable, boolean screen) {
        for (int i = candidates.size() - 1; i >= 0; i--) {
            int candidate = candidates.get(i);
            Tile tile = tiles.get(candidate);
            if ((!screen || tile.isVisible()) && reachable.test(tile)) return candidate;
        }
        return -1;
    }

    private int nearestPathIndex(Tile here) {
        int nearest = index;
        int nearestDistance = Integer.MAX_VALUE;
        for (int i = index; i < tiles.size(); i++) {
            int candidateDistance = distance(here, tiles.get(i));
            if (candidateDistance < nearestDistance) {
                nearestDistance = candidateDistance;
                nearest = i;
            }
        }
        return nearest;
    }

    void accepted(boolean screen) {
        sameMethodCount = lastScreen != null && lastScreen == screen ? sameMethodCount + 1 : 1;
        lastScreen = screen;
    }

    void delegateTransition() { step = null; }

    Transition nearbyTransition(Tile here) {
        int nearest = nearestPathIndex(here);
        for (int i = nearest; i + 1 < tiles.size(); i++) {
            Tile from = tiles.get(i), to = tiles.get(i + 1);
            if (distance(from, to) <= 32) continue;
            return distance(here, from) <= 6 ? new Transition(from, to) : null;
        }
        return null;
    }

    static int distance(Tile first, Tile second) {
        if (first == null || second == null || first.getPlane() != second.getPlane()) return Integer.MAX_VALUE;
        return Math.max(Math.abs(first.getX() - second.getX()), Math.abs(first.getY() - second.getY()));
    }

    record Step(int pathIndex, Tile target, LocalWalkIntent intent) { }
    record Transition(Tile from, Tile to) { }
}
