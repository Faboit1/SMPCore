package net.siftvanilla.siftcore.feature.bounties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Announce;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bounty settings: groups and order, config-dependent offering, the confirmation threshold and the announcement filter. */
class BountySettingsTest {

    private static YamlConfiguration shipped() throws Exception {
        InputStream in = BountySettingsTest.class.getClassLoader().getResourceAsStream("features/bounties.yml");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static BountiesSettings parse(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/bounties.yml", yaml);
        BountiesSettings settings = BountiesSettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    @Test
    void settingsSitInTheirGroupsInCatalogOrder() throws Exception {
        AtomicReference<BountiesSettings> config = new AtomicReference<>(parse(shipped()));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        BountiesFeature.registerSettings(settings, config::get);
        assertEquals(List.of("bounty-target-alert", "quiet-in-combat", "bounty-confirm-above", "bounty-join-reminder"),
            offered(settings, SettingCategories.COMBAT.id()));
        assertEquals(List.of("bounty-announcements"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));

        YamlConfiguration quiet = shipped();
        quiet.set("place.notify-target", false);
        quiet.set("place.remind-on-join", false);
        quiet.set("place.announce", false);
        quiet.set("claim.announce", false);
        config.set(parse(quiet));
        assertEquals(List.of("quiet-in-combat", "bounty-confirm-above"), offered(settings, SettingCategories.COMBAT.id()),
            "switches for messages the server never sends are not offered");
        assertEquals(List.of(), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));
        quiet.set("claim.announce", true);
        config.set(parse(quiet));
        assertEquals(List.of("bounty-announcements"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()), "claims are still announced");
    }

    @Test
    void theOptionsUseTheSharedVocabularies() {
        assertEquals(List.of("chat", "actionbar", "title", "off"), BountiesFeature.TARGET_ALERT.optionIds());
        assertEquals(AlertStyle.CHAT, BountiesFeature.TARGET_ALERT.defaultValue());
        assertEquals(List.of("all", "100k", "1m", "10m", "off"), BountiesFeature.ANNOUNCEMENTS.optionIds());
        assertEquals(Announce.ALL, BountiesFeature.ANNOUNCEMENTS.defaultValue());
        assertEquals(List.of("server", "always", "10k", "100k", "1m"), BountiesFeature.CONFIRM_ABOVE.optionIds(),
            "no never: a bounty can't be taken back");
        assertEquals(ConfirmAbove.SERVER, BountiesFeature.CONFIRM_ABOVE.defaultValue());
        assertTrue(BountiesFeature.JOIN_REMINDER.defaultOn());
    }

    @Test
    void confirmationFollowsTheSponsorsChoice() {
        ConfirmAbove server = ConfirmAbove.SERVER;
        assertFalse(BountyActions.asks(server, 99_999, 100_000), "below the server's amount");
        assertTrue(BountyActions.asks(server, 100_000, 100_000), "at the server's amount");
        assertFalse(BountyActions.asks(server, 5_000_000, 0), "the server's 0 never asks");
        assertTrue(BountyActions.asks(ConfirmAbove.ALWAYS, 1_000, 100_000));
        ConfirmAbove from10k = BountiesFeature.CONFIRM_ABOVE.decode("10k").orElseThrow();
        assertFalse(BountyActions.asks(from10k, 9_999, 100_000));
        assertTrue(BountyActions.asks(from10k, 10_000, 100_000), "stricter than the server");
        ConfirmAbove from1m = BountiesFeature.CONFIRM_ABOVE.decode("1m").orElseThrow();
        assertFalse(BountyActions.asks(from1m, 500_000, 100_000), "looser than the server is the player's choice");
        assertTrue(BountyActions.asks(from1m, 1_000_000, 0));
    }

    @Test
    void theTargetAlertFollowsTheTargetsStyle() {
        assertNull(BountyActions.targetLine(AlertStyle.OFF, false), "off tells the target nothing");
        assertEquals(BountiesMessages.PLACED_TARGET, BountyActions.targetLine(AlertStyle.CHAT, false));
        assertEquals(BountiesMessages.PLACED_TARGET, BountyActions.targetLine(AlertStyle.ACTIONBAR, false));
        assertEquals(BountiesMessages.PLACED_TARGET_TITLE, BountyActions.targetLine(AlertStyle.TITLE, false));
        assertEquals(BountiesMessages.PLACED_TARGET, BountyActions.targetLine(AlertStyle.TITLE, true),
            "quiet in combat makes the title a chat line, which gets the full text");
    }

    @Test
    void announcementsFilterByAmount() {
        Announce from100k = BountiesFeature.ANNOUNCEMENTS.decode("100k").orElseThrow();
        Announce from10m = BountiesFeature.ANNOUNCEMENTS.decode("10m").orElseThrow();
        assertTrue(BountyActions.shows(Announce.ALL, 1_000));
        assertFalse(BountyActions.shows(from100k, 99_999));
        assertTrue(BountyActions.shows(from100k, 100_000));
        assertFalse(BountyActions.shows(from10m, 9_999_999));
        assertTrue(BountyActions.shows(from10m, 10_000_000));
        assertFalse(BountyActions.shows(Announce.OFF, Long.MAX_VALUE));
    }
}
