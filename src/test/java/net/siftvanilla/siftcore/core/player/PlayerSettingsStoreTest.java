package net.siftvanilla.siftcore.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerSettingsStoreTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");
    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();

    private static final Toggle ALERTS = new Toggle("alerts", true, LABEL, DESCRIPTION, null);
    private static final NumberSetting VOLUME = new NumberSetting("volume", 100, 0, 100, 10, null, LABEL, DESCRIPTION, null);
    private static final Choice<AlertStyle> RECEIPTS = Choices.alert("receipts", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.ACTIONBAR,
        AlertStyle.OFF).legacyValue("true", "chat").legacyValue("false", "actionbar").text(LABEL, DESCRIPTION).build();
    private static final Choice<Audience> PRIVACY = Choice.ofEnum("privacy", Audience.class, Audience::id, Audience.EVERYONE)
        .option(Audience.EVERYONE, LABEL).option(Audience.FRIENDS, LABEL, null, "nobody").option(Audience.NOBODY, LABEL)
        .text(LABEL, DESCRIPTION).build();

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerSettings settings;
    private final boolean[] friends = {true};

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = PlayerSettingsStoreTest.class.getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.settings = fresh();
    }

    private PlayerSettings fresh() {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        s.register(SettingCategories.CHAT, ALERTS);
        s.register(SettingCategories.SOUND, VOLUME);
        s.register(SettingCategories.ECONOMY, RECEIPTS);
        s.register(SettingCategories.PRIVACY, PRIVACY, SettingOptions.<Audience>builder()
            .optionAvailableWhen("friends", () -> this.friends[0]).build());
        return s;
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private void insert(UUID player, String setting, String value) throws Exception {
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                ps.setString(3, value);
                ps.executeUpdate();
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
    }

    private Map<String, String> rows(UUID player) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            Map<String, String> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT setting, value FROM settings WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return map;
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void storedRowsLoadLenientlyAndUnknownRowsSurvive() throws Exception {
        insert(ALEX, "alerts", " OFF ");
        insert(ALEX, "volume", "55");
        insert(ALEX, "receipts", "false");
        insert(ALEX, "privacy", "rubbish");
        insert(ALEX, "auction-sort", "price");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        assertFalse(this.settings.get(ALEX, ALERTS));
        assertEquals(60L, this.settings.get(ALEX, VOLUME), "snapped onto the slider's steps");
        assertEquals(AlertStyle.ACTIONBAR, this.settings.get(ALEX, RECEIPTS), "a toggle row read by the choice it became");
        assertEquals(Audience.EVERYONE, this.settings.get(ALEX, PRIVACY), "an unreadable row reads as the default");
        assertEquals("price", this.settings.raw(ALEX, "auction-sort", "newest"), "UI state stays raw");
        assertEquals("actionbar", this.settings.raw(ALEX, "receipts", "x"), "a registered id reads its effective value");
        assertTrue(this.settings.get(SAM, ALERTS), "players who are not loaded read defaults");
        assertEquals(Map.of("alerts", " OFF ", "volume", "55", "receipts", "false", "privacy", "rubbish", "auction-sort", "price"),
            rows(ALEX), "loading rewrites nothing");
    }

    @Test
    void storingAValueUpsertsAndStoringTheDefaultDeletesTheRow() throws Exception {
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, VOLUME, 40L, Change.dialog("Alex")));
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, RECEIPTS, AlertStyle.OFF, Change.command("Alex")));
        this.settings.set(ALEX, ALERTS, false);
        assertEquals(Map.of("volume", "40", "receipts", "off", "alerts", "false"), rows(ALEX));
        assertTrue(this.settings.changed(ALEX, VOLUME));
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, VOLUME, 100L, Change.dialog("Alex")));
        this.settings.set(ALEX, ALERTS, true);
        assertEquals(Map.of("receipts", "off"), rows(ALEX), "back to the default: the rows are gone");
        assertFalse(this.settings.changed(ALEX, VOLUME));
        assertEquals(100L, this.settings.get(ALEX, VOLUME));
        assertEquals(SetResult.UNCHANGED, this.settings.set(ALEX, RECEIPTS, AlertStyle.OFF, Change.dialog("Alex")));
        assertEquals(SetResult.INVALID, this.settings.set(ALEX, VOLUME, 35L, Change.dialog("Alex")), "off step");
        assertEquals(SetResult.INVALID, this.settings.set(ALEX, VOLUME, null, Change.dialog("Alex")));
        assertEquals(SetResult.UNKNOWN, this.settings.set(ALEX, new Toggle("ghost", true, LABEL, DESCRIPTION, null), true,
            Change.feature()));
        PlayerSettings again = fresh();
        again.load(ALEX).get(5, TimeUnit.SECONDS);
        assertEquals(AlertStyle.OFF, again.get(ALEX, RECEIPTS), "persisted");
    }

    @Test
    void oldRowsEqualToTheDefaultAreLeftAloneUntilTheNextChange() throws Exception {
        insert(ALEX, "alerts", "true");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        assertFalse(this.settings.changed(ALEX, ALERTS), "a stored default counts as unchanged");
        assertEquals(SetResult.UNCHANGED, this.settings.set(ALEX, ALERTS, true, Change.dialog("Alex")));
        assertEquals(Map.of("alerts", "true"), rows(ALEX));
        this.settings.set(ALEX, ALERTS, false);
        this.settings.set(ALEX, ALERTS, true);
        assertEquals(Map.of(), rows(ALEX));
    }

    @Test
    void resetDeletesOnlyTheGivenRows() throws Exception {
        insert(ALEX, "alerts", "false");
        insert(ALEX, "volume", "30");
        insert(ALEX, "receipts", "chat");
        insert(ALEX, "auction-sort", "price");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        List<PlayerSettings.Pending> seen = new ArrayList<>();
        this.settings.listener(change -> {
            seen.add(change);
            return false;
        });
        assertEquals(2, this.settings.reset(ALEX, List.of(ALERTS, RECEIPTS, VOLUME), Change.reset("Alex")),
            "receipts was stored as its default: its row goes but nothing changes");
        assertEquals(Map.of("auction-sort", "price"), rows(ALEX));
        assertEquals(2, seen.size(), "one change per setting that really changed");
        assertTrue(this.settings.get(ALEX, ALERTS), "a reset can't be cancelled");
        assertEquals(0, this.settings.reset(ALEX, List.of(ALERTS), Change.reset("Alex")));
    }

    @Test
    void listenersSeeChangesAndMayCancelOnlyThePlayersOwn() throws Exception {
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        List<PlayerSettings.Pending> seen = new ArrayList<>();
        this.settings.listener(change -> {
            seen.add(change);
            return false;
        });
        assertEquals(SetResult.CANCELLED, this.settings.set(ALEX, VOLUME, 50L, Change.dialog("Alex")));
        assertEquals(SetResult.CANCELLED, this.settings.set(ALEX, VOLUME, 50L, Change.api("Plugin")));
        assertEquals(100L, this.settings.get(ALEX, VOLUME));
        assertEquals(Map.of(), rows(ALEX), "nothing stored");
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, VOLUME, 50L, Change.feature()), "features only report");
        assertEquals(SetResult.CHANGED, this.settings.set(ALEX, VOLUME, 60L, Change.admin("Mod")), "staff only report");
        assertEquals(4, seen.size());
        PlayerSettings.Pending last = seen.getLast();
        assertEquals("50", last.oldValue());
        assertEquals("60", last.newValue());
        assertEquals("volume", last.entry().id());
        assertEquals(Change.Cause.ADMIN, last.change().cause());
        seen.clear();
        assertEquals(SetResult.UNCHANGED, this.settings.set(ALEX, VOLUME, 60L, Change.admin("Mod")));
        assertTrue(seen.isEmpty(), "no event without a change");
    }

    @Test
    void serverDefaultsApplyToPlayersWithoutARowAndLocksBeatEverything() throws Exception {
        insert(ALEX, "volume", "30");
        insert(ALEX, "alerts", "true");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.load(SAM).get(5, TimeUnit.SECONDS);
        this.settings.overrides(new Overrides(Map.of("volume", "70", "receipts", "off"), Map.of("alerts", "false"), Set.of()));
        assertEquals(30L, this.settings.get(ALEX, VOLUME), "a stored choice is kept");
        assertEquals(70L, this.settings.get(SAM, VOLUME), "the server default for the others");
        assertEquals(AlertStyle.OFF, this.settings.get(SAM, RECEIPTS));
        assertFalse(this.settings.get(ALEX, ALERTS), "locked beats stored");
        assertTrue(this.settings.locked(ALERTS));
        assertEquals(SetResult.LOCKED, this.settings.set(ALEX, ALERTS, true, Change.dialog("Alex")));
        assertEquals(70L, this.settings.defaultValue(VOLUME));
        assertEquals(Boolean.FALSE, this.settings.defaultValue(ALERTS), "the lock is the default");
        assertEquals(SetResult.CHANGED, this.settings.set(SAM, VOLUME, 100L, Change.dialog("Sam")), "the code default differs");
        assertEquals(Map.of("volume", "100"), rows(SAM), "kept: it differs from the server default");
        assertEquals(SetResult.CHANGED, this.settings.set(SAM, VOLUME, 70L, Change.dialog("Sam")));
        assertEquals(Map.of(), rows(SAM), "storing the server default deletes the row, so Sam follows it");
        this.settings.overrides(new Overrides(Map.of("volume", "80"), Map.of(), Set.of()));
        assertEquals(80L, this.settings.get(SAM, VOLUME), "and a later default reaches Sam");
        assertTrue(this.settings.get(ALEX, ALERTS), "unlocked again: the stored row counts");
        this.settings.overrides(new Overrides(Map.of("volume", "55", "ghost", "1"), Map.of("receipts", "sideways"), Set.of()));
        assertEquals(100L, this.settings.get(SAM, VOLUME), "values a setting can't take are ignored");
        assertEquals(AlertStyle.CHAT, this.settings.get(SAM, RECEIPTS));
        this.settings.overrides(new Overrides(Map.of(), Map.of("receipts", "false"), Set.of("volume")));
        assertEquals(AlertStyle.ACTIONBAR, this.settings.get(SAM, RECEIPTS), "config entries read legacy values too");
        assertTrue(this.settings.hidden(VOLUME));
        assertFalse(this.settings.visible(this.settings.registry().entry("volume"), node -> true), "hidden settings are not offered");
    }

    @Test
    void anOptionThatIsNotAvailableReadsAsItsFallback() throws Exception {
        insert(ALEX, "privacy", "friends");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        assertEquals(Audience.FRIENDS, this.settings.get(ALEX, PRIVACY));
        this.friends[0] = false;
        assertEquals(Audience.NOBODY, this.settings.get(ALEX, PRIVACY), "never more open than the player chose");
        assertEquals(List.of("everyone", "nobody"), this.settings.options(privacy(), node -> true).stream()
            .map(Choice.Option::id).toList(), "the dialog offers only available options");
        this.friends[0] = true;
        assertEquals(Audience.FRIENDS, this.settings.get(ALEX, PRIVACY), "the choice comes back with the option");
        assertEquals(3, this.settings.options(privacy(), node -> true).size());
    }

    @SuppressWarnings("unchecked")
    private Registry.Entry<Audience> privacy() {
        return (Registry.Entry<Audience>) this.settings.registry().entry("privacy");
    }

    @Test
    void legacyRowsMoveToTheirNewSettingOnLoad() throws Exception {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        Choice<AlertStyle> moved = Choices.alert("moved", AlertStyle.OFF, AlertStyle.OFF, AlertStyle.CHAT).text(LABEL, DESCRIPTION).build();
        s.register(SettingCategories.CHAT, moved, SettingOptions.<AlertStyle>builder()
            .legacy("old-alerts", v -> Boolean.TRUE.equals(Toggle.parse(v)) ? "chat" : Boolean.FALSE.equals(Toggle.parse(v)) ? "off" : null)
            .build());
        insert(ALEX, "old-alerts", "true");
        insert(SAM, "old-alerts", "maybe");
        UUID kai = UUID.randomUUID();
        insert(kai, "old-alerts", "false");
        s.load(ALEX).get(5, TimeUnit.SECONDS);
        s.load(SAM).get(5, TimeUnit.SECONDS);
        s.load(kai).get(5, TimeUnit.SECONDS);
        assertEquals(AlertStyle.CHAT, s.get(ALEX, moved));
        assertEquals(Map.of("moved", "chat"), rows(ALEX), "moved in the table too");
        assertEquals(Map.of("old-alerts", "maybe"), rows(SAM), "a value that can't move stays where it was");
        assertEquals(AlertStyle.OFF, s.get(SAM, moved));
        assertEquals(Map.of(), rows(kai), "a moved default needs no row");
        assertEquals(AlertStyle.OFF, s.get(kai, moved));
    }

    @Test
    void nothingMovesWhileTheOldSettingIsStillRegistered() throws Exception {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        Toggle old = new Toggle("old-alerts", false, LABEL, DESCRIPTION, null);
        Choice<AlertStyle> moved = Choices.alert("moved", AlertStyle.OFF, AlertStyle.OFF, AlertStyle.CHAT).text(LABEL, DESCRIPTION).build();
        s.register(SettingCategories.CHAT, moved, SettingOptions.<AlertStyle>builder().legacy("old-alerts", v -> "chat").build());
        s.register(old);
        insert(ALEX, "old-alerts", "true");
        s.load(ALEX).get(5, TimeUnit.SECONDS);
        assertTrue(s.get(ALEX, old));
        assertEquals(AlertStyle.OFF, s.get(ALEX, moved));
        assertEquals(Map.of("old-alerts", "true"), rows(ALEX));
    }

    @Test
    void offlinePlayersAreWrittenAndLookedUpInTheTable() throws Exception {
        assertEquals(SetResult.CHANGED, this.settings.set(SAM, PRIVACY, Audience.NOBODY, Change.admin("Mod")));
        assertEquals(Map.of("privacy", "nobody"), rows(SAM));
        assertEquals(Audience.EVERYONE, this.settings.get(SAM, PRIVACY), "nothing cached for players who are not loaded");
        assertFalse(this.settings.loaded(SAM));
        assertEquals(Audience.NOBODY, this.settings.lookup(SAM, PRIVACY).get(5, TimeUnit.SECONDS));
        assertEquals(Map.of("privacy", "nobody"), this.settings.stored(SAM).get(5, TimeUnit.SECONDS));
        this.settings.overrides(new Overrides(Map.of(), Map.of("privacy", "everyone"), Set.of()));
        assertEquals(Audience.EVERYONE, this.settings.lookup(SAM, PRIVACY).get(5, TimeUnit.SECONDS), "locks apply to lookups too");
        this.settings.overrides(Overrides.NONE);
        assertEquals(SetResult.CHANGED, this.settings.set(SAM, PRIVACY, Audience.EVERYONE, Change.admin("Mod")));
        assertEquals(Map.of(), rows(SAM), "the default deletes the row offline too");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.set(ALEX, PRIVACY, Audience.FRIENDS, Change.dialog("Alex"));
        assertEquals(Audience.FRIENDS, this.settings.lookup(ALEX, PRIVACY).get(5, TimeUnit.SECONDS), "loaded players come from memory");
        this.settings.forget(ALEX);
        assertFalse(this.settings.loaded(ALEX));
        assertEquals(Audience.FRIENDS, this.settings.lookup(ALEX, PRIVACY).get(5, TimeUnit.SECONDS), "and from the table after they left");
    }

    @Test
    void rawKeysAndRegisteredIds() throws Exception {
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.setRaw(ALEX, "orders_sort", "total");
        assertEquals("total", this.settings.raw(ALEX, "orders_sort", "newest"));
        this.settings.setRaw(ALEX, "receipts", "OFF");
        assertEquals(AlertStyle.OFF, this.settings.get(ALEX, RECEIPTS), "a registered id is validated and set");
        this.settings.setRaw(ALEX, "receipts", "chat");
        assertEquals(Map.of("orders_sort", "total"), rows(ALEX), "and follows the default rule");
        assertThrows(IllegalArgumentException.class, () -> this.settings.setRaw(ALEX, "receipts", "loud"));
        assertThrows(IllegalArgumentException.class, () -> this.settings.setRaw(ALEX, "x".repeat(33), "v"));
        assertEquals(SetResult.CHANGED, this.settings.setParsed(ALEX, "alerts", "toggle", Change.command("Alex")));
        assertFalse(this.settings.get(ALEX, ALERTS));
        assertEquals(SetResult.INVALID, this.settings.setParsed(ALEX, "volume", "55", Change.command("Alex")));
        assertEquals(SetResult.CHANGED, this.settings.setParsed(ALEX, "VOLUME", "50", Change.command("Alex")), "input keys work");
        assertEquals(SetResult.UNKNOWN, this.settings.setParsed(ALEX, "nothing", "1", Change.command("Alex")));
        assertEquals("50", this.settings.encoded(ALEX, "volume"));
        assertNull(this.settings.encoded(ALEX, "nothing"));
    }

    @Test
    void permissionsApplyToPlayers() throws Exception {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        Toggle spy = new Toggle("spy", false, LABEL, DESCRIPTION, "test.spy");
        Choice<AlertStyle> fancy = Choice.ofEnum("fancy", AlertStyle.class, AlertStyle::id, AlertStyle.CHAT)
            .option(AlertStyle.CHAT, LABEL).option(AlertStyle.TITLE, LABEL, "test.title", null).text(LABEL, DESCRIPTION).build();
        s.register(SettingCategories.STAFF, spy);
        s.register(SettingCategories.CHAT, fancy);
        insert(ALEX, "spy", "true");
        insert(ALEX, "fancy", "title");
        s.load(ALEX).get(5, TimeUnit.SECONDS);
        Set<String> nodes = new java.util.HashSet<>(Set.of("test.spy", "test.title"));
        Player alex = player(ALEX, nodes);
        assertTrue(s.get(alex, spy));
        assertEquals(AlertStyle.TITLE, s.get(alex, fancy));
        nodes.clear();
        assertFalse(s.get(alex, spy), "losing the permission loses the perk at once");
        assertEquals(AlertStyle.CHAT, s.get(alex, fancy), "and the option");
        assertTrue(s.get(ALEX, spy), "code reading by id still sees the stored value");
        assertEquals(SetResult.NOT_ALLOWED, s.set(alex, spy, false, Change.dialog("Alex")));
        assertEquals(SetResult.NOT_ALLOWED, s.set(alex, fancy, AlertStyle.TITLE, Change.dialog("Alex")));
        assertEquals(SetResult.NOT_ALLOWED, s.setParsed(alex, "fancy", "title", Change.command("Alex")));
        nodes.add("test.title");
        assertEquals(SetResult.UNCHANGED, s.set(alex, fancy, AlertStyle.TITLE, Change.dialog("Alex")));
        assertEquals(SetResult.CHANGED, s.set(alex, fancy, AlertStyle.CHAT, Change.dialog("Alex")));
    }

    @Test
    void instantHooksRunAfterAChangeOfAnOnlinePlayer() throws Exception {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        List<String> calls = new ArrayList<>();
        Toggle sidebar = new Toggle("sidebar", true, LABEL, DESCRIPTION, null);
        s.register(SettingCategories.DISPLAY, sidebar, SettingOptions.instant((player, before, after) ->
            calls.add(player.getName() + ":" + before + "->" + after)));
        Player alex = player(ALEX, Set.of());
        s.players(uuid -> uuid.equals(ALEX) ? alex : null);
        s.load(ALEX).get(5, TimeUnit.SECONDS);
        s.set(ALEX, sidebar, false);
        s.set(SAM, sidebar, false);
        assertEquals(List.of("Alex:true->false"), calls, "only online players");
        s.overrides(new Overrides(Map.of(), Map.of("sidebar", "true"), Set.of()));
        assertEquals(List.of("Alex:true->false", "Alex:false->true"), calls, "a lock that changes the value runs the hook too");
        s.reset(ALEX, List.of(sidebar), Change.reset("Alex"));
        assertEquals(2, calls.size(), "a locked setting is not reset");
    }

    @Test
    void hiddenSettingsReadTheServersValueAndKeepWhatPlayersStored() throws Exception {
        insert(ALEX, "volume", "30");
        insert(SAM, "volume", "40");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.overrides(new Overrides(Map.of(), Map.of(), Set.of("volume")));
        assertEquals(100L, this.settings.get(ALEX, VOLUME), "a hidden setting reads the code default, not the stored 30");
        assertFalse(this.settings.changed(ALEX, VOLUME), "nothing the player could see counts as changed");
        assertEquals("100", this.settings.encoded(ALEX, "volume"));
        assertEquals(100L, this.settings.lookup(SAM, VOLUME).get(5, TimeUnit.SECONDS), "offline lookups too");
        assertEquals(SetResult.NOT_ALLOWED, this.settings.set(ALEX, VOLUME, 50L, Change.feature()), "nobody can change it");
        assertEquals(SetResult.NOT_ALLOWED, this.settings.setParsed(ALEX, "volume", "50", Change.admin("Mod")));
        this.settings.overrides(new Overrides(Map.of("volume", "70"), Map.of(), Set.of("volume")));
        assertEquals(70L, this.settings.get(ALEX, VOLUME), "the server default");
        this.settings.overrides(new Overrides(Map.of("volume", "70"), Map.of("volume", "20"), Set.of("volume")));
        assertEquals(20L, this.settings.get(ALEX, VOLUME), "the lock");
        assertEquals(Map.of("volume", "30"), rows(ALEX), "the stored choice is kept");
        this.settings.overrides(Overrides.NONE);
        assertEquals(30L, this.settings.get(ALEX, VOLUME), "and comes back when the setting is shown again");
        assertTrue(this.settings.changed(ALEX, VOLUME));
    }

    @Test
    void hidingASettingRunsTheInstantHookOfPlayersWhoseValueChanges() throws Exception {
        PlayerSettings s = new PlayerSettings(this.database, null, LOGGER);
        List<String> calls = new ArrayList<>();
        Toggle sidebar = new Toggle("sidebar", true, LABEL, DESCRIPTION, null);
        s.register(SettingCategories.DISPLAY, sidebar, SettingOptions.instant((player, before, after) -> calls.add(before + "->" + after)));
        Player alex = player(ALEX, Set.of());
        s.players(uuid -> uuid.equals(ALEX) ? alex : null);
        insert(ALEX, "sidebar", "false");
        s.load(ALEX).get(5, TimeUnit.SECONDS);
        s.overrides(new Overrides(Map.of(), Map.of(), Set.of("sidebar")));
        assertEquals(List.of("false->true"), calls, "hidden: back to the default at once");
        s.overrides(Overrides.NONE);
        assertEquals(List.of("false->true", "true->false"), calls, "shown again: the player's choice at once");
    }

    @Test
    void aResetIsReportedAsAResetOutsideTheLockAndCanNotBeCancelled() throws Exception {
        insert(ALEX, "alerts", "false");
        insert(ALEX, "volume", "30");
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        List<PlayerSettings.Pending> seen = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        this.settings.listener(change -> {
            seen.add(change);
            try {
                // Another thread changing the same player must not wait for the reset (listeners are other plugins).
                java.util.concurrent.CompletableFuture.runAsync(() -> this.settings.setRaw(ALEX, "auction-sort", change.entry().id()))
                    .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                problems.add(change.entry().id() + ": " + e);
            }
            return false;
        });
        assertEquals(2, this.settings.reset(ALEX, List.of(ALERTS, VOLUME), Change.dialog("Alex")));
        assertEquals(List.of(), problems, "the listener ran outside the player's lock");
        assertEquals(2, seen.size());
        assertTrue(seen.stream().allMatch(p -> p.change().cause() == Change.Cause.RESET), "a reset is reported as RESET");
        assertTrue(seen.stream().allMatch(p -> p.change().actor().equals("Alex")), "with the caller's actor");
        assertTrue(this.settings.get(ALEX, ALERTS), "cancelling does not stop a reset");
        assertEquals(100L, this.settings.get(ALEX, VOLUME));
        assertFalse(rows(ALEX).containsKey("alerts"));
        assertFalse(rows(ALEX).containsKey("volume"));
    }

    @Test
    void aLoginReadThatComesAfterThePlayerLeftIsDropped() throws Exception {
        GatedDatabase gated = new GatedDatabase(this.database);
        PlayerSettings s = new PlayerSettings(gated, null, LOGGER);
        s.register(SettingCategories.SOUND, VOLUME);
        insert(ALEX, "volume", "30");
        java.util.concurrent.CompletableFuture<Void> load = s.load(ALEX);
        s.forget(ALEX);
        gated.open();
        load.get(5, TimeUnit.SECONDS);
        assertFalse(s.loaded(ALEX), "no values are kept for a player who is gone");
        assertEquals(30L, s.lookup(ALEX, VOLUME).get(5, TimeUnit.SECONDS), "and lookups read the table");
    }

    @Test
    void aReconnectThatReplacesTheSessionKeepsTheNewLoginsValues() throws Exception {
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.joined(ALEX);
        this.settings.set(ALEX, VOLUME, 40L, Change.dialog("Alex"));
        // The same player logs in again: the new login loads before the old session is kicked and quits.
        this.settings.load(ALEX).get(5, TimeUnit.SECONDS);
        this.settings.forget(ALEX);
        assertTrue(this.settings.loaded(ALEX), "the old session's quit leaves the new login's values");
        assertEquals(40L, this.settings.get(ALEX, VOLUME));
        this.settings.joined(ALEX);
        this.settings.forget(ALEX);
        assertFalse(this.settings.loaded(ALEX), "the new session's own quit forgets them");
    }

    @Test
    void aLateLoginReadKeepsWhatTheOnlinePlayerChangedMeanwhile() throws Exception {
        GatedDatabase gated = new GatedDatabase(this.database);
        PlayerSettings s = new PlayerSettings(gated, null, LOGGER);
        s.register(SettingCategories.CHAT, ALERTS);
        s.register(SettingCategories.SOUND, VOLUME);
        s.register(SettingCategories.ECONOMY, RECEIPTS);
        Player alex = player(ALEX, Set.of());
        s.players(uuid -> uuid.equals(ALEX) ? alex : null);
        insert(ALEX, "volume", "30");
        insert(ALEX, "alerts", "false");
        insert(ALEX, "receipts", "off");
        java.util.concurrent.CompletableFuture<Void> load = s.load(ALEX);
        // The database is slow: the player joined with defaults and changes two settings before the read comes.
        assertEquals(SetResult.CHANGED, s.set(ALEX, VOLUME, 50L, Change.dialog("Alex")));
        assertEquals(SetResult.UNCHANGED, s.set(ALEX, ALERTS, true, Change.dialog("Alex")), "on, as far as the player can see");
        gated.open();
        load.get(5, TimeUnit.SECONDS);
        assertEquals(50L, s.get(ALEX, VOLUME), "the change made meanwhile wins over the late read");
        assertTrue(s.get(ALEX, ALERTS), "and so does the value the player confirmed");
        assertEquals(AlertStyle.OFF, s.get(ALEX, RECEIPTS), "what they did not touch comes from the read");
        gated.flush();
        assertEquals(Map.of("volume", "50", "receipts", "off"), rows(ALEX), "the table agrees with what the player sees");
    }

    @Test
    void writesForAPlayerWhoseLoginIsQueuedReachTheirValues() throws Exception {
        GatedDatabase gated = new GatedDatabase(this.database);
        PlayerSettings s = new PlayerSettings(gated, null, LOGGER);
        s.register(SettingCategories.CHAT, ALERTS);
        s.register(SettingCategories.SOUND, VOLUME);
        insert(ALEX, "volume", "30");
        insert(ALEX, "alerts", "false");
        java.util.concurrent.CompletableFuture<Void> load = s.load(ALEX);
        // Staff change and reset the player's settings while their login read waits in the queue.
        assertEquals(0, s.reset(ALEX, List.of(VOLUME), Change.admin("Mod")), "not loaded yet");
        assertEquals(SetResult.CHANGED, s.set(ALEX, ALERTS, true, Change.admin("Mod")));
        gated.open();
        load.get(5, TimeUnit.SECONDS);
        gated.flush();
        assertEquals(Map.of(), rows(ALEX));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (s.get(ALEX, VOLUME) != 100L || !s.get(ALEX, ALERTS)) {
                Thread.onSpinWait();
            }
        }, "the loaded values follow the reset and the change: " + s.get(ALEX, VOLUME) + " " + s.get(ALEX, ALERTS));
    }

    /**
     * A database whose writer is held until {@link #open}: writes queue in order behind the gate (like a busy writer),
     * reads run at once.
     */
    private static final class GatedDatabase implements net.siftvanilla.siftcore.storage.Database {
        private final JdbcDatabase delegate;
        private final java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.CompletableFuture<?> tail;

        GatedDatabase(JdbcDatabase delegate) {
            this.delegate = delegate;
            this.tail = this.gate;
        }

        void open() {
            this.gate.complete(null);
        }

        @Override
        public net.siftvanilla.siftcore.storage.Dialect dialect() {
            return this.delegate.dialect();
        }

        @Override
        public synchronized <T> java.util.concurrent.CompletableFuture<T> write(net.siftvanilla.siftcore.storage.SqlWork<T> work) {
            java.util.concurrent.CompletableFuture<T> result = this.tail.thenCompose(ignored -> this.delegate.write(work));
            this.tail = result.handle((value, error) -> null);
            return result;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<T> read(net.siftvanilla.siftcore.storage.SqlWork<T> work) {
            return this.delegate.read(work);
        }

        @Override
        public int pendingWrites() {
            return this.delegate.pendingWrites();
        }

        @Override
        public long committedWrites() {
            return this.delegate.committedWrites();
        }

        @Override
        public void flush() {
            this.tail.join();
            this.delegate.flush();
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    /** A player stub: name, uuid, online, and permissions from {@code nodes}. */
    private static Player player(UUID uuid, Set<String> nodes) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> uuid.equals(ALEX) ? "Alex" : "Sam";
                case "isOnline" -> true;
                case "hasPermission" -> args[0] instanceof String node && nodes.contains(node);
                case "hashCode" -> uuid.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "Player(" + uuid + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
