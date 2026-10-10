package net.siftvanilla.siftcore.integration.floodgate;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;

/**
 * Text for Bedrock forms. Forms take section-sign text, so the palette is kept with the nearest legacy colours
 * (white, gray, and green for money). Sprite icons have no Bedrock equivalent and are left out; vanilla translation
 * keys (item names) are rendered with the server's translations, or turned into readable words when there is none.
 */
public final class BedrockText {

    private static final ComponentFlattener FLATTENER = ComponentFlattener.basic().toBuilder()
        .mapper(ObjectComponent.class, component -> "")
        .mapper(TranslatableComponent.class, BedrockText::translatable)
        .unknownMapper(component -> "")
        .build();

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .flattener(FLATTENER)
        .build();

    private BedrockText() {
    }

    /** The component as form text, rendered for {@code locale}. */
    public static String render(Component component, Locale locale) {
        if (component == null) {
            return "";
        }
        Component translated = GlobalTranslator.render(component, locale == null ? Locale.US : locale);
        return trimResets(SERIALIZER.serialize(translated));
    }

    private static String translatable(TranslatableComponent component) {
        String fallback = component.fallback();
        return fallback != null ? fallback : humanize(component.key());
    }

    /** A translation key as words: {@code block.minecraft.diamond_block} becomes {@code Diamond block}. */
    static String humanize(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        int dot = key.lastIndexOf('.');
        String last = (dot >= 0 ? key.substring(dot + 1) : key).replace('_', ' ').strip();
        if (last.isEmpty()) {
            return "";
        }
        return last.substring(0, 1).toUpperCase(Locale.ROOT) + last.substring(1);
    }

    /** Drops trailing colour codes that would style nothing. */
    private static String trimResets(String text) {
        String result = text;
        while (result.length() >= 2 && result.charAt(result.length() - 2) == LegacyComponentSerializer.SECTION_CHAR) {
            result = result.substring(0, result.length() - 2);
        }
        return result;
    }
}
