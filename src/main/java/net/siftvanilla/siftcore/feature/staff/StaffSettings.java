package net.siftvanilla.siftcore.feature.staff;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Durations;

/**
 * Parsed {@code features/staff.yml}.
 *
 * @param vanishReminder        how often vanished staff see the reminder on the action bar
 * @param freezeReminder        how often frozen players see the reminder on the action bar
 * @param freezeAllowedCommands command labels a frozen player may still use
 * @param freezeBanOnLogout     whether logging out while frozen bans the player
 * @param freezeBanLength       how long that ban lasts, null for permanent
 * @param freezeBanReason       the reason stored with that ban
 * @param muteBlockedCommands   command labels a muted player can't use
 * @param appeal                the appeal line on the ban screen
 * @param maxLength             the longest temporary ban or mute
 * @param reports               report limits
 * @param historyPageSize       entries per page in /history
 * @param reportsPageSize       reports per page in /reports
 * @param clearChatLines        blank lines sent by /clearchat
 */
public record StaffSettings(
    Duration vanishReminder,
    Duration freezeReminder,
    Set<String> freezeAllowedCommands,
    boolean freezeBanOnLogout,
    Duration freezeBanLength,
    String freezeBanReason,
    Set<String> muteBlockedCommands,
    String appeal,
    Duration maxLength,
    ReportRules.Limits reports,
    int historyPageSize,
    int reportsPageSize,
    int clearChatLines) {

    private static final List<String> DEFAULT_FREEZE_ALLOWED = List.of("msg", "r", "reply", "tell", "w", "whisper");
    private static final List<String> DEFAULT_MUTE_BLOCKED = List.of("me", "say", "tell", "msg", "w", "whisper", "r",
        "reply", "teammsg", "tm");

    public static StaffSettings parse(ConfigReader r) {
        ConfigReader vanish = r.section("vanish");
        Duration vanishReminder = vanish.duration("reminder-interval", Duration.ofSeconds(1), Duration.ofMinutes(1),
            Duration.ofSeconds(3));

        ConfigReader freeze = r.section("freeze");
        Duration freezeReminder = freeze.duration("reminder-interval", Duration.ofSeconds(1), Duration.ofMinutes(1),
            Duration.ofSeconds(2));
        Set<String> allowed = labels(freeze, "allowed-commands", DEFAULT_FREEZE_ALLOWED);
        ConfigReader logout = freeze.section("ban-on-logout");
        boolean banOnLogout = logout.bool("enabled", false);
        Duration banLength = logout.custom("duration", value -> {
            String text = value.strip().toLowerCase(Locale.ROOT);
            if (text.equals("permanent")) {
                return null;
            }
            DurationInput.Problem problem = DurationInput.check(text, Duration.ofDays(3650));
            if (problem != DurationInput.Problem.NONE) {
                throw new IllegalArgumentException("is not a time like 7d or the word permanent");
            }
            return Durations.parse(text);
        }, "a time like 7d, or permanent", null);
        String banReason = CleanText.clean(logout.string("reason", "Logged out while frozen"));
        if (banReason.length() > DurationInput.MAX_REASON) {
            logout.problem("reason", "must be at most " + DurationInput.MAX_REASON + " characters");
            banReason = "Logged out while frozen";
        }

        ConfigReader mutes = r.section("mutes");
        Set<String> blocked = labels(mutes, "blocked-commands", DEFAULT_MUTE_BLOCKED);

        ConfigReader bans = r.section("bans");
        String appeal = bans.string("appeal", "Appeal on our Discord: discord.gg/siftvanilla");

        ConfigReader punishments = r.section("punishments");
        Duration maxLength = punishments.duration("max-duration", Duration.ofMinutes(1), Duration.ofDays(3650),
            Duration.ofDays(3650));

        ConfigReader reports = r.section("reports");
        Duration cooldown = reports.duration("cooldown", Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(60));
        int min = reports.integer("reason-min-length", 1, 50, 3);
        int max = reports.integer("reason-max-length", 10, 120, 100);
        if (max < min) {
            reports.problem("reason-max-length", "must not be smaller than reason-min-length");
            min = 3;
            max = 100;
        }
        int openPerPlayer = reports.integer("max-open-per-player", 1, 50, 5);
        int reportsPage = reports.integer("page-size", 2, 12, 6);

        int historyPage = r.section("history").integer("page-size", 3, 12, 6);
        int clearLines = r.section("clear-chat").integer("lines", 20, 300, 100);

        return new StaffSettings(vanishReminder, freezeReminder, allowed, banOnLogout, banLength, banReason, blocked,
            appeal, maxLength, new ReportRules.Limits(min, max, openPerPlayer, cooldown), historyPage, reportsPage, clearLines);
    }

    private static Set<String> labels(ConfigReader section, String path, List<String> fallback) {
        List<String> raw = section.stringList(path, fallback);
        Set<String> result = new LinkedHashSet<>();
        for (String entry : raw) {
            String label = entry.strip().toLowerCase(Locale.ROOT);
            while (label.startsWith("/")) {
                label = label.substring(1);
            }
            if (!label.matches("[a-z0-9_.-]{1,32}")) {
                section.problem(path, "contains '" + entry + "'; use command names without the slash, like msg");
                continue;
            }
            result.add(label);
        }
        return Set.copyOf(result);
    }
}
