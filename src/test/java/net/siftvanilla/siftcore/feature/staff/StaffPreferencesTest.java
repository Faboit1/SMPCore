package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSetting;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.feature.extras.ExtrasMessages;
import net.siftvanilla.siftcore.testing.Fakes;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The staff settings: group, order and permissions, and the pure deciders of notices, vanish and staff chat. */
class StaffPreferencesTest {

    @TempDir
    Path dir;

    private static PlayerSettings registered(AtomicBoolean fakeLines) {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        StaffPreferences.register(settings, (player, before, after) -> { }, fakeLines::get);
        return settings;
    }

    @Test
    void settingsSitInTheStaffGroupInCatalogOrderBehindTheirPermissions() {
        PlayerSettings settings = registered(new AtomicBoolean(true));
        List<Registry.Entry<?>> group = settings.registry().in(SettingCategories.STAFF.id());
        assertEquals(List.of("staff-chat", "staff-punish-alerts", "staff-report-alerts", "vanish-on-join", "vanish-reminder",
            "vanish-see-vanished", "staff-freeze-alerts", "staff-confirm-bans", "vanish-fake-messages"),
            group.stream().map(Registry.Entry::id).toList());
        assertEquals(List.of(2, 3, 4, 6, 7, 8, 9, 11, 12), group.stream().map(entry -> entry.options().order()).toList(),
            "the gaps are social spy (1), team spy (5), staff combat alerts (10) and config problem alerts (13)");
        Map<PlayerSetting<?>, String> permissions = Map.of(
            StaffPreferences.STAFF_CHAT, StaffNodes.CHAT,
            StaffPreferences.PUNISH_ALERTS, StaffNodes.NOTIFY,
            StaffPreferences.REPORT_ALERTS, StaffNodes.REPORTS,
            StaffPreferences.VANISH_ON_JOIN, StaffNodes.VANISH,
            StaffPreferences.VANISH_REMINDER, StaffNodes.VANISH,
            StaffPreferences.SEE_VANISHED, StaffNodes.VANISH_SEE,
            StaffPreferences.FREEZE_ALERTS, StaffNodes.FREEZE,
            StaffPreferences.CONFIRM_BANS, StaffNodes.BAN,
            StaffPreferences.FAKE_MESSAGES, StaffNodes.VANISH);
        assertEquals(group.size(), permissions.size());
        permissions.forEach((setting, node) -> assertEquals(node, setting.permission(), setting.id() + " needs the tool's permission"));
        for (Registry.Entry<?> entry : group) {
            assertFalse(entry.placeholder(), entry.id() + " is staff only, so no placeholder shows it");
            if (entry.setting() instanceof Choice<?> choice) {
                assertEquals(List.of("chat", "actionbar", "off"), choice.optionIds(), entry.id());
                assertEquals(AlertStyle.CHAT, choice.defaultValue(), entry.id());
            }
        }
        assertEquals(SettingOptions.Apply.INSTANT, settings.registry().entry("vanish-see-vanished").options().apply(),
            "who sees vanished staff changes at once");
    }

    @Test
    void defaultsMatchTheCatalog() {
        assertTrue(StaffPreferences.STAFF_CHAT.defaultOn());
        assertFalse(StaffPreferences.VANISH_ON_JOIN.defaultOn());
        assertTrue(StaffPreferences.VANISH_REMINDER.defaultOn());
        assertTrue(StaffPreferences.SEE_VANISHED.defaultOn());
        assertFalse(StaffPreferences.CONFIRM_BANS.defaultOn());
        assertFalse(StaffPreferences.FAKE_MESSAGES.defaultOn());
    }

    @Test
    void fakeLinesAreOfferedOnlyWhenThereIsALineToImitate() {
        AtomicBoolean available = new AtomicBoolean(false);
        PlayerSettings settings = registered(available);
        assertFalse(settings.registry().entry("vanish-fake-messages").offered());
        available.set(true);
        assertTrue(settings.registry().entry("vanish-fake-messages").offered());
    }

