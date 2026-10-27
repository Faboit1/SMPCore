package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

/** How displays.yml and the positions set in-game combine. */
class DisplayCatalogTest {

    private static final DisplayPosition FILE_SPOT = new DisplayPosition("world", 1, 64, 1, 0f);
    private static final DisplayPosition GAME_SPOT = new DisplayPosition("world", 50, 70, -20, 90f);

    private final TextStyle style = DisplaysTestSupport.style();

    private DisplayTemplate template(String id) {
        return DisplayTemplate.compile(id, List.of("<primary>" + id), this.style, (line, message) -> {
            throw new AssertionError(message);
        });
    }

    private DisplaysSettings settings(boolean interaction, Map<String, DisplaysSettings.ConfigDisplay> displays) {
        DisplayOptions big = new DisplayOptions(Display.Billboard.FIXED, TextDisplay.TextAlignment.LEFT, 300, 2f, false, true,
            Background.DEFAULT, 32, false, Duration.ofSeconds(30));
        Map<String, DisplaysSettings.ConfigDisplay> withOptions = new java.util.LinkedHashMap<>();
        displays.forEach((id, d) -> withOptions.put(id, new DisplaysSettings.ConfigDisplay(d.id(), d.template(), d.position(), big)));
        return new DisplaysSettings(Map.of("richest", template("richest"), "welcome", template("welcome")), DisplayOptions.FALLBACK,
            withOptions, interaction, Map.of("richest", "baltop"), Duration.ofSeconds(1));
    }

    private static DisplaysSettings.ConfigDisplay configured(String id, String template, DisplayPosition position) {
        return new DisplaysSettings.ConfigDisplay(id, template, position, DisplayOptions.FALLBACK);
    }

    @Test
    void aFileDisplayWithoutPositionWaitsToBePlaced() {
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings(true, Map.of("richest", configured("richest", "richest", null))), Map.of());
        DisplayDef def = defs.get("richest");
        assertTrue(def.inConfig());
        assertFalse(def.placed());
        assertNull(def.position());
        assertFalse(def.showable());
        assertEquals("baltop", def.clickCommand());
    }

    @Test
    void anInGamePositionWinsOverTheFileAndKeepsTheFileTemplateAndLooks() {
        DisplaysSettings settings = settings(true, Map.of("richest", configured("richest", "richest", FILE_SPOT)));
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings,
            Map.of("richest", new Placement("richest", null, GAME_SPOT, "console", 1L)));
        DisplayDef def = defs.get("richest");
        assertEquals(GAME_SPOT, def.position());
        assertSame(settings.templates().get("richest"), def.template());
        assertEquals(2f, def.options().scale(), "looks come from the file");
        assertTrue(def.inConfig() && def.placed() && def.showable());
    }

    @Test
    void theFileTemplateWinsOverAStoredOne() {
        DisplaysSettings settings = settings(true, Map.of("richest", configured("richest", "richest", null)));
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings,
            Map.of("richest", new Placement("richest", "welcome", GAME_SPOT, "console", 1L)));
        assertEquals("richest", defs.get("richest").templateId());
    }

    @Test
    void aDisplayCreatedInGameUsesItsTemplateAndTheDefaultLooks() {
        DisplaysSettings settings = settings(true, Map.of());
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings,
            Map.of("hall", new Placement("hall", "welcome", GAME_SPOT, "console", 1L)));
        DisplayDef def = defs.get("hall");
        assertFalse(def.inConfig());
        assertTrue(def.placed() && def.showable());
        assertEquals("welcome", def.templateId());
        assertSame(DisplayOptions.FALLBACK, def.options());
        assertNull(def.clickCommand(), "welcome has no click command");
    }

    @Test
    void aRowWhoseTemplateIsGoneIsListedButNeverShown() {
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings(true, Map.of()), Map.of(
            "orphan", new Placement("orphan", null, GAME_SPOT, "console", 1L),
            "stale", new Placement("stale", "deleted-template", GAME_SPOT, "console", 1L)));
        assertFalse(defs.get("orphan").showable());
        assertNull(defs.get("orphan").templateId());
        assertFalse(defs.get("stale").showable());
        assertEquals("deleted-template", defs.get("stale").templateId());
    }

    @Test
    void clickBoxesFollowTheInteractionSwitch() {
        Map<String, DisplaysSettings.ConfigDisplay> displays = Map.of("richest", configured("richest", "richest", FILE_SPOT));
        assertEquals("baltop", DisplayCatalog.merge(settings(true, displays), Map.of()).get("richest").clickCommand());
        assertNull(DisplayCatalog.merge(settings(false, displays), Map.of()).get("richest").clickCommand());
    }

    @Test
    void displaysAreSortedByName() {
        Map<String, DisplayDef> defs = DisplayCatalog.merge(settings(true, Map.of("welcome", configured("welcome", "welcome", null))),
            Map.of("alpha", new Placement("alpha", "richest", GAME_SPOT, "console", 1L),
                "zulu", new Placement("zulu", "richest", GAME_SPOT, "console", 1L)));
        assertEquals(List.of("alpha", "welcome", "zulu"), List.copyOf(defs.keySet()));
    }
}
