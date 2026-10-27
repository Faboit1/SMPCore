package net.siftvanilla.siftcore.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.money.MoneyStyle;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingsCheck;
import net.siftvanilla.siftcore.testing.Fakes;
import org.junit.jupiter.api.Test;

/** The money-format setting: its options and their samples, where it is registered, and what core binds it to. */
class MoneyDisplayTest {

    private static final UUID SOMEONE = UUID.randomUUID();

    @Test
    void aChoiceOfTheThreeFormatsDefaultingToTheServers() {
        Choice<MoneyStyle> setting = MoneyDisplay.MONEY_FORMAT;
        assertEquals("money-format", setting.id());
        assertEquals(MoneyStyle.SERVER, setting.defaultValue());
        assertEquals(List.of("server", "full", "short"), setting.optionIds());
        assertEquals(MoneyStyle.SHORT, setting.decode(" Short ").orElseThrow());
        assertNull(setting.permission());
        assertTrue(setting.legacyValues().isEmpty());
    }

    @Test
    void eachOptionShowsTheSameSampleForEveryReader() {
        Lang lang = Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, MoneyDisplay.class);
        lang.viewers(id -> MoneyStyle.SHORT);
        List<String> expected = List.of("The server's way ($1.23m)", "In full ($1,234,567)", "Short ($1.2m)");
        for (UUID reader : List.of(SOMEONE, UUID.randomUUID())) {
            List<String> labels = lang.viewing(reader, () -> MoneyDisplay.MONEY_FORMAT.options().stream().map(o -> o.text(lang)).toList());
            assertEquals(expected, labels, "the samples are pinned to their option's format");
        }
        assertEquals("Money format", lang.plain(MoneyDisplay.LABEL));
        assertTrue(lang.plain(MoneyDisplay.DESCRIPTION).contains("Confirmations always show the exact amount"));
    }

    @Test
    void registeredInTheDisplayGroupAfterTheSidebarLines() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Lang lang = Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, MoneyDisplay.class);
        MoneyDisplay.register(settings, lang);
        Registry.Entry<?> entry = settings.registry().entry("money-format");
        assertNotNull(entry);
        assertEquals(SettingCategories.DISPLAY, entry.category());
        assertEquals(MoneyDisplay.ORDER, entry.options().order());
        assertEquals(MoneyDisplay.KEYWORDS, entry.options().keywords());
        assertTrue(entry.placeholder(), "a display preference, not a private one");
        assertTrue(entry.offered(), "core applies it, so it is always offered");
        // Only core's lang file is loaded here, so the groups' own text (lang/settings.yml) is left out.
        assertEquals(List.of(), SettingsCheck.missingText(settings.registry(), lang::plain).stream()
            .filter(missing -> missing.startsWith("money-format")).toList(), "every label, option and keyword has text");
    }

    private static <T> List<String> optionIds(PlayerSettings settings, Registry.Entry<T> entry) {
        return settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    private static List<String> offeredOptions(PlayerSettings settings) {
        return optionIds(settings, settings.registry().entry("money-format"));
    }

    private static MoneyFormat format(long compactFrom, int decimals, List<MoneyFormat.Suffix> suffixes) {
        return new MoneyFormat("$<amount>", true, compactFrom, decimals, suffixes, 1_000_000_000_000_000L);
    }

    @Test
    void anOptionThatWritesEveryAmountTheServersWayIsNotOffered() {
        AtomicReference<MoneyFormat> money = new AtomicReference<>(MoneyFormat.defaults());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), new Icons(Set.of())), money::get);
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        MoneyDisplay.register(settings, lang);
        assertEquals(List.of("server", "full", "short"), offeredOptions(settings), "the shipped format: all three differ");

        // compact-from: 0 ("always show full amounts"): the server's way already is every digit.
        money.set(format(0, 2, MoneyFormat.DEFAULT_SUFFIXES));
        assertEquals(List.of("server", "short"), offeredOptions(settings));
        assertTrue(settings.registry().entry("money-format").offered(), "short still changes something");
        settings.overrides(new Overrides(Map.of("money-format", "full"), Map.of(), Set.of()));
        assertEquals(MoneyStyle.SERVER, lang.styleOf(SOMEONE), "a choice that is not offered reads the server's way");
        assertEquals("$1,234,567", lang.viewing(SOMEONE, () -> lang.money(1_234_567)), "which is every digit here anyway");

        // The server already shortens from k with one decimal: short is the server's way.
        money.set(format(1_000, 1, MoneyFormat.DEFAULT_SUFFIXES));
        assertEquals(List.of("server", "full"), offeredOptions(settings));
        assertEquals(MoneyStyle.FULL, lang.styleOf(SOMEONE), "in full is offered again, so the server default applies again");
        settings.overrides(new Overrides(Map.of("money-format", "short"), Map.of(), Set.of()));
        assertEquals(MoneyStyle.SERVER, lang.styleOf(SOMEONE));
        assertEquals("$1.2m", lang.viewing(SOMEONE, () -> lang.money(1_234_567)), "the same as short here");

        // Nothing can be shortened (no suffixes): every option writes the same, so the setting is not shown at all.
        money.set(format(1_000_000, 2, List.of()));
        assertEquals(List.of("server"), offeredOptions(settings));
        assertFalse(settings.registry().entry("money-format").offered(), "a switch that changes nothing is not shown");

        // /sift reload back to the shipped format: offered again, and the stored choice comes back.
        money.set(MoneyFormat.defaults());
        assertTrue(settings.registry().entry("money-format").offered());
        assertEquals(List.of("server", "full", "short"), offeredOptions(settings));
        assertEquals(MoneyStyle.SHORT, lang.styleOf(SOMEONE));
    }

    @Test
    void langReadsEachPlayersChoiceFromTheSetting() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Lang lang = Fakes.lang(List.of("lang/core.yml"), CoreMessages.class, MoneyDisplay.class);
        MoneyDisplay.register(settings, lang);
        assertEquals(MoneyStyle.SERVER, lang.styleOf(SOMEONE), "the default");
        settings.overrides(new Overrides(Map.of("money-format", "short"), Map.of(), Set.of()));
        assertEquals(MoneyStyle.SHORT, lang.styleOf(SOMEONE), "a server default reaches players who never chose");
        assertEquals("$1.2m", lang.viewing(SOMEONE, () -> lang.money(1_234_567)));
        settings.overrides(new Overrides(Map.of(), Map.of("money-format", "full"), Set.of()));
        assertEquals(MoneyStyle.FULL, lang.styleOf(SOMEONE), "a lock wins");
        assertEquals("$1,234,567", lang.viewing(SOMEONE, () -> lang.money(1_234_567)));
    }
}
