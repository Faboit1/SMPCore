package net.siftvanilla.siftcore.feature.afk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The AFK settings: group and order, the old switch's values, config-dependent offering and the pure deciders. */
class AfkPlayerSettingsTest {

    private static AfkSettings config(YamlConfiguration yaml) {
        ConfigReader reader = new ConfigReader("features/afk.yml", yaml);
        AfkSettings settings = AfkSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static PlayerSettings registered(AtomicReference<AfkSettings> config) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        AfkFeature.registerSettings(settings, config::get);
        return settings;
    }

    private static List<String> offered(PlayerSettings settings) {
        return settings.registry().in(SettingCategories.AFK.id()).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    @Test
    void settingsSitInTheAfkGroupInCatalogOrder() throws Exception {
        AtomicReference<AfkSettings> config = new AtomicReference<>(config(AfkResourcesTest.yaml("features/afk.yml")));
        PlayerSettings settings = registered(config);
        List<String> ids = List.of("afk-zone-status", "afk-zone-payouts", "afk-kick-warning", "afk-status-messages", "afk-return-summary");
        assertEquals(ids, settings.registry().in(SettingCategories.AFK.id()).stream().map(Registry.Entry::id).toList());
        assertEquals(ids, offered(settings), "everything is offered with the shipped config");
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(i + 1, settings.registry().entry(ids.get(i)).options().order(), ids.get(i));
        }
        for (Registry.Entry<?> entry : settings.registry().entries()) {
            if (entry.setting() instanceof Choice<?> choice) {
                assertTrue(choice.options().size() <= Choice.MAX_OPTIONS, entry.id());
            }
            assertNull(entry.setting().permission(), entry.id() + " is for everyone");
        }
    }

    @Test
    void theOldZoneStatusSwitchReadsAsAChoice() {
        Choice<AlertStyle> status = AfkFeature.STATUS;
        assertEquals("afk-zone-status", status.id(), "the id stays, so stored rows keep working");
        assertEquals(List.of("actionbar", "bossbar", "off"), status.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, status.defaultValue());
        assertEquals(AlertStyle.ACTIONBAR, status.decodeOrNull("true"), "a row of the old switch that was on");
        assertEquals(AlertStyle.OFF, status.decodeOrNull("false"), "a row of the old switch that was off");
        assertEquals(AlertStyle.BOSSBAR, status.decodeOrNull(" BossBar "));
        assertNull(status.decodeOrNull("chat"), "chat is not an option of the countdown");
    }

    @Test
    void theOtherChoicesOfferTheSharedStyles() {
        assertEquals(List.of("actionbar", "chat", "off"), AfkFeature.PAYOUTS.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, AfkFeature.PAYOUTS.defaultValue());
        assertEquals(List.of("chat", "title"), AfkFeature.KICK_WARNING.optionIds(), "the kick warning can't be turned off");
        assertEquals(AlertStyle.CHAT, AfkFeature.KICK_WARNING.defaultValue());
        assertEquals(List.of("actionbar", "chat", "off"), AfkFeature.STATUS_MESSAGES.optionIds());
        assertEquals(AlertStyle.ACTIONBAR, AfkFeature.STATUS_MESSAGES.defaultValue());
        assertTrue(AfkFeature.RETURN_SUMMARY.defaultOn());
    }

    @Test
    void zoneSettingsAreOnlyOfferedWhileTheZoneIsOn() throws Exception {
        YamlConfiguration yaml = AfkResourcesTest.yaml("features/afk.yml");
        AtomicReference<AfkSettings> config = new AtomicReference<>(config(yaml));
        PlayerSettings settings = registered(config);

        yaml.set("zone.enabled", false);
        config.set(config(yaml));
        assertEquals(List.of("afk-kick-warning", "afk-status-messages", "afk-return-summary"), offered(settings));

        yaml.set("zone.enabled", true);
        yaml.set("rewards.status-every", "0s");
        config.set(config(yaml));
        assertEquals(List.of("afk-zone-payouts", "afk-kick-warning", "afk-status-messages", "afk-return-summary"), offered(settings),
            "no countdown at all when the server turned it off");
    }

