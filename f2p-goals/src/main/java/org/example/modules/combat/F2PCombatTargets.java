package org.example.modules.combat;

import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class F2PCombatTargets {
    public static final MobDefinition CHICKENS = new MobDefinition(
            "chickens",
            "Chicken",
            new Area(
                    new Tile(3226, 3301, 0),
                    new Tile(3225, 3299, 0),
                    new Tile(3225, 3295, 0),
                    new Tile(3235, 3294, 0),
                    new Tile(3236, 3294, 0),
                    new Tile(3235, 3301, 0),
                    new Tile(3226, 3301, 0)
            ),
            new String[]{"Bones", "Feather", "Feathers", "Raw chicken", "Coins"}
    );
    public static final MobDefinition GOBLINS = new MobDefinition(
            "goblins",
            "Goblin",
            new Area(
                    new Tile(3241, 3242, 0),
                    new Tile(3242, 3235, 0),
                    new Tile(3246, 3230, 0),
                    new Tile(3252, 3228, 0),
                    new Tile(3258, 3229, 0),
                    new Tile(3259, 3237, 0),
                    new Tile(3258, 3242, 0),
                    new Tile(3247, 3241, 0),
                    new Tile(3241, 3241, 0)
            ),
            new String[]{"Bones", "Coins", "Bronze spear", "Iron dagger"}
    );
    public static final MobDefinition COWS = new MobDefinition(
            "cows",
            "Cow",
            new Area(3247, 3255, 3268, 3300),
            new String[]{"Bones", "Coins"},
            "Cow calf"
    );
    public static final MobDefinition MONKS = new MobDefinition(
            "monks",
            "Monk",
            new Area(3044, 3481, 3063, 3500),
            new String[]{"Bones", "Coins"}
    );
    public static final MobDefinition GIANT_RATS = new MobDefinition(
            "giant_rats",
            "Giant rat",
            new Area(3160, 3158, 3228, 3194),
            new String[]{"Bones", "Raw rat meat", "Coins"}
    );
    public static final MobDefinition GIANT_FROGS = new MobDefinition(
            "giant_frogs",
            "Giant frog",
            new Area(3188, 3168, 3205, 3207),
            new String[]{"Coins", "Big bones"},
            "Frog"
    );
    public static final MobDefinition EDGE_ZOMBIES = new MobDefinition(
            "edge_zombies",
            "Zombie",
            new Area(3140, 9895, 3152, 9907),
            new String[]{"Bones", "Coins", "Iron dagger", "Bronze axe"}
    );
    public static final MobDefinition EDGE_SKELETONS = new MobDefinition(
            "edge_skeletons",
            "Skeleton",
            new Area(3092, 9903, 3104, 9915),
            new String[]{"Bones", "Coins"}
    );
    public static final MobDefinition AL_KHARID_SCORPIONS = new MobDefinition(
            "al_kharid_scorpions",
            "Scorpion",
            new Area(3289, 3278, 3316, 3301),
            new String[]{"Bones"}
    );
    public static final MobDefinition BARBARIANS = new MobDefinition(
            "barbarians",
            "Barbarian",
            new Area(3072, 3412, 3088, 3432),
            new String[]{"Bones", "Coins", "Mead", "Steel axe", "Iron sword"}
    );
    public static final MobDefinition EDGE_HOBGOBLINS = new MobDefinition(
            "edge_hobgoblins",
            "Hobgoblin",
            new Area(3119, 9871, 3131, 9883),
            new String[]{"Bones", "Coins", "Ensouled goblin head", "Iron sq shield", "Iron square shield"}
    );
    public static final MobDefinition FALADOR_GUARDS = new MobDefinition(
            "falador_guards",
            "Guard",
            new Area(2960, 3368, 2990, 3390),
            new String[]{"Bones", "Coins", "Iron dagger", "Bronze longsword"}
    );
    public static final MobDefinition EDGE_HILL_GIANTS = new MobDefinition(
            "edge_hill_giants",
            "Hill Giant",
            new Area(3110, 9834, 3122, 9846),
            new String[]{"Big bones", "Limpwurt root", "Coins", "Uncut sapphire", "Uncut emerald", "Uncut ruby", "Giant key"}
    );

    private static final Map<CombatPhase, List<MobDefinition>> TARGETS_BY_PHASE = Map.of(
            CombatPhase.EARLY, List.of(CHICKENS, GOBLINS, COWS, MONKS),
            CombatPhase.MID, List.of(GIANT_RATS, GIANT_FROGS, GOBLINS, COWS, EDGE_ZOMBIES, EDGE_SKELETONS),
            CombatPhase.LATE, List.of(AL_KHARID_SCORPIONS, BARBARIANS, EDGE_HOBGOBLINS, FALADOR_GUARDS),
            CombatPhase.ENDGAME, List.of(EDGE_HILL_GIANTS, FALADOR_GUARDS, EDGE_HOBGOBLINS)
    );

    private F2PCombatTargets() {
    }

    public static List<MobDefinition> forLevel(int trainedSkillLevel) {
        return forPhase(CombatPhase.forSkillLevel(trainedSkillLevel));
    }

    public static List<MobDefinition> forPhase(CombatPhase phase) {
        return TARGETS_BY_PHASE.getOrDefault(phase, TARGETS_BY_PHASE.get(CombatPhase.EARLY));
    }

    public static MobDefinition pickRandomForLevel(int trainedSkillLevel, MobDefinition previous) {
        CombatPhase phase = CombatPhase.forSkillLevel(trainedSkillLevel);
        List<MobDefinition> options = forPhase(phase);
        MobDefinition picked = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        if (previous != null && options.size() > 1) {
            int attempts = 0;
            while (picked.key().equals(previous.key()) && attempts++ < 5) {
                picked = options.get(ThreadLocalRandom.current().nextInt(options.size()));
            }
        }
        return picked;
    }
}
