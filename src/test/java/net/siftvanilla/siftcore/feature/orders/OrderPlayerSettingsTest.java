package net.siftvanilla.siftcore.feature.orders;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Announce;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The order settings: groups and order, config-dependent offering (ending warnings, announcements and their amount
 * presets), the old switches read by the choices they became, and the deciders of delivery lines, the join summary
 * and announcements.
 */
class OrderPlayerSettingsTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");

    @TempDir
    Path dir;

    private static YamlConfiguration shipped() throws Exception {
        InputStream in = OrderPlayerSettingsTest.class.getClassLoader().getResourceAsStream("features/orders.yml");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static OrdersSettings parse(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/orders.yml", yaml);
        OrdersSettings settings = OrdersSettings.parse(reader, MoneyFormat.defaults(), Set.of());
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    /** The options of a choice the dialog offers now. */
    @SuppressWarnings("unchecked")
    private static List<String> openOptions(PlayerSettings settings, Choice<Announce> choice) {
        Registry.Entry<Announce> entry = (Registry.Entry<Announce>) settings.registry().entry(choice.id());
        return settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    @Test
    void settingsSitInTheirGroupsInCatalogOrder() throws Exception {
        AtomicReference<OrdersSettings> config = new AtomicReference<>(parse(shipped()));
        PlayerSettings settings = new PlayerSettings(null, null, LOGGER);
        OrdersFeature.registerSettings(settings, config::get);
        assertEquals(List.of("order-notices", "order-join-summary", "order-ending-alerts", "order-auto-collect"),
            offered(settings, SettingCategories.MARKET.id()));
        assertEquals(List.of(2, 5, 7, 10), settings.registry().in(SettingCategories.MARKET.id()).stream()
            .map(entry -> entry.options().order()).toList());
        assertEquals(List.of("orders_announce"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));
        assertEquals(5, settings.registry().entry("orders_announce").options().order());
        assertEquals(List.of("order-announce-mine"), offered(settings, SettingCategories.PRIVACY.id()));
        assertEquals(4, settings.registry().entry("order-announce-mine").options().order());
        assertFalse(settings.registry().entry("order-announce-mine").placeholder(), "privacy settings are not placeholders");

        assertEquals(List.of("all", "10m", "100m", "off"), openOptions(settings, OrdersFeature.ANNOUNCEMENTS),
            "the server announces from $1m: \"from $1m\" would be the same as All");

        YamlConfiguration quiet = shipped();
        quiet.set("expiry-warning", "0s");
        quiet.set("announce.min-total", 0);
        config.set(parse(quiet));
        assertEquals(List.of("order-notices", "order-join-summary", "order-auto-collect"), offered(settings, SettingCategories.MARKET.id()),
            "no ending warning switch while the server sends none");
        assertEquals(List.of(), offered(settings, SettingCategories.ANNOUNCEMENTS.id()), "nothing is announced");
        assertEquals(List.of(), offered(settings, SettingCategories.PRIVACY.id()));

        YamlConfiguration low = shipped();
        low.set("announce.min-total", "500k");
        config.set(parse(low));
        assertEquals(List.of("all", "1m", "10m", "100m", "off"), openOptions(settings, OrdersFeature.ANNOUNCEMENTS));
    }

    @Test
    void theOldSwitchesReadAsTheChoicesTheyBecame() throws Exception {
        assertEquals(List.of("chat", "actionbar", "complete", "off"), OrdersFeature.NOTIFICATIONS.optionIds());
        assertEquals(DeliveryAlerts.CHAT, OrdersFeature.NOTIFICATIONS.defaultValue());
        assertSame(DeliveryAlerts.CHAT, OrdersFeature.NOTIFICATIONS.decodeOrNull("true"));
        assertSame(DeliveryAlerts.OFF, OrdersFeature.NOTIFICATIONS.decodeOrNull("false"));
        assertEquals(List.of("all", "1m", "10m", "100m", "off"), OrdersFeature.ANNOUNCEMENTS.optionIds());
        assertEquals(Announce.ALL, OrdersFeature.ANNOUNCEMENTS.decodeOrNull("true"));
        assertEquals(Announce.OFF, OrdersFeature.ANNOUNCEMENTS.decodeOrNull("false"));
        assertTrue(OrdersFeature.ENDING_ALERTS.defaultOn());
        assertTrue(OrdersFeature.JOIN_SUMMARY.defaultOn());
        assertFalse(OrdersFeature.AUTO_COLLECT.defaultOn());
        assertTrue(OrdersFeature.ANNOUNCE_MINE.defaultOn());

        UUID quiet = new UUID(8, 1);
        UUID loud = new UUID(8, 2);
        UUID picky = new UUID(8, 3);
        JdbcDatabase database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        try {
            ClassLoader loader = getClass().getClassLoader();
            new Migrations(database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
            Map<UUID, Map<String, String>> rows = Map.of(quiet, Map.of("order-notices", "false", "orders_announce", "false"),
                loud, Map.of("order-notices", "true", "orders_announce", "true"), picky, Map.of("orders_announce", "1m"));
            database.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                    for (Map.Entry<UUID, Map<String, String>> player : rows.entrySet()) {
                        for (Map.Entry<String, String> row : player.getValue().entrySet()) {
                            ps.setString(1, player.getKey().toString());
                            ps.setString(2, row.getKey());
                            ps.setString(3, row.getValue());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
            AtomicReference<OrdersSettings> config = new AtomicReference<>(parse(shipped()));
            PlayerSettings settings = new PlayerSettings(database, null, LOGGER);
            OrdersFeature.registerSettings(settings, config::get);
            for (UUID player : rows.keySet()) {
                settings.load(player).get(5, TimeUnit.SECONDS);
            }
            assertEquals(DeliveryAlerts.OFF, settings.get(quiet, OrdersFeature.NOTIFICATIONS));
            assertEquals(Announce.OFF, settings.get(quiet, OrdersFeature.ANNOUNCEMENTS));
            assertTrue(settings.get(quiet, OrdersFeature.ENDING_ALERTS), "ending warnings start on for players who had messages off");
            assertEquals(DeliveryAlerts.CHAT, settings.get(loud, OrdersFeature.NOTIFICATIONS));
            assertEquals(Announce.ALL, settings.get(loud, OrdersFeature.ANNOUNCEMENTS));
            assertEquals(Announce.ALL, settings.get(picky, OrdersFeature.ANNOUNCEMENTS),
                "from $1m reads as All while the server announces from $1m anyway");
            YamlConfiguration low = shipped();
            low.set("announce.min-total", "500k");
            config.set(parse(low));
            assertEquals(OrdersFeature.ANNOUNCEMENTS.decodeOrNull("1m"), settings.get(picky, OrdersFeature.ANNOUNCEMENTS),
                "and comes back when the server announces smaller orders");
        } finally {
            database.close();
        }
    }

    @Test
    void deliveryLinesFollowTheOwnersChoice() {
        assertEquals(OrdersMessages.NOTIFY_DELIVERED, OwnerNotices.deliveryLine(DeliveryAlerts.CHAT, false, false));
        assertEquals(OrdersMessages.NOTIFY_SOLD, OwnerNotices.deliveryLine(DeliveryAlerts.ACTIONBAR, false, true));
        assertEquals(OrdersMessages.NOTIFY_COMPLETE, OwnerNotices.deliveryLine(DeliveryAlerts.CHAT, true, false),
            "the last delivery says the order is complete");
        assertNull(OwnerNotices.deliveryLine(DeliveryAlerts.COMPLETE, false, false), "only completions");
        assertEquals(OrdersMessages.NOTIFY_COMPLETE, OwnerNotices.deliveryLine(DeliveryAlerts.COMPLETE, true, true));
        assertNull(OwnerNotices.deliveryLine(DeliveryAlerts.OFF, true, false));
        assertNull(OwnerNotices.deliveryLine(DeliveryAlerts.OFF, false, false));
        assertEquals(AlertStyle.ACTIONBAR, DeliveryAlerts.ACTIONBAR.place());
        assertEquals(AlertStyle.CHAT, DeliveryAlerts.COMPLETE.place(), "completions are kept in chat");
        assertEquals(AlertStyle.CHAT, DeliveryAlerts.CHAT.place());
    }

    private static OrderStore.NoticeRow row(long order, String kind, int units, long amount) {
        return new OrderStore.NoticeRow(order, kind, units, amount, null, 0, "minecraft:diamond", null, 64, 0);
    }

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Order order(long id, String item, int quantity) {
        return Order.placed(id, OWNER, item, null, quantity, 100, 0, 1_000_000);
    }

    /**
     * A sale over several orders: chat gets the sale and one line per completed order; above the hotbar a second line
     * would replace the first at once, so the completions are folded into one line; "only when complete" gets only the
     * completions, "never" nothing.
     */
    @Test
    void saleLinesNeverStackOnTheHotbar() {
        OwnerNotices.Lines chat = OwnerNotices.saleLines(DeliveryAlerts.CHAT, true, 2);
        assertEquals(OrdersMessages.NOTIFY_SOLD, chat.main());
        assertEquals(0, chat.done());
        assertEquals(List.of(OrdersMessages.NOTIFY_COMPLETE, OrdersMessages.NOTIFY_COMPLETE), chat.completed());
        assertEquals(OrdersMessages.NOTIFY_SOLD_MANY, OwnerNotices.saleLines(DeliveryAlerts.CHAT, false, 0).main(), "several items");

        OwnerNotices.Lines bar = OwnerNotices.saleLines(DeliveryAlerts.ACTIONBAR, false, 2);
        assertEquals(OrdersMessages.NOTIFY_SOLD_DONE, bar.main(), "one line for the sale and the completions");
        assertEquals(2, bar.done());
        assertTrue(bar.completed().stream().allMatch(java.util.Objects::isNull), "no second line above the hotbar: " + bar.completed());
        OwnerNotices.Lines barNothingDone = OwnerNotices.saleLines(DeliveryAlerts.ACTIONBAR, true, 0);
        assertEquals(OrdersMessages.NOTIFY_SOLD, barNothingDone.main());
        assertEquals(0, barNothingDone.done());

        OwnerNotices.Lines complete = OwnerNotices.saleLines(DeliveryAlerts.COMPLETE, true, 1);
        assertNull(complete.main(), "no sale line for only when complete");
        assertEquals(List.of(OrdersMessages.NOTIFY_COMPLETE), complete.completed());
        assertNull(OwnerNotices.saleLines(DeliveryAlerts.COMPLETE, true, 0).main());

        OwnerNotices.Lines off = OwnerNotices.saleLines(DeliveryAlerts.OFF, true, 1);
        assertNull(off.main());
        assertTrue(off.completed().stream().allMatch(java.util.Objects::isNull), "never: nothing");
    }

    /**
     * Auto-collect tells what reached the inventory as order-notices says: "never" nothing at all, "only when
     * complete" nothing for a partial delivery and only the completion otherwise, chat and the hotbar one line (the
     * hotbar with the completions folded in), a completed order whose items partly wait still says to collect them.
     */
    @Test
    void autoCollectLinesFollowTheDeliveryAlerts() {
        OwnerNotices.Lines never = OwnerNotices.autoLines(DeliveryAlerts.OFF, false, List.of(false));
        assertNull(never.main(), "never: no collected line");
        assertEquals(0, never.done());
        assertTrue(never.completed().stream().allMatch(java.util.Objects::isNull), "never: no completion either");

        OwnerNotices.Lines partial = OwnerNotices.autoLines(DeliveryAlerts.COMPLETE, false, List.of());
        assertNull(partial.main(), "only when complete: a partial delivery says nothing");
        assertTrue(partial.completed().isEmpty());
        OwnerNotices.Lines finished = OwnerNotices.autoLines(DeliveryAlerts.COMPLETE, false, List.of(false));
        assertNull(finished.main());
        assertEquals(List.of(OrdersMessages.NOTIFY_AUTO_COMPLETE), finished.completed(), "complete and in the inventory");
        assertEquals(List.of(OrdersMessages.NOTIFY_COMPLETE), OwnerNotices.autoLines(DeliveryAlerts.COMPLETE, true, List.of(true)).completed(),
            "complete, but some wait: collect it in /orders");

        OwnerNotices.Lines chat = OwnerNotices.autoLines(DeliveryAlerts.CHAT, false, List.of(false));
        assertEquals(OrdersMessages.NOTIFY_AUTO_COLLECTED, chat.main());
        assertEquals(0, chat.done());
        assertEquals(List.of(OrdersMessages.NOTIFY_AUTO_COMPLETE), chat.completed());
        assertEquals(OrdersMessages.NOTIFY_AUTO_COLLECTED_SOME, OwnerNotices.autoLines(DeliveryAlerts.CHAT, true, List.of()).main(),
            "not all fit: how many wait");

        OwnerNotices.Lines bar = OwnerNotices.autoLines(DeliveryAlerts.ACTIONBAR, true, List.of(true, false));
        assertEquals(OrdersMessages.NOTIFY_AUTO_COLLECTED_SOME, bar.main());
        assertEquals(2, bar.done(), "the completions folded into the one line");
        assertTrue(bar.completed().stream().allMatch(java.util.Objects::isNull), "no second line above the hotbar");
    }

    /** An arrival sums the items, names the one item (or none for several), and lists the orders it completed. */
    @Test
    void anArrivalDescribesWhatReachedTheOrders() {
        Order diamonds = order(1, "minecraft:diamond", 10);
        Order more = order(2, "minecraft:diamond", 20);
        Order iron = order(3, "minecraft:iron_ingot", 5);
        OwnerNotices.Arrival sale = new OwnerNotices.Arrival(OWNER, "Steve", true, List.of(
            new OwnerNotices.Part(diamonds, 10, 1_000, true), new OwnerNotices.Part(more, 4, 400, false)));
        assertEquals(14, sale.units());
        assertEquals("minecraft:diamond", sale.key());
        assertEquals(List.of(diamonds), sale.completed());
        assertEquals(List.of(1L, 2L), sale.orderIds());
        OwnerNotices.Arrival mixed = new OwnerNotices.Arrival(OWNER, "Steve", true, List.of(
            new OwnerNotices.Part(diamonds, 1, 100, false), new OwnerNotices.Part(iron, 5, 500, true)));
        assertNull(mixed.key(), "several kinds of items");
        assertEquals(List.of(iron), mixed.completed());
    }

    @Test
    void theJoinSummaryFollowsTheSettings() {
        List<OrderStore.NoticeRow> rows = List.of(row(1, NoticeSummary.DELIVERED, 10, 1_000), row(2, NoticeSummary.COMPLETE, 64, 0),
            row(3, NoticeSummary.EXPIRED, 64, 2_000), row(4, NoticeSummary.ENDING, 64, 0));

        NoticeSummary all = NoticeSummary.of(rows, NoticeSummary.Filter.of(true, DeliveryAlerts.ACTIONBAR, true), 10);
        assertEquals(10, all.delivered());
        assertEquals(1, all.complete());
        assertEquals(4, all.details().size());

        NoticeSummary completions = NoticeSummary.of(rows, NoticeSummary.Filter.of(true, DeliveryAlerts.COMPLETE, false), 10);
        assertEquals(0, completions.delivered(), "only when complete: no deliveries");
        assertEquals(1, completions.complete());
        assertEquals(List.of(NoticeSummary.EXPIRED, NoticeSummary.COMPLETE), completions.details().stream().map(OrderStore.NoticeRow::kind).toList(),
            "no ending warning with the warnings off");

        NoticeSummary nothing = NoticeSummary.of(rows, NoticeSummary.Filter.of(true, DeliveryAlerts.OFF, true), 10);
        assertEquals(List.of(NoticeSummary.EXPIRED, NoticeSummary.ENDING), nothing.details().stream().map(OrderStore.NoticeRow::kind).toList(),
            "delivery alerts off still warns about endings: that is its own setting");

        NoticeSummary refunds = NoticeSummary.of(rows, NoticeSummary.Filter.of(false, DeliveryAlerts.CHAT, true), 10);
        assertEquals(2_000, refunds.refunded(), "refunds always show");
        assertEquals(0, refunds.delivered());
        assertEquals(0, refunds.complete());
        assertEquals(List.of(NoticeSummary.EXPIRED), refunds.details().stream().map(OrderStore.NoticeRow::kind).toList(),
            "with the summary off only the refund");
        assertEquals(rows, refunds.shown(), "every row is still deleted once read");
        assertTrue(NoticeSummary.of(List.of(row(1, NoticeSummary.DELIVERED, 1, 1)), NoticeSummary.Filter.REFUNDS_ONLY, 4).empty());
    }

    @Test
    void announcementsNeedTheServerTheOwnerAndTheViewer() {
        assertTrue(OwnerNotices.announced(1_000_000, 1_000_000, true));
        assertFalse(OwnerNotices.announced(1_000_000, 999_999, true), "below the server's minimum");
        assertFalse(OwnerNotices.announced(0, Long.MAX_VALUE, true), "the server announces nothing");
        assertFalse(OwnerNotices.announced(1_000_000, 5_000_000, false), "the owner turned it off");
        Announce from10m = OrdersFeature.ANNOUNCEMENTS.decodeOrNull("10m");
        assertFalse(from10m.shows(9_999_999));
        assertTrue(from10m.shows(10_000_000));
        assertTrue(Announce.ALL.shows(1));
        assertFalse(Announce.OFF.shows(Long.MAX_VALUE));
    }
}
