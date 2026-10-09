package net.siftvanilla.siftcore.feature.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Ring and border arithmetic, and the shipped rtp config checked against the live server's borders. */
class RtpBorderTest {

    /** The live server: pre-generated to exactly these borders. */
    private static final Map<String, BorderSpec> LIVE = Map.of(
        "world", new BorderSpec(0, 0, 10_000),
        "world_nether", new BorderSpec(0, 0, 5_000),
        "world_the_end", new BorderSpec(0, 0, 6_000));

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = RtpBorderTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static RtpSettings parse(YamlConfiguration yaml, Map<String, BorderSpec> borders, List<ConfigProblem> problems) {
        ConfigReader reader = new ConfigReader("features/rtp.yml", yaml);
        RtpSettings settings = RtpSettings.parse(reader, MoneyFormat.defaults(), world -> Optional.ofNullable(borders.get(world)),
            LIVE::containsKey);
        problems.addAll(reader.problems());
        return settings;
    }

    @Test
    void reachIsTheLargestCircleInsideTheSquare() {
        BorderSpec border = new BorderSpec(0, 0, 10_000);
        assertEquals(5_000, RtpGeometry.reach(border, 0, 0, 0));
        assertEquals(4_968, RtpGeometry.reach(border, 0, 0, 32));
        assertEquals(3_968, RtpGeometry.reach(border, 1_000, -200, 32), "the centre's larger offset counts");
        assertEquals(-32, RtpGeometry.reach(border, 5_000, 0, 32), "a centre on the edge leaves nothing");
        assertEquals(4_800, RtpGeometry.usableMax(4_800, border, 0, 0, 32));
        assertEquals(3_968, RtpGeometry.usableMax(4_800, border, 1_000, 0, 32), "cut down to what fits");
        assertEquals(4_800, RtpGeometry.usableMax(4_800, null, 0, 0, 32), "no border, no limit");
    }

    @Test
    void problemTextIsPrecise() {
        BorderSpec border = new BorderSpec(0, 0, 10_000);
        assertNull(RtpGeometry.problem("world", border, 0, 0, 300, 4_968, 32), "exactly fits");
        String tooFar = RtpGeometry.problem("world", border, 0, 0, 300, 6_000, 32);
        assertNotNull(tooFar);
        assertTrue(tooFar.contains("is 6,000"), tooFar);
        assertTrue(tooFar.contains("'world' (10,000 wide around 0, 0)"), tooFar);
        assertTrue(tooFar.contains("at most 4,968"), tooFar);
        assertTrue(tooFar.contains("lower it to 4,968 or grow the border by 2,064"), tooFar);
        String hopeless = RtpGeometry.problem("world", border, 4_000, 0, 1_500, 2_000, 32);
        assertNotNull(hopeless);
        assertTrue(hopeless.contains("not even the minimum radius"), hopeless);
    }

    @Test
    void shippedRingsFitTheLiveBorders() throws Exception {
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        RtpSettings settings = parse(yaml("features/rtp.yml"), LIVE, problems);
        assertEquals(List.of(), problems);
        assertEquals(Duration.ofSeconds(5), settings.warmup());
        assertEquals(12, settings.maxAttempts());
        assertEquals(32, settings.borderMargin());
        assertEquals(List.of("overworld", "nether", "end"), List.copyOf(settings.regions().keySet()));
        RtpSettings.Region overworld = settings.regions().get("overworld");
        assertEquals(300, overworld.minRadius());
        assertEquals(4_800, overworld.maxRadius());
        assertEquals(0, overworld.cost());
        assertEquals(Duration.ofSeconds(60), overworld.cooldown());
        RtpSettings.Region nether = settings.regions().get("nether");
        assertEquals(200, nether.minRadius());
        assertEquals(2_300, nether.maxRadius());
        assertEquals(0, nether.cost(), "every shipped place is free");
        RtpSettings.Region end = settings.regions().get("end");
        assertEquals(1_000, end.minRadius());
        assertEquals(2_800, end.maxRadius());
        assertEquals(0, end.cost());
        assertEquals(net.kyori.adventure.text.format.TextColor.color(0xFF8A65), nether.color(), "each place has its button colour");
        assertNotNull(overworld.color());
        assertNotNull(end.color());
        assertTrue(!RtpFeature.anyPaidRegion(settings.regions().values()), "so the price confirmation isn't offered");
        assertEquals("world_the_end", end.world());
        assertEquals(nether, settings.find("world_nether").orElseThrow(), "regions can be named by their world");
        assertEquals(end, settings.find("END").orElseThrow());
    }