    @Test
    void theKickWarningStyleNeedsAKickWithAWarning() throws Exception {
        YamlConfiguration yaml = AfkResourcesTest.yaml("features/afk.yml");
        AtomicReference<AfkSettings> config = new AtomicReference<>(config(yaml));
        PlayerSettings settings = registered(config);
        assertTrue(AfkFeature.warnsBeforeKick(config.get()));

        yaml.set("kick.warn-before", "0s");
        config.set(config(yaml));
        assertFalse(AfkFeature.warnsBeforeKick(config.get()));
        assertFalse(offered(settings).contains("afk-kick-warning"));

        yaml.set("kick.warn-before", "1m");
        yaml.set("kick.enabled", false);
        config.set(config(yaml));
        assertFalse(AfkFeature.warnsBeforeKick(config.get()), "no kick, nothing to warn about");
        assertFalse(offered(settings).contains("afk-kick-warning"));
    }

    @Test
    void storedValuesDecodeAndUnknownPlayersReadTheDefaults() throws Exception {
        PlayerSettings settings = registered(new AtomicReference<>(config(AfkResourcesTest.yaml("features/afk.yml"))));
        UUID player = new UUID(3, 3);
        assertEquals(AlertStyle.ACTIONBAR, settings.get(player, AfkFeature.STATUS));
        assertEquals(AlertStyle.CHAT, settings.get(player, AfkFeature.KICK_WARNING));
        assertTrue(settings.get(player, AfkFeature.RETURN_SUMMARY));
    }

    @Test
    void slashAfkAlwaysAnswers() {
        assertEquals(AlertStyle.ACTIONBAR, AfkService.statusStyle(AlertStyle.OFF, true), "/afk with the lines off still answers");
        assertEquals(AlertStyle.OFF, AfkService.statusStyle(AlertStyle.OFF, false), "becoming AFK on its own stays silent");
        assertEquals(AlertStyle.CHAT, AfkService.statusStyle(AlertStyle.CHAT, true));
        assertEquals(AlertStyle.CHAT, AfkService.statusStyle(AlertStyle.CHAT, false));
        assertEquals(AlertStyle.ACTIONBAR, AfkService.statusStyle(AlertStyle.ACTIONBAR, false));
    }

    @Test
    void theBossBarFillsTowardsTheNextShard() {
        assertEquals(0f, AfkService.barProgress(60_000, 60_000), 1e-6, "a whole interval to go");
        assertEquals(0.5f, AfkService.barProgress(30_000, 60_000), 1e-6);
        assertEquals(1f, AfkService.barProgress(0, 60_000), 1e-6, "due now");
        assertEquals(1f, AfkService.barProgress(-5, 60_000), 1e-6, "overdue stays full");
        assertEquals(0f, AfkService.barProgress(90_000, 60_000), 1e-6, "a longer wait (a reload shortened the interval) stays empty");
        assertEquals(1f, AfkService.barProgress(10, 0), 1e-6, "no interval");
    }

    @Test
    void aSlashAfkSpellCountsFromTheCommand() {
        UUID id = new UUID(4, 4);
        AfkClock.Timing timing = new AfkClock.Timing(60_000, 0, 0, 0, 0);
        PlayerAfk state = new PlayerAfk(id, ActivityClassifier.Settings.DEFAULTS, 0);
        state.earned(5, 500);
        assertNull(state.lastSpell(), "no spell yet");
        assertEquals(AfkClock.Change.BECAME_AFK, state.goAfk(1_000, timing));
        state.earned(2, 30_000);
        state.earned(3, 60_000);
        state.earned(0, 70_000);
        assertEquals(AfkClock.Change.RETURNED, state.comeBack(121_000));
        assertEquals(new PlayerAfk.Spell(120_000, 5), state.lastSpell(), "two minutes away, the 5 shards paid since /afk");
        state.earned(7, 130_000);
        assertEquals(new PlayerAfk.Spell(120_000, 5), state.lastSpell(), "shards paid after coming back don't change it");

        assertEquals(AfkClock.Change.BECAME_AFK, state.goAfk(140_000, timing));
        assertEquals(AfkClock.Change.RETURNED, state.comeBack(150_000));
        assertEquals(new PlayerAfk.Spell(10_000, 0), state.lastSpell(), "a new /afk spell leaves out what was paid before the command");
    }

