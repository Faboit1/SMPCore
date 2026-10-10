package net.siftvanilla.siftcore.feature.displays;

import java.util.Locale;

/**
 * The box behind a display's text: none (just the text), the client's default (the translucent box each player
 * picks in their accessibility settings), or an exact colour with transparency.
 *
 * @param mode which of the three
 * @param argb the colour for {@link Mode#COLOR}, as {@code 0xAARRGGBB}; 0 otherwise
 */
public record Background(Mode mode, int argb) {

    /** The three kinds of background. */
    public enum Mode {
        NONE,
        DEFAULT,
        COLOR
    }

    public static final Background NONE = new Background(Mode.NONE, 0);
    public static final Background DEFAULT = new Background(Mode.DEFAULT, 0);
    public static final String EXPECTED = "none, default or a colour like \"#40000000\" (#AARRGGBB)";

    /**
     * Parses {@code none}, {@code default}, {@code #AARRGGBB} or {@code #RRGGBB} (opaque).
     *
     * @throws IllegalArgumentException with a short reason
     */
    public static Background parse(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        switch (value) {
            case "none" -> {
                return NONE;
            }
            case "default" -> {
                return DEFAULT;
            }
            default -> {
            }
        }
        if (value.matches("#[0-9a-f]{8}")) {
            return new Background(Mode.COLOR, (int) Long.parseLong(value.substring(1), 16));
        }
        if (value.matches("#[0-9a-f]{6}")) {
            return new Background(Mode.COLOR, 0xFF000000 | Integer.parseInt(value.substring(1), 16));
        }
        throw new IllegalArgumentException("is not a background");
    }
}