    @Test
    void aCostAndAColourCanStillBeSet() throws Exception {
        YamlConfiguration yaml = yaml("features/rtp.yml");
        yaml.set("regions.end.cost", "5k");
        yaml.set("colors.end", "#123456");
        yaml.set("colors.nether", "not a colour");
        yaml.set("colors.gone", "#FFFFFF");
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        RtpSettings settings = parse(yaml, LIVE, problems);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().toString().contains("'colors.nether'"), problems.toString());
        assertEquals(5_000, settings.regions().get("end").cost());
        assertEquals(net.kyori.adventure.text.format.TextColor.color(0x123456), settings.regions().get("end").color());
        assertNull(settings.regions().get("nether").color(), "a bad colour leaves the button white");
        assertEquals(3, settings.regions().size(), "a colour for a place that doesn't exist adds nothing");
        assertTrue(RtpFeature.anyPaidRegion(settings.regions().values()), "the price confirmation is offered again");
    }

    @Test
    void ringsPastTheBorderAreRefusedWithTheExactLimit() throws Exception {
        YamlConfiguration yaml = yaml("features/rtp.yml");
        yaml.set("regions.nether.max-radius", 2_600);
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        parse(yaml, LIVE, problems);
        assertEquals(1, problems.size(), problems.toString());
        String text = problems.getFirst().toString();
        assertTrue(text.startsWith("features/rtp.yml: 'regions.nether.max-radius' is 2,600"), text);
        assertTrue(text.contains("'world_nether' (5,000 wide around 0, 0) leaves room for at most 2,468"), text);
    }

    @Test
    void aSmallerBorderMakesTheShippedRingsFail() throws Exception {
        Map<String, BorderSpec> shrunk = Map.of("world", new BorderSpec(0, 0, 8_000), "world_nether", LIVE.get("world_nether"),
            "world_the_end", LIVE.get("world_the_end"));
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        parse(yaml("features/rtp.yml"), shrunk, problems);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().toString().contains("'regions.overworld.max-radius'"), problems.toString());
    }

    @Test
    void otherMistakesAreReported() throws Exception {
        YamlConfiguration yaml = yaml("features/rtp.yml");
        yaml.set("regions.overworld.min-radius", 5_000);
        yaml.set("regions.overworld.max-radius", 4_000);
        yaml.set("regions.end.world", "world_end");
        yaml.set("regions.nether.cost", "lots");
        yaml.set("regions.Bad Id.world", "world");
        yaml.set("max-attempts", 0);
        List<ConfigProblem> problems = new java.util.ArrayList<>();
        parse(yaml, LIVE, problems);
        String all = problems.toString();
        assertTrue(all.contains("'regions.overworld.max-radius' is 4000 but must be larger than min-radius (5000)"), all);
        assertTrue(all.contains("'regions.end.world' is 'world_end'"), all);
        assertTrue(all.contains("'regions.nether.cost'"), all);
        assertTrue(all.contains("'regions.Bad Id'"), all);
        assertTrue(all.contains("'max-attempts'"), all);
    }

    @Test
    void textLoadsAndEveryEntryBelongsToAMessage() throws Exception {
        Icons icons = new Icons(Icons.readIndex(RtpBorderTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(RtpMessages.class);
        YamlConfiguration langYaml = yaml("lang/rtp.yml");
        assertEquals(List.of(), lang.load(langYaml, langYaml, "lang/rtp.yml"));
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("rtp.")).collect(Collectors.toSet());
        Set<String> inFile = new TreeSet<>();
        for (String key : langYaml.getKeys(true)) {
            if (!langYaml.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        assertEquals(new TreeSet<>(registered), inFile);
    }
}
