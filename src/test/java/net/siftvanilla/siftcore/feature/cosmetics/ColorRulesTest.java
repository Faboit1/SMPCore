package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.Palette;
import org.junit.jupiter.api.Test;

/** The colour rules: CIEDE2000, readability and the reserved red and green. */
class ColorRulesTest {

    private static final ColorRules RULES = ColorRules.defaults(Palette.DEFAULT_ERROR, TextColor.color(0x1AFF1A));

    private static TextColor hex(int value) {
        return TextColor.color(value);
    }

    @Test
    void deltaE2000MatchesThePublishedTestData() {
        // Sharma, Wu and Dalal (2005): pairs of the published CIEDE2000 test data.
        assertEquals(2.0425, ColorRules.deltaE2000(new double[] {50.0, 2.6772, -79.7751}, new double[] {50.0, 0.0, -82.7485}), 1e-4);
        assertEquals(0.0, ColorRules.deltaE2000(new double[] {50.0, 0.0, 0.0}, new double[] {50.0, 0.0, 0.0}), 1e-9);
        assertEquals(2.3669, ColorRules.deltaE2000(new double[] {50.0, 0.0, 0.0}, new double[] {50.0, -1.0, 2.0}), 1e-4);
        assertEquals(27.1492, ColorRules.deltaE2000(new double[] {50.0, 2.5, 0.0}, new double[] {73.0, 25.0, -18.0}), 1e-4);
        assertEquals(1.2644, ColorRules.deltaE2000(new double[] {60.2574, -34.0099, 36.2677}, new double[] {60.4626, -34.1751, 39.4387}), 1e-4);
        assertEquals(2.0373, ColorRules.deltaE2000(new double[] {22.7233, 20.0904, -46.6940}, new double[] {23.0331, 14.9730, -42.5619}), 1e-4);
        assertEquals(1.2630, ColorRules.deltaE2000(new double[] {63.0109, -31.0961, -5.8663}, new double[] {62.8187, -29.7946, -4.0864}), 1e-4);
    }

    @Test
    void labAndContrastAreStandard() {
        double[] white = ColorRules.lab(hex(0xFFFFFF));
        assertEquals(100.0, white[0], 1e-3);
        assertEquals(0.0, white[1], 1e-2);
        assertEquals(0.0, white[2], 1e-2);
        double[] red = ColorRules.lab(hex(0xFF0000));
        assertEquals(53.24, red[0], 0.01);
        assertEquals(80.09, red[1], 0.01);
        assertEquals(67.20, red[2], 0.01);
        assertEquals(21.0, ColorRules.contrast(hex(0xFFFFFF)), 1e-6);
        assertEquals(1.0, ColorRules.contrast(hex(0x000000)), 1e-6);
        assertEquals(4.13, ColorRules.contrast(NamedTextColor.BLUE), 0.01);
    }

    @Test
    void theErrorRedAndItsNeighboursAreRefused() {
        for (int color : new int[] {0xFF5555, 0xFF4B4B, 0xFF0000, 0xE64545, 0xFF6060, 0xFF7F7F, 0xFA8072, 0xFF6347, 0xDC143C, 0xCC3333,
            0xFF7F50, 0xFF9E9E}) {
            ColorRules.Verdict verdict = RULES.check(hex(color));
            assertFalse(verdict.allowed(), Integer.toHexString(color) + " is refused");
            assertEquals(ColorRules.Reason.ERRORS, verdict.reserved(), Integer.toHexString(color) + " is kept for errors");
        }
    }

    @Test
    void theMoneyGreenAndItsNeighboursAreRefused() {
        // Sea greens (#3CB371, #2E8B57), forest and olive greens and the greyish #8FBC8F read as green too.
        for (int color : new int[] {0x1AFF1A, 0x00FF00, 0x55FF55, 0x9DFB2B, 0x33CC33, 0x7FFF00, 0x00FF7F, 0xB5FF4A, 0x00AA00, 0x3CB371,
            0x2E8B57, 0x8FBC8F, 0x27AE60, 0x2ECC71, 0x228B22, 0x6B8E23, 0x4CAF50, 0x00FA9A, 0x90EE90}) {
            ColorRules.Verdict verdict = RULES.check(hex(color));
            assertFalse(verdict.allowed(), Integer.toHexString(color) + " is refused");
            assertEquals(ColorRules.Reason.MONEY, verdict.reserved(), Integer.toHexString(color) + " is kept for money");
        }
    }

    @Test
    void darkColoursAreRefusedAsUnreadable() {
        for (int color : new int[] {0x000000, 0x555555, 0x0000AA, 0x333333, 0x220044, 0xAA0000}) {
            ColorRules.Verdict verdict = RULES.check(hex(color));
            assertFalse(verdict.allowed(), Integer.toHexString(color) + " is refused");
            assertTrue(verdict.tooDark(), Integer.toHexString(color) + " is too dark");
        }
    }

