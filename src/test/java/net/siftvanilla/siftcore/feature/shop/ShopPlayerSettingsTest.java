package net.siftvanilla.siftcore.feature.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The shop settings: group and order, the confirmation threshold, the start amount of the buy window and the receipt choice. */
class ShopPlayerSettingsTest {

    @Test
    void settingsSitInTheMarketGroupInCatalogOrder() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        ShopFeature.registerSettings(settings);
        List<Registry.Entry<?>> market = settings.registry().in(SettingCategories.MARKET.id());
        assertEquals(List.of("shop-confirm-above", "shop-default-amount", "shop-receipts"), market.stream().map(Registry.Entry::id).toList());
        assertEquals(List.of(3, 6, 9), market.stream().map(entry -> entry.options().order()).toList());
        assertTrue(market.stream().allMatch(Registry.Entry::offered));
    }

    @Test
    void theOptionsUseTheSharedVocabularies() {
        assertEquals(List.of("server", "always", "10k", "100k", "1m", "never"), ShopFeature.CONFIRM_ABOVE.optionIds());
        assertEquals(ConfirmAbove.SERVER, ShopFeature.CONFIRM_ABOVE.defaultValue());
        assertEquals(List.of("stack", "one", "last", "fill"), ShopFeature.DEFAULT_AMOUNT.optionIds());
        assertEquals(StartAmount.STACK, ShopFeature.DEFAULT_AMOUNT.defaultValue());
        assertEquals(List.of("chat", "actionbar"), ShopFeature.RECEIPTS.optionIds());
        assertEquals(AlertStyle.CHAT, ShopFeature.RECEIPTS.defaultValue());
    }

    @Test
    void confirmationFollowsTheBuyersChoice() {
        long server = 50_000;
        assertFalse(PurchaseFlow.asks(ConfirmAbove.SERVER, 49_999, server), "below the server's amount");
        assertTrue(PurchaseFlow.asks(ConfirmAbove.SERVER, 50_000, server), "at the server's amount");
        assertFalse(PurchaseFlow.asks(ConfirmAbove.SERVER, 5_000_000, 0), "the server's 0 never asks");
        assertTrue(PurchaseFlow.asks(ConfirmAbove.ALWAYS, 1, server));
        assertFalse(PurchaseFlow.asks(ConfirmAbove.NEVER, Long.MAX_VALUE, server));
        ConfirmAbove from10k = ShopFeature.CONFIRM_ABOVE.decodeOrNull("10k");
        assertFalse(PurchaseFlow.asks(from10k, 9_999, server));
        assertTrue(PurchaseFlow.asks(from10k, 10_000, server), "stricter than the server");
        ConfirmAbove from1m = ShopFeature.CONFIRM_ABOVE.decodeOrNull("1m");
        assertFalse(PurchaseFlow.asks(from1m, 500_000, server), "looser than the server is the player's choice");
    }

    @Test
    void theSettingTextIsInTheLangFile() throws Exception {
        Icons icons;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(ShopMessages.class);
        YamlConfiguration file;
        try (InputStreamReader reader = new InputStreamReader(getClass().getClassLoader().getResourceAsStream("lang/shop.yml"),
            StandardCharsets.UTF_8)) {
            file = YamlConfiguration.loadConfiguration(reader);
        }
        assertEquals(List.of(), lang.load(file, file, "lang/shop.yml"), "every shop message has text");
        assertEquals("Buy window starts at", lang.plain(ShopMessages.SETTING_DEFAULT_AMOUNT));
        assertEquals("My last amount", lang.plain(ShopMessages.SETTING_DEFAULT_AMOUNT_LAST));
    }

    @Test
    void theBuyWindowStartsWhereThePlayerWants() {
        // A stackable item: stacks of 64, at most 2,304 per purchase.
        assertEquals(64, StartAmount.STACK.start(64, 2_304, 0, 0));
        assertEquals(16, StartAmount.STACK.start(16, 2_304, 0, 0), "ender pearls stack to 16");
        assertEquals(10, StartAmount.STACK.start(64, 10, 0, 0), "capped at the purchase limit");
        assertEquals(1, StartAmount.STACK.start(1, 64, 0, 0), "spawners start at one");
        assertEquals(1, StartAmount.ONE.start(64, 2_304, 0, 0));
        assertEquals(200, StartAmount.LAST.start(64, 2_304, 200, 0), "the last amount");
        assertEquals(10, StartAmount.LAST.start(64, 10, 200, 0), "capped when the limit dropped since");
        assertEquals(64, StartAmount.LAST.start(64, 2_304, 0, 0), "never bought: one stack");
        assertEquals(500, StartAmount.FILL.start(64, 2_304, 0, 500), "what fits");
        assertEquals(2_304, StartAmount.FILL.start(64, 2_304, 0, 10_000), "capped at the limit");
        assertEquals(64, StartAmount.FILL.start(64, 2_304, 0, 0), "a full inventory starts at one stack");
    }
}
