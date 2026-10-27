package net.siftvanilla.siftcore.integration.luckperms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Rank labels. LuckPerms display names, prefixes and meta values are often written with legacy colour codes
 * ({@code &6}, {@code §l}, {@code &#ffaa00}, {@code &x&f&f&a&a&0&0}) or MiniMessage tags ({@code <gold>},
 * {@code <gradient:...>}); the label is that text without any of it ({@link #plain}). A rank's colour comes from its
 * own meta values instead ({@link #color}, {@link #gradient}, {@link #styled}), so it is one clean colour or gradient
 * however the prefix was written.
 */
public final class RankText {

    private static final Pattern SPIGOT_HEX = Pattern.compile("(?i)[&§]x(?:[&§][0-9a-f]){6}");
    private static final Pattern HASH_HEX = Pattern.compile("(?i)(?<![<:])[&§]?\\{?#[0-9a-f]{6}}?");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§][0-9a-fk-orx]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private RankText() {
    }

    /** The text without colour codes or tags, whitespace collapsed; empty for null. */
    public static String plain(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = SPIGOT_HEX.matcher(raw).replaceAll("");
        text = HASH_HEX.matcher(text).replaceAll("");
        text = LEGACY.matcher(text).replaceAll("");
        text = MINI.stripTags(text);
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != '§') {
                clean.append(c);
            }
        }
        return SPACES.matcher(clean).replaceAll(" ").strip();
    }

    /**
     * A colour written as {@code #RRGGBB} (also {@code &#RRGGBB}) or a vanilla colour name ({@code gold}), or null when
     * it is neither.
     */
    public static TextColor color(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("&")) {
            text = text.substring(1);
        }
        if (text.matches("#[0-9a-f]{6}")) {
            return TextColor.fromHexString(text);
        }
        return NamedTextColor.NAMES.value(text);
    }

    /**
     * A colour as {@code #RRGGBB} in upper case, the way ranks are configured ({@code siftcore-rank-color #5FA8FF}),
     * whatever Adventure's own {@code asHexString} prints; empty for null.
     */
    public static String hex(TextColor color) {
        return color == null ? "" : String.format(Locale.ROOT, "#%06X", color.value() & 0xFFFFFF);
    }

    /** The colours of a gradient written as {@code #FF6AD5:#B26BFF} (two or more stops), or an empty list. */
    public static List<TextColor> gradient(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<TextColor> stops = new ArrayList<>();
        for (String part : raw.split(":")) {
            TextColor stop = color(part);
            if (stop == null) {
                return List.of();
            }
            stops.add(stop);
        }
        return stops.size() >= 2 ? List.copyOf(stops) : List.of();
    }

    /**
     * The label as a component: in the gradient when one is given, else in {@code color}, else in {@code fallback}.
     * Each letter of a gradient gets its own colour, spread evenly over the stops.
     */
    public static Component styled(String label, TextColor color, List<TextColor> gradient, TextColor fallback) {
        if (label == null || label.isEmpty()) {
            return Component.empty();
        }
        if (gradient.size() < 2) {
            return Component.text(label, color != null ? color : fallback);
        }
        int[] codePoints = label.codePoints().toArray();
        TextComponent.Builder builder = Component.text();
        int segments = gradient.size() - 1;
        for (int i = 0; i < codePoints.length; i++) {
            float position = codePoints.length == 1 ? 0f : (float) i / (codePoints.length - 1);
            int segment = Math.min(segments - 1, (int) (position * segments));
            float local = position * segments - segment;
            TextColor letter = TextColor.lerp(local, gradient.get(segment), gradient.get(segment + 1));
            builder.append(Component.text(new String(codePoints, i, 1), letter));
        }
        return builder.build();
    }

    /** A group name as a label when the group has no display name: {@code elite} becomes {@code Elite}. */
    public static String fromGroup(String group) {
        if (group == null || group.isBlank()) {
            return "";
        }
        String spaced = group.replace('_', ' ').replace('-', ' ').strip();
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}
