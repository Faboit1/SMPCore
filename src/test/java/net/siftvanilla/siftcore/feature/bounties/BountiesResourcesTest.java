package net.siftvanilla.siftcore.feature.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped config and text load without a single problem and follow the design rules. */
class BountiesResourcesTest {

    private static Lang lang;
    private static YamlConfiguration langYaml;

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = BountiesResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void loadText() throws Exception {
        Icons icons = new Icons(Icons.readIndex(BountiesResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(BountiesMessages.class);
        langYaml = yaml("lang/bounties.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/bounties.yml");
        assertEquals(List.of(), problems);
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/bounties.yml", yaml("features/bounties.yml"));
        BountiesSettings settings = BountiesSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        assertEquals(1_000, settings.minimum());
        assertEquals(100_000, settings.confirmAbove());
        assertEquals(Duration.ofSeconds(5), settings.placeCooldown());
        assertTrue(settings.announcePlacements());
        assertEquals(50_000, settings.announceAbove());
        assertTrue(settings.notifyTarget());
        assertEquals(0, settings.taxPercent(), "no tax: the owner removed every fee");
        assertTrue(settings.announceClaims());
        assertTrue(settings.notifySponsors());
        assertEquals(Duration.ofDays(14), settings.expireAfter());
        assertEquals(Duration.ofMinutes(5), settings.expiryCheck());
        assertEquals(50, settings.listSize());
    }

    @Test
    void badValuesAreReportedPrecisely() throws Exception {
        YamlConfiguration broken = yaml("features/bounties.yml");
        broken.set("place.minimum", "0");
        broken.set("claim.tax-percent", 95);
        broken.set("expiry.after", "10m");
        broken.set("list-size", 500);
        ConfigReader reader = new ConfigReader("features/bounties.yml", broken);
        BountiesSettings settings = BountiesSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(4, reader.problems().size(), reader.problems().toString());
        assertEquals(1_000, settings.minimum());
        assertEquals(0, settings.taxPercent());
        assertEquals(Duration.ofDays(14), settings.expireAfter());
        assertEquals(50, settings.listSize());
    }

    @Test
    void everyLangEntryBelongsToAMessage() {
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("bounties."))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(registered, inFile);
    }

    @Test
    void linesRenderTheirValues() {
        assertEquals("You put $5,000 on Alex. Their bounty is now $12,000.", lang.plain(BountiesMessages.PLACED,
            Arg.money("amount", 5_000), Arg.text("name", "Alex"), Arg.money("total", 12_000)));
        assertEquals("Sam claimed the $50,000 bounty on Alex.", lang.plain(BountiesMessages.CLAIM_ANNOUNCE,
            Arg.text("killer", "Sam"), Arg.money("total", 50_000), Arg.text("name", "Alex")));
        assertEquals("You claimed $45,000 for killing Alex. $5,000 went to tax.", lang.plain(BountiesMessages.CLAIMED,
            Arg.money("payout", 45_000), Arg.text("name", "Alex"), Arg.money("tax", 5_000)));
        assertEquals("2. Alex $9,000 from 3 players", lang.plain(BountiesMessages.LIST_LINE, Arg.number("rank", 2),
            Arg.text("name", "Alex"), Arg.money("total", 9_000), Arg.text("sponsors", "3 players")));
        assertEquals("Bounty on Alex", lang.plain(BountiesMessages.DETAILS_TITLE, Arg.text("name", "Alex")));
        List<String> confirm = lang.lines(BountiesMessages.CONFIRM_BODY, Arg.money("amount", 150_000), Arg.text("name", "Alex"),
            Arg.text("time", "14d")).stream().map(TextStyle::plain).toList();
        assertEquals(List.of("Put $150,000 on Alex?", "You get it back if nobody kills them within 14d."), confirm,
            "no tax named in the confirmation itself");
        assertEquals("The killer gets it minus 10% tax.", lang.plain(BountiesMessages.CONFIRM_TAX, Arg.text("tax", "10")),
            "the tax line, only while there is a tax");
        assertEquals("10% of it goes to tax.", lang.plain(BountiesMessages.DETAILS_TAX_TOOLTIP, Arg.text("tax", "10")));
    }

    @Test
    void titlesAndButtonsArePlain() {
        for (MessageKey key : List.of(BountiesMessages.LIST_TITLE, BountiesMessages.DETAILS_TITLE, BountiesMessages.FORM_TITLE,
            BountiesMessages.CONFIRM_TITLE, BountiesMessages.CONFIRM_BUTTON, BountiesMessages.LIST_PLACE, BountiesMessages.DETAILS_ADD,
            BountiesMessages.DETAILS_PLACE, BountiesMessages.HUB_LABEL)) {
            String raw = String.join("", langYaml.getStringList(key.path()).isEmpty()
                ? List.of(langYaml.getString(key.path(), "")) : langYaml.getStringList(key.path()));
            assertFalse(raw.contains("<primary>") || raw.contains("<secondary>") || raw.contains("<icon"), key.path() + " is styled: " + raw);
        }
    }

    @Test
    void messagesHaveNoLeftoverTagsOrStraySpaces() {
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "x")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertFalse(plain.contains("<") || plain.contains(">"), key.path() + ": " + plain);
            assertFalse(plain.matches("(?s).* [.,!?:].*"), "space before punctuation in " + key.path() + ": " + plain);
            assertFalse(plain.matches("(?s).*\\b[A-Z]{4,}\\b.*"), "all caps in " + key.path() + ": " + plain);
            assertFalse(plain.contains("  "), "double space in " + key.path() + ": " + plain);
        }
    }
}
