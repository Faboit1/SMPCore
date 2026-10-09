package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The economy's text: /eco replies name shards, and players without a daily pay limit see no "-" for it. */
class EconomyTextTest {

    private static Lang lang;

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = EconomyTextTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void load() throws Exception {
        Icons icons = new Icons(Icons.readIndex(EconomyTextTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(EconomyMessages.class);
        YamlConfiguration file = yaml("lang/economy.yml");
        assertEquals(List.of(), lang.load(file, file, "lang/economy.yml"));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String plain(List<Component> lines) {
        return String.join("\n", lines.stream().map(EconomyTextTest::plain).toList());
    }

    @Test
    void ecoRepliesNameTheShardsUnit() {
        assertSame(EconomyMessages.ECO_GIVEN, EconomyCommands.reply("give", Currency.MONEY));
        assertSame(EconomyMessages.ECO_TAKEN, EconomyCommands.reply("take", Currency.MONEY));
        assertSame(EconomyMessages.ECO_SET, EconomyCommands.reply("set", Currency.MONEY));
        assertEquals("Gave Alex 50 shards. They now have 1,250 shards.", plain(lang.get(EconomyCommands.reply("give", Currency.SHARDS),
            Arg.text("name", "Alex"), Arg.amount("amount", Currency.SHARDS, 50), Arg.amount("balance", Currency.SHARDS, 1_250))));
        assertEquals("Took 5 shards from Alex. They now have 45 shards.", plain(lang.get(EconomyCommands.reply("take", Currency.SHARDS),
            Arg.text("name", "Alex"), Arg.amount("amount", Currency.SHARDS, 5), Arg.amount("balance", Currency.SHARDS, 45))));
        assertEquals("Set Alex's shards to 50.", plain(lang.get(EconomyCommands.reply("set", Currency.SHARDS),
            Arg.text("name", "Alex"), Arg.amount("amount", Currency.SHARDS, 50))));
        assertEquals("Gave Alex $50. They now have $1,250.", plain(lang.get(EconomyCommands.reply("give", Currency.MONEY),
            Arg.text("name", "Alex"), Arg.amount("amount", Currency.MONEY, 50), Arg.amount("balance", Currency.MONEY, 1_250))));
    }

    @Test
    void noDailyLimitLineWithoutALimit() {
        String confirm = plain(lang.lines(EconomyMessages.PAY_CONFIRM_BODY_UNLIMITED, Arg.text("name", "Alex"),
            Arg.component("amount", Component.text("$1,500"))));
        assertEquals("Send $1,500 to Alex?\nPayments can't be undone.", confirm);
        String hub = plain(lang.lines(EconomyMessages.HUB_BODY_UNLIMITED, Arg.money("amount", 1_500), Arg.number("shards", 3),
            Arg.number("rank", 2)));
        assertFalse(hub.contains("send") || hub.contains(" - "), hub);
    }

    @Test
    void moneyPageSaysWhenTheDailyLimitCouldNotLoad() {
        RuntimeException failed = new RuntimeException("database down");
        // A player with a limit whose day's total failed to load gets the error line, not the page of someone without one.
        assertSame(EconomyFeature.HubLimit.UNKNOWN, EconomyFeature.HubLimit.of(250_000, null, failed));
        assertSame(EconomyFeature.HubLimit.UNKNOWN, EconomyFeature.HubLimit.of(250_000, null, null));
        assertSame(EconomyFeature.HubLimit.LEFT, EconomyFeature.HubLimit.of(250_000, 1_000L, null));
        // Without a limit there is nothing to load, so a failed load changes nothing.
        assertSame(EconomyFeature.HubLimit.NONE, EconomyFeature.HubLimit.of(Long.MAX_VALUE, 0L, null));
        assertSame(EconomyFeature.HubLimit.NONE, EconomyFeature.HubLimit.of(Long.MAX_VALUE, null, failed));
        assertEquals("Your daily pay limit couldn't be loaded. Open this page again to see it.",
            plain(lang.get(EconomyMessages.HUB_LIMIT_FAILED)));
        assertEquals(Palette.defaults().error(), lang.get(EconomyMessages.HUB_LIMIT_FAILED).color(), "rendered red");
    }
}
