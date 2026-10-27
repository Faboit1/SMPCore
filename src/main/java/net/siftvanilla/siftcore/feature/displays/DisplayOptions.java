package net.siftvanilla.siftcore.feature.displays;

import java.time.Duration;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/**
 * How a display looks and how often its placeholders are read again. {@code defaults} in displays.yml holds one
 * full set; each display may override any key.
 *
 * @param billboard  which way the text turns towards viewers
 * @param alignment  line alignment
 * @param lineWidth  the longest line in pixels before it wraps
 * @param scale      text size; 1 is the size of a name tag
 * @param seeThrough whether the text shows through blocks
 * @param textShadow whether letters get a shadow
 * @param background the box behind the text
 * @param viewRange  how far away it renders, in blocks, at the default client entity distance
 * @param fullBright whether the text ignores the light level
 * @param refresh    how often placeholders are read again
 */
public record DisplayOptions(
    Display.Billboard billboard,
    TextDisplay.TextAlignment alignment,
    int lineWidth,
    float scale,
    boolean seeThrough,
    boolean textShadow,
    Background background,
    int viewRange,
    boolean fullBright,
    Duration refresh) {

    /** Vanilla displays render up to this view range multiplier times 64 blocks. */
    public static final float BLOCKS_PER_VIEW_RANGE = 64.0f;

    /** Used for a broken {@code defaults} section so the server still starts. */
    public static final DisplayOptions FALLBACK = new DisplayOptions(Display.Billboard.VERTICAL, TextDisplay.TextAlignment.CENTER,
        200, 1.0f, false, true, Background.NONE, 64, true, Duration.ofSeconds(60));

    /** The vanilla view range multiplier for {@link #viewRange()} blocks. */
    public float viewRangeMultiplier() {
        return this.viewRange / BLOCKS_PER_VIEW_RANGE;
    }

    /**
     * Reads the keys of a defaults or display section. With {@code complete} every key must be present (the
     * defaults section); otherwise missing keys keep the value from {@code base}. Every present key is validated
     * and a bad value records a problem and keeps the base value.
     */
    public static DisplayOptions read(ConfigReader r, DisplayOptions base, boolean complete) {
        return new DisplayOptions(
            wants(r, "billboard", complete) ? r.enumValue("billboard", Display.Billboard.class, base.billboard()) : base.billboard(),
            wants(r, "alignment", complete)
                ? r.enumValue("alignment", TextDisplay.TextAlignment.class, base.alignment()) : base.alignment(),
            wants(r, "line-width", complete) ? r.integer("line-width", 10, 1000, base.lineWidth()) : base.lineWidth(),
            wants(r, "scale", complete) ? (float) r.decimal("scale", 0.1, 10.0, base.scale()) : base.scale(),
            wants(r, "see-through", complete) ? r.bool("see-through", base.seeThrough()) : base.seeThrough(),
            wants(r, "text-shadow", complete) ? r.bool("text-shadow", base.textShadow()) : base.textShadow(),
            wants(r, "background", complete)
                ? r.custom("background", Background::parse, Background.EXPECTED, base.background()) : base.background(),
            wants(r, "view-range", complete) ? r.integer("view-range", 1, 512, base.viewRange()) : base.viewRange(),
            wants(r, "full-bright", complete) ? r.bool("full-bright", base.fullBright()) : base.fullBright(),
            wants(r, "refresh", complete)
                ? r.duration("refresh", Duration.ofSeconds(1), Duration.ofHours(1), base.refresh()) : base.refresh());
    }

    private static boolean wants(ConfigReader r, String key, boolean complete) {
        return complete || r.has(key);
    }
}
