package net.siftvanilla.siftcore.feature.combat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.siftvanilla.siftcore.api.event.CombatLogEvent;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/combat.yml}. */
record CombatSettings(
    Duration tagDuration,
    boolean tagPets,
    boolean actionBar,
    CommandFilter blockedCommands,
    boolean blockEnderPearls,
    boolean disableElytra,
    boolean blockSpawnEntry,
    CombatLogEvent.Punishment logoutPunishment,
    boolean punishKicks,
    boolean announceLogout,
    boolean deathMessages,
    boolean showWeapon,
    Streaks streaks,
    AntiFarm.Rules antiFarm) {

    /** The longest repeated-pair cooldown; the kill log is read back this far at startup. */
    static final Duration MAX_PAIR_COOLDOWN = Duration.ofHours(24);

    /** The longest friends window (the friends feature remembers removals for its own anti-farm.remember, 7d). */
    static final Duration MAX_FRIENDS_WINDOW = Duration.ofDays(30);

    /** The highest streak that can be named in the config. */
    static final int MAX_STREAK = 100_000;

    /** The commands refused in combat when the config does not list any. */
    static final List<String> DEFAULT_BLOCKED_COMMANDS = List.of(
        "spawn", "home", "homes", "sethome", "tpa", "tpahere", "tpaccept", "back", "rtp", "wild", "warp", "warps",
        "team home", "afk", "ec", "enderchest", "craft", "workbench", "anvil", "kit", "kits", "shop", "shardshop", "ah");

    /** The kill streaks announced by default. */
    static final List<Integer> DEFAULT_STREAKS = List.of(5, 10, 15, 20, 25, 30, 40, 50, 75, 100);

    /**
     * Kill streak announcements.
     *
     * @param announceAt   streaks that are announced when a player reaches them (sorted, may be empty)
     * @param endedFrom    a streak at least this long is announced when someone ends it; 0 turns that off
     */
    record Streaks(List<Integer> announceAt, int endedFrom) {

        Streaks {
            announceAt = List.copyOf(new TreeSet<>(announceAt));
        }

        static final Streaks OFF = new Streaks(List.of(), 0);

        /** True when reaching {@code streak} is announced. */
        boolean reached(int streak) {
            return streak > 0 && this.announceAt.contains(streak);
        }

        /** True when ending a streak of {@code streak} is announced. */
        boolean ended(int streak) {
            return this.endedFrom > 0 && streak >= this.endedFrom;
        }
    }

    static CombatSettings parse(ConfigReader r) {
        ConfigReader tag = r.section("tag");
        ConfigReader tagged = r.section("while-tagged");
        ConfigReader logout = r.section("logout");
        ConfigReader death = r.section("death-messages");
        ConfigReader streaks = r.section("streaks");
        ConfigReader farm = r.section("anti-farm");
        return new CombatSettings(
            tag.duration("duration", Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(20)),
            tag.bool("pets", true),
            tag.bool("action-bar", true),
            commands(tagged),
            tagged.bool("block-ender-pearls", false),
            tagged.bool("disable-elytra", true),
            tagged.bool("block-spawn-entry", true),
            logout.enumValue("punishment", CombatLogEvent.Punishment.class, CombatLogEvent.Punishment.KILL),
            logout.bool("punish-kicks", true),
            logout.bool("announce", true),
            death.bool("enabled", true),
            death.bool("show-weapon", true),
            new Streaks(milestones(streaks), streaks.integer("announce-ended-from", 0, MAX_STREAK, 5)),
            new AntiFarm.Rules(
                farm.bool("same-team", true),
                farm.bool("friends", true),
                farm.bool("same-ip", true),
                farm.duration("repeated-pair-cooldown", Duration.ZERO, MAX_PAIR_COOLDOWN, Duration.ofMinutes(10)),
                !farm.has("friends-window") ? AntiFarm.Rules.DEFAULT_FRIENDS_WINDOW
                    : farm.duration("friends-window", Duration.ZERO, MAX_FRIENDS_WINDOW, AntiFarm.Rules.DEFAULT_FRIENDS_WINDOW)));
    }

    private static CommandFilter commands(ConfigReader tagged) {
        List<String> entries = tagged.stringList("blocked-commands", DEFAULT_BLOCKED_COMMANDS);
        List<CommandFilter.Rule> rules = new ArrayList<>(entries.size());
        for (String entry : entries) {
            try {
                rules.add(CommandFilter.Rule.parse(entry));
            } catch (IllegalArgumentException e) {
                tagged.problem("blocked-commands", "entry '" + entry + "' " + e.getMessage()
                    + " (use a command name like home, or a command and subcommand like team home)");
            }
        }
        return new CommandFilter(rules);
    }

    private static List<Integer> milestones(ConfigReader streaks) {
        List<String> entries = streaks.stringList("announce-at", DEFAULT_STREAKS.stream().map(String::valueOf).toList());
        List<Integer> milestones = new ArrayList<>(entries.size());
        for (String entry : entries) {
            int value;
            try {
                value = Integer.parseInt(entry.strip());
            } catch (NumberFormatException e) {
                value = -1;
            }
            if (value < 1 || value > MAX_STREAK) {
                streaks.problem("announce-at", "entry '" + entry + "' is not a whole number from 1 to " + MAX_STREAK);
                continue;
            }
            milestones.add(value);
        }
        return milestones;
    }
}
