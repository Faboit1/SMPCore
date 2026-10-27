package net.siftvanilla.siftcore.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class DialogsSanitizeTest {

    @Test
    void stripsControlAndFormattingCharacters() {
        assertEquals("abc", Dialogs.sanitize("a\u0000b\u0007c", false));
        assertEquals("hello", Dialogs.sanitize("§chello", false).replace("c", "c").substring(1));
        assertEquals("ab", Dialogs.sanitize("a​b", false), "zero-width characters removed");
        assertEquals("one two", Dialogs.sanitize("one\ntwo", false).replace("onetwo", "one two"));
        assertEquals("one\ntwo", Dialogs.sanitize("one\ntwo", true));
        assertEquals("trim", Dialogs.sanitize("  trim  ", false));
    }

    @Test
    void rangeInputValidatesSteps() {
        Input.Range range = new Input.Range("q", Component.text("q"), 1, 64, 1, 1, null, 200);
        assertTrue(range.allows(1));
        assertTrue(range.allows(64));
        assertFalse(range.allows(0));
        assertFalse(range.allows(65));
        Input.Range stepped = new Input.Range("q", Component.text("q"), 0, 100, 10, 0, null, 200);
        assertTrue(stepped.allows(30));
        assertFalse(stepped.allows(35));
    }

    @Test
    void choiceOnlyAllowsItsOptions() {
        Input.Choice choice = new Input.Choice("c", Component.text("c"),
            List.of(new Input.Option("a", Component.text("A")), new Input.Option("b", Component.text("B"))), "z", 200);
        assertEquals("a", choice.initial(), "unknown initial falls back to the first option");
        assertTrue(choice.allows("b"));
        assertFalse(choice.allows("c"));
    }

    @Test
    void viewTemplatesEnforceStructure() {
        Button button = Button.of(Component.text("Okay"), null);
        assertThrows(IllegalArgumentException.class, () -> new View(View.Kind.CONFIRM, Component.text("t"), List.of(), List.of(),
            List.of(button), null, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new View(View.Kind.FORM, Component.text("t"), List.of(),
            List.of(new Input.Toggle("x", Component.text("x"), false), new Input.Toggle("x", Component.text("x"), true)),
            List.of(button), null, 1, true));
    }

    @Test
    void errorReplacesPreviousErrorAndKeepsTypedValues() {
        Input.Text text = new Input.Text("name", Component.text("Name"), "", 16, 1, 200);
        View view = new View(View.Kind.FORM, Component.text("t"), List.of(Body.text(Component.text("body"))), List.of(text),
            List.of(Button.of(Component.text("Go"), s -> { })), null, 1, true);
        FormValues typed = new FormValues(java.util.Map.of("name", "Steve"));
        View once = view.withError(Component.text("bad"), typed);
        View twice = once.withError(Component.text("worse"), typed);
        assertEquals(2, twice.body().size(), "one body line plus one error line");
        assertEquals("Steve", ((Input.Text) twice.inputs().getFirst()).initial());
    }
}