    @Test
    void aSpellTheClockNoticesStartsAtTheLastActivity() {
        long afkAfter = 300_000;
        AfkClock.Timing timing = new AfkClock.Timing(afkAfter, 0, 0, 0, 0);
        PlayerAfk state = new PlayerAfk(new UUID(4, 5), ActivityClassifier.Settings.DEFAULTS, 0);
        state.earned(1, 5_000);
        assertEquals(AfkClock.Change.NONE, state.chat("brb", 10_000, timing), "the last activity");
        // Idle in the zone: a shard a minute, before and after the AFK mark.
        long paid = 0;
        for (long at = 70_000; at < 10_000 + afkAfter; at += 60_000) {
            state.earned(1, at);
            paid++;
        }
        assertEquals(4, paid);
        assertEquals(AfkClock.Change.NONE, state.tick(10_000 + afkAfter - 1, timing, false));
        assertEquals(AfkClock.Change.BECAME_AFK, state.tick(10_000 + afkAfter, timing, false), "the AFK mark, afk-after later");
        for (long at = 10_000 + afkAfter; at <= 550_000; at += 60_000) {
            state.earned(1, at);
            paid++;
        }
        assertEquals(9, paid);
        assertEquals(AfkClock.Change.RETURNED, state.chat("back", 560_000, timing));
        assertEquals(new PlayerAfk.Spell(550_000, 9), state.lastSpell(),
            "away since the last activity (9m10s, not the 4m10s since the mark), with every shard paid since then");
    }

    @Test
    void aShortPassiveSpellStillGetsItsSummary() {
        long afkAfter = 300_000;
        AfkClock.Timing timing = new AfkClock.Timing(afkAfter, 0, 0, 0, 0);
        PlayerAfk state = new PlayerAfk(new UUID(4, 6), ActivityClassifier.Settings.DEFAULTS, 0);
        assertEquals(AfkClock.Change.BECAME_AFK, state.tick(afkAfter, timing, false));
        assertEquals(AfkClock.Change.RETURNED, state.chat("back", 350_000, timing));
        PlayerAfk.Spell spell = state.lastSpell();
        assertEquals(new PlayerAfk.Spell(350_000, 0), spell, "5m50s idle is 5m50s away, not 50s");
        assertTrue(spell.worthTelling());
    }

    @Test
    void motionPastTheMotionLimitDoesNotDropShardsPaidAfterTheLastActivity() {
        AfkClock.Timing timing = new AfkClock.Timing(300_000, 0, 0, 0, 60_000);
        PlayerAfk state = new PlayerAfk(new UUID(4, 7), ActivityClassifier.Settings.DEFAULTS, 0);
        state.chat("hello", 1_000, timing);
        state.earned(1, 20_000);
        assertEquals(AfkClock.Change.NONE, state.move(turn(0f, 90f, 30_000), timing), "looking around within the limit is activity");
        state.earned(1, 70_000);
        state.move(turn(90f, 180f, 90_000), timing);
        assertEquals(61_000, state.lastActivity(timing), "motion past the limit counts up to the limit only");
        assertEquals(AfkClock.Change.BECAME_AFK, state.tick(361_000, timing, false));
        state.earned(1, 361_000);
        assertEquals(AfkClock.Change.RETURNED, state.chat("back", 400_000, timing));
        assertEquals(new PlayerAfk.Spell(339_000, 2), state.lastSpell(),
            "from the last activity the clock counts (61s), with the shard paid after it and the one while AFK");
    }

    /** A plain turn of the view from {@code fromYaw} to {@code toYaw}, standing still. */
    private static ActivityClassifier.Move turn(float fromYaw, float toYaw, long now) {
        return new ActivityClassifier.Move(0, 64, 0, 0, 64, 0, fromYaw, 0f, toYaw, 0f, Float.NaN, false, false, false, now);
    }

    @Test
    void theSpellLengthIsNeverNegative() {
        assertEquals(new PlayerAfk.Spell(0, 3), PlayerAfk.spell(-1, 10_000, 3), "unknown start");
        assertEquals(new PlayerAfk.Spell(0, 0), PlayerAfk.spell(20_000, 10_000, 0), "a clock that went back");
        assertEquals(new PlayerAfk.Spell(5_000, 1), PlayerAfk.spell(5_000, 10_000, 1));
    }

    @Test
    void onlySpellsWorthReadingGetASummary() {
        assertFalse(new PlayerAfk.Spell(5_000, 0).worthTelling(), "a quick /afk and back");
        assertTrue(new PlayerAfk.Spell(5_000, 1).worthTelling(), "shards earned");
        assertTrue(new PlayerAfk.Spell(PlayerAfk.Spell.SHORT_MILLIS, 0).worthTelling(), "a minute away");
        assertFalse(new PlayerAfk.Spell(PlayerAfk.Spell.SHORT_MILLIS - 1, 0).worthTelling());
    }
}
