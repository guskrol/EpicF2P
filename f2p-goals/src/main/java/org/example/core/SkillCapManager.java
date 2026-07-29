package org.example.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class SkillCapManager {
    private static final int MIN_CAP = 29;
    private static final int MAX_CAP = 35;
    private static final int RANGED_CAP = 30;
    private static final int MAGIC_CAP = 25;
    private static final int MIN_CRAFTING_CAP = 25;
    private static final int MAX_CRAFTING_CAP = 30;

    private final Map<Skill.Skills, Integer> caps = new EnumMap<>(Skill.Skills.class);

    public SkillCapManager() {
        addRandomCap(Skill.Skills.ATTACK);
        addRandomCap(Skill.Skills.STRENGTH);
        addRandomCap(Skill.Skills.DEFENCE);
        caps.put(Skill.Skills.RANGED, RANGED_CAP);
        caps.put(Skill.Skills.MAGIC, MAGIC_CAP);
        addRandomCap(Skill.Skills.WOODCUTTING);
        addRandomCap(Skill.Skills.FIREMAKING);
        addRandomCap(Skill.Skills.FISHING);
        addRandomCap(Skill.Skills.COOKING);
        addRandomCap(Skill.Skills.MINING);
        addRandomCap(Skill.Skills.SMITHING);
        caps.put(Skill.Skills.CRAFTING, randomCap(MIN_CRAFTING_CAP, MAX_CRAFTING_CAP));
    }

    public int capFor(Skill.Skills skill) {
        return caps.computeIfAbsent(skill, ignored -> randomCap());
    }

    public boolean isComplete(APIContext ctx, Skill.Skills skill) {
        return ctx.skills().get(skill).getRealLevel() >= capFor(skill);
    }

    public int levelsRemaining(APIContext ctx, Skill.Skills skill) {
        return Math.max(0, capFor(skill) - ctx.skills().get(skill).getRealLevel());
    }

    public String describeCaps() {
        return "Skill caps: Attack " + capFor(Skill.Skills.ATTACK)
                + ", Strength " + capFor(Skill.Skills.STRENGTH)
                + ", Defence " + capFor(Skill.Skills.DEFENCE)
                + ", Ranged " + capFor(Skill.Skills.RANGED)
                + ", Magic " + capFor(Skill.Skills.MAGIC)
                + ", WC " + capFor(Skill.Skills.WOODCUTTING)
                + ", FM " + capFor(Skill.Skills.FIREMAKING)
                + ", Fishing " + capFor(Skill.Skills.FISHING)
                + ", Cooking " + capFor(Skill.Skills.COOKING)
                + ", Mining " + capFor(Skill.Skills.MINING)
                + ", Smithing " + capFor(Skill.Skills.SMITHING)
                + ", Crafting " + capFor(Skill.Skills.CRAFTING);
    }

    private void addRandomCap(Skill.Skills skill) {
        caps.put(skill, randomCap());
    }

    private int randomCap() {
        return randomCap(MIN_CAP, MAX_CAP);
    }

    private int randomCap(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
