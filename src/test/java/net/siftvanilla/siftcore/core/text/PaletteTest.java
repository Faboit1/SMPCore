package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class PaletteTest {

    private final Palette palette = Palette.defaults();

    @Test
    void errorsTurnPrimaryAndSecondaryRedAndKeepMoney() {
        Component message = Component.text("You need ")
            .append(Component.text("$500", this.palette.money()))
            .append(Component.text(" more.", NamedTextColor.GRAY))
            .color(this.palette.primary());
        Component red = this.palette.asError(message);
        assertEquals(Palette.DEFAULT_ERROR, red.color());
        assertEquals(this.palette.money(), red.children().get(0).color(), "money amounts keep their colour");
        assertEquals(Palette.DEFAULT_ERROR_SECONDARY, red.children().get(1).color());
    }

    @Test
    void uncolouredTextBecomesRed() {
        assertEquals(Palette.DEFAULT_ERROR, this.palette.asError(Component.text("Nope.")).color());
    }

    @Test
    void otherColoursStay() {
        TextColor gold = NamedTextColor.GOLD;
        assertEquals(gold, this.palette.asError(Component.text("x", gold)).color());
    }
}
