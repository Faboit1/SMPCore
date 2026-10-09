package net.siftvanilla.siftcore.feature.teams;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/teams.yml}.
 *
 * @param homeDisabledWorlds worlds where team homes can't be set or used ({@code home.disabled-worlds})
 */
public record TeamsSettings(
    long createCost,
    int nameMinLength,
    int nameMaxLength,
    List<String> blockedWords,
    int defaultMemberLimit,
    Duration inviteExpiry,
    int maxOpenInvites,
    Duration inviteCooldown,
    Duration homeWarmup,
    boolean friendlyFireDefault,
    boolean protectMembers,
    boolean chatToConsole,
    Duration topRefresh,
    int topSize,
    int pageSize,
    Set<String> homeDisabledWorlds) {

    public TeamsSettings {
        blockedWords = List.copyOf(blockedWords);
        homeDisabledWorlds = Set.copyOf(homeDisabledWorlds);
    }

    /** Whether team homes are turned off in this world. */
    public boolean homeDisabled(String world) {
        return this.homeDisabledWorlds.contains(world);
    }

    /** @param worldExists whether a world with that name is loaded (a disabled world must exist) */
    public static TeamsSettings parse(ConfigReader r, MoneyFormat money, Predicate<String> worldExists) {
        ConfigReader create = r.section("create");
        ConfigReader names = r.section("names");
        ConfigReader members = r.section("members");
        ConfigReader invites = r.section("invites");
        ConfigReader home = r.section("home");
        ConfigReader ff = r.section("friendly-fire");
        ConfigReader chat = r.section("chat");
        ConfigReader top = r.section("leaderboard");
        int min = names.integer("min-length", 1, TeamNames.MAX_LENGTH, 3);
        int max = names.integer("max-length", 1, TeamNames.MAX_LENGTH, TeamNames.MAX_LENGTH);
        if (max < min) {
            names.problem("max-length", "must be at least min-length (" + min + ")");
            max = Math.max(min, TeamNames.MAX_LENGTH);
        }
        Set<String> disabled = new LinkedHashSet<>();
        for (String world : home.optionalStringList("disabled-worlds")) {
            if (!worldExists.test(world)) {
                home.problem("disabled-worlds", "contains '" + world + "', but no world with that name is loaded");
            }
            disabled.add(world);
        }
        return new TeamsSettings(
            create.money("cost", money, true, 50_000),
            min,
            max,
            TeamNames.cleanBlockList(names.stringList("blocked-words", List.of())),
            members.integer("default-limit", 1, 1000, 5),
            invites.duration("expire-after", Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofMinutes(2)),
            invites.integer("max-open", 1, 100, 10),
            invites.duration("cooldown", Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(3)),
            home.duration("warmup", Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(5)),
            ff.bool("default", false),
            ff.bool("protect-members", true),
            chat.bool("log-to-console", true),
            top.duration("refresh", Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofSeconds(60)),
            top.integer("size", 3, 100, 10),
            r.integer("page-size", 5, 20, 10),
            disabled);
    }
}
