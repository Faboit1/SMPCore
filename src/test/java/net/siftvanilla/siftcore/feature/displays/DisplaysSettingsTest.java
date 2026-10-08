package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisplaysSettingsTest {

    private static final String DEFAULTS = """
        defaults:
          billboard: center
          alignment: center
          line-width: 200
          scale: 1.0
          see-through: false
          text-shadow: true
          background: none
          view-range: 64
          full-bright: true
          refresh: 60s
        interaction:
          enabled: true
          cooldown: 1s
          commands:
            board: "/baltop"
        templates:
          board:
            - "<primary>Richest"
            - "<secondary>1. <primary>{baltop_name_1} <money>{baltop_value_1}"
        """;

    private DisplaysTestSupport.FakeContext context;
    private ConfigReader reader;

    @BeforeEach
    void setUp() {
        this.context = new DisplaysTestSupport.FakeContext(DisplaysTestSupport.style());
    }

    private DisplaysSettings parse(String yaml) {
        this.reader = new ConfigReader("features/displays.yml", DisplaysTestSupport.yaml(yaml));
        return DisplaysSettings.parse(this.reader, this.context);
    }

    private List<String> problems() {
        return this.reader.problems().stream().map(ConfigProblem::toString).toList();
    }

    private void expectProblem(String path, String fragment) {
        List<ConfigProblem> matching = this.reader.problems().stream()
            .filter(p -> p.path().equals(path) && p.message().contains(fragment)).toList();
        assertEquals(1, matching.size(), "expected one problem at '" + path + "' containing '" + fragment + "' in " + problems());
    }

    @Test
    void theShippedFileHasNoProblems() {
        this.reader = new ConfigReader("features/displays.yml", DisplaysTestSupport.bundled("features/displays.yml"));
        DisplaysSettings settings = DisplaysSettings.parse(this.reader, this.context);
        assertEquals(List.of(), problems());
        assertEquals(List.of("richest", "top-kills", "most-active", "welcome"), List.copyOf(settings.templates().keySet()));
        assertEquals(List.of("richest", "top-kills", "most-active", "welcome"), List.copyOf(settings.displays().keySet()));
        assertTrue(settings.displays().values().stream().allMatch(d -> d.position() == null), "shipped displays wait to be placed");
        DisplayOptions defaults = settings.defaults();
        assertEquals(Display.Billboard.CENTER, defaults.billboard());
        assertEquals(Background.NONE, defaults.background());
        assertFalse(defaults.seeThrough());
        assertEquals(Duration.ofSeconds(60), defaults.refresh());
        assertEquals(240, settings.displays().get("welcome").options().lineWidth(), "a display overrides one key");
        assertEquals(200, settings.displays().get("richest").options().lineWidth());
        assertEquals("baltop", settings.clickCommands().get("richest"));
        assertEquals("top kills", settings.clickCommands().get("top-kills"));
        assertNull(settings.clickCommands().get("welcome"));
    }

    @Test
    void placeholdersNoFeatureProvidesAreProblemsOnReload() {
        for (int i = 1; i <= 10; i++) {
            this.context.unknown.add("top_kills_name_" + i);
        }
        this.reader = new ConfigReader("features/displays.yml", DisplaysTestSupport.bundled("features/displays.yml"));
        DisplaysSettings.parse(this.reader, this.context);
        assertEquals(1, this.reader.problems().size(), "one problem per template: " + problems());
        expectProblem("templates.top-kills", "uses placeholders that no feature provides: {top_kills_name_1}, {top_kills_name_2}");
        expectProblem("templates.top-kills", "{top_kills_name_10} (/sift placeholders lists them)");
    }

    @Test
    void perPlayerPlaceholdersAreRejected() {
        this.context.perPlayer.add("baltop_name_1");
        parse(DEFAULTS);
        expectProblem("templates.board", "different for every player, so a display can't show them: {baltop_name_1}");
        assertEquals(1, this.reader.problems().size(), problems().toString());
    }

    @Test
    void aDisplayWithAPositionAndOverrides() {
        DisplaysSettings settings = parse(DEFAULTS + """
            displays:
              spawn-top:
                template: board
                world: world
                x: 10.5
                y: 64
                z: -3.25
                yaw: 90
                billboard: fixed
                alignment: left
                scale: 1.5
                background: "#40000000"
                view-range: 32
                refresh: 15s
            """);
        assertEquals(List.of(), problems());
        DisplaysSettings.ConfigDisplay display = settings.displays().get("spawn-top");
        assertEquals(new DisplayPosition("world", 10.5, 64, -3.25, 90f), display.position());
        DisplayOptions options = display.options();
        assertEquals(Display.Billboard.FIXED, options.billboard());
        assertEquals(TextDisplay.TextAlignment.LEFT, options.alignment());
        assertEquals(1.5f, options.scale());
        assertEquals(new Background(Background.Mode.COLOR, 0x40000000), options.background());
        assertEquals(0.5f, options.viewRangeMultiplier());
        assertEquals(Duration.ofSeconds(15), options.refresh());
        assertTrue(options.textShadow(), "keys that are not overridden come from defaults");
    }

    @Test
    void unknownTemplateAndWorldAreReportedPrecisely() {
        parse(DEFAULTS + """
            displays:
              a:
                template: nope
              b:
                template: board
                world: lobby
                x: 0
                y: 64
                z: 0
            """);
        expectProblem("displays.a.template", "'nope' is not a template in this file (templates: board)");
        expectProblem("displays.b.world", "'lobby' is not a loaded world (loaded: world, world_nether)");
        assertEquals(2, this.reader.problems().size(), problems().toString());
    }

    @Test
    void badNumbersAndValuesAreReportedPrecisely() {
        parse(DEFAULTS + """
            displays:
              a:
                template: board
                scale: 0
                line-width: wide
                view-range: 9999
                refresh: 5x
                background: red
                billboard: sideways
                see-through: maybe
            """);
        expectProblem("displays.a.scale", "must be between 0.1 and 10.0");
        expectProblem("displays.a.line-width", "must be a whole number");
        expectProblem("displays.a.view-range", "must be between 1 and 512");
        expectProblem("displays.a.refresh", "unknown unit");
        expectProblem("displays.a.background", "expected none, default or a colour");
        expectProblem("displays.a.billboard", "must be one of fixed, vertical, horizontal, center");
        expectProblem("displays.a.see-through", "must be true or false");
        assertEquals(7, this.reader.problems().size(), problems().toString());
    }

    @Test
    void aPartialPositionNamesWhatIsMissing() {
        parse(DEFAULTS + """
            displays:
              a:
                template: board
                world: world
                x: 1
              b:
                template: board
                yaw: 90
              c:
                template: board
                world: world
                x: 1
                y: 99999
                z: 1
            """);
        expectProblem("displays.a.y", "is missing");
        expectProblem("displays.a.z", "is missing");
        expectProblem("displays.b.yaw", "needs world, x, y and z");
        expectProblem("displays.c.y", "must be between");
        assertEquals(4, this.reader.problems().size(), problems().toString());
    }

    @Test
    void namesAndTemplatesAreValidated() {
        parse(DEFAULTS + """
            displays:
              Bad Name:
                template: board
              ok:
                world: world
                x: 0
                y: 64
                z: 0
            """);
        expectProblem("displays.Bad Name", "is not a valid display name");
        expectProblem("displays.ok.template", "is missing");
    }

    @Test
    void theDefaultsSectionMustBeComplete() {
        parse("""
            defaults:
              billboard: center
            interaction:
              enabled: false
              cooldown: 0s
            templates: {}
            """);
        expectProblem("defaults.alignment", "is missing");
        expectProblem("defaults.refresh", "is missing");
        assertEquals(9, this.reader.problems().size(), problems().toString());
    }

    @Test
    void clickCommandsNeedAKnownTemplateAndText() {
        DisplaysSettings settings = parse(DEFAULTS
            .replace("board: \"/baltop\"", "board: \"/baltop\"\n    ghost: \"top\"\n    info: \"  \"")
            + "  info:\n    - \"<primary>Info\"\n");
        expectProblem("interaction.commands.ghost", "is not a template in this file");
        expectProblem("interaction.commands.info", "is empty");
        assertEquals("baltop", settings.clickCommands().get("board"), "a leading slash is dropped");
        assertNull(settings.clickCommands().get("info"));
        assertEquals(2, this.reader.problems().size(), problems().toString());
    }

    @Test
    void templatesUsedByDisplaysPlacedInGameMustStay() {
        this.context.placed.put("hall", "gone");
        this.context.placed.put("lobby", "board");
        parse(DEFAULTS);
        expectProblem("templates.gone", "the display 'hall' placed in-game uses it");
        assertEquals(1, this.reader.problems().size(), problems().toString());
    }

    @Test
    void templateProblemsCarryTheLineNumber() {
        parse(DEFAULTS.replace("- \"<primary>Richest\"", "- \"<bold>Richest\""));
        expectProblem("templates.board", "line 1 uses tags that are not allowed: <bold>");
    }

    @Test
    void backgroundsParse() {
        assertEquals(Background.NONE, Background.parse("none"));
        assertEquals(Background.DEFAULT, Background.parse(" Default "));
        assertEquals(0x80112233, Background.parse("#80112233").argb());
        assertEquals(0xFF112233, Background.parse("#112233").argb());
        assertNotNull(Background.parse("#ffffffff"));
    }
}
