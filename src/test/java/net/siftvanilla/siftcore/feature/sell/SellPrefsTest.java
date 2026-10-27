package net.siftvanilla.siftcore.feature.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.Test;

/** The selling settings: group and order, config-dependent offering, old switch rows, level-up lines and selling on close. */
class SellPrefsTest {

    private static final String PRICES = """
        craft-loss: 0.9
        recipe-types: [crafting]
        base-prices:
          diamond: 400
        """;

    /** A sell.yml with these {@code sell-all} lines and further top-level lines. */
    private static SellSettings parse(String sellAll, String extra) {
        String yaml = PRICES + "sell-all:\n  skip-unstackable: true\n  skip-hotbar: false\n" + sellAll.indent(2) + extra;
        List<ConfigProblem> problems = new ArrayList<>();
        SellSettings settings = SellSettingsTest.parse(yaml, problems);
        assertEquals(List.of(), problems);
        return settings;
    }

    private static List<String> offered(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.ECONOMY.id()).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = SellPrefsTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void sellingSettingsSitInMoneyAndSellingInCatalogOrder() {
        AtomicReference<SellSettings> config = new AtomicReference<>(parse("", ""));
        AtomicBoolean orders = new AtomicBoolean(true);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        SellPrefs.register(settings, config::get, orders::get);
        assertTrue(settings.hasReader(SharedSettings.SELL_RECEIPTS), "selling reads the shared sale receipts");
        assertTrue(settings.hasReader(SharedSettings.HIDE_FROM_LEADERBOARDS), "the top sellers leave out hidden players");
        assertEquals(List.of("sell_receipts", "sell_all_confirm", "sell-all-hotbar", "sell_orders", "sell-all-shulkers",
            "sell-menu-close", "mastery-levelup"), offered(settings));

        orders.set(false);
        assertFalse(offered(settings).contains("sell_orders"), "no buy orders, no switch for them");
        config.set(parse("""
            confirm: always
            shulker-contents: false
            """, """
            shulker-contents: true
            mastery:
              enabled: false
            """));
        assertEquals(List.of("sell_receipts", "sell-all-hotbar", "sell-menu-close"), offered(settings),
            "the server always asks, never opens boxes on /sell all and has no mastery: those choices would do nothing");
        config.set(parse("confirm: never\n", "shulker-contents: false\n"));
        assertFalse(offered(settings).contains("sell-all-shulkers"), "boxes are never opened at all");
        assertFalse(offered(settings).contains("sell_all_confirm"), "the server never asks");
    }

    @Test
    void oldSwitchRowsStillDecode() {
        assertEquals(ConfirmAbove.SERVER, SellPrefs.CONFIRM.decodeOrNull("true"), "asking on: from the server's amount, as before");
        assertEquals(ConfirmAbove.NEVER, SellPrefs.CONFIRM.decodeOrNull("false"), "asking off: never, as before");
        assertEquals(List.of("server", "always", "10k", "100k", "1m", "never"), SellPrefs.CONFIRM.optionIds());
        assertEquals(ConfirmAbove.SERVER, SellPrefs.CONFIRM.defaultValue());
        assertEquals(10_000, SellPrefs.CONFIRM.decodeOrNull("10K").amount());
        assertTrue(SellPrefs.ORDERS.defaultOn());
        assertEquals("sell_orders", SellPrefs.ORDERS.id(), "the existing id is kept");
        assertEquals(List.of("server", "keep", "sell"), SellPrefs.HOTBAR.optionIds());
        assertEquals(SellPrefs.Hotbar.SERVER, SellPrefs.HOTBAR.defaultValue());
        assertTrue(SellPrefs.SHULKERS.defaultOn());
        assertEquals(List.of("return", "sell"), SellPrefs.MENU_CLOSE.optionIds());
        assertEquals(SellPrefs.MenuClose.RETURN, SellPrefs.MENU_CLOSE.defaultValue());
        assertEquals(List.of("chat", "actionbar", "title", "off"), SellPrefs.LEVEL_UP.optionIds());
        assertEquals(AlertStyle.CHAT, SellPrefs.LEVEL_UP.defaultValue());
    }

