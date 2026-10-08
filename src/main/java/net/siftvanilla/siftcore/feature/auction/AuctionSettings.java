package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.item.ItemPatterns;
import net.siftvanilla.siftcore.core.money.MoneyFormat;

/**
 * Parsed {@code features/auction.yml}.
 *
 * @param duration              how long a listing stays up
 * @param defaultSlots          listing slots without a {@code siftcore.auction.listings.<n>} permission
 * @param price                 price limits
 * @param taxBasisPoints        sale tax in basis points (500 = 5%)
 * @param blacklist             item types that can't be listed
 * @param allowFilledContainers whether shulker boxes and bundles that hold items can be listed
 * @param maxItemBytes          the largest uncompressed item data a listing may carry, 0 for no limit
 * @param allowCreative         whether players in creative mode can list items
 * @param blockInCombat         whether combat-tagged players are kept out of the auction house
 * @param expiryCheck           how often expired listings are returned
 * @param autoClaim             whether bought and returned items go straight into the inventory when they fit
 * @param joinReminder          whether players are told about waiting claim box items when they join
 * @param historySize           entries in /ah history
 * @param defaultSort           the sort order of players who never chose one
 */
public record AuctionSettings(
    Duration duration,
    int defaultSlots,
    AuctionMath.PriceRules price,
    int taxBasisPoints,
    ItemPatterns blacklist,
    boolean allowFilledContainers,
    long maxItemBytes,
    boolean allowCreative,
    boolean blockInCombat,
    Duration expiryCheck,
    boolean autoClaim,
    boolean joinReminder,
    int historySize,
    SortOrder defaultSort) {

    /** Fallback blacklist when the configured one is broken. */
    static final List<String> DEFAULT_BLACKLIST = List.of(
        "minecraft:barrier", "minecraft:bedrock", "minecraft:command_block", "minecraft:chain_command_block",
        "minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:structure_block",
        "minecraft:structure_void", "minecraft:jigsaw", "minecraft:light", "minecraft:debug_stick",
        "minecraft:knowledge_book", "minecraft:test_block", "minecraft:test_instance_block", "minecraft:*_spawn_egg");

    public static AuctionSettings parse(ConfigReader r, MoneyFormat money) {
        ConfigReader listings = r.section("listings");
        Duration duration = listings.duration("duration", Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofHours(48));
        int slots = listings.integer("default-slots", 0, 1000, 3);

        ConfigReader price = r.section("price");
        long minimum = price.money("minimum", money, false, 1);
        long maximum = price.money("maximum", money, false, 10_000_000_000L);
        long minimumPerItem = price.money("minimum-per-item", money, true, 1);
        long maximumPerItem = price.money("maximum-per-item", money, true, 0);
        AuctionMath.PriceRules rules;
        if (maximum < minimum) {
            price.problem("maximum", "must be at least the minimum (" + minimum + ")");
            rules = new AuctionMath.PriceRules(1, 10_000_000_000L, 1, 0);
        } else if (maximumPerItem != 0 && maximumPerItem < minimumPerItem) {
            price.problem("maximum-per-item", "must be 0 (no limit) or at least minimum-per-item (" + minimumPerItem + ")");
            rules = new AuctionMath.PriceRules(minimum, maximum, minimumPerItem, 0);
        } else {
            rules = new AuctionMath.PriceRules(minimum, maximum, minimumPerItem, maximumPerItem);
        }

        int tax = r.custom("tax", AuctionMath::parsePercent, "a percentage from 0 to 100 like 5 or 2.5", 500);

        ConfigReader blacklistSection = r.section("blacklist");
        List<String> entries = blacklistSection.stringList("items", DEFAULT_BLACKLIST);
        ItemPatterns blacklist;
        try {
            blacklist = ItemPatterns.compile(entries);
        } catch (IllegalArgumentException e) {
            blacklistSection.problem("items", e.getMessage());
            blacklist = ItemPatterns.compile(DEFAULT_BLACKLIST);
        }
        boolean filled = blacklistSection.bool("allow-filled-containers", true);
        long maxItemBytes = blacklistSection.integer("max-item-size", 0, 4096, 128) * 1024L;
        boolean creative = blacklistSection.bool("allow-creative-mode", false);

        boolean combat = r.bool("block-in-combat", true);
        Duration expiryCheck = r.duration("expiry-check", Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofSeconds(30));
        boolean autoClaim = r.bool("auto-claim", true);
        boolean joinReminder = r.bool("join-reminder", true);
        int history = r.integer("history-size", 1, 50, 20);
        SortOrder sort = r.custom("default-sort", value -> {
            SortOrder order = SortOrder.byId(value);
            if (order == null) {
                throw new IllegalArgumentException("must be newest, ending-soon, lowest-price or highest-price");
            }
            return order;
        }, "newest, ending-soon, lowest-price or highest-price", SortOrder.NEWEST);
        return new AuctionSettings(duration, slots, rules, tax, blacklist, filled, maxItemBytes, creative, combat, expiryCheck,
            autoClaim, joinReminder, history, sort);
    }
}
