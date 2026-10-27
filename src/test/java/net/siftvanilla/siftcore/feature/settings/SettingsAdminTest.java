package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.Sounds;
import net.siftvanilla.siftcore.testing.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code /sift settings <player> ...} changes, puts back, leaves alone and names, and in which order. */
class SettingsAdminTest {

    private static final UUID BOB = UUID.randomUUID();
    private static final int RUNS = 5;

    @TempDir
    Path dir;

    @Test
    void aResetTakesStoredRowsAndNamesTheLockedOnes() throws Exception {
        try (SettingsDb db = new SettingsDb(this.dir)) {
            Registry registry = db.settings.registry();
            List<Registry.Entry<?>> targets = List.of(registry.entry("sound-volume"), registry.entry("quiet-in-combat"),
                registry.entry("sound-clicks"), registry.entry("friends-tpa"));
            // An offline player: a volume, a locked setting's row, and the old TPA switch standing in for friends-tpa.
            Set<String> stored = LegacyRows.resolve(Map.of("sound-volume", "30", "quiet-in-combat", "true", "tpa-friends", "true",
                "auction-sort", "price"), registry).rows().keySet();
            StaffChanges.ResetPlan plan = StaffChanges.plan(targets, stored, setting -> setting.id().equals("quiet-in-combat"));
            assertEquals(List.of("sound-volume", "friends-tpa"), plan.ids(), "rows of their own or under an old id; nothing for unset ones");
            assertEquals(List.of("quiet-in-combat"), plan.locked(), "a locked setting is left alone and named");

            StaffChanges.ResetPlan none = StaffChanges.plan(targets, Set.of(), setting -> true);
            assertTrue(none.reset().isEmpty() && none.locked().isEmpty(), "nothing stored, nothing to say about locks");
        }
    }

    /** Staff tools on a fresh database: the changes, the audit log, and what staff were told. */
    private static final class Staff implements AutoCloseable {

        final SettingsDb db;
        final AuditLog audit;
        final Fakes.FakePlayer member = new Fakes.FakePlayer("Mod");
        final StaffChanges changes;

        Staff(Path dir) throws Exception {
            this.db = new SettingsDb(dir);
            this.audit = new AuditLog(this.db.database);
            this.changes = new StaffChanges(this.db.settings, this.db.lang, new Messenger(this.db.lang, new Sounds()), this.audit,
                SettingsDb.LOGGER, id -> "Bob", id -> null);
        }

        Registry.Entry<?> entry(String id) {
            return this.db.settings.registry().entry(id);
        }

        /** Waits for every step, then for the writes they queued (a read in the writer's order). */
        void settle() throws Exception {
            this.changes.idle().get(5, TimeUnit.SECONDS);
            this.db.row(BOB, "settle");
        }

        /** The settings audit rows of Bob, oldest first. */
        List<String> audited() throws Exception {
            List<String> lines = new ArrayList<>();
            for (AuditLog.Entry row : this.audit.recent("settings.", BOB.toString(), 50).get(5, TimeUnit.SECONDS).reversed()) {
                lines.add(row.action() + " " + row.details());
            }
            return lines;
        }

        @Override
        public void close() {
            this.db.close();
        }
    }

    @Test
    void backToBackChangesOfAnOfflinePlayerSeeEachOther() throws Exception {
        // Both commands in one tick (a console script): the second must read the value the first wrote.
        for (int run = 0; run < RUNS; run++) {
            try (Staff staff = new Staff(this.dir.resolve("set" + run))) {
                staff.changes.set(staff.member.player, BOB, staff.entry("sound-volume"), "50");
                staff.changes.set(staff.member.player, BOB, staff.entry("sound-volume"), "100");
                staff.settle();
                assertNull(staff.db.row(BOB, "sound-volume"), "back at the default: no row");
                assertEquals(List.of("settings.set sound-volume: 100 -> 50", "settings.set sound-volume: 50 -> 100"), staff.audited());
                assertEquals(List.of("Set sound-volume of Bob to 50 (was 100).", "Set sound-volume of Bob to 100 (was 50)."),
                    staff.member.said());
            }
        }
    }

    @Test
    void aResetRightAfterAChangeOfAnOfflinePlayerFindsIt() throws Exception {
        for (int run = 0; run < RUNS; run++) {
            try (Staff staff = new Staff(this.dir.resolve("reset" + run))) {
                Registry.Entry<?> volume = staff.entry("sound-volume");
                staff.changes.set(staff.member.player, BOB, volume, "30");
                staff.changes.reset(staff.member.player, BOB, List.of(volume));
                staff.settle();
                assertNull(staff.db.row(BOB, "sound-volume"), "the reset removed the row the change wrote");
                assertEquals(List.of("settings.set sound-volume: 100 -> 30", "settings.reset sound-volume"), staff.audited());
                assertEquals(List.of("Set sound-volume of Bob to 30 (was 100).", "Reset 1 settings of Bob."), staff.member.said());
            }
        }
    }

    @Test
    void aChangeRightAfterAResetOfAnOfflinePlayerComparesWithTheDefault() throws Exception {
        try (Staff staff = new Staff(this.dir)) {
            staff.db.insert(BOB, "sound-volume", "30");
            Registry.Entry<?> volume = staff.entry("sound-volume");
            staff.changes.reset(staff.member.player, BOB, List.of(volume));
            staff.changes.set(staff.member.player, BOB, volume, "30");
            staff.settle();
            assertEquals("30", staff.db.row(BOB, "sound-volume"), "set again after the reset");
            assertEquals(List.of("settings.reset sound-volume", "settings.set sound-volume: 100 -> 30"), staff.audited());
        }
    }

    @Test
    void changesOfALoadedPlayerSeeEachOtherToo() throws Exception {
        try (Staff staff = new Staff(this.dir)) {
            staff.db.join(BOB);
            staff.changes.set(staff.member.player, BOB, staff.entry("sound-volume"), "50");
            staff.changes.set(staff.member.player, BOB, staff.entry("sound-volume"), "50");
            staff.settle();
            assertEquals("50", staff.db.row(BOB, "sound-volume"));
            assertEquals(List.of("settings.set sound-volume: 100 -> 50"), staff.audited());
            assertEquals(List.of("Set sound-volume of Bob to 50 (was 100).", "sound-volume of Bob already is 50."), staff.member.said());
        }
    }
}
