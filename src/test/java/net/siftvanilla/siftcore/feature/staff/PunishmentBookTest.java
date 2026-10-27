package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentBookTest {

    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final long NOW = 1_700_000_000_000L;

    private static Punishment of(long id, PunishmentType type, UUID target, long created, Duration length) {
        return new Punishment(id, type, target, "x", "console", "Console", "", created, Punishment.endOf(created, length),
            Punishment.NONE, "");
    }

    @Test
    void activeLookupIgnoresEndedOnes() {
        PunishmentBook book = new PunishmentBook();
        book.put(of(1, PunishmentType.MUTE, BOB, NOW, Duration.ofMinutes(10)));
        assertTrue(book.active(PunishmentType.MUTE, BOB, NOW + 1).isPresent());
        assertFalse(book.active(PunishmentType.BAN, BOB, NOW + 1).isPresent());
        assertFalse(book.active(PunishmentType.MUTE, BOB, NOW + Duration.ofMinutes(10).toMillis()).isPresent());
        assertFalse(book.active(PunishmentType.MUTE, EVE, NOW).isPresent());
    }

    @Test
    void newPunishmentReplacesTheOldOne() {
        PunishmentBook book = new PunishmentBook();
        Punishment first = of(1, PunishmentType.BAN, BOB, NOW, Duration.ofDays(1));
        Punishment second = of(2, PunishmentType.BAN, BOB, NOW + 5, null);
        assertTrue(book.put(first).isEmpty());
        assertEquals(first, book.put(second).orElseThrow());
        assertEquals(2, book.active(PunishmentType.BAN, BOB, NOW + 10).orElseThrow().id());
        assertEquals(1, book.count(PunishmentType.BAN, NOW + 10));
    }

    @Test
    void expiringRemovesOnlyEndedOnes() {
        PunishmentBook book = new PunishmentBook();
        book.put(of(1, PunishmentType.MUTE, BOB, NOW, Duration.ofSeconds(30)));
        book.put(of(2, PunishmentType.MUTE, EVE, NOW, null));
        book.put(of(3, PunishmentType.BAN, BOB, NOW, Duration.ofHours(1)));
        List<Punishment> ended = book.expire(NOW + Duration.ofMinutes(1).toMillis());
        assertEquals(1, ended.size());
        assertEquals(1, ended.getFirst().id());
        assertEquals(1, book.count(PunishmentType.MUTE, NOW + Duration.ofMinutes(1).toMillis()));
        assertEquals(1, book.count(PunishmentType.BAN, NOW + Duration.ofMinutes(1).toMillis()));
        assertTrue(book.expire(NOW + Duration.ofMinutes(2).toMillis()).isEmpty());
    }

    @Test
    void removeReturnsWhatWasThere() {
        PunishmentBook book = new PunishmentBook();
        book.put(of(1, PunishmentType.MUTE, BOB, NOW, null));
        assertEquals(1, book.remove(PunishmentType.MUTE, BOB).orElseThrow().id());
        assertTrue(book.remove(PunishmentType.MUTE, BOB).isEmpty());
    }

    @Test
    void loadKeepsTheNewestActiveOnePerPlayer() {
        PunishmentBook book = new PunishmentBook();
        Punishment old = of(1, PunishmentType.BAN, BOB, NOW - 1000, null);
        Punishment newer = of(2, PunishmentType.BAN, BOB, NOW - 10, Duration.ofDays(1));
        Punishment ended = of(3, PunishmentType.MUTE, EVE, NOW - Duration.ofDays(2).toMillis(), Duration.ofDays(1));
        Punishment lifted = of(4, PunishmentType.MUTE, BOB, NOW - 5, null).lift(NOW - 1, "Alice");
        Punishment warning = new Punishment(5, PunishmentType.WARN, EVE, "x", "console", "Console", "", NOW, 0, 0, "");
        book.load(List.of(newer, old, ended, lifted, warning), NOW);
        assertEquals(2, book.active(PunishmentType.BAN, BOB, NOW).orElseThrow().id());
        assertFalse(book.active(PunishmentType.MUTE, EVE, NOW).isPresent());
        assertFalse(book.active(PunishmentType.MUTE, BOB, NOW).isPresent());
        assertEquals(1, book.count(PunishmentType.BAN, NOW));
        assertEquals(0, book.count(PunishmentType.MUTE, NOW));
    }

    @Test
    void onlyLastingTypesAreKept() {
        PunishmentBook book = new PunishmentBook();
        assertThrows(IllegalArgumentException.class, () -> book.active(PunishmentType.KICK, BOB, NOW));
    }
}
