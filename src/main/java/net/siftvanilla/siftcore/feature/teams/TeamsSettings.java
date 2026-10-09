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
 * @param memberAlerts       timing of teammate login alerts ({@code member-alerts})
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
    Set<String> homeDisabledWorlds,
    MemberAlerts memberAlerts) {

    /**
     * When teammates hear about a member's login and logout (each player picks whether in Settings).
     *
     * @param joinDelay    wait after a member joins before teammates are told (a vanish applied on join is in effect)
     * @param leaveDelay   wait before a logout is told; nothing is told when the member is back by then
     * @param relogGrace   a member who left less than this ago rejoins without an alert
     * @param startupQuiet no login alerts this long after the server starts
     */
    public record MemberAlerts(Duration joinDelay, Duration leaveDelay, Duration relogGrace, Duration startupQuiet) {

        /** The shipped timing. */
        public static final MemberAlerts DEFAULTS = new MemberAlerts(Duration.ofSeconds(3), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofSeconds(60));
    }

    public TeamsSettings {
        blockedWords = List.copyOf(blockedWords);
        homeDisabledWorlds = Set.copyOf(homeDisabledWorlds);
        memberAlerts = memberAlerts == null ? MemberAlerts.DEFAULTS : memberAlerts;
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
        ConfigReader alerts = r.section("member-alerts");
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
            disabled,
            new MemberAlerts(
                alerts.duration("join-delay", Duration.ofMillis(50), Duration.ofMinutes(1), MemberAlerts.DEFAULTS.joinDelay()),
                alerts.duration("leave-delay", Duration.ZERO, Duration.ofMinutes(10), MemberAlerts.DEFAULTS.leaveDelay()),
                alerts.duration("relog-grace", Duration.ZERO, Duration.ofHours(1), MemberAlerts.DEFAULTS.relogGrace()),
                alerts.duration("startup-quiet", Duration.ZERO, Duration.ofMinutes(10), MemberAlerts.DEFAULTS.startupQuiet())));
    }
}