    @Test
    void levelUpsFollowTheStyle() {
        AlertStyle chat = AlertStyle.CHAT;
        assertNull(SellPrefs.levelUpNotice(AlertStyle.OFF, chat, false, 2));
        assertNull(SellPrefs.levelUpNotice(chat, chat, false, 0), "no level-up, nothing to tell");
        assertEquals(new SellPrefs.LevelUpNotice(null, chat, true), SellPrefs.levelUpNotice(chat, chat, false, 1));
        assertEquals(new SellPrefs.LevelUpNotice(null, chat, true), SellPrefs.levelUpNotice(chat, AlertStyle.ACTIONBAR, false, 3),
            "chat lines never cover anything: one per level-up");
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP, AlertStyle.ACTIONBAR, false),
            SellPrefs.levelUpNotice(AlertStyle.ACTIONBAR, chat, false, 1));
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP, AlertStyle.ACTIONBAR, false),
            SellPrefs.levelUpNotice(AlertStyle.ACTIONBAR, AlertStyle.OFF, false, 1));
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP_TITLE, AlertStyle.TITLE, false),
            SellPrefs.levelUpNotice(AlertStyle.TITLE, chat, false, 1));
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP_TITLE, AlertStyle.TITLE, false),
            SellPrefs.levelUpNotice(AlertStyle.TITLE, AlertStyle.ACTIONBAR, false, 1), "a title does not cover the hotbar total");
        assertEquals(new SellPrefs.LevelUpNotice(null, chat, true), SellPrefs.levelUpNotice(AlertStyle.TITLE, chat, true, 1),
            "quiet in combat makes the title a chat line, which gets the full text");
    }

    @Test
    void oneSaleSendsOnePopUp() {
        AlertStyle chat = AlertStyle.CHAT;
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP_MANY, AlertStyle.ACTIONBAR, true),
            SellPrefs.levelUpNotice(AlertStyle.ACTIONBAR, chat, false, 2),
            "two level-ups above the hotbar would replace each other: one count there, the lines in chat");
        assertEquals(new SellPrefs.LevelUpNotice(SellMessages.LEVEL_UP_MANY_TITLE, AlertStyle.TITLE, true),
            SellPrefs.levelUpNotice(AlertStyle.TITLE, AlertStyle.OFF, false, 3));
        assertEquals(new SellPrefs.LevelUpNotice(null, chat, true), SellPrefs.levelUpNotice(AlertStyle.ACTIONBAR, chat, true, 2),
            "quiet in combat: chat lines only");
    }

    @Test
    void aLevelUpNeverCoversTheReceipt() {
        AlertStyle bar = AlertStyle.ACTIONBAR;
        assertEquals(new SellPrefs.LevelUpNotice(null, AlertStyle.CHAT, true), SellPrefs.levelUpNotice(bar, bar, false, 1),
            "the sale total is above the hotbar: the level-up goes to chat");
        assertEquals(new SellPrefs.LevelUpNotice(null, AlertStyle.CHAT, true), SellPrefs.levelUpNotice(bar, bar, false, 2));
        assertEquals(new SellPrefs.LevelUpNotice(null, AlertStyle.CHAT, true), SellPrefs.levelUpNotice(bar, bar, true, 1),
            "in quiet combat both are chat lines anyway");
    }

    @Test
    void onlyAPlayersOwnCloseSells() {
        SellPrefs.MenuClose sell = SellPrefs.MenuClose.SELL;
        assertTrue(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.PLAYER, false));
        assertFalse(SellPrefs.sellsOnClose(SellPrefs.MenuClose.RETURN, InventoryCloseEvent.Reason.PLAYER, false));
        assertFalse(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.PLAYER, true), "dying drops the grid");
        assertFalse(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.DISCONNECT, false), "quitting always gives back");
        assertFalse(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.TELEPORT, false));
        assertFalse(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.OPEN_NEW, false), "another screen gives back");
        assertFalse(SellPrefs.sellsOnClose(sell, InventoryCloseEvent.Reason.PLUGIN, false));
        assertFalse(SellPrefs.sellsOnClose(sell, null, false), "no close event: give back");
    }

    @Test
    void theSellTextLoadsWithoutProblems() throws Exception {
        Icons icons = new Icons(Icons.readIndex(SellPrefsTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(SellMessages.class);
        YamlConfiguration file = yaml("lang/sell.yml");
        assertEquals(List.of(), lang.load(file, file, "lang/sell.yml"));
        assertEquals("Mining mastery 3", PlainTextComponentSerializer.plainText().serialize(lang.get(SellMessages.LEVEL_UP_TITLE,
            Arg.text("category", "Mining"), Arg.number("level", 3), Arg.text("multiplier", "1.15"))));
        assertEquals("Confirm /sell all from", lang.plain(SellPrefs.CONFIRM.label()));
    }
}
