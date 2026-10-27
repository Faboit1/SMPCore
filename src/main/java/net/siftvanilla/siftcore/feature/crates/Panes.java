package net.siftvanilla.siftcore.feature.crates;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** The stained glass pane closest to a colour, for the frame of the opening animation. Pure. */
final class Panes {

    /** A pane and the colour it looks like (its dye's colour as the client draws the glass). */
    private record Pane(Material material, int rgb) {
    }

    private static final List<Pane> PANES = List.of(
        new Pane(Material.WHITE_STAINED_GLASS_PANE, 0xF9FFFE),
        new Pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, 0x9D9D97),
        new Pane(Material.GRAY_STAINED_GLASS_PANE, 0x474F52),
        new Pane(Material.BLACK_STAINED_GLASS_PANE, 0x1D1D21),
        new Pane(Material.BROWN_STAINED_GLASS_PANE, 0x835432),
        new Pane(Material.RED_STAINED_GLASS_PANE, 0xB02E26),
        new Pane(Material.ORANGE_STAINED_GLASS_PANE, 0xF9801D),
        new Pane(Material.YELLOW_STAINED_GLASS_PANE, 0xFED83D),
        new Pane(Material.LIME_STAINED_GLASS_PANE, 0x80C71F),
        new Pane(Material.GREEN_STAINED_GLASS_PANE, 0x5E7C16),
        new Pane(Material.CYAN_STAINED_GLASS_PANE, 0x169C9C),
        new Pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, 0x3AB3DA),
        new Pane(Material.BLUE_STAINED_GLASS_PANE, 0x3C44AA),
        new Pane(Material.PURPLE_STAINED_GLASS_PANE, 0x8932B8),
        new Pane(Material.MAGENTA_STAINED_GLASS_PANE, 0xC74EBD),
        new Pane(Material.PINK_STAINED_GLASS_PANE, 0xF38BAA));

    /** Bright panes for the flashing frame while the reel rolls. */
    static final List<Material> BRIGHT = List.of(Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
        Material.YELLOW_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
        Material.CYAN_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE,
        Material.PINK_STAINED_GLASS_PANE, Material.WHITE_STAINED_GLASS_PANE);

    private Panes() {
    }

    /** The glass the rarity names ({@code minecraft:red_stained_glass_pane}), else the pane nearest to its colour. */
    static Material of(Rarity rarity) {
        if (rarity.glass() != null) {
            String key = rarity.glass();
            Material material = Material.getMaterial(key.substring(key.indexOf(':') + 1).toUpperCase(java.util.Locale.ROOT));
            if (material != null && !material.isLegacy()) {
                return material;
            }
        }
        return nearest(rarity.color());
    }

    /** The pane whose colour is nearest to {@code color} (weighted RGB distance, the way eyes see it). */
    static Material nearest(TextColor color) {
        Pane best = PANES.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (Pane pane : PANES) {
            double distance = distance(color.value(), pane.rgb());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pane;
            }
        }
        return best.material();
    }

    /** The "redmean" colour distance: cheap and close to how different two colours look. */
    static double distance(int a, int b) {
        int r1 = a >> 16 & 0xFF;
        int g1 = a >> 8 & 0xFF;
        int b1 = a & 0xFF;
        int r2 = b >> 16 & 0xFF;
        int g2 = b >> 8 & 0xFF;
        int b2 = b & 0xFF;
        double meanRed = (r1 + r2) / 2.0;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return Math.sqrt((2 + meanRed / 256) * dr * dr + 4 * dg * dg + (2 + (255 - meanRed) / 256) * db * db);
    }
}
