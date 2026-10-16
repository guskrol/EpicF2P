package org.example.core.funding;

import java.util.List;

public final class FundingCollectionRegressionTest {
    public static void main(String[] args) {
        FundingPlanner planner = new FundingPlanner();
        for (int count = 1; count < 27; count++) {
            var decision = planner.choose(607, 100,
                    List.of(new FundingPlanner.Asset("Beer glass", count, 0, 19)));
            check(decision.method() == FundingPlanner.Method.BEER_GLASS,
                    "continue collecting instead of travelling to sell " + count + " glasses");
        }
        var sufficient = planner.choose(607, 100,
                List.of(new FundingPlanner.Asset("Beer glass", 27, 0, 19)));
        check(sufficient.method() == FundingPlanner.Method.SELL_READY_STOCK,
                "sell when the batch covers the remaining 507 coins");
        check(sufficient.inventoryCount() == 27 && sufficient.projectedValue() == 513L,
                "sell the whole stack at its current estimated value");

        var banked = planner.choose(507, 0,
                List.of(new FundingPlanner.Asset("Beer glass", 0, 2, 19)));
        check(banked.method() == FundingPlanner.Method.BEER_GLASS,
                "resume collecting a partial banked batch after restart");
        var combined = planner.choose(507, 0,
                List.of(new FundingPlanner.Asset("Beer glass", 2, 25, 19)));
        check(combined.method() == FundingPlanner.Method.SELL_READY_STOCK
                        && combined.inventoryCount() == 2 && combined.bankCount() == 25,
                "count both bank and inventory toward the target");

        var largeTarget = planner.choose(10_000, 0,
                List.of(new FundingPlanner.Asset("Beer glass", 0, 140, 19)));
        check(largeTarget.method() == FundingPlanner.Method.SELL_READY_STOCK
                        && largeTarget.bankCount() == 140,
                "a complete sale batch can fund a larger target in stages");
        var otherStock = planner.choose(507, 0, List.of(
                new FundingPlanner.Asset("Beer glass", 2, 0, 19),
                new FundingPlanner.Asset("Tin ore", 0, 88, 23)));
        check(otherStock.method() == FundingPlanner.Method.SELL_READY_STOCK
                        && otherStock.itemName().equals("Tin ore") && otherStock.bankCount() == 88,
                "other eligible bank stock retains priority and is sold in full");
        var smallOtherStock = planner.choose(507, 0,
                List.of(new FundingPlanner.Asset("Big bones", 0, 1, 200)));
        check(smallOtherStock.method() == FundingPlanner.Method.SELL_READY_STOCK,
                "do not impose the collection batch on other existing eligible stock");
        var blockedCollection = planner.choose(507, 0,
                List.of(new FundingPlanner.Asset("Beer glass", 2, 0, 19)),
                method -> method != FundingPlanner.Method.BEER_GLASS);
        check(blockedCollection.method() == FundingPlanner.Method.SELL_READY_STOCK,
                "existing glasses remain sellable when collection is inaccessible");
        System.out.println("Funding collection regression checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
