package org.example.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public class SkillCapManager {
    private static final int MIN_CAP = 29;
    private static final int MAX_CAP = 35;
    private static final int MIN_COMBAT_CAP = 30;
    private static final int MAX_COMBAT_CAP = 33;
    private static final int MIN_MAGIC_CAP = 22;
    private static final int MAX_MAGIC_CAP = 25;
    private static final int CRAFTING_CAP = 15;
    private static final Skill.Skills[] COMBAT_SKILLS = {
            Skill.Skills.ATTACK, Skill.Skills.STRENGTH, Skill.Skills.DEFENCE,
            Skill.Skills.RANGED, Skill.Skills.MAGIC
    };

    private final Map<Skill.Skills, Integer> caps = new EnumMap<>(Skill.Skills.class);
    private final Path accountCapsDirectory;
    private String activeAccount;

    public SkillCapManager() {
        this(Path.of(System.getProperty("user.home"), "EpicBot", "f2p-goals", "account-caps"));
    }

    public SkillCapManager(Path accountCapsDirectory) {
        this.accountCapsDirectory = accountCapsDirectory;
        for (Skill.Skills skill : COMBAT_SKILLS) {
            caps.put(skill, randomCapFor(skill));
        }
        addRandomCap(Skill.Skills.WOODCUTTING);
        addRandomCap(Skill.Skills.FIREMAKING);
        addRandomCap(Skill.Skills.FISHING);
        addRandomCap(Skill.Skills.COOKING);
        addRandomCap(Skill.Skills.MINING);
        addRandomCap(Skill.Skills.SMITHING);
        caps.put(Skill.Skills.CRAFTING, CRAFTING_CAP);
    }

    public static String accountIdentifier(APIContext ctx) {
        String username = ctx.client().getUsername();
        return normalizeAccount(username == null || username.isBlank()
                ? ctx.localPlayer().getName() : username);
    }

    public boolean isInitializedForAccount(String accountIdentifier) {
        String normalized = normalizeAccount(accountIdentifier);
        return !normalized.isEmpty() && normalized.equals(activeAccount);
    }

    public synchronized boolean initializeAccount(String accountIdentifier) {
        String account = normalizeAccount(accountIdentifier);
        if (account.isEmpty() || isInitializedForAccount(account)) {
            return false;
        }

        Path profile = accountCapsDirectory.resolve(accountKey(account) + ".properties");
        Properties savedCaps = new Properties();
        Map<Skill.Skills, Integer> accountCaps = new EnumMap<>(Skill.Skills.class);
        try {
            Files.createDirectories(accountCapsDirectory);
            if (Files.exists(profile)) {
                try (InputStream input = Files.newInputStream(profile)) {
                    savedCaps.load(input);
                }
            }

            boolean changed = false;
            for (Skill.Skills skill : COMBAT_SKILLS) {
                int saved = savedCap(savedCaps, skill);
                if (!validCombatCap(skill, saved)) {
                    saved = randomCapFor(skill);
                    savedCaps.setProperty(skill.name(), Integer.toString(saved));
                    changed = true;
                }
                accountCaps.put(skill, saved);
            }

            if (changed) {
                saveAccountCaps(profile, savedCaps);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load or save account combat caps", exception);
        }

        caps.putAll(accountCaps);
        activeAccount = account;
        return true;
    }

    public int capFor(Skill.Skills skill) {
        return caps.computeIfAbsent(skill, this::randomCapFor);
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

    private static String normalizeAccount(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }

    private static String accountKey(String account) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(account.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int savedCap(Properties properties, Skill.Skills skill) {
        try {
            return Integer.parseInt(properties.getProperty(skill.name(), ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean validCombatCap(Skill.Skills skill, int cap) {
        return skill == Skill.Skills.MAGIC
                ? cap >= MIN_MAGIC_CAP && cap <= MAX_MAGIC_CAP
                : cap >= MIN_COMBAT_CAP && cap <= MAX_COMBAT_CAP;
    }

    private void saveAccountCaps(Path profile, Properties properties) throws IOException {
        Path temporary = Files.createTempFile(accountCapsDirectory, "combat-caps-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "F2P Goals account combat caps");
            }
            try {
                Files.move(temporary, profile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, profile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void addRandomCap(Skill.Skills skill) {
        caps.put(skill, randomCapFor(skill));
    }

    private int randomCapFor(Skill.Skills skill) {
        if (skill == Skill.Skills.MAGIC) {
            return randomCap(MIN_MAGIC_CAP, MAX_MAGIC_CAP);
        }
        if (skill == Skill.Skills.ATTACK || skill == Skill.Skills.STRENGTH
                || skill == Skill.Skills.DEFENCE || skill == Skill.Skills.RANGED) {
            return randomCap(MIN_COMBAT_CAP, MAX_COMBAT_CAP);
        }
        return randomCap(MIN_CAP, MAX_CAP);
    }

    private int randomCap(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
