package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.item.ItemCategory;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The auction settings: their group and order, offered only while SiftCore's auction house is the server's, the old
 * sale switch read by the choice it became, the low price warning and hiding one's own listings.
 */
class AuctionPlayerSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");
    private static final UUID ALEX = new UUID(7, 1);
    private static final UUID SAM = new UUID(7, 2);

    @TempDir
    Path dir;

    private static AuctionSettings parse(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/auction.yml", yaml);
        AuctionSettings settings = AuctionSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static YamlConfiguration shipped() throws Exception {
        InputStream in = AuctionPlayerSettingsTest.class.getClassLoader().getResourceAsStream("features/auction.yml");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    @Test
    void settingsSitInTheMarketGroupInCatalogOrder() throws Exception {
        AtomicReference<AuctionSettings> config = new AtomicReference<>(parse(shipped()));
        AtomicBoolean ownHouse = new AtomicBoolean(true);
        PlayerSettings settings = new PlayerSettings(null, null, LOGGER);
        AuctionFeature.registerSettings(settings, config::get, ownHouse::get);
        assertEquals(List.of("auction-sales", "auction-join-summary", "auction-price-warning", "auction-expiry-alerts", "auction-hide-own"),
            offered(settings, SettingCategories.MARKET.id()));
        assertEquals(List.of(1, 4, 8, 11, 12), settings.registry().in(SettingCategories.MARKET.id()).stream()
            .map(entry -> entry.options().order()).toList(), "the catalog positions");

        ownHouse.set(false);
        assertEquals(List.of("auction-join-summary"), offered(settings, SettingCategories.MARKET.id()),
            "while AxAuctions is the auction house only the claim box reminder of the summary still does something");
        YamlConfiguration quiet = shipped();
        quiet.set("join-reminder", false);
        config.set(parse(quiet));
        assertEquals(List.of(), offered(settings, SettingCategories.MARKET.id()));
        ownHouse.set(true);
        assertTrue(offered(settings, SettingCategories.MARKET.id()).contains("auction-join-summary"),
            "SiftCore's own house: the sales part of the summary works without the claim box reminder");
    }

    @Test
    void theOptionsUseTheSharedVocabulary() {
        assertEquals(List.of("chat", "actionbar", "off"), AuctionFeature.SALE_ALERTS.optionIds());
        assertEquals(AlertStyle.CHAT, AuctionFeature.SALE_ALERTS.defaultValue());
        assertEquals(List.of("chat", "actionbar", "off"), AuctionFeature.EXPIRY_ALERTS.optionIds());
        assertEquals(AlertStyle.CHAT, AuctionFeature.EXPIRY_ALERTS.defaultValue());
        assertTrue(AuctionFeature.JOIN_SUMMARY.defaultOn());
        assertTrue(AuctionFeature.PRICE_WARNING.defaultOn());
        assertFalse(AuctionFeature.HIDE_OWN.defaultOn());
    }

    @Test
    void theOldSaleSwitchReadsAsTheChoiceItBecame() throws Exception {
        assertSame(AlertStyle.CHAT, AuctionFeature.SALE_ALERTS.decodeOrNull("true"));
        assertSame(AlertStyle.OFF, AuctionFeature.SALE_ALERTS.decodeOrNull("false"));
        assertSame(AlertStyle.ACTIONBAR, AuctionFeature.SALE_ALERTS.decodeOrNull(" ActionBar "));
        assertNull(AuctionFeature.SALE_ALERTS.decodeOrNull("title"), "not an option of this setting");

        JdbcDatabase database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        try {
            ClassLoader loader = getClass().getClassLoader();
            new Migrations(database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
            database.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                    ps.setString(1, ALEX.toString());
                    ps.setString(2, "auction-sales");
                    ps.setString(3, "false");
                    ps.addBatch();
                    ps.setString(1, SAM.toString());
                    ps.setString(3, "true");
                    ps.addBatch();
                    ps.executeBatch();
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
            PlayerSettings settings = new PlayerSettings(database, null, LOGGER);
            AuctionSettings config = parse(shipped());
            AuctionFeature.registerSettings(settings, () -> config, () -> true);
            settings.load(ALEX).get(5, TimeUnit.SECONDS);
            settings.load(SAM).get(5, TimeUnit.SECONDS);
            assertEquals(AlertStyle.OFF, settings.get(ALEX, AuctionFeature.SALE_ALERTS), "a player who turned sale messages off");
            assertEquals(AlertStyle.CHAT, settings.get(SAM, AuctionFeature.SALE_ALERTS), "the old on is chat");
            assertEquals(AlertStyle.OFF, settings.lookup(ALEX, AuctionFeature.SALE_ALERTS).get(5, TimeUnit.SECONDS));
        } finally {
            database.close();
        }
    }

    @Test
    void priceWarningsCompareTheWholeListingAndThePricePerItem() {
        assertTrue(PriceCheck.belowSell(99, 100), "below what /sell pays");
        assertFalse(PriceCheck.belowSell(100, 100), "the same as /sell is fine");
        assertFalse(PriceCheck.belowSell(1, 0), "unknown sell value: no warning");

        // 64 for $1,000 is $15.62 each; 32 for $5,000 is $156.25 each.
        assertTrue(PriceCheck.farBelow(1_000, 64, 5_000, 32));
        assertFalse(PriceCheck.farBelow(2_500, 1, 5_000, 1), "exactly half is not far below");
        assertTrue(PriceCheck.farBelow(2_499, 1, 5_000, 1));
        assertFalse(PriceCheck.farBelow(4_000, 64, 5_000, 64), "a bit cheaper is fine");
        assertFalse(PriceCheck.farBelow(1, 1, 0, 1), "no reference price");
        assertFalse(PriceCheck.farBelow(Long.MAX_VALUE, 1, Long.MAX_VALUE, 1), "no overflow with huge prices");
        assertTrue(PriceCheck.farBelow(Long.MAX_VALUE, 64, Long.MAX_VALUE, 1), "a 64th of the price each");
        assertTrue(PriceCheck.farBelow(1, 64, Long.MAX_VALUE, 64));

        Listing<String> pricey = listing(1, 10_000, 10);
        Listing<String> cheap = listing(2, 1_000, 2);
        Listing<String> same = listing(3, 500, 1);
        assertSame(cheap, PriceCheck.cheapest(List.of(pricey, cheap, same)), "$500 each, the first of equals");
        assertNull(PriceCheck.cheapest(List.<Listing<String>>of()));
        assertEquals(15, PriceCheck.each(1_000, 64), "rounded down");
        assertEquals(1_000, PriceCheck.each(1_000, 0));
    }

    @Test
    void hidingOwnListingsKeepsEveryoneElses() {
        UUID viewer = new UUID(3, 3);
        Listing<String> mine = new Listing<>(1, viewer, "a", "minecraft:stone", "stone", ItemCategory.BLOCKS, 1, 10, 0, 100);
        Listing<String> theirs = listing(2, 10, 1);
        List<Listing<String>> all = List.of(mine, theirs);
        assertEquals(all, AuctionMenu.visible(all, viewer, false));
        assertEquals(List.of(theirs), AuctionMenu.visible(all, viewer, true));
        assertEquals(all, AuctionMenu.visible(all, new UUID(4, 4), true), "another viewer sees both");
    }

    private static Listing<String> listing(long id, long price, int amount) {
        return new Listing<>(id, new UUID(9, id), "item", "minecraft:stone", "stone", ItemCategory.BLOCKS, amount, price, 0, 100);
    }
}
