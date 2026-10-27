package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.link.MuteStatus;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The staff tables (V090) and the punishment service against a real migrated SQLite database. */
class StaffStorageTest {

    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Logger LOGGER = Logger.getLogger("staff-test");

    @TempDir
    Path dir;

    private JdbcDatabase database;
    private StaffStore store;
    private Punishments punishments;

    @BeforeEach
    void setUp() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("staff.db"), 2), LOGGER);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.store = new StaffStore(this.database, LOGGER);
        this.store.open();
        this.punishments = punishments();
    }

    /** A fresh service loaded from storage, like after a restart (which flushes every write first). */
    private Punishments punishments() throws Exception {
        this.database.flush();
        Lang lang = new Lang(new TextStyle(Palette.defaults(), new Icons(Set.of())), () -> null);
        StaffSettings settings = new StaffSettings(Duration.ofSeconds(3), Duration.ofSeconds(2), Set.of("msg"), false, null,
            "Logged out while frozen", Set.of("me"), "appeal", Duration.ofDays(3650),
            new ReportRules.Limits(3, 100, 5, Duration.ofSeconds(60)), 6, 6, 100, StaffSettings.Hierarchy.DEFAULT);
        Punishments service = new Punishments(this.store, new AuditLog(this.database), new Messenger(lang, new Sounds()),
            new StaffText(lang), () -> settings, LOGGER);
        service.load();
        return service;
    }

    @AfterEach
    void tearDown() {
        this.database.close();
    }

    @Test
    void banReplaceLiftAndReload() throws Exception {
        UUID modId = UUID.randomUUID();
        Actor mod = new Actor(modId.toString(), "Mod", modId);
        assertFalse(mod.isConsole());
        Punishments.Issued first = this.punishments.issue(PunishmentType.BAN, BOB, "Bob", mod, "xray", Duration.ofDays(7), true);
        assertNull(first.replaced());
        assertTrue(this.punishments.activeBan(BOB).isPresent());
        assertNull(this.punishments.checkStorage().get());

        Punishments.Issued second = this.punishments.issue(PunishmentType.BAN, BOB, "Bob", Actor.console(), "", null, true);
        assertEquals(first.punishment().id(), second.replaced().id());
        assertTrue(this.punishments.activeBan(BOB).orElseThrow().permanent());
        assertNull(this.punishments.checkStorage().get());

        // A restart sees the same state.
        assertTrue(punishments().activeBan(BOB).orElseThrow().permanent());

        assertTrue(this.punishments.lift(PunishmentType.BAN, BOB, mod).isPresent());
        assertTrue(this.punishments.lift(PunishmentType.BAN, BOB, mod).isEmpty(), "lifting twice does nothing");
        assertTrue(this.punishments.activeBan(BOB).isEmpty());
        assertNull(this.punishments.checkStorage().get());
        assertTrue(punishments().activeBan(BOB).isEmpty());

        List<Punishment> history = this.store.history(BOB, 10).get();
        assertEquals(2, history.size());
        long now = System.currentTimeMillis();
        assertEquals(second.punishment().id(), history.get(0).id(), "newest first");
        assertEquals(PunishmentState.LIFTED, history.get(0).state(now));
        assertEquals("Mod", history.get(0).revokedBy());
        assertEquals(PunishmentState.LIFTED, history.get(1).state(now));
        assertEquals("Console", history.get(1).revokedBy());
        assertEquals("xray", history.get(1).reason());
        assertEquals(Duration.ofDays(7), history.get(1).length());
        assertEquals("console", history.get(0).staff());
    }

    @Test
    void mutesAreVisibleThroughMuteStatus() throws Exception {
        MuteStatus status = this.punishments;
        assertTrue(status.mute(EVE).isEmpty());
        this.punishments.issue(PunishmentType.MUTE, EVE, "Eve", Actor.console(), "caps", Duration.ofHours(1), true);
        MuteStatus.Mute mute = status.mute(EVE).orElseThrow();
        assertEquals("caps", mute.reason());
        assertFalse(mute.permanent());
        assertTrue(mute.until() > System.currentTimeMillis() + Duration.ofMinutes(59).toMillis());
        this.punishments.issue(PunishmentType.MUTE, EVE, "Eve", Actor.console(), "", null, true);
        assertTrue(status.mute(EVE).orElseThrow().permanent());
        assertNull(this.punishments.checkStorage().get());
        this.punishments.lift(PunishmentType.MUTE, EVE, Actor.console());
        assertTrue(status.mute(EVE).isEmpty());
    }

    @Test
    void expiredBansDoNotCountAndDoNotLoad() throws Exception {
        this.punishments.issue(PunishmentType.BAN, BOB, "Bob", Actor.console(), "short", Duration.ofMillis(1500), true);
        assertTrue(this.punishments.activeBan(BOB).isPresent());
        Thread.sleep(1700);
        assertTrue(this.punishments.activeBan(BOB).isEmpty());
        assertNull(this.punishments.checkStorage().get());
        assertTrue(punishments().activeBan(BOB).isEmpty());
        assertEquals(PunishmentState.EXPIRED, this.store.history(BOB, 1).get().getFirst().state(System.currentTimeMillis()));
    }

    @Test
    void warningsGivenOfflineAreShownOnce() throws Exception {
        this.punishments.issue(PunishmentType.WARN, BOB, "Bob", Actor.console(), "language", null, false);
        this.punishments.issue(PunishmentType.WARN, BOB, "Bob", Actor.console(), "seen already", null, true);
        this.punishments.issue(PunishmentType.KICK, BOB, "Bob", Actor.console(), "", null, true);
        this.database.flush();
        List<Punishment> unseen = this.store.unseenWarnings(BOB).get();
        assertEquals(1, unseen.size());
        assertEquals("language", unseen.getFirst().reason());
        this.store.markNotified(List.of(unseen.getFirst().id())).get();
        assertTrue(this.store.unseenWarnings(BOB).get().isEmpty());
        assertEquals(3, this.store.history(BOB, 10).get().size());
    }

    @Test
    void reportsOpenAndCloseOnce() throws Exception {
        Report report = new Report(this.store.nextReportId(), EVE, "Eve", BOB, "Bob", "fly hacks", 10L, ReportState.OPEN, "", 0L);
        this.store.insert(report).get();
        assertEquals(1, this.store.openReports().get().size());
        assertEquals(1, this.store.countOpenReports().get());
        Report handled = report.close(ReportState.HANDLED, "Mod", 20L);
        assertTrue(this.store.close(handled).get());
        assertFalse(this.store.close(report.close(ReportState.DISMISSED, "Other", 21L)).get(), "already closed");
        assertTrue(this.store.openReports().get().isEmpty());
        assertEquals(0, this.store.countOpenReports().get());
        assertTrue(this.store.nextReportId() > report.id());
    }

    @Test
    void vanishAndFreezeSurviveRestarts() throws Exception {
        this.store.vanish(BOB, 5L).get();
        this.store.vanish(BOB, 6L).get();
        this.store.freeze(new StaffStore.Freeze(EVE, "console", "Console", 7L)).get();
        assertEquals(Set.of(BOB), this.store.vanished().get());
        assertEquals(1, this.store.countVanished().get());
        Map<UUID, StaffStore.Freeze> frozen = this.store.frozen().get();
        assertEquals("Console", frozen.get(EVE).staffName());
        this.store.unvanish(BOB).get();
        this.store.unfreeze(EVE).get();
        assertTrue(this.store.vanished().get().isEmpty());
        assertEquals(0, this.store.countFrozen().get());
    }

    @Test
    void accountsSharingAnAddress() throws Exception {
        UUID alt = UUID.randomUUID();
        insertPlayer(BOB, "Bob", "hash-a", 100L);
        insertPlayer(alt, "BobAlt", "hash-a", 300L);
        insertPlayer(EVE, "Eve", "hash-b", 200L);
        List<PlayerDirectory.Known> rows = this.store.sharingAddress("hash-a", 10).get();
        assertEquals(2, rows.size());
        assertEquals("BobAlt", rows.getFirst().name(), "most recently seen first");
        assertEquals(1, this.store.sharingAddress("hash-a", 1).get().size());
        assertTrue(this.store.sharingAddress("hash-c", 10).get().isEmpty());
    }

    private void insertPlayer(UUID uuid, String name, String hash, long lastSeen) throws Exception {
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO players (uuid, name, name_lower, first_join, last_seen, ip_hash) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setLong(4, 1L);
                ps.setLong(5, lastSeen);
                ps.setString(6, hash);
                ps.executeUpdate();
            }
            return null;
        }).get();
    }
}
