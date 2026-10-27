package net.siftvanilla.siftcore.feature.orders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.config.Durations;
import net.siftvanilla.siftcore.core.item.ItemPatterns;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/orders.yml}.
 *
 * @param duration          how long a new order stays open
 * @param expiryCheck       how often ended orders are refunded
 * @param expiryWarning     how long before the end owners are told (zero: never)
 * @param defaultLimit      active orders per player without a {@code siftcore.orders.limit.<n>} node
 * @param minPrice          the lowest price each
 * @param maxQuantity       the most items one order may ask for
 * @param maxTotal          the most money one order may hold
 * @param taxBasisPoints    tax the deliverer pays, in basis points (200 = 2%)
 * @param blockInCombat     refuse orders while combat tagged
 * @param joinReminder      remind players on join of items waiting in their orders
 * @param refuseSameIp      refuse deliveries to orders of players last seen from the seller's address
 * @param suggestMargin     how much above the server's price the suggested price leaves the seller, in basis points
 * @param minVsWorth        the lowest price each as a share of the worth, in basis points (0: off)
 * @param maxVsWorth        the highest price each as a multiple of the worth (0: off)
 * @param booksEnabled      whether enchanted book orders (one enchantment, one level) are allowed
 * @param allowCurses       whether books with a curse can be ordered
 * @param potionsEnabled    whether potion orders (one base potion type) are allowed
 * @param spawnersEnabled   whether spawner orders are allowed (only while the spawner feature provides spawners)
 * @param historyEntries    how many past orders and deliveries the history menus show
 * @param historyKeep       how long closed orders are kept in storage (zero: forever)
 * @param extendEnabled     whether owners may extend their active orders
 * @param maxLifetime       how long after it was placed an extended order must end at the latest
 * @param announceMinTotal  orders holding at least this much are announced (0: never)
 * @param announceCooldown  the least time between two announcements of one owner
 * @param blocked           item types that can't be ordered as plain items
 */
