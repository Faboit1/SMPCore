package net.siftvanilla.siftcore.core.text;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The single MiniMessage setup for trusted text (lang and config). It knows the palette tags
 * {@code <primary> <secondary> <money> <error> <shards> <on> <off> <accent>} ({@link Palette}), any colour ({@code <red>}, {@code <#3CC4EE>}, {@code <color:...>}),
 * {@code <shadow:...>}, {@code <bold>}, {@code <icon:name>} sprites, {@code <!italic>}, {@code <newline>},
 * {@code <reset>}, click/hover/key/lang tags, and the placeholders a message declares. Gradients, rainbow,
 * obfuscation and other decorative tags are unavailable, and {@link #findDisallowedTags} reports them.
 * Untrusted text never goes through MiniMessage: it is inserted with {@link Arg.Text} or {@link #literal(String)}.
 */
public final class TextStyle {

    private static final Pattern TAG = Pattern.compile("<(/?)(!?)([a-zA-Z0-9_#:.\\-]+)");
    private static final Set<String> STRUCTURAL = Set.of(
        "primary", "secondary", "money", "error", "shards", "on", "off", "accent", "icon", "italic", "i", "em", "newline", "br", "reset",
        "click", "hover", "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or",
        "color", "colour", "c", "shadow", "bold", "b");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> NEGATION_ONLY = Set.of("italic", "i", "em");

    private volatile Palette palette;
    private volatile Icons icons;
    private volatile MiniMessage miniMessage;

    public TextStyle(Palette palette, Icons icons) {
        this.palette = palette;
        this.icons = icons;
        rebuild();
    }

    public void update(Palette palette, Icons icons) {
        this.palette = palette;
        this.icons = icons;
        rebuild();
    }

    private void rebuild() {
        Palette p = this.palette;
        Icons i = this.icons;
        TagResolver designTags = TagResolver.builder()
            .tag("primary", Tag.styling(p.primary()))
            .tag("secondary", Tag.styling(p.secondary()))
            .tag("money", Tag.styling(p.money()))
            .tag("error", Tag.styling(p.error()))
            .tag("shards", Tag.styling(p.shards()))
            .tag("on", Tag.styling(p.on()))
            .tag("off", Tag.styling(p.off()))
            .tag("accent", Tag.styling(p.accent()))
            .tag("icon", (args, ctx) -> Tag.selfClosingInserting(i.component(args.popOr("<icon> needs a name").lowerValue())))
            .resolver(StandardTags.decorations(TextDecoration.ITALIC))
            .resolver(StandardTags.decorations(TextDecoration.BOLD))
            .resolver(StandardTags.color())
            .resolver(StandardTags.shadowColor())
            .resolver(StandardTags.newline())
            .resolver(StandardTags.reset())
            .resolver(StandardTags.clickEvent())
            .resolver(StandardTags.hoverEvent())
            .resolver(StandardTags.keybind())
            .resolver(StandardTags.translatable())
            .resolver(StandardTags.translatableFallback())
            .build();
        this.miniMessage = MiniMessage.builder().tags(designTags).strict(false).build();
    }

    public Palette palette() {
        return this.palette;
    }

    public Icons icons() {
        return this.icons;
    }

    /** Parses trusted MiniMessage with the design tags plus {@code extra} placeholders. */
    public Component parse(String trusted, TagResolver extra) {
        return this.miniMessage.deserialize(trusted, extra);
    }

    public Component parse(String trusted) {
        return this.miniMessage.deserialize(trusted);
    }

    /** Untrusted text as a plain component: formatting characters stay literal. */
    public static Component literal(String untrusted) {
        return Component.text(untrusted == null ? "" : untrusted);
    }

    /** Escapes text for embedding inside a MiniMessage string (prefer {@link Arg.Text} instead). */
    public String escape(String untrusted) {
        return this.miniMessage.escapeTags(untrusted == null ? "" : untrusted);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Text in the primary colour with italics explicitly off (safe for item names and lore). */
    public Component primary(String untrusted) {
        return literal(untrusted).color(this.palette.primary()).decoration(TextDecoration.ITALIC, false);
    }

    public Component secondary(String untrusted) {
        return literal(untrusted).color(this.palette.secondary()).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Returns every tag in {@code text} that the design system does not allow, given the placeholders the message
     * declares. Closing tags and {@code <!italic>} are fine; {@code <italic>} (turning italics on) is not.
     */
    public java.util.List<String> findDisallowedTags(String text, Set<String> placeholders) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            boolean negated = !matcher.group(2).isEmpty();
            String full = matcher.group(3);
            String name = full.contains(":") ? full.substring(0, full.indexOf(':')) : full;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (placeholders.contains(lower) || HEX.matcher(name).matches()
                || net.kyori.adventure.text.format.NamedTextColor.NAMES.value(lower) != null) {
                continue;
            }
            if (!STRUCTURAL.contains(lower)) {
                bad.add("<" + matcher.group(1) + matcher.group(2) + full + ">");
                continue;
            }
            if (NEGATION_ONLY.contains(lower) && !negated && matcher.group(1).isEmpty()) {
                bad.add("<" + full + "> (only <!" + lower + "> is allowed)");
            }
            if (lower.equals("icon") && matcher.group(1).isEmpty()) {
                String icon = full.contains(":") ? full.substring(full.indexOf(':') + 1) : "";
                if (icon.isEmpty() || !this.icons.has(icon.toLowerCase(java.util.Locale.ROOT))) {
                    bad.add("<" + full + "> (unknown icon)");
                }
            }
        }
        return bad;
    }
}
