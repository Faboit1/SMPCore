package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.Fakes;
import org.junit.jupiter.api.Test;

/** The dialog style's helpers: switch and choice buttons, one- and two-column button lists, the number picker. */
class TemplatesTest {

    private final Templates templates = new Templates(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class));
    private final Palette palette = Palette.defaults();

    private static String plain(Component text) {
        return TextStyle.plain(text);
    }

    /** Every coloured, non-blank part of a text, in order. */
    private static List<TextColor> colours(Component text) {
        List<TextColor> colours = new ArrayList<>();
        collect(text, colours);
        return colours;
    }

    private static void collect(Component text, List<TextColor> colours) {
        if (text.color() != null && !plain(text).isBlank()) {
            colours.add(text.color());
        }
        for (Component child : text.children()) {
            collect(child, colours);
        }
    }

    @Test
    void aSwitchReadsOnInGreenOrOffInRed() {
        Button on = this.templates.switchButton(Component.text("Sounds"), true, Component.text("Play sounds."), s -> { });
        assertEquals("Sounds: ON", plain(on.label()));
        assertEquals(this.palette.on(), colours(on.label()).getLast(), "ON in the on colour");
        assertEquals("Play sounds.", plain(on.tooltip()));
        Button off = this.templates.switchButton(Component.text("Sounds"), false, null, s -> { });
        assertEquals("Sounds: OFF", plain(off.label()));
        assertEquals(this.palette.off(), colours(off.label()).getLast(), "OFF in the off colour");
        assertEquals(this.palette.primary(), colours(off.label()).getFirst(), "the label stays plain");
        assertEquals(Button.After.NEXT, off.after(), "the page shows again with the new state");
    }

    @Test
    void aChoiceShowsItsValueInTheAccentColourUnlessItHasItsOwn() {
        Button choice = this.templates.choiceButton(Component.text("Mention sound"), Component.text("Bell"), null, s -> { });
        assertEquals("Mention sound: Bell", plain(choice.label()));
        assertEquals(this.palette.accent(), colours(choice.label()).getLast());
        Button money = this.templates.choiceButton(Component.text("Confirm from"), Component.text("$10,000", this.palette.money()), null,
            s -> { });
        assertEquals(this.palette.money(), colours(money.label()).getLast(), "a value keeps its own colour");
    }

    @Test
    void columnsAndGridsShowButtonsOnlyWithBackAtTheEnd() {
        List<Button> buttons = List.of(Button.of(Component.text("A"), s -> { }), Button.of(Component.text("B"), s -> { }));
        View column = this.templates.column(Component.text("List"), buttons, s -> { });
        assertEquals(1, column.columns());
        assertTrue(column.body().isEmpty() && column.inputs().isEmpty());
        assertTrue(column.buttons().stream().allMatch(button -> button.width() == Templates.LONG));
        assertEquals("Back", plain(column.exit().label()));
        View grid = this.templates.grid(Component.text("List"), buttons, null);
        assertEquals(2, grid.columns());
        assertTrue(grid.buttons().stream().allMatch(button -> button.width() == Templates.HALF));
        assertEquals("Close", plain(grid.exit().label()));
        assertNull(grid.exit().handler(), "Close just closes");
        View withLine = this.templates.column(Component.text("List"), List.of(Component.text("You have 3.")), buttons, null);
        assertEquals(1, withLine.body().size(), "a short status line when the player needs it");
    }

    @Test
    void aNumberIsPickedOnASliderWithDone() {
        Input.Range range = new Input.Range("volume", Component.text("Volume (%)"), 0, 100, 10, 60, null, Templates.LONG);
        View view = this.templates.number(Component.text("Volume"), range, s -> { }, s -> { });
        assertEquals(View.Kind.FORM, view.kind());
        assertEquals(List.of(range), view.inputs());
        assertEquals(List.of("Done", "Back"), view.buttons().stream().map(button -> plain(button.label())).toList());
    }

    @Test
    void tooltipLinesJoinAndAButtonTakesOne() {
        assertNull(Templates.lines(List.of()));
        assertEquals("one\ntwo", plain(Templates.lines(List.of(Component.text("one"), Component.text("two")))));
        Button button = Button.of(Component.text("A"), s -> { }).closes().tooltip(Component.text("Does A."));
        assertEquals("Does A.", plain(button.tooltip()));
        assertEquals(Button.After.CLOSE, button.after(), "the rest of the button stays");
    }
}
