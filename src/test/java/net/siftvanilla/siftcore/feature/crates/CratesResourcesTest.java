package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled {@code lang/crates.yml} is complete, follows the design system and reads well. */
class CratesResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = CratesResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = CratesResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CratesMessages.class);
        return lang;
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/crates.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/crates.yml");
        assertEquals(List.of(), problems);
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "Word")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + " leaves a tag behind: " + plain);
            assertFalse(plain.matches("(?s).*\\s[.,!?;:].*"), key.path() + " has a space before punctuation: " + plain);
            assertFalse(plain.isBlank(), key.path() + " is empty");
            String raw = String.join("\n", file.isList(key.path()) ? file.getStringList(key.path()) : List.of(file.getString(key.path())));
            assertFalse(raw.matches("(?s).*[A-Z]{3,}.*"), key.path() + " uses capitals: " + raw);
            assertFalse(raw.contains("!"), key.path() + " shouts: " + raw);
        }
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/crates.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }

    @Test
    void keyAmountsReadNaturally() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/crates.yml");
        lang.load(file, file, "lang/crates.yml");
        TextColor gray = TextColor.color(0xC8C8C8);
        TextColor gold = TextColor.color(0xFFB12E);
        Crate common = new Crate("basic", "Common", "minecraft:chest", List.of(new Reward("cash", 1, "common", "$750", false, null,
            new Reward.Money(750))), List.of(), 1, gray);
        Crate rare = new Crate("rare", "Rare", "minecraft:ender_chest", common.rewards(), List.of(), 3, TextColor.color(0x4DA6FF));
        CratesSettings settings = new CratesSettings(java.time.Duration.ofSeconds(1), true, 10, true, true, java.time.Duration.ofDays(90),
            new CratesSettings.Keyall(false, java.time.Duration.ofHours(4), "basic", 1, java.time.Duration.ZERO, false, true, List.of(),
                java.time.Duration.ZERO),
            List.of(new Rarity("common", "Common", false, false, gray), new Rarity("legendary", "Legendary", true, true, gold)),
            List.of(common, rare));
        CrateText text = new CrateText(lang, () -> settings);
        assertEquals("1 Common key", TextStyle.plain(text.keys(1, "basic")));
        assertEquals("3 Rare keys", TextStyle.plain(text.keys(3, rare)));
        assertEquals("2 gone keys", TextStyle.plain(text.keys(2, "gone")), "a crate that is gone reads as its id");
        assertEquals(gray, CrateText.name(common).color(), "a crate's name is in its colour");
        Reward elytra = new Reward("elytra", 1, "legendary", "an elytra", true, null,
            new Reward.Item("minecraft:elytra", 1, null, List.of(), java.util.Map.of()));
        assertEquals(gold, text.reward(elytra).color(), "a reward is in its rarity's colour");
        assertEquals(Palette.DEFAULT_SHARDS, text.reward(new Reward("s", 1, "common", "50 shards", false, null, new Reward.Shards(50))).color(),
            "shards are always in the shards colour");
        assertEquals("Legendary", TextStyle.plain(CrateText.rarity(settings.rarity("legendary"))));
        assertEquals(gold, CrateText.rarity(settings.rarity("legendary")).color());
        assertEquals("no keys", TextStyle.plain(text.count(0)));
        assertEquals("1 key", TextStyle.plain(text.count(1)));
        assertEquals("1,200 keys", TextStyle.plain(text.count(1200)));
        Component won = lang.get(CratesMessages.WON, Arg.component("reward", text.reward(
            new Reward("cash", 1, "common", "$750", false, null, new Reward.Money(750)))), Arg.text("name", "Basic"));
        assertEquals("You won $750 from the Basic crate.", TextStyle.plain(won));
        assertEquals("You won 3 diamonds from the Basic crate.", TextStyle.plain(lang.get(CratesMessages.WON,
            Arg.component("reward", text.reward(new Reward("d", 1, "common", "3 diamonds", true, null,
                new Reward.Item("minecraft:diamond", 3, null, List.of(), java.util.Map.of())))), Arg.text("name", "Basic"))));
        assertEquals("You opened 10 Basic crates. Best: $750", TextStyle.plain(lang.get(CratesMessages.BATCH_WON_SHORT,
            Arg.number("count", 10), Arg.text("name", "Basic"), Arg.text("reward", "$750"))));
    }

    @Test
    void settingTextsReadWell() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/crates.yml");
        lang.load(file, file, "lang/crates.yml");
        assertEquals(" keys", lang.plain(CratesMessages.UNIT_KEYS), "the unit keeps its space");
        assertEquals("10 keys", CratePlayerSettings.BULK_AMOUNT.display(lang, 10L));
        assertEquals("Crate win announcements", lang.plain(CratePlayerSettings.WIN_ANNOUNCEMENTS.label()));
        assertEquals("Rarest only", lang.plain(CratePlayerSettings.WinFilter.RAREST.label()));
        assertEquals("Crate window", lang.plain(CratePlayerSettings.QuickOpen.OFF.label()));
        for (MessageKey key : List.of(CratePlayerSettings.RECEIPT.description(), CratePlayerSettings.KEY_REMINDER.description(),
            CratePlayerSettings.KEYALL_COUNTDOWN.description(), CratePlayerSettings.QUICK_OPEN.description(),
            CratePlayerSettings.BULK_AMOUNT.description(), CratePlayerSettings.WIN_ANNOUNCEMENTS.description())) {
            String plain = lang.plain(key);
            assertTrue(plain.endsWith("."), key.path() + " is a sentence: " + plain);
        }
    }
}
