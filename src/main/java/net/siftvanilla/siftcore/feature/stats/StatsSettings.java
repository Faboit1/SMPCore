package net.siftvanilla.siftcore.feature.stats;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.config.ConfigReader;

/** Parsed {@code features/stats.yml}. */
public record StatsSettings(
    Duration saveInterval,
    Duration keepOffline,
    Duration leaderboardRefresh,
    int leaderboardSize,
    int pageSize,
    int kdrMinKills,
    boolean countInstantBlocks,
    Duration ignorePlacedFor,
    int placedMemory,
    Set<String> ignoredBlocks,
    Set<String> earnKinds,
    Set<String> taxKinds) {

    static final List<String> DEFAULT_EARN_KINDS = List.of("sell", "ah_sale", "order_fill", "bounty_claim", "spawner_sell", "crate_reward");
    static final List<String> DEFAULT_TAX_KINDS = List.of("ah_tax", "order_tax");
    static final List<String> DEFAULT_IGNORED_BLOCKS = List.of("melon", "pumpkin", "cactus", "bamboo", "sugar_cane", "kelp",
        "kelp_plant", "cocoa", "chorus_plant", "chorus_flower");

    private static final String MINECRAFT = "minecraft:";

    /**
     * Parses the file. {@code isBlock} tells whether a lowercase name without namespace (such as {@code melon}) is a
     * block of this Minecraft version; it is passed in so parsing needs no running server.
     */
    public static StatsSettings parse(ConfigReader r, Predicate<String> isBlock) {
        ConfigReader boards = r.section("leaderboards");
        ConfigReader blocks = r.section("blocks-mined");
        ConfigReader money = r.section("money-earned");
        return new StatsSettings(
            r.duration("save-interval", Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(60)),
            r.duration("keep-offline", Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMinutes(5)),
            boards.duration("refresh", Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofSeconds(60)),
            boards.integer("size", 10, 100, 100),
            boards.integer("page-size", 5, 20, 10),
            boards.integer("kdr-min-kills", 0, 1_000_000, 25),
            blocks.bool("count-instant-blocks", false),
            blocks.duration("ignore-placed-for", Duration.ZERO, Duration.ofDays(1), Duration.ofMinutes(15)),
            blocks.integer("placed-memory", 1_000, 1_000_000, 50_000),
            blockNames(blocks, "ignore", isBlock),
            kinds(money, "kinds", DEFAULT_EARN_KINDS),
            kinds(money, "tax-kinds", DEFAULT_TAX_KINDS));
    }

    /** Whether breaking a block of this type (lowercase name without namespace) never counts as mined. */
    public boolean ignored(String blockName) {
        return this.ignoredBlocks.contains(blockName);
    }

    private static Set<String> kinds(ConfigReader reader, String path, List<String> fallback) {
        List<String> values = reader.stringList(path, fallback);
        Set<String> kinds = new LinkedHashSet<>();
        for (String value : values) {
            String kind = value.trim().toLowerCase(Locale.ROOT);
            if (!kind.matches("[a-z0-9_]{1,32}")) {
                reader.problem(path, "contains '" + value + "'; a kind is 1-32 lowercase letters, digits or _");
                return Set.copyOf(fallback);
            }
            kinds.add(kind);
        }
        return Set.copyOf(kinds);
    }

    /** Block names, lowercase and without the {@code minecraft:} namespace; entries that are no block are reported. */
    private static Set<String> blockNames(ConfigReader reader, String path, Predicate<String> isBlock) {
        List<String> values = reader.stringList(path, DEFAULT_IGNORED_BLOCKS);
        Set<String> names = new LinkedHashSet<>();
        for (String value : values) {
            String name = value.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith(MINECRAFT)) {
                name = name.substring(MINECRAFT.length());
            }
            if (!name.matches("[a-z0-9_]{1,64}") || !isBlock.test(name)) {
                reader.problem(path, "contains '" + value + "', which is not a block (write names like melon or minecraft:pumpkin)");
                continue;
            }
            names.add(name);
        }
        return Set.copyOf(names);
    }
}
