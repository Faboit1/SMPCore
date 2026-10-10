package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;

/**
 * The steps that turn the nametag teams a board shows into the teams it should show. Applied in order: removed teams
 * first, then players leaving teams, new teams, prefix changes and finally players joining (a player moving between
 * teams leaves one before joining the other). Pure: no server calls.
 */
public final class TeamDiff {

    private TeamDiff() {
    }

    /** A team as a board shows it: the prefix in front of its members' names, and the members. */
    public record View(Component prefix, Set<String> members) {

        public View {
            members = Set.copyOf(members);
        }
    }

    /** One step. */
    public sealed interface Op permits Remove, Leave, Create, Prefix, Join {
        String team();
    }

    public record Remove(String team) implements Op {
    }

    public record Leave(String team, List<String> players) implements Op {
    }

    public record Create(String team, Component prefix) implements Op {
    }

    public record Prefix(String team, Component prefix) implements Op {
    }

    public record Join(String team, List<String> players) implements Op {
    }

    /** The steps from {@code current} to {@code wanted}; both map team names to views. Empty when they are equal. */
    public static List<Op> between(Map<String, View> current, Map<String, View> wanted) {
        List<Op> removes = new ArrayList<>();
        List<Op> leaves = new ArrayList<>();
        List<Op> creates = new ArrayList<>();
        List<Op> prefixes = new ArrayList<>();
        List<Op> joins = new ArrayList<>();
        for (String team : new TreeSet<>(current.keySet())) {
            if (!wanted.containsKey(team)) {
                removes.add(new Remove(team));
            }
        }
        for (String team : new TreeSet<>(wanted.keySet())) {
            View want = wanted.get(team);
            View have = current.get(team);
            if (have == null) {
                creates.add(new Create(team, want.prefix()));
                if (!want.members().isEmpty()) {
                    joins.add(new Join(team, sorted(want.members(), Set.of())));
                }
                continue;
            }
            List<String> leaving = sorted(have.members(), want.members());
            if (!leaving.isEmpty()) {
                leaves.add(new Leave(team, leaving));
            }
            if (!Objects.equals(have.prefix(), want.prefix())) {
                prefixes.add(new Prefix(team, want.prefix()));
            }
            List<String> joining = sorted(want.members(), have.members());
            if (!joining.isEmpty()) {
                joins.add(new Join(team, joining));
            }
        }
        List<Op> ops = new ArrayList<>(removes.size() + leaves.size() + creates.size() + prefixes.size() + joins.size());
        ops.addAll(removes);
        ops.addAll(leaves);
        ops.addAll(creates);
        ops.addAll(prefixes);
        ops.addAll(joins);
        return ops;
    }

    /** The members of {@code from} that are not in {@code except}, sorted. */
    private static List<String> sorted(Set<String> from, Set<String> except) {
        TreeSet<String> result = new TreeSet<>(from);
        result.removeAll(except);
        return List.copyOf(result);
    }
}
