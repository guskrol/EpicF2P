package org.example.modules.combat;

import com.epicbot.api.shared.model.Tile;

public final class TargetTravelProgressRegressionTest {
    public static void main(String[] args) {
        TargetTravelProgress travel = new TargetTravelProgress();
        long now = 1_000L;
        for (int step = 0; step < 20; step++) {
            require(travel.observe(new Tile(3160 + step, 3449, 0), now)
                    == TargetTravelProgress.Decision.CONTINUE,
                    "moving along a long route must retain the target");
            now += 20_000L;
        }
        require(travel.observe(new Tile(3179, 3449, 0), now + 36_000L)
                == TargetTravelProgress.Decision.RETRY_ROUTE,
                "first true stall should refresh the route");
        require(travel.observe(new Tile(3179, 3449, 0), now + 72_000L)
                == TargetTravelProgress.Decision.SWITCH_TARGET,
                "second true stall after recovery may switch target");
        travel.reset();
        require(travel.observe(new Tile(3200, 3400, 0), now + 100_000L)
                == TargetTravelProgress.Decision.CONTINUE,
                "new target starts with a fresh travel state");
        System.out.println("PASS: long travel retains target; stalled route retries once before switching");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
