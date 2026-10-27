package net.siftvanilla.siftcore.feature.scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.core.combat.CombatTags;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The values of the {@code {placeholders}} in sidebar lines and the tab list header and footer. The scoreboard
 * provides a few itself from the services it is connected to ({@link #LOCAL}); every other name is looked up in
 * SiftCore's placeholder registry, so any feature's placeholder works. Thread-safe: reads thread-safe services and
 * cached registry values only.
 */
final class Values {

    /** Placeholders the scoreboard provides itself. They win over registry placeholders of the same name. */
    static final Set<String> LOCAL = Set.of("player", "online", "max_players", "ping", "rank", "team", "kills", "deaths",
        "streak", "best_streak", "playtime", "combat");

    /** What a player's values are worked out from, besides the player. */
    interface Subject {
        Player player();

        UUID id();

        String name();

        /** The rank label, empty for none. */
        String rankLabel();
    }

    private final Placeholders placeholders;
    private final StatsRecorder stats;
    private final TeamLookup teams;
    private final CombatTags combat;

    Values(Placeholders placeholders, StatsRecorder stats, TeamLookup teams, CombatTags combat) {
        this.placeholders = placeholders;
        this.stats = stats;
        this.teams = teams;
        this.combat = combat;
    }

    /** The values of {@code tokens} for one player, in order; null for a name nothing provides. */
    List<String> resolve(Subject subject, List<String> tokens, int online) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            values.add(value(subject, token, online));
        }
        return values;
    }

    /** One value; null when nothing provides the name. A failing provider counts as missing. */
    String value(Subject subject, String token, int online) {
        try {
            return switch (token) {
                case "player" -> subject.name();
                case "online" -> Integer.toString(online);
                case "max_players" -> Integer.toString(Bukkit.getMaxPlayers());
                case "ping" -> Integer.toString(Math.max(0, subject.player().getPing()));
                case "rank" -> subject.rankLabel();
                case "team" -> this.teams.teamName(subject.id()).orElse("");
                case "kills" -> Lang.number(this.stats.get(subject.id(), StatsRecorder.Stat.KILLS));
                case "deaths" -> Lang.number(this.stats.get(subject.id(), StatsRecorder.Stat.DEATHS));
                case "streak" -> Lang.number(this.stats.streak(subject.id()));
                case "best_streak" -> Lang.number(this.stats.bestStreak(subject.id()));
                case "playtime" -> Durations.format(Duration.ofSeconds(this.stats.get(subject.id(), StatsRecorder.Stat.PLAYTIME_SECONDS)));
                case "combat" -> combatLeft(this.combat.remaining(subject.id()));
                default -> this.placeholders.resolve(subject.player(), token);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whole seconds of combat left, rounded up ("12s"), or empty when the player is not in combat. */
    static String combatLeft(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) {
            return "";
        }
        long seconds = (remaining.toMillis() + 999) / 1000;
        return Durations.format(Duration.ofSeconds(seconds));
    }

    /** Whether something provides this placeholder (the scoreboard itself or a feature's registry entry). */
    boolean known(String token) {
        if (LOCAL.contains(token)) {
            return true;
        }
        try {
            return this.placeholders.resolve(null, token) != null;
        } catch (RuntimeException e) {
            // Registered, but its value needs a player to look at.
            return true;
        }
    }
}
