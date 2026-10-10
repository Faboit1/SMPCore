package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.permission.Permissions;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

/** The bundled config and text load without a problem, follow the design system, and ship the planned perks. */
class CosmeticsResourcesTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream in = CosmeticsResourcesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is bundled");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static Lang lang() throws Exception {
        Icons icons;
        try (InputStream in = CosmeticsResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CosmeticsMessages.class);
        return lang;
    }

    private static CosmeticsSettings settings() throws Exception {
        ConfigReader reader = new ConfigReader("features/cosmetics.yml", yaml("features/cosmetics.yml"));
        CosmeticsSettings settings = CosmeticsSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    @Test
    void langFileIsCompleteAndFollowsTheDesignSystem() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/cosmetics.yml");
        List<ConfigProblem> problems = lang.load(file, file, "lang/cosmetics.yml");
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
            assertFalse(raw.contains("<bold>") || raw.contains("<b>") || raw.contains("<gradient"), key.path() + " is decorated: " + raw);
        }
    }

    @Test
    void pickersMarkTheChosenOptionAndNamePerksPlainly() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/cosmetics.yml");
        assertEquals(List.of(), lang.load(file, file, "lang/cosmetics.yml"));
        assertEquals("Gold (now)", lang.plain(CosmeticsMessages.CURRENT_OPTION, Arg.text("option", "Gold")));
        assertEquals("locked", lang.plain(CosmeticsMessages.MENU_LOCKED));
        for (String path : List.of("cosmetics.menu.body", "cosmetics.color.body", "cosmetics.color.premium-body",
            "cosmetics.color.custom-body", "cosmetics.nick.body", "cosmetics.tags.body", "cosmetics.join.body", "cosmetics.join.rank-body",
            "cosmetics.kill.body", "cosmetics.page", "cosmetics.next-page")) {
            assertFalse(file.contains(path), path + ": explanations live in tooltips now, and nothing is paged");
        }
        assertEquals("Click to use it.", lang.plain(CosmeticsMessages.TAGS_PICK_TOOLTIP));
    }

    @Test
    void langFileHasNoUnusedEntries() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/cosmetics.yml");
        List<String> unused = new ArrayList<>();
        for (String path : file.getKeys(true)) {
            if (!file.isConfigurationSection(path) && !lang.registered().containsKey(path)) {
                unused.add(path);
            }
        }
        assertEquals(List.of(), unused);
    }

    @Test
    void everyVanillaColourAndKillEffectHasAName() throws Exception {
        Lang lang = lang();
        YamlConfiguration file = yaml("lang/cosmetics.yml");
        lang.load(file, file, "lang/cosmetics.yml");
        for (NamedTextColor color : NamedTextColor.NAMES.values()) {
            String name = lang.plain(CosmeticsMessages.colorName(color));
            assertTrue(Character.isUpperCase(name.charAt(0)), NamedTextColor.NAMES.key(color) + ": " + name);
        }
        for (KillEffect effect : KillEffect.values()) {
            assertFalse(lang.plain(CosmeticsMessages.name(effect)).isBlank(), effect.id());
            assertFalse(lang.plain(CosmeticsMessages.description(effect)).isBlank(), effect.id());
        }
    }

    @Test
    void shippedConfigHasTheTiersOfThePerkMatrix() throws Exception {
        CosmeticsSettings settings = settings();
        assertTrue(settings.enabled());
        assertEquals(8, settings.colors().basic().size(), "eight vanilla colours for Baron");
        for (NamedTextColor reserved : List.of(NamedTextColor.RED, NamedTextColor.DARK_RED, NamedTextColor.GREEN, NamedTextColor.DARK_GREEN)) {
            assertFalse(settings.colors().basic().contains(reserved), NamedTextColor.NAMES.key(reserved) + " is not offered");
        }
        ColorRules rules = settings.colors().rules(Palette.defaults());
        for (NamedTextColor color : settings.colors().basic()) {
            assertTrue(rules.check(color).allowed(), NamedTextColor.NAMES.key(color));
        }
        assertEquals(18, settings.colors().presets().size(), "every shipped preset passes the rules");
        for (CosmeticsSettings.Preset preset : settings.colors().presets()) {
            assertTrue(rules.check(preset.style()).allowed(), preset.id());
            assertTrue(preset.style().premium(), preset.id() + " is a hex colour or gradient");
        }
        Map<String, Long> perTier = new java.util.TreeMap<>();
        int monthly = 0;
        for (ChatTag tag : settings.tags().values()) {
            if (tag.month() != null) {
                monthly++;
                assertEquals(CosmeticsNodes.TAGS_TYCOON, tag.permission(), "monthly exclusives are Tycoon's");
                assertEquals(YearMonth.of(2026, 10), tag.month());
            } else {
                perTier.merge(tag.permission(), 1L, Long::sum);
            }
            assertTrue(tag.plain().length() <= 24, tag.id());
        }
        assertEquals(Map.of(CosmeticsNodes.TAGS_PROSPECTOR, 3L, CosmeticsNodes.TAGS_BARON, 7L, CosmeticsNodes.TAGS_TYCOON, 6L), perTier);
        assertEquals(1, monthly);
        assertEquals("[Miner]", settings.tags().get("miner").plain());
        assertEquals(new CosmeticsSettings.Join(true, Duration.ofSeconds(60), 40), settings.join());
        assertEquals(List.of(KillEffect.values()), settings.killEffects().effects(), "at least six effects, all on");
        assertTrue(settings.killEffects().effects().size() >= 6);
        assertEquals(Duration.ofSeconds(3), settings.killEffects().cooldown());
        assertEquals(CosmeticsSettings.DEFAULT_RESERVED_WORDS, settings.nicknames().reservedWords());
        assertEquals(3, settings.nicknames().minLength());
        assertEquals(16, settings.nicknames().maxLength());
        assertEquals(Duration.ofDays(14), settings.nicknames().hold(), "a nickname is kept two weeks after its holder could last show it");
        assertTrue(settings.colors().reserved().isEmpty(), "aqua and purple stay the owner's choice (colors.reserved)");
    }

    @Test
    void brokenEntriesAreReportedAndSkipped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", true);
        yaml.set("colors.basic", List.of("gold", "red", "dark_gray", "pink"));
        yaml.set("colors.presets.bad.name", "Bad");
        yaml.set("colors.presets.bad.style", "#FFAA00:#FF55FF");
        yaml.set("colors.presets.good.name", "Good");
        yaml.set("colors.presets.good.style", "#55FFFF:#5555FF");
        yaml.set("colors.min-distance", 20);
        yaml.set("colors.min-contrast", 3.0);
        yaml.set("nicknames.min-length", 3);
        yaml.set("nicknames.max-length", 16);
        yaml.set("nicknames.reserved-words", List.of("admin", "x y"));
        yaml.set("nicknames.cooldown", "30s");
        yaml.set("nicknames.hold", "14d");
        yaml.set("tags.list.ok.display", "<gradient:#FF6AD5:#B26BFF>Ok</gradient>");
        yaml.set("tags.list.ok.permission", "siftcore.tags.baron");
        yaml.set("tags.list.late.display", "Late");
        yaml.set("tags.list.late.permission", "siftcore.tags.tycoon");
        yaml.set("tags.list.late.month", "October");
        yaml.set("join-messages.enabled", true);
        yaml.set("join-messages.cooldown", "60s");
        yaml.set("join-messages.max-length", 40);
        yaml.set("kill-effects.enabled", true);
        yaml.set("kill-effects.effects", List.of("hearts", "confetti"));
        yaml.set("kill-effects.cooldown", "3s");
        yaml.set("kill-effects.max-per-second", 4);
        yaml.set("kill-effects.range", 32);
        ConfigReader reader = new ConfigReader("features/cosmetics.yml", yaml);
        CosmeticsSettings settings = CosmeticsSettings.parse(reader);
        assertEquals(List.of(NamedTextColor.GOLD), settings.colors().basic());
        assertEquals(1, settings.colors().presets().size());
        assertEquals(List.of("admin"), settings.nicknames().reservedWords());
        assertEquals(List.of("ok"), List.copyOf(settings.tags().keySet()));
        assertEquals(List.of(KillEffect.HEARTS), settings.killEffects().effects());
        List<String> paths = reader.problems().stream().map(ConfigProblem::toString).toList();
        assertEquals(7, reader.problems().size(), "red, dark gray, pink, the gradient through red, 'x y', the month, confetti: " + paths);
    }

    @Test
    void rankNodesIncludeTheTiersBelow() {
        Permissions permissions = new Permissions();
        CosmeticsNodes.declare(permissions);
        assertEquals(Map.of(CosmeticsNodes.TAGS_BARON, true, CosmeticsNodes.TAGS_PROSPECTOR, true),
            permissions.childrenOf(CosmeticsNodes.TAGS_TYCOON));
        assertEquals(Map.of(CosmeticsNodes.TAGS_PROSPECTOR, true), permissions.childrenOf(CosmeticsNodes.TAGS_BARON));
        assertEquals(Map.of(CosmeticsNodes.CHAT_COLOR, true), permissions.childrenOf(CosmeticsNodes.CHAT_COLOR_HEX));
        assertEquals(Map.of(CosmeticsNodes.NICK, true), permissions.childrenOf(CosmeticsNodes.NICK_GRADIENT));
        assertEquals(Map.of(CosmeticsNodes.JOIN, true), permissions.childrenOf(CosmeticsNodes.JOIN_CUSTOM));
        Map<String, Boolean> effects = permissions.childrenOf(CosmeticsNodes.KILL_EFFECTS);
        assertEquals(KillEffect.values().length, effects.size());
        assertTrue(effects.containsKey("siftcore.killeffect.lightning"));
        assertEquals(PermissionDefault.TRUE, permissions.all().get(CosmeticsNodes.TAGS).defaultValue(), "everyone sees /tags");
        assertEquals(PermissionDefault.OP, permissions.all().get(CosmeticsNodes.CHAT_COLOR).defaultValue(), "perks come with ranks");
        assertTrue(permissions.childrenOf(CosmeticsNodes.CHAT_COLOR).isEmpty());
    }

    @Test
    void reservedPaletteColoursAreTheOnesInUse() {
        Palette custom = new Palette(NamedTextColor.WHITE, NamedTextColor.GRAY, TextColor.color(0x00FFAA), TextColor.color(0xFF2222), null);
        ColorRules rules = new CosmeticsSettings.Colors(List.of(), List.of(), 20, 3, List.of()).rules(custom);
        assertEquals(ColorRules.Reason.MONEY, rules.check(TextColor.color(0x00FFAA)).reserved(), "a changed money colour is reserved");
        assertEquals(ColorRules.Reason.ERRORS, rules.check(TextColor.color(0xFF2222)).reserved(), "a changed error colour is reserved");
    }
}
