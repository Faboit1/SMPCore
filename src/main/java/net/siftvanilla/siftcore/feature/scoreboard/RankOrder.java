package net.siftvanilla.siftcore.feature.scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The server's ranks from highest to lowest (features/scoreboard.yml {@code ranks.order}), and how a player's rank is
 * worked out from what the rank integration says and which LuckPerms groups they are in. Pure: the permission check
 * is passed in.
 *
 * @param ranks group name and fallback label, highest first
 */
public record RankOrder(List<Rank> ranks) {

    /** A rank: its LuckPerms group and the label shown when the rank integration gives none. */
    public record Rank(String group, String label) {
    }

    /**
     * A player's rank as the tab list and nametags show it.
     *
     * @param group the matched group, or null when none of the listed groups applies
     * @param label the plain-text label, empty for none
     * @param order the position in the rank order (0 is the highest); unranked players come last
     */
    public record PlayerRank(String group, String label, int order) {

        public static final PlayerRank NONE = new PlayerRank(null, "", Integer.MAX_VALUE);
    }

    public RankOrder {
        ranks = List.copyOf(ranks);
    }

    /** SiftVanilla's ranks: the paid tiers tycoon, baron and prospector, then default. */
    public static RankOrder defaults() {
        return new RankOrder(List.of(new Rank("tycoon", "Tycoon"), new Rank("baron", "Baron"), new Rank("prospector", "Prospector"),
            new Rank("default", "")));
    }

    /** The permission LuckPerms gives every member of a group (inherited groups included). */
    public static String groupPermission(String group) {
        return "group." + group;
    }

    /**
     * Works out a player's rank.
     * <ol>
     *   <li>The group: the primary group the rank integration reports, when it is listed and not {@code default};
     *       otherwise the highest listed group the player belongs to ({@code inGroup} checks the group permission);
     *       otherwise {@code default} when the integration reports it and it is listed.</li>
     *   <li>The label: the integration's label when it gives one, otherwise the listed label of the group.</li>
     * </ol>
     *
     * @param providerLabel the rank integration's label (empty for none)
     * @param providerGroup the rank integration's primary group ({@code default} when unknown)
     * @param inGroup       whether the player is in a group, by name
     */
    public PlayerRank resolve(String providerLabel, String providerGroup, Predicate<String> inGroup) {
        String primary = providerGroup == null ? "" : providerGroup.toLowerCase(Locale.ROOT);
        int index = primary.equals("default") ? -1 : indexOf(primary);
        if (index < 0) {
            for (int i = 0; i < this.ranks.size(); i++) {
                if (inGroup.test(this.ranks.get(i).group())) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0 && primary.equals("default")) {
            index = indexOf("default");
        }
        String label = providerLabel == null ? "" : providerLabel.strip();
        if (index < 0) {
            return label.isEmpty() ? PlayerRank.NONE : new PlayerRank(null, label, this.ranks.size());
        }
        Rank rank = this.ranks.get(index);
        return new PlayerRank(rank.group(), label.isEmpty() ? rank.label() : label, index);
    }

    /**
     * How a player who turned {@code show-my-rank} off is shown: exactly like a member of the {@code default} group
     * (its listed label, team and tab list order), or unranked when {@code default} is not listed. So nothing in the
     * tab list, nametags or team order tells them apart from an ordinary player.
     */
    public PlayerRank hidden() {
        int index = indexOf("default");
        return index < 0 ? PlayerRank.NONE : new PlayerRank("default", this.ranks.get(index).label(), index);
    }

    private int indexOf(String group) {
        for (int i = 0; i < this.ranks.size(); i++) {
            if (this.ranks.get(i).group().equals(group)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The tab list order of a rank: higher ranks get a higher number (the client lists higher numbers first);
     * unranked players get 0.
     */
    public int listOrder(PlayerRank rank) {
        if (rank.order() >= this.ranks.size()) {
            return 0;
        }
        return this.ranks.size() - rank.order();
    }

    /** The group names, for messages. */
    public List<String> groups() {
        List<String> groups = new ArrayList<>(this.ranks.size());
        for (Rank rank : this.ranks) {
            groups.add(rank.group());
        }
        return groups;
    }
}
