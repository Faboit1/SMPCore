package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.FriendLookup;
import net.siftvanilla.siftcore.core.link.IgnoreLookup;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The payment settings: group and order, the shared vocabularies, old stored values and config-dependent offering. */
class EconomySettingsTest {

    private static EconomySettings shipped(boolean offline) throws Exception {
        InputStream in = EconomySettingsTest.class.getClassLoader().getResourceAsStream("features/economy.yml");
        YamlConfiguration yaml;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml = YamlConfiguration.loadConfiguration(reader);
        }
        yaml.set("pay.allow-offline-targets", offline);
        ConfigReader reader = new ConfigReader("features/economy.yml", yaml);
        EconomySettings settings = EconomySettings.parse(reader, MoneyFormat.defaults());
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static List<String> offered(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.ECONOMY.id()).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    /** Friends exist: an ordinary friend graph where nobody is friends with anybody. */
    private static Relations withFriends() {
        Relations relations = new Relations();
        relations.bind(new FriendLookup() {
            @Override
            public boolean friends(UUID a, UUID b) {
                return false;
            }

            @Override
            public java.util.Set<UUID> friendsOf(UUID player) {
                return java.util.Set.of();
            }
        }, TeamLookup.NONE, IgnoreLookup.NONE);
        return relations;
    }

    @Test
    void paymentSettingsSitInMoneyAndSellingInCatalogOrder() throws Exception {
        AtomicReference<EconomySettings> config = new AtomicReference<>(shipped(true));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Relations relations = withFriends();
        SharedSettings.register(settings, relations);
        settings.reads(SharedSettings.SELL_RECEIPTS);
        EconomyFeature.registerSettings(settings, relations, config::get);
        assertEquals(List.of("sell_receipts", "pay-notifications", "pay-confirm-above", "pay-accept-from", "pay-join-summary",
            "pay-alert-minimum"), offered(settings));
        assertTrue(settings.hasReader(SharedSettings.BALANCE_PRIVACY), "/balance <name> reads balance-privacy");
        assertTrue(settings.hasReader(SharedSettings.HIDE_FROM_LEADERBOARDS), "the money leaderboard reads hide-from-leaderboards");
        assertFalse(settings.registry().entry("pay-accept-from").placeholder(), "a who-can setting is never a placeholder");

        config.set(shipped(false));
        assertFalse(offered(settings).contains("pay-join-summary"), "no summary of offline payments where nobody can pay offline players");
    }

    @Test
    void theOptionsUseTheSharedVocabularies() {
        assertEquals(List.of("chat", "actionbar", "off"), EconomyFeature.PAY_NOTIFICATIONS.optionIds());
        assertEquals(AlertStyle.CHAT, EconomyFeature.PAY_NOTIFICATIONS.defaultValue());
        assertEquals(List.of("server", "always", "1k", "10k", "100k"), EconomyFeature.PAY_CONFIRM_ABOVE.optionIds());
        assertEquals(ConfirmAbove.SERVER, EconomyFeature.PAY_CONFIRM_ABOVE.defaultValue());
        assertEquals(List.of("everyone", "friends-team", "friends", "nobody"), EconomyFeature.PAY_ACCEPT_FROM.optionIds());
        assertEquals(Audience.EVERYONE, EconomyFeature.PAY_ACCEPT_FROM.defaultValue());
        assertTrue(EconomyFeature.PAY_JOIN_SUMMARY.defaultOn());
        assertEquals(List.of("any", "100", "1k", "10k", "100k"), EconomyFeature.PAY_ALERT_MINIMUM.optionIds());
        assertEquals(0L, EconomyFeature.PAY_ALERT_MINIMUM.defaultValue());
        assertEquals(List.of(0L, 100L, 1_000L, 10_000L, 100_000L),
            EconomyFeature.PAY_ALERT_MINIMUM.options().stream().map(Choice.Option::value).toList());
    }

    @Test
    void oldPaymentSwitchRowsStillDecode() {
        assertEquals(AlertStyle.CHAT, EconomyFeature.PAY_NOTIFICATIONS.decodeOrNull("true"), "the old switch on: chat, as before");
        assertEquals(AlertStyle.OFF, EconomyFeature.PAY_NOTIFICATIONS.decodeOrNull("false"), "the old switch off: no alerts, as before");
        assertEquals(AlertStyle.ACTIONBAR, EconomyFeature.PAY_NOTIFICATIONS.decodeOrNull(" ActionBar "));
        assertNull(EconomyFeature.PAY_NOTIFICATIONS.decodeOrNull("title"), "not offered for payments");
        assertEquals("off", EconomyFeature.PAY_NOTIFICATIONS.encode(AlertStyle.OFF));
    }

    @Test
    void friendOptionsReadAsNobodyWithoutFriends() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        Relations none = new Relations();
        SharedSettings.register(settings, none);
        EconomyFeature.registerSettings(settings, none, () -> {
            try {
                return shipped(true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Registry.Entry<?> entry = settings.registry().entry(EconomyFeature.PAY_ACCEPT_FROM.id());
        assertFalse(entry.options().isOptionAvailable("friends"));
        assertFalse(entry.options().isOptionAvailable("friends-team"));
        assertTrue(entry.options().isOptionAvailable("everyone"));
        assertEquals("nobody", EconomyFeature.PAY_ACCEPT_FROM.option("friends").unavailableAs(),
            "friends only on a server without friends: nobody, never more open than chosen");
        assertEquals("nobody", EconomyFeature.PAY_ACCEPT_FROM.option("friends-team").unavailableAs());
        assertTrue(withFriends().friendsAvailable());
    }

    @Test
    void friendsAndTeammatesMeansTeammatesOnAServerWithOnlyTeams() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        UUID owner = new UUID(0, 1);
        UUID mate = new UUID(0, 2);
        Relations teams = new Relations();
        teams.bind(FriendLookup.NONE, new TeamLookup() {
            @Override
            public java.util.Optional<Long> team(UUID player) {
                return player.equals(owner) || player.equals(mate) ? java.util.Optional.of(7L) : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<String> teamName(UUID player) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean friendlyFire(long team) {
                return false;
            }

            @Override
            public java.util.Set<UUID> members(long team) {
                return java.util.Set.of(owner, mate);
            }
        }, IgnoreLookup.NONE);
        SharedSettings.register(settings, teams);
        EconomyFeature.registerSettings(settings, teams, () -> {
            try {
                return shipped(true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Registry.Entry<?> entry = settings.registry().entry(EconomyFeature.PAY_ACCEPT_FROM.id());
        assertTrue(entry.options().isOptionAvailable("friends-team"), "teams exist: friends and teammates means teammates");
        assertFalse(entry.options().isOptionAvailable("friends"), "no friends system: friends only is not offered");
        assertTrue(EconomyFeature.friendsOrTeams(teams));
        assertFalse(EconomyFeature.friendsOrTeams(new Relations()));
        assertTrue(teams.allows(Audience.FRIENDS_TEAM, owner, mate), "a teammate may pay");
        assertFalse(teams.allows(Audience.FRIENDS_TEAM, owner, new UUID(0, 3)), "anyone else may not");
        assertTrue(PayRules.accepts(Audience.FRIENDS_TEAM, false, true, false, false));
        assertFalse(PayRules.accepts(Audience.FRIENDS_TEAM, false, true, true, false), "an ignored teammate still can't pay");
    }

    @Test
    void theShippedConfigKeepsItsDefaults() throws Exception {
        EconomySettings s = shipped(true);
        assertEquals(100_000, s.payConfirmAbove());
        assertEquals(Duration.ofSeconds(2), s.payCooldown());
    }
}
