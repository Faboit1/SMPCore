package net.siftvanilla.siftcore.feature.scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/**
 * Parsed {@code features/scoreboard.yml}.
 *
 * @param sidebarEnabled  whether anyone gets a sidebar
 * @param sidebarRefresh  how often the sidebar lines are refreshed
 * @param lines           the sidebar lines from top to bottom: a line name from {@link ScoreboardMessages#LINES} or
 *                        {@link #BLANK}
 * @param tabEnabled      whether the tab list header and footer are set
 * @param tabRefresh      how often the header and footer are refreshed
 * @param tabNames        whether tab list names show the rank (and are sorted by it)
 * @param afkMarker       whether AFK players are marked in the tab list
 * @param nametagsEnabled whether rank labels are shown in front of names above heads
 * @param rankRefresh     how often ranks are read again
 * @param ranks           the ranks from highest to lowest
 * @param sidebarYieldTo  plugins that show the sidebar instead while they run
 * @param tabYieldTo      plugins that run the tab list instead while they run
 * @param nametagsYieldTo plugins that run nametags instead while they run
 */
public record ScoreboardSettings(
    boolean sidebarEnabled,
    Duration sidebarRefresh,
    List<String> lines,
    boolean tabEnabled,
    Duration tabRefresh,
    boolean tabNames,
    boolean afkMarker,
    boolean nametagsEnabled,
    Duration rankRefresh,
    RankOrder ranks,
    List<String> sidebarYieldTo,
    List<String> tabYieldTo,
    List<String> nametagsYieldTo) {

    /** The line name of an empty line. */
    public static final String BLANK = "blank";
    /** The default sidebar. */
    public static final List<String> DEFAULT_LINES = List.of(BLANK, "balance", "shards", "kills", "deaths", "playtime", "team",
        BLANK, "website");
    /** LuckPerms group names: lowercase letters, digits, - and _ (a dot would split the YAML key). */
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_-]{1,36}");
    private static final int MAX_LABEL = 32;
    /** Plugin names as paper-plugin.yml allows them. */
    private static final Pattern PLUGIN = Pattern.compile("[A-Za-z0-9 _.-]{1,64}");
    /** The plugin SiftVanilla used for the tab list, nametags and sidebar before SiftCore. */
    public static final List<String> DEFAULT_YIELD_TO = List.of("TAB");

    public ScoreboardSettings {
        lines = List.copyOf(lines);
        sidebarYieldTo = List.copyOf(sidebarYieldTo);
        tabYieldTo = List.copyOf(tabYieldTo);
        nametagsYieldTo = List.copyOf(nametagsYieldTo);
    }

    /**
     * Which parts another plugin shows instead: for each part, the first of its {@code yield-to} plugins that runs, or
     * null when SiftCore shows it.
     */
    public record Yielded(String sidebar, String tab, String nametags) {

        public static final Yielded NONE = new Yielded(null, null, null);

        public boolean any() {
            return this.sidebar != null || this.tab != null || this.nametags != null;
        }
    }

    /** Works out which parts to leave to other plugins; {@code running} says whether a plugin is enabled. */
    public Yielded yielded(Predicate<String> running) {
        return new Yielded(first(this.sidebarYieldTo, running), first(this.tabYieldTo, running), first(this.nametagsYieldTo, running));
    }

    private static String first(List<String> plugins, Predicate<String> running) {
        for (String plugin : plugins) {
            if (running.test(plugin)) {
                return plugin;
            }
        }
        return null;
    }

    /** These settings with every part another plugin shows turned off. */
    public ScoreboardSettings effective(Yielded yielded) {
        if (!yielded.any()) {
            return this;
        }
        boolean tab = yielded.tab() == null;
        return new ScoreboardSettings(this.sidebarEnabled && yielded.sidebar() == null, this.sidebarRefresh, this.lines,
            this.tabEnabled && tab, this.tabRefresh, this.tabNames && tab, this.afkMarker, this.nametagsEnabled && yielded.nametags() == null,
            this.rankRefresh, this.ranks, this.sidebarYieldTo, this.tabYieldTo, this.nametagsYieldTo);
    }

    /** Whether players need a board of their own (for the sidebar or for nametags). */
    public boolean boards() {
        return this.sidebarEnabled || this.nametagsEnabled;
    }

    public static ScoreboardSettings parse(ConfigReader r) {
        ConfigReader sidebar = r.section("sidebar");
        ConfigReader tab = r.section("tab");
        ConfigReader nametags = r.section("nametags");
        ConfigReader ranks = r.section("ranks");
        return new ScoreboardSettings(
            sidebar.bool("enabled", true),
            sidebar.duration("refresh", Duration.ofMillis(250), Duration.ofMinutes(1), Duration.ofSeconds(1)),
            lines(sidebar),
            tab.bool("enabled", true),
            tab.duration("refresh", Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(5)),
            tab.bool("names", true),
            tab.bool("afk-marker", true),
            nametags.bool("enabled", true),
            ranks.duration("refresh", Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(30)),
            rankOrder(ranks),
            plugins(sidebar),
            plugins(tab),
            plugins(nametags));
    }

    private static List<String> plugins(ConfigReader section) {
        List<String> raw = section.stringList("yield-to", DEFAULT_YIELD_TO);
        List<String> plugins = new ArrayList<>(raw.size());
        for (String entry : raw) {
            String name = entry == null ? "" : entry.strip();
            if (!PLUGIN.matcher(name).matches()) {
                section.problem("yield-to", "has '" + entry + "', which is not a plugin name");
                return DEFAULT_YIELD_TO;
            }
            plugins.add(name);
        }
        return plugins;
    }

    private static List<String> lines(ConfigReader sidebar) {
        List<String> raw = sidebar.stringList("lines", DEFAULT_LINES);
        if (raw.size() > SidebarLines.MAX_LINES) {
            sidebar.problem("lines", "has " + raw.size() + " lines; a sidebar shows at most " + SidebarLines.MAX_LINES);
            return DEFAULT_LINES;
        }
        List<String> lines = new ArrayList<>(raw.size());
        List<String> unknown = new ArrayList<>();
        for (String entry : raw) {
            String name = entry == null ? "" : entry.strip().toLowerCase(java.util.Locale.ROOT);
            if (name.equals(BLANK) || ScoreboardMessages.LINES.containsKey(name)) {
                lines.add(name);
            } else {
                unknown.add("'" + entry + "'");
            }
        }
        if (!unknown.isEmpty()) {
            sidebar.problem("lines", "has unknown lines " + String.join(", ", unknown) + " (available: " + BLANK + ", "
                + String.join(", ", ScoreboardMessages.LINES.keySet()) + ")");
            return DEFAULT_LINES;
        }
        return lines;
    }

    private static RankOrder rankOrder(ConfigReader ranks) {
        ConfigReader order = ranks.section("order");
        Set<String> groups = order.keys();
        if (groups.isEmpty()) {
            return new RankOrder(List.of());
        }
        List<RankOrder.Rank> list = new ArrayList<>(groups.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String group : groups) {
            if (!GROUP.matcher(group).matches()) {
                order.problem(group, "is not a LuckPerms group name (lowercase letters, digits, - and _)");
                continue;
            }
            if (!seen.add(group)) {
                continue;
            }
            String label = order.string(group, "").strip();
            if (label.length() > MAX_LABEL) {
                order.problem(group, "has a label longer than " + MAX_LABEL + " characters");
                label = label.substring(0, MAX_LABEL);
            }
            if (label.indexOf('§') >= 0 || label.indexOf('&') >= 0 || label.indexOf('<') >= 0) {
                order.problem(group, "has formatting in its label; labels are plain text (the tab list and nametags style them)");
                continue;
            }
            list.add(new RankOrder.Rank(group, label));
        }
        return new RankOrder(list);
    }
}
