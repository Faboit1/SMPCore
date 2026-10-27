package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The punishment state machine: active until it expires or is lifted; kicks and warnings are plain records. */
class PunishmentTest {

    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long T0 = 1_700_000_000_000L;

    private static Punishment lasting(PunishmentType type, Duration length) {
        return new Punishment(1, type, TARGET, "Bob", "console", "Console", "spam", T0, Punishment.endOf(T0, length),
            Punishment.NONE, "");
    }

    @Test
    void temporaryBanIsActiveUntilItsEnd() {
        Punishment ban = lasting(PunishmentType.BAN, Duration.ofHours(2));
        assertEquals(PunishmentState.ACTIVE, ban.state(T0));
        assertEquals(PunishmentState.ACTIVE, ban.state(T0 + Duration.ofHours(2).toMillis() - 1));
        assertEquals(PunishmentState.EXPIRED, ban.state(T0 + Duration.ofHours(2).toMillis()));
        assertEquals(PunishmentState.EXPIRED, ban.state(T0 + Duration.ofDays(30).toMillis()));
        assertEquals(Duration.ofHours(1), ban.remaining(T0 + Duration.ofHours(1).toMillis()));
        assertEquals(Duration.ZERO, ban.remaining(T0 + Duration.ofHours(3).toMillis()));
        assertEquals(Duration.ofHours(2), ban.length());
        assertFalse(ban.permanent());
    }

    @Test
    void permanentMuteNeverExpires() {
        Punishment mute = lasting(PunishmentType.MUTE, null);
        assertTrue(mute.permanent());
        assertEquals(Punishment.PERMANENT, mute.expires());
        assertEquals(PunishmentState.ACTIVE, mute.state(Long.MAX_VALUE - 1));
        assertNull(mute.length());
        assertEquals(Duration.ZERO, mute.remaining(T0));
    }

    @Test
    void liftingEndsItForGoodAndKeepsTheRecord() {
        Punishment ban = lasting(PunishmentType.BAN, Duration.ofDays(1));
        Punishment lifted = ban.lift(T0 + 1000, "Alice");
        assertEquals(PunishmentState.LIFTED, lifted.state(T0 + 2000));
        assertEquals(PunishmentState.LIFTED, lifted.state(T0 + Duration.ofDays(2).toMillis()));
        assertFalse(lifted.active(T0 + 2000));
        assertEquals("Alice", lifted.revokedBy());
        assertEquals(ban.id(), lifted.id());
        assertEquals(ban.expires(), lifted.expires());
        assertEquals(PunishmentState.ACTIVE, ban.state(T0 + 2000), "the original record is immutable");
        // A permanent ban can be lifted too.
        assertEquals(PunishmentState.LIFTED, lasting(PunishmentType.BAN, null).lift(T0, "Alice").state(T0));
    }

    @Test
    void kicksAndWarningsAreRecordsOnly() {
        Punishment kick = new Punishment(2, PunishmentType.KICK, TARGET, "Bob", "console", "Console", "", T0, 12345L, 0L, "");
        assertEquals(PunishmentState.RECORD, kick.state(T0));
        assertEquals(Punishment.NONE, kick.expires(), "kicks never carry an end time");
        assertFalse(kick.active(T0));
        assertFalse(kick.permanent());
        assertNull(kick.length());
        assertThrows(IllegalStateException.class, () -> kick.lift(T0, "Alice"));
        Punishment warn = new Punishment(3, PunishmentType.WARN, TARGET, "Bob", "console", "Console", "rude", T0, 0L, 0L, "");
        assertEquals(PunishmentState.RECORD, warn.state(T0 + 1));
        assertTrue(warn.hasReason());
        assertFalse(kick.hasReason());
    }

    @Test
    void lastingPunishmentsNeedAnEnd() {
        assertThrows(IllegalArgumentException.class, () ->
            new Punishment(4, PunishmentType.BAN, TARGET, "Bob", "console", "Console", "", T0, 0L, 0L, ""));
        assertThrows(IllegalArgumentException.class, () -> Punishment.endOf(T0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Punishment.endOf(T0, Duration.ofSeconds(-5)));
    }

    @Test
    void endTimesNeverOverflow() {
        assertEquals(Punishment.PERMANENT, Punishment.endOf(T0, null));
        assertEquals(Punishment.PERMANENT, Punishment.endOf(Long.MAX_VALUE - 10, Duration.ofDays(1)));
        assertEquals(T0 + 60_000, Punishment.endOf(T0, Duration.ofMinutes(1)));
    }

    @Test
    void typesParseFromStorage() {
        assertEquals(PunishmentType.BAN, PunishmentType.parse("BAN"));
        assertEquals(PunishmentType.WARN, PunishmentType.parse(" warn "));
        assertNull(PunishmentType.parse("JAIL"));
        assertNull(PunishmentType.parse(null));
        assertTrue(PunishmentType.MUTE.lasting());
        assertFalse(PunishmentType.KICK.lasting());
    }
}
