package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextStyleTest {

    private TextStyle style;

    @BeforeEach
    void setUp() throws Exception {
        Set<String> index;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("atlas-index.txt")) {
            index = Icons.readIndex(in);
        }
        Icons icons = new Icons(index);
        Set<String> invalid = icons.load(Map.of(
            "money", new Icons.Sprite(Key.key("minecraft:items"), Key.key("item/gold_ingot"), true),
            "bad", new Icons.Sprite(Key.key("minecraft:blocks"), Key.key("item/gold_ingot"), true)));
        assertEquals(Set.of("bad"), invalid, "item sprites are not in the blocks atlas");
        this.style = new TextStyle(Palette.defaults(), icons);
    }

    @Test
    void designTagsAndDeclaredPlaceholdersAreAllowed() {
        assertEquals(List.of(), this.style.findDisallowedTags("<primary>You got <amount>.", Set.of("amount")));
        assertEquals(List.of(), this.style.findDisallowedTags("<secondary>a <icon:money> b <!italic>c</secondary>", Set.of()));
        assertEquals(List.of(), this.style.findDisallowedTags("<primary>line<newline><secondary>two", Set.of()));
    }

    @Test
    void boldColoursAndGradientsAreRejected() {
        assertEquals(1, this.style.findDisallowedTags("<bold>hi", Set.of()).size());
        assertEquals(1, this.style.findDisallowedTags("<b>hi", Set.of()).size());
        assertEquals(1, this.style.findDisallowedTags("<red>hi", Set.of()).size());
        assertEquals(1, this.style.findDisallowedTags("<#ff0000>hi", Set.of()).size());
        assertEquals(1, this.style.findDisallowedTags("<gradient:red:blue>hi", Set.of()).size());
        assertEquals(1, this.style.findDisallowedTags("<italic>hi", Set.of()).size(), "italics may only be turned off");
        assertEquals(1, this.style.findDisallowedTags("<player>", Set.of("name")).size(), "undeclared placeholder");
        assertEquals(1, this.style.findDisallowedTags("<icon:nope>", Set.of()).size(), "unknown icon");
    }

    @Test
    void untrustedTextCannotInject() {
        String hostile = "<click:run_command:/op me><bold>pwn</bold> <icon:money> \\<red>";
        Component parsed = this.style.parse("<primary>Hello <name>.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", hostile));
        String plain = PlainTextComponentSerializer.plainText().serialize(parsed);
        assertEquals("Hello " + hostile + ".", plain);
        assertTrue(parsed.clickEvent() == null && parsed.children().stream().allMatch(c -> c.clickEvent() == null));
    }

    @Test
    void iconTagRendersAnObjectComponent() {
        Component parsed = this.style.parse("<icon:money> x");
        assertTrue(parsed.children().stream().anyMatch(c -> c instanceof net.kyori.adventure.text.ObjectComponent)
            || parsed instanceof net.kyori.adventure.text.ObjectComponent);
    }
}