record OrdersSettings(
    Duration duration,
    Duration expiryCheck,
    Duration expiryWarning,
    int defaultLimit,
    long minPrice,
    int maxQuantity,
    long maxTotal,
    int taxBasisPoints,
    boolean blockInCombat,
    boolean joinReminder,
    boolean refuseSameIp,
    int suggestMargin,
    int minVsWorth,
    double maxVsWorth,
    boolean booksEnabled,
    boolean allowCurses,
    boolean potionsEnabled,
    boolean spawnersEnabled,
    int historyEntries,
    Duration historyKeep,
    boolean extendEnabled,
    Duration maxLifetime,
    long announceMinTotal,
    Duration announceCooldown,
    ItemPatterns blocked) {

    /**
     * Items nobody can get in survival, and items whose worth is in data a plain item never has. Enchanted books,
     * potions and spawners are ordered as exact variants instead (see {@code books}, {@code potions} and
     * {@code spawners}); their plain forms are never orderable.
     */
    static final List<String> DEFAULT_BLOCKED = List.of(
        "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart", "structure_block",
        "structure_void", "jigsaw", "barrier", "light", "debug_stick", "knowledge_book", "test_block",
        "test_instance_block", "bedrock", "end_portal_frame", "reinforced_deepslate", "budding_amethyst", "spawner",
        "trial_spawner", "vault", "petrified_oak_slab", "farmland", "dirt_path", "chorus_plant", "frogspawn",
        "*_spawn_egg", "infested_*",
        "potion", "splash_potion", "lingering_potion", "tipped_arrow", "written_book", "filled_map",
        "suspicious_stew", "firework_star");

    boolean isBlocked(String itemKey) {
        return this.blocked.matches(itemKey);
    }

    /**
     * @param knownItems every item key in the registry ({@code minecraft:diamond}), to report entries that match nothing
     */
    static OrdersSettings parse(ConfigReader r, MoneyFormat money, Set<String> knownItems) {
        Duration duration = r.duration("duration", Duration.ofSeconds(30), Duration.ofDays(90), Duration.ofDays(7));
        Duration expiryCheck = r.duration("expiry-check", Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(30));
        Duration expiryWarning = r.duration("expiry-warning", Duration.ZERO, Duration.ofDays(30), Duration.ofHours(12));

        ConfigReader limits = r.section("limits");
        int defaultLimit = limits.integer("active-orders", 0, 1000, 3);
        long minPrice = limits.money("min-price", money, false, 1);
        int maxQuantity = limits.integer("max-quantity", 1, 1_000_000_000, 100_000);
        long maxTotal = limits.money("max-total", money, false, 100_000_000_000L);
        if (maxTotal < minPrice) {
            limits.problem("max-total", "must be at least min-price");
            maxTotal = Math.max(minPrice, 100_000_000_000L);
        }

        double taxPercent = r.decimal("tax", 0, 50, 0.0);
        boolean blockInCombat = r.bool("block-in-combat", true);
        boolean joinReminder = r.bool("join-reminder", true);
        boolean refuseSameIp = r.bool("refuse-same-ip", false);

        ConfigReader pricing = r.section("pricing");
        double margin = pricing.decimal("suggest-margin", 0, 1000, 10);
        double minVsWorth = pricing.decimal("min-vs-worth", 0, 100, 0);
        double maxVsWorth = pricing.decimal("max-vs-worth", 0, 1_000_000, 0);
        if (maxVsWorth > 0 && maxVsWorth < 1) {
            pricing.problem("max-vs-worth", "must be 0 (off) or at least 1, got " + maxVsWorth);
            maxVsWorth = 0;
        }

        ConfigReader books = r.section("books");
        boolean booksEnabled = books.bool("enabled", true);
        boolean allowCurses = books.bool("allow-curses", false);
        boolean potionsEnabled = r.section("potions").bool("enabled", true);
        boolean spawnersEnabled = r.section("spawners").bool("enabled", true);

        ConfigReader history = r.section("history");
        int historyEntries = history.integer("max-entries", 10, 1000, 200);
        Duration historyKeep = history.duration("keep", Duration.ZERO, Duration.ofDays(3650), Duration.ZERO);
        if (!historyKeep.isZero() && historyKeep.compareTo(Duration.ofDays(1)) < 0) {
            history.problem("keep", "must be 0 (keep forever) or at least 1d");
            historyKeep = Duration.ZERO;
        }

        ConfigReader extend = r.section("extend");
        boolean extendEnabled = extend.bool("enabled", true);
        Duration maxLifetime = extend.duration("max-lifetime", Duration.ofMinutes(1), Duration.ofDays(365), Duration.ofDays(30));
        if (maxLifetime.compareTo(duration) < 0) {
            extend.problem("max-lifetime", "must be at least the order duration (" + Durations.format(duration) + ")");
            maxLifetime = duration;
        }

        ConfigReader announce = r.section("announce");
        long announceMinTotal = announce.money("min-total", money, true, 1_000_000);
        Duration announceCooldown = announce.duration("cooldown", Duration.ZERO, Duration.ofDays(7), Duration.ofMinutes(10));

        List<String> entries = r.stringList("blocked-items", DEFAULT_BLOCKED);
        List<String> blocked = new ArrayList<>();
        for (String entry : entries) {
            ItemPatterns pattern;
            try {
                pattern = ItemPatterns.compile(List.of(entry));
            } catch (IllegalArgumentException e) {
                r.problem("blocked-items", "'" + entry + "' is not an item key like diamond or minecraft:*_spawn_egg");
                continue;
            }
            if (!knownItems.isEmpty() && knownItems.stream().noneMatch(pattern::matches)) {
                r.problem("blocked-items", "'" + entry + "' matches no item in this Minecraft version");
                continue;
            }
            blocked.add(pattern.entries().getFirst());
        }
        return new OrdersSettings(duration, expiryCheck, expiryWarning, defaultLimit, minPrice, maxQuantity, maxTotal,
            OrderMath.basisPoints(taxPercent), blockInCombat, joinReminder, refuseSameIp, (int) Math.round(margin * 100),
            OrderMath.basisPoints(minVsWorth), maxVsWorth, booksEnabled, allowCurses, potionsEnabled, spawnersEnabled,
            historyEntries, historyKeep, extendEnabled, maxLifetime, announceMinTotal, announceCooldown, ItemPatterns.compile(blocked));
    }
}
