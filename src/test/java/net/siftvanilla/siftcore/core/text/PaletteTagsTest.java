package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The palette tags of the dialog style: shards purple, switches green and red, values in the accent colour. */
class PaletteTagsTest {

    private final Palette palette = Palette.defaults();
    private final TextStyle style = new TextStyle(this.palette, new Icons(Set.of()));

    /** The colour of the part of a text that reads {@code text}. */
    private static TextColor colourOf(Component component, String text) {
        List<Component> parts = new ArrayList<>();
        collect(component, null, text, parts);
        return parts.isEmpty() ? null : parts.getFirst().color();
    }

    private static void collect(Component component, TextColor inherited, String text, List<Component> found) {
        TextColor colour = component.color() != null ? component.color() : inherited;
        if (component instanceof net.kyori.adventure.text.TextComponent t && t.content().equals(text)) {
            found.add(component.color(colour));
        }
        for (Component child : component.children()) {
            collect(child, colour, text, found);
        }
    }

    @Test
    void theDefaultsAreTheOwnersColours() {
        assertEquals(TextColor.color(0x915DFF), this.palette.shards(), "the shard colour of the TAB sidebar");
        assertEquals(TextColor.color(0x55FF55), this.palette.on());
        assertEquals(TextColor.color(0xFF5555), this.palette.off());
        assertEquals(this.palette.on(), this.palette.state(true));
        assertEquals(this.palette.off(), this.palette.state(false));
        Palette custom = new Palette(NamedTextColor.WHITE, NamedTextColor.GRAY, TextColor.color(0x00FF00), null, null,
            TextColor.color(0x123456), null, null, null);
        assertEquals(TextColor.color(0x123456), custom.shards());
        assertEquals(Palette.DEFAULT_ACCENT, custom.accent(), "unset colours are the defaults");
    }

    @Test
    void theNewTagsAreAllowedAndColour() {
        assertEquals(List.of(), this.style.findDisallowedTags("<shards>5 shards</shards> <on>ON <off>OFF <accent>Bell", Set.of()));
        Component parsed = this.style.parse("<primary>Pings: <on>ON</on>, sound: <accent>Bell</accent>, <shards>5 shards</shards>, <off>OFF");
        assertEquals(this.palette.on(), colourOf(parsed, "ON"));
        assertEquals(this.palette.accent(), colourOf(parsed, "Bell"));
        assertEquals(this.palette.shards(), colourOf(parsed, "5 shards"));
        assertEquals(this.palette.off(), colourOf(parsed, "OFF"));
    }

    @Test
    void aPlaceholderCalledShardsStaysThePlaceholder() {
        assertEquals(List.of(), this.style.findDisallowedTags("You have <shards> shards.", Set.of("shards")));
        Component parsed = this.style.parse("You have <shards> shards.", TagResolver.resolver(
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("shards", "12")));
        assertEquals("You have 12 shards.", TextStyle.plain(parsed));
    }

    @Test
    void shardAmountsAreWrittenInTheShardsColour() {
        Lang lang = new Lang(this.style, MoneyFormat::defaults);
        MessageKey key = MessageKey.chat("test.shards", "shards", "amount");
        lang.register(Holder.class);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("test.shards", "<primary>You have <shards> and <amount>.");
        assertTrue(lang.load(yaml, yaml, "test").isEmpty());
        Component text = lang.get(key, Arg.shards("shards", 1500), Arg.amount("amount", Currency.SHARDS, 7));
        assertEquals("You have 1,500 and 7.", TextStyle.plain(text));
        assertEquals(this.palette.shards(), colourOf(text, "1,500"));
        assertEquals(this.palette.shards(), colourOf(text, "7"));
        assertEquals(this.palette.shards(), colourOf(this.palette.asError(text), "1,500"), "errors keep the shards colour");
    }

    /** Holds the test key so the lang can register it. */
    static final class Holder {
        static final MessageKey KEY = MessageKey.chat("test.shards", "shards", "amount");
    }

    @Test
    void aFeaturesOwnSoundFollowsThePlayersSoundSettings() {
        Sounds sounds = new Sounds();
        Fakes.FakePlayer player = new Fakes.FakePlayer("Listener");
        Sound chime = Sound.sound(Key.key("block.amethyst_block.chime"), Sound.Source.MASTER, 1f, 1f);
        sounds.play(player.player, chime, Feedback.SUCCESS);
        assertEquals(1, player.calls.stream().filter("playSound"::equals).count(), "played at the player's settings (all on)");
        sounds.play(player.player, null, Feedback.SUCCESS);
        sounds.play(player.player, chime, Feedback.NONE);
        assertEquals(1, player.calls.stream().filter("playSound"::equals).count(), "no sound, or no kind: nothing");
    }
}
