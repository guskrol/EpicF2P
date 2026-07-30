package org.example.core.funding;

import org.example.core.items.F2PItemRegistry;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FundingPlanner {
    public enum Method {
        SELL_READY_STOCK,
        BEER_GLASS,
        WOODCUTTING,
        MINING_SMITHING,
        FISHING_COOKING
    }

    public Decision choose(int targetCoins, int knownCoins, List<Asset> assets) {
        Asset best = null;
        long bestValue = 0L;
        long totalValue = 0L;
        Asset bestAccumulator = null;
        long bestAccumulatorValue = 0L;
        for (Asset asset : assets) {
            if (asset == null || asset.totalCount() <= 0 || asset.unitSellPrice() <= 0) {
                continue;
            }

            long value = (long) asset.totalCount() * asset.unitSellPrice();
            totalValue += value;
        }

        for (Asset asset : assets) {
            if (asset == null || asset.totalCount() <= 0 || asset.unitSellPrice() <= 0) {
                continue;
            }

            long value = (long) asset.totalCount() * asset.unitSellPrice();
            boolean accumulateUntilTarget = shouldAccumulateUntilTarget(asset.name());
            boolean coversTarget = knownCoins + value >= targetCoins;
            boolean sellableBatch = asset.totalCount() >= minimumSaleBatch(asset.name());
            boolean sellableNow = accumulateUntilTarget
                    ? coversTarget && asset.bankCount() > 0 && asset.inventoryCount() == 0
                    : coversTarget || knownCoins + totalValue >= targetCoins || sellableBatch;

            if (sellableNow) {
                if (value > bestValue) {
                    best = asset;
                    bestValue = value;
                }
            } else if (accumulateUntilTarget && value > bestAccumulatorValue) {
                bestAccumulator = asset;
                bestAccumulatorValue = value;
            }
        }

        if (best != null) {
            return new Decision(
                    Method.SELL_READY_STOCK,
                    best.name(),
                    best.inventoryCount(),
                    best.bankCount(),
                    bestValue
            );
        }

        if (bestAccumulator != null) {
            return new Decision(
                    accumulationMethodFor(bestAccumulator.name()),
                    bestAccumulator.name(),
                    bestAccumulator.inventoryCount(),
                    bestAccumulator.bankCount(),
                    bestAccumulatorValue
            );
        }

        return fallbackDecision();
    }

    private boolean shouldAccumulateUntilTarget(String itemName) {
        return normalizedName(itemName).equals("beerglass");
    }

    private Method accumulationMethodFor(String itemName) {
        if (normalizedName(itemName).equals("beerglass")) {
            return Method.BEER_GLASS;
        }
        return Method.SELL_READY_STOCK;
    }

    private int minimumSaleBatch(String itemName) {
        int registryBatch = F2PItemRegistry.minimumSaleBatch(itemName);
        if (registryBatch > 0) {
            return registryBatch;
        }

        String normalized = normalizedName(itemName);
        if (normalized.equals("beerglass")) {
            return 140;
        }
        if (normalized.equals("bronzebar")) {
            return 50;
        }
        if (normalized.equals("logs")) {
            return 100;
        }
        if (normalized.equals("shrimps")) {
            return 120;
        }
        if (normalized.equals("cowhide")) {
            return 54;
        }
        return 27;
    }

    private String normalizedName(String itemName) {
        return itemName == null ? "" : itemName.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private Decision fallbackDecision() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 55) {
            return new Decision(Method.BEER_GLASS, "Beer glass", 0, 0, 0L);
        }
        if (roll < 75) {
            return new Decision(Method.WOODCUTTING, "Logs", 0, 0, 0L);
        }
        if (roll < 90) {
            return new Decision(Method.MINING_SMITHING, "Bronze bar", 0, 0, 0L);
        }
        return new Decision(Method.FISHING_COOKING, "Shrimps", 0, 0, 0L);
    }

    public int randomBufferCoins(int baseBuffer) {
        int minimum = Math.max(100, baseBuffer);
        int maximum = Math.max(minimum, minimum + 350);
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    public static final class Asset {
        private final String name;
        private final int inventoryCount;
        private final int bankCount;
        private final int unitSellPrice;

        public Asset(String name, int inventoryCount, int bankCount, int unitSellPrice) {
            this.name = name;
            this.inventoryCount = Math.max(0, inventoryCount);
            this.bankCount = Math.max(0, bankCount);
            this.unitSellPrice = Math.max(0, unitSellPrice);
        }

        public String name() {
            return name;
        }

        public int inventoryCount() {
            return inventoryCount;
        }

        public int bankCount() {
            return bankCount;
        }

        public int unitSellPrice() {
            return unitSellPrice;
        }

        public int totalCount() {
            return inventoryCount + bankCount;
        }
    }

    public static final class Decision {
        private final Method method;
        private final String itemName;
        private final int inventoryCount;
        private final int bankCount;
        private final long projectedValue;

        public Decision(Method method, String itemName, int inventoryCount, int bankCount, long projectedValue) {
            this.method = method;
            this.itemName = itemName;
            this.inventoryCount = Math.max(0, inventoryCount);
            this.bankCount = Math.max(0, bankCount);
            this.projectedValue = Math.max(0L, projectedValue);
        }

        public Method method() {
            return method;
        }

        public String itemName() {
            return itemName;
        }

        public int inventoryCount() {
            return inventoryCount;
        }

        public int bankCount() {
            return bankCount;
        }

        public long projectedValue() {
            return projectedValue;
        }
    }
}
