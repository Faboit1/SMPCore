package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.storage.JdbcDatabase;
import net.siftvanilla.siftcore.storage.Migrations;
import net.siftvanilla.siftcore.storage.SqliteSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiles are stored through the migrations' table, survive a restart, keep nicknames unique and pass on stale ones. */
class ProfilesTest {

    private static final Logger LOGGER = Logger.getLogger("siftcore-test");
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID SAM = UUID.randomUUID();
    private static final long NOW = 1_790_000_000_000L;
    /** Every other holder still holds their nickname. */
    private static final Predicate<UUID> HELD = holder -> true;
    /** Every other holder's hold ran out. */
    private static final Predicate<UUID> EXPIRED = holder -> false;

    @TempDir
    Path dir;
    private JdbcDatabase database;

    @BeforeEach
    void open() throws Exception {
        this.database = new JdbcDatabase(new SqliteSource(this.dir.resolve("test.db"), 2), LOGGER);
    }

    private void migrate(int upTo) throws Exception {
        ClassLoader loader = ProfilesTest.class.getClassLoader();
        List<Migrations.Migration> all = Migrations.discover(loader::getResourceAsStream);
        new Migrations(this.database, LOGGER, loader::getResourceAsStream, all.stream().filter(m -> m.version() <= upTo).toList()).migrate();
    }

    @AfterEach
    void close() {
        this.database.close();
    }

    private Profiles reloaded() throws Exception {
        this.database.flush();
        Profiles profiles = new Profiles(this.database, LOGGER);
        profiles.load();
        return profiles;
    }