    @Test
    void noticesSkipTheActorStaffWithoutThePermissionAndStaffWhoTurnedThemOff() {
        assertEquals(AlertStyle.CHAT, StaffPreferences.noticeStyle(true, false, AlertStyle.CHAT));
        assertEquals(AlertStyle.ACTIONBAR, StaffPreferences.noticeStyle(true, false, AlertStyle.ACTIONBAR));
        assertEquals(AlertStyle.OFF, StaffPreferences.noticeStyle(true, false, AlertStyle.OFF));
        assertEquals(AlertStyle.OFF, StaffPreferences.noticeStyle(false, false, AlertStyle.CHAT), "no permission");
        assertEquals(AlertStyle.OFF, StaffPreferences.noticeStyle(true, true, AlertStyle.CHAT), "the actor already got a confirmation");
    }

    @Test
    void vanishedStaffShowOnlyToViewersWhoMayAndWantToSeeThem() {
        assertTrue(StaffPreferences.shouldSee(false, false, false), "a visible player shows to everyone");
        assertTrue(StaffPreferences.shouldSee(true, true, true));
        assertFalse(StaffPreferences.shouldSee(true, true, false), "See vanished staff turned off");
        assertFalse(StaffPreferences.shouldSee(true, false, true), "the setting alone is not the permission");
        assertFalse(StaffPreferences.shouldSee(true, false, false));
    }

    @Test
    void staffChatAlwaysEchoesTheSender() {
        assertTrue(StaffPreferences.showsStaffChat(true, false), "your own line, with Show staff chat off");
        assertTrue(StaffPreferences.showsStaffChat(false, true));
        assertFalse(StaffPreferences.showsStaffChat(false, false));
    }

