package org.example.modules.combat;

import java.util.concurrent.ThreadLocalRandom;

public enum CombatPhase {
    EARLY(1, 9, 15, 40),
    MID(10, 19, 20, 50),
    LATE(20, 39, 30, 70),
    ENDGAME(40, 126, 100, 200);

    private final int minLevel;
    private final int maxLevel;
    private final int minKillsBeforeSwitch;
    private final int maxKillsBeforeSwitch;

    CombatPhase(int minLevel, int maxLevel, int minKillsBeforeSwitch, int maxKillsBeforeSwitch) {
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.minKillsBeforeSwitch = minKillsBeforeSwitch;
        this.maxKillsBeforeSwitch = maxKillsBeforeSwitch;
    }

    public static CombatPhase forSkillLevel(int level) {
        if (level >= 40) {
            return ENDGAME;
        }
        if (level >= 20) {
            return LATE;
        }
        if (level >= 10) {
            return MID;
        }
        return EARLY;
    }

    public int minLevel() {
        return minLevel;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int randomKillsBeforeSwitch() {
        return ThreadLocalRandom.current().nextInt(minKillsBeforeSwitch, maxKillsBeforeSwitch + 1);
    }
}