    @Test
    void everyChoiceSurvivesARestart() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = new Profiles(this.database, LOGGER);
        profiles.load();
        assertSame(Profile.EMPTY, profiles.get(ALEX));
        profiles.update(ALEX, p -> p.withChatStyle(ChatStyle.parse("#55FFFF:#5555FF")).withTag("spooky").withOwnedTag("spooky")
            .withJoinMessage("{name} rolls in").withLeaveMessage("bye").withKillEffect("totem").withNickLost("OldName"));
        assertTrue(profiles.setNick(ALEX, "Shadow", ChatStyle.parse("gold"), NOW, HELD).ok());
        Profile stored = reloaded().get(ALEX);
        assertEquals("#55FFFF:#5555FF", stored.chatStyle().serialize());
        assertEquals("Shadow", stored.nick());
        assertEquals("gold", stored.nickStyle().serialize());
        assertEquals(NOW, stored.nickSeen());
        assertEquals("OldName", stored.nickLost());
        assertEquals("spooky", stored.tag());
        assertEquals(Set.of("spooky"), stored.ownedTags());
        assertEquals("{name} rolls in", stored.joinMessage());
        assertEquals("bye", stored.leaveMessage());
        assertEquals("totem", stored.killEffect());
        assertEquals(1, reloaded().countRows().get());
    }

    @Test
    void nicknamesAreUniqueIgnoringCaseWhileHeld() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        assertTrue(profiles.setNick(ALEX, "Shadow", ChatStyle.NONE, NOW, HELD).ok());
        assertSame(Profiles.Claim.TAKEN, profiles.setNick(SAM, "SHADOW", ChatStyle.NONE, NOW, HELD), "taken");
        assertNull(profiles.get(SAM).nick());
        assertEquals(Optional.of(ALEX), profiles.nickOwner("shadow"));
        assertTrue(profiles.setNick(ALEX, "Night", ChatStyle.NONE, NOW, HELD).ok(), "renaming frees the old nickname");
        assertSame(Profiles.Claim.FREE, profiles.setNick(SAM, "Shadow", ChatStyle.NONE, NOW, HELD));
        Profiles after = reloaded();
        assertEquals(Optional.of(SAM), after.nickOwner("Shadow"));
        assertEquals(Optional.of(ALEX), after.nickOwner("night"));
        assertEquals(2, after.nickCount());
    }

    @Test
    void aNicknameWhoseHoldRanOutPassesToTheNewOwnerInOneStep() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        profiles.setNick(ALEX, "Shadow", ChatStyle.parse("aqua"), NOW, HELD);
        profiles.update(ALEX, p -> p.withTag("miner"));
        Profiles.Claim claim = profiles.setNick(SAM, "shadow", ChatStyle.parse("gold"), NOW + 5, EXPIRED);
        assertTrue(claim.ok());
        assertEquals(ALEX, claim.displaced());
        assertEquals("Shadow", claim.lost(), "the nickname as the old holder wrote it");
        Profile alex = profiles.get(ALEX);
        assertNull(alex.nick(), "the old holder lost it");
        assertEquals(0L, alex.nickSeen());
        assertEquals("Shadow", alex.nickLost(), "kept until they are told");
        assertEquals("aqua", alex.nickStyle().serialize(), "their colour stays for a new nickname");
        assertEquals("miner", alex.tag(), "nothing else changes");
        assertEquals("shadow", profiles.get(SAM).nick());
        Profiles after = reloaded();
        assertEquals(Optional.of(SAM), after.nickOwner("SHADOW"));
        assertEquals("Shadow", after.get(ALEX).nickLost());
        assertNull(after.get(ALEX).nick());
        assertEquals(1, after.nickCount());

        profiles.update(ALEX, p -> p.withNickLost(null).withTag(null));
        assertEquals("aqua", reloaded().get(ALEX).nickStyle().serialize(), "only the colour is left");
    }

    @Test
    void theHoldIsAskedOnlyAboutAnotherHolder() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        profiles.setNick(ALEX, "Shadow", ChatStyle.NONE, NOW, EXPIRED);
        Profiles.Claim again = profiles.setNick(ALEX, "SHADOW", ChatStyle.parse("gold"), NOW + 10, holder -> {
            throw new AssertionError("asked about " + holder);
        });
        assertSame(Profiles.Claim.FREE, again, "a player can recase or recolour their own nickname");
        assertEquals(NOW + 10, profiles.get(ALEX).nickSeen());
    }

    @Test
    void seeingAHolderRenewsTheHold() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        profiles.seeNick(ALEX, NOW);
        assertSame(Profile.EMPTY, profiles.get(ALEX), "nothing to renew without a nickname");
        profiles.setNick(ALEX, "Shadow", ChatStyle.NONE, NOW, HELD);
        profiles.seeNick(ALEX, NOW + 60_000);
        assertEquals(NOW + 60_000, reloaded().get(ALEX).nickSeen());
        profiles.seeNick(ALEX, NOW + 1_000);
        assertEquals(NOW + 60_000, profiles.get(ALEX).nickSeen(), "never goes back");
    }

    @Test
    void aStaffResetKeepsOwnedExclusivesAndReturnsWhatWasThere() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        profiles.setNick(ALEX, "Shadow", ChatStyle.parse("gold"), NOW, HELD);
        profiles.update(ALEX, p -> p.withTag("spooky").withOwnedTag("spooky").withJoinMessage("{name} rolls in").withKillEffect("hearts"));
        Profile before = profiles.reset(ALEX);
        assertEquals("Shadow", before.nick());
        assertEquals("nick=Shadow gold; tag=spooky; owned=spooky; join='{name} rolls in'; effect=hearts", before.describe(),
            "the audit entry can put it back");
        Profile after = profiles.get(ALEX);
        assertEquals(Set.of("spooky"), after.ownedTags(), "a monthly exclusive can't be picked again, so it stays");
        assertNull(after.nick());
        assertNull(after.tag());
        assertNull(after.joinMessage());
        assertNull(after.killEffect());
        assertTrue(profiles.nickOwner("Shadow").isEmpty(), "the nickname is free");
        assertEquals(Set.of("spooky"), reloaded().get(ALEX).ownedTags());
        assertEquals(1, reloaded().countRows().get());

        profiles.update(SAM, p -> p.withTag("miner"));
        profiles.reset(SAM);
        assertSame(Profile.EMPTY, profiles.get(SAM));
        assertEquals(1, reloaded().countRows().get(), "a reset without owned tags deletes the row");
    }

    @Test
    void anEmptiedProfileDeletesItsRow() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        profiles.update(ALEX, p -> p.withKillEffect("hearts"));
        assertEquals(1, reloaded().countRows().get());
        profiles.update(ALEX, p -> p.withKillEffect(null));
        assertEquals(0, reloaded().countRows().get());
    }

    @Test
    void nicknamesOnlyChangeThroughSetNick() throws Exception {
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        assertThrows(IllegalArgumentException.class, () -> profiles.update(ALEX, p -> p.withNick("Sneaky", ChatStyle.NONE, NOW)));
        assertTrue(profiles.nickOwner("Sneaky").isEmpty());
        assertFalse(profiles.nickCount() > 0);
    }

    @Test
    void nicknamesStoredBeforeHoldsCountFromTheirLastChange() throws Exception {
        migrate(110);
        this.database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO player_cosmetics (uuid, nick, nick_lower, updated) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, ALEX.toString());
                ps.setString(2, "Shadow");
                ps.setString(3, "shadow");
                ps.setLong(4, NOW - 1_000);
                ps.executeUpdate();
                ps.setString(1, SAM.toString());
                ps.setString(2, null);
                ps.setString(3, null);
                ps.setLong(4, NOW - 2_000);
                ps.executeUpdate();
            }
            return null;
        }).get();
        migrate(Integer.MAX_VALUE);
        Profiles profiles = reloaded();
        assertEquals(NOW - 1_000, profiles.get(ALEX).nickSeen(), "the hold starts at the row's last change");
        long samSeen = this.database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT nick_seen FROM player_cosmetics WHERE uuid = ?")) {
                ps.setString(1, SAM.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }).get();
        assertEquals(0L, samSeen, "no nickname, no hold");
    }
}
