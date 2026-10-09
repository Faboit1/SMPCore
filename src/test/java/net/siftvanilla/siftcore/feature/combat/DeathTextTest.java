package net.siftvanilla.siftcore.feature.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Death message text: the game's messages restyled to one colour, kill streak lines, and the streak rules. */
class DeathTextTest {

    private static Lang lang;
    private static DeathMessages messages;

    @BeforeAll
    static void load() throws Exception {
        Icons icons = new Icons(Icons.readIndex(DeathTextTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", CombatResourcesTest.yaml("icons.yml"))).icons());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CombatMessages.class);
        YamlConfiguration yaml = CombatResourcesTest.yaml("lang/combat.yml");
        assertEquals(List.of(), lang.load(yaml, yaml, "lang/combat.yml"));
        messages = new DeathMessages(lang, null, CombatFeature.DEATH_MESSAGES, new Participants(VanishStatus.NONE), Cosmetics.NONE);
    }

    /** A death message shaped like the game's: a translation with a styled player name and a styled item. */
    private static Component vanillaKill() {
        Component victim = Component.text("Alex", NamedTextColor.RED)
            .hoverEvent(HoverEvent.showText(Component.text("Alex")))
            .clickEvent(ClickEvent.suggestCommand("/tell Alex "))
            .insertion("Alex");
        Component zombie = Component.translatable("entity.minecraft.zombie").decorate(TextDecoration.BOLD);
        Component item = Component.text("[", NamedTextColor.AQUA)
            .append(Component.text("Sword").decorate(TextDecoration.ITALIC))
            .append(Component.text("]"))
            .hoverEvent(HoverEvent.showText(Component.text("Sword")));
        return Component.translatable("death.attack.mob.item", NamedTextColor.WHITE, victim, zombie, item);
    }

    private static void assertNoColourOrDecoration(Component component) {
        assertNull(component.color(), "colour left in " + component);
        for (TextDecoration decoration : TextDecoration.values()) {
            assertEquals(TextDecoration.State.NOT_SET, component.decoration(decoration), decoration + " left in " + component);
        }
        if (component instanceof TranslatableComponent translatable) {
            translatable.arguments().forEach(argument -> assertNoColourOrDecoration(argument.asComponent()));
        }
        component.children().forEach(DeathTextTest::assertNoColourOrDecoration);
    }

    @Test
    void monochromeKeepsTextTranslationsAndEvents() {
        Component original = vanillaKill();
        Component mono = DeathMessages.monochrome(original);
        assertNoColourOrDecoration(mono);
        TranslatableComponent translatable = (TranslatableComponent) mono;
        assertEquals("death.attack.mob.item", translatable.key(), "the translation is kept");
        assertEquals(3, translatable.arguments().size());
        Component victim = translatable.arguments().get(0).asComponent();
        assertEquals(HoverEvent.Action.SHOW_TEXT, victim.hoverEvent().action(), "the name card is kept");
        assertEquals(ClickEvent.suggestCommand("/tell Alex "), victim.clickEvent());
        assertEquals("Alex", victim.insertion());
        Component item = translatable.arguments().get(2).asComponent();
        assertTrue(item.hoverEvent() != null, "the item hover is kept");
        assertEquals("[Sword]", PlainTextComponentSerializer.plainText().serialize(item));
        assertEquals("entity.minecraft.zombie", ((TranslatableComponent) translatable.arguments().get(1).asComponent()).key());
    }

    @Test
    void restyleColoursTheRootSecondary() {
        Component restyled = messages.restyle(vanillaKill());
        assertEquals(Palette.defaults().secondary(), restyled.color());
        TranslatableComponent translatable = (TranslatableComponent) restyled;
        translatable.arguments().forEach(argument -> assertNoColourOrDecoration(argument.asComponent()));
    }

    @Test
    void unstyledDropsEverything() {
        Component name = Component.text("Excalibur", NamedTextColor.GOLD).decorate(TextDecoration.ITALIC)
            .hoverEvent(HoverEvent.showText(Component.text("x")))
            .append(Component.text("!", NamedTextColor.RED));
        Component plain = DeathMessages.unstyled(name);
        assertNoColourOrDecoration(plain);
        assertNull(plain.hoverEvent());
        assertEquals("Excalibur!", PlainTextComponentSerializer.plainText().serialize(plain));
    }

    @Test
    void plainComponentsAreUntouched() {
        Component text = Component.text("fell from a high place");
        assertEquals(text, DeathMessages.monochrome(text));
        Component keyed = Component.translatable("death.fell.accident.generic", Component.text("Alex"));
        assertEquals("death.fell.accident.generic", ((TranslatableComponent) DeathMessages.monochrome(keyed)).key());
    }

    @Test
    void streakLines() {
        assertEquals("Alex is on a kill streak of 10.", lang.plain(CombatMessages.STREAK_REACHED, Arg.text("name", "Alex"),
            Arg.number("count", 10)));
        assertEquals("Sam ended Alex's kill streak of 1,200.",
            PlainTextComponentSerializer.plainText().serialize(messages.streakEnded(Component.text("Sam"), Component.text("Alex"), 1_200)));
        assertEquals("Sam is on a kill streak of 5.", PlainTextComponentSerializer.plainText().serialize(messages.streak(Component.text("Sam"), 5)));
    }

    @Test
    void streakRules() {
        CombatSettings.Streaks streaks = new CombatSettings.Streaks(List.of(10, 5, 5, 25), 5);
        assertEquals(List.of(5, 10, 25), streaks.announceAt(), "sorted without repeats");
        assertTrue(streaks.reached(5));
        assertTrue(streaks.reached(25));
        assertFalse(streaks.reached(6));
        assertFalse(streaks.reached(0));
        assertFalse(streaks.ended(4));
        assertTrue(streaks.ended(5));
        assertTrue(streaks.ended(500));
        assertFalse(CombatSettings.Streaks.OFF.reached(5));
        assertFalse(CombatSettings.Streaks.OFF.ended(1_000));
        assertFalse(new CombatSettings.Streaks(List.of(), 0).ended(Integer.MAX_VALUE), "0 turns ended streaks off");
    }

    @Test
    void outOfPlayFollowsVanish() {
        UUID hidden = UUID.randomUUID();
        Participants participants = new Participants(player -> player.equals(hidden));
        assertTrue(participants.hidden(hidden));
        assertFalse(participants.hidden(UUID.randomUUID()));
        assertFalse(participants.hidden(null));
        assertFalse(new Participants(VanishStatus.NONE).hidden(hidden));
    }
}
