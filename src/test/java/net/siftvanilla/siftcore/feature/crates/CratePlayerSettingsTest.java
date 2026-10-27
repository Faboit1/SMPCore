package net.siftvanilla.siftcore.feature.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import org.junit.jupiter.api.Test;

/** The crate settings: groups and order, legacy values of crate-wins, config-dependent offering and the deciders. */
class CratePlayerSettingsTest {

    private static final Rarity COMMON = new Rarity("common", "Common", false, false);
    private static final Rarity RARE = new Rarity("rare", "Rare", true, false);
    private static final Rarity EPIC = new Rarity("epic", "Epic", true, true);
    private static final Rarity LEGENDARY = new Rarity("legendary", "Legendary", true, true);
    private static final List<Rarity> SHIPPED = List.of(COMMON, RARE, EPIC, LEGENDARY);

    static CratesSettings.Keyall keyall(boolean enabled, List<Duration> chatAt, Duration actionBar) {
        return new CratesSettings.Keyall(enabled, Duration.ofHours(4), "basic", 1, Duration.ofMinutes(10), false, true, chatAt, actionBar);
    }

    static CratesSettings config(int bulkOpen, boolean quickOpen, boolean joinReminder, CratesSettings.Keyall keyall, List<Rarity> rarities) {
        return new CratesSettings(Duration.ofSeconds(1), true, bulkOpen, quickOpen, joinReminder, Duration.ofDays(90), keyall, rarities,
            List.of());
    }

    private static CratesSettings shipped() {
        return config(10, true, true, keyall(true, List.of(Duration.ofMinutes(5), Duration.ofMinutes(1)), Duration.ofSeconds(10)), SHIPPED);
    }

