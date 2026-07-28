package org.example.modules.combat;

import com.epicbot.api.shared.model.Area;

import java.util.Arrays;

public class MobDefinition {
    private final String key;
    private final String primaryName;
    private final String[] names;
    private final Area area;
    private final String[] lootNames;

    public MobDefinition(String key, String primaryName, Area area, String[] lootNames, String... aliases) {
        this.key = key;
        this.primaryName = primaryName;
        this.area = area;
        this.lootNames = lootNames.clone();
        this.names = buildNames(primaryName, aliases);
    }

    public String key() {
        return key;
    }

    public String primaryName() {
        return primaryName;
    }

    public String[] names() {
        return names.clone();
    }

    public Area area() {
        return area;
    }

    public String[] lootNames() {
        return lootNames.clone();
    }

    private String[] buildNames(String primaryName, String[] aliases) {
        String[] result = Arrays.copyOf(aliases, aliases.length + 1);
        result[0] = primaryName;
        System.arraycopy(aliases, 0, result, 1, aliases.length);
        return result;
    }
}