    @Test
    void fakeLinesReuseTheExtrasTextAndRespectTheViewersFilter() throws Exception {
        assertEquals(ExtrasMessages.JOIN, FakeLines.JOIN, "the same lang entry as a real join line");
        assertEquals(ExtrasMessages.QUIT, FakeLines.QUIT, "the same lang entry as a real leave line");
        assertTrue(FakeLines.seesJoinLines(null), "without the viewer setting everyone sees them");
        assertTrue(FakeLines.seesJoinLines("all"));
        assertFalse(FakeLines.seesJoinLines("first-joins"), "a fake line is never a first join");
        assertFalse(FakeLines.seesJoinLines("off"));

        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons()), () -> null);
        lang.register(FakeLines.class);
        YamlConfiguration extras = yaml("lang/extras.yml");
        assertEquals(List.of(), lang.load(extras, extras, "lang/extras.yml"), "the extras file has both lines");
    }

    @Test
    void fakeLinesAreAvailableWhenExtrasShowsPlainLines() throws IOException {
        Path features = Files.createDirectories(this.dir.resolve("features"));
        Path file = features.resolve("extras.yml");
        FakeLines lines = new FakeLines(null, null, this.dir, Logger.getLogger("siftcore-test"));
        assertFalse(lines.available(), "no file: the shipped default shows no plain lines");
        write(file, "messages:\n  join: false\n  quit: true\n");
        assertTrue(lines.available(), "leave lines are on");
        write(file, "messages:\n  join: false\n  quit: false\n");
        assertFalse(lines.available(), "re-read after the file changed");
        write(file, "messages: [broken\n");
        assertFalse(lines.available(), "a broken file imitates nothing");
    }

    @Test
    void rankLinesCountOnlyWhenLinkedAndSwitchedOnInTheCosmeticsConfig() throws IOException {
        Path features = Files.createDirectories(this.dir.resolve("features"));
        Path extras = features.resolve("extras.yml");
        Path cosmetics = features.resolve("cosmetics.yml");
        write(extras, "messages:\n  join: false\n  quit: false\n");
        FakeLines lines = new FakeLines(null, null, this.dir, Logger.getLogger("siftcore-test"));
        write(cosmetics, "enabled: true\njoin-messages:\n  enabled: true\n");
        assertFalse(lines.available(), "rank lines on, but not linked: nothing a fake line could copy");

        lines.cosmetics(new Cosmetics() {
        });
        assertTrue(lines.available(), "linked, with the feature and its join lines on");
        write(cosmetics, "enabled: true\njoin-messages:\n  enabled: false\n");
        assertFalse(lines.available(), "join lines turned off in cosmetics: a switch that would do nothing is not offered");
        write(cosmetics, "enabled: false\njoin-messages:\n  enabled: true\n");
        assertFalse(lines.available(), "the whole cosmetics feature turned off");
        write(cosmetics, "enabled: [broken\n");
        assertFalse(lines.available(), "a broken cosmetics file offers nothing until it is fixed");
        write(cosmetics, "colors: {}\n");
        assertTrue(lines.available(), "missing keys read as the cosmetics defaults (on)");
        Files.delete(cosmetics);
        assertTrue(lines.available(), "no file yet: the cosmetics defaults (on)");

        write(cosmetics, "enabled: true\njoin-messages:\n  enabled: false\n");
        write(extras, "messages:\n  join: true\n  quit: false\n");
        assertTrue(lines.available(), "the plain lines alone are enough");
        lines.cosmetics(null);
        assertTrue(lines.available());
    }

    @Test
    void theCosmeticsSwitchesReadLikeTheCosmeticsFeature() {
        assertTrue(FakeLines.rankLinesOn(new YamlConfiguration()), "both default to on");
        YamlConfiguration off = new YamlConfiguration();
        off.set("join-messages.enabled", false);
        assertFalse(FakeLines.rankLinesOn(off));
        assertEquals(new FakeLines.PlainLines(true, false), FakeLines.PlainLines.read(yamlOf("messages.join", true)));
        assertEquals(FakeLines.PlainLines.NONE, FakeLines.PlainLines.read(new YamlConfiguration()), "both default to off, as shipped");
    }

    @Test
    void theShippedCosmeticsConfigHasTheSwitchesFakeLinesRead() throws Exception {
        YamlConfiguration shipped = yaml("features/cosmetics.yml");
        assertTrue(shipped.isBoolean("enabled"), "cosmetics.yml has enabled");
        assertTrue(shipped.isBoolean("join-messages.enabled"), "cosmetics.yml has join-messages.enabled");
        YamlConfiguration extras = yaml("features/extras.yml");
        assertTrue(extras.isBoolean("messages.join") && extras.isBoolean("messages.quit"), "extras.yml has messages.join and messages.quit");
    }

    @Test
    void theBanButtonChecksTheBanPermissionAgain() {
        assertEquals(StaffNodes.BAN, PunishCommands.banNode(false), "/ban");
        assertEquals(StaffNodes.TEMPBAN, PunishCommands.banNode(true), "/tempban");
        Messenger messenger = new Messenger(Fakes.lang(List.of("lang/core.yml"), CoreMessages.class), new Sounds());
        Fakes.FakePlayer staff = new Fakes.FakePlayer("BanConfirmer");
        staff.permissions.add(StaffNodes.TEMPBAN);
        assertTrue(PunishCommands.mayStillBan(staff.player, StaffNodes.TEMPBAN, messenger));
        assertEquals(List.of(), staff.said(), "nothing to tell while they may");
        assertFalse(PunishCommands.mayStillBan(staff.player, StaffNodes.BAN, messenger), "a permanent ban needs /ban's permission");
        staff.permissions.clear();
        assertFalse(PunishCommands.mayStillBan(staff.player, StaffNodes.TEMPBAN, messenger), "the permission was taken away meanwhile");
        assertEquals(List.of("You can't do that.", "You can't do that."), staff.said(), "told each time");
    }

    @Test
    void theNoFakeLineNoticeNamesTheSubjectWhenStaffVanishedSomeoneElse() {
        Lang lang = Fakes.lang(List.of("lang/staff.yml"), StaffMessages.class);
        assertEquals("No fake join or leave line for Alex: this server shows none for them right now.",
            TextStyle.plain(lang.get(StaffMessages.VANISH_NO_FAKE_LINE_OTHER, Arg.text("name", "Alex"))));
        assertEquals("No fake join or leave line: this server shows none for you right now.",
            TextStyle.plain(lang.get(StaffMessages.VANISH_NO_FAKE_LINE)));
    }

    /** Writes a file and moves its modification time on, so a re-read within the same second still notices it. */
    private static void write(Path file, String text) throws IOException {
        long before = Files.exists(file) ? file.toFile().lastModified() : 0;
        Files.writeString(file, text, StandardCharsets.UTF_8);
        assertTrue(file.toFile().setLastModified(Math.max(before + 2_000, file.toFile().lastModified())), "a later modification time");
    }

    private static YamlConfiguration yamlOf(String path, Object value) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(path, value);
        return yaml;
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = StaffPreferencesTest.class.getClassLoader().getResourceAsStream(resource);
             var reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    private static Icons icons() throws Exception {
        Icons icons;
        try (var in = StaffPreferencesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            icons = new Icons(Icons.readIndex(in));
        }
        icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons());
        return icons;
    }
}
