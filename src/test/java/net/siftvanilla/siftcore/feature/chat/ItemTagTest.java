package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class ItemTagTest {

    private static final Component SWORD = Component.text("[Diamond Sword]")
        .hoverEvent(HoverEvent.showText(Component.text("Sharpness V")));

    @Test
    void detectsBothSpellingsInAnyCase() {
        assertTrue(ItemTag.present("look at my [item]"));
        assertTrue(ItemTag.present("[I] got it"));
        assertTrue(ItemTag.present("[ITEM]"));
        assertFalse(ItemTag.present("[items] [it] item i"));
    }

    @Test
    void replacesOnlyTheFirstTag() {
        Component result = ItemTag.replaceFirst(Component.text("look [item] and [i]"), SWORD);
        assertEquals("look [Diamond Sword] and [i]", ChatText.plain(result));
    }

    @Test
    void keepsTheReplacementsHover() {
        Component result = ItemTag.replaceFirst(Component.text("[i]"), SWORD);
        Component found = find(result, "[Diamond Sword]");
        assertEquals(SWORD.hoverEvent(), found.hoverEvent());
    }

    @Test
    void worksInsideComponentTrees() {
        Component message = Component.text("before ")
            .append(Component.text("middle [Item] part", NamedTextColor.GRAY))
            .append(Component.text(" after [item]"));
        Component result = ItemTag.replaceFirst(message, SWORD);
        assertEquals("before middle [Diamond Sword] part after [item]", ChatText.plain(result));
    }

    @Test
    void neverSearchesHoverTexts() {
        Component message = Component.text("hi").hoverEvent(HoverEvent.showText(Component.text("[item]")));
        Component result = ItemTag.replaceFirst(message, SWORD);
        assertEquals("hi", ChatText.plain(result));
        assertEquals(message.hoverEvent(), result.hoverEvent());
    }

    @Test
    void typedTextLeavesShownItemsOut() {
        Component item = Component.text("[Alex's sword]").hoverEvent(HoverEvent.showItem(Key.key("diamond_sword"), 1));
        Component message = ItemTag.replaceFirst(Component.text("look [item] @steve"), item);
        assertEquals("look [Alex's sword] @steve", ChatText.plain(message));
        assertEquals("look  @steve", ItemTag.typedText(message));
        assertEquals("plain text", ItemTag.typedText(Component.text("plain ").append(Component.text("text"))));
    }

    @Test
    void messagesWithoutATagStayTheSame() {
        Component message = Component.text("nothing to see");
        assertEquals(message, ItemTag.replaceFirst(message, SWORD));
    }

    @Test
    void playerTextIsNeverParsed() {
        Component result = ItemTag.replaceFirst(Component.text("<red>[item]</red>"), SWORD);
        assertEquals("<red>[Diamond Sword]</red>", ChatText.plain(result));
    }

    @Test
    void monochromeStripsColoursAndDecorationsEverywhere() {
        Component name = Component.translatable("item.minecraft.diamond_sword", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, true)
            .arguments(Component.text("x", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" of doom", NamedTextColor.GOLD));
        Component plain = ItemTag.monochrome(name);
        assertNull(plain.color());
        assertEquals(TextDecoration.State.NOT_SET, plain.decoration(TextDecoration.ITALIC));
        TranslatableComponent translatable = (TranslatableComponent) plain;
        assertEquals("item.minecraft.diamond_sword", translatable.key(), "translations stay translations");
        Component argument = translatable.arguments().getFirst().asComponent();
        assertNull(argument.color());
        assertEquals(TextDecoration.State.NOT_SET, argument.decoration(TextDecoration.BOLD));
        List<Component> children = plain.children();
        assertNull(children.getFirst().color());
    }

    private static Component find(Component root, String text) {
        if (root instanceof net.kyori.adventure.text.TextComponent t && t.content().equals(text)) {
            return root;
        }
        for (Component child : root.children()) {
            Component found = find(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