    @Test
    void colourfulReadableColoursPass() {
        for (NamedTextColor color : List.of(NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.AQUA, NamedTextColor.DARK_AQUA,
            NamedTextColor.BLUE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.DARK_PURPLE, NamedTextColor.GRAY, NamedTextColor.WHITE)) {
            assertTrue(RULES.check(color).allowed(), NamedTextColor.NAMES.key(color) + " is allowed");
        }
        for (int color : new int[] {0xFF6AD5, 0xB26BFF, 0x5FA8FF, 0xFFD27A, 0xFFB07A, 0xFF8800, 0x87CEEB, 0xE58BFF, 0x7FFFD4, 0xC8A2C8,
            0xFF69B4, 0x3CC4EE}) {
            assertTrue(RULES.check(hex(color)).allowed(), Integer.toHexString(color) + " is allowed");
        }
        assertNull(RULES.check(hex(0xFFAA00)).reserved());
    }

    @Test
    void coloursNextToGreenButNotGreenStayFree() {
        // Aquamarine, turquoise and teal sit between green and aqua; gold and yellow between green and red.
        for (int color : new int[] {0x7FFFD4, 0x66CDAA, 0x40E0D0, 0x20B2AA, 0x48D1CC, 0xFFFF55, 0xF0E68C, 0x87CEEB, 0x8C9EFF, 0x915DFF}) {
            assertTrue(RULES.check(hex(color)).allowed(), Integer.toHexString(color) + " is allowed");
        }
        for (String gradient : new String[] {"#55FFFF:#5555FF", "#FF6AD5:#B26BFF", "#FFFF55:#FFAA00", "#55FFFF:#B26BFF", "#FFFFFF:#55FFFF",
            "#E58BFF:#8C9EFF", "#FFD27A:#B26BFF", "#5FA8FF:#FF6AD5", "#FFD27A:#FFAA00", "#B26BFF:#5FA8FF"}) {
            assertTrue(RULES.check(ChatStyle.parse(gradient)).allowed(), gradient + " (a shipped preset) is allowed");
        }
    }

    @Test
    void gradientsAreCheckedAlongTheirWholeLength() {
        assertTrue(RULES.check(hex(0xFF6AD5), hex(0xB26BFF)).allowed(), "the Tycoon gradient");
        assertTrue(RULES.check(hex(0x55FFFF), hex(0x5555FF)).allowed(), "aqua to blue");
        ColorRules.Verdict throughRed = RULES.check(hex(0xFFAA00), hex(0xFF55FF));
        assertFalse(throughRed.allowed(), "gold to pink blends through salmon red");
        assertEquals(ColorRules.Reason.ERRORS, throughRed.reserved());
        assertEquals(ColorRules.Reason.MONEY, RULES.check(hex(0x55FFFF), hex(0x1AFF1A)).reserved(), "ending in money green");
        assertTrue(RULES.check(hex(0xFFFFFF), hex(0x333333)).tooDark(), "fading into dark gray");
        assertEquals(0x808080, ColorRules.lerp(hex(0xFFFFFF), hex(0x000000), 0.5).value());
    }

    @Test
    void stylesAreChecked() {
        assertTrue(RULES.check(ChatStyle.NONE).allowed());
        assertTrue(RULES.check(ChatStyle.vanilla(NamedTextColor.GOLD)).allowed());
        assertFalse(RULES.check(ChatStyle.vanilla(NamedTextColor.RED)).allowed());
        assertFalse(RULES.check(ChatStyle.vanilla(NamedTextColor.GREEN)).allowed());
        assertFalse(RULES.check(ChatStyle.vanilla(NamedTextColor.DARK_GREEN)).allowed());
        assertFalse(RULES.check(new ChatStyle.Gradient(hex(0xFFAA00), hex(0xFF55FF))).allowed());
    }

    @Test
    void ownerReservedColoursAndStricterSettingsApply() {
        ColorRules rules = new ColorRules(List.of(
            new ColorRules.Reserved(Palette.DEFAULT_ERROR, ColorRules.Reason.ERRORS),
            new ColorRules.Reserved(hex(0x915DFF), ColorRules.Reason.SERVER)), 20.0, 4.5);
        assertEquals(ColorRules.Reason.SERVER, rules.check(hex(0x9060F8)).reserved(), "close to the shard purple");
        assertTrue(rules.check(NamedTextColor.BLUE).tooDark(), "blue is under a 4.5 contrast");
        assertTrue(rules.check(NamedTextColor.GREEN).allowed(), "green is fine when money green is not reserved");
    }
}
