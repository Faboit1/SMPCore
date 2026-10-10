package net.siftvanilla.siftcore.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Last seen: players who leave get it on quit, and the players still online when the server stops (who get no quit
 * event) get it in one write before storage closes, so /seen, friends and teams show when they left, not when they
 * joined.
 */
class PlayerDirectoryTest {

    private static final Logger LOGGER = Logger.getLogger("directory-test");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID BLAKE = UUID.randomUUID();
    private static final UUID NOBODY = UUID.randomUUID();

    @TempDir
    Path dir;
    private JdbcDatabase database;
    private PlayerDirectory directory;

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
        ClassLoader loader = getClass().getClassLoader();
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, Migrations.discover(loader::getResourceAsStream)).migrate();
        this.directory = new PlayerDirectory(this.database, new byte[16]);
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private long storedLastSeen(UUID player) throws Exception {
        this.database.flush();
        return this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }).get();
    }

    private void backdate(UUID player, long lastSeen) throws Exception {
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE players SET last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, lastSeen);
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
            return null;
        }).get();
        this.directory.load();
    }

    /** Dupe audit R17: one person's accounts on addresses of one IPv6 /64 are one connection; IPv4 is per address. */
    @Test
    void connectionsGroupAnIpv6NetworkAndKeepIpv4AddressesApart() {
        this.directory.recordJoin(ALEX, "Alex", "2001:db8:1:2:aaaa::1");
        this.directory.recordJoin(BLAKE, "Blake", "2001:db8:1:2:bbbb::7%eth0");
        org.junit.jupiter.api.Assertions.assertEquals(this.directory.connection(ALEX), this.directory.connection(BLAKE),
            "same /64, same connection");
        org.junit.jupiter.api.Assertions.assertNotEquals(this.directory.ipHash(ALEX), this.directory.ipHash(BLAKE),
            "staff tools still see two addresses");
        this.directory.recordJoin(BLAKE, "Blake", "2001:db8:1:3::1");
        org.junit.jupiter.api.Assertions.assertNotEquals(this.directory.connection(ALEX), this.directory.connection(BLAKE),
            "another /64 is another connection");
        this.directory.recordJoin(ALEX, "Alex", "203.0.113.5");
        this.directory.recordJoin(BLAKE, "Blake", "::ffff:203.0.113.5");
        org.junit.jupiter.api.Assertions.assertEquals(this.directory.connection(ALEX), this.directory.connection(BLAKE),
            "an IPv4-mapped address is the IPv4 address");
        this.directory.recordJoin(BLAKE, "Blake", "203.0.113.6");
        org.junit.jupiter.api.Assertions.assertNotEquals(this.directory.connection(ALEX), this.directory.connection(BLAKE));
        org.junit.jupiter.api.Assertions.assertEquals("player:" + NOBODY, this.directory.connection(NOBODY), "unknown: their own");
        org.junit.jupiter.api.Assertions.assertNull(PlayerDirectory.network("example.org"), "names are never looked up");
    }

    @Test
    void playersOnlineAtShutdownAreSeenWhenTheServerStops() throws Exception {
        this.directory.recordJoin(ALEX, "Alex", null);
        this.directory.recordJoin(BLAKE, "Blake", null);
        // They joined an hour ago and are still online when the server stops.
        long joined = System.currentTimeMillis() - 3_600_000L;
        backdate(ALEX, joined);
        backdate(BLAKE, joined);
        assertEquals(joined, this.directory.get(ALEX).orElseThrow().lastSeen());

        long before = System.currentTimeMillis();
        this.directory.recordQuitAll(List.of(ALEX, BLAKE, NOBODY));
        long now = this.directory.get(ALEX).orElseThrow().lastSeen();
        assertTrue(now >= before, "in memory at once");
        assertEquals(now, this.directory.get(BLAKE).orElseThrow().lastSeen(), "everyone in the same write");
        assertEquals(now, storedLastSeen(ALEX), "and stored");
        assertEquals(now, storedLastSeen(BLAKE));
        assertEquals(-1L, storedLastSeen(NOBODY), "a player the directory never saw gets no row");
    }

    @Test
    void aQuitStillRecordsOnePlayer() throws Exception {
        this.directory.recordJoin(ALEX, "Alex", null);
        this.directory.recordJoin(BLAKE, "Blake", null);
        long joined = System.currentTimeMillis() - 60_000L;
        backdate(ALEX, joined);
        backdate(BLAKE, joined);
        this.directory.recordQuit(ALEX);
        assertTrue(storedLastSeen(ALEX) > joined);
        assertEquals(joined, storedLastSeen(BLAKE), "the others are untouched");
    }

    @Test
    void theTimeBeforeThisSessionIsKeptUntilTheyLeave() throws Exception {
        this.directory.recordJoin(ALEX, "Alex", null);
        long earlier = System.currentTimeMillis() - 86_400_000L;
        backdate(ALEX, earlier);
        this.directory.recordJoin(ALEX, "Alex", null);
        assertEquals(earlier, this.directory.previousSeen(ALEX));
        this.directory.recordQuitAll(List.of(ALEX));
        assertEquals(0L, this.directory.previousSeen(ALEX), "forgotten once they are gone");
    }

    @Test
    void nobodyOnlineWritesNothing() throws Exception {
        this.directory.recordJoin(ALEX, "Alex", null);
        this.database.flush();
        long writes = this.database.committedWrites();
        this.directory.recordQuitAll(List.of());
        this.directory.recordQuitAll(List.of(NOBODY));
        this.database.flush();
        assertEquals(writes + 1, this.database.committedWrites(), "only the flush itself was written");
    }
}
