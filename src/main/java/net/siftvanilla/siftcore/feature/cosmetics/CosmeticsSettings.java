package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.Palette;

/**
 * Parsed {@code features/cosmetics.yml}.
 *
 * @param enabled     every cosmetic on or off at once
 * @param colors      the colours players may pick
 * @param nicknames   nickname rules
 * @param tags        the chat tags by id, in menu order
 * @param join        rank join and leave lines
 * @param killEffects kill effects
 */
record CosmeticsSettings(boolean enabled, Colors colors, Nicknames nicknames, Map<String, ChatTag> tags, Join join,
                         KillEffects killEffects) {

    /**
     * @param basic       the vanilla colours Baron players may pick, in menu order
     * @param presets     ready-made hex colours and gradients for Tycoon players, in menu order
     * @param minDistance the smallest CIEDE2000 difference from a reserved colour
     * @param minContrast the smallest contrast ratio against black
     * @param reserved    more colours nobody may pick (besides the palette's error and money colours)
     */
    record Colors(List<NamedTextColor> basic, List<Preset> presets, double minDistance, double minContrast, List<TextColor> reserved) {

        /** The rules with the palette's error and money colours. */
        ColorRules rules(Palette palette) {
            List<ColorRules.Reserved> list = new ArrayList<>();
            list.add(new ColorRules.Reserved(palette.error(), ColorRules.Reason.ERRORS));
            list.add(new ColorRules.Reserved(palette.money(), ColorRules.Reason.MONEY));
            for (TextColor color : this.reserved) {
                list.add(new ColorRules.Reserved(color, ColorRules.Reason.SERVER));
            }
            return new ColorRules(list, this.minDistance, this.minContrast);
        }
    }

    /** A ready-made premium colour or gradient. */
    record Preset(String id, String name, ChatStyle style) {
    }

    /**
     * @param minLength     shortest nickname
     * @param maxLength     longest nickname
     * @param reservedWords words no nickname may contain (lowercase)
     * @param cooldown      how often a player can change their nickname
     * @param hold          how long a nickname stays reserved after its holder was last able to show it
     */
    record Nicknames(int minLength, int maxLength, List<String> reservedWords, Duration cooldown, Duration hold) {

        NickRules rules() {
            return new NickRules(this.minLength, this.maxLength, this.reservedWords);
        }
    }

    /**
     * @param enabled   rank join and leave lines on
     * @param cooldown  a player's line shows at most this often
     * @param maxLength the longest custom message
     */
    record Join(boolean enabled, Duration cooldown, int maxLength) {
    }

    /**
     * @param enabled      kill effects on
     * @param effects      the effects players may pick, in menu order
     * @param cooldown     shortest time between two effects of one killer
     * @param maxPerSecond most effects per second on the server
     * @param range        how far away players see an effect
     */
    record KillEffects(boolean enabled, List<KillEffect> effects, Duration cooldown, int maxPerSecond, int range) {
    }

    /** The shipped vanilla colours: none of them red or green, none too dark to read. */
    static final List<String> DEFAULT_BASIC = List.of("gold", "yellow", "aqua", "dark_aqua", "blue", "light_purple", "dark_purple", "gray");
    static final List<String> DEFAULT_RESERVED_WORDS = List.of("admin", "mod", "owner", "staff", "helper", "console", "server",
        "siftvanilla");

    /**
     * MiniMessage for tag looks written by the owner: colours, gradients, rainbow, bold and the like, shadows. No
     * clicks, hovers or other events (the tag's hover is its description).
     */
    static final MiniMessage TAG_TEXT = MiniMessage.builder()
        .tags(TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.rainbow())
            .resolver(StandardTags.transition())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.shadowColor())
            .resolver(StandardTags.reset())
            .build())
        .strict(false)
        .build();

    static CosmeticsSettings parse(ConfigReader r) {
        boolean enabled = r.bool("enabled", true);
        Colors colors = colors(r.section("colors"));
        ConfigReader nick = r.section("nicknames");
        int minLength = nick.integer("min-length", 1, 16, 3);
        int maxLength = nick.integer("max-length", 1, 16, 16);
        if (maxLength < minLength) {
            nick.problem("max-length", "must be at least min-length (" + minLength + ")");
            maxLength = Math.max(minLength, 16);
        }
        List<String> words = new ArrayList<>();
        for (String word : nick.stringList("reserved-words", DEFAULT_RESERVED_WORDS)) {
            String lower = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
            if (lower.matches("[a-z0-9_]{2,16}")) {
                words.add(lower);
            } else {
                nick.problem("reserved-words", "'" + word + "' is not a word of 2 to 16 letters, digits or underscores; it is skipped");
            }
        }
        Nicknames nicknames = new Nicknames(minLength, maxLength, List.copyOf(words),
            nick.duration("cooldown", Duration.ZERO, Duration.ofDays(1), Duration.ofSeconds(30)),
            nick.duration("hold", Duration.ZERO, Duration.ofDays(365), Duration.ofDays(14)));
        Map<String, ChatTag> tags = tags(r.section("tags"));
        ConfigReader join = r.section("join-messages");
        Join joinSettings = new Join(join.bool("enabled", true),
            join.duration("cooldown", Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(60)),
            join.integer("max-length", 8, 64, 40));
        return new CosmeticsSettings(enabled, colors, nicknames, tags, joinSettings, killEffects(r.section("kill-effects")));
    }

    private static Colors colors(ConfigReader c) {
        double minDistance = c.decimal("min-distance", 0.0, 60.0, 20.0);
        double minContrast = c.decimal("min-contrast", 1.0, 21.0, 3.0);
        List<TextColor> reserved = new ArrayList<>();
        for (String raw : c.optionalStringList("reserved")) {
            TextColor color = ChatStyle.hex(raw == null ? "" : raw);
            if (color == null) {
                c.problem("reserved", "'" + raw + "' is not a hex colour like #915DFF; it is skipped");
            } else {
                reserved.add(color);
            }
        }
        Colors draft = new Colors(List.of(), List.of(), minDistance, minContrast, List.copyOf(reserved));
        ColorRules rules = draft.rules(Palette.defaults());
        List<NamedTextColor> basic = new ArrayList<>();
        for (String raw : c.stringList("basic", DEFAULT_BASIC)) {
            NamedTextColor color = raw == null ? null : NamedTextColor.NAMES.value(raw.strip().toLowerCase(Locale.ROOT));
            if (color == null) {
                c.problem("basic", "'" + raw + "' is not a vanilla colour name (gold, aqua, light_purple...); it is skipped");
            } else if (!rules.check(color).allowed()) {
                c.problem("basic", "'" + raw + "' is " + why(rules.check(color)) + "; it is skipped");
            } else if (!basic.contains(color)) {
                basic.add(color);
            }
        }
        List<Preset> presets = new ArrayList<>();
        for (Map.Entry<String, ConfigReader> entry : c.children("presets").entrySet()) {
            String id = entry.getKey();
            ConfigReader preset = entry.getValue();
            String name = preset.string("name", id).strip();
            String styleText = preset.string("style", "");
            ChatStyle style = ChatStyle.parse(styleText);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                c.problem("presets." + id, "the id must be lowercase letters, digits, - or _; it is skipped");
            } else if (name.isEmpty() || name.length() > 24) {
                preset.problem("name", "must be 1 to 24 characters; the preset is skipped");
            } else if (style == null || style.none()) {
                preset.problem("style", "'" + styleText + "' is not a colour (#FFB07A) or gradient (#55FFFF:#5555FF); the preset is skipped");
            } else if (!rules.check(style).allowed()) {
                preset.problem("style", "'" + styleText + "' is " + why(rules.check(style)) + "; the preset is skipped");
            } else {
                presets.add(new Preset(id, name, style));
            }
        }
        return new Colors(List.copyOf(basic), List.copyOf(presets), minDistance, minContrast, List.copyOf(reserved));
    }

    private static String why(ColorRules.Verdict verdict) {
        if (verdict.tooDark()) {
            return "too dark to read in chat";
        }
        if (verdict.reserved() == null) {
            return "not allowed";
        }
        return switch (verdict.reserved()) {
            case ERRORS -> "too close to the red used for errors and kills";
            case MONEY -> "too close to the green used for money";
            case SERVER -> "too close to a reserved colour";
        };
    }

    private static Map<String, ChatTag> tags(ConfigReader t) {
        Map<String, ChatTag> tags = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigReader> entry : t.children("list").entrySet()) {
            String id = entry.getKey();
            ConfigReader tag = entry.getValue();
            if (!ChatTag.validId(id)) {
                t.problem("list." + id, "the id must be lowercase letters, digits, - or _ (at most 32); the tag is skipped");
                continue;
            }
            String source = tag.string("display", "");
            Component display;
            try {
                display = TAG_TEXT.deserialize(source);
            } catch (RuntimeException e) {
                tag.problem("display", "is not valid MiniMessage (" + e.getMessage() + "); the tag is skipped");
                continue;
            }
            String plain = PlainTextComponentSerializer.plainText().serialize(display).strip();
            if (plain.isEmpty() || plain.length() > 24) {
                tag.problem("display", "must show 1 to 24 characters, got '" + plain + "'; the tag is skipped");
                continue;
            }
            String description = tag.optionalString("description", "").strip();
            String permission = tag.string("permission", "").strip();
            if (!permission.matches("[a-z0-9_.*-]{3,96}")) {
                tag.problem("permission", "must be a permission node like siftcore.tags.baron; the tag is skipped");
                continue;
            }
            YearMonth month = null;
            String monthText = tag.optionalString("month", "").strip();
            if (!monthText.isEmpty()) {
                try {
                    month = YearMonth.parse(monthText);
                } catch (DateTimeParseException e) {
                    tag.problem("month", "must be a month like 2026-10; the tag is skipped");
                    continue;
                }
            }
            tags.put(id, new ChatTag(id, source, display, plain, description, permission, month, tag.optionalString("hint", "").strip()));
        }
        return java.util.Collections.unmodifiableMap(tags);
    }

    private static KillEffects killEffects(ConfigReader k) {
        List<KillEffect> effects = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        for (KillEffect effect : KillEffect.values()) {
            defaults.add(effect.id());
        }
        for (String raw : k.stringList("effects", defaults)) {
            KillEffect effect = KillEffect.byId(raw);
            if (effect == null) {
                k.problem("effects", "'" + raw + "' is not a kill effect (" + String.join(", ", defaults) + "); it is skipped");
            } else if (!effects.contains(effect)) {
                effects.add(effect);
            }
        }
        return new KillEffects(k.bool("enabled", true), List.copyOf(effects),
            k.duration("cooldown", Duration.ZERO, Duration.ofMinutes(10), Duration.ofSeconds(3)),
            k.integer("max-per-second", 0, 100, 4),
            k.integer("range", 8, 96, 32));
    }
}
