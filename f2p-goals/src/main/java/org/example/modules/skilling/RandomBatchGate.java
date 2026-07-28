package org.example.modules.skilling;

import java.util.concurrent.ThreadLocalRandom;

class RandomBatchGate {
    private final int minAmount;
    private final int maxAmount;
    private int targetAmount;

    RandomBatchGate(int minAmount, int maxAmount) {
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
    }

    boolean shouldProcess(int currentAmount, boolean inventoryFull) {
        return currentAmount > 0 && (inventoryFull || currentAmount >= targetAmount());
    }

    int targetAmount() {
        if (targetAmount <= 0) {
            targetAmount = ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
        }
        return targetAmount;
    }

    void reset() {
        targetAmount = 0;
    }
}