    private static PlayerSettings registered(AtomicReference<CratesSettings> config) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        CratePlayerSettings.register(settings, config::get);
        return settings;
    }

    private static List<String> ids(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().map(Registry.Entry::id).toList();
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    @Test
    void settingsSitInTheirGroupsAndAreOfferedWithTheShippedConfig() {
        PlayerSettings settings = registered(new AtomicReference<>(shipped()));
        assertEquals(List.of("crate-receipt", "crate-key-reminder", "keyall-countdown", "crate-quick-open", "crate-bulk-amount"),
            ids(settings, SettingCategories.CRATES.id()));
        assertEquals(List.of("crate-wins"), ids(settings, SettingCategories.ANNOUNCEMENTS.id()));
        assertEquals(ids(settings, SettingCategories.CRATES.id()), offered(settings, SettingCategories.CRATES.id()));
        assertEquals(List.of("crate-wins"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));
        for (Registry.Entry<?> entry : settings.registry().entries()) {
            if (entry.setting() instanceof Choice<?> choice) {
                assertTrue(choice.options().size() <= Choice.MAX_OPTIONS, entry.id());
            }
            assertEquals(null, entry.setting().permission(), entry.id() + " is for everyone");
        }
        assertEquals("crate_bulk_amount", settings.registry().entry("crate-bulk-amount").inputKey());
    }

    @Test
    void defaultsMatchTheCatalog() {
        assertEquals(CratePlayerSettings.WinFilter.ALL, CratePlayerSettings.WIN_ANNOUNCEMENTS.defaultValue());
        assertEquals(AlertStyle.CHAT, CratePlayerSettings.RECEIPT.defaultValue());
        assertTrue(CratePlayerSettings.KEY_REMINDER.defaultOn());
        assertEquals(AlertStyle.BOTH, CratePlayerSettings.KEYALL_COUNTDOWN.defaultValue());
        assertEquals(CratePlayerSettings.QuickOpen.ONE, CratePlayerSettings.QUICK_OPEN.defaultValue());
        assertEquals(10L, CratePlayerSettings.BULK_AMOUNT.defaultValue());
        assertEquals(List.of("all", "rarest", "off"), CratePlayerSettings.WIN_ANNOUNCEMENTS.optionIds());
        assertEquals(List.of("chat", "actionbar", "off"), CratePlayerSettings.RECEIPT.optionIds());
        assertEquals(List.of("both", "chat", "actionbar", "off"), CratePlayerSettings.KEYALL_COUNTDOWN.optionIds());
        assertEquals(List.of("one", "bulk", "off"), CratePlayerSettings.QUICK_OPEN.optionIds());
        assertEquals(2, CratePlayerSettings.BULK_AMOUNT.min());
        assertEquals(64, CratePlayerSettings.BULK_AMOUNT.max());
    }

    @Test
    void crateWinsWasASwitchAndOldRowsStillRead() {
        Choice<CratePlayerSettings.WinFilter> wins = CratePlayerSettings.WIN_ANNOUNCEMENTS;
        assertEquals(CratePlayerSettings.WinFilter.ALL, wins.decodeOrNull("true"));
        assertEquals(CratePlayerSettings.WinFilter.OFF, wins.decodeOrNull("false"));
        assertEquals(CratePlayerSettings.WinFilter.OFF, wins.decodeOrNull(" FALSE "));
        assertEquals(CratePlayerSettings.WinFilter.RAREST, wins.decodeOrNull("rarest"));
        assertEquals(null, wins.decodeOrNull("maybe"));
        assertEquals("off", wins.encode(CratePlayerSettings.WinFilter.OFF));
        // Config entries written for the old switch keep working too.
        assertEquals(CratePlayerSettings.WinFilter.OFF, PlayerSettings.configValue(
            registered(new AtomicReference<>(shipped())).registry().entry("crate-wins"), "false"));
    }

    @Test
    void settingsTheServerTurnedOffAreNotOffered() {
        AtomicReference<CratesSettings> config = new AtomicReference<>(shipped());
        PlayerSettings settings = registered(config);
        config.set(config(0, false, false, keyall(false, List.of(), Duration.ZERO), List.of(COMMON, RARE)));
        assertEquals(List.of("crate-receipt"), offered(settings, SettingCategories.CRATES.id()),
            "only the receipt is left without quick-open, bulk opening, the join reminder and the keyall");
        assertEquals(List.of(), offered(settings, SettingCategories.ANNOUNCEMENTS.id()), "no rarity is announced");

        config.set(config(10, true, true, keyall(true, List.of(), Duration.ZERO), SHIPPED));
        assertFalse(offered(settings, SettingCategories.CRATES.id()).contains("keyall-countdown"),
            "a keyall without announcements or a countdown has nothing to show");
        config.set(config(10, true, true, keyall(true, List.of(), Duration.ofSeconds(10)), SHIPPED));
        assertTrue(offered(settings, SettingCategories.CRATES.id()).contains("keyall-countdown"));
    }

    @Test
    void optionsTheServerCantGiveFallBack() {
        AtomicReference<CratesSettings> config = new AtomicReference<>(shipped());
        PlayerSettings settings = registered(config);
        Registry.Entry<CratePlayerSettings.QuickOpen> quick = typed(settings, "crate-quick-open");
        assertEquals(List.of("one", "bulk", "off"), optionIds(settings, quick));
        Registry.Entry<CratePlayerSettings.WinFilter> wins = typed(settings, "crate-wins");
        assertEquals(List.of("all", "rarest", "off"), optionIds(settings, wins));

        config.set(config(1, true, true, shipped().keyall(), List.of(COMMON, RARE, EPIC)));
        assertEquals(List.of("one", "off"), optionIds(settings, quick), "no bulk opening without bulk-open");
        assertEquals(List.of("all", "off"), optionIds(settings, wins), "rarest is all when only one rarity is announced");
        UUID player = new UUID(9, 9);
        assertEquals(CratePlayerSettings.QuickOpen.ONE, settings.get(player, CratePlayerSettings.QUICK_OPEN));
        assertFalse(settings.registry().entry("crate-bulk-amount").offered());
    }

    @Test
    void keyallCountdownOffersOnlyWhatTheServerShows() {
        AtomicReference<CratesSettings> config = new AtomicReference<>(shipped());
        PlayerSettings settings = registered(config);
        Registry.Entry<AlertStyle> countdown = typed(settings, "keyall-countdown");
        UUID player = new UUID(9, 9);
        assertEquals(List.of("both", "chat", "actionbar", "off"), optionIds(settings, countdown));

        config.set(config(10, true, true, keyall(true, List.of(Duration.ofMinutes(5)), Duration.ZERO), SHIPPED));
        assertEquals(List.of("chat", "off"), optionIds(settings, countdown), "no action bar count: chat only");
        assertEquals(AlertStyle.CHAT, settings.get(player, CratePlayerSettings.KEYALL_COUNTDOWN), "both reads as chat");
        settings.overrides(new Overrides(Map.of("keyall-countdown", "actionbar"), Map.of(), Set.of()));
        assertEquals(AlertStyle.CHAT, settings.get(player, CratePlayerSettings.KEYALL_COUNTDOWN), "actionbar reads as chat");
        settings.overrides(Overrides.NONE);

        config.set(config(10, true, true, keyall(true, List.of(), Duration.ofSeconds(10)), SHIPPED));
        assertEquals(List.of("actionbar", "off"), optionIds(settings, countdown), "no chat lines: action bar only");
        assertEquals(AlertStyle.ACTIONBAR, settings.get(player, CratePlayerSettings.KEYALL_COUNTDOWN), "both reads as the action bar");
        settings.overrides(new Overrides(Map.of("keyall-countdown", "chat"), Map.of(), Set.of()));
        assertEquals(AlertStyle.ACTIONBAR, settings.get(player, CratePlayerSettings.KEYALL_COUNTDOWN), "chat reads as the action bar");
        settings.overrides(new Overrides(Map.of("keyall-countdown", "off"), Map.of(), Set.of()));
        assertEquals(AlertStyle.OFF, settings.get(player, CratePlayerSettings.KEYALL_COUNTDOWN), "off stays off");

        config.set(config(10, true, true, keyall(true, List.of(), Duration.ZERO), SHIPPED));
        assertFalse(offered(settings, SettingCategories.CRATES.id()).contains("keyall-countdown"), "nothing shown: not offered");
    }

    @Test
    void aBulkOpeningsStopReasonGoesToChatWhenItsReceiptTookTheActionBar() {
        assertTrue(CratePlayerSettings.stopReasonInChat(AlertStyle.ACTIONBAR), "it would replace the receipt there");
        assertFalse(CratePlayerSettings.stopReasonInChat(AlertStyle.CHAT), "the receipt is in chat: the reason may take the action bar");
        assertFalse(CratePlayerSettings.stopReasonInChat(AlertStyle.OFF), "no receipt shown");
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry.Entry<T> typed(PlayerSettings settings, String id) {
        return (Registry.Entry<T>) settings.registry().entry(id);
    }

    private static <T> List<String> optionIds(PlayerSettings settings, Registry.Entry<T> entry) {
        return settings.options(entry, permission -> true).stream().map(Choice.Option::id).toList();
    }

    @Test
    void winFilterShowsAllTheRarestOrNothing() {
        assertEquals(LEGENDARY, CratePlayerSettings.rarestAnnounced(SHIPPED));
        assertEquals(2, CratePlayerSettings.announced(SHIPPED));
        assertEquals(null, CratePlayerSettings.rarestAnnounced(List.of(COMMON, RARE)));
        assertTrue(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.ALL, EPIC, SHIPPED));
        assertTrue(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.ALL, LEGENDARY, SHIPPED));
        assertFalse(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.RAREST, EPIC, SHIPPED));
        assertTrue(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.RAREST, LEGENDARY, SHIPPED));
        assertFalse(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.OFF, LEGENDARY, SHIPPED));
        // Compared by id: a rarity read from a reloaded config is the same rarity.
        assertTrue(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.RAREST,
            new Rarity("legendary", "Legendary!", false, true), SHIPPED));
        assertFalse(CratePlayerSettings.showsWin(CratePlayerSettings.WinFilter.RAREST, EPIC, List.of(COMMON)));
    }

    @Test
    void bulkAmountIsThePlayersNumberWithinWhatTheyHaveAndTheServerAllows() {
        assertEquals(10, CratePlayerSettings.bulkAmount(10, 25, 10));
        assertEquals(5, CratePlayerSettings.bulkAmount(5, 25, 10), "fewer than the server allows");
        assertEquals(10, CratePlayerSettings.bulkAmount(64, 25, 10), "never more than the server allows");
        assertEquals(3, CratePlayerSettings.bulkAmount(10, 3, 10), "never more than they have");
        assertEquals(0, CratePlayerSettings.bulkAmount(10, 1, 10), "one key is no bulk opening");
        assertEquals(0, CratePlayerSettings.bulkAmount(10, 25, 1), "bulk opening is off");
        assertEquals(2, CratePlayerSettings.bulkAmount(2, 25, 10));
    }

    @Test
    void quickOpenNeedsSneakingTheServerAndNotTheCrateWindow() {
        assertTrue(CratePlayerSettings.quickOpens(CratePlayerSettings.QuickOpen.ONE, true, true));
        assertTrue(CratePlayerSettings.quickOpens(CratePlayerSettings.QuickOpen.BULK, true, true));
        assertFalse(CratePlayerSettings.quickOpens(CratePlayerSettings.QuickOpen.OFF, true, true));
        assertFalse(CratePlayerSettings.quickOpens(CratePlayerSettings.QuickOpen.ONE, false, true));
        assertFalse(CratePlayerSettings.quickOpens(CratePlayerSettings.QuickOpen.ONE, true, false));
    }

    @Test
    void keyallCountdownStyles() {
        assertTrue(CratePlayerSettings.countdownInChat(AlertStyle.BOTH));
        assertTrue(CratePlayerSettings.countdownInActionBar(AlertStyle.BOTH));
        assertTrue(CratePlayerSettings.countdownInChat(AlertStyle.CHAT));
        assertFalse(CratePlayerSettings.countdownInActionBar(AlertStyle.CHAT));
        assertFalse(CratePlayerSettings.countdownInChat(AlertStyle.ACTIONBAR));
        assertTrue(CratePlayerSettings.countdownInActionBar(AlertStyle.ACTIONBAR));
        assertFalse(CratePlayerSettings.countdownInChat(AlertStyle.OFF));
        assertFalse(CratePlayerSettings.countdownInActionBar(AlertStyle.OFF));
        assertTrue(CratePlayerSettings.countdownShown(keyall(true, List.of(Duration.ofMinutes(1)), Duration.ZERO)));
        assertFalse(CratePlayerSettings.countdownShown(keyall(false, List.of(Duration.ofMinutes(1)), Duration.ofSeconds(10))));
    }
}
