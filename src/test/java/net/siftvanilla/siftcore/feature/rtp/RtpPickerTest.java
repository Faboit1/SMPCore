package net.siftvanilla.siftcore.feature.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.MergedLang;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The random teleport picker: buttons only (nothing above them), each place in its colour, how far out and whether it
 * is ready in the tooltip, and money mentioned only for a place that costs something.
 */
class RtpPickerTest {

    private static Lang lang;
    private static Templates templates;

    @BeforeAll
    static void load() {
        lang = MergedLang.of(List.of("lang/core.yml", "lang/rtp.yml"), CoreMessages.class, RtpMessages.class);
        templates = new Templates(lang);
    }

    private static RtpSettings.Region region(String id, long cost, TextColor color) {
        return new RtpSettings.Region(id, id.substring(0, 1).toUpperCase() + id.substring(1), true, "world", null, cost,
            Duration.ofSeconds(60), 0, 0, 300, 4_800, color);
    }

    private static String plain(Component text) {
        return text == null ? "" : TextStyle.plain(text);
    }

    @Test
    void freePlacesSayNothingAboutMoney() {
        List<String> picked = new ArrayList<>();
        View view = RtpPicker.view(lang, templates, List.of(
                new RtpPicker.Choice(region("overworld", 0, TextColor.color(0x86EFAC)), 4_800, Duration.ZERO),
                new RtpPicker.Choice(region("nether", 0, null), 2_300, Duration.ofSeconds(42))),
            region -> s -> picked.add(region.id()), null);
        assertTrue(view.body().isEmpty(), "no intro above the buttons: " + view.body());
        List<Button> buttons = view.allButtons();
        assertEquals(List.of("Overworld", "Nether (42s)", "Close"), buttons.stream().map(b -> plain(b.label())).toList());
        assertEquals(TextColor.color(0x86EFAC), buttons.get(0).label().color(), "the place's own colour");
        String ready = plain(buttons.get(0).tooltip());
        assertTrue(ready.contains("Lands 300 to 4,800 blocks out") && ready.contains("Ready, click to go"), ready);
        String waiting = plain(buttons.get(1).tooltip());
        assertTrue(waiting.contains("Ready again in 42s"), waiting);
        for (Button button : buttons) {
            String all = plain(button.label()) + " " + plain(button.tooltip());
            assertTrue(!all.contains("$") && !all.toLowerCase().contains("free") && !all.contains("Costs"), "no money talk: " + all);
        }
        buttons.get(1).handler().handle(null);
        assertEquals(List.of("nether"), picked);
    }

    @Test
    void aPlaceThatCostsShowsItsPrice() {
        View view = RtpPicker.view(lang, templates, List.of(new RtpPicker.Choice(region("end", 5_000, null), 2_800, Duration.ZERO)),
            region -> s -> { }, s -> { });
        String tooltip = plain(view.buttons().getFirst().tooltip());
        assertTrue(tooltip.contains("Costs $5") && tooltip.contains("paid once a safe spot is found"), tooltip);
        assertEquals("Back", plain(view.exit().label()));
    }
}
