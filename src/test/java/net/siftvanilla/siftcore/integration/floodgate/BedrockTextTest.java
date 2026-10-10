package net.siftvanilla.siftcore.integration.floodgate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.junit.jupiter.api.Test;

/** Dialog text becomes Bedrock form text: palette colours kept as legacy codes, icons dropped, item names readable. */
class BedrockTextTest {

    @Test
    void paletteColoursBecomeTheNearestLegacyCodes() {
        Component line = Component.text("You have ", NamedTextColor.WHITE)
            .append(Component.text("$1,500", TextColor.color(0x1AFF1A)))
            .append(Component.text(" in the bank", NamedTextColor.GRAY));
        assertEquals("§fYou have §a$1,500§7 in the bank", BedrockText.render(line, Locale.US));
    }

    @Test
    void plainTextStaysPlain() {
        assertEquals("Okay", BedrockText.render(Component.text("Okay"), Locale.US));
        assertEquals("", BedrockText.render(null, Locale.US));
    }

    @Test
    void spriteIconsAreLeftOut() {
        Component line = Component.object(ObjectContents.sprite(Key.key("minecraft", "items"), Key.key("minecraft", "item/diamond")))
            .append(Component.text(" 12 kills"));
        assertEquals(" 12 kills", BedrockText.render(line, Locale.US));
    }

    @Test
    void untranslatedKeysBecomeWords() {
        assertEquals("Diamond block", BedrockText.render(Component.translatable("block.minecraft.diamond_block"), Locale.US));
        assertEquals("Shiny", BedrockText.render(Component.translatable().key("custom.key").fallback("Shiny").build(), Locale.US));
        assertEquals("Netherite sword", BedrockText.humanize("item.minecraft.netherite_sword"));
        assertEquals("Stone", BedrockText.humanize("stone"));
        assertEquals("", BedrockText.humanize(""));
    }
}
